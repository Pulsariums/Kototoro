use regex::Regex;
use std::collections::HashSet;
use std::fs;
use std::path::Path;
use std::sync::OnceLock;

#[derive(Debug, Clone, PartialEq)]
pub struct Entry {
    pub name: String,
    pub value: String,
    /// Attributes other than name/translatable, as ` k="v"` text.
    pub extra_attrs: String,
}

fn strip_comments(s: &str) -> String {
    static R: OnceLock<Regex> = OnceLock::new();
    R.get_or_init(|| Regex::new(r"(?s)<!--.*?-->").unwrap()).replace_all(s, "").into_owned()
}

const STRING_RE: &str = r#"(?s)<string((?:\s+[\w:.\-]+\s*=\s*"[^"]*")*)\s*>(.*?)</string>"#;
const ATTR_RE: &str = r#"([\w:.\-]+)\s*=\s*"([^"]*)""#;

/// Collects translatable `<string>` elements from XML text, in file order.
pub fn scan_strings(xml: &str) -> Vec<Entry> {
    let xml = strip_comments(xml);
    let sre = Regex::new(STRING_RE).unwrap();
    let are = Regex::new(ATTR_RE).unwrap();
    let mut out = Vec::new();
    for c in sre.captures_iter(&xml) {
        let (mut name, mut translatable, mut extra) = (None, true, String::new());
        for a in are.captures_iter(&c[1]) {
            match &a[1] {
                "name" => name = Some(a[2].to_string()),
                "translatable" => translatable = &a[2] != "false",
                k => extra.push_str(&format!(" {k}=\"{}\"", &a[2])),
            }
        }
        let value = c[2].to_string();
        let Some(name) = name else { continue };
        if !translatable || value.starts_with('@') || !value.chars().any(|ch| ch.is_alphabetic()) {
            continue;
        }
        out.push(Entry { name, value, extra_attrs: extra });
    }
    out
}

/// Names defined by any `<string name="...">` in the text.
pub fn defined_names(xml: &str) -> Vec<String> {
    static R: OnceLock<Regex> = OnceLock::new();
    let r = R.get_or_init(|| Regex::new(r#"<string\s[^>]*?\bname\s*=\s*"([^"]*)""#).unwrap());
    r.captures_iter(&strip_comments(xml)).map(|c| c[1].to_string()).collect()
}

/// Names present in any xml file of a locale directory.
pub fn present_names(dir: &Path) -> HashSet<String> {
    let mut set = HashSet::new();
    if let Ok(rd) = fs::read_dir(dir) {
        for e in rd.flatten() {
            let p = e.path();
            if p.extension().is_some_and(|x| x == "xml") {
                if let Ok(s) = fs::read_to_string(&p) {
                    set.extend(defined_names(&s));
                }
            }
        }
    }
    set
}

pub fn render_line(e: &Entry, translation: &str) -> String {
    format!("    <string name=\"{}\"{}>{}</string>", e.name, e.extra_attrs, translation)
}

/// Inserts lines before the last `</resources>`; every existing byte is kept.
pub fn insert_into_existing(content: &str, lines: &[String]) -> Result<String, String> {
    let pos = content.rfind("</resources>").ok_or("no </resources> found")?;
    let mut ins = String::new();
    if !content[..pos].ends_with('\n') {
        ins.push('\n');
    }
    for l in lines {
        ins.push_str(l);
        ins.push('\n');
    }
    Ok(format!("{}{}{}", &content[..pos], ins, &content[pos..]))
}

/// Builds a new locale file, copying xmlns:* attributes from the base `<resources>` tag.
pub fn new_file(base_xml: &str, lines: &[String]) -> String {
    static R: OnceLock<Regex> = OnceLock::new();
    static X: OnceLock<Regex> = OnceLock::new();
    let tag = R.get_or_init(|| Regex::new(r"(?s)<resources([^>]*)>").unwrap());
    let xmlns = X.get_or_init(|| Regex::new(r#"xmlns:[\w.\-]+\s*=\s*"[^"]*""#).unwrap());
    let attrs: String = tag
        .captures(base_xml)
        .map(|c| xmlns.find_iter(&c[1]).map(|m| format!(" {}", m.as_str())).collect())
        .unwrap_or_default();
    format!(
        "<?xml version=\"1.0\" encoding=\"utf-8\"?>\n<resources{attrs}>\n{}\n</resources>\n",
        lines.join("\n")
    )
}

#[cfg(test)]
mod tests {
    use super::*;

    const SAMPLE: &str = r#"<?xml version="1.0" encoding="utf-8"?>
<resources xmlns:xliff="urn:oasis:names:tc:xliff:document:1.2">
    <!-- <string name="commented">Hidden</string> -->
    <string name="a">Hello <b>world</b> it\'s</string>
    <string name="multi">Line one
        line two &amp; more</string>
    <string name="skip" translatable="false">Kototoro</string>
    <string name="ref">@string/a</string>
    <string name="num">123 %d</string>
    <string name="fmt" formatted="false">100% done</string>
    <string name="x">Size <xliff:g id="s" example="1">%1$s</xliff:g></string>
    <plurals name="p"><item quantity="one">One thing</item></plurals>
    <string-array name="arr"><item>Nope</item></string-array>
</resources>
"#;

    #[test]
    fn scans_sample() {
        let e = scan_strings(SAMPLE);
        let names: Vec<_> = e.iter().map(|x| x.name.as_str()).collect();
        assert_eq!(names, ["a", "multi", "num", "fmt", "x"]);
        assert_eq!(e[0].value, "Hello <b>world</b> it\\'s");
        assert_eq!(e[1].value, "Line one\n        line two &amp; more");
        assert_eq!(e[3].extra_attrs, " formatted=\"false\"");
        assert!(e[4].value.contains("<xliff:g id=\"s\" example=\"1\">%1$s</xliff:g>"));
    }

    #[test]
    fn insert_keeps_existing_bytes() {
        let old = "<?xml version=\"1.0\" encoding=\"utf-8\"?>\n<resources>\n    <string name=\"a\">A</string>\n</resources>\n";
        let e = Entry { name: "b".into(), value: "B".into(), extra_attrs: " formatted=\"false\"".into() };
        let new = insert_into_existing(old, &[render_line(&e, "Bee")]).unwrap();
        let cut = old.rfind("</resources>").unwrap();
        assert!(new.starts_with(&old[..cut]));
        assert!(new.ends_with(&old[cut..]));
        assert!(new.contains("    <string name=\"b\" formatted=\"false\">Bee</string>\n</resources>"));
        assert_eq!(new.len(), old.len() + "    <string name=\"b\" formatted=\"false\">Bee</string>\n".len());
    }

    #[test]
    fn new_file_copies_xmlns() {
        let f = new_file(SAMPLE, &["    <string name=\"a\">X</string>".into()]);
        assert!(f.contains("<resources xmlns:xliff=\"urn:oasis:names:tc:xliff:document:1.2\">"));
        assert!(f.ends_with("</resources>\n"));
        assert_eq!(defined_names(&f), ["a"]);
    }
}
