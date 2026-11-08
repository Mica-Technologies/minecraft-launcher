// Copyright (c) Mica Technologies. Licensed under the GNU GPL v3.

//! The modal dialogs of the terminal UI: message, confirm, text input, action menu, launch
//! progress and the task spinner. A dialog captures every key while it is open; global keys do
//! not reach the main view (Lanterna showed dialogs as separate windows with the same effect).

use mmcl_i18n::Localizer;
use ratatui::Frame;
use ratatui::crossterm::event::{KeyCode, KeyEvent, KeyModifiers};
use ratatui::layout::{Position, Rect};
use ratatui::text::{Line, Span};
use ratatui::widgets::{Block, Clear, Paragraph};
use unicode_width::UnicodeWidthStr;

use crate::app::{ConfirmAction, InputPurpose, MenuAction};
use crate::progress::ProgressLines;
use crate::theme::TuiTheme;

/// The spinner frames of a running task.
const SPINNER: [char; 4] = ['|', '/', '-', '\\'];

/// A single-line text input.
#[derive(Clone, Debug, PartialEq)]
pub struct InputDialog {
    pub title: String,
    pub prompt: String,
    pub value: String,
    /// Cursor position in characters.
    pub cursor: usize,
    pub purpose: InputPurpose,
}

impl InputDialog {
    /// An input prefilled with `value`, the cursor at the end.
    pub fn new(
        title: impl Into<String>,
        prompt: impl Into<String>,
        value: impl Into<String>,
        purpose: InputPurpose,
    ) -> Self {
        let value = value.into();
        InputDialog {
            title: title.into(),
            prompt: prompt.into(),
            cursor: value.chars().count(),
            value,
            purpose,
        }
    }

    fn byte_at(&self, char_idx: usize) -> usize {
        self.value
            .char_indices()
            .nth(char_idx)
            .map_or(self.value.len(), |(i, _)| i)
    }

    /// Applies an editing key; `false` when the key is not an editing key.
    pub fn edit(&mut self, key: &KeyEvent) -> bool {
        let len = self.value.chars().count();
        match key.code {
            KeyCode::Char(c)
                if !key
                    .modifiers
                    .intersects(KeyModifiers::CONTROL | KeyModifiers::ALT) =>
            {
                let at = self.byte_at(self.cursor);
                self.value.insert(at, c);
                self.cursor += 1;
            }
            KeyCode::Char('u') if key.modifiers.contains(KeyModifiers::CONTROL) => {
                self.value.clear();
                self.cursor = 0;
            }
            KeyCode::Backspace if self.cursor > 0 => {
                let at = self.byte_at(self.cursor - 1);
                self.value.remove(at);
                self.cursor -= 1;
            }
            KeyCode::Delete if self.cursor < len => {
                let at = self.byte_at(self.cursor);
                self.value.remove(at);
            }
            KeyCode::Left => self.cursor = self.cursor.saturating_sub(1),
            KeyCode::Right => self.cursor = (self.cursor + 1).min(len),
            KeyCode::Home => self.cursor = 0,
            KeyCode::End => self.cursor = len,
            KeyCode::Backspace | KeyCode::Delete => {}
            _ => return false,
        }
        true
    }
}

/// An open dialog.
#[derive(Clone, Debug, PartialEq)]
pub enum Dialog {
    /// Text and an OK button.
    Message { title: String, body: String },
    /// A Yes/No question.
    Confirm {
        title: String,
        body: String,
        yes_selected: bool,
        action: ConfirmAction,
    },
    /// A text input with OK/Cancel.
    Input(InputDialog),
    /// A list of actions.
    Menu {
        title: String,
        description: String,
        items: Vec<(String, MenuAction)>,
        selected: usize,
    },
    /// The launch progress window.
    Progress {
        title: String,
        lines: ProgressLines,
        cancelling: bool,
    },
    /// A running task.
    Spinner { message: String, frame: usize },
}

/// What a key did to the top dialog.
#[derive(Clone, Debug, PartialEq)]
pub enum DialogOutcome {
    /// Still open.
    Keep,
    /// Closed with no result.
    Close,
    /// Confirmed with Yes.
    Confirmed(ConfirmAction),
    /// Input accepted with OK.
    Input(InputPurpose, String),
    /// Input cancelled.
    InputCancelled(InputPurpose),
    /// A menu item was picked.
    Picked(MenuAction),
    /// Esc on the launch progress window.
    CancelLaunch,
}

impl Dialog {
    /// Handles one key.
    pub fn handle_key(&mut self, key: &KeyEvent) -> DialogOutcome {
        match self {
            Dialog::Message { .. } => match key.code {
                KeyCode::Enter | KeyCode::Esc | KeyCode::Char(' ') => DialogOutcome::Close,
                _ => DialogOutcome::Keep,
            },
            Dialog::Confirm {
                yes_selected,
                action,
                ..
            } => match key.code {
                KeyCode::Left | KeyCode::Right | KeyCode::Tab | KeyCode::BackTab => {
                    *yes_selected = !*yes_selected;
                    DialogOutcome::Keep
                }
                KeyCode::Char('y') | KeyCode::Char('Y') => DialogOutcome::Confirmed(action.clone()),
                KeyCode::Char('n') | KeyCode::Char('N') | KeyCode::Esc => DialogOutcome::Close,
                KeyCode::Enter | KeyCode::Char(' ') => {
                    if *yes_selected {
                        DialogOutcome::Confirmed(action.clone())
                    } else {
                        DialogOutcome::Close
                    }
                }
                _ => DialogOutcome::Keep,
            },
            Dialog::Input(input) => match key.code {
                KeyCode::Enter => DialogOutcome::Input(input.purpose.clone(), input.value.clone()),
                KeyCode::Esc => DialogOutcome::InputCancelled(input.purpose.clone()),
                _ => {
                    input.edit(key);
                    DialogOutcome::Keep
                }
            },
            Dialog::Menu {
                items, selected, ..
            } => match key.code {
                KeyCode::Up => {
                    *selected = selected.saturating_sub(1);
                    DialogOutcome::Keep
                }
                KeyCode::Down | KeyCode::Tab => {
                    *selected = (*selected + 1).min(items.len().saturating_sub(1));
                    DialogOutcome::Keep
                }
                KeyCode::Home => {
                    *selected = 0;
                    DialogOutcome::Keep
                }
                KeyCode::End => {
                    *selected = items.len().saturating_sub(1);
                    DialogOutcome::Keep
                }
                KeyCode::Enter | KeyCode::Char(' ') => match items.get(*selected) {
                    Some((_, a)) => DialogOutcome::Picked(a.clone()),
                    None => DialogOutcome::Close,
                },
                KeyCode::Esc => DialogOutcome::Close,
                _ => DialogOutcome::Keep,
            },
            Dialog::Progress { cancelling, .. } => match key.code {
                KeyCode::Esc if !*cancelling => {
                    *cancelling = true;
                    DialogOutcome::CancelLaunch
                }
                _ => DialogOutcome::Keep,
            },
            Dialog::Spinner { .. } => DialogOutcome::Keep,
        }
    }

    /// Advances the spinner animation.
    pub fn tick(&mut self) {
        if let Dialog::Spinner { frame, .. } = self {
            *frame = (*frame + 1) % SPINNER.len();
        }
    }

    /// Draws the dialog centred in `area`.
    pub fn render(&self, f: &mut Frame, area: Rect, theme: TuiTheme, l: &Localizer) {
        let base = theme.base();
        let sel = theme.selected();
        let ok = l.get("dialog.button.ok").into_owned();
        let cancel = l.get("dialog.button.cancel").into_owned();
        let (title, lines, cursor): (String, Vec<Line>, Option<(u16, u16)>) = match self {
            Dialog::Message { title, body } => {
                let mut v: Vec<Line> = body.lines().map(|s| Line::from(s.to_string())).collect();
                v.push(Line::from(""));
                v.push(Line::from(Span::styled(format!("< {ok} >"), sel)));
                (title.clone(), v, None)
            }
            Dialog::Confirm {
                title,
                body,
                yes_selected,
                ..
            } => {
                let yes = l.get("tui.dialog.yes").into_owned();
                let no = l.get("tui.dialog.no").into_owned();
                let mut v: Vec<Line> = body.lines().map(|s| Line::from(s.to_string())).collect();
                v.push(Line::from(""));
                let (ys, ns) = if *yes_selected {
                    (sel, base)
                } else {
                    (base, sel)
                };
                v.push(Line::from(vec![
                    Span::styled(format!("< {yes} >"), ys),
                    Span::raw("   "),
                    Span::styled(format!("< {no} >"), ns),
                ]));
                (title.clone(), v, None)
            }
            Dialog::Input(input) => {
                let mut v: Vec<Line> = input
                    .prompt
                    .lines()
                    .map(|s| Line::from(s.to_string()))
                    .collect();
                let field = format!("{} ", input.value);
                v.push(Line::from(Span::styled(field, theme.editable())));
                v.push(Line::from(""));
                v.push(Line::from(format!("[Enter] {ok}   [Esc] {cancel}")));
                let before: String = input.value.chars().take(input.cursor).collect();
                let row = (v.len() - 3) as u16;
                (input.title.clone(), v, Some((before.width() as u16, row)))
            }
            Dialog::Menu {
                title,
                description,
                items,
                selected,
            } => {
                let mut v = vec![Line::from(description.clone()), Line::from("")];
                for (i, (label, _)) in items.iter().enumerate() {
                    let style = if i == *selected { sel } else { base };
                    v.push(Line::from(Span::styled(format!(" {label} "), style)));
                }
                (title.clone(), v, None)
            }
            Dialog::Progress {
                title,
                lines,
                cancelling,
            } => {
                let hint = if *cancelling {
                    l.get("tui.launch.cancelling").into_owned()
                } else {
                    l.get("tui.launch.cancelHint").into_owned()
                };
                let v = vec![
                    Line::from(lines.section.clone()),
                    Line::from(lines.detail.clone()),
                    Line::from(lines.percent.clone()),
                    Line::from(""),
                    Line::from(hint),
                ];
                (format!(" {title} "), v, None)
            }
            Dialog::Spinner { message, frame } => {
                let v = vec![Line::from(format!("  {}  {message}  ", SPINNER[*frame]))];
                (String::new(), v, None)
            }
        };

        let content_w = lines.iter().map(|ln| ln.width()).max().unwrap_or(0);
        let title_w = title.width() + 2;
        let min_w: usize = if matches!(self, Dialog::Input(_) | Dialog::Progress { .. }) {
            60
        } else {
            24
        };
        let max_w = usize::from(area.width.saturating_sub(4)).max(10);
        let w = (content_w.max(title_w).max(min_w) + 4).min(max_w) as u16;
        let h = ((lines.len() + 2) as u16).min(area.height.max(3));
        let rect = centered(area, w, h);
        f.render_widget(Clear, rect);
        let mut block = Block::bordered().style(base);
        if !title.is_empty() {
            block = block.title(Line::from(title));
        }
        let inner = block.inner(rect);
        let para = Paragraph::new(lines).style(base).block(block);
        let para = if matches!(self, Dialog::Input(_)) {
            para
        } else {
            para.wrap(ratatui::widgets::Wrap { trim: false })
        };
        f.render_widget(para, rect);
        if let Some((x, y)) = cursor {
            let x = inner
                .x
                .saturating_add(x)
                .min(inner.right().saturating_sub(1));
            let y = inner.y.saturating_add(y);
            f.set_cursor_position(Position::new(x, y));
        }
    }
}

/// A `w` x `h` rectangle centred in `area` (clipped to it).
pub fn centered(area: Rect, w: u16, h: u16) -> Rect {
    let w = w.min(area.width);
    let h = h.min(area.height);
    Rect {
        x: area.x + (area.width - w) / 2,
        y: area.y + (area.height - h) / 2,
        width: w,
        height: h,
    }
}

#[cfg(test)]
mod tests {
    use super::*;
    use ratatui::crossterm::event::KeyEventKind;

    fn key(code: KeyCode) -> KeyEvent {
        KeyEvent::new_with_kind(code, KeyModifiers::NONE, KeyEventKind::Press)
    }

    #[test]
    fn input_edits_with_a_cursor() {
        let mut i = InputDialog::new("t", "p", "ab", InputPurpose::InstallUrl);
        assert_eq!(i.cursor, 2);
        i.edit(&key(KeyCode::Left));
        i.edit(&key(KeyCode::Char('\u{00E9}')));
        assert_eq!(i.value, "a\u{00E9}b");
        i.edit(&key(KeyCode::Backspace));
        assert_eq!(i.value, "ab");
        i.edit(&key(KeyCode::Home));
        i.edit(&key(KeyCode::Delete));
        assert_eq!(i.value, "b");
        i.edit(&key(KeyCode::End));
        i.edit(&key(KeyCode::Backspace));
        i.edit(&key(KeyCode::Backspace));
        assert_eq!(i.value, "");
        assert_eq!(i.cursor, 0);
    }

    #[test]
    fn confirm_defaults_and_toggles() {
        let mut d = Dialog::Confirm {
            title: "t".into(),
            body: "b".into(),
            yes_selected: true,
            action: ConfirmAction::Install(Box::new(crate::format::tests::pack("P", "u"))),
        };
        d.handle_key(&key(KeyCode::Right));
        assert_eq!(d.handle_key(&key(KeyCode::Enter)), DialogOutcome::Close);
        d.handle_key(&key(KeyCode::Left));
        assert_eq!(
            d.handle_key(&key(KeyCode::Enter)),
            DialogOutcome::Confirmed(ConfirmAction::Install(Box::new(
                crate::format::tests::pack("P", "u")
            )))
        );
        assert_eq!(d.handle_key(&key(KeyCode::Esc)), DialogOutcome::Close);
    }

    #[test]
    fn menu_moves_within_bounds() {
        let mut d = Dialog::Menu {
            title: "t".into(),
            description: "d".into(),
            items: vec![
                ("A".into(), MenuAction::Close),
                ("B".into(), MenuAction::Close),
            ],
            selected: 0,
        };
        d.handle_key(&key(KeyCode::Up));
        d.handle_key(&key(KeyCode::Down));
        d.handle_key(&key(KeyCode::Down));
        if let Dialog::Menu { selected, .. } = &d {
            assert_eq!(*selected, 1);
        }
        assert_eq!(
            d.handle_key(&key(KeyCode::Enter)),
            DialogOutcome::Picked(MenuAction::Close)
        );
    }

    #[test]
    fn progress_cancels_once() {
        let mut d = Dialog::Progress {
            title: "t".into(),
            lines: ProgressLines::default(),
            cancelling: false,
        };
        assert_eq!(
            d.handle_key(&key(KeyCode::Esc)),
            DialogOutcome::CancelLaunch
        );
        assert_eq!(d.handle_key(&key(KeyCode::Esc)), DialogOutcome::Keep);
    }

    #[test]
    fn centered_clips_to_the_area() {
        let a = Rect::new(0, 0, 10, 5);
        assert_eq!(centered(a, 20, 20), a);
        assert_eq!(centered(a, 4, 1), Rect::new(3, 2, 4, 1));
    }
}
