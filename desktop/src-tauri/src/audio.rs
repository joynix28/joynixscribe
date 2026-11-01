//! Capture micro multiplateforme.
//!
//! Points importants :
//! - on utilise la configuration **par défaut** du périphérique (pas de 16 kHz
//!   imposé : beaucoup de pilotes Windows refusent cette fréquence) ;
//! - on gère les formats f32 / i16 / u16 (le format par défaut de WASAPI est
//!   souvent f32 mais pas toujours) ;
//! - le flux cpal n'est pas `Send` sous Windows : il est créé et détruit dans
//!   un thread dédié, et les erreurs sont remontées de façon synchrone.

use cpal::traits::{DeviceTrait, HostTrait, StreamTrait};
use std::sync::atomic::{AtomicBool, AtomicU32, Ordering};
use std::sync::mpsc;
use std::sync::{Arc, Mutex};
use std::time::Duration;

pub struct Capture {
    samples: Arc<Mutex<Vec<f32>>>,
    rate: Arc<AtomicU32>,
    level: Arc<AtomicU32>,
    stop: Arc<AtomicBool>,
    running: Arc<AtomicBool>,
    committed: Arc<Mutex<usize>>,
    thread: Option<std::thread::JoinHandle<()>>,
}

impl Default for Capture {
    fn default() -> Self {
        Self::new()
    }
}

impl Capture {
    pub fn new() -> Self {
        Self {
            samples: Arc::new(Mutex::new(Vec::new())),
            rate: Arc::new(AtomicU32::new(16_000)),
            level: Arc::new(AtomicU32::new(0f32.to_bits())),
            stop: Arc::new(AtomicBool::new(false)),
            running: Arc::new(AtomicBool::new(false)),
            committed: Arc::new(Mutex::new(0)),
            thread: None,
        }
    }

    pub fn is_running(&self) -> bool {
        self.running.load(Ordering::Relaxed)
    }

    /// Niveau RMS instantané (0.0 → ~1.0), utilisé pour la forme d'onde.
    pub fn level(&self) -> f32 {
        f32::from_bits(self.level.load(Ordering::Relaxed))
    }

    pub fn committed(&self) -> usize {
        self.committed.lock().map(|c| *c).unwrap_or(0)
    }

    pub fn sample_rate(&self) -> u32 {
        self.rate.load(Ordering::Relaxed).max(8_000)
    }

    pub fn duration_secs(&self) -> f32 {
        let rate = self.rate.load(Ordering::Relaxed).max(1) as f32;
        self.samples.lock().map(|s| s.len() as f32 / rate).unwrap_or(0.0)
    }

    pub fn start(&mut self) -> Result<(), String> {
        self.shutdown();

        if let Ok(mut s) = self.samples.lock() {
            s.clear();
        }
        if let Ok(mut c) = self.committed.lock() {
            *c = 0;
        }
        self.stop.store(false, Ordering::SeqCst);
        self.running.store(false, Ordering::SeqCst);
        self.level.store(0f32.to_bits(), Ordering::Relaxed);

        let samples = self.samples.clone();
        let rate = self.rate.clone();
        let level = self.level.clone();
        let stop = self.stop.clone();
        let running = self.running.clone();
        let (tx, rx) = mpsc::channel::<Result<(), String>>();

        let handle = std::thread::Builder::new()
            .name("joynixscribe-capture".into())
            .spawn(move || match build_stream(samples, level) {
                Ok((stream, device_rate)) => {
                    if let Err(e) = stream.play() {
                        let _ = tx.send(Err(format!("Impossible de démarrer le micro : {e}")));
                        return;
                    }
                    rate.store(device_rate, Ordering::Relaxed);
                    let _ = tx.send(Ok(()));
                    running.store(true, Ordering::SeqCst);
                    while !stop.load(Ordering::SeqCst) {
                        std::thread::sleep(Duration::from_millis(25));
                    }
                    running.store(false, Ordering::SeqCst);
                    drop(stream);
                }
                Err(e) => {
                    let _ = tx.send(Err(e));
                }
            })
            .map_err(|e| format!("Impossible de créer le thread audio : {e}"))?;

        match rx.recv_timeout(Duration::from_secs(6)) {
            Ok(Ok(())) => {
                self.thread = Some(handle);
                Ok(())
            }
            Ok(Err(e)) => {
                let _ = handle.join();
                Err(e)
            }
            Err(_) => {
                self.stop.store(true, Ordering::SeqCst);
                let _ = handle.join();
                Err("Le micro n'a pas répondu (délai dépassé). Vérifie l'autorisation Windows.".into())
            }
        }
    }

    /// Arrête la capture et renvoie les échantillons mono.
    pub fn stop(&mut self) -> Vec<f32> {
        self.stop.store(true, Ordering::SeqCst);
        if let Some(h) = self.thread.take() {
            let _ = h.join();
        }
        self.running.store(false, Ordering::SeqCst);
        self.samples.lock().map(|s| s.clone()).unwrap_or_default()
    }

    fn shutdown(&mut self) {
        self.stop.store(true, Ordering::SeqCst);
        if let Some(h) = self.thread.take() {
            let _ = h.join();
        }
        self.running.store(false, Ordering::SeqCst);
    }

    /// Renvoie l'audio non encore transcrit (mode streaming), en gardant un
    /// petit recouvrement pour ne pas couper les mots à cheval sur deux blocs.
    pub fn snapshot(&self, overlap_secs: f32) -> (Vec<f32>, f32) {
        let rate = self.rate.load(Ordering::Relaxed).max(1) as usize;
        let overlap = (rate as f32 * overlap_secs.clamp(0.0, 2.0)) as usize;
        let samples = match self.samples.lock() {
            Ok(s) => s,
            Err(_) => return (Vec::new(), 0.0),
        };
        let mut committed = match self.committed.lock() {
            Ok(c) => c,
            Err(_) => return (Vec::new(), 0.0),
        };
        let start = (*committed).min(samples.len());
        let end = samples.len().saturating_sub(overlap);
        if end <= start {
            return (Vec::new(), 0.0);
        }
        let chunk = samples[start..end].to_vec();
        *committed = end;
        let level = rms(&chunk);
        (chunk, level)
    }
}

pub fn rms(samples: &[f32]) -> f32 {
    if samples.is_empty() {
        return 0.0;
    }
    let sum: f64 = samples.iter().map(|v| (*v as f64) * (*v as f64)).sum();
    (sum / samples.len() as f64).sqrt() as f32
}

#[derive(Clone)]
struct Sink {
    samples: Arc<Mutex<Vec<f32>>>,
    level: Arc<AtomicU32>,
    channels: usize,
}

impl Sink {
    fn push<I: Iterator<Item = f32>>(&self, it: I) {
        let ch = self.channels.max(1);
        let mut mono: Vec<f32> = Vec::with_capacity(1024);
        let mut acc = 0f32;
        let mut n = 0usize;
        let mut sum_sq = 0f64;
        let mut count = 0usize;
        for v in it {
            acc += v;
            n += 1;
            sum_sq += (v as f64) * (v as f64);
            count += 1;
            if n == ch {
                mono.push(acc / ch as f32);
                acc = 0.0;
                n = 0;
            }
        }
        if let Ok(mut s) = self.samples.lock() {
            s.extend_from_slice(&mono);
        }
        let rms = if count > 0 { (sum_sq / count as f64).sqrt() as f32 } else { 0.0 };
        self.level.store(rms.to_bits(), Ordering::Relaxed);
    }
}

fn err_fn(e: cpal::StreamError) {
    eprintln!("[audio] {e}");
}

fn build_stream(
    samples: Arc<Mutex<Vec<f32>>>,
    level: Arc<AtomicU32>,
) -> Result<(cpal::Stream, u32), String> {
    let host = cpal::default_host();
    let device = host
        .default_input_device()
        .ok_or("Aucun microphone détecté. Branche un micro puis réessaie.")?;
    let supported = device
        .default_input_config()
        .map_err(|e| format!("Impossible de lire la configuration du micro : {e}"))?;

    let sample_format = supported.sample_format();
    let config: cpal::StreamConfig = supported.into();
    let device_rate = config.sample_rate.0;
    let channels = config.channels as usize;
    let sink = Sink { samples, level, channels };

    let stream = match sample_format {
        cpal::SampleFormat::F32 => {
            let sink = sink.clone();
            device.build_input_stream(
                &config,
                move |data: &[f32], _| sink.push(data.iter().copied()),
                err_fn,
                None,
            )
        }
        cpal::SampleFormat::I16 => {
            let sink = sink.clone();
            device.build_input_stream(
                &config,
                move |data: &[i16], _| sink.push(data.iter().map(|v| *v as f32 / 32768.0)),
                err_fn,
                None,
            )
        }
        cpal::SampleFormat::U16 => {
            let sink = sink.clone();
            device.build_input_stream(
                &config,
                move |data: &[u16], _| {
                    sink.push(data.iter().map(|v| (*v as f32 - 32768.0) / 32768.0))
                },
                err_fn,
                None,
            )
        }
        other => {
            return Err(format!(
                "Format audio non pris en charge par l'application ({other:?}). \
                 Choisis un autre micro dans les paramètres Windows."
            ))
        }
    }
    .map_err(|e| {
        format!(
            "Le micro refuse la configuration ({device_rate} Hz, {channels} canaux) : {e}"
        )
    })?;

    Ok((stream, device_rate))
}

pub fn encode_wav(samples: &[f32], rate: u32) -> Vec<u8> {
    let mut cursor = std::io::Cursor::new(Vec::new());
    let spec = hound::WavSpec {
        channels: 1,
        sample_rate: rate.max(8_000),
        bits_per_sample: 16,
        sample_format: hound::SampleFormat::Int,
    };
    let Ok(mut writer) = hound::WavWriter::new(&mut cursor, spec) else {
        return Vec::new();
    };
    for &s in samples {
        let v = (s.clamp(-1.0, 1.0) * 32767.0) as i16;
        let _ = writer.write_sample(v);
    }
    let _ = writer.finalize();
    cursor.into_inner()
}
