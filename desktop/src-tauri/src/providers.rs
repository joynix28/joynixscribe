//! Fournisseurs de transcription : Gemini, Groq, Cloudflare Workers AI et
//! Aperture (secours sans clé).

use serde::{Deserialize, Serialize};
use serde_json::Value;
use std::time::{Duration, Instant};

#[derive(Clone, Serialize, Deserialize)]
#[serde(default)]
pub struct Settings {
    pub provider: String,
    pub gemini_key: String,
    pub gemini_model: String,
    pub groq_key: String,
    pub groq_model: String,
    pub cf_key: String,
    pub cf_model: String,
    pub language: String,
    pub prompt: String,
    pub streaming: bool,
    pub aperture_fallback: bool,
    pub auto_type: bool,
    pub theme: String,
    pub always_on_top: bool,
    pub autostart: bool,
    pub close_to_tray: bool,
    pub hotkey: String,
    pub daily_limit: u32,
    pub used_today: u32,
    pub usage_day: String,
}

impl Default for Settings {
    fn default() -> Self {
        Self {
            provider: "Gemini".into(),
            gemini_key: String::new(),
            gemini_model: "gemini-2.5-flash".into(),
            groq_key: String::new(),
            groq_model: "whisper-large-v3-turbo".into(),
            cf_key: String::new(),
            cf_model: "@cf/openai/whisper-large-v3-turbo".into(),
            language: "fr".into(),
            prompt: "Transcris fidèlement la dictée. Corrige la ponctuation et les majuscules, \
                     ne résume pas, n'ajoute aucun commentaire."
                .into(),
            streaming: false,
            aperture_fallback: true,
            auto_type: false,
            theme: "dark".into(),
            always_on_top: false,
            autostart: false,
            close_to_tray: false,
            hotkey: "Ctrl+Alt+Space".into(),
            daily_limit: 0,
            used_today: 0,
            usage_day: String::new(),
        }
    }
}

#[derive(Serialize)]
pub struct Transcription {
    pub text: String,
    pub provider: String,
    pub model: String,
    pub latency_ms: u64,
    pub fallback: bool,
}

fn client() -> Result<reqwest::Client, String> {
    reqwest::Client::builder()
        .timeout(Duration::from_secs(90))
        .connect_timeout(Duration::from_secs(15))
        .build()
        .map_err(|e| format!("Client HTTP : {e}"))
}

fn api_error(status: reqwest::StatusCode, body: &str) -> String {
    let msg = serde_json::from_str::<Value>(body)
        .ok()
        .and_then(|v| {
            v["error"]["message"]
                .as_str()
                .or_else(|| v["errors"][0]["message"].as_str())
                .or_else(|| v["message"].as_str())
                .map(str::to_string)
        })
        .unwrap_or_else(|| body.chars().take(200).collect());
    match status.as_u16() {
        401 | 403 => format!("Clé refusée ({status}). Vérifie la clé API. {msg}"),
        429 => format!("Quota atteint ({status}). Réessaie plus tard ou change de fournisseur. {msg}"),
        _ => format!("Erreur {status} : {msg}"),
    }
}

fn clean(text: &str) -> String {
    text.trim().trim_matches('"').trim().to_string()
}

fn is_garbage(text: &str) -> bool {
    let t = text.trim();
    if t.is_empty() {
        return true;
    }
    let letters = t.chars().filter(|c| c.is_alphanumeric()).count();
    if letters < 2 {
        return true;
    }
    let lower = t.to_lowercase();
    let known = [
        "sous-titres réalisés par la communauté d'amara.org",
        "sous-titres réalisés par la communauté",
        "merci d'avoir regardé cette vidéo",
        "abonnez-vous",
        "amara.org",
        "thanks for watching",
        "thank you for watching",
        "subtitles by the amara.org community",
    ];
    known.iter().any(|k| lower.contains(k))
}

pub async fn transcribe(settings: &Settings, wav: &[u8]) -> Result<Transcription, String> {
    let started = Instant::now();
    let (provider, model) = match settings.provider.as_str() {
        "Groq" => ("Groq", settings.groq_model.clone()),
        "Cloudflare" => ("Cloudflare", settings.cf_model.clone()),
        "Aperture" => ("Aperture", "parakeet".to_string()),
        _ => ("Gemini", settings.gemini_model.clone()),
    };

    let attempt = match provider {
        "Groq" => groq(settings, wav).await,
        "Cloudflare" => cloudflare(settings, wav).await,
        "Aperture" => aperture(settings, wav).await,
        _ => gemini(settings, wav).await,
    };

    let latency_ms = started.elapsed().as_millis() as u64;
    match attempt {
        Ok(text) if !is_garbage(&text) => Ok(Transcription {
            text: clean(&text),
            provider: provider.into(),
            model,
            latency_ms,
            fallback: false,
        }),
        Ok(_) => Err("Aucune parole détectée dans l'audio.".into()),
        Err(primary) => {
            if settings.aperture_fallback && provider != "Aperture" {
                match aperture(settings, wav).await {
                    Ok(text) if !is_garbage(&text) => Ok(Transcription {
                        text: clean(&text),
                        provider: "Aperture".into(),
                        model: "parakeet".into(),
                        latency_ms: started.elapsed().as_millis() as u64,
                        fallback: true,
                    }),
                    _ => Err(primary),
                }
            } else {
                Err(primary)
            }
        }
    }
}

async fn gemini(s: &Settings, wav: &[u8]) -> Result<String, String> {
    use base64::Engine;
    if s.gemini_key.trim().is_empty() {
        return Err("Aucune clé Gemini enregistrée.".into());
    }
    let b64 = base64::engine::general_purpose::STANDARD.encode(wav);
    let model = if s.gemini_model.trim().is_empty() { "gemini-2.5-flash" } else { s.gemini_model.trim() };
    let url = format!(
        "https://generativelanguage.googleapis.com/v1beta/models/{model}:generateContent?key={}",
        s.gemini_key.trim()
    );
    let mut instruction = s.prompt.clone();
    if s.language != "auto" && !s.language.trim().is_empty() {
        instruction.push_str(&format!("\nLangue attendue : {}.", s.language.trim()));
    }
    let body = serde_json::json!({
        "contents": [{"role": "user", "parts": [
            {"inline_data": {"mime_type": "audio/wav", "data": b64}},
            {"text": instruction}
        ]}],
        "generationConfig": {"temperature": 0.0, "maxOutputTokens": 2048}
    });
    let resp = client()?
        .post(&url)
        .json(&body)
        .send()
        .await
        .map_err(|e| format!("Réseau (Gemini) : {e}"))?;
    let status = resp.status();
    let raw = resp.text().await.map_err(|e| e.to_string())?;
    if !status.is_success() {
        return Err(api_error(status, &raw));
    }
    let v: Value = serde_json::from_str(&raw).map_err(|e| format!("Réponse Gemini illisible : {e}"))?;
    if let Some(reason) = v["promptFeedback"]["blockReason"].as_str() {
        return Err(format!("Gemini a bloqué la requête : {reason}"));
    }
    let text = v["candidates"][0]["content"]["parts"]
        .as_array()
        .map(|parts| {
            parts
                .iter()
                .filter_map(|p| p["text"].as_str())
                .collect::<Vec<_>>()
                .join(" ")
        })
        .unwrap_or_default();
    if text.trim().is_empty() {
        Err("Gemini n'a renvoyé aucun texte.".into())
    } else {
        Ok(text)
    }
}

async fn groq(s: &Settings, wav: &[u8]) -> Result<String, String> {
    if s.groq_key.trim().is_empty() {
        return Err("Aucune clé Groq enregistrée.".into());
    }
    let model = if s.groq_model.trim().is_empty() { "whisper-large-v3-turbo" } else { s.groq_model.trim() };
    let part = reqwest::multipart::Part::bytes(wav.to_vec())
        .file_name("audio.wav")
        .mime_str("audio/wav")
        .map_err(|e| e.to_string())?;
    let mut form = reqwest::multipart::Form::new()
        .text("model", model.to_string())
        .text("response_format", "json")
        .text("temperature", "0")
        .part("file", part);
    if s.language != "auto" && !s.language.trim().is_empty() {
        form = form.text("language", s.language.trim().to_string());
    }
    if !s.prompt.trim().is_empty() {
        form = form.text("prompt", s.prompt.chars().take(220).collect::<String>());
    }
    let resp = client()?
        .post("https://api.groq.com/openai/v1/audio/transcriptions")
        .bearer_auth(s.groq_key.trim())
        .multipart(form)
        .send()
        .await
        .map_err(|e| format!("Réseau (Groq) : {e}"))?;
    let status = resp.status();
    let raw = resp.text().await.map_err(|e| e.to_string())?;
    if !status.is_success() {
        return Err(api_error(status, &raw));
    }
    let v: Value = serde_json::from_str(&raw).map_err(|e| format!("Réponse Groq illisible : {e}"))?;
    let text = v["text"].as_str().unwrap_or_default().to_string();
    if text.trim().is_empty() {
        Err("Groq n'a renvoyé aucun texte.".into())
    } else {
        Ok(text)
    }
}

async fn cloudflare(s: &Settings, wav: &[u8]) -> Result<String, String> {
    let (account, token) = s
        .cf_key
        .split_once('|')
        .ok_or("Clé Cloudflare au format accountId|token attendu.")?;
    if account.trim().is_empty() || token.trim().is_empty() {
        return Err("Clé Cloudflare incomplète (accountId|token).".into());
    }
    let model = if s.cf_model.trim().is_empty() {
        "@cf/openai/whisper-large-v3-turbo"
    } else {
        s.cf_model.trim()
    };
    let url = format!(
        "https://api.cloudflare.com/client/v4/accounts/{}/ai/run/{model}",
        account.trim()
    );
    let resp = client()?
        .post(&url)
        .bearer_auth(token.trim())
        .header("Content-Type", "audio/wav")
        .body(wav.to_vec())
        .send()
        .await
        .map_err(|e| format!("Réseau (Cloudflare) : {e}"))?;
    let status = resp.status();
    let raw = resp.text().await.map_err(|e| e.to_string())?;
    if !status.is_success() {
        return Err(api_error(status, &raw));
    }
    let v: Value = serde_json::from_str(&raw).map_err(|e| format!("Réponse Cloudflare illisible : {e}"))?;
    if v["success"].as_bool() == Some(false) {
        let msg = v["errors"][0]["message"].as_str().unwrap_or("erreur inconnue");
        return Err(format!("Cloudflare : {msg}"));
    }
    let text = v["result"]["text"]
        .as_str()
        .or_else(|| v["result"]["transcription"].as_str())
        .unwrap_or_default()
        .to_string();
    if text.trim().is_empty() {
        Err("Cloudflare n'a renvoyé aucun texte.".into())
    } else {
        Ok(text)
    }
}

async fn aperture(s: &Settings, wav: &[u8]) -> Result<String, String> {
    let part = reqwest::multipart::Part::bytes(wav.to_vec())
        .file_name("joynixscribe.wav")
        .mime_str("audio/wav")
        .map_err(|e| e.to_string())?;
    let mut form = reqwest::multipart::Form::new().part("file", part);
    if s.language != "auto" && !s.language.trim().is_empty() {
        form = form.text("language", s.language.trim().to_string());
    }
    let resp = client()?
        .post("https://aperture-sciences.com/api/audio/transcribe")
        .multipart(form)
        .send()
        .await
        .map_err(|e| format!("Réseau (Aperture) : {e}"))?;
    let status = resp.status();
    let raw = resp.text().await.map_err(|e| e.to_string())?;
    if !status.is_success() {
        return Err(api_error(status, &raw));
    }
    let v: Value = serde_json::from_str(&raw).map_err(|e| format!("Réponse Aperture illisible : {e}"))?;
    let text = v["text"]
        .as_str()
        .or_else(|| v["transcription"].as_str())
        .or_else(|| v["result"]["text"].as_str())
        .or_else(|| v["data"]["text"].as_str())
        .unwrap_or_default()
        .to_string();
    if text.trim().is_empty() {
        Err("Aperture n'a renvoyé aucun texte.".into())
    } else {
        Ok(text)
    }
}

/// Vérifie une clé sans consommer de transcription.
pub async fn test_key(settings: &Settings) -> Result<String, String> {
    let c = client()?;
    match settings.provider.as_str() {
        "Groq" => {
            if settings.groq_key.trim().is_empty() {
                return Err("Renseigne d'abord une clé Groq.".into());
            }
            let resp = c
                .get("https://api.groq.com/openai/v1/models")
                .bearer_auth(settings.groq_key.trim())
                .send()
                .await
                .map_err(|e| format!("Réseau : {e}"))?;
            let status = resp.status();
            let raw = resp.text().await.unwrap_or_default();
            if status.is_success() {
                Ok("Clé Groq valide.".into())
            } else {
                Err(api_error(status, &raw))
            }
        }
        "Cloudflare" => {
            let (_, token) = settings
                .cf_key
                .split_once('|')
                .ok_or("Format accountId|token attendu.")?;
            let resp = c
                .get("https://api.cloudflare.com/client/v4/user/tokens/verify")
                .bearer_auth(token.trim())
                .send()
                .await
                .map_err(|e| format!("Réseau : {e}"))?;
            let status = resp.status();
            let raw = resp.text().await.unwrap_or_default();
            if status.is_success() {
                Ok("Jeton Cloudflare valide.".into())
            } else {
                Err(api_error(status, &raw))
            }
        }
        "Aperture" => {
            let resp = c
                .get("https://aperture-sciences.com/api/audio/transcribe")
                .send()
                .await
                .map_err(|e| format!("Réseau : {e}"))?;
            if resp.status().is_server_error() {
                Err(format!("Aperture est indisponible ({})", resp.status()))
            } else {
                Ok("Service Aperture joignable.".into())
            }
        }
        _ => {
            if settings.gemini_key.trim().is_empty() {
                return Err("Renseigne d'abord une clé Gemini.".into());
            }
            let resp = c
                .get("https://generativelanguage.googleapis.com/v1beta/models")
                .query(&[("key", settings.gemini_key.trim())])
                .send()
                .await
                .map_err(|e| format!("Réseau : {e}"))?;
            let status = resp.status();
            let raw = resp.text().await.unwrap_or_default();
            if status.is_success() {
                Ok("Clé Gemini valide.".into())
            } else {
                Err(api_error(status, &raw))
            }
        }
    }
}
