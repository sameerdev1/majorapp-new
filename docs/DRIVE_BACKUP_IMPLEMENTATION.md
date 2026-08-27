# Google Drive Automatic Backup — Implementation Report

## Inspection performed first (per the brief)

- Backup/Restore screen: `ui/Screens.kt` → `BackupScreen()`
- Manual export/import: `data/BackupManager.kt` (`exportJson` / `importJson`) — this is the
  single reusable exporter/importer; it's what `MembersViewModel.exportJson`/`importJson`
  and the "Share Backup File" flow already call. A second, unrelated `LocalBackupManager.kt`
  exists with a different, unused format (`"MajorGym Local Sync"`) — confirmed via grep that
  nothing in the app actually calls it, so it was left untouched and NOT reused (using it would
  have created the "second incompatible backup format" the brief explicitly forbids).
- Member/data model: `data/Member.kt` (Room entity), `data/Repository.kt` (DB + file access,
  plus the existing "Share Backup File" internal-backups-folder logic: `saveInternalBackupCopy`,
  `latestInternalBackupFile`, `createBackupNow`, `getOrCreateLatestBackup`).
- Architecture: no navigation library (manual `Screen` sealed class + `when` in
  `MainActivity`), ViewModel via `MembersViewModel` (AndroidViewModel), SharedPreferences-backed
  settings classes following the `SyncPrefs.kt` pattern, WorkManager already used for
  `MembershipCleanupWorker` (daily periodic job).
- Dependencies/build: `app/build.gradle.kts`, Kotlin 1.9.24, Compose BOM 2024.06, `minSdk 27`.
  `INTERNET` / `ACCESS_NETWORK_STATE` permissions already declared (for the local Wi-Fi sync
  feature), so no new manifest permissions were needed for Drive REST calls.

## Files changed

- `app/build.gradle.kts` — added Google Sign-In + Drive v3 REST client dependencies.
- `app/src/main/java/com/majorgym/app/MembersViewModel.kt` — added a `driveBackup` controller
  instance and thin `viewModelScope` wrapper functions for the UI. Every existing function
  (`exportJson`, `importJson`, `latestBackupFile`, `getOrCreateLatestBackup`, sync functions) is
  unchanged.
- `app/src/main/java/com/majorgym/app/ui/Screens.kt` — `BackupScreen()`:
  1. Wrapped the whole content `Column` in `.verticalScroll(rememberScrollState())`. This was
     genuinely missing before (the screen used a plain `fillMaxSize()` Column with no scroll
     modifier at all) — on a small phone or once enough content exists, the lower cards would
     already have been unreachable. Fixed as part of satisfying spec section 17.
  2. Added one line, `DriveBackupSection(vm)`, at the end of the existing content — after every
     existing Export/Restore/Share card, so the new feature is purely additive.

## Files added

- `data/DriveBackupPrefs.kt` — local settings/state (SharedPreferences): connected account
  email, auto-backup on/off, backup time, retention, last-backup status/size/timestamp,
  next-backup timestamp, and a capped local history log.
- `data/DriveBackupManager.kt` — the Drive v3 REST wrapper: sign-in client, folder
  find-or-create, upload, list, download+validate, delete, retention cleanup, connectivity
  check.
- `data/BackupScheduleWorker.kt` — the self-rescheduling daily `CoroutineWorker`, plus
  `DriveBackupScheduler` (enqueue/cancel/re-arm logic).
- `data/DriveBackupController.kt` — `StateFlow`-based controller the UI reads from; the only
  layer that talks to both `Repository` (member data) and `DriveBackupManager`/`DriveBackupPrefs`.
- `ui/DriveBackupSection.kt` — all the new Compose UI: connection card, auto-backup
  toggle/time, status card + Backup Now, history, restore picker, retention dialog.

## Existing backup/export logic reused

`DriveBackupManager.performBackup()` calls `BackupManager.exportJson(context, repository.allOnce())`
— the exact same function the manual "Export Backup" button calls — so an automatic backup and a
manual export are byte-for-byte the same format. No new fields were added to the JSON schema.
Restore calls `BackupManager.importJson()` — the same function "Restore Records" uses — and
`Repository.mergeAll()`, the same non-destructive merge the existing manual import already uses
(so, like the manual restore, a Drive restore can never silently delete a local-only record).

## Google Drive implementation approach

- **Auth**: Google Sign-In (`GoogleSignInClient`, scope `DriveScopes.DRIVE_FILE`) for
  connect/change-account/disconnect, `GoogleAccountCredential` for the actual Drive API calls.
  The app never reads or stores an access/refresh token or password — Play Services /
  AccountManager manage that entirely on-device. Only the account's email is stored locally
  (for the "Connected to: owner@gmail.com" display), in plain `SharedPreferences` — that's
  fine because it's a display label, not a credential.
- **Folder**: `ensureBackupFolder()` searches for a folder literally named `Major Gym Backups`;
  reuses it if found, creates it once if not, and caches the id. Every read/write/delete this
  feature performs is scoped to files inside that folder — retention cleanup explicitly never
  touches anything else in the user's Drive.
- **Scope**: uses `drive.file` (not full Drive access) — the app can only see files it created
  itself, which is the minimum needed and the safest choice for a member-data app.

## Scheduling approach

WorkManager has no "run daily at a specific clock time" request type, so `DriveBackupScheduler`
chains one-shot `OneTimeWorkRequest`s: each run of `BackupScheduleWorker` computes tomorrow's
occurrence of the configured time and re-enqueues itself before finishing. The request carries a
`NetworkType.CONNECTED` constraint, so if the target time arrives with no internet, WorkManager
simply waits for connectivity rather than firing and failing — combined with exponential backoff
on `Result.retry()`, this is the "retry automatically" behavior from spec section 7 without a
hand-rolled polling loop. As the spec itself acknowledges, Android's Doze/battery-optimization
behavior means this can only ever be "approximately around" the selected time, never a guaranteed
exact trigger — the UI's "Next Backup" line is a target, not a promise.

## Encryption / security approach

Deliberately **did not** encrypt the backup payload before upload, per spec section 16's own
instruction not to invent an insecure scheme when there's no existing key-management mechanism
to build on. The two real options both have a hard tradeoff that needs a product decision, not a
silent implementation choice:

- **Android Keystore-derived key**: strongest option, but Keystore keys are hardware-bound and
  non-exportable — a backup encrypted this way could never be restored on a *different* phone,
  which breaks the exact "restore on a new phone" scenario this whole feature exists for.
- **User-chosen passphrase (PBKDF2 → AES-256-GCM)**: portable across devices, but if the owner
  ever forgets the passphrase, their backups become permanently unrecoverable — spec section 16
  explicitly says "do not lose the key/password required for restoration," and a forgotten
  passphrase is exactly that failure, just user-caused instead of app-caused.

Given that, the current security boundary is: HTTPS in transit (the Drive API is always TLS),
`drive.file` scope (the app-created folder isn't visible to other apps), and the fact that access
requires the owner's own Google account credentials — the same trust model the existing "Share
Backup File" feature already relies on when the owner shares the same JSON via WhatsApp/Gmail/
Drive manually. If passphrase-based encryption is wanted, it should be a follow-up with an
explicit "if you lose this, your backups can't be recovered" confirmation UX — flagging it here
rather than adding it silently.

## Restore approach

Select backup → show details → confirmation dialog with the exact required warning text
("Restoring this backup will replace the current app data") → automatic safety backup of
current data (`Repository.createBackupNow()`, the same internal-backup mechanism "Share Backup
File" already uses) → download → validate (`app == "MajorGym"` and a `members` array present,
matching `major-gym-backup (8).json`'s structure) → restore via the existing `mergeAll` import
path → done. An invalid/corrupted file is rejected before anything local is touched, matching
`❌ Invalid or corrupted backup.` from spec section 11.

## Scrolling implementation

Single `Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState())...)` wraps the
entire screen content — existing cards and the new section together — with no nested
scrollable containers anywhere inside it (backup history and the restore picker render as plain
bounded lists / a dialog-scoped list, specifically to avoid the nested-scroll conflicts spec
section 17 calls out). `BottomNav` is a separate overlay drawn by `MainActivity`, not part of
this Column, so it stays fixed above the scroll content exactly as it already did on every other
screen in the app.

## Tests performed

**None could be executed.** This sandbox has no Android SDK, emulator, or device, and the Google
Maven repository (`maven.google.com` / `dl.google.com`) needed to resolve the new dependencies
isn't in this environment's allowed network domains — so the project could not actually be built
here, and none of the acceptance-criteria checkboxes in the brief (scrolling on a real device,
sign-in flow, upload/restore against a real Drive account, etc.) could be verified end to end.

What *was* done instead: every existing file the new code touches or calls into was read in full
first; every new file's braces/parens were checked as balanced; every class/method referenced
from Google's Drive/Sign-In APIs was cross-checked against the well-established
"Android Drive quickstart" pattern (`GoogleSignInClient` + `GoogleAccountCredential` +
`Drive.Builder`); and the whole feature was kept in new, additively-wired files precisely so it
can be reviewed/tested in isolation without risk to the existing manual export/import path.

**Before this is shipped, it needs a real build + on-device pass** — Gradle sync (the new Drive/
Sign-In dependencies need to actually resolve), then manually walking the acceptance checklist:
connect/change/disconnect Drive, enable auto-backup and change the time, Backup Now, kill
connectivity and confirm the "pending" state, restore a backup, restore an intentionally-corrupted
file, and check the screen on a small phone and a tablet.

## Limitations / Android-specific constraints

- Scheduled time is "approximately around" the target, never exact — inherent to Android
  background execution, not something this implementation can fix.
- `drive.file` scope means backups uploaded by a *previous* version of the app (if one ever used
  full Drive access) wouldn't be visible here — not a concern for a first implementation, but
  worth knowing if this is ever revisited.
- No payload encryption yet, for the reasons above — current protection is transport (TLS) +
  account access control only.
- The Drive dependency versions in `build.gradle.kts` (`play-services-auth:21.2.0`,
  `google-api-client-android:2.2.0`, `google-api-services-drive:v3-rev20240914-2.0.0`,
  `google-http-client-gson:1.44.1`) are current as of this implementation's knowledge but should
  be checked against Maven Central / Google's Maven for newer patch releases at build time.
