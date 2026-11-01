#![cfg_attr(not(debug_assertions), windows_subsystem = "windows")]

use serde::{Deserialize, Serialize};
use std::sync::{Arc, Mutex};
use tauri::State;
use cpal::traits::{DeviceTrait, HostTrait, StreamTrait};

#[derive(Clone, Serialize, Deserialize, Default)]
struct Settings {
    provider: String,
    gemini_key: String,
    groq_key: String,
    cf_key: String,
    gemini_model: String,
    groq_model: String,
    cf_model: String,
    language: String,
    streaming: bool,
}

struct AppState {
    samples: Arc<Mutex<Vec<f32>>>,
    sample_rate: Arc<Mutex<u32>>,
    recording: Arc<Mutex<bool>>,
}

impl Default for AppState {
    fn default() -> Self {
        Self {
            samples: Arc::new(Mutex::new(Vec::new())),
            sample_rate: Arc::new(Mutex::new(16000)),
            recording: Arc::new(Mutex::new(false)),
        }
    }
}

#[tauri::command]
fn has_mic() -> bool {
    cpal::default_host().default_input_device().is_some()
}

#[tauri::command]
fn open_mic_settings() {
    let _ = std::process::Command::new("cmd").args(["/C", "start", "ms-settings:privacy-microphone"]).spawn();
}

#[tauri::command]
fn record_start(state: State<AppState>) -> Result<(), String> {
    let host = cpal::default_host();
    let device = host.default_input_device().ok_or("Aucun micro")?;
    let supported = device.default_input_config().map_err(|e| e.to_string())?;
    let config: cpal::StreamConfig = supported.into();
    *state.sample_rate.lock().unwrap() = config.sample_rate.0;
    state.samples.lock().unwrap().clear();
    *state.recording.lock().unwrap() = true;

    let samples = state.samples.clone();
    let recording = state.recording.clone();
    let channels = config.channels as usize;
    std::thread::spawn(move || {
        let stream = match device.build_input_stream(
            &config,
            move |data: &[f32], _| {
                if let Ok(mut s) = samples.lock() {
                    if channels == 1 { s.extend_from_slice(data); }
                    else { for chunk in data.chunks(channels) { s.push(chunk.iter().sum::<f32>() / channels as f32); } }
                }
            },
            |e| eprintln!("{}", e),
            None,
        ) { Ok(s) => s, Err(_) => return };
        if stream.play().is_err() { return; }
        // keep stream alive until recording false
        while *recording.lock().unwrap() { std::thread::sleep(std::time::Duration::from_millis(50)); }
        drop(stream);
    });
    Ok(())
}

#[tauri::command]
fn record_stop(state: State<AppState>) -> Result<String, String> {
    *state.recording.lock().unwrap() = false;
    // wait for thread to finish
    std::thread::sleep(std::time::Duration::from_millis(200));
    let samples = state.samples.lock().unwrap().clone();
    let rate = *state.sample_rate.lock().unwrap();
    let mut cursor = std::io::Cursor::new(Vec::new());
    let spec = hound::WavSpec { channels: 1, sample_rate: rate, bits_per_sample: 16, sample_format: hound::SampleFormat::Int };
    let mut writer = hound::WavWriter::new(&mut cursor, spec).map_err(|e| e.to_string())?;
    for &s in &samples {
        let v = (s * 32767.0).clamp(-32768.0, 32767.0) as i16;
        let _ = writer.write_sample(v);
    }
    writer.finalize().map_err(|e| e.to_string())?;
    let wav = cursor.into_inner();
    use base64::Engine;
    Ok(base64::engine::general_purpose::STANDARD.encode(&wav))
}

#[tauri::command]
async fn transcribe(settings: Settings, wav_b64: String) -> Result<String, String> {
    use base64::Engine;
    let wav = base64::engine::general_purpose::STANDARD.decode(&wav_b64).map_err(|e| e.to_string())?;
    match settings.provider.as_str() {
        "Groq" => groq(&settings, &wav).await,
        "Cloudflare" => cloudflare(&settings, &wav).await,
        _ => gemini(&settings, &wav).await,
    }
}

async fn gemini(s: &Settings, wav: &[u8]) -> Result<String, String> {
    use base64::Engine;
    let b64 = base64::engine::general_purpose::STANDARD.encode(wav);
    let url = format!("https://generativelanguage.googleapis.com/v1beta/models/{}:generateContent?key={}", s.gemini_model, s.gemini_key);
    let body = serde_json::json!({"contents":[{"parts":[{"inline_data":{"mime_type":"audio/wav","data":b64}},{"text":"Transcris cet audio."}]}],"generationConfig":{"temperature":0.0}});
    let resp = reqwest::Client::new().post(&url).json(&body).send().await.map_err(|e| e.to_string())?;
    let v: serde_json::Value = resp.json().await.map_err(|e| e.to_string())?;
    let text = v["candidates"][0]["content"]["parts"][0]["text"].as_str().unwrap_or("").to_string();
    if text.is_empty() { Err("Aucun texte".into()) } else { Ok(text) }
}

async fn groq(s: &Settings, wav: &[u8]) -> Result<String, String> {
    let part = reqwest::multipart::Part::bytes(wav.to_vec()).file_name("audio.wav").mime_str("audio/wav").unwrap();
    let form = reqwest::multipart::Form::new().text("model", s.groq_model.clone()).text("response_format", "verbose_json").text("temperature", "0").part("file", part);
    let resp = reqwest::Client::new().post("https://api.groq.com/openai/v1/audio/transcriptions").bearer_auth(&s.groq_key).multipart(form).send().await.map_err(|e| e.to_string())?;
    let v: serde_json::Value = resp.json().await.map_err(|e| e.to_string())?;
    let text = v["text"].as_str().unwrap_or("").to_string();
    if text.is_empty() { Err("Aucun texte".into()) } else { Ok(text) }
}

async fn cloudflare(s: &Settings, wav: &[u8]) -> Result<String, String> {
    let (account, token) = s.cf_key.split_once('|').ok_or("Format accountId|token")?;
    let url = format!("https://api.cloudflare.com/client/v4/accounts/{}/ai/run/{}", account.trim(), s.cf_model);
    let resp = reqwest::Client::new().post(&url).bearer_auth(token.trim()).header("Content-Type", "audio/wav").body(wav.to_vec()).send().await.map_err(|e| e.to_string())?;
    let v: serde_json::Value = resp.json().await.map_err(|e| e.to_string())?;
    let text = v["result"]["text"].as_str().unwrap_or("").to_string();
    if text.is_empty() { Err("Aucun texte".into()) } else { Ok(text) }
}

#[tauri::command]
fn load_settings() -> Settings {
    std::fs::read_to_string("joynixscribe_settings.json").ok()
        .and_then(|s| serde_json::from_str(&s).ok())
        .unwrap_or_default()
}

#[tauri::command]
fn save_settings(settings: Settings) -> Result<(), String> {
    let json = serde_json::to_string_pretty(&settings).map_err(|e| e.to_string())?;
    std::fs::write("joynixscribe_settings.json", json).map_err(|e| e.to_string())
}

#[tauri::command]
fn copy_text(text: String) {
    if let Ok(mut cb) = arboard::Clipboard::new() { let _ = cb.set_text(text); }
}

#[tauri::command]
fn type_text(text: String) {
    if let Ok(mut enigo) = enigo::Enigo::new(&enigo::Settings::default()) {
        use enigo::Keyboard; let _ = enigo.text(&text);
    }
}

fn main() {
    tauri::Builder::default()
        .manage(AppState::default())
        .invoke_handler(tauri::generate_handler![
            has_mic, open_mic_settings, record_start, record_stop,
            transcribe, load_settings, save_settings, copy_text, type_text
        ])
        .run(tauri::generate_context!())
        .expect("error while running tauri application");
}
