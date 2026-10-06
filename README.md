# Scrinium Mobile

Android client for [Scrinium](https://github.com/mohak34/scrinium), a
self-hosted notes app that keeps notes as plain `.md` files on the server.

Notes live in a local database on the phone, so the app works offline.
While it is open, it syncs with the server: on launch, every minute, and a
few seconds after you stop typing. Nothing runs in the background except
task reminders.

Design notes are in [`docs/ARCHITECTURE.md`](docs/ARCHITECTURE.md). Rules
for working in this repo are in [`AGENTS.md`](AGENTS.md).

## What it does

- Notes and folders: create, rename, move, delete, pin
- Markdown editor with live preview, `[[wikilink]]` and `#tag` completion,
  and a format bar
- Backlinks, outline, frontmatter and word count per note
- Full-text search, tags, trash, share links
- Tasks with a list, board and calendar view, plus reminders as
  notifications. Tasks need a connection
- Image upload and preview
- PDF export
- Updates itself from GitHub releases

## Install

Download the APK from the
[latest release](https://github.com/mohak34/scrinium-mobile/releases/latest)
and open it on the phone. After that, update from Settings: when a newer
release exists, the About row offers it.

## Build

Needs JDK 17+, an Android SDK (`export ANDROID_HOME=~/Android/Sdk`) and a
running Scrinium backend.

1. Put the backend's Google web client ID in `keystore.properties` (not
   committed):

   ```properties
   scriniumGoogleClientId=<GOOGLE_CLIENT_ID>
   ```

   The app asks Google for an ID token issued to this client, which is the
   one the server checks. No separate Android client is needed.

2. Build a debug APK and install it:

   ```bash
   ./gradlew assembleDebug -PscriniumApiUrl="http://10.0.2.2:5173"
   adb install -r app/build/outputs/apk/debug/app-debug.apk
   ```

   `10.0.2.2` is the host machine as seen from the emulator. For a real
   phone on your Wi-Fi, use your computer's LAN IP and add it to
   `app/src/debug/res/xml/network_security_config.xml`, since debug builds
   only allow plain HTTP to listed hosts.

Release builds need your server's URL and a signing keystore, both in
`keystore.properties`:

```properties
scriniumApiUrl=https://notes.example.com
storeFile=release.jks
storePassword=...
keyAlias=...
keyPassword=...
```

```bash
./gradlew assembleRelease
```

Local builds don't check for updates. Only CI builds know which GitHub repo
to check.

## Release

Push a version tag:

```bash
git tag v0.3.0 && git push origin v0.3.0
```

`.github/workflows/release.yml` builds a signed APK and attaches it to a
GitHub release. The version name comes from the tag, and the app checks
that same repo's releases for updates, so a fork updates from its own
releases. It reads these repo secrets: `KEYSTORE_BASE64` (the `.jks` file,
base64), `STORE_PASSWORD`, `KEY_ALIAS`, `KEY_PASSWORD`, `GOOGLE_CLIENT_ID`,
and the repo variable `SCRINIUM_API_URL`.

Android only installs an update signed with the same key as the installed
app, so the in-app updater can't replace a debug build.
