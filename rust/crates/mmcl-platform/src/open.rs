// Copyright (c) Mica Technologies. Licensed under the GNU GPL v3.

//! Opening folders and links with the OS (`Desktop.open` / `Desktop.browse`).

use std::path::Path;

use crate::error::{PlatformError, Result};

/// Opens `folder` in the file manager, creating it first if it is missing.
pub fn open_folder(folder: &Path) -> Result<()> {
    std::fs::create_dir_all(folder)?;
    open::that_detached(folder).map_err(|e| {
        tracing::warn!(
            "{}",
            mmcl_i18n::tf!(
                "log.platform.openFailed",
                folder.display().to_string(),
                e.to_string()
            )
        );
        PlatformError::Io(e)
    })
}

/// Opens an http(s) link in the default browser. Anything that does not pass
/// [`mmcl_util::url_validation`] is refused (author-supplied links must not run `file:` or
/// custom schemes).
pub fn open_url(url: &str) -> Result<()> {
    let Some(safe) = mmcl_util::url_validation::sanitized_http_url(url) else {
        tracing::warn!("{}", mmcl_i18n::tf!("log.platform.openUrlRejected", url));
        return Err(PlatformError::Other(mmcl_i18n::tf!(
            "log.platform.openUrlRejected",
            url
        )));
    };
    open::that_detached(&safe).map_err(|e| {
        tracing::warn!(
            "{}",
            mmcl_i18n::tf!("log.platform.openFailed", safe.as_str(), e.to_string())
        );
        PlatformError::Io(e)
    })
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn non_http_links_are_refused_before_reaching_the_os() {
        assert!(open_url("file:///etc/passwd").is_err());
        assert!(open_url("javascript:alert(1)").is_err());
        assert!(open_url("mmcl://open").is_err());
    }
}
