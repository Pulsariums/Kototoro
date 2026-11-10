use serde_json::{json, Map, Value};
use std::collections::HashMap;
use std::time::Duration;

pub struct Client {
    http: reqwest::blocking::Client,
    endpoint: String,
    token: String,
    pub model: String,
}

pub struct Item<'a> {
    pub key: &'a str,
    pub text: &'a str,
    pub max: usize,
    pub problem: Option<&'a str>,
}

const WAITS: [u64; 3] = [5, 15, 45];

const PRODUCT_NAMES: &str = "Kototoro, Mihon, Aniyomi, Tachiyomi, IReader, Tsundoku, LNReader, Legado, TVBox, Cloudstream, Cloudflare, WebDAV, Google Drive, AniList, MyAnimeList, Kitsu, Shikimori, Bangumi, GitHub, Discord, Telegram, JAR, APK, JSON, URL, OCR, TTS, NSFW, DNS, HTTP, mpv";

fn system_prompt(lang: &str) -> String {
    format!(
        "You translate Android app UI strings from English to {lang}. The app is a manga/anime/novel reader. \
Use the natural, concise wording a native user expects in app UIs, and keep terminology consistent across the batch.\n\
The user message is a JSON object: {{\"KEY\": {{\"text\": \"English string\", \"max\": N}}}}. A key may also carry \"problem\": your previous translation of that key was rejected for that reason; fix it.\n\
Return ONLY a JSON object mapping each key to its translation string. No commentary, no code fences.\n\
Rules:\n\
- Keep every format placeholder (%s, %d, %1$s, %2$d, %%, etc.) exactly; never drop or add one. Reordering positional placeholders is allowed.\n\
- Keep \\n, \\', \\\", &amp;, &lt;, &gt;, HTML tags and <xliff:g ...>...</xliff:g> blocks unchanged (do not translate the content of xliff:g blocks).\n\
- Escape every apostrophe as \\' and every double quote as \\\" in the translation.\n\
- Never translate product and technical names: {PRODUCT_NAMES}.\n\
- Each translation must be at most its \"max\" characters. Choose a shorter natural wording rather than exceed it."
    )
}

impl Client {
    pub fn new(endpoint: &str, token: &str, model: &str) -> Result<Self, String> {
        let http = reqwest::blocking::Client::builder()
            .timeout(Duration::from_secs(240))
            .build()
            .map_err(|e| e.to_string())?;
        Ok(Self {
            http,
            endpoint: endpoint.trim_end_matches('/').to_string(),
            token: token.to_string(),
            model: model.to_string(),
        })
    }

    /// Ok(None) when the endpoint does not list model ids.
    pub fn list_models(&self) -> Result<Option<Vec<String>>, String> {
        let r = self
            .http
            .get(format!("{}/models", self.endpoint))
            .bearer_auth(&self.token)
            .send()
            .map_err(|e| e.to_string())?;
        if !r.status().is_success() {
            return Err(format!("HTTP {}", r.status()));
        }
        let v: Value = r.json().map_err(|e| e.to_string())?;
        let ids: Vec<String> = v["data"]
            .as_array()
            .map(|a| a.iter().filter_map(|m| m["id"].as_str().map(String::from)).collect())
            .unwrap_or_default();
        Ok(if ids.is_empty() { None } else { Some(ids) })
    }

    /// One batch request with retries. Err = whole batch failed.
    pub fn translate(&self, lang: &str, items: &[Item]) -> Result<HashMap<String, String>, String> {
        let mut user = Map::new();
        for it in items {
            let mut o = Map::new();
            o.insert("text".into(), json!(it.text));
            o.insert("max".into(), json!(it.max));
            if let Some(p) = it.problem {
                o.insert("problem".into(), json!(p));
            }
            user.insert(it.key.to_string(), Value::Object(o));
        }
        let body = json!({
            "model": self.model,
            "temperature": 0.2,
            "messages": [
                {"role": "system", "content": system_prompt(lang)},
                {"role": "user", "content": Value::Object(user).to_string()},
            ],
        });
        let mut last = String::new();
        for attempt in 0..=WAITS.len() {
            if attempt > 0 {
                std::thread::sleep(Duration::from_secs(WAITS[attempt - 1]));
            }
            match self.http.post(format!("{}/chat/completions", self.endpoint)).bearer_auth(&self.token).json(&body).send() {
                Err(e) => last = e.to_string(),
                Ok(r) => {
                    let st = r.status();
                    if st.is_success() {
                        let v: Value = r.json().map_err(|e| format!("bad response body: {e}"))?;
                        let content = v["choices"][0]["message"]["content"]
                            .as_str()
                            .ok_or("reply has no choices[0].message.content")?;
                        return parse_reply(content);
                    }
                    let text = r.text().unwrap_or_default();
                    last = format!("HTTP {st}: {}", text.chars().take(200).collect::<String>());
                    if !(st.as_u16() == 429 || st.is_server_error()) {
                        return Err(last);
                    }
                }
            }
        }
        Err(last)
    }
}

pub fn parse_reply(content: &str) -> Result<HashMap<String, String>, String> {
    let mut s = content.trim();
    if let Some(r) = s.strip_prefix("```json").or_else(|| s.strip_prefix("```")) {
        s = r.trim_start();
        s = s.strip_suffix("```").unwrap_or(s).trim();
    }
    let v: Value = serde_json::from_str(s).map_err(|e| format!("reply is not JSON: {e}"))?;
    let o = v.as_object().ok_or("reply is not a JSON object")?;
    Ok(o.iter().filter_map(|(k, v)| v.as_str().map(|t| (k.clone(), t.to_string()))).collect())
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn strips_fence() {
        let m = parse_reply("```json\n{\"a\": \"b\"}\n```").unwrap();
        assert_eq!(m["a"], "b");
    }
}
