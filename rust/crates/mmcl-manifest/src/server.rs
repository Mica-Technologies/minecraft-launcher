// Copyright (c) Mica Technologies. Licensed under the GNU GPL v3.

//! Server addresses: a pack's default server and user favourites.

use serde::{Deserialize, Serialize};

/// A server to join: display name, host and port.
#[derive(Clone, Debug, PartialEq, Eq, Hash, Serialize, Deserialize)]
pub struct ServerFavorite {
    pub name: String,
    pub host: String,
    pub port: u16,
}

impl ServerFavorite {
    /// Minecraft's default port.
    pub const DEFAULT_PORT: u16 = 25565;

    /// Builds a favourite as-is.
    pub fn new(name: impl Into<String>, host: impl Into<String>, port: u16) -> Self {
        ServerFavorite {
            name: name.into(),
            host: host.into(),
            port,
        }
    }

    /// The host, or `host:port` when the port is not the default.
    pub fn display_address(&self) -> String {
        if self.port == Self::DEFAULT_PORT {
            self.host.clone()
        } else {
            format!("{}:{}", self.host, self.port)
        }
    }

    /// Parses user input `host` or `host:port` (split at the last `:`). `None` when blank,
    /// when the port is not an integer in 1..=65535, or when the host is blank. A blank name
    /// falls back to the host. IPv6 literals without brackets misparse, as in Java.
    pub fn parse(name: Option<&str>, host_port: Option<&str>) -> Option<Self> {
        let trimmed = crate::non_blank(host_port)?;
        let (host, port) = match trimmed.rfind(':') {
            None => (trimmed, Self::DEFAULT_PORT),
            Some(colon) => {
                let port: i64 = trimmed[colon + 1..].parse().ok()?;
                if !(1..=65535).contains(&port) {
                    return None;
                }
                (&trimmed[..colon], port as u16)
            }
        };
        if host.trim().is_empty() {
            return None;
        }
        let name = crate::non_blank(name).unwrap_or(host);
        Some(ServerFavorite::new(name, host, port))
    }
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn host_only_assigns_default_port() {
        let f = ServerFavorite::parse(Some("My Server"), Some("play.example.com")).unwrap();
        assert_eq!(f.name, "My Server");
        assert_eq!(f.host, "play.example.com");
        assert_eq!(f.port, ServerFavorite::DEFAULT_PORT);
    }

    #[test]
    fn host_and_custom_port() {
        let f =
            ServerFavorite::parse(Some("Custom"), Some("alto.tacomano.example.com:8585")).unwrap();
        assert_eq!(f.host, "alto.tacomano.example.com");
        assert_eq!(f.port, 8585);
    }

    #[test]
    fn blank_or_missing_name_falls_back_to_host() {
        assert_eq!(
            ServerFavorite::parse(Some("  "), Some("play.example.com"))
                .unwrap()
                .name,
            "play.example.com"
        );
        let f = ServerFavorite::parse(None, Some("play.example.com:25566")).unwrap();
        assert_eq!(f.name, "play.example.com");
        assert_eq!(f.port, 25566);
    }

    #[test]
    fn rejects_bad_input() {
        assert_eq!(ServerFavorite::parse(Some("x"), Some("")), None);
        assert_eq!(ServerFavorite::parse(Some("x"), Some("   ")), None);
        assert_eq!(ServerFavorite::parse(Some("x"), None), None);
        assert_eq!(ServerFavorite::parse(Some("x"), Some(":25565")), None);
        assert_eq!(ServerFavorite::parse(Some("x"), Some("host:abc")), None);
        assert_eq!(ServerFavorite::parse(Some("x"), Some("host:0")), None);
        assert_eq!(ServerFavorite::parse(Some("x"), Some("host:65536")), None);
        assert_eq!(ServerFavorite::parse(Some("x"), Some("host:-1")), None);
    }

    #[test]
    fn port_limits_are_accepted() {
        assert_eq!(
            ServerFavorite::parse(Some("x"), Some("host:1"))
                .unwrap()
                .port,
            1
        );
        assert_eq!(
            ServerFavorite::parse(Some("x"), Some("host:65535"))
                .unwrap()
                .port,
            65535
        );
    }

    #[test]
    fn display_address() {
        assert_eq!(
            ServerFavorite::new("x", "play.example.com", ServerFavorite::DEFAULT_PORT)
                .display_address(),
            "play.example.com"
        );
        assert_eq!(
            ServerFavorite::new("x", "play.example.com", 8585).display_address(),
            "play.example.com:8585"
        );
    }

    #[test]
    fn trims_surrounding_spaces() {
        let f = ServerFavorite::parse(Some("  Name  "), Some("  play.example.com  ")).unwrap();
        assert_eq!(f.name, "Name");
        assert_eq!(f.host, "play.example.com");
    }
}
