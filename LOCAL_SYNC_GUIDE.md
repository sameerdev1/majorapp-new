# MajorGym Local Storage, Wi-Fi Sync & Manual Backup

MajorGym is configured as a local-first application with no Firebase and no cloud database.

## Live device sync (new)

The **Sync** tab lets up to 3 authorized phones (the gym's phone + up to 2 more)
sync member records directly over the same Wi-Fi network or a mobile hotspot -
no internet connection, no server, no account.

1. On the first phone, open **Sync**, tap **Generate New** to create a 6-character
   sync code, then **Save Code**.
2. On each other phone (up to 2), open **Sync**, type in the *exact same* code,
   then **Save Code**.
3. Put all phones on the same Wi-Fi network (or connect them to one phone's
   mobile hotspot).
4. Open **Sync** on two phones at a time and tap **Sync Now** on both within
   about 20 seconds of each other. They'll find each other, merge their member
   lists (whichever copy of a record was edited most recently wins), and both
   end up with the same combined data.
5. Repeat with the third phone.

Only devices that know the sync code can join, and a circle is capped at 3
devices total. This only uses the local network - it never contacts the
internet or any Anthropic/Google/Firebase service, and networking only runs
while a sync is actively in progress.

## Manual file backup (still available)

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
