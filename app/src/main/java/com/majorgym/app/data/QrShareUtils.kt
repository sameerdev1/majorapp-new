package com.majorgym.app.data

import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import androidx.core.content.FileProvider
import java.io.File
import java.io.FileOutputStream

/**
 * Shares a rendered QR bitmap (e.g. the static gym attendance QR on the
 * Dashboard) through the system share sheet — WhatsApp, Gallery, Print,
 * Bluetooth, whatever the device offers.
 *
 * Android blocks sharing raw file:// paths on modern versions, so the bitmap
 * is written into a dedicated cache folder and handed off through the
 * FileProvider declared in AndroidManifest.xml (authorities matches
 * "${applicationId}.fileprovider", paths matches res/xml/file_paths.xml).
 */
object QrShareUtils {
    fun shareBitmap(context: Context, bitmap: Bitmap, fileName: String, chooserTitle: String) {
        val dir = File(context.cacheDir, "shared_qr").apply { mkdirs() }
        val file = File(dir, fileName)
        FileOutputStream(file).use { out -> bitmap.compress(Bitmap.CompressFormat.PNG, 100, out) }

        val uri = FileProvider.getUriForFile(context, "${context.packageName}.fileprovider", file)
        val intent = Intent(Intent.ACTION_SEND).apply {
            type = "image/png"
            putExtra(Intent.EXTRA_STREAM, uri)
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        context.startActivity(Intent.createChooser(intent, chooserTitle))
    }
}
