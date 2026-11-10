use regex::Regex;
use std::sync::OnceLock;

fn re(cell: &'static OnceLock<Regex>, p: &str) -> &'static Regex {
    cell.get_or_init(|| Regex::new(p).unwrap())
}

/// Sorted multiset of format placeholders.
pub fn placeholders(s: &str) -> Vec<String> {
    static R: OnceLock<Regex> = OnceLock::new();
    let mut v: Vec<String> = re(&R, r"%(\d+\$)?[-#+ 0,(]*\d*(\.\d+)?[a-zA-Z%]")
        .find_iter(s)
        .map(|m| m.as_str().to_string())
        .collect();
    v.sort();
    v
}

/// Sorted multiset of opening tag names.
pub fn tag_names(s: &str) -> Vec<String> {
    static R: OnceLock<Regex> = OnceLock::new();
    let mut v: Vec<String> = re(&R, r"<([A-Za-z][\w:.\-]*)")
        .captures_iter(s)
        .map(|c| c[1].to_string())
        .collect();
    v.sort();
    v
}

fn newline_count(s: &str) -> usize {
    s.matches("\\n").count()
}

/// Text with tags removed (replaced by a neutral separator).
fn outside_tags(s: &str) -> String {
    static R: OnceLock<Regex> = OnceLock::new();
    re(&R, r"</?[A-Za-z][^<>]*>").replace_all(s, "\u{1}").into_owned()
}

/// Returns a problem description when the text is not valid Android XML string content.
pub fn escape_problem(s: &str) -> Option<String> {
    static ENT: OnceLock<Regex> = OnceLock::new();
    let t = outside_tags(s);
    let chars: Vec<char> = t.chars().collect();
    for (i, &c) in chars.iter().enumerate() {
        let escaped = i > 0 && chars[i - 1] == '\\';
        match c {
            '\'' | '"' if !escaped => return Some(format!("unescaped {c} (use \\{c})")),
            '<' | '>' => return Some(format!("stray '{c}' that is not part of a tag")),
            _ => {}
        }
    }
    let ent = re(&ENT, r"^&(amp|lt|gt|quot|apos|#\d+|#x[0-9a-fA-F]+);");
    for (i, c) in t.char_indices() {
        if c == '&' && !ent.is_match(&t[i..]) {
            return Some("raw '&' that is not an entity (use &amp;)".into());
        }
    }
    None
}

pub fn max_len(base: &str, ratio: f32) -> usize {
    let n = base.chars().count();
    let r: f64 = format!("{ratio}").parse().unwrap_or(1.2);
    let scaled = (n as f64 * r - 1e-9).ceil().max(0.0) as usize;
    scaled.max(n + 8)
}

#[derive(Debug, PartialEq)]
pub enum Verdict {
    Ok,
    /// Fails one of checks a-d.
    Bad(String),
    /// Only fails check e (too long).
    TooLong(usize),
}

pub fn validate(base: &str, tr: Option<&str>, max: usize) -> Verdict {
    let tr = match tr {
        Some(t) if !t.trim().is_empty() => t,
        _ => return Verdict::Bad("missing or empty".into()),
    };
    if placeholders(base) != placeholders(tr) {
        return Verdict::Bad(format!(
            "placeholders differ (expected {:?}, got {:?})",
            placeholders(base),
            placeholders(tr)
        ));
    }
    if tag_names(base) != tag_names(tr) || newline_count(base) != newline_count(tr) {
        return Verdict::Bad("tags or \\n count differ from the English text".into());
    }
    if let Some(p) = escape_problem(tr) {
        return Verdict::Bad(p);
    }
    let len = tr.chars().count();
    if len > max {
        return Verdict::TooLong(len);
    }
    Verdict::Ok
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn placeholder_multiset() {
        assert_eq!(placeholders("%1$s of %2$d"), placeholders("%2$d / %1$s"));
        assert_ne!(placeholders("%s %s"), placeholders("%s"));
        assert_ne!(placeholders("%d"), placeholders("%s"));
        assert_eq!(placeholders("100%% %.1f"), vec!["%%", "%.1f"]);
    }

    #[test]
    fn escape_check() {
        assert!(escape_problem("It\\'s fine").is_none());
        assert!(escape_problem("It's bad").is_some());
        assert!(escape_problem("say \\\"hi\\\"").is_none());
        assert!(escape_problem("say \"hi\"").is_some());
        assert!(escape_problem("A &amp; B &lt;").is_none());
        assert!(escape_problem("A & B").is_some());
        assert!(escape_problem("a < b").is_some());
        assert!(escape_problem("<b>bold</b> <xliff:g id=\"x\">%s</xliff:g>").is_none());
    }

    #[test]
    fn max_length_formula() {
        assert_eq!(max_len("0123456789", 1.2), 18); // len+8 wins
        assert_eq!(max_len(&"a".repeat(100), 1.2), 120);
        assert_eq!(max_len(&"a".repeat(50), 1.2), 60);
        assert_eq!(max_len("çğış", 1.2), 12); // counted in scalar values
    }

    #[test]
    fn verdicts() {
        assert_eq!(validate("Hi %s", Some("Merhaba %s"), 20), Verdict::Ok);
        assert!(matches!(validate("Hi %s", Some("Merhaba"), 20), Verdict::Bad(_)));
        assert!(matches!(validate("Hi", None, 20), Verdict::Bad(_)));
        assert_eq!(validate("Hi", Some("Merhaba dunya"), 10), Verdict::TooLong(13));
    }
}
