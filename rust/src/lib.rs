//! The XTXP muppet inside the Android app, reached from Kotlin through UniFFI.
//!
//! The phone keeps its own session of each document (decision 17), on disk, so
//! the app works with no network. Sync merges it with the remote sessions it
//! trusts, both ways; pausing stops that entirely until it's resumed or the
//! person merges by hand. The app (Compose, WorkManager) owns the views and the
//! schedule; this crate is the executor (decision 27).

pub mod address;
pub mod session;
pub mod sync;
pub mod tree;

use address::Address;
use session::Session;
use std::collections::HashMap;
use std::path::PathBuf;
use std::sync::{Arc, Mutex, OnceLock, Weak};
use std::time::{SystemTime, UNIX_EPOCH};
use xmesh::{NodeId, Xmesh};

pub use tree::{Attr, Drop, Row};

/// Opens a connection to a remote; tests pass an in-process host.
pub type Connect<'a> = dyn FnMut(&Address) -> Result<Box<dyn sync::Link>, XtxpError> + 'a;

uniffi::setup_scaffolding!();

#[derive(Debug, thiserror::Error, uniffi::Error)]
pub enum XtxpError {
    #[error("{msg}")]
    Invalid { msg: String },
    #[error("{msg}")]
    Store { msg: String },
    #[error("{msg}")]
    Network { msg: String },
}

/// The drawing a brand-new host starts with, pushed only when both the phone
/// and the host are empty.
pub const SAMPLE_SVG: &str = r##"<svg xmlns="http://www.w3.org/2000/svg" xmlns:inkscape="http://www.inkscape.org/namespaces/inkscape" viewBox="0 0 120 80"><g id="sky" inkscape:label="Sky"><circle id="sun" cx="95" cy="18" r="10" fill="#f5b700"/><ellipse id="cloud" cx="35" cy="20" rx="18" ry="7" fill="#ffffff"/></g><g id="ground" inkscape:label="Ground"><rect id="field" x="0" y="55" width="120" height="25" fill="#4caf50"/><rect id="house" x="20" y="35" width="30" height="20" fill="#c0392b"/><path id="roof" d="M18 35 L35 22 L52 35 Z" fill="#7f3f20"/></g><text id="title" x="60" y="76" text-anchor="middle">xtxp</text></svg>"##;

/// A local change waiting to be merged.
#[derive(Debug, Clone, PartialEq, uniffi::Record)]
pub struct PendingChange {
    pub summary: String,
    pub at_ms: i64,
}

/// A remote session this one trusts, both ways for now.
#[derive(Debug, Clone, PartialEq, uniffi::Record)]
pub struct Remote {
    pub address: String,
    pub host: String,
    pub path: String,
    pub session: String,
    pub last_sync_ms: Option<i64>,
    pub last_error: Option<String>,
}

#[derive(Debug, Clone, PartialEq, uniffi::Record)]
pub struct SyncReport {
    /// Paused, and the sync wasn't forced.
    pub skipped: bool,
    /// Something new arrived from a remote.
    pub received: bool,
    pub merged_remotes: u32,
    pub errors: Vec<String>,
}

#[derive(uniffi::Object)]
pub struct Muppet {
    session: Mutex<Session>,
}

fn registry() -> &'static Mutex<HashMap<PathBuf, Weak<Muppet>>> {
    static R: OnceLock<Mutex<HashMap<PathBuf, Weak<Muppet>>>> = OnceLock::new();
    R.get_or_init(Default::default)
}

fn fresh_client_id() -> u64 {
    use rand::Rng;
    rand::thread_rng().gen_range(1..=u32::MAX as u64)
}

fn now_ms() -> i64 {
    SystemTime::now()
        .duration_since(UNIX_EPOCH)
        .map(|d| d.as_millis() as i64)
        .unwrap_or(0)
}

fn node(client: u64, clock: u32) -> NodeId {
    NodeId { client, clock }
}

#[uniffi::export]
impl Muppet {
    /// Opens the session stored in `dir`. The app and its sync worker share
    /// one instance per directory, so they never write the files at once.
    #[uniffi::constructor]
    pub fn open(dir: String) -> Result<Arc<Muppet>, XtxpError> {
        let path = PathBuf::from(&dir);
        let mut reg = registry().lock().unwrap();
        if let Some(m) = reg.get(&path).and_then(Weak::upgrade) {
            return Ok(m);
        }
        let m = Arc::new(Muppet {
            session: Mutex::new(Session::open(&path, fresh_client_id())?),
        });
        reg.insert(path, Arc::downgrade(&m));
        Ok(m)
    }

    /// Changes whenever the rows, pending list, pause or remotes change.
    pub fn revision(&self) -> u64 {
        self.session.lock().unwrap().revision
    }

    pub fn rows(&self) -> Vec<Row> {
        self.session.lock().unwrap().rows()
    }

    pub fn is_empty(&self) -> bool {
        self.session.lock().unwrap().is_empty()
    }

    /// Starts an empty, never-synced document offline with the sample drawing.
    pub fn start_with_sample(&self) -> Result<(), XtxpError> {
        let seed =
            Xmesh::seed(SAMPLE_SVG).map_err(|e| XtxpError::Invalid { msg: e.to_string() })?;
        self.session.lock().unwrap().start_with(&seed)
    }

    /// The drawing to render: the document with `data-xtxp-node="client:clock"`
    /// on every element.
    pub fn svg(&self) -> String {
        self.session.lock().unwrap().marked_svg()
    }

    /// Drags an element by `(dx, dy)` in its parent's user units, as a
    /// `translate` at the front of its `transform`.
    pub fn translate(&self, client: u64, clock: u32, dx: f64, dy: f64) -> Result<(), XtxpError> {
        self.session
            .lock()
            .unwrap()
            .translate(node(client, clock), dx, dy, now_ms())
    }

    /// Saves the element editor: sets the `set` attributes (by name as in
    /// [`Attr::name`], or typed as `fill` or `inkscape:label`), removes the
    /// `remove` ones, and replaces the text if `text` is given.
    pub fn edit_element(
        &self,
        client: u64,
        clock: u32,
        set: Vec<Attr>,
        remove: Vec<String>,
        text: Option<String>,
    ) -> Result<(), XtxpError> {
        let set: Vec<(String, String)> = set.into_iter().map(|a| (a.name, a.value)).collect();
        self.session.lock().unwrap().edit(
            node(client, clock),
            &set,
            &remove,
            text.as_deref(),
            now_ms(),
        )
    }

    pub fn canonical(&self) -> String {
        self.session.lock().unwrap().doc().canonical()
    }

    pub fn move_node(
        &self,
        client: u64,
        clock: u32,
        target_client: u64,
        target_clock: u32,
        drop: Drop,
    ) -> Result<(), XtxpError> {
        self.session.lock().unwrap().move_node(
            node(client, clock),
            node(target_client, target_clock),
            drop,
            now_ms(),
        )
    }

    pub fn pending(&self) -> Vec<PendingChange> {
        self.session
            .lock()
            .unwrap()
            .meta
            .pending
            .iter()
            .map(|c| PendingChange {
                summary: c.summary.clone(),
                at_ms: c.at_ms,
            })
            .collect()
    }

    pub fn is_paused(&self) -> bool {
        self.session.lock().unwrap().meta.paused
    }

    pub fn set_paused(&self, paused: bool) -> Result<(), XtxpError> {
        self.session.lock().unwrap().set_paused(paused)
    }

    pub fn remotes(&self) -> Vec<Remote> {
        let s = self.session.lock().unwrap();
        s.meta
            .remotes
            .iter()
            .filter_map(|r| {
                let a = Address::parse(&r.address).ok()?;
                Some(Remote {
                    address: r.address.clone(),
                    host: a.host,
                    path: a.path,
                    session: a.session,
                    last_sync_ms: r.last_sync_ms,
                    last_error: r.last_error.clone(),
                })
            })
            .collect()
    }

    pub fn add_remote(&self, address: String) -> Result<(), XtxpError> {
        self.session.lock().unwrap().add_remote(&address)
    }

    pub fn remove_remote(&self, address: String) -> Result<(), XtxpError> {
        self.session.lock().unwrap().remove_remote(&address)
    }

    /// Merges with every remote, both ways. Blocking: call it off the main
    /// thread. While paused it does nothing unless `force` (the + button).
    pub fn sync(&self, force: bool) -> Result<SyncReport, XtxpError> {
        self.sync_with(force, &mut |a: &Address| {
            sync::WsLink::connect(a).map(|l| Box::new(l) as Box<dyn sync::Link>)
        })
    }
}

impl Muppet {
    /// [`Muppet::sync`] with the connection made by `connect`, for tests.
    pub fn sync_with(&self, force: bool, connect: &mut Connect) -> Result<SyncReport, XtxpError> {
        // The lock isn't held over the network, so the person can keep
        // dragging while a sync runs; those edits stay pending.
        let (mut state, mut sent_pending, remotes) = {
            let s = self.session.lock().unwrap();
            if s.meta.paused && !force {
                return Ok(SyncReport {
                    skipped: true,
                    received: false,
                    merged_remotes: 0,
                    errors: vec![],
                });
            }
            let remotes: Vec<String> = s.meta.remotes.iter().map(|r| r.address.clone()).collect();
            (s.full_state(), s.meta.pending.len(), remotes)
        };
        let seed =
            Xmesh::seed(SAMPLE_SVG).map_err(|e| XtxpError::Invalid { msg: e.to_string() })?;
        let mut report = SyncReport {
            skipped: false,
            received: false,
            merged_remotes: 0,
            errors: vec![],
        };
        for address in remotes {
            let result = Address::parse(&address).and_then(|a| {
                let mut link = connect(&a)?;
                sync::sync_over(
                    link.as_mut(),
                    &state,
                    fresh_client_id(),
                    Some(&seed),
                    sync::DEADLINE,
                )
            });
            let mut s = self.session.lock().unwrap();
            match result {
                Ok(done) => {
                    let changed = s.merge_remote(&done.state, sent_pending, &address, now_ms())?;
                    sent_pending = 0;
                    report.received |= changed || done.received;
                    report.merged_remotes += 1;
                    state = s.full_state();
                }
                Err(e) => {
                    s.record_error(&address, &e.to_string())?;
                    report.errors.push(format!("{address}: {e}"));
                }
            }
        }
        Ok(report)
    }
}
