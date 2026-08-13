package wang.xuann.sharebridge

import android.app.Activity
import android.content.Intent
import android.graphics.BitmapFactory
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.widget.Toast
import androidx.core.content.FileProvider
import java.io.File

class ShareBridgeActivity : Activity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        try {
            handleSendIntent(intent)
        } catch (e: Exception) {
            e.printStackTrace()
            Toast.makeText(this, "Share failed: ${e.localizedMessage}", Toast.LENGTH_SHORT).show()
        } finally {
            finish()
        }
    }

    private fun handleSendIntent(intent: Intent) {
        val action = intent.action
        if (action != Intent.ACTION_SEND && action != Intent.ACTION_VIEW) {
            return
        }

        // Get Uri from EXTRA_STREAM (for ACTION_SEND) or intent.data (for ACTION_VIEW / Intent data)
        val uri = getParcelableExtraUri(intent, Intent.EXTRA_STREAM) ?: intent.data ?: return

        // Step 1: Detect MIME type & File Extension by reading header only
        val (mimeType, extension) = detectFileType(uri)

        // Step 2: Copy stream into cache with complete extension
        val cacheFile = copyToCache(uri, extension) ?: return

        // Step 3: Generate FileProvider URI & dispatch ACTION_SEND intent
        val contentUri = FileProvider.getUriForFile(
            this,
            "$packageName.fileprovider",
            cacheFile
        )

        val shareIntent = Intent(Intent.ACTION_SEND).apply {
            type = mimeType
            putExtra(Intent.EXTRA_STREAM, contentUri)
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }

        val chooserIntent = Intent.createChooser(shareIntent, null).apply {
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }

        startActivity(chooserIntent)
    }

    @Suppress("DEPRECATION")
    private fun getParcelableExtraUri(intent: Intent, key: String): Uri? {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            intent.getParcelableExtra(key, Uri::class.java)
        } else {
            intent.getParcelableExtra(key)
        }
    }

    private fun detectFileType(uri: Uri): Pair<String, String> {
        // Read initial header bytes for magic number analysis
        val headerBytes = ByteArray(32)
        var readCount = 0
        try {
            contentResolver.openInputStream(uri)?.use { stream ->
                readCount = stream.read(headerBytes, 0, headerBytes.size)
            }
        } catch (e: Exception) {
            e.printStackTrace()
        }

        if (readCount > 0) {
            val magicMime = parseMagicNumber(headerBytes, readCount)
            if (magicMime != null) {
                val ext = getExtensionForMime(magicMime)
                return Pair(magicMime, ext)
            }
        }

        // Fallback: BitmapFactory inJustDecodeBounds (reads header without decoding pixels)
        val options = BitmapFactory.Options().apply {
            inJustDecodeBounds = true
        }
        try {
            contentResolver.openInputStream(uri)?.use { stream ->
                BitmapFactory.decodeStream(stream, null, options)
            }
        } catch (e: Exception) {
            e.printStackTrace()
        }

        val detectedMime = options.outMimeType ?: contentResolver.getType(uri) ?: "image/jpeg"
        val extension = getExtensionForMime(detectedMime)
        return Pair(detectedMime, extension)
    }

    private fun parseMagicNumber(header: ByteArray, length: Int): String? {
        if (length < 2) return null

        // JPEG: FF D8 FF
        if (length >= 3 && header[0] == 0xFF.toByte() && header[1] == 0xD8.toByte() && header[2] == 0xFF.toByte()) {
            return "image/jpeg"
        }

        // PNG: 89 50 4E 47 0D 0A 1A 0A
        if (length >= 8 &&
            header[0] == 0x89.toByte() && header[1] == 0x50.toByte() &&
            header[2] == 0x4E.toByte() && header[3] == 0x47.toByte() &&
            header[4] == 0x0D.toByte() && header[5] == 0x0A.toByte() &&
            header[6] == 0x1A.toByte() && header[7] == 0x0A.toByte()
        ) {
            return "image/png"
        }

        // GIF: 47 49 46 38 ("GIF8")
        if (length >= 6 &&
            header[0] == 0x47.toByte() && header[1] == 0x49.toByte() &&
            header[2] == 0x46.toByte() && header[3] == 0x38.toByte()
        ) {
            return "image/gif"
        }

        // WEBP: RIFF....WEBP (header[0..3] == "RIFF", header[8..11] == "WEBP")
        if (length >= 12 &&
            header[0] == 'R'.code.toByte() && header[1] == 'I'.code.toByte() &&
            header[2] == 'F'.code.toByte() && header[3] == 'F'.code.toByte() &&
            header[8] == 'W'.code.toByte() && header[9] == 'E'.code.toByte() &&
            header[10] == 'B'.code.toByte() && header[11] == 'P'.code.toByte()
        ) {
            return "image/webp"
        }

        // BMP: BM (header[0..1] == "BM")
        if (length >= 2 && header[0] == 'B'.code.toByte() && header[1] == 'M'.code.toByte()) {
            return "image/bmp"
        }

        return null
    }

    private fun getExtensionForMime(mimeType: String): String {
        return when (mimeType.lowercase()) {
            "image/jpeg", "image/jpg" -> ".jpg"
            "image/png" -> ".png"
            "image/gif" -> ".gif"
            "image/webp" -> ".webp"
            "image/bmp" -> ".bmp"
            "image/heic", "image/heif" -> ".heic"
            "image/avif" -> ".avif"
            else -> ".jpg"
        }
    }

    private fun copyToCache(sourceUri: Uri, extension: String): File? {
        val sharedDir = File(cacheDir, "shared").apply {
            if (!exists()) mkdirs()
        }
        // Clean up cached share files older than 24 hours
        sharedDir.listFiles()?.forEach { file ->
            if (System.currentTimeMillis() - file.lastModified() > 86400000) {
                file.delete()
            }
        }

        val targetFile = File(sharedDir, "shared_${System.currentTimeMillis()}$extension")

        return try {
            contentResolver.openInputStream(sourceUri)?.use { inputStream ->
                targetFile.outputStream().use { outputStream ->
                    inputStream.copyTo(outputStream)
                }
            } ?: return null
            targetFile
        } catch (e: Exception) {
            e.printStackTrace()
            null
        }
    }
}
