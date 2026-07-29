# Major Gym — Membership Manager (Android source project)

Native Android app (Kotlin + Jetpack Compose). All data — including member
photos — is stored on-device in a local SQLite database and internal app
storage via Room. No internet permission is requested and none is needed for
normal use.

## What's inside
- Dashboard: total/active/expiring/expired counts + total revenue (₹)
- Members: search, status ring, quick renew
- Add/Edit member: photo picker, plan selector, auto-calculated expiry
- Profile: full details, membership + payment history, renew/edit/delete
- Backup: export/import a single JSON file (photos embedded as base64) via
  Android's file picker — works with Google Drive, local storage, email, etc.

## How to build the actual .apk

1. Install **Android Studio** (free, from developer.android.com/studio) if
   you don't have it. Requires JDK 17, which Android Studio bundles.
2. Open Android Studio → **Open** → select this `MajorGym` folder.
3. Android Studio will detect there's no Gradle wrapper jar and offer to
   generate one — click **OK**. This needs internet once, to download Gradle
   and the project's dependencies (Compose, Room, Coil). The finished app
   itself needs no internet to run.
4. Wait for **Gradle Sync** to finish (bottom status bar).
5. Plug in your Android phone via USB with **USB debugging** enabled
   (Settings → About phone → tap "Build number" 7 times → Developer options →
   USB debugging), or use an emulator.
6. Click the green **Run ▶** button. The app installs and opens on your
   phone directly.

## To get a standalone `.apk` file (to share/install without Android Studio)

- **Build → Build App Bundle(s) / APK(s) → Build APK(s)**
- Once finished, click the "locate" link in the notification, or find it at:
  `app/build/outputs/apk/debug/app-debug.apk`
- Copy that file to your phone (or send it to yourself) and tap it to install.
  You'll need to allow "Install from unknown sources" for whichever app you
  used to open the file.

For a signed release build (recommended before sharing widely):
**Build → Generate Signed Bundle / APK → APK**, then follow the wizard to
create a signing key.

## Notes
- `minSdk` is 26 (Android 8.0+), which covers effectively all phones in use.
- No `google-services.json`, API keys, or backend of any kind are required.
- If Gradle sync complains about a missing wrapper jar, use
  **File → Sync Project with Gradle Files** after step 3, or run
  `gradle wrapper` once from a terminal if you have Gradle installed locally.

## Local Storage & Manual Sync

This version keeps the Room/SQLite database on each phone and provides a portable JSON backup/sync mechanism.

- No Firebase.
- No cloud database.
- No recurring backend service cost.
- Export data from one phone.
- Transfer the backup file to another phone.
- Import the backup on the other phone.
- Member records are matched by unique ID.

See `LOCAL_SYNC_GUIDE.md` for details.
