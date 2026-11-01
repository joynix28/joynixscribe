//! Persistance locale : réglages + historique.
//!
//! Les fichiers vivent dans `%APPDATA%\JoynixScribe` (jamais dans le dossier
//! courant : un exe installé dans Program Files ne peut pas y écrire).

use crate::providers::Settings;
use serde::{Deserialize, Serialize};
use std::path::PathBuf;
use std::sync::OnceLock;
use std::time::{SystemTime, UNIX_EPOCH};

static DIR: OnceLock<PathBuf> = OnceLock::new();

pub fn init(dir: PathBuf) {
    let _ = DIR.set(dir);
}

fn dir() -> PathBuf {
    DIR.get().cloned().unwrap_or_else(|| PathBuf::from("."))
}

fn settings_path() -> PathBuf {
    dir().join("settings.json")
}

fn history_path() -> PathBuf {
    dir().join("history.json")
}

fn write_atomic(path: &PathBuf, data: &str) -> Result<(), String> {
    if let Some(parent) = path.parent() {
        std::fs::create_dir_all(parent).map_err(|e| format!("Dossier de données : {e}"))?;
    }
    let tmp = path.with_extension("tmp");
    std::fs::write(&tmp, data).map_err(|e| format!("Écriture impossible : {e}"))?;
    std::fs::rename(&tmp, path).map_err(|e| format!("Enregistrement impossible : {e}"))
}

pub fn load_settings() -> Settings {
    std::fs::read_to_string(settings_path())
        .ok()
        .and_then(|s| serde_json::from_str(&s).ok())
        .unwrap_or_default()
}

pub fn save_settings(settings: &Settings) -> Result<(), String> {
    let json = serde_json::to_string_pretty(settings).map_err(|e| e.to_string())?;
    write_atomic(&settings_path(), &json)
}

pub fn data_dir() -> PathBuf {
    dir()
}

#[derive(Clone, Serialize, Deserialize)]
pub struct HistoryEntry {
    pub id: String,
    pub text: String,
    pub provider: String,
    pub model: String,
    pub duration_secs: f32,
    pub created_at: u64,
    pub app_hint: String,
}

pub fn load_history() -> Vec<HistoryEntry> {
    std::fs::read_to_string(history_path())
        .ok()
        .and_then(|s| serde_json::from_str(&s).ok())
        .unwrap_or_default()
}

pub fn add_history(entry: HistoryEntry) -> Vec<HistoryEntry> {
    let mut list = load_history();
    list.insert(0, entry);
    list.truncate(300);
    if let Ok(json) = serde_json::to_string_pretty(&list) {
        let _ = write_atomic(&history_path(), &json);
    }
    list
}

pub fn delete_history(id: &str) -> Vec<HistoryEntry> {
    let mut list = load_history();
    list.retain(|e| e.id != id);
    if let Ok(json) = serde_json::to_string_pretty(&list) {
        let _ = write_atomic(&history_path(), &json);
    }
    list
}

pub fn clear_history() -> Vec<HistoryEntry> {
    let _ = write_atomic(&history_path(), "[]");
    Vec::new()
}

pub fn now_millis() -> u64 {
    SystemTime::now()
        .duration_since(UNIX_EPOCH)
        .map(|d| d.as_millis() as u64)
        .unwrap_or(0)
}

/// Date locale approximative au format AAAA-MM-JJ (UTC), utilisée pour le
/// compteur de quota journalier.
pub fn today() -> String {
    let secs = SystemTime::now()
        .duration_since(UNIX_EPOCH)
        .map(|d| d.as_secs())
        .unwrap_or(0);
    let days = secs / 86_400;
    let (y, m, d) = civil_from_days(days as i64);
    format!("{y:04}-{m:02}-{d:02}")
}

// Algorithme de Howard Hinnant, sans dépendance externe.
fn civil_from_days(z: i64) -> (i64, u32, u32) {
    let z = z + 719_468;
    let era = if z >= 0 { z } else { z - 146_096 } / 146_097;
    let doe = (z - era * 146_097) as u64;
    let yoe = (doe - doe / 1460 + doe / 36_524 - doe / 146_096) / 365;
    let y = yoe as i64 + era * 400;
    let doy = doe - (365 * yoe + yoe / 4 - yoe / 100);
    let mp = (5 * doy + 2) / 153;
    let d = (doy - (153 * mp + 2) / 5 + 1) as u32;
    let m = if mp < 10 { mp + 3 } else { mp - 9 } as u32;
    (if m <= 2 { y + 1 } else { y }, m, d)
}
