// Copyright (c) Mica Technologies. Licensed under the GNU GPL v3.

//! Images: [`FadeImage`] (`ImageFadeIn`) and the avatar cache ([`Avatars`], `AvatarImages`).

use std::collections::HashMap;
use std::future::Future;
use std::pin::Pin;
use std::sync::Arc;

use gpui::{
    Animation, AnimationExt as _, App, ElementId, Global, Image, ImageFormat, ImageSource,
    ImgResourceLoader, IntoElement, ParentElement, RenderOnce, Styled, Window, div, img,
};

use crate::assets::NO_USER;
use crate::focus::child_id;
use crate::motion;
use crate::scale::u;

/// How long a freshly loaded image takes to fade in.
pub const FADE_IN: std::time::Duration = motion::SHORT;

#[derive(Default)]
struct FadeState {
    seen_loading: bool,
}

/// An image that fades from transparent to opaque when its bytes arrive (immediately when they
/// were already loaded). Size it like any `img`.
#[derive(IntoElement)]
pub struct FadeImage {
    id: ElementId,
    source: ImageSource,
    rounded_full: bool,
    size: Option<f32>,
}

impl FadeImage {
    /// A fading image.
    pub fn new(id: impl Into<ElementId>, source: impl Into<ImageSource>) -> Self {
        FadeImage {
            id: id.into(),
            source: source.into(),
            rounded_full: false,
            size: None,
        }
    }

    /// A square `size` px.
    pub fn size(mut self, size: f32) -> Self {
        self.size = Some(size);
        self
    }

    /// Clips to a circle.
    pub fn circular(mut self) -> Self {
        self.rounded_full = true;
        self
    }
}

impl RenderOnce for FadeImage {
    fn render(self, window: &mut Window, cx: &mut App) -> impl IntoElement {
        let loaded = match &self.source {
            ImageSource::Resource(resource) => window
                .use_asset::<ImgResourceLoader>(resource, cx)
                .is_some(),
            _ => true,
        };
        let state =
            window.use_keyed_state(child_id(&self.id, "fade"), cx, |_, _| FadeState::default());
        let fade = state.update(cx, |s, _| {
            if !loaded {
                s.seen_loading = true;
            }
            loaded && s.seen_loading
        });
        let mut image = img(self.source).size_full();
        if self.rounded_full {
            image = image.rounded_full();
        }
        let mut frame = div().overflow_hidden();
        if let Some(size) = self.size {
            frame = frame.flex_none().size(u(size));
        } else {
            frame = frame.size_full();
        }
        if self.rounded_full {
            frame = frame.rounded_full();
        }
        if !loaded {
            return frame
                .child(div().size_full().opacity(0.0))
                .into_any_element();
        }
        if fade && !motion::is_reduced() {
            let id = child_id(&self.id, "fade-in");
            frame
                .child(div().size_full().child(image).with_animation(
                    id,
                    Animation::new(FADE_IN),
                    |el, t| el.opacity(t),
                ))
                .into_any_element()
        } else {
            frame.child(image).into_any_element()
        }
    }
}

/// The avatar URL for a player uuid (100 px armoured bust).
pub fn avatar_url(uuid: &str) -> String {
    format!("https://minotar.net/armor/bust/{uuid}/100.png")
}

/// A future resolving to downloaded bytes or an error message.
pub type FetchFuture = Pin<Box<dyn Future<Output = Result<Vec<u8>, String>> + Send>>;
/// Downloads a URL. The app installs one backed by the shared HTTP client (generic User-Agent,
/// no personal identifiers); the future must not need a particular async runtime to be polled
/// (`mmcl-engine`'s spawn returns such a future).
pub type Fetcher = Arc<dyn Fn(String) -> FetchFuture + Send + Sync>;

/// One avatar's state.
#[derive(Clone)]
pub enum AvatarState {
    /// Being downloaded.
    Loading,
    /// Ready to draw.
    Ready(Arc<Image>),
    /// The last load failed; the next [`Avatars::get`] retries.
    Failed,
}

/// The process-wide avatar cache keyed by uuid. Failed loads are never cached: the next request
/// starts a new one.
#[derive(Default)]
pub struct Avatars {
    cache: HashMap<String, AvatarState>,
    fetcher: Option<Fetcher>,
}

impl Global for Avatars {}

impl Avatars {
    /// Installs the downloader.
    pub fn set_fetcher(cx: &mut App, fetcher: Fetcher) {
        cx.default_global::<Avatars>().fetcher = Some(fetcher);
    }

    /// The avatar URL (see [`avatar_url`]).
    pub fn url(uuid: &str) -> String {
        avatar_url(uuid)
    }

    /// The avatar for `uuid`, starting a load when it is absent or failed. Blank -> `None`.
    pub fn get(cx: &mut App, uuid: &str) -> Option<AvatarState> {
        let uuid = uuid.trim();
        if uuid.is_empty() {
            return None;
        }
        let avatars = cx.default_global::<Avatars>();
        match avatars.cache.get(uuid) {
            Some(AvatarState::Failed) | None => {}
            Some(state) => return Some(state.clone()),
        }
        let Some(fetcher) = avatars.fetcher.clone() else {
            avatars.cache.insert(uuid.to_string(), AvatarState::Failed);
            return Some(AvatarState::Failed);
        };
        avatars.cache.insert(uuid.to_string(), AvatarState::Loading);
        let key = uuid.to_string();
        let future = fetcher(avatar_url(uuid));
        cx.spawn(async move |cx| {
            let result = future.await;
            cx.update(|cx| {
                let state = match result {
                    Ok(bytes) if !bytes.is_empty() => {
                        AvatarState::Ready(Arc::new(Image::from_bytes(ImageFormat::Png, bytes)))
                    }
                    Ok(_) => AvatarState::Failed,
                    Err(e) => {
                        tracing::debug!("{}", mmcl_i18n::tf!("log.uiKit.avatarFailed", &key, e));
                        AvatarState::Failed
                    }
                };
                cx.default_global::<Avatars>().cache.insert(key, state);
                cx.refresh_windows();
            });
        })
        .detach();
        Some(AvatarState::Loading)
    }

    /// Drops every cached avatar (for example after signing out).
    pub fn clear(cx: &mut App) {
        cx.default_global::<Avatars>().cache.clear();
    }
}

/// A circular avatar (16 px in tabs, 28 px in the navbar, 36 px in pane headers); the
/// signed-out placeholder while loading or after a failure.
#[derive(IntoElement)]
pub struct Avatar {
    id: ElementId,
    uuid: Option<String>,
    size: f32,
}

impl Avatar {
    /// An avatar for `uuid` (`None` = signed out).
    pub fn new(id: impl Into<ElementId>, uuid: Option<String>, size: f32) -> Self {
        Avatar {
            id: id.into(),
            uuid,
            size,
        }
    }
}

impl RenderOnce for Avatar {
    fn render(self, _window: &mut Window, cx: &mut App) -> impl IntoElement {
        let state = self.uuid.as_deref().and_then(|u| Avatars::get(cx, u));
        let source: ImageSource = match state {
            Some(AvatarState::Ready(image)) => ImageSource::Image(image),
            _ => NO_USER.into(),
        };
        FadeImage::new(self.id, source).size(self.size).circular()
    }
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn avatar_url_format() {
        assert_eq!(
            avatar_url("069a79f4-44e9-4726-a5be-fca90e38aaf5"),
            "https://minotar.net/armor/bust/069a79f4-44e9-4726-a5be-fca90e38aaf5/100.png"
        );
    }
}
