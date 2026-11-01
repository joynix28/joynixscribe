#![cfg_attr(not(debug_assertions), windows_subsystem = "windows")]

mod audio;
mod providers;
mod store;

use audio::{encode_wav, Capture};
use providers::{Settings, Transcription};
use serde::Serialize;
use std::sync::atomic::{AtomicBool, AtomicIsize, Ordering};
use std::sync::Mutex;
use tauri::menu::{Menu, MenuItem};
use tauri::tray::{MouseButton, MouseButtonState, TrayIconBuilder, TrayIconEvent};
use tauri::{Emitter, LogicalSize, Manager, State};
use tauri_plugin_global_shortcut::{Code, GlobalShortcutExt, Modifiers, Shortcut, ShortcutState};

struct AppState {
    capture: Mutex<Capture>,
    settings: Mutex<Settings>,
}

static HOTKEY_OK: AtomicBool = AtomicBool::new(false);
/// Dernière fenêtre d'une autre application au premier plan : sert à réinsérer
/// le texte là où l'utilisateur travaillait, pas dans JoynixScribe.
static LAST_FOREIGN_WINDOW: AtomicIsize = AtomicIsize::new(0);

#[cfg(target_os = "windows")]
fn track_foreground_window() {
    use windows_sys::Win32::System::Threading::GetCurrentProcessId;
    use windows_sys::Win32::UI::WindowsAndMessaging::{GetForegroundWindow, GetWindowThreadProcessId};

    std::thread::spawn(|| loop {
        unsafe {
            let hwnd = GetForegroundWindow();
            if !hwnd.is_null() {
                let mut pid: u32 = 0;
                GetWindowThreadProcessId(hwnd, &mut pid);
                if pid != GetCurrentProcessId() {
                    LAST_FOREIGN_WINDOW.store(hwnd as isize, Ordering::Relaxed);
                }
            }
        }
        std::thread::sleep(std::time::Duration::from_millis(350));
    });
}

#[cfg(not(target_os = "windows"))]
fn track_foreground_window() {}

fn focus_previous_window() {
    #[cfg(target_os = "windows")]
    {
        use windows_sys::Win32::UI::WindowsAndMessaging::{IsWindow, SetForegroundWindow};
        let raw = LAST_FOREIGN_WINDOW.load(Ordering::Relaxed);
        if raw != 0 {
            let hwnd = raw as *mut core::ffi::c_void;
            unsafe {
                if IsWindow(hwnd) != 0 {
                    SetForegroundWindow(hwnd);
                    std::thread::sleep(std::time::Duration::from_millis(180));
                }
            }
        }
    }
}

#[derive(Serialize)]
struct MicInfo {
    has_mic: bool,
    devices: Vec<String>,
    platform: String,
    hotkey_ok: bool,
}

#[derive(Serialize)]
struct RecordStatus {
    level: f32,
    seconds: f32,
    running: bool,
}

#[derive(Serialize)]
struct Snapshot {
    wav_b64: String,
    rms: f32,
    seconds: f32,
}

#[derive(Serialize)]
struct TranscribeResult {
    text: String,
    provider: String,
    model: String,
    latency_ms: u64,
    fallback: bool,
    history: Vec<store::HistoryEntry>,
    used_today: u32,
    daily_limit: u32,
}

#[derive(Serialize)]
struct Stopped {
    wav_b64: String,
    seconds: f32,
    total_seconds: f32,
}

fn normalize(settings: &mut Settings) {
    let today = store::today();
    if settings.usage_day != today {
        settings.usage_day = today;
        settings.used_today = 0;
    }
    if settings.provider.is_empty() {
        settings.provider = "Gemini".into();
    }
}

#[tauri::command]
fn mic_info() -> MicInfo {
    use cpal::traits::{DeviceTrait, HostTrait};
    let host = cpal::default_host();
    let has_mic = host.default_input_device().is_some();
    let devices = host
        .input_devices()
        .map(|it| it.filter_map(|d| d.name().ok()).collect())
        .unwrap_or_default();
    MicInfo {
        has_mic,
        devices,
        platform: std::env::consts::OS.to_string(),
        hotkey_ok: HOTKEY_OK.load(Ordering::Relaxed),
    }
}

#[tauri::command]
fn record_start(state: State<AppState>) -> Result<(), String> {
    let mut settings = state.settings.lock().map_err(|e| e.to_string())?.clone();
    normalize(&mut settings);
    if settings.daily_limit > 0 && settings.used_today >= settings.daily_limit {
        return Err(format!(
            "Limite journalière atteinte ({} dictées). Modifie la limite dans les réglages.",
            settings.daily_limit
        ));
    }
    let mut capture = state.capture.lock().map_err(|e| e.to_string())?;
    capture.start()
}

#[tauri::command]
fn record_status(state: State<AppState>) -> RecordStatus {
    let capture = match state.capture.lock() {
        Ok(c) => c,
        Err(_) => return RecordStatus { level: 0.0, seconds: 0.0, running: false },
    };
    RecordStatus {
        level: capture.level(),
        seconds: capture.duration_secs(),
        running: capture.is_running(),
    }
}

#[tauri::command]
fn record_stop(state: State<AppState>) -> Result<Stopped, String> {
    use base64::Engine;
    let mut capture = state.capture.lock().map_err(|e| e.to_string())?;
    let rate = capture.sample_rate();
    let committed = capture.committed();
    let samples = capture.stop();
    if samples.len() < (rate as usize / 5) {
        return Err("Enregistrement trop court : parle au moins une seconde.".into());
    }
    let total_seconds = samples.len() as f32 / rate as f32;
    // En streaming, l'audio déjà transcrit ne doit pas être renvoyé une 2e fois.
    let tail = samples.get(committed.min(samples.len())..).unwrap_or(&[]);
    let wav = encode_wav(tail, rate);
    Ok(Stopped {
        wav_b64: base64::engine::general_purpose::STANDARD.encode(&wav),
        seconds: tail.len() as f32 / rate as f32,
        total_seconds,
    })
}

#[tauri::command]
fn record_cancel(state: State<AppState>) -> Result<(), String> {
    let mut capture = state.capture.lock().map_err(|e| e.to_string())?;
    let _ = capture.stop();
    Ok(())
}

#[tauri::command]
fn record_snapshot(state: State<AppState>) -> Result<Snapshot, String> {
    use base64::Engine;
    let capture = state.capture.lock().map_err(|e| e.to_string())?;
    let rate = capture.sample_rate();
    let (samples, level) = capture.snapshot(0.35);
    if samples.len() < (rate as usize / 2) {
        return Ok(Snapshot { wav_b64: String::new(), rms: level, seconds: 0.0 });
    }
    let seconds = samples.len() as f32 / rate as f32;
    let wav = encode_wav(&samples, rate);
    Ok(Snapshot {
        wav_b64: base64::engine::general_purpose::STANDARD.encode(&wav),
        rms: level,
        seconds,
    })
}

#[tauri::command]
async fn transcribe(
    state: State<'_, AppState>,
    settings: Settings,
    wav_b64: String,
    duration_secs: f32,
    app_hint: String,
) -> Result<TranscribeResult, String> {
    use base64::Engine;
    if wav_b64.is_empty() {
        return Err("Audio vide.".into());
    }
    let wav = base64::engine::general_purpose::STANDARD
        .decode(&wav_b64)
        .map_err(|e| format!("Audio illisible : {e}"))?;

    let mut settings = settings;
    // Le compteur du serveur fait foi : le formulaire peut être en retard.
    if let Ok(guard) = state.settings.lock() {
        settings.used_today = guard.used_today;
        settings.usage_day = guard.usage_day.clone();
    }
    normalize(&mut settings);

    let result: Transcription = providers::transcribe(&settings, &wav).await?;

    settings.used_today = settings.used_today.saturating_add(1);
    if let Ok(mut guard) = state.settings.lock() {
        *guard = settings.clone();
    }
    let _ = store::save_settings(&settings);

    let entry = store::HistoryEntry {
        id: format!("{}-{}", store::now_millis(), settings.used_today),
        text: result.text.clone(),
        provider: result.provider.clone(),
        model: result.model.clone(),
        duration_secs,
        created_at: store::now_millis(),
        app_hint,
    };
    let history = store::add_history(entry);

    Ok(TranscribeResult {
        text: result.text,
        provider: result.provider,
        model: result.model,
        latency_ms: result.latency_ms,
        fallback: result.fallback,
        history,
        used_today: settings.used_today,
        daily_limit: settings.daily_limit,
    })
}

#[tauri::command]
async fn test_key(settings: Settings) -> Result<String, String> {
    providers::test_key(&settings).await
}

#[tauri::command]
fn load_settings(state: State<AppState>) -> Settings {
    let mut s = store::load_settings();
    normalize(&mut s);
    if let Ok(mut guard) = state.settings.lock() {
        *guard = s.clone();
    }
    s
}

#[tauri::command]
fn save_settings(state: State<AppState>, settings: Settings) -> Result<Settings, String> {
    let mut s = settings;
    if let Ok(guard) = state.settings.lock() {
        s.used_today = guard.used_today;
        s.usage_day = guard.usage_day.clone();
    }
    normalize(&mut s);
    store::save_settings(&s)?;
    if let Ok(mut guard) = state.settings.lock() {
        *guard = s.clone();
    }
    Ok(s)
}

#[tauri::command]
fn reset_usage(state: State<AppState>) -> Result<Settings, String> {
    let mut s = state.settings.lock().map_err(|e| e.to_string())?.clone();
    s.used_today = 0;
    s.usage_day = store::today();
    store::save_settings(&s)?;
    if let Ok(mut guard) = state.settings.lock() {
        *guard = s.clone();
    }
    Ok(s)
}

#[tauri::command]
fn history_list() -> Vec<store::HistoryEntry> {
    store::load_history()
}

#[tauri::command]
fn history_delete(id: String) -> Vec<store::HistoryEntry> {
    store::delete_history(&id)
}

#[tauri::command]
fn history_clear() -> Vec<store::HistoryEntry> {
    store::clear_history()
}

#[tauri::command]
fn copy_text(text: String) -> Result<(), String> {
    let mut cb = arboard::Clipboard::new().map_err(|e| format!("Presse-papiers : {e}"))?;
    cb.set_text(text).map_err(|e| format!("Copie impossible : {e}"))
}

#[tauri::command]
fn type_text(text: String) -> Result<(), String> {
    use enigo::{Enigo, Keyboard, Settings as EnigoSettings};
    focus_previous_window();
    let mut enigo = Enigo::new(&EnigoSettings::default())
        .map_err(|e| format!("Clavier indisponible : {e}"))?;
    enigo.text(&text).map_err(|e| format!("Frappe impossible : {e}"))
}

#[tauri::command]
fn open_url(url: String) -> Result<(), String> {
    if !(url.starts_with("https://") || url.starts_with("http://")) {
        return Err("Lien non autorisé.".into());
    }
    #[cfg(target_os = "windows")]
    {
        std::process::Command::new("cmd")
            .args(["/C", "start", "", &url])
            .spawn()
            .map_err(|e| e.to_string())?;
    }
    #[cfg(not(target_os = "windows"))]
    {
        let _ = &url;
    }
    Ok(())
}

#[tauri::command]
fn open_mic_settings() -> Result<(), String> {
    #[cfg(target_os = "windows")]
    {
        std::process::Command::new("cmd")
            .args(["/C", "start", "ms-settings:privacy-microphone"])
            .spawn()
            .map_err(|e| e.to_string())?;
    }
    Ok(())
}

#[tauri::command]
fn open_data_folder() -> Result<(), String> {
    #[cfg(target_os = "windows")]
    {
        std::process::Command::new("explorer")
            .arg(store::data_dir())
            .spawn()
            .map_err(|e| e.to_string())?;
    }
    Ok(())
}

#[tauri::command]
fn set_always_on_top(window: tauri::WebviewWindow, value: bool) -> Result<(), String> {
    window
        .set_always_on_top(value)
        .map_err(|e| format!("Fenêtre : {e}"))
}

#[tauri::command]
fn set_compact(
    window: tauri::WebviewWindow,
    state: State<AppState>,
    compact: bool,
) -> Result<(), String> {
    if compact {
        window
            .set_size(LogicalSize::new(440.0, 190.0))
            .map_err(|e| e.to_string())?;
        window.set_always_on_top(true).map_err(|e| e.to_string())?;
        let _ = window.set_resizable(false);
    } else {
        window
            .set_size(LogicalSize::new(980.0, 720.0))
            .map_err(|e| e.to_string())?;
        let top = state
            .settings
            .lock()
            .map(|s| s.always_on_top)
            .unwrap_or(false);
        let _ = window.set_always_on_top(top);
        let _ = window.set_resizable(true);
    }
    Ok(())
}

#[tauri::command]
fn set_autostart(app: tauri::AppHandle, enabled: bool) -> Result<(), String> {
    #[cfg(target_os = "windows")]
    {
        let exe = std::env::current_exe().map_err(|e| e.to_string())?;
        let exe = exe.display().to_string();
        if enabled {
            let status = std::process::Command::new("reg")
                .args([
                    "add",
                    r"HKCU\Software\Microsoft\Windows\CurrentVersion\Run",
                    "/v",
                    "JoynixScribe",
                    "/t",
                    "REG_SZ",
                    "/d",
                    &format!("\"{exe}\""),
                    "/f",
                ])
                .status()
                .map_err(|e| format!("Registre : {e}"))?;
            if !status.success() {
                return Err("Windows a refusé l'ajout au démarrage.".into());
            }
        } else {
            let _ = std::process::Command::new("reg")
                .args([
                    "delete",
                    r"HKCU\Software\Microsoft\Windows\CurrentVersion\Run",
                    "/v",
                    "JoynixScribe",
                    "/f",
                ])
                .status();
        }
    }
    let _ = &app;
    Ok(())
}

#[tauri::command]
fn quit_app(app: tauri::AppHandle) {
    app.exit(0);
}

#[tauri::command]
fn hide_window(window: tauri::WebviewWindow) -> Result<(), String> {
    window.hide().map_err(|e| e.to_string())
}

#[tauri::command]
fn window_minimize(window: tauri::WebviewWindow) -> Result<(), String> {
    window.minimize().map_err(|e| e.to_string())
}

#[tauri::command]
fn window_close(window: tauri::WebviewWindow) -> Result<(), String> {
    window.hide().map_err(|e| e.to_string())
}

#[tauri::command]
fn app_version() -> String {
    env!("CARGO_PKG_VERSION").to_string()
}

#[tauri::command]
fn data_dir() -> String {
    store::data_dir().display().to_string()
}

#[tauri::command]
fn audio_rms(samples_b64: String) -> Result<f32, String> {
    use base64::Engine;
    let bytes = base64::engine::general_purpose::STANDARD
        .decode(&samples_b64)
        .map_err(|e| e.to_string())?;
    let floats: Vec<f32> = bytes
        .chunks_exact(4)
        .map(|c| f32::from_le_bytes([c[0], c[1], c[2], c[3]]))
        .collect();
    Ok(audio::rms(&floats))
}

fn show_main(app: &tauri::AppHandle) {
    if let Some(w) = app.get_webview_window("main") {
        let _ = w.show();
        let _ = w.unminimize();
        let _ = w.set_focus();
    }
}

fn main() {
    let settings = {
        let mut s = store::load_settings();
        normalize(&mut s);
        s
    };

    tauri::Builder::default()
        .plugin(
            tauri_plugin_global_shortcut::Builder::new()
                .with_handler(|app, shortcut, event| {
                    if event.state() == ShortcutState::Pressed {
                        let expected = Shortcut::new(
                            Some(Modifiers::CONTROL | Modifiers::ALT),
                            Code::Space,
                        );
                        if shortcut == &expected {
                            show_main(app);
                            let _ = app.emit("hotkey-toggle", ());
                        }
                    }
                })
                .build(),
        )
        .manage(AppState {
            capture: Mutex::new(Capture::new()),
            settings: Mutex::new(settings),
        })
        .on_window_event(|window, event| {
            if let tauri::WindowEvent::CloseRequested { api, .. } = event {
                let keep = window
                    .app_handle()
                    .state::<AppState>()
                    .settings
                    .lock()
                    .map(|s| s.close_to_tray)
                    .unwrap_or(false);
                if keep {
                    api.prevent_close();
                    let _ = window.hide();
                }
            }
        })
        .setup(|app| {
            let dir = app
                .path()
                .app_config_dir()
                .unwrap_or_else(|_| std::path::PathBuf::from("."));
            store::init(dir);

            let shortcut = Shortcut::new(Some(Modifiers::CONTROL | Modifiers::ALT), Code::Space);
            match app.global_shortcut().register(shortcut) {
                Ok(()) => HOTKEY_OK.store(true, Ordering::Relaxed),
                Err(e) => eprintln!("[hotkey] {e}"),
            }
            track_foreground_window();

            let show = MenuItem::with_id(app, "show", "Afficher JoynixScribe", true, None::<&str>)?;
            let record = MenuItem::with_id(app, "record", "Démarrer / arrêter la dictée", true, None::<&str>)?;
            let quit = MenuItem::with_id(app, "quit", "Quitter", true, None::<&str>)?;
            let menu = Menu::with_items(app, &[&show, &record, &quit])?;

            let mut tray = TrayIconBuilder::with_id("main")
                .menu(&menu)
                .show_menu_on_left_click(false)
                .tooltip("JoynixScribe — Ctrl+Alt+Espace")
                .on_menu_event(|app, event| match event.id().as_ref() {
                    "show" => show_main(app),
                    "record" => {
                        show_main(app);
                        let _ = app.emit("tray-record", ());
                    }
                    "quit" => app.exit(0),
                    _ => {}
                })
                .on_tray_icon_event(|tray, event| {
                    if let TrayIconEvent::Click {
                        button: MouseButton::Left,
                        button_state: MouseButtonState::Up,
                        ..
                    } = event
                    {
                        show_main(tray.app_handle());
                    }
                });
            if let Some(icon) = app.default_window_icon().cloned() {
                tray = tray.icon(icon);
            }
            tray.build(app)?;

            Ok(())
        })
        .invoke_handler(tauri::generate_handler![
            mic_info,
            record_start,
            record_status,
            record_stop,
            record_cancel,
            record_snapshot,
            transcribe,
            test_key,
            load_settings,
            save_settings,
            reset_usage,
            history_list,
            history_delete,
            history_clear,
            copy_text,
            type_text,
            open_url,
            open_mic_settings,
            open_data_folder,
            set_always_on_top,
            set_compact,
            set_autostart,
            quit_app,
            hide_window,
            window_minimize,
            window_close,
            data_dir,
            app_version,
            audio_rms
        ])
        .run(tauri::generate_context!())
        .expect("Impossible de démarrer JoynixScribe");
}
