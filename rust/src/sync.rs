//! One sync with one remote: connect, exchange state vectors both ways with
//! `xtxp-wire`'s peer, and hand back the merged state. Each connection edits
//! under a fresh client ID (decision 19), so the peer starts from a copy of
//! the session's state rather than from the session itself.

use crate::address::Address;
use crate::XtxpError;
use std::net::TcpStream;
use std::time::{Duration, Instant};
use tungstenite::stream::MaybeTlsStream;
use tungstenite::{Message, WebSocket};
use xtxp_wire::{Event, Input, Output, Peer};
use yrs::updates::encoder::Encode;

/// How long one sync may take before it's given up and retried later.
pub const DEADLINE: Duration = Duration::from_secs(20);

/// A connection carrying binary frames.
pub trait Link {
    fn send(&mut self, frame: Vec<u8>) -> Result<(), String>;
    /// The next frame, or `None` if none arrived within `wait`.
    fn recv(&mut self, wait: Duration) -> Result<Option<Vec<u8>>, String>;
    fn close(&mut self) {}
}

pub struct Synced {
    /// The whole merged document, to apply to the session.
    pub state: Vec<u8>,
    /// True if the host sent anything the session didn't have.
    pub received: bool,
}

/// Runs one sync of `state` over `link`. `seed` is pushed only if both sides
/// turn out to be empty, so a fresh host gets a starting drawing and two
/// different roots never meet.
pub fn sync_over(
    link: &mut dyn Link,
    state: &[u8],
    client_id: u64,
    seed: Option<&[u8]>,
    deadline: Duration,
) -> Result<Synced, XtxpError> {
    let net = |msg: String| XtxpError::Network { msg };
    let mut peer = Peer::new(client_id).map_err(|e| XtxpError::Invalid { msg: e.to_string() })?;
    // Disconnected, so this only applies; the state goes out in our step 2.
    peer.handle(Input::Apply(state.to_vec()));
    let sent_sv = peer.doc().state_vector();

    let start = Instant::now();
    let mut synced = false;
    let mut received = false;
    let mut seeded = false;
    let mut pending = peer.handle(Input::Connected);
    loop {
        for out in pending.drain(..) {
            match out {
                Output::Send(frame) => link.send(frame).map_err(net)?,
                Output::Event(Event::Synced) => synced = true,
                Output::Event(Event::RemoteUpdate { .. }) => received = true,
                Output::Event(Event::ProtocolError(e)) => return Err(net(e)),
                Output::Event(Event::EditFailed(e)) => {
                    return Err(XtxpError::Invalid { msg: e.to_string() })
                }
                Output::Event(Event::LocalAcked) => {}
            }
        }
        if synced && !seeded && peer.doc().tree().is_empty() {
            if let Some(seed) = seed {
                seeded = true;
                pending = peer.handle(Input::Apply(seed.to_vec()));
                continue;
            }
        }
        if synced && !peer.unacked() {
            break;
        }
        let left = deadline
            .checked_sub(start.elapsed())
            .ok_or_else(|| net("the host didn't finish syncing in time".into()))?;
        match link
            .recv(left.min(Duration::from_millis(500)))
            .map_err(net)?
        {
            Some(frame) => pending = peer.handle(Input::Frame(frame)),
            None => pending = vec![],
        }
    }
    link.close();
    let state = peer
        .doc()
        .diff(&yrs::StateVector::default().encode_v1())
        .map_err(|e| XtxpError::Store { msg: e.to_string() })?;
    Ok(Synced {
        state,
        received: received || seeded || peer.doc().state_vector() != sent_sv,
    })
}

/// A blocking WebSocket to a y-sweet-compatible host.
pub struct WsLink {
    socket: WebSocket<MaybeTlsStream<TcpStream>>,
}

impl WsLink {
    pub fn connect(address: &Address) -> Result<WsLink, XtxpError> {
        if address.session != "main" {
            // y-sweet and xtxp-worker host one session per document (decision 25).
            return Err(XtxpError::Invalid {
                msg: format!(
                    "{} hosts only @main for now, not @{}",
                    address.host, address.session
                ),
            });
        }
        let _ = rustls::crypto::ring::default_provider().install_default();
        let (socket, _) =
            tungstenite::connect(address.ws_url()).map_err(|e| XtxpError::Network {
                msg: format!("couldn't reach {}: {e}", address.host),
            })?;
        Ok(WsLink { socket })
    }

    fn set_timeout(&mut self, wait: Duration) {
        let wait = Some(wait.max(Duration::from_millis(1)));
        let _ = match self.socket.get_mut() {
            MaybeTlsStream::Plain(s) => s.set_read_timeout(wait),
            MaybeTlsStream::Rustls(s) => s.get_mut().set_read_timeout(wait),
            _ => Ok(()),
        };
    }
}

impl Link for WsLink {
    fn send(&mut self, frame: Vec<u8>) -> Result<(), String> {
        self.socket
            .send(Message::Binary(frame.into()))
            .map_err(|e| e.to_string())
    }

    fn recv(&mut self, wait: Duration) -> Result<Option<Vec<u8>>, String> {
        self.set_timeout(wait);
        loop {
            match self.socket.read() {
                Ok(Message::Binary(b)) => return Ok(Some(b.to_vec())),
                Ok(Message::Close(_)) => return Err("the host closed the connection".into()),
                Ok(_) => continue,
                Err(tungstenite::Error::Io(e))
                    if matches!(
                        e.kind(),
                        std::io::ErrorKind::WouldBlock | std::io::ErrorKind::TimedOut
                    ) =>
                {
                    return Ok(None)
                }
                Err(e) => return Err(e.to_string()),
            }
        }
    }

    fn close(&mut self) {
        let _ = self.socket.close(None);
        let _ = self.socket.flush();
    }
}
