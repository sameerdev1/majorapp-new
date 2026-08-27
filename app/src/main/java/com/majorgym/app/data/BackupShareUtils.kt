package com.majorgym.app.data

import android.content.Context
import android.content.Intent
import androidx.core.content.FileProvider
import java.io.File

/**
 * Shares an existing backup ZIP file (Feature 1: Share Backup File) through
 * the system share sheet — whatever the device offers (WhatsApp, Gmail,
 * Telegram, Bluetooth, a cloud storage app the owner has installed, etc.).
 * Uses the same FileProvider already declared in AndroidManifest.xml for QR
 * sharing (see QrShareUtils), just a different internal path (see
 * file_paths.xml's "backups" entry).
 */
object BackupShareUtils {
    fun shareBackupFile(context: Context, file: File) {
        val uri = FileProvider.getUriForFile(context, "${context.packageName}.fileprovider", file)
        val intent = Intent(Intent.ACTION_SEND).apply {
            type = "application/zip"
            putExtra(Intent.EXTRA_STREAM, uri)
            putExtra(Intent.EXTRA_SUBJECT, "MajorGym Backup")
            putExtra(
                Intent.EXTRA_TEXT,
                "MajorGym Backup File\n\nImport this backup using the Restore Backup option inside the MajorGym Owner App."
            )
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        context.startActivity(Intent.createChooser(intent, "Share MajorGym Backup"))
    }
}
