use eframe::egui;
use std::sync::{Arc, Mutex};
use std::time::Instant;

mod audio;
mod providers;

#[derive(Clone, PartialEq)]
enum Provider { Gemini, Groq, Cloudflare }

#[derive(Clone)]
struct Settings {
    provider: Provider,
    gemini_key: String,
    groq_key: String,
    cf_key: String, // accountId|token
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
    rt: tokio::runtime::Runtime,
}

impl App {
    fn new(_cc: &eframe::CreationContext) -> Self {
        let rt = tokio::runtime::Runtime::new().unwrap();
        let rec = audio::Recorder::new();
        Self {
            settings: Settings::default(),
            show_settings: false,
            recording: false,
            audio: Arc::new(Mutex::new(rec)),
            samples: Arc::new(Mutex::new(Vec::new())),
            sample_rate: 16000,
            transcript: String::new(),
            status: "Prêt".into(),
            busy: false,
            rt,
        }
    }
}

impl eframe::App for App {
    fn update(&mut self, ctx: &egui::Context, _frame: &mut eframe::Frame) {
        egui::TopBottomPanel::top("top").show(ctx, |ui| {
            ui.horizontal(|ui| {
                ui.heading("JoynixScribe");
                ui.with_layout(egui::Layout::right_to_left(egui::Align::Center), |ui| {
                    if ui.button("⚙ Réglages").clicked() { self.show_settings = !self.show_settings; }
                });
            });
        });

        // Raccourci espace pour démarrer/arrêter (fenêtre au premier plan).
        let space = ctx.input(|i| i.key_pressed(egui::Key::Space));
        if space {
            if self.recording { self.stop_recording(ctx); } else { self.start_recording(); }
        }

        egui::CentralPanel::default().show(ctx, |ui| {
            ui.vertical_centered(|ui| {
                let btn_label = if self.recording { "■ Arrêter" } else { "● Parler" };
                let btn_color = if self.recording { egui::Color32::from_rgb(220,60,60) } else { egui::Color32::from_rgb(100,80,220) };
                if ui.add(egui::Button::new(btn_label).fill(btn_color).min_size(egui::vec2(160.0, 60.0))).clicked() {
                    if self.recording {
                        self.stop_recording(ctx);
                    } else {
                        self.start_recording();
                    }
                }
                ui.label(&self.status);
            });
            ui.add_space(8.0);
            ui.label("Transcription :");
            ui.add(egui::TextEdit::multiline(&mut self.transcript).desired_rows(8).desired_width(f32::INFINITY));
            ui.horizontal(|ui| {
                if ui.button("Copier").clicked() {
                    let mut cb = arboard::Clipboard::new().unwrap();
                    let _ = cb.set_text(self.transcript.clone());
                }
                if ui.button("Taper").clicked() {
                    let mut enigo = enigo::Enigo::new(&enigo::Settings::default()).unwrap();
                    use enigo::Keyboard;
                    let _ = enigo.text(&self.transcript);
                }
            });
        });

        if self.show_settings {
            egui::Window::new("Réglages").show(ctx, |ui| {
                ui.label("Fournisseur principal");
                ui.horizontal(|ui| {
                    ui.selectable_value(&mut self.settings.provider, Provider::Gemini, "Gemini");
                    ui.selectable_value(&mut self.settings.provider, Provider::Groq, "Groq");
                    ui.selectable_value(&mut self.settings.provider, Provider::Cloudflare, "Cloudflare");
                });
                ui.separator();
                ui.label("Clé Gemini"); ui.text_edit_singleline(&mut self.settings.gemini_key);
                ui.label("Modèle Gemini"); ui.text_edit_singleline(&mut self.settings.gemini_model);
                ui.label("Clé Groq"); ui.text_edit_singleline(&mut self.settings.groq_key);
                ui.label("Modèle Groq"); ui.text_edit_singleline(&mut self.settings.groq_model);
                ui.label("Clé Cloudflare (accountId|token)"); ui.text_edit_singleline(&mut self.settings.cf_key);
                ui.label("Modèle Cloudflare"); ui.text_edit_singleline(&mut self.settings.cf_model);
                ui.separator();
                ui.label("Langue"); ui.text_edit_singleline(&mut self.settings.language);
                ui.checkbox(&mut self.settings.streaming, "Mode streaming (live)");
            });
        }
    }
}

impl App {
    fn start_recording(&mut self) {
        self.recording = true;
        self.status = "Enregistrement…".into();
        let mut rec = self.audio.lock().unwrap();
        rec.start(self.samples.clone(), self.sample_rate);
    }

    fn stop_recording(&mut self, ctx: &egui::Context) {
        self.recording = false;
        let samples = {
            let mut rec = self.audio.lock().unwrap();
            rec.stop();
            self.samples.lock().unwrap().clone()
        };
        // encode WAV
        let wav = audio::encode_wav(&samples, self.sample_rate);
        self.busy = true;
        self.status = "Transcription…".into();
        let settings = self.settings.clone();
        let transcript = self.transcript.clone();
        let samples_clone = samples.clone();
        let ctx2 = ctx.clone();
        let (tx) = ((),);
        // run async
        let rt = &self.rt;
        let result = rt.block_on(async {
            providers::transcribe(&settings, &wav).await
        });
        match result {
            Ok(t) => {
                if self.transcript.is_empty() { self.transcript = t; } else { self.transcript = format!("{} {}", self.transcript, t); }
                self.status = "Terminé".into();
            }
            Err(e) => { self.status = format!("Erreur: {}", e); }
        }
        self.busy = false;
        let _ = (samples_clone, transcript, ctx2, tx);
    }
}

fn main() -> eframe::Result {
    let options = eframe::NativeOptions {
        viewport: egui::ViewportBuilder::default().with_inner_size([480.0, 640.0]).with_always_on_top(),
        ..Default::default()
    };
    eframe::run_native("JoynixScribe", options, Box::new(|cc| Ok(Box::new(App::new(cc)))))
}
