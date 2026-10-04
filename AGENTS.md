# Scrinium Mobile

Native Android client for [Scrinium](https://github.com/mohak/scrinium) — a
self-hosted, Obsidian-style notes app where notes are plain `.md` files on the
server's disk. The mobile client is **local-first**: Room is the source of
truth the UI observes, and a sync engine reconciles with the server in the
background.

`ARCHITECTURE.md` is the design spec — read it before touching anything. The
rules below are the decisions that keep it small and battery-friendly.

## Non-negotiables

1. **No background work at all.** Sync runs only while the app is open:
   auto-push 5s after local edits settle, a 60s pull tick while
   foregrounded, plus on app foreground and manual pull-to-refresh. No
   WorkManager jobs, no foreground service, no polling loop — killing the
   app stops everything.
2. **Manifest-first sync.** Sync fetches `GET /api/notes/manifest` (metadata
   only — path, mtime, size-fingerprint), diffs against Room, and only then
   fetches/pushes the notes that actually changed. Never download the whole
   vault to check for changes.
3. **Room is the UI's single source of truth.** ViewModels observe Room via
   Flow; the sync engine is the only thing that talks to the network. UI never
   writes to the server directly.
4. **Debounced autosave is local-only.** Typing writes to Room on a ~500ms
   debounce; the server push happens on sync triggers, not on keystrokes.
5. **Conflicts never lose data.** On conflict, the local version is saved
   server-side as `name (conflict, phone, <timestamp>).md` alongside the
   pulled server version. No merge UI.
6. **YAGNI is a feature.** Single-user allowlist app, no Hilt, no DataStore
   repository ceremony, no over-abstraction. One `:app` module.

## Stack

Kotlin + Jetpack Compose (Material 3, dark theme ported from the web app's
`DESIGN.md` palette), Room, Retrofit + OkHttp, WorkManager, DataStore for
preferences, Android Keystore (AES-GCM) for token encryption, Credential
Manager for Google Sign-In. MVVM, manual DI via an `AppContainer`.

## Auth flow (mobile)

1. Credential Manager `GetGoogleIdOption` with `serverClientId` = the web
   client ID from the backend's `GOOGLE_CLIENT_ID` — so the ID token's
   audience matches what the server verifies (no separate Android credential).
2. `POST /api/auth/mobile` `{ googleIdToken }` → `{ apiToken, email }`.
   Server checks the token's `aud` = `GOOGLE_CLIENT_ID` and the email against
   `ALLOWED_EMAILS`.
3. The raw API token is stored locally encrypted with a Keystore-backed
   AES-GCM key; sent as `Authorization: Bearer <token>` on every request.
4. Tokens are long-lived (no expiry server-side). 401 → token revoked or
   server data reset → drop token, re-run Google sign-in.

## API contract

Base URL is a BuildConfig field (`SCRINIUM_API_URL`), `http://10.0.2.2:5173`
in debug (host `localhost:5173` from the emulator), `https://scrinium.mohak.dev`
in release.

- `GET /api/notes/manifest` → `[{ path, updatedAt, contentHash }]` for every
  `.md` note; `contentHash` = `"<size>:<mtimeMs>"` fingerprint (stat only).
- `GET /api/notes/<path>` → note content as `text/plain`
- `PUT /api/notes/<path>` → write note, body `text/plain`
- `POST /api/notes/<path>` → `{ folder: true }` creates a folder
- `PATCH /api/notes/<path>` → `{ newPath }` rename/move
- `DELETE /api/notes/<path>` → move to trash (recovers to vault `.trash`)
- `GET /api/tree` → nested vault listing
- `GET /api/search?q=` → `[{ path, title, snippet }]` (FTS5)
- `GET /api/tasks` (`?note=<path>` for one note's tasks), `POST /api/tasks`,
  `PATCH|DELETE /api/tasks/<id>`, `GET|POST|DELETE /api/tasks/<id>/links`.
  Rows are snake_case. Tasks live in the server's SQLite, not the vault, so
  they are online-only: `TasksViewModel` calls the API directly, no Room.
- `GET /api/assets/<path>` → image bytes; `POST /api/attachments` (multipart
  `file` + `folder`) → `{ path }`. Phone uploads go to `attachments/`, the
  web default, and the note gets a note-relative `![name](path)`.

Paths are URL-encoded per segment. Backlinks are computed on the phone from
Room (`ui/Backlinks.kt`, a port of the web's `wikilinks.ts`), not fetched.

## Sync engine rules

On trigger (app foreground, pull-to-refresh, periodic WorkManager):

1. Fetch manifest; build `{path: contentHash}` remote map.
2. For each Room note compare `contentHash`:
   - local has unsynced edits (`localModifiedAt != null`) + remote changed →
     **conflict**: fetch server copy, keep both (conflict-renamed local push)
   - local unsynced, remote unchanged → push `PUT`
   - remote changed, no local edits → pull `GET`, update Room
   - neither changed → skip (the cheap majority)
3. Room notes missing from remote + no local edits → they were deleted
   elsewhere; delete locally. Local deletes push `DELETE`.
4. On success, clear `localModifiedAt` and update `contentHash`/`updatedAt`.

## Commands

- `./gradlew assembleDebug` — build the APK (needs Android SDK; SDK setup is
  pending — machine has no SDK/emulator yet, JDK 26 installed)
- `bun run check` in the scrinium repo (sibling dir) is the backend gate —
  0 errors / 0 warnings before backend commits

## Gotchas

- **Emulator ↔ dev server**: host `localhost:5173` appears as
  `10.0.2.2:5173` inside the emulator. For WebView-free flows this only
  affects the API base URL, which is already covered by BuildConfig.
- **Room + KSP**: KSP only publishes some Kotlin patches — match the minor
  version and use the latest KSP patch (e.g. Kotlin `2.3.21` + KSP `2.3.12`).
  Check Maven Central for the newest `2.x` KSP before bumping Kotlin.
- **`isDeleted` notes** must still push their delete on the next sync —
  don't drop them from Room until the server confirmed the delete.
