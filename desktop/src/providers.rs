use serde_json::json;

pub async fn transcribe(settings: &super::Settings, wav: &[u8]) -> Result<String, String> {
    match settings.provider {
        super::Provider::Gemini => gemini(settings, wav).await,
        super::Provider::Groq => groq(settings, wav).await,
        super::Provider::Cloudflare => cloudflare(settings, wav).await,
    }
}

async fn gemini(s: &super::Settings, wav: &[u8]) -> Result<String, String> {
    use base64::Engine;
    let b64 = base64::engine::general_purpose::STANDARD.encode(wav);
    let url = format!("https://generativelanguage.googleapis.com/v1beta/models/{}:generateContent?key={}", s.gemini_model, s.gemini_key);
    let body = json!({
        "contents": [{"parts": [
            {"inline_data": {"mime_type": "audio/wav", "data": b64}},
            {"text": "Transcris cet audio."}
        ]}],
        "generationConfig": {"temperature": 0.0}
    });
    let resp = reqwest::Client::new().post(&url).json(&body).send().await.map_err(|e| e.to_string())?;
    let v: serde_json::Value = resp.json().await.map_err(|e| e.to_string())?;
    let text = v["candidates"][0]["content"]["parts"][0]["text"].as_str().unwrap_or("").to_string();
    if text.is_empty() { Err("Aucun texte".into()) } else { Ok(text) }
}

async fn groq(s: &super::Settings, wav: &[u8]) -> Result<String, String> {
    let part = reqwest::multipart::Part::bytes(wav.to_vec())
        .file_name("audio.wav").mime_str("audio/wav").unwrap();
    let form = reqwest::multipart::Form::new()
        .text("model", s.groq_model.clone())
        .text("response_format", "verbose_json")
        .text("temperature", "0")
        .part("file", part);
    let resp = reqwest::Client::new()
        .post("https://api.groq.com/openai/v1/audio/transcriptions")
        .bearer_auth(&s.groq_key)
        .multipart(form)
        .send().await.map_err(|e| e.to_string())?;
    let v: serde_json::Value = resp.json().await.map_err(|e| e.to_string())?;
    let text = v["text"].as_str().unwrap_or("").to_string();
    if text.is_empty() { Err("Aucun texte".into()) } else { Ok(text) }
}

async fn cloudflare(s: &super::Settings, wav: &[u8]) -> Result<String, String> {
    let (account, token) = s.cf_key.split_once('|').ok_or("Format accountId|token")?;
    let url = format!("https://api.cloudflare.com/client/v4/accounts/{}/ai/run/{}", account.trim(), s.cf_model);
    let resp = reqwest::Client::new()
        .post(&url)
        .bearer_auth(token.trim())
        .header("Content-Type", "audio/wav")
        .body(wav.to_vec())
        .send().await.map_err(|e| e.to_string())?;
    let v: serde_json::Value = resp.json().await.map_err(|e| e.to_string())?;
    let text = v["result"]["text"].as_str().unwrap_or("").to_string();
    if text.is_empty() { Err("Aucun texte".into()) } else { Ok(text) }
}