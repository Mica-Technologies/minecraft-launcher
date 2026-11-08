// Copyright (c) Mica Technologies. Licensed under the GNU GPL v3.

//! The Settings page's "Connecting a client" snippets (Java `McpClientSetup`). Pure strings.

use serde_json::json;

/// The server's name in client configs.
pub const SERVER_NAME: &str = "mica-launcher";

/// Shown for the stdio option when there is no launcher binary to point at.
pub const UNPACKAGED_NOTICE: &str = "This launcher is running from source rather than an installed build, so there is no\nlauncher command to give a client. Use one of the HTTP options instead --\nthey do not need a launcher path.";

/// The clients the Settings page offers, in UI order.
#[derive(Clone, Copy, Debug, PartialEq, Eq, Hash)]
pub enum Client {
    ClaudeCode,
    Cursor,
    Codex,
    Http,
    Stdio,
}

impl Client {
    pub const ALL: [Client; 5] = [
        Client::ClaudeCode,
        Client::Cursor,
        Client::Codex,
        Client::Http,
        Client::Stdio,
    ];

    /// `settings.mcp.connect.client.*`.
    pub fn label_key(self) -> &'static str {
        match self {
            Client::ClaudeCode => "settings.mcp.connect.client.claudeCode",
            Client::Cursor => "settings.mcp.connect.client.cursor",
            Client::Codex => "settings.mcp.connect.client.codex",
            Client::Http => "settings.mcp.connect.client.http",
            Client::Stdio => "settings.mcp.connect.client.stdio",
        }
    }
}

/// `http://127.0.0.1:<port>/mcp`.
pub fn endpoint_url(port: u16) -> String {
    format!("http://127.0.0.1:{port}/mcp")
}

fn is_jar(path: &str) -> bool {
    path.to_lowercase().ends_with(".jar")
}

/// Whether `path` is a launcher a client can run (not blank, not a bare JVM).
pub fn is_usable_launcher_path(path: &str) -> bool {
    if path.trim().is_empty() {
        return false;
    }
    if is_jar(path) {
        return true;
    }
    let norm = path.replace('\\', "/");
    let base = norm.rsplit('/').next().unwrap_or("").to_lowercase();
    !matches!(base.as_str(), "java" | "java.exe" | "javaw.exe")
}

/// The command a stdio client spawns.
pub fn relay_command(path: &str, native: bool) -> Vec<String> {
    if native {
        vec![path.to_string(), "--mcp".to_string()]
    } else {
        vec![
            "java".to_string(),
            "-jar".to_string(),
            path.to_string(),
            "--mcp".to_string(),
        ]
    }
}

/// Bare when safe (`[A-Za-z0-9_./:=+-]+`), otherwise single-quoted POSIX style.
pub fn shell_quote(v: &str) -> String {
    if v.is_empty() {
        return "''".to_string();
    }
    if v.chars()
        .all(|c| c.is_ascii_alphanumeric() || "_./:=+-".contains(c))
    {
        return v.to_string();
    }
    format!("'{}'", v.replace('\'', "'\\''"))
}

/// A TOML basic string.
pub fn toml_string(v: &str) -> String {
    format!("\"{}\"", v.replace('\\', "\\\\").replace('"', "\\\""))
}

/// The snippet for one client. A blank token shows as `<token>`; the stdio snippet embeds no
/// token at all (the relay reads it from the endpoint file).
pub fn snippet(
    client: Client,
    launcher_path: &str,
    native: bool,
    port: u16,
    endpoint_file: &str,
    token: Option<&str>,
) -> String {
    let tok = token.filter(|t| !t.trim().is_empty()).unwrap_or("<token>");
    let url = endpoint_url(port);
    match client {
        Client::ClaudeCode => format!(
            "claude mcp add --transport http {SERVER_NAME} {url} --header {}",
            shell_quote(&format!("Authorization: Bearer {tok}"))
        ),
        Client::Cursor => {
            let v = json!({"mcpServers":{SERVER_NAME:{"url":url,"headers":{"Authorization":format!("Bearer {tok}")}}}});
            format!(
                "// ~/.cursor/mcp.json\n{}",
                serde_json::to_string_pretty(&v).unwrap_or_default()
            )
        }
        Client::Codex => format!(
            "# ~/.codex/config.toml\n[mcp_servers.{SERVER_NAME}]\nurl = {}\n\n[mcp_servers.{SERVER_NAME}.http_headers]\nAuthorization = {}\n",
            toml_string(&url),
            toml_string(&format!("Bearer {tok}"))
        ),
        Client::Http => format!(
            "URL     {url}\nHeader  Authorization: Bearer {tok}\n\nTransport is Streamable HTTP (POST JSON-RPC). The launcher must be open."
        ),
        Client::Stdio => {
            if !is_usable_launcher_path(launcher_path) {
                return UNPACKAGED_NOTICE.to_string();
            }
            let cmd = relay_command(launcher_path, native)
                .iter()
                .map(|p| shell_quote(p))
                .collect::<Vec<_>>()
                .join(" ");
            format!(
                "For a client that can only spawn a subprocess, run this as the server command:\n{cmd}\n\nIt relays stdio to the launcher and reads the port and token from\n{endpoint_file} itself, so no token goes in the client's config."
            )
        }
    }
}

#[cfg(test)]
mod tests {
    //! Port of `McpClientSetupTest`.
    use super::*;

    const TOK: &str = "abc123";

    #[test]
    fn endpoint_url_shape() {
        assert_eq!(endpoint_url(47833), "http://127.0.0.1:47833/mcp");
    }

    #[test]
    fn claude_code_snippet() {
        assert_eq!(
            snippet(Client::ClaudeCode, "", true, 5, "", Some(TOK)),
            "claude mcp add --transport http mica-launcher http://127.0.0.1:5/mcp --header 'Authorization: Bearer abc123'"
        );
    }

    #[test]
    fn a_blank_token_is_a_placeholder() {
        assert!(snippet(Client::Http, "", true, 5, "", None).contains("Bearer <token>"));
        assert!(snippet(Client::Http, "", true, 5, "", Some("  ")).contains("Bearer <token>"));
    }

    #[test]
    fn cursor_snippet_is_valid_json_after_the_comment() {
        let s = snippet(Client::Cursor, "", true, 5, "", Some(TOK));
        let (comment, body) = s.split_once('\n').unwrap();
        assert_eq!(comment, "// ~/.cursor/mcp.json");
        let v: serde_json::Value = serde_json::from_str(body).unwrap();
        assert_eq!(
            v["mcpServers"]["mica-launcher"]["url"],
            "http://127.0.0.1:5/mcp"
        );
        assert_eq!(
            v["mcpServers"]["mica-launcher"]["headers"]["Authorization"],
            "Bearer abc123"
        );
    }

    #[test]
    fn codex_snippet() {
        assert_eq!(
            snippet(Client::Codex, "", true, 5, "", Some(TOK)),
            "# ~/.codex/config.toml\n[mcp_servers.mica-launcher]\nurl = \"http://127.0.0.1:5/mcp\"\n\n[mcp_servers.mica-launcher.http_headers]\nAuthorization = \"Bearer abc123\"\n"
        );
    }

    #[test]
    fn http_snippet() {
        assert_eq!(
            snippet(Client::Http, "", true, 5, "", Some(TOK)),
            "URL     http://127.0.0.1:5/mcp\nHeader  Authorization: Bearer abc123\n\nTransport is Streamable HTTP (POST JSON-RPC). The launcher must be open."
        );
    }

    #[test]
    fn stdio_snippet_embeds_no_token() {
        let s = snippet(
            Client::Stdio,
            "/opt/mica/bin/launcher",
            true,
            5,
            "/home/u/.x/config/mcp-endpoint.json",
            Some(TOK),
        );
        assert!(s.contains("/opt/mica/bin/launcher --mcp"));
        assert!(s.contains("/home/u/.x/config/mcp-endpoint.json itself"));
        assert!(!s.contains(TOK));
    }

    #[test]
    fn stdio_quotes_paths_with_spaces() {
        let s = snippet(
            Client::Stdio,
            "C:\\Program Files\\Mica\\Mica.exe",
            true,
            5,
            "x",
            None,
        );
        assert!(s.contains("'C:\\Program Files\\Mica\\Mica.exe' --mcp"));
        let s = snippet(Client::Stdio, "/x/launcher.jar", false, 5, "x", None);
        assert!(s.contains("java -jar /x/launcher.jar --mcp"));
    }

    #[test]
    fn stdio_unusable_path_shows_the_notice() {
        for p in [
            "",
            "  ",
            "/usr/bin/java",
            "C:\\jdk\\bin\\java.exe",
            "C:\\jdk\\bin\\JAVAW.EXE",
        ] {
            assert_eq!(
                snippet(Client::Stdio, p, true, 5, "x", None),
                UNPACKAGED_NOTICE,
                "{p}"
            );
        }
    }

    #[test]
    fn usable_paths() {
        assert!(is_usable_launcher_path("/x/launcher.JAR"));
        assert!(is_usable_launcher_path("/opt/x/mica-launcher"));
        assert!(!is_usable_launcher_path("java"));
    }

    #[test]
    fn relay_commands() {
        assert_eq!(relay_command("/x", true), ["/x", "--mcp"]);
        assert_eq!(
            relay_command("/x.jar", false),
            ["java", "-jar", "/x.jar", "--mcp"]
        );
    }

    #[test]
    fn shell_quote_vectors() {
        assert_eq!(shell_quote(""), "''");
        assert_eq!(shell_quote("abc/def.x:1=2+3-4_5"), "abc/def.x:1=2+3-4_5");
        assert_eq!(shell_quote("a b"), "'a b'");
        assert_eq!(shell_quote("it's"), "'it'\\''s'");
    }

    #[test]
    fn toml_string_vectors() {
        assert_eq!(toml_string("a\\b\"c"), "\"a\\\\b\\\"c\"");
    }

    #[test]
    fn client_labels_exist() {
        for c in Client::ALL {
            assert!(mmcl_i18n::english(c.label_key()).is_some());
        }
    }
}
