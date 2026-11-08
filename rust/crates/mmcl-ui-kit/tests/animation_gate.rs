// Copyright (c) Mica Technologies. Licensed under the GNU GPL v3.

//! Port of `AnimationGateFxTest`: an animated widget asks for frames only while it is painted.
//! (Its own test binary, because it reads a process-wide frame counter.)

use gpui::{
    AppContext as _, Context, IntoElement, ParentElement, Render, Styled, TestAppContext, Window,
    div, px,
};
use mmcl_ui_kit::{LoadingIndicator, WavyProgressBar, motion};

struct Holder {
    show: bool,
    bar: gpui::Entity<WavyProgressBar>,
}

impl Render for Holder {
    fn render(&mut self, _: &mut Window, _: &mut Context<Self>) -> impl IntoElement {
        let mut root = div().size(px(300.));
        if self.show {
            root = root
                .child(LoadingIndicator::new("spinner"))
                .child(self.bar.clone());
        }
        root
    }
}

#[gpui::test]
fn animation_runs_only_while_seen(cx: &mut TestAppContext) {
    let (view, cx) = cx.add_window_view(|_, cx| Holder {
        show: false,
        bar: cx.new(|_| WavyProgressBar::indeterminate()),
    });
    let draw = |cx: &mut gpui::VisualTestContext| {
        cx.update(|window, cx| window.draw(cx).clear(cx));
    };

    // Present in the model but not rendered (a swapped-out screen, a collapsed section).
    draw(cx);
    let before = motion::frames_requested();
    draw(cx);
    cx.run_until_parked();
    assert_eq!(
        motion::frames_requested(),
        before,
        "unrendered widgets scheduled frames"
    );

    // Rendered: each paint asks for the next frame.
    cx.update(|_, cx| {
        view.update(cx, |v, cx| {
            v.show = true;
            cx.notify();
        })
    });
    draw(cx);
    let shown = motion::frames_requested();
    assert!(
        shown >= before + 2,
        "painted indicator and bar must keep animating"
    );

    // Hidden again: no further requests.
    cx.update(|_, cx| {
        view.update(cx, |v, cx| {
            v.show = false;
            cx.notify();
        })
    });
    draw(cx);
    let after = motion::frames_requested();
    draw(cx);
    cx.run_until_parked();
    assert_eq!(motion::frames_requested(), after);
}
