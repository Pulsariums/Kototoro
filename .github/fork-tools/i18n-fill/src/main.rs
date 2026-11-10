mod api;
mod check;
mod xml;

use clap::Parser;
use std::collections::{BTreeMap, HashSet};
use std::fmt::Write as _;
use std::fs;
use std::io::Write as _;
use std::path::{Path, PathBuf};
use std::process::exit;

#[derive(Parser)]
#[command(about = "Fill missing Android string translations via an OpenAI-compatible endpoint")]
struct Args {
    /// Path to app/src/main/res
    #[arg(long)]
    res: PathBuf,
    /// Android locale qualifiers, e.g. tr,de,pt-rBR
    #[arg(long, value_delimiter = ',', required = true)]
    langs: Vec<String>,
    /// OpenAI-compatible base URL, e.g. https://host/maestro/v1
    #[arg(long)]
    endpoint: Option<String>,
    #[arg(long, default_value = "gemini-3.5-flash-lite")]
    model: String,
    #[arg(long, default_value_t = 1.2)]
    max_ratio: f32,
    #[arg(long, default_value_t = 50)]
    batch: usize,
    /// Max keys per language (trial runs)
    #[arg(long)]
    limit: Option<usize>,
    /// Only report how many keys are missing
    #[arg(long)]
    dry_run: bool,
}

struct BaseFile {
    name: String,
    xml: String,
}

struct Missing {
    file: usize,
    entry: xml::Entry,
    max: usize,
}

#[derive(Default)]
struct LangResult {
    accepted: Vec<(usize, String)>, // index into missing
    long: Vec<(String, String)>,
    dropped: Vec<(String, String)>,
    batches: usize,
    batches_ok: usize,
}

fn die(msg: &str) -> ! {
    eprintln!("error: {msg}");
    exit(2)
}

fn lang_label(q: &str) -> String {
    let name = match q {
        "ar" => "Arabic", "bn" => "Bengali", "cs" => "Czech", "de" => "German", "el" => "Greek",
        "es" => "Spanish", "fa" => "Persian", "fi" => "Finnish", "fr" => "French", "he" | "iw" => "Hebrew",
        "hi" => "Hindi", "hu" => "Hungarian", "id" | "in" => "Indonesian", "it" => "Italian",
        "ja" => "Japanese", "ko" => "Korean", "ms" => "Malay", "nl" => "Dutch", "pl" => "Polish",
        "pt" => "Portuguese", "pt-rBR" => "Brazilian Portuguese", "pt-rPT" => "European Portuguese",
        "ro" => "Romanian", "ru" => "Russian", "sv" => "Swedish", "th" => "Thai", "tr" => "Turkish",
        "uk" => "Ukrainian", "vi" => "Vietnamese", "zh-rCN" => "Simplified Chinese",
        "zh-rTW" | "zh-rHK" => "Traditional Chinese", "es-rMX" => "Latin American Spanish",
        "fil" => "Filipino", "ca" => "Catalan", "be" => "Belarusian", "bg" => "Bulgarian",
        "da" => "Danish", "nb" => "Norwegian Bokmal", "sk" => "Slovak", "sr" => "Serbian",
        "hr" => "Croatian", "az" => "Azerbaijani", "ka" => "Georgian", "kk" => "Kazakh",
        _ => return q.to_string(),
    };
    format!("{name} ({q})")
}

fn locale_dir(res: &Path, lang: &str) -> PathBuf {
    res.join(format!("values-{lang}"))
}

fn load_bases(res: &Path) -> Vec<BaseFile> {
    let dir = res.join("values");
    let mut names: Vec<String> = fs::read_dir(&dir)
        .unwrap_or_else(|e| die(&format!("cannot read {}: {e}", dir.display())))
        .flatten()
        .filter_map(|e| e.file_name().into_string().ok())
        .filter(|n| n.starts_with("strings") && n.ends_with(".xml"))
        .collect();
    names.sort();
    names
        .into_iter()
        .map(|name| {
            let xml = fs::read_to_string(dir.join(&name)).unwrap_or_else(|e| die(&format!("{name}: {e}")));
            BaseFile { name, xml }
        })
        .collect()
}

fn find_missing(res: &Path, lang: &str, bases: &[BaseFile], ratio: f32) -> Vec<Missing> {
    let present = xml::present_names(&locale_dir(res, lang));
    let mut seen = HashSet::new();
    let mut out = Vec::new();
    for (i, b) in bases.iter().enumerate() {
        for e in xml::scan_strings(&b.xml) {
            if !present.contains(&e.name) && seen.insert(e.name.clone()) {
                let max = check::max_len(&e.value, ratio);
                out.push(Missing { file: i, entry: e, max });
            }
        }
    }
    out
}

fn translate_lang(client: &api::Client, lang: &str, missing: &[Missing], batch: usize) -> LangResult {
    let label = lang_label(lang);
    let mut res = LangResult::default();
    // (index, reason, first-round translation)
    let mut rejected: Vec<(usize, String, Option<String>)> = Vec::new();
    let mut offset = 0;
    for chunk in missing.chunks(batch.max(1)) {
        let items: Vec<api::Item> = chunk
            .iter()
            .map(|m| api::Item { key: &m.entry.name, text: &m.entry.value, max: m.max, problem: None })
            .collect();
        res.batches += 1;
        eprintln!("[{lang}] batch {} ({} keys)", res.batches, chunk.len());
        match client.translate(&label, &items) {
            Err(e) => {
                for m in chunk {
                    res.dropped.push((m.entry.name.clone(), format!("request failed: {e}")));
                }
            }
            Ok(map) => {
                res.batches_ok += 1;
                for (j, m) in chunk.iter().enumerate() {
                    let tr = map.get(&m.entry.name);
                    match check::validate(&m.entry.value, tr.map(|s| s.as_str()), m.max) {
                        check::Verdict::Ok => res.accepted.push((offset + j, tr.unwrap().clone())),
                        check::Verdict::Bad(r) => rejected.push((offset + j, r, tr.cloned())),
                        check::Verdict::TooLong(l) => {
                            rejected.push((offset + j, format!("too long: {l} characters, max {}", m.max), tr.cloned()))
                        }
                    }
                }
            }
        }
        offset += chunk.len();
    }
    if rejected.is_empty() {
        return res;
    }
    eprintln!("[{lang}] retrying {} rejected keys", rejected.len());
    let items: Vec<api::Item> = rejected
        .iter()
        .map(|(i, r, _)| api::Item {
            key: &missing[*i].entry.name,
            text: &missing[*i].entry.value,
            max: missing[*i].max,
            problem: Some(r),
        })
        .collect();
    let second = client.translate(&label, &items).unwrap_or_default();
    for (i, _, first) in &rejected {
        let m = &missing[*i];
        let cand = second.get(&m.entry.name).or(first.as_ref());
        let cap = (m.entry.value.chars().count() as f64 * 1.5 - 1e-9).ceil() as usize;
        match check::validate(&m.entry.value, cand.map(|s| s.as_str()), m.max) {
            check::Verdict::Ok => res.accepted.push((*i, cand.unwrap().clone())),
            check::Verdict::Bad(r) => res.dropped.push((m.entry.name.clone(), r)),
            check::Verdict::TooLong(l) if l <= cap => {
                res.long.push((m.entry.name.clone(), format!("{l} chars, max {}", m.max)));
                res.accepted.push((*i, cand.unwrap().clone()));
            }
            check::Verdict::TooLong(l) => {
                res.dropped.push((m.entry.name.clone(), format!("too long: {l} characters, max {}", m.max)))
            }
        }
    }
    res.accepted.sort_by_key(|(i, _)| *i);
    res
}

fn write_lang(res_dir: &Path, lang: &str, bases: &[BaseFile], missing: &[Missing], accepted: &[(usize, String)]) -> Result<(), String> {
    let mut per_file: BTreeMap<usize, Vec<String>> = BTreeMap::new();
    for (i, t) in accepted {
        per_file.entry(missing[*i].file).or_default().push(xml::render_line(&missing[*i].entry, t));
    }
    let dir = locale_dir(res_dir, lang);
    for (fi, lines) in per_file {
        let path = dir.join(&bases[fi].name);
        let out = if path.exists() {
            let old = fs::read_to_string(&path).map_err(|e| format!("{}: {e}", path.display()))?;
            xml::insert_into_existing(&old, &lines).map_err(|e| format!("{}: {e}", path.display()))?
        } else {
            fs::create_dir_all(&dir).map_err(|e| e.to_string())?;
            xml::new_file(&bases[fi].xml, &lines)
        };
        fs::write(&path, out).map_err(|e| format!("{}: {e}", path.display()))?;
    }
    Ok(())
}

fn list_section(out: &mut String, title: &str, rows: &[(String, String)]) {
    if rows.is_empty() {
        return;
    }
    let _ = writeln!(out, "\n{title}:");
    for (k, r) in rows.iter().take(200) {
        let _ = writeln!(out, "- `{k}`: {r}");
    }
    if rows.len() > 200 {
        let _ = writeln!(out, "- ... and {} more", rows.len() - 200);
    }
}

fn main() {
    let a = Args::parse();
    let bases = load_bases(&a.res);
    if bases.is_empty() {
        die("no strings*.xml found in <res>/values");
    }
    let mut report = String::from("## i18n-fill\n\n");
    let mut exit_code = 0;

    if a.dry_run {
        for lang in &a.langs {
            let missing = find_missing(&a.res, lang, &bases, a.max_ratio);
            let _ = writeln!(report, "{lang}: missing {}", missing.len());
            for (i, b) in bases.iter().enumerate() {
                let n = missing.iter().filter(|m| m.file == i).count();
                if n > 0 {
                    let _ = writeln!(report, "  - {}: {n}", b.name);
                }
            }
        }
    } else {
        let endpoint = a.endpoint.clone().unwrap_or_else(|| die("--endpoint is required without --dry-run"));
        let token = std::env::var("MAESTRO_TOKEN").unwrap_or_else(|_| die("MAESTRO_TOKEN is not set"));
        let client = api::Client::new(&endpoint, &token, &a.model).unwrap_or_else(|e| die(&e));
        match client.list_models() {
            Ok(Some(ids)) if !ids.contains(&a.model) => {
                die(&format!("model '{}' not available. Available models:\n{}", a.model, ids.join("\n")))
            }
            Ok(_) => {}
            Err(e) => eprintln!("warning: could not list models ({e}); continuing"),
        }
        for lang in &a.langs {
            let mut missing = find_missing(&a.res, lang, &bases, a.max_ratio);
            let total = missing.len();
            if let Some(l) = a.limit {
                missing.truncate(l);
            }
            let r = translate_lang(&client, lang, &missing, a.batch);
            if let Err(e) = write_lang(&a.res, lang, &bases, &missing, &r.accepted) {
                eprintln!("error writing {lang}: {e}");
                exit_code = 2;
            }
            if r.batches > 0 && r.batches_ok == 0 {
                eprintln!("error: every request failed for {lang}");
                exit_code = exit_code.max(1);
            }
            let _ = writeln!(
                report,
                "{lang}: missing {total}, translated {}, long {}, dropped {}",
                r.accepted.len(),
                r.long.len(),
                r.dropped.len()
            );
            list_section(&mut report, &format!("Long ({lang})"), &r.long);
            list_section(&mut report, &format!("Dropped ({lang})"), &r.dropped);
            report.push('\n');
        }
    }

    print!("{report}");
    if let Ok(p) = std::env::var("GITHUB_STEP_SUMMARY") {
        if let Ok(mut f) = fs::OpenOptions::new().create(true).append(true).open(p) {
            let _ = f.write_all(report.as_bytes());
        }
    }
    exit(exit_code);
}
