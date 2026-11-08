// Copyright (c) Mica Technologies. Licensed under the GNU GPL v3.

//! [`PackRun`]: one pack's view of one prepare run (environment, pack, verify context, cancel
//! token), shared by the vanilla installer, the loaders and the pipeline.

use std::path::{Path, PathBuf};
use std::sync::Arc;

use mmcl_manifest::ManifestFile;
use mmcl_modpack::{GameModPack, ManagedFile, PrepareContext};
use mmcl_net::CancelToken;
use mmcl_progress::ProgressSink;

use crate::env::LaunchEnv;
use crate::error::{LaunchError, Result};

/// One pack in one run.
#[derive(Clone)]
pub struct PackRun {
    pub env: LaunchEnv,
    pub pack: Arc<GameModPack>,
    pub ctx: Arc<PrepareContext>,
    pub cancel: CancelToken,
}

impl std::fmt::Debug for PackRun {
    fn fmt(&self, f: &mut std::fmt::Formatter<'_>) -> std::fmt::Result {
        f.debug_struct("PackRun")
            .field("pack", &self.pack.name())
            .field("verify_mode", &self.ctx.verify_mode)
            .finish_non_exhaustive()
    }
}

impl PackRun {
    /// A run with the pack's current prepare context.
    pub fn new(env: LaunchEnv, pack: Arc<GameModPack>, cancel: CancelToken) -> PackRun {
        let ctx = pack.prepare_context();
        PackRun {
            env,
            pack,
            ctx,
            cancel,
        }
    }

    /// The pack folder (refusing a name with no folder of its own).
    pub fn root(&self) -> Result<PathBuf> {
        Ok(self.pack.checked_pack_root()?)
    }

    /// `<pack>/libraries`.
    pub fn libraries_dir(&self) -> Result<PathBuf> {
        Ok(self.root()?.join("libraries"))
    }

    /// `<pack>/bin/natives`.
    pub fn natives_dir(&self) -> Result<PathBuf> {
        Ok(self.root()?.join("bin").join("natives"))
    }

    /// The launcher data folder.
    pub fn data_root(&self) -> &Path {
        self.env.paths.root()
    }

    /// A managed file under `prefix` sharing the pack's session-verified set and the sink's
    /// byte tracker.
    pub fn managed(
        &self,
        spec: ManifestFile,
        prefix: &Path,
        sink: Option<&dyn ProgressSink>,
    ) -> ManagedFile {
        ManagedFile::new(spec, prefix, self.data_root())
            .with_session(self.pack.session_verified().clone())
            .with_tracker(sink.and_then(mmcl_modpack::meter::tracker_of))
    }

    /// A managed file at a launcher-built full path.
    pub fn managed_at(&self, spec: ManifestFile, path: &Path) -> ManagedFile {
        ManagedFile::at_path(spec, path, self.data_root())
            .with_session(self.pack.session_verified().clone())
    }

    /// Fails with `launch.error.cancelled` once the run is cancelled.
    pub fn check_cancelled(&self) -> Result<()> {
        if self.cancel.is_cancelled() {
            Err(LaunchError::Cancelled)
        } else {
            Ok(())
        }
    }
}
