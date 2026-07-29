# MajorGym Local Storage & Manual Sync

MajorGym is configured as a local-first application with no Firebase and no cloud database.

## Storage

Member data stays in the phone's Room/SQLite database.

## Export

Use the app's export/backup action to create a portable `.json` backup.

Transfer the file to another phone using:
- USB
- WhatsApp
- Telegram
- Bluetooth
- Google Drive
- Any other file-transfer method

## Import

Use the app's import/restore action on the other phone.

Records are matched by the member ID:
- Existing IDs are updated.
- New IDs are inserted.

## Important

This is manual synchronization, not live automatic synchronization.

If two phones independently edit the same member, the last imported copy can overwrite the older local record. For safer production use, the app should add:
- `updatedAt`
- per-record revision numbers
- change logs
- conflict detection
- a merge screen

The app does not require Firebase, a cloud database, or an ongoing server subscription.
