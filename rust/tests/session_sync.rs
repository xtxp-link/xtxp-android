//! Two phones and a host, end to end, with xtxp-wire's in-process y-sweet.

use std::cell::RefCell;
use std::collections::VecDeque;
use std::rc::Rc;
use std::sync::Arc;
use std::time::Duration;
use xtxp_mobile::address::Address;
use xtxp_mobile::sync::Link;
use xtxp_mobile::{Drop, Muppet, Row, XtxpError};
use xtxp_wire::fake_host::{ConnId, FakeHost};

struct FakeLink {
    host: Rc<RefCell<FakeHost>>,
    conn: ConnId,
    inbox: VecDeque<Vec<u8>>,
}

impl FakeLink {
    fn open(host: &Rc<RefCell<FakeHost>>) -> FakeLink {
        let (conn, frames) = host.borrow_mut().connect();
        FakeLink {
            host: host.clone(),
            conn,
            inbox: frames.into(),
        }
    }
}

impl Link for FakeLink {
    fn send(&mut self, frame: Vec<u8>) -> Result<(), String> {
        for (to, f) in self.host.borrow_mut().receive(self.conn, &frame) {
            if to == self.conn {
                self.inbox.push_back(f);
            }
        }
        Ok(())
    }
    fn recv(&mut self, _wait: Duration) -> Result<Option<Vec<u8>>, String> {
        Ok(self.inbox.pop_front())
    }
    fn close(&mut self) {
        self.host.borrow_mut().disconnect(self.conn);
    }
}

fn sync(m: &Muppet, host: &Rc<RefCell<FakeHost>>, force: bool) -> xtxp_mobile::SyncReport {
    m.sync_with(force, &mut |_a: &Address| {
        Ok(Box::new(FakeLink::open(host)) as Box<dyn Link>)
    })
    .unwrap()
}

fn phone() -> (tempfile::TempDir, Arc<Muppet>) {
    let dir = tempfile::tempdir().unwrap();
    let m = Muppet::open(dir.path().to_string_lossy().into()).unwrap();
    (dir, m)
}

fn row<'a>(rows: &'a [Row], label: &str) -> &'a Row {
    rows.iter()
        .find(|r| r.label == label)
        .unwrap_or_else(|| panic!("no row {label}: {rows:?}"))
}

fn drag(m: &Muppet, from: &str, to: &str, drop: Drop) -> Result<(), XtxpError> {
    let rows = m.rows();
    let (a, b) = (row(&rows, from), row(&rows, to));
    m.move_node(a.client, a.clock, b.client, b.clock, drop)
}

fn labels(m: &Muppet) -> Vec<String> {
    m.rows()
        .iter()
        .map(|r| format!("{}{}", "  ".repeat(r.depth as usize), r.label))
        .collect()
}

#[test]
fn a_fresh_host_gets_the_sample_and_a_second_phone_fetches_it() {
    let host = Rc::new(RefCell::new(FakeHost::new()));
    let (_d1, a) = phone();
    assert!(a.is_empty());
    let report = sync(&a, &host, false);
    assert!(report.errors.is_empty(), "{report:?}");
    assert!(!a.is_empty());
    assert_eq!(a.canonical(), host.borrow().doc().canonical());

    let (_d2, b) = phone();
    let report = sync(&b, &host, false);
    assert!(report.received);
    assert_eq!(b.canonical(), a.canonical());
}

#[test]
fn drags_stay_pending_while_paused_and_merge_on_plus() {
    let host = Rc::new(RefCell::new(FakeHost::new()));
    let (_d1, a) = phone();
    let (_d2, b) = phone();
    sync(&a, &host, false);
    sync(&b, &host, false);

    a.set_paused(true).unwrap();
    drag(&a, "#sun", "Ground", Drop::Into).unwrap();
    drag(&a, "#title", "Sky", Drop::Before).unwrap();
    assert_eq!(a.pending().len(), 2);
    assert_eq!(
        a.pending()[0].summary,
        "Moved <circle #sun> into <g Ground>"
    );

    // Paused is truly offline: an automatic sync does nothing either way.
    let report = sync(&a, &host, false);
    assert!(report.skipped);
    assert_eq!(a.pending().len(), 2);

    // Meanwhile phone B moves something else.
    drag(&b, "#cloud", "#house", Drop::After).unwrap();
    sync(&b, &host, false);

    // + merges both ways, and the session stays paused.
    let report = sync(&a, &host, true);
    assert!(report.errors.is_empty(), "{report:?}");
    assert!(report.received);
    assert!(a.pending().is_empty());
    assert!(a.is_paused());
    sync(&b, &host, false);
    assert_eq!(a.canonical(), b.canonical());
    assert_eq!(
        labels(&a),
        [
            "viewBox=0 0 120 80",
            "  #title",
            "  Sky",
            "  Ground",
            "    #field",
            "    #house",
            "    #cloud",
            "    #roof",
            "    #sun",
        ]
        .iter()
        .map(|s| s.to_string())
        .collect::<Vec<_>>(),
        "{:#?}",
        labels(&a)
    );
}

#[test]
fn the_session_survives_a_restart() {
    let host = Rc::new(RefCell::new(FakeHost::new()));
    let dir = tempfile::tempdir().unwrap();
    let path: String = dir.path().to_string_lossy().into();
    let canonical = {
        let a = Muppet::open(path.clone()).unwrap();
        sync(&a, &host, false);
        a.set_paused(true).unwrap();
        drag(&a, "#roof", "Sky", Drop::Into).unwrap();
        a.add_remote("http://localhost:8787/demo@main".into())
            .unwrap();
        a.canonical()
    };
    // The registry only holds weak references, so this is a real reopen.
    let a = Muppet::open(path).unwrap();
    assert_eq!(a.canonical(), canonical);
    assert!(a.is_paused());
    assert_eq!(a.pending().len(), 1);
    let remotes: Vec<String> = a.remotes().into_iter().map(|r| r.address).collect();
    assert_eq!(
        remotes,
        [
            "xtxp-worker.campey.workers.dev/demo@main",
            "http://localhost:8787/demo@main"
        ]
    );
}

#[test]
fn bad_drops_are_refused_and_change_nothing() {
    let host = Rc::new(RefCell::new(FakeHost::new()));
    let (_d, a) = phone();
    sync(&a, &host, false);
    let before = a.canonical();
    assert!(
        drag(&a, "Sky", "#sun", Drop::Into).is_err(),
        "into its own child"
    );
    assert!(
        drag(&a, "#sun", "#sun", Drop::After).is_err(),
        "onto itself"
    );
    let root = a.rows()[0].clone();
    let sun = row(&a.rows(), "#sun").clone();
    assert!(a
        .move_node(root.client, root.clock, sun.client, sun.clock, Drop::Into)
        .is_err());
    assert!(a
        .move_node(sun.client, sun.clock, root.client, root.clock, Drop::Before)
        .is_err());
    assert_eq!(a.canonical(), before);
    assert!(a.pending().is_empty());
}

#[test]
fn an_unreachable_remote_is_recorded_and_the_edits_stay_pending() {
    let host = Rc::new(RefCell::new(FakeHost::new()));
    let (_d, a) = phone();
    sync(&a, &host, false);
    drag(&a, "#sun", "Ground", Drop::Into).unwrap();
    let report = a
        .sync_with(false, &mut |a: &Address| {
            Err(XtxpError::Network {
                msg: format!("couldn't reach {}", a.host),
            })
        })
        .unwrap();
    assert_eq!(report.merged_remotes, 0);
    assert_eq!(report.errors.len(), 1);
    assert_eq!(a.pending().len(), 1);
    assert_eq!(
        a.remotes()[0].last_error.as_deref(),
        Some("couldn't reach xtxp-worker.campey.workers.dev")
    );
}

/// The real WebSocket link against a y-sweet-shaped server on localhost,
/// so the blocking reads, timeouts and URL layout are exercised too.
#[test]
#[allow(clippy::result_large_err)]
fn syncs_over_a_real_websocket() {
    use std::net::TcpListener;
    use tungstenite::Message;

    let listener = TcpListener::bind("127.0.0.1:0").unwrap();
    let port = listener.local_addr().unwrap().port();
    let server = std::thread::spawn(move || {
        let mut host = FakeHost::new();
        let mut paths = vec![];
        // Two phones, one after the other.
        for _ in 0..2 {
            let (stream, _) = listener.accept().unwrap();
            let mut path = String::new();
            let mut ws = tungstenite::accept_hdr(
                stream,
                |req: &tungstenite::handshake::server::Request, res| {
                    path = req.uri().path().to_string();
                    Ok(res)
                },
            )
            .unwrap();
            paths.push(path);
            let (conn, frames) = host.connect();
            for f in frames {
                ws.send(Message::Binary(f.into())).unwrap();
            }
            loop {
                match ws.read() {
                    Ok(Message::Binary(b)) => {
                        for (to, f) in host.receive(conn, &b) {
                            if to == conn {
                                ws.send(Message::Binary(f.into())).unwrap();
                            }
                        }
                    }
                    Ok(_) => {}
                    Err(_) => break,
                }
            }
            host.disconnect(conn);
        }
        (paths, host.doc().canonical())
    });

    let address = format!("http://127.0.0.1:{port}/demo@main");
    let (_d1, a) = phone();
    a.remove_remote("xtxp-worker.campey.workers.dev/demo@main".into())
        .unwrap();
    a.add_remote(address.clone()).unwrap();
    let report = a.sync(false).unwrap();
    assert!(report.errors.is_empty(), "{report:?}");

    let (_d2, b) = phone();
    b.remove_remote("xtxp-worker.campey.workers.dev/demo@main".into())
        .unwrap();
    b.add_remote(address).unwrap();
    let report = b.sync(false).unwrap();
    assert!(report.errors.is_empty(), "{report:?}");
    assert!(report.received);

    let (paths, host_canonical) = server.join().unwrap();
    assert_eq!(paths, ["/d/demo/ws/demo", "/d/demo/ws/demo"]);
    assert_eq!(a.canonical(), host_canonical);
    assert_eq!(b.canonical(), host_canonical);
}

#[test]
fn offline_first_run_merges_with_a_host_seeded_the_same_way() {
    let host = Rc::new(RefCell::new(FakeHost::new()));
    let (_d1, a) = phone();
    sync(&a, &host, false); // seeds the host with the sample
    let (_d2, b) = phone();
    b.start_with_sample().unwrap();
    assert!(b.start_with_sample().is_err(), "only once");
    drag(&b, "#sun", "Ground", Drop::Into).unwrap();
    sync(&b, &host, false);
    sync(&a, &host, false);
    assert_eq!(a.canonical(), b.canonical());
    assert_eq!(a.rows().len(), 9, "one root, not two");
    assert!(
        a.start_with_sample().is_err(),
        "a synced document can't restart"
    );
}
