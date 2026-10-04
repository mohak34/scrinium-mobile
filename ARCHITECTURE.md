# Scrinium Mobile — Architecture Plan (Native Android)

## Stack decision

- **Kotlin + Jetpack Compose** — native UI, no cross-platform runtime
  overhead. Given "don't care about iOS" + battery/lightweight as the
  top priority, this beats React Native and Flutter for this specific
  app.
- **Room** — local SQLite database, the offline source of truth for
  notes
- **WorkManager** — battery-aware background sync (respects Doze mode,
  battery constraints, network availability — this is the standard
  Android-recommended way to do exactly the kind of periodic sync you
  need, and it's built to not fight the OS's battery optimization)
- **Retrofit + OkHttp** — API client for talking to Scrinium's backend
- **DataStore (encrypted) / Android Keystore** — auth token storage
- **MVVM + Repository pattern** — ViewModel + StateFlow driving Compose
  UI, with Room as the single source of truth the UI observes; sync is
  a background concern that updates Room, not something the UI talks
  to directly

---

## Required backend changes to Scrinium (do this first)

Your current web app uses Better Auth's cookie-based sessions, which
doesn't translate to mobile. Two additions needed on the Scrinium
backend before mobile can talk to it at all:

### 1. Token-based auth endpoint

After Google Sign-In succeeds on the phone (using Android's native
Google Sign-In / Credential Manager API), the app gets a Google ID
token. Add an endpoint on Scrinium:

```
POST /api/auth/mobile
Body: { googleIdToken: string }
→ verifies the ID token server-side against Google
→ checks it against your existing ALLOWED_EMAILS allowlist
  (reuse the exact same check from databaseHooks.user.create)
→ issues a long-lived API token (store it, associate with the user)
→ returns { apiToken: string }
```

The app stores `apiToken` in encrypted DataStore and sends it as
`Authorization: Bearer <token>` on every subsequent request. This
reuses your existing allowlist logic — same security model, different
transport (bearer token instead of cookie).

### 2. Delta-sync endpoint

For efficient offline sync, the app shouldn't have to fetch full note
content just to check what changed. Add:

```
GET /api/notes/manifest
→ returns [{ path, updatedAt, contentHash }, ...] for the whole vault
```

This is a cheap call (metadata only) the phone can use to figure out
what actually needs downloading — important both for battery (fewer,
smaller requests) and for your 1GB VPS (avoid sending full vault
content on every sync check).

---

## Local data model (Room)

```kotlin
@Entity(tableName = "notes")
data class NoteEntity(
    @PrimaryKey val path: String,
    val content: String,
    val remoteUpdatedAt: Long,      // last known server timestamp
    val localModifiedAt: Long?,     // null if no unsynced local edits
    val contentHash: String,
    val isDeleted: Boolean = false
)
```

The `localModifiedAt` field is what drives sync: null means "in sync
with server," non-null means "has local changes waiting to push."

---

## Sync engine

**On sync trigger** (app foreground, manual pull-to-refresh, or
periodic WorkManager job):

1. Fetch `/api/notes/manifest` (cheap — metadata only)
2. For each note, compare local `contentHash` vs remote:
   - Local has unsynced edits (`localModifiedAt != null`) AND remote
     changed since last sync → **conflict** (see below)
   - Local has unsynced edits, remote unchanged → push local → server
   - Remote changed, no local edits → pull remote content → update Room
   - Neither changed → skip (this is why the manifest step matters —
     most notes hit this branch and cost nothing)
3. Push and pull happen as individual note content requests, only for
   notes that actually need it

**Conflict handling**: for a single-user, multi-device app, don't build
a merge UI for v1 — that's real complexity for a rare case. Simplest
safe approach: on conflict, save the local version as
`notename (conflict, phone, <timestamp>).md` alongside pulling the
server version, so you never silently lose an edit. You'll almost never
hit this in practice (it requires editing the same note offline on two
devices between syncs), but when you do, nothing gets lost.

---

## Battery/lightweight specifics — where this actually gets decided

This is worth being explicit about since it's your stated top priority:

- **No foreground service.** Sync is a WorkManager job that runs,
  finishes, and stops — never a persistent background process holding
  a wakelock.
- **No background sync.** Sync triggers, all foreground-only: auto-push 5s
  after local edits settle, a 60s pull tick while foregrounded, app opened
  from background, and manual pull-to-refresh. Killing the app stops
  sync — no WorkManager jobs, no service, no polling loop. Task reminders
  are the one exception: one exact alarm per reminder, which wakes the app
  only to post its notification.
- **Manifest-first sync** (above) means most sync cycles transfer a few
  KB of metadata and nothing else, not full note bodies.
- **Lazy content loading**: don't hold the entire vault's text in memory
  at once. Room + Compose's lazy lists mean only visible note previews
  and the currently-open note's full content need to be in memory.
- **Debounced autosave**, same principle as the web app's existing
  debounced autosave — write to Room locally on a short debounce while
  typing (cheap, local, no network), but only _sync to server_ on the
  triggers above, not on every keystroke.

---

## Feature parity checklist (web → mobile)

| Web feature                                  | Mobile equivalent                                                 |
| -------------------------------------------- | ----------------------------------------------------------------- |
| Google OAuth + email allowlist               | Native Google Sign-In → mobile auth endpoint (above)              |
| Sidebar / file tree                          | Compose `LazyColumn` note list, same vault structure              |
| Markdown editor                              | Live-preview text field + preview toggle                          |
| Debounced autosave                           | Same concept, local-first (see above)                             |
| `/api/tree` vault listing                    | Served by the new manifest endpoint instead                       |
| Note CRUD                                    | Room locally + sync engine reconciles with server                 |
| Attachments (drop into `vault/attachments/`) | Photo picker upload to `attachments/`; images render in preview   |

---

## Live-preview editor

`livePreview.ts`'s core idea — hide markdown marks (`**`, `#`, etc.)
except on the line the cursor is on — is a `VisualTransformation`
(`ui/LiveMarkdown.kt`). `liveLayout` parses the text line by line,
styles headings, bold, italic, code, links, tags and code blocks, and
drops the marks from every line outside the selection; the cursor's
lines keep them, dimmed. It returns both offset maps the text field
needs, so taps and the cursor land on the right raw character. Without
focus, every line renders clean. The text itself never changes.

---

## Build order

1. **Backend additions** — mobile auth endpoint + manifest endpoint on
   Scrinium (small, testable independently with curl before any mobile
   code exists)
2. **Auth flow** — Google Sign-In → token exchange → encrypted storage
3. **Read-only sync** — pull manifest, pull note content, display in a
   list + basic viewer. Get this solid before adding editing at all.
4. **Local editing + push sync** — Room writes, debounced autosave,
   push-on-trigger logic
5. **Conflict handling** — the save-both-copies fallback above
6. **Phase 1 editor polish** — preview toggle, note creation/deletion
7. **Phase 2: live-preview editor port** — once the above is genuinely
   in daily use and stable
