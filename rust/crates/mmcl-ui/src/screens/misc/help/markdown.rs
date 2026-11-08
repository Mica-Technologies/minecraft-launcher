// Copyright (c) Mica Technologies. Licensed under the GNU GPL v3.

//! The help pages' HTML converted to Markdown for gpui-component's `TextView`.
//!
//! The bundled pages are static prose: headings, paragraphs, lists (ordered, unordered, nested),
//! tables, `strong` / `em`, `code` / `kbd`, links (`help://topic/X`), and callout boxes
//! (`div.tip` / `div.note` / `div.warning` with a `div.callout-title`). This is a small,
//! tolerant converter for exactly that subset: unknown tags are transparent, unknown entities stay
//! as written, and text is escaped so page prose can never turn into Markdown syntax.
//!
//! Callouts become block quotes headed by their bold title; `kbd` becomes inline code (the
//! closest Markdown has to a key cap).

use std::fmt::Write as _;

/// Converts a help page body (HTML fragment) to Markdown.
pub fn html_to_markdown(html: &str) -> String {
    let nodes = parse(html);
    let mut out = Vec::new();
    render_blocks(&nodes, &mut out);
    let mut md = out.join("\n\n");
    md.push('\n');
    md
}

/// `MCLauncherHelpWindow.escapeHtml`: escapes `& < > "` for text inserted into a page.
pub fn escape_html(text: &str) -> String {
    text.replace('&', "&amp;")
        .replace('<', "&lt;")
        .replace('>', "&gt;")
        .replace('"', "&quot;")
}

// ---------------------------------------------------------------------------------------------
// Parsing
// ---------------------------------------------------------------------------------------------

#[derive(Debug, Clone, PartialEq)]
enum Node {
    Text(String),
    Element(Element),
}

#[derive(Debug, Clone, PartialEq)]
struct Element {
    tag: String,
    attrs: Vec<(String, String)>,
    children: Vec<Node>,
}

impl Element {
    fn attr(&self, name: &str) -> Option<&str> {
        self.attrs
            .iter()
            .find(|(k, _)| k == name)
            .map(|(_, v)| v.as_str())
    }

    fn has_class(&self, class: &str) -> bool {
        self.attr("class")
            .is_some_and(|c| c.split_whitespace().any(|c| c == class))
    }
}

const VOID: &[&str] = &[
    "br", "hr", "img", "meta", "link", "input", "wbr", "source", "col",
];

const BLOCKS: &[&str] = &[
    "p",
    "div",
    "h1",
    "h2",
    "h3",
    "h4",
    "h5",
    "h6",
    "ul",
    "ol",
    "li",
    "table",
    "thead",
    "tbody",
    "tfoot",
    "tr",
    "td",
    "th",
    "pre",
    "blockquote",
    "hr",
    "section",
    "article",
    "header",
    "footer",
    "dl",
    "dt",
    "dd",
];

fn is_block(tag: &str) -> bool {
    BLOCKS.contains(&tag)
}

/// Parses an HTML fragment into a tree. Mismatched end tags close up to the nearest open element
/// of that name (or are ignored); `<p>` and `<li>` close implicitly like browsers do.
fn parse(html: &str) -> Vec<Node> {
    let mut stack: Vec<Element> = vec![Element {
        tag: String::new(),
        attrs: Vec::new(),
        children: Vec::new(),
    }];
    let bytes = html.as_bytes();
    let mut i = 0;
    let mut text_start = 0;

    fn flush_text(stack: &mut [Element], raw: &str) {
        if raw.is_empty() {
            return;
        }
        let text = decode_entities(raw);
        let top = stack.last_mut().expect("root stays on the stack");
        if let Some(Node::Text(prev)) = top.children.last_mut() {
            prev.push_str(&text);
        } else {
            top.children.push(Node::Text(text));
        }
    }

    fn close_top(stack: &mut Vec<Element>) {
        if stack.len() > 1 {
            let done = stack.pop().expect("checked");
            stack
                .last_mut()
                .expect("root stays")
                .children
                .push(Node::Element(done));
        }
    }

    while i < bytes.len() {
        if bytes[i] != b'<' {
            i += 1;
            continue;
        }
        // Comment.
        if html[i..].starts_with("<!--") {
            flush_text(&mut stack, &html[text_start..i]);
            let end = html[i + 4..]
                .find("-->")
                .map_or(html.len(), |e| i + 4 + e + 3);
            i = end;
            text_start = i;
            continue;
        }
        // Doctype / processing instruction.
        if html[i..].starts_with("<!") || html[i..].starts_with("<?") {
            flush_text(&mut stack, &html[text_start..i]);
            let end = html[i..].find('>').map_or(html.len(), |e| i + e + 1);
            i = end;
            text_start = i;
            continue;
        }
        let closing = bytes.get(i + 1) == Some(&b'/');
        let name_start = if closing { i + 2 } else { i + 1 };
        let name_len = html[name_start..]
            .bytes()
            .take_while(|b| b.is_ascii_alphanumeric())
            .count();
        if name_len == 0 {
            // A literal '<' in text.
            i += 1;
            continue;
        }
        let Some(tag_end) = find_tag_end(html, name_start + name_len) else {
            break;
        };
        flush_text(&mut stack, &html[text_start..i]);
        let tag = html[name_start..name_start + name_len].to_ascii_lowercase();
        let inner = &html[name_start + name_len..tag_end];
        i = tag_end + 1;
        text_start = i;

        if closing {
            if let Some(pos) = stack.iter().rposition(|e| e.tag == tag)
                && pos > 0
            {
                while stack.len() > pos {
                    close_top(&mut stack);
                }
            }
            continue;
        }

        // Implicit closes: a block start closes an open <p>; <li> closes a sibling <li>;
        // <tr>/<td>/<th> close their open siblings.
        if is_block(&tag)
            && let Some(pos) = stack.iter().rposition(|e| e.tag == "p")
            && pos > 0
            && stack[pos + 1..].iter().all(|e| !is_block(&e.tag))
        {
            while stack.len() > pos {
                close_top(&mut stack);
            }
        }
        let siblings: &[&str] = match tag.as_str() {
            "li" => &["li"],
            "tr" => &["tr", "td", "th"],
            "td" | "th" => &["td", "th"],
            "dt" | "dd" => &["dt", "dd"],
            _ => &[],
        };
        if !siblings.is_empty() {
            let boundary: &[&str] = match tag.as_str() {
                "li" => &["ul", "ol"],
                _ => &["table", "thead", "tbody", "tfoot", "tr"],
            };
            if let Some(pos) = stack
                .iter()
                .rposition(|e| siblings.contains(&e.tag.as_str()))
            {
                let blocked = stack[pos + 1..]
                    .iter()
                    .any(|e| boundary.contains(&e.tag.as_str()));
                let is_row_boundary = tag != "tr"
                    && stack[pos..].iter().any(|e| e.tag == "tr")
                    && stack[pos].tag == "tr";
                if pos > 0 && !blocked && !is_row_boundary {
                    while stack.len() > pos {
                        close_top(&mut stack);
                    }
                }
            }
        }

        let self_closing = inner.trim_end().ends_with('/');
        let element = Element {
            tag: tag.clone(),
            attrs: parse_attrs(inner.trim_end().trim_end_matches('/')),
            children: Vec::new(),
        };
        if self_closing || VOID.contains(&tag.as_str()) {
            stack
                .last_mut()
                .expect("root stays")
                .children
                .push(Node::Element(element));
        } else if tag == "script" || tag == "style" {
            // Raw text elements: skip their content entirely.
            let close = format!("</{tag}");
            let lower = html[i..].to_ascii_lowercase();
            let end = lower.find(&close).map_or(html.len(), |e| i + e);
            let after = html[end..].find('>').map_or(html.len(), |e| end + e + 1);
            i = after;
            text_start = i;
        } else {
            stack.push(element);
        }
    }
    flush_text(&mut stack, &html[text_start.min(html.len())..]);
    while stack.len() > 1 {
        close_top(&mut stack);
    }
    stack.pop().map(|root| root.children).unwrap_or_default()
}

/// The index of the `>` that ends a tag, skipping quoted attribute values.
fn find_tag_end(html: &str, from: usize) -> Option<usize> {
    let mut quote: Option<u8> = None;
    for (off, b) in html.as_bytes()[from..].iter().enumerate() {
        match (quote, *b) {
            (Some(q), c) if c == q => quote = None,
            (Some(_), _) => {}
            (None, b'"') | (None, b'\'') => quote = Some(*b),
            (None, b'>') => return Some(from + off),
            _ => {}
        }
    }
    None
}

fn parse_attrs(s: &str) -> Vec<(String, String)> {
    let mut attrs = Vec::new();
    let chars: Vec<char> = s.chars().collect();
    let mut i = 0;
    while i < chars.len() {
        while i < chars.len() && chars[i].is_whitespace() {
            i += 1;
        }
        let start = i;
        while i < chars.len() && !chars[i].is_whitespace() && chars[i] != '=' {
            i += 1;
        }
        if start == i {
            i += 1;
            continue;
        }
        let name: String = chars[start..i]
            .iter()
            .collect::<String>()
            .to_ascii_lowercase();
        while i < chars.len() && chars[i].is_whitespace() {
            i += 1;
        }
        let mut value = String::new();
        if i < chars.len() && chars[i] == '=' {
            i += 1;
            while i < chars.len() && chars[i].is_whitespace() {
                i += 1;
            }
            if i < chars.len() && (chars[i] == '"' || chars[i] == '\'') {
                let q = chars[i];
                i += 1;
                let vs = i;
                while i < chars.len() && chars[i] != q {
                    i += 1;
                }
                value = chars[vs..i].iter().collect();
                i += 1;
            } else {
                let vs = i;
                while i < chars.len() && !chars[i].is_whitespace() {
                    i += 1;
                }
                value = chars[vs..i].iter().collect();
            }
        }
        attrs.push((name, decode_entities(&value)));
    }
    attrs
}

/// Decodes the HTML entities the pages use (named ones and numeric). Unknown entities stay as
/// written.
pub(crate) fn decode_entities(s: &str) -> String {
    if !s.contains('&') {
        return s.to_string();
    }
    let mut out = String::with_capacity(s.len());
    let mut rest = s;
    while let Some(amp) = rest.find('&') {
        out.push_str(&rest[..amp]);
        let after = &rest[amp + 1..];
        let semi = after.find(';').filter(|&p| p <= 10);
        let decoded = semi.and_then(|p| decode_entity(&after[..p]).map(|c| (c, p)));
        match decoded {
            Some((c, p)) => {
                out.push_str(&c);
                rest = &after[p + 1..];
            }
            None => {
                out.push('&');
                rest = after;
            }
        }
    }
    out.push_str(rest);
    out
}

fn decode_entity(name: &str) -> Option<String> {
    if let Some(num) = name.strip_prefix('#') {
        let code = if let Some(hex) = num.strip_prefix('x').or_else(|| num.strip_prefix('X')) {
            u32::from_str_radix(hex, 16).ok()?
        } else {
            num.parse::<u32>().ok()?
        };
        return char::from_u32(code).map(String::from);
    }
    let c = match name {
        "amp" => '&',
        "lt" => '<',
        "gt" => '>',
        "quot" => '"',
        "apos" => '\'',
        "nbsp" => '\u{a0}',
        "mdash" => '\u{2014}',
        "ndash" => '\u{2013}',
        "hellip" => '\u{2026}',
        "larr" => '\u{2190}',
        "rarr" => '\u{2192}',
        "uarr" => '\u{2191}',
        "darr" => '\u{2193}',
        "harr" => '\u{2194}',
        "times" => '\u{d7}',
        "middot" => '\u{b7}',
        "bull" => '\u{2022}',
        "copy" => '\u{a9}',
        "reg" => '\u{ae}',
        "trade" => '\u{2122}',
        "deg" => '\u{b0}',
        "lsquo" => '\u{2018}',
        "rsquo" => '\u{2019}',
        "ldquo" => '\u{201c}',
        "rdquo" => '\u{201d}',
        "laquo" => '\u{ab}',
        "raquo" => '\u{bb}',
        "ccedil" => '\u{e7}',
        "Ccedil" => '\u{c7}',
        "ecirc" => '\u{ea}',
        "eacute" => '\u{e9}',
        "egrave" => '\u{e8}',
        "aacute" => '\u{e1}',
        "agrave" => '\u{e0}',
        "acirc" => '\u{e2}',
        "atilde" => '\u{e3}',
        "auml" => '\u{e4}',
        "iacute" => '\u{ed}',
        "oacute" => '\u{f3}',
        "ocirc" => '\u{f4}',
        "otilde" => '\u{f5}',
        "ouml" => '\u{f6}',
        "uacute" => '\u{fa}',
        "uuml" => '\u{fc}',
        "ntilde" => '\u{f1}',
        "szlig" => '\u{df}',
        _ => return None,
    };
    Some(c.to_string())
}

// ---------------------------------------------------------------------------------------------
// Rendering
// ---------------------------------------------------------------------------------------------

/// Escapes Markdown syntax characters in prose (CommonMark lets any ASCII punctuation be
/// backslash-escaped).
fn escape_md(text: &str) -> String {
    let mut out = String::with_capacity(text.len());
    for c in text.chars() {
        if matches!(
            c,
            '\\' | '`' | '*' | '_' | '[' | ']' | '<' | '>' | '#' | '|' | '~' | '!'
        ) {
            out.push('\\');
        }
        out.push(c);
    }
    out
}

/// Escapes what would start a block at the beginning of a paragraph (`- `, `+ `, `1. `).
fn escape_line_start(line: String) -> String {
    let trimmed = line.trim_start();
    if trimmed.starts_with("- ") || trimmed.starts_with("+ ") || trimmed == "-" || trimmed == "+" {
        return format!("\\{trimmed}");
    }
    let digits = trimmed.bytes().take_while(u8::is_ascii_digit).count();
    if digits > 0 && matches!(trimmed.as_bytes().get(digits), Some(b'.') | Some(b')')) {
        return format!("{}\\{}", &trimmed[..digits], &trimmed[digits..]);
    }
    line
}

/// An inline code span holding `text`, with a fence longer than any backtick run inside.
fn code_span(text: &str) -> String {
    let text = collapse_ws(text);
    let text = text.trim();
    if text.is_empty() {
        return String::new();
    }
    let longest = text.split(|c| c != '`').map(str::len).max().unwrap_or(0);
    let fence = "`".repeat(longest + 1);
    let pad = if text.starts_with('`') || text.ends_with('`') {
        " "
    } else {
        ""
    };
    format!("{fence}{pad}{text}{pad}{fence}")
}

fn collapse_ws(s: &str) -> String {
    let mut out = String::with_capacity(s.len());
    let mut space = false;
    for c in s.chars() {
        if c.is_whitespace() && c != '\u{a0}' {
            space = true;
        } else {
            if space {
                out.push(' ');
                space = false;
            }
            out.push(c);
        }
    }
    if space {
        out.push(' ');
    }
    out
}

/// Inline rendering context.
#[derive(Clone, Copy, Default)]
struct InlineCx {
    /// Inside a table cell: `|` is escaped (always is) and line breaks become spaces.
    in_cell: bool,
}

/// Renders inline content to a single Markdown string with HTML whitespace collapsing.
fn render_inline(nodes: &[Node], icx: InlineCx) -> String {
    let mut out = String::new();
    render_inline_into(nodes, icx, &mut out);
    let collapsed = normalise_spaces(&out);
    collapsed.trim().to_string()
}

/// Collapses runs of spaces left by joining inline pieces, keeping hard breaks.
fn normalise_spaces(s: &str) -> String {
    let mut out = String::with_capacity(s.len());
    let mut prev_space = false;
    for c in s.chars() {
        if c == ' ' {
            if !prev_space {
                out.push(' ');
            }
            prev_space = true;
        } else {
            out.push(c);
            prev_space = c == '\n';
        }
    }
    out
}

fn wrap_emphasis(inner: String, marker: &str) -> String {
    let trimmed = inner.trim();
    if trimmed.is_empty() {
        return inner;
    }
    let lead = if inner.starts_with(' ') { " " } else { "" };
    let trail = if inner.ends_with(' ') { " " } else { "" };
    format!("{lead}{marker}{trimmed}{marker}{trail}")
}

fn render_inline_into(nodes: &[Node], icx: InlineCx, out: &mut String) {
    for node in nodes {
        match node {
            Node::Text(t) => out.push_str(&escape_md(&collapse_ws(t))),
            Node::Element(e) => match e.tag.as_str() {
                "strong" | "b" => {
                    let mut inner = String::new();
                    render_inline_into(&e.children, icx, &mut inner);
                    out.push_str(&wrap_emphasis(inner, "**"));
                }
                "em" | "i" => {
                    let mut inner = String::new();
                    render_inline_into(&e.children, icx, &mut inner);
                    out.push_str(&wrap_emphasis(inner, "*"));
                }
                "code" | "kbd" | "samp" | "tt" => out.push_str(&code_span(&text_content(e))),
                "a" => {
                    let mut inner = String::new();
                    render_inline_into(&e.children, icx, &mut inner);
                    match e.attr("href").map(str::trim).filter(|h| !h.is_empty()) {
                        Some(href) => {
                            let text = inner.trim();
                            let text = if text.is_empty() {
                                escape_md(href)
                            } else {
                                text.to_string()
                            };
                            let href = href.replace(' ', "%20").replace(')', "%29");
                            let _ = write!(out, "[{text}](<{href}>)");
                        }
                        None => out.push_str(&inner),
                    }
                }
                "br" => {
                    if icx.in_cell {
                        out.push(' ');
                    } else {
                        out.push_str("\\\n");
                    }
                }
                "img" => {
                    if let Some(alt) = e.attr("alt").filter(|a| !a.is_empty()) {
                        out.push_str(&escape_md(alt));
                    }
                }
                _ => {
                    // Blocks nested in inline context (a list inside a cell): flatten.
                    if is_block(&e.tag) {
                        out.push(' ');
                    }
                    render_inline_into(&e.children, icx, out);
                    if is_block(&e.tag) {
                        out.push(' ');
                    }
                }
            },
        }
    }
}

fn text_content(e: &Element) -> String {
    let mut s = String::new();
    fn walk(nodes: &[Node], s: &mut String) {
        for n in nodes {
            match n {
                Node::Text(t) => s.push_str(t),
                Node::Element(e) => walk(&e.children, s),
            }
        }
    }
    walk(&e.children, &mut s);
    s
}

/// Whether a node is block-level content.
fn is_block_node(node: &Node) -> bool {
    matches!(node, Node::Element(e) if is_block(&e.tag))
}

/// Renders a node list as Markdown blocks: runs of inline content become paragraphs.
fn render_blocks(nodes: &[Node], out: &mut Vec<String>) {
    let mut run: Vec<Node> = Vec::new();
    let flush = |run: &mut Vec<Node>, out: &mut Vec<String>| {
        if run.is_empty() {
            return;
        }
        let text = render_inline(run, InlineCx::default());
        run.clear();
        if !text.is_empty() {
            out.push(escape_line_start(text));
        }
    };
    for node in nodes {
        if is_block_node(node) {
            flush(&mut run, out);
            if let Node::Element(e) = node {
                render_block(e, out);
            }
        } else {
            run.push(node.clone());
        }
    }
    flush(&mut run, out);
}

fn render_block(e: &Element, out: &mut Vec<String>) {
    match e.tag.as_str() {
        "h1" | "h2" | "h3" | "h4" | "h5" | "h6" => {
            let level = (e.tag.as_bytes()[1] - b'0') as usize;
            let text = render_inline(&e.children, InlineCx::default());
            if !text.is_empty() {
                out.push(format!("{} {}", "#".repeat(level), text));
            }
        }
        "p" | "dt" | "dd" => {
            let text = render_inline(&e.children, InlineCx::default());
            if !text.is_empty() {
                out.push(escape_line_start(text));
            }
        }
        "hr" => out.push("---".to_string()),
        "pre" => {
            let text = text_content(e);
            let text = text.trim_matches('\n');
            let longest = text
                .split(|c| c != '`')
                .map(str::len)
                .max()
                .unwrap_or(0)
                .max(2);
            let fence = "`".repeat(longest + 1);
            out.push(format!("{fence}\n{text}\n{fence}"));
        }
        "ul" | "ol" => out.push(render_list(e)),
        "table" => {
            if let Some(t) = render_table(e) {
                out.push(t);
            }
        }
        "blockquote" => {
            let mut inner = Vec::new();
            render_blocks(&e.children, &mut inner);
            if !inner.is_empty() {
                out.push(quote(&inner.join("\n\n")));
            }
        }
        "div" if is_callout(e) => out.push(render_callout(e)),
        _ => render_blocks(&e.children, out),
    }
}

fn is_callout(e: &Element) -> bool {
    ["tip", "note", "warning", "callout", "danger", "info"]
        .iter()
        .any(|c| e.has_class(c))
}

fn quote(text: &str) -> String {
    text.lines()
        .map(|l| {
            if l.is_empty() {
                ">".to_string()
            } else {
                format!("> {l}")
            }
        })
        .collect::<Vec<_>>()
        .join("\n")
}

fn render_callout(e: &Element) -> String {
    let mut blocks = Vec::new();
    let mut rest = Vec::new();
    for child in &e.children {
        match child {
            Node::Element(c) if c.has_class("callout-title") => {
                let title = render_inline(&c.children, InlineCx::default());
                if !title.is_empty() {
                    blocks.push(format!("**{title}**"));
                }
            }
            other => rest.push(other.clone()),
        }
    }
    render_blocks(&rest, &mut blocks);
    quote(&blocks.join("\n\n"))
}

fn render_list(e: &Element) -> String {
    let ordered = e.tag == "ol";
    let mut n: usize = e
        .attr("start")
        .and_then(|s| s.trim().parse().ok())
        .unwrap_or(1);
    let mut items = Vec::new();
    for child in &e.children {
        let Node::Element(li) = child else {
            continue;
        };
        if li.tag != "li" {
            // A stray nested list directly in a list: attach to the previous item.
            if li.tag == "ul" || li.tag == "ol" {
                let nested = indent(&render_list(li), if ordered { 3 } else { 2 });
                if let Some(last) = items.last_mut() {
                    let last: &mut String = last;
                    last.push('\n');
                    last.push_str(&nested);
                }
            }
            continue;
        }
        let marker = if ordered {
            let m = format!("{n}. ");
            n += 1;
            m
        } else {
            "- ".to_string()
        };
        let mut blocks = Vec::new();
        render_blocks(&li.children, &mut blocks);
        // A nested list follows its item's text directly (keeps the outer list tight);
        // paragraphs never start with a list marker (`escape_line_start`), so this is exact.
        let mut body = String::new();
        for (i, block) in blocks.iter().enumerate() {
            if i > 0 {
                body.push_str(if starts_with_list_marker(block) {
                    "\n"
                } else {
                    "\n\n"
                });
            }
            body.push_str(block);
        }
        let pad = marker.len();
        let mut lines = body.lines();
        let first = lines.next().unwrap_or("");
        let mut item = format!("{marker}{first}");
        for line in lines {
            item.push('\n');
            if !line.is_empty() {
                item.push_str(&" ".repeat(pad));
                item.push_str(line);
            }
        }
        items.push(item.trim_end().to_string());
    }
    // Loose lists (items with several blocks) need blank lines between items.
    let loose = items.iter().any(|i| i.contains("\n\n"));
    items.join(if loose { "\n\n" } else { "\n" })
}

fn starts_with_list_marker(block: &str) -> bool {
    if block.starts_with("- ") {
        return true;
    }
    let digits = block.bytes().take_while(u8::is_ascii_digit).count();
    digits > 0 && block[digits..].starts_with(". ")
}

fn indent(text: &str, by: usize) -> String {
    let pad = " ".repeat(by);
    text.lines()
        .map(|l| {
            if l.is_empty() {
                String::new()
            } else {
                format!("{pad}{l}")
            }
        })
        .collect::<Vec<_>>()
        .join("\n")
}

fn collect_rows<'a>(e: &'a Element, rows: &mut Vec<(&'a Element, bool)>, in_head: bool) {
    for child in &e.children {
        if let Node::Element(c) = child {
            match c.tag.as_str() {
                "tr" => rows.push((c, in_head)),
                "thead" => collect_rows(c, rows, true),
                "tbody" | "tfoot" => collect_rows(c, rows, false),
                _ => {}
            }
        }
    }
}

fn render_table(e: &Element) -> Option<String> {
    let mut rows = Vec::new();
    collect_rows(e, &mut rows, false);
    if rows.is_empty() {
        return None;
    }
    let cells = |tr: &Element| -> Vec<(String, bool)> {
        tr.children
            .iter()
            .filter_map(|n| match n {
                Node::Element(c) if c.tag == "td" || c.tag == "th" => Some((
                    render_inline(&c.children, InlineCx { in_cell: true }),
                    c.tag == "th",
                )),
                _ => None,
            })
            .collect()
    };
    let mut parsed: Vec<(Vec<String>, bool)> = rows
        .iter()
        .map(|(tr, head)| {
            let c = cells(tr);
            let all_th = !c.is_empty() && c.iter().all(|(_, th)| *th);
            (c.into_iter().map(|(t, _)| t).collect(), *head || all_th)
        })
        .collect();
    parsed.retain(|(c, _)| !c.is_empty());
    if parsed.is_empty() {
        return None;
    }
    let width = parsed
        .iter()
        .map(|(c, _)| c.len())
        .max()
        .unwrap_or(1)
        .max(1);
    // GFM needs exactly one header row: the first head row, else an empty header.
    let head_pos = parsed.iter().position(|(_, h)| *h);
    let header: Vec<String> = match head_pos {
        Some(p) => parsed.remove(p).0,
        None => vec![String::new(); width],
    };
    let line = |cells: &[String]| -> String {
        let mut s = String::from("|");
        for i in 0..width {
            let cell = cells.get(i).map(String::as_str).unwrap_or("");
            let _ = write!(s, " {cell} |");
        }
        s
    };
    let mut out = vec![line(&header)];
    out.push(format!("|{}", " --- |".repeat(width)));
    for (row, _) in &parsed {
        out.push(line(row));
    }
    Some(out.join("\n"))
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn headings_paragraphs_and_inline() {
        let md = html_to_markdown(
            "<h1>Title</h1>\n<p>Hello <strong>bold</strong> and <em>it</em>,\n  wrapped\n line.</p>",
        );
        assert_eq!(md, "# Title\n\nHello **bold** and *it*, wrapped line.\n");
    }

    #[test]
    fn entities_decode_and_prose_is_escaped() {
        let md = html_to_markdown(
            "<p>Install &amp; manage &mdash; 2 * 3 &lt;tag&gt; [x] &#20013;&#x41;</p>",
        );
        assert_eq!(
            md,
            "Install & manage \u{2014} 2 \\* 3 \\<tag\\> \\[x\\] \u{4e2d}A\n"
        );
        assert_eq!(decode_entities("a &unknown; b"), "a &unknown; b");
        assert_eq!(
            decode_entities("&rarr;&larr;&ccedil;"),
            "\u{2192}\u{2190}\u{e7}"
        );
    }

    #[test]
    fn links_keep_the_help_scheme() {
        let md = html_to_markdown(r#"<p>See <a href="help://topic/LOGIN">Login</a>.</p>"#);
        assert_eq!(md, "See [Login](<help://topic/LOGIN>).\n");
    }

    #[test]
    fn kbd_and_code_become_code_spans() {
        let md = html_to_markdown("<p><kbd>Ctrl</kbd> + <kbd>,</kbd> runs <code>a`b</code></p>");
        assert_eq!(md, "`Ctrl` + `,` runs ``a`b``\n");
    }

    #[test]
    fn lists_nested_and_ordered() {
        let md = html_to_markdown(
            "<ol class=\"steps\"><li><strong>Sign in</strong> first.</li><li>Then\n<ul><li>a</li><li>b</li></ul></li></ol>",
        );
        assert_eq!(md, "1. **Sign in** first.\n2. Then\n   - a\n   - b\n");
        let loose = html_to_markdown("<ul><li><p>one</p><p>more</p></li><li>two</li></ul>");
        assert_eq!(loose, "- one\n\n  more\n\n- two\n");
        let ul = html_to_markdown("<ul><li>one<li>two</ul>");
        assert_eq!(ul, "- one\n- two\n");
    }

    #[test]
    fn tables_become_gfm_tables() {
        let md = html_to_markdown(
            "<table class=\"shortcut-table\"><thead><tr><th>Shortcut</th><th>Action</th></tr></thead>\
             <tbody><tr><td><kbd>F1</kbd></td><td>Open | help</td></tr><tr><td>x</td></tr></tbody></table>",
        );
        assert_eq!(
            md,
            "| Shortcut | Action |\n| --- | --- |\n| `F1` | Open \\| help |\n| x |  |\n"
        );
    }

    #[test]
    fn headerless_tables_get_an_empty_header() {
        let md = html_to_markdown("<table><tr><td>a</td><td>b</td></tr></table>");
        assert_eq!(md, "|  |  |\n| --- | --- |\n| a | b |\n");
    }

    #[test]
    fn callouts_become_quotes_with_a_bold_title() {
        let md = html_to_markdown(
            "<div class=\"tip\">\n<div class=\"callout-title\">Tip</div>\n<p>Look for <strong>?</strong>.</p>\n</div>",
        );
        assert_eq!(
            md,
            "> **Tip**\n>\n> Look for **\\?**.\n".replace("\\?", "?")
        );
    }

    #[test]
    fn block_starts_are_escaped() {
        assert_eq!(html_to_markdown("<p>- not a list</p>"), "\\- not a list\n");
        assert_eq!(
            html_to_markdown("<p>1. not a list</p>"),
            "1\\. not a list\n"
        );
        assert_eq!(
            html_to_markdown("<p># not a heading</p>"),
            "\\# not a heading\n"
        );
    }

    #[test]
    fn whitespace_inside_emphasis_moves_outside() {
        assert_eq!(
            html_to_markdown("<p>a<strong> b </strong>c</p>"),
            "a **b** c\n"
        );
    }

    #[test]
    fn comments_scripts_and_unknown_tags() {
        let md = html_to_markdown(
            "<!-- hi --><section><p>x<span>y</span></p></section><script>alert(1)</script><p>z</p>",
        );
        assert_eq!(md, "xy\n\nz\n");
    }

    #[test]
    fn html_escape_matches_java() {
        assert_eq!(
            escape_html("Language & \"Localization\" <x>"),
            "Language &amp; &quot;Localization&quot; &lt;x&gt;"
        );
        assert_eq!(escape_html(""), "");
    }

    #[test]
    fn mismatched_tags_do_not_lose_text() {
        let md = html_to_markdown("<p>a <strong>b</p><p>c</em></p>");
        assert!(
            md.contains('a') && md.contains('b') && md.contains('c'),
            "{md}"
        );
    }
}
