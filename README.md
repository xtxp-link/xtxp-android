# xtxp-android

A native Android app for [XTXP](https://xtxp.link): drag and drop the elements
of a shared XML document (an SVG drawing, first), local first.

The phone keeps its own session of the document (decision 17), stored on the
device, so everything works with no network. WorkManager merges it with the
remote sessions it trusts, both ways, by Yjs sync with a y-sweet-compatible
host such as the [xtxp-worker](https://xtxp-worker.campey.workers.dev). Out of the box
it edits `xtxp-logo`, the document at https://xtxp-worker.campey.workers.dev/d/xtxp-logo/.
A remote can be added as `host/doc@session`, as the worker's page URL, or
without `@session`, which means `@main`.

## The `< + >` bar

| Part | Tap | Spec |
| --- | --- | --- |
| `‹` Online | Goes offline: no merging either way. The part turns into a pause sign, and the local changes waiting to merge are listed under the bar. Tap the pause sign to come back online. | A [paused queue](https://xtxp.link/spec/sessions/): a session whose automatic merge is off |
| `+` Merge | Merges with every remote now, both ways, online or offline. Shows how many local changes are waiting. | Releasing a paused queue is an ordinary merge; the host admits whole sessions (decision 19) |
| `Remotes ›` | Lists the sessions this one trusts, as `{host}/{path}@{session}`, with when each last merged or why it failed. Add or remove one there. | Trust between sessions drives automatic merges (decision 16) |

Long-press an element and drag it. Dropping on the top of a row puts it
before that row, on the bottom after it, and on the middle inside it as the
last child. A move is a delete plus a copy (decision 20), so an edit someone
makes to the element during your move lands on the original.

## Layout

| Path | What it is |
| --- | --- |
| `rust/` | `xtxp-mobile`: the muppet for the app. Xmesh and `xtxp-wire` from [xtxp-rs](https://github.com/xtxp-link/xtxp-rs) (pinned to one commit), the phone's session on disk, moves, pending changes, remotes, and one blocking sync per remote. Exposed to Kotlin with UniFFI. |
| `core/` | A plain JVM Kotlin build: the generated bindings, `Document` (the session as a `StateFlow`), and the bar and drop logic. Its tests load the muppet built for your machine. |
| `app/` | The Compose app: the bar, the element list, the remotes sheet, and `SyncWorker`. |

## Build

You need the Android SDK and NDK, a JDK 17+, and Rust from [rustup](https://rustup.rs)
(Homebrew's Rust can't add Android targets):

```sh
rustup target add aarch64-linux-android x86_64-linux-android
cargo install cargo-ndk
cd rust && cargo test && cd ..
./gradlew -p core test
./gradlew :app:installDebug
```

`cargo-ndk` finds the NDK through `ANDROID_NDK_HOME`, or the newest one under
the SDK's `ndk/` folder. CI builds a debug APK on every push and attaches it to
the run.

To try it against a local worker (`scripts/try-worker.sh` in xtxp-rs), add the
remote `http://10.0.2.2:8787/xtxp-logo@main` from the emulator.

## Not yet

- **Live updates while the app is open.** Online, the app merges after every
  change and every 15 minutes (WorkManager's shortest period), not over an
  open WebSocket.
- **One-way trust.** Every remote is trusted both ways; the arrows in the
  spec's diagram aren't settable yet.
- **Admission.** y-sweet and the worker accept every connection, so a merge
  is never refused (decision 25 lists this gap).
- **Auto-scroll while dragging**, and editing anything other than position.
- **One document**, `xtxp-logo`. Only `@main` exists on today's hosts.
