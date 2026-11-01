#![windows_subsystem = "windows"]

use eframe::egui;
use std::sync::{Arc, Mutex};
use std::sync::mpsc;

mod audio;
mod providers;

#[derive(Clone, PartialEq, serde::Serialize, serde::Deserialize)]
enum Provider { Gemini, Groq, Cloudflare }

#[derive(Clone, serde::Serialize, serde::Deserialize)]
struct Settings {
    provider: Provider,
    gemini_key: String,
    groq_key: String,
    cf_key: String,
    gemini_model: String,
    groq_model: String,
    cf_model: String,
    language: String,
    streaming: bool,
}

impl Default for Settings {
    fn default() -> Self {
        Self {
            provider: Provider::Gemini,
            gemini_key: String::new(),
            groq_key: String::new(),
            cf_key: String::new(),
            gemini_model: "gemini-3.5-flash-lite".into(),
            groq_model: "whisper-large-v3-turbo".into(),
            cf_model: "@cf/openai/whisper-large-v3-turbo".into(),
            language: "auto".into(),
            streaming: false,
        }
    }
}

enum Msg { Done(Result<String,String>) }

struct App {
    settings: Settings,
    show_settings: bool,
    recording: bool,
    audio: Arc<Mutex<audio::Recorder>>,
    samples: Arc<Mutex<Vec<f32>>>,
    sample_rate: u32,
    transcript: String,
    status: String,
    busy: bool,
    rx: Option<mpsc::Receiver<Msg>>,
}

impl App {
    fn new(cc: &eframe::CreationContext) -> Self {
        cc.egui_ctx.set_visuals(egui::Visuals::dark());
        Self {
            settings: Settings::default(),
            show_settings: false,
            recording: false,
            audio: Arc::new(Mutex::new(audio::Recorder::new())),
            samples: Arc::new(Mutex::new(Vec::new())),
            sample_rate: 16000,
            transcript: String::new(),
            status: "Pret".into(),
            busy: false,
            rx: None,
        }
    }
}

impl eframe::App for App {
    fn update(&mut self, ctx: &egui::Context, _frame: &mut eframe::Frame) {
        if let Some(rx) = &self.rx {
            if let Ok(Msg::Done(res)) = rx.try_recv() {
                self.rx = None;
                self.busy = false;
                match res {
                    Ok(t) => { if self.transcript.is_empty() { self.transcript = t; } else { self.transcript = format!("{} {}", self.transcript, t); } self.status = "Termine".into(); }
                    Err(e) => { self.status = format!("Erreur: {}", e); }
                }
            }
        }
        if ctx.input(|i| i.key_pressed(egui::Key::Space)) && !self.busy {
            if self.recording { self.stop_recording(); } else { self.start_recording(); }
        }

        egui::TopBottomPanel::top("top").show(ctx, |ui| {
            ui.horizontal(|ui| {
                ui.heading("JoynixScribe");
                ui.with_layout(egui::Layout::right_to_left(egui::Align::Center), |ui| {
                    if ui.button("Reglages").clicked() { self.show_settings = !self.show_settings; }
                });
            });
        });

        egui::CentralPanel::default().show(ctx, |ui| {
            ui.vertical_centered(|ui| {
                let btn_label = if self.recording { "Arreter" } else { "Parler" };
                let btn_color = if self.recording { egui::Color32::from_rgb(220,60,60) } else { egui::Color32::from_rgb(100,80,220) };
                if ui.add(egui::Button::new(btn_label).fill(btn_color).min_size(egui::vec2(180.0, 64.0))).clicked() && !self.busy {
                    if self.recording { self.stop_recording(); } else { self.start_recording(); }
                }
                ui.label(&self.status);
                if !audio::Recorder::has_input_device() {
                    ui.colored_label(egui::Color32::RED, "Aucun micro detecte.");
                    if ui.button("Ouvrir parametres micro Windows").clicked() {
                        let _ = std::process::Command::new("cmd").args(["/C","start","ms-settings:privacy-microphone"]).spawn();
                    }
                }
            });
            ui.add_space(8.0);
            ui.label("Transcription :");
            ui.add(egui::TextEdit::multiline(&mut self.transcript).desired_rows(10).desired_width(f32::INFINITY));
            ui.horizontal(|ui| {
                if ui.button("Copier").clicked() {
                    if let Ok(mut cb) = arboard::Clipboard::new() { let _ = cb.set_text(self.transcript.clone()); }
                }
                if ui.button("Taper").clicked() {
                    if let Ok(mut enigo) = enigo::Enigo::new(&enigo::Settings::default()) {
                        use enigo::Keyboard; let _ = enigo.text(&self.transcript);
                    }
                }
                if ui.button("Effacer").clicked() { self.transcript.clear(); }
            });
        });

        if self.show_settings {
            egui::Window::new("Reglages").show(ctx, |ui| {
                ui.label("Fournisseur principal");
                ui.horizontal(|ui| {
                    ui.selectable_value(&mut self.settings.provider, Provider::Gemini, "Gemini");
                    ui.selectable_value(&mut self.settings.provider, Provider::Groq, "Groq");
                    ui.selectable_value(&mut self.settings.provider, Provider::Cloudflare, "Cloudflare");
                });
                ui.separator();
                ui.label("Cle Gemini"); ui.text_edit_singleline(&mut self.settings.gemini_key);
                ui.label("Modele Gemini"); ui.text_edit_singleline(&mut self.settings.gemini_model);
                ui.label("Cle Groq"); ui.text_edit_singleline(&mut self.settings.groq_key);
                ui.label("Modele Groq"); ui.text_edit_singleline(&mut self.settings.groq_model);
                ui.label("Cle Cloudflare (accountId|token)"); ui.text_edit_singleline(&mut self.settings.cf_key);
                ui.label("Modele Cloudflare"); ui.text_edit_singleline(&mut self.settings.cf_model);
                ui.separator();
                ui.label("Langue"); ui.text_edit_singleline(&mut self.settings.language);
                ui.checkbox(&mut self.settings.streaming, "Mode streaming (live)");
                ui.separator();
                if ui.button("Sauvegarder").clicked() {
                    if let Ok(json) = serde_json::to_string(&self.settings) {
                        let _ = std::fs::write("joynixscribe_settings.json", json);
                    }
                    self.show_settings = false;
                }
                if ui.button("Charger").clicked() {
                    if let Ok(s) = std::fs::read_to_string("joynixscribe_settings.json") {
                        if let Ok(st) = serde_json::from_str(&s) { self.settings = st; }
                    }
                }
            });
        }
    }
}

impl App {
    fn start_recording(&mut self) {
        self.recording = true;
        self.status = "Enregistrement...".into();
        self.samples.lock().unwrap().clear();
        let mut rec = self.audio.lock().unwrap();
        if let Err(e) = rec.start(self.samples.clone(), self.sample_rate) { self.status = format!("Erreur micro: {}", e); self.recording = false; }
    }

    fn stop_recording(&mut self) {
        self.recording = false;
        let rate = self.audio.lock().unwrap().sample_rate();
        let samples = self.samples.lock().unwrap().clone();
        let wav = audio::encode_wav(&samples, rate);
        self.busy = true;
        self.status = "Transcription...".into();
        let settings = self.settings.clone();
        let (tx, rx) = mpsc::channel();
        self.rx = Some(rx);
        std::thread::spawn(move || {
            let rt = tokio::runtime::Runtime::new().unwrap();
            let res = rt.block_on(providers::transcribe(&settings, &wav));
            let _ = tx.send(Msg::Done(res));
        });
    }
}

fn main() -> eframe::Result {
    let options = eframe::NativeOptions {
        viewport: egui::ViewportBuilder::default().with_inner_size([520.0, 680.0]).with_always_on_top(),
        ..Default::default()
    };
    eframe::run_native("JoynixScribe", options, Box::new(|cc| Ok(Box::new(App::new(cc)))))
}
