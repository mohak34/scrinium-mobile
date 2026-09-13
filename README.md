# Scrinium Mobile

Native Android client for [Scrinium](https://github.com/mohak/scrinium), a
self-hosted, Obsidian-style notes app where notes are plain `.md` files on
the server's disk.

Local-first: Room is the source of truth the UI observes. A sync engine
reconciles with the server in the background while the app is open. See
`ARCHITECTURE.md` for the design and `AGENTS.md` for repo rules.

## Features

- File tree with folders: create, rename, move, delete, drag and drop
- Markdown editor with rich preview: headings, lists, task checkboxes,
  tables of code, math, blockquotes, kind-colored callouts, wikilinks, tag
  pills
- Title-filename two-way sync (first `# ` heading and frontmatter `title:`)
- Server full-text search with snippets, title-ranked like the web client
- Tag browsing via the server tags API
- Server trash with restore, purge, empty
- Public share links with optional passwords
- Foreground-only sync: 5s auto-push after edits settle, 60s pull tick,
  sync on foreground and pull-to-refresh. Killing the app stops everything.

Attachments are out of scope for v1.

## Getting started

Needs the Scrinium backend running (it provides auth, manifest, search,
tags, trash and share endpoints).

1. Copy the Google web client ID into `keystore.properties`:

   ```properties
   scriniumGoogleClientId=<GOOGLE_CLIENT_ID>
   ```

   The app requests its Google ID token with this as `serverClientId`, so
   the token audience matches what the server verifies. No separate
   Android credential.

2. Point the debug build at your backend (default is the emulator
   loopback):

   ```bash
   ./gradlew assembleDebug -PscriniumApiUrl="http://10.0.2.2:5173"
   ```

   Physical device on the same LAN: pass your machine's LAN IP instead,
   e.g. `-PscriniumApiUrl="http://10.0.0.71:5173"`. The debug network
   security config allowlists `10.0.2.2`, `localhost` and that IP for
   cleartext HTTP.

3. Install:

   ```bash
   adb install -r app/build/outputs/apk/debug/app-debug.apk
   ```

Release builds point at `https://scrinium.mohak.dev` and need the release
keystore described in `keystore.properties`:

```bash
./gradlew assembleRelease
```

`ANDROID_HOME` must point at an Android SDK, e.g.
`export ANDROID_HOME=~/Android/Sdk`.

## Auth

Credential Manager Google Sign-In, then `POST /api/auth/mobile`
`{ googleIdToken }` gives a long-lived API token. Stored encrypted with a
Keystore-backed AES-GCM key, sent as `Authorization: Bearer` per request.
401 drops the token and re-runs sign-in.

## Layout

- `data/local/` — Room database, DAO, entity
- `data/remote/` — Retrofit API, auth interceptor
- `data/` — repositories, encrypted token store, session
- `sync/` — manifest-first sync engine with conflict copies
- `ui/screens/` — Notes, Editor, Search, Tags, Trash, Settings, Login
- `ui/` — ViewModel (observes Room, owns navigation), theme, preview
  renderer, tree model
- `di/` — manual DI container

## Workflow

One branch per feature, squash-merge via pull request. Build the branch,
install on a device, test against a local backend before merging.
