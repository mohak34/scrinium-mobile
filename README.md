# Scrinium Mobile

Native Android client for [Scrinium](https://github.com/mohak/scrinium), a
self-hosted, Obsidian-style notes app where notes are plain `.md` files on
the server's disk.

Local-first: Room is the source of truth the UI observes. A sync engine
reconciles with the server in the background while the app is open. See
`ARCHITECTURE.md` for the design and `AGENTS.md` for repo rules.

## Features

- Five bottom tabs: Notes, Tasks, Search (center), Board, Calendar.
  Look ported from the web app: true black, teal accent, Space Grotesk,
  Atkinson Hyperlegible Next and JetBrains Mono, Material Symbols icons
- File tree with folders: create, rename, move, delete, drag and drop
- Live-preview markdown editor: marks hide except on the cursor's line,
  `[[` note and `#tag` autocomplete. Rich preview mode too: headings,
  lists, task checkboxes, code, math, blockquotes, kind-colored callouts,
  wikilinks, tag pills. A format bar above the keyboard (bold, italic,
  link, tag, checkbox, list, image) with undo
- Pinned notes and folders, new-note template (`{{title}}`), both kept on
  the phone like the web keeps them in the browser
- PDF export through the Android print dialog
- Title-filename two-way sync (first `# ` heading and frontmatter `title:`)
- Search tab: notes (server full-text with snippets, title-ranked like the
  web client) and tasks, recent searches, tag shortcuts
- Tag browsing via the server tags API
- Server trash with restore, purge, empty, restore all. Swipe right to
  restore, left to delete forever
- Public share links with optional passwords
- Tasks: list grouped by status with area tabs, board paged by status
  (long-press to move), month calendar by due date with Google Calendar
  events, area filter.
  Detail screen with status, area, priority, due date and time, reminder,
  waiting-on, subtasks and linked notes. Online only. Reminders become
  phone notifications; ones set on the web arm the next time the app opens.
  Settings can turn reminders off and lists the scheduled ones
- Note panel: outline, frontmatter properties, tasks linked to the note,
  backlinks, unlinked mentions you can turn into links, word count
- Settings: sync status with notes waiting to upload, devices (list and
  revoke signed-in phones), template editor with preview
- Images in preview (vault images through the asset API, disk-cached for
  an hour), and image upload from the editor into `attachments/`
- Foreground-only sync: 5s auto-push after edits settle, 60s pull tick,
  sync on foreground and pull-to-refresh. Killing the app stops sync;
  only reminder alarms outlive it.

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

