// Copyright (c) Mica Technologies. Licensed under the GNU GPL v3.

//! MCP resources (Java `resources/`): read-only documents under `mica://`.
//!
//! A resource read is authorized as a call of its governing tool, so a tool the user disabled
//! cannot be read around through its resource, and its text passes the same strict redaction
//! as tool output.

use std::sync::Arc;

use indexmap::IndexMap;
use serde_json::{Map, Value};

use crate::{BoxError, McpError};

pub mod launcher;
pub mod uri;

/// The governing tool name when a resource names none.
pub const DEFAULT_GOVERNING_TOOL: &str = "read_resource";

/// One resource or resource template.
pub trait Resource: Send + Sync {
    /// A concrete URI or a template with `{placeholders}`.
    fn uri_template(&self) -> &str;
    fn name(&self) -> &str;
    fn description(&self) -> &str;
    fn mime_type(&self) -> &str;
    /// Reads the text for the matched placeholder values.
    fn read(&self, params: &IndexMap<String, String>) -> Result<String, BoxError>;

    /// The tool whose approval governs reading this resource.
    fn governing_tool_name(&self) -> &str {
        DEFAULT_GOVERNING_TOOL
    }

    fn is_template(&self) -> bool {
        uri::is_template(self.uri_template())
    }

    /// The URIs `resources/list` advertises: none for a template, the URI itself otherwise.
    fn concrete_uris(&self) -> Vec<String> {
        if self.is_template() {
            Vec::new()
        } else {
            vec![self.uri_template().to_string()]
        }
    }

    /// `{uriTemplate,name,description,mimeType}`.
    fn describe_template(&self) -> Value {
        let mut m = Map::new();
        m.insert(
            "uriTemplate".into(),
            Value::String(self.uri_template().into()),
        );
        m.insert("name".into(), Value::String(self.name().into()));
        m.insert(
            "description".into(),
            Value::String(self.description().into()),
        );
        m.insert("mimeType".into(), Value::String(self.mime_type().into()));
        Value::Object(m)
    }

    /// `{uri,name,description,mimeType}`.
    fn describe_concrete(&self, uri: &str) -> Value {
        let mut m = Map::new();
        m.insert("uri".into(), Value::String(uri.into()));
        m.insert("name".into(), Value::String(self.name().into()));
        m.insert(
            "description".into(),
            Value::String(self.description().into()),
        );
        m.insert("mimeType".into(), Value::String(self.mime_type().into()));
        Value::Object(m)
    }
}

/// A resolved read.
pub struct ResourceMatch {
    pub resource: Arc<dyn Resource>,
    pub params: IndexMap<String, String>,
}

/// The registered resources, in registration order.
#[derive(Default)]
pub struct ResourceRegistry {
    resources: IndexMap<String, Arc<dyn Resource>>,
}

impl ResourceRegistry {
    pub fn new() -> ResourceRegistry {
        ResourceRegistry::default()
    }

    /// Adds a resource; refuses a non-`mica://` template or a duplicate.
    pub fn register(&mut self, r: Arc<dyn Resource>) -> Result<(), McpError> {
        let t = r.uri_template().to_string();
        if !t.starts_with(uri::SCHEME) {
            return Err(McpError::InvalidArgument(format!(
                "Resource URI must start with {}: {t}",
                uri::SCHEME
            )));
        }
        if self.resources.contains_key(&t) {
            return Err(McpError::IllegalState(format!("Duplicate resource: {t}")));
        }
        self.resources.insert(t, r);
        Ok(())
    }

    /// The first registration whose template matches; `None` for anything else, traversal
    /// attempts included.
    pub fn resolve(&self, uri_str: &str) -> Option<ResourceMatch> {
        self.resources.values().find_map(|r| {
            uri::match_template(r.uri_template(), uri_str).map(|params| ResourceMatch {
                resource: r.clone(),
                params,
            })
        })
    }

    pub fn all(&self) -> Vec<Arc<dyn Resource>> {
        self.resources.values().cloned().collect()
    }

    pub fn len(&self) -> usize {
        self.resources.len()
    }

    pub fn is_empty(&self) -> bool {
        self.resources.is_empty()
    }

    /// `{"resources":[…]}`: every concrete URI of every resource.
    pub fn list_result(&self) -> Value {
        let mut out = Vec::new();
        for r in self.resources.values() {
            for u in r.concrete_uris() {
                out.push(r.describe_concrete(&u));
            }
        }
        let mut m = Map::new();
        m.insert("resources".into(), Value::Array(out));
        Value::Object(m)
    }

    /// `{"resourceTemplates":[…]}`: the template resources only.
    pub fn templates_list_result(&self) -> Value {
        let out: Vec<Value> = self
            .resources
            .values()
            .filter(|r| r.is_template())
            .map(|r| r.describe_template())
            .collect();
        let mut m = Map::new();
        m.insert("resourceTemplates".into(), Value::Array(out));
        Value::Object(m)
    }
}

#[cfg(test)]
mod tests {
    //! Port of `McpResourceRegistryTest`.
    use super::*;
    use serde_json::json;

    struct R(&'static str);

    impl Resource for R {
        fn uri_template(&self) -> &str {
            self.0
        }
        fn name(&self) -> &str {
            "n"
        }
        fn description(&self) -> &str {
            "d"
        }
        fn mime_type(&self) -> &str {
            "text/plain"
        }
        fn read(&self, p: &IndexMap<String, String>) -> Result<String, BoxError> {
            Ok(format!("{}:{:?}", self.0, p))
        }
    }

    #[test]
    fn refuses_other_schemes_and_duplicates() {
        let mut r = ResourceRegistry::new();
        assert!(r.register(Arc::new(R("file:///x"))).is_err());
        assert!(r.register(Arc::new(R("http://x"))).is_err());
        r.register(Arc::new(R("mica://a"))).unwrap();
        assert!(matches!(
            r.register(Arc::new(R("mica://a"))),
            Err(McpError::IllegalState(_))
        ));
    }

    #[test]
    fn resolves_first_match() {
        let mut r = ResourceRegistry::new();
        r.register(Arc::new(R("mica://p/{x}"))).unwrap();
        r.register(Arc::new(R("mica://p/{y}"))).unwrap();
        let m = r.resolve("mica://p/abc").unwrap();
        assert_eq!(m.resource.uri_template(), "mica://p/{x}");
        assert_eq!(m.params["x"], "abc");
        assert!(r.resolve("mica://p/..").is_none());
        assert!(r.resolve("mica://q/abc").is_none());
    }

    #[test]
    fn listings_shape() {
        let r = ResourceRegistry::new();
        assert_eq!(r.list_result(), json!({"resources":[]}));
        assert_eq!(r.templates_list_result(), json!({"resourceTemplates":[]}));
        let mut r = ResourceRegistry::new();
        r.register(Arc::new(R("mica://a"))).unwrap();
        r.register(Arc::new(R("mica://p/{x}"))).unwrap();
        assert_eq!(
            r.list_result(),
            json!({"resources":[{"uri":"mica://a","name":"n","description":"d","mimeType":"text/plain"}]})
        );
        assert_eq!(
            r.templates_list_result(),
            json!({"resourceTemplates":[{"uriTemplate":"mica://p/{x}","name":"n","description":"d","mimeType":"text/plain"}]})
        );
    }

    #[test]
    fn default_governing_tool() {
        assert_eq!(R("mica://a").governing_tool_name(), DEFAULT_GOVERNING_TOOL);
        assert!(R("mica://p/{x}").concrete_uris().is_empty());
    }
}
