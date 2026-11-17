//! The phone's own session of one document (decision 17): its state on disk,
//! the local changes not yet merged anywhere, whether it's paused, and the
//! remote sessions it trusts. Pure apart from the files in its directory.

use crate::address::Address;
use crate::tree::{self, Drop, Row};
use crate::XtxpError;
use serde::{Deserialize, Serialize};
use std::fs;
use std::path::{Path, PathBuf};
use xmesh::{NodeId, Xmesh};
use yrs::updates::encoder::Encode;

/// The host this app syncs with out of the box: the Cloudflare Worker.
pub const DEFAULT_REMOTE: &str = "xtxp-worker.campey.workers.dev/xtxp-logo@main";

/// One local edit not yet merged into a remote.
#[derive(Debug, Clone, PartialEq, Serialize, Deserialize)]
pub struct Change {
    pub summary: String,
    pub at_ms: i64,
}

#[derive(Debug, Clone, PartialEq, Serialize, Deserialize)]
pub struct RemoteState {
    pub address: String,
    #[serde(default)]
    pub last_sync_ms: Option<i64>,
    #[serde(default)]
    pub last_error: Option<String>,
}

#[derive(Debug, Clone, PartialEq, Serialize, Deserialize)]
pub struct Meta {
    /// Paused means truly offline: no automatic merge either way.
    pub paused: bool,
    pub remotes: Vec<RemoteState>,
    #[serde(default)]
    pub pending: Vec<Change>,
}

impl Default for Meta {
    fn default() -> Meta {
        Meta {
            paused: false,
            remotes: vec![RemoteState {
                address: DEFAULT_REMOTE.into(),
                last_sync_ms: None,
                last_error: None,
            }],
            pending: vec![],
        }
    }
}

pub struct Session {
    dir: PathBuf,
    doc: Xmesh,
    pub meta: Meta,
    /// Bumped on every change the UI should redraw for.
    pub revision: u64,
}

const STATE: &str = "state.ydoc";
const META: &str = "meta.json";

impl Session {
    /// Opens (or creates) the session stored in `dir`, editing under a fresh
    /// client ID (decision 19): earlier edits keep the IDs they were made with.
    pub fn open(dir: &Path, client_id: u64) -> Result<Session, XtxpError> {
        fs::create_dir_all(dir).map_err(store)?;
        let doc = Xmesh::new(client_id).map_err(invalid)?;
        let state = dir.join(STATE);
        if state.exists() {
            doc.apply(&fs::read(&state).map_err(store)?)
                .map_err(store)?;
        }
        let meta_path = dir.join(META);
        let meta = if meta_path.exists() {
            serde_json::from_slice(&fs::read(&meta_path).map_err(store)?).map_err(store)?
        } else {
            Meta::default()
        };
        Ok(Session {
            dir: dir.to_path_buf(),
            doc,
            meta,
            revision: 0,
        })
    }

    pub fn doc(&self) -> &Xmesh {
        &self.doc
    }

    /// The whole state as one update, as stored and as handed to a sync.
    pub fn full_state(&self) -> Vec<u8> {
        self.doc
            .diff(&yrs::StateVector::default().encode_v1())
            .expect("own diff")
    }

    pub fn is_empty(&self) -> bool {
        self.doc.tree().is_empty()
    }

    pub fn rows(&self) -> Vec<Row> {
        tree::rows(&self.doc.tree_with_ids())
    }

    /// Drags `node` onto `target`: before it, after it, or into it as its last child.
    pub fn move_node(
        &mut self,
        node: NodeId,
        target: NodeId,
        drop: Drop,
        now_ms: i64,
    ) -> Result<(), XtxpError> {
        let tree = self.doc.tree_with_ids();
        let (op, summary) = tree::move_op(&tree, node, target, drop)?;
        self.doc.edit(&[op]).map_err(invalid)?;
        self.meta.pending.push(Change {
            summary,
            at_ms: now_ms,
        });
        self.save()
    }

    /// Drags `node` by `(dx, dy)` in its parent's user units.
    pub fn translate(
        &mut self,
        node: NodeId,
        dx: f64,
        dy: f64,
        now_ms: i64,
    ) -> Result<(), XtxpError> {
        let tree = self.doc.tree_with_ids();
        let (op, summary) = tree::translate_op(&tree, node, dx, dy)?;
        self.doc.edit(&[op]).map_err(invalid)?;
        self.meta.pending.push(Change {
            summary,
            at_ms: now_ms,
        });
        self.save()
    }

    /// The element editor's changes, as one transaction and one pending change.
    pub fn edit(
        &mut self,
        node: NodeId,
        set: &[(String, String)],
        remove: &[String],
        text: Option<&str>,
        now_ms: i64,
    ) -> Result<(), XtxpError> {
        let tree = self.doc.tree_with_ids();
        let (ops, summary) = tree::edit_ops(&tree, node, set, remove, text)?;
        if ops.is_empty() {
            return Ok(());
        }
        self.doc.edit(&ops).map_err(invalid)?;
        self.meta.pending.push(Change {
            summary,
            at_ms: now_ms,
        });
        self.save()
    }

    /// The drawing, with each element marked so a touch finds its node.
    pub fn marked_svg(&self) -> String {
        xmesh::canonical(&tree::marked(&self.doc.tree_with_ids()))
    }

    /// Merges a state that came back from a remote, and drops the first
    /// `merged` pending changes: those were in the state that was sent.
    pub fn merge_remote(
        &mut self,
        update: &[u8],
        merged: usize,
        address: &str,
        now_ms: i64,
    ) -> Result<bool, XtxpError> {
        let before = self.doc.snapshot_hash();
        self.doc.apply(update).map_err(store)?;
        let n = merged.min(self.meta.pending.len());
        self.meta.pending.drain(..n);
        if let Some(r) = self.remote_mut(address) {
            r.last_sync_ms = Some(now_ms);
            r.last_error = None;
        }
        self.save()?;
        Ok(before != self.doc.snapshot_hash())
    }

    /// Starts an empty, never-synced session from `seed`, for a first run
    /// with no network. Seeds are byte-identical wherever they're made, so
    /// this merges cleanly with a host that was seeded with the same drawing.
    pub fn start_with(&mut self, seed: &[u8]) -> Result<(), XtxpError> {
        if !self.is_empty() {
            return Err(invalid("the document already has content"));
        }
        if self.meta.remotes.iter().any(|r| r.last_sync_ms.is_some()) {
            return Err(invalid(
                "this document has synced before; tap + to fetch it",
            ));
        }
        self.doc.apply(seed).map_err(invalid)?;
        self.save()
    }

    pub fn record_error(&mut self, address: &str, error: &str) -> Result<(), XtxpError> {
        if let Some(r) = self.remote_mut(address) {
            r.last_error = Some(error.to_string());
        }
        self.save()
    }

    pub fn set_paused(&mut self, paused: bool) -> Result<(), XtxpError> {
        self.meta.paused = paused;
        self.save()
    }

    pub fn add_remote(&mut self, address: &str) -> Result<(), XtxpError> {
        let parsed = Address::parse(address)?;
        let canonical = parsed.to_string();
        if self.meta.remotes.iter().all(|r| r.address != canonical) {
            self.meta.remotes.push(RemoteState {
                address: canonical,
                last_sync_ms: None,
                last_error: None,
            });
        }
        self.save()
    }

    pub fn remove_remote(&mut self, address: &str) -> Result<(), XtxpError> {
        self.meta.remotes.retain(|r| r.address != address);
        self.save()
    }

    fn remote_mut(&mut self, address: &str) -> Option<&mut RemoteState> {
        self.meta.remotes.iter_mut().find(|r| r.address == address)
    }

    /// Writes state and meta, each through a temporary file so a crash never
    /// leaves half a document.
    fn save(&mut self) -> Result<(), XtxpError> {
        write_atomic(&self.dir.join(STATE), &self.full_state())?;
        let meta = serde_json::to_vec_pretty(&self.meta).map_err(store)?;
        write_atomic(&self.dir.join(META), &meta)?;
        self.revision += 1;
        Ok(())
    }
}

fn write_atomic(path: &Path, bytes: &[u8]) -> Result<(), XtxpError> {
    let tmp = path.with_extension("tmp");
    fs::write(&tmp, bytes).map_err(store)?;
    fs::rename(&tmp, path).map_err(store)
}

fn store(e: impl std::fmt::Display) -> XtxpError {
    XtxpError::Store { msg: e.to_string() }
}

fn invalid(e: impl std::fmt::Display) -> XtxpError {
    XtxpError::Invalid { msg: e.to_string() }
}
