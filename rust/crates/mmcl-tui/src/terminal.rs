// Copyright (c) Mica Technologies. Licensed under the GNU GPL v3.

//! The real terminal: setup and restore (raw mode, alternate screen, a panic hook that restores
//! the terminal first), and the event loop.
//!
//! One UI thread owns the terminal and the [`App`]. It waits for input with a short timeout,
//! drains the worker channel, runs the 1 Hz tick and redraws after anything changed. A panic in
//! a key or message handler is logged and the UI keeps going (Java logged and continued too);
//! a terminal I/O error ends the loop.

use std::cell::Cell;
use std::io::{self, IsTerminal, Stdout};
use std::panic::{AssertUnwindSafe, catch_unwind};
use std::sync::Arc;
use std::sync::mpsc::{self, Receiver};
use std::time::{Duration, Instant};

use mmcl_i18n::Localizer;
use ratatui::Terminal;
use ratatui::backend::CrosstermBackend;
use ratatui::crossterm::event::{self, Event};
use ratatui::crossterm::execute;
use ratatui::crossterm::terminal::{
    EnterAlternateScreen, LeaveAlternateScreen, disable_raw_mode, enable_raw_mode,
};

use crate::TuiError;
use crate::app::{App, Msg};
use crate::host::TuiHost;

/// How long one input wait lasts before the channel is drained again.
const POLL: Duration = Duration::from_millis(50);
/// The refresh period (status bar, log pane, spinner).
const TICK: Duration = Duration::from_secs(1);

thread_local! {
    /// Set while a handler runs under `catch_unwind`: its panic is logged, not fatal, so the
    /// panic hook must leave the terminal alone.
    static GUARDED: Cell<bool> = const { Cell::new(false) };
}

/// Leaves raw mode and the alternate screen (best-effort; safe to call twice).
pub fn restore() {
    let _ = disable_raw_mode();
    let _ = execute!(
        io::stdout(),
        LeaveAlternateScreen,
        ratatui::crossterm::cursor::Show
    );
}

/// Restores the terminal when dropped (every exit path of [`run_app`]).
struct Guard;

impl Drop for Guard {
    fn drop(&mut self) {
        restore();
    }
}

/// Whether stdin and stdout are both interactive terminals.
pub fn is_interactive() -> bool {
    io::stdin().is_terminal() && io::stdout().is_terminal()
}

fn setup() -> Result<Terminal<CrosstermBackend<Stdout>>, TuiError> {
    enable_raw_mode().map_err(TuiError::Setup)?;
    if let Err(e) = execute!(io::stdout(), EnterAlternateScreen) {
        let _ = disable_raw_mode();
        return Err(TuiError::Setup(e));
    }
    match Terminal::new(CrosstermBackend::new(io::stdout())) {
        Ok(t) => Ok(t),
        Err(e) => {
            restore();
            Err(TuiError::Setup(e))
        }
    }
}

fn install_panic_hook() {
    let previous = std::panic::take_hook();
    std::panic::set_hook(Box::new(move |info| {
        if GUARDED.with(Cell::get) {
            tracing::error!(
                "{}",
                mmcl_i18n::tf!("log.tui.handlerPanicked", info.to_string())
            );
            return;
        }
        restore();
        previous(info);
    }));
}

/// Runs `f` and logs a panic instead of unwinding out of the loop.
fn guarded(f: impl FnOnce()) {
    GUARDED.with(|g| g.set(true));
    // A panic was already logged by the hook; the UI carries on.
    let _ = catch_unwind(AssertUnwindSafe(f));
    GUARDED.with(|g| g.set(false));
}

/// Sets up the terminal, runs the UI until the user quits, restores the terminal.
pub fn run_app(host: Arc<dyn TuiHost>, l: &'static Localizer) -> Result<(), TuiError> {
    if !is_interactive() {
        return Err(TuiError::NoTerminal);
    }
    let (tx, rx) = mpsc::channel::<Msg>();
    let events = tx.clone();
    host.attach(Arc::new(move |e| {
        let _ = events.send(Msg::Host(e));
    }));
    let mut terminal = setup()?;
    let _guard = Guard;
    install_panic_hook();
    let mut app = App::new(host, l, tx);
    let result = event_loop(&mut terminal, &mut app, &rx);
    if let Err(e) = &result {
        tracing::warn!(
            "{}",
            mmcl_i18n::tf!("log.tui.terminalIoError", e.to_string())
        );
    }
    let _ = terminal.show_cursor();
    result.map_err(TuiError::Io)
}

fn event_loop(
    terminal: &mut Terminal<CrosstermBackend<Stdout>>,
    app: &mut App,
    rx: &Receiver<Msg>,
) -> io::Result<()> {
    let mut dirty = true;
    let mut last_tick = Instant::now();
    let mut revision = mmcl_i18n::revision();
    loop {
        if mmcl_i18n::revision() != revision {
            revision = mmcl_i18n::revision();
            dirty = true;
        }
        if dirty {
            terminal.draw(|f| app.render(f))?;
            dirty = false;
        }
        if app.should_quit() {
            return Ok(());
        }
        if event::poll(POLL)? {
            match event::read()? {
                Event::Key(k) => {
                    guarded(|| app.handle_key(k));
                    dirty = true;
                }
                Event::Resize(_, _) => dirty = true,
                _ => {}
            }
        }
        while let Ok(m) = rx.try_recv() {
            guarded(|| app.handle_msg(m));
            dirty = true;
        }
        if app.poll_logs() {
            dirty = true;
        }
        if last_tick.elapsed() >= TICK {
            last_tick = Instant::now();
            guarded(|| app.tick());
            dirty = true;
        }
    }
}
