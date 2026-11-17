//! Session addresses, `{host}/{path}@{ref}` (decisions 9 and 16).

use crate::XtxpError;
use std::fmt;

#[derive(Debug, Clone, PartialEq, Eq)]
pub struct Address {
    /// False only for an explicit `http://`, for a local `wrangler dev`.
    pub tls: bool,
    /// Host, with a port if one was given.
    pub host: String,
    /// The document; y-sweet's rule: ASCII letters, digits, `-` and `_`.
    pub path: String,
    /// The session's name. The last `@` starts it.
    pub session: String,
}

impl Address {
    pub fn parse(text: &str) -> Result<Address, XtxpError> {
        let bad = |why: &str| XtxpError::Invalid {
            msg: format!("{text:?} isn't a session address ({{host}}/{{path}}@{{session}}): {why}"),
        };
        let text = text.trim();
        let (tls, rest) = if let Some(r) = text.strip_prefix("http://") {
            (false, r)
        } else {
            (true, text.strip_prefix("https://").unwrap_or(text))
        };
        let (place, session) = rest.rsplit_once('@').ok_or_else(|| bad("no @session"))?;
        let (host, path) = place.split_once('/').ok_or_else(|| bad("no /path"))?;
        if host.is_empty() || host.contains(char::is_whitespace) {
            return Err(bad("no host"));
        }
        let name_ok = |s: &str| {
            !s.is_empty()
                && s.chars()
                    .all(|c| c.is_ascii_alphanumeric() || c == '-' || c == '_')
        };
        if !name_ok(path) {
            return Err(bad("the path must be letters, digits, - and _"));
        }
        if !name_ok(session) {
            return Err(bad("the session must be letters, digits, - and _"));
        }
        Ok(Address {
            tls,
            host: host.to_string(),
            path: path.to_string(),
            session: session.to_string(),
        })
    }

    /// The host's WebSocket for this document, as y-sweet and xtxp-worker serve it.
    pub fn ws_url(&self) -> String {
        let scheme = if self.tls { "wss" } else { "ws" };
        format!("{scheme}://{}/d/{}/ws/{}", self.host, self.path, self.path)
    }
}

impl fmt::Display for Address {
    fn fmt(&self, f: &mut fmt::Formatter<'_>) -> fmt::Result {
        if !self.tls {
            f.write_str("http://")?;
        }
        write!(f, "{}/{}@{}", self.host, self.path, self.session)
    }
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn parses_the_default_remote() {
        let a = Address::parse("xtxp-worker.campey.workers.dev/demo@main").unwrap();
        assert!(a.tls);
        assert_eq!(a.host, "xtxp-worker.campey.workers.dev");
        assert_eq!(a.path, "demo");
        assert_eq!(a.session, "main");
        assert_eq!(
            a.ws_url(),
            "wss://xtxp-worker.campey.workers.dev/d/demo/ws/demo"
        );
    }

    #[test]
    fn https_is_implied_and_http_is_kept() {
        let a = Address::parse("https://h.example/doc@main").unwrap();
        assert_eq!(a.to_string(), "h.example/doc@main");
        let b = Address::parse("http://localhost:8787/doc@main").unwrap();
        assert_eq!(b.ws_url(), "ws://localhost:8787/d/doc/ws/doc");
        assert_eq!(b.to_string(), "http://localhost:8787/doc@main");
    }

    #[test]
    fn rejects_incomplete_addresses() {
        for bad in [
            "",
            "host",
            "host/doc",
            "host@main",
            "/doc@main",
            "h/a b@main",
            "h/doc@",
        ] {
            assert!(Address::parse(bad).is_err(), "{bad}");
        }
    }
}
