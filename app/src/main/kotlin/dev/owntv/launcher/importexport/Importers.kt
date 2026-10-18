package dev.owntv.launcher.importexport

import android.content.Context
import android.graphics.Bitmap
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import com.google.zxing.BarcodeFormat
import com.google.zxing.EncodeHintType
import com.google.zxing.qrcode.QRCodeWriter
import dev.owntv.layout.codec.LayoutCodec
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.InputStream
import java.util.concurrent.TimeUnit

/** Downloads a layout from a URL, e.g. a raw GitHub gist link. */
class UrlImporter {
    private val client = OkHttpClient.Builder()
        .connectTimeout(10, TimeUnit.SECONDS)
        .readTimeout(15, TimeUnit.SECONDS)
        .build()

    suspend fun fetch(url: String): Result<String> = withContext(Dispatchers.IO) {
        runCatching {
            val request = Request.Builder().url(url.trim()).header("Accept", "application/json, text/plain, */*").build()
            client.newCall(request).execute().use { response ->
                if (!response.isSuccessful) error("The server answered HTTP ${response.code}")
                response.body.byteStream().readLimited(LayoutCodec.MAX_LENGTH)
            }
        }
    }

    private fun InputStream.readLimited(limit: Int): String {
        val out = ByteArrayOutputStream()
        val buffer = ByteArray(8192)
        while (true) {
            val read = read(buffer)
            if (read < 0) break
            out.write(buffer, 0, read)
            if (out.size() > limit) error("The file is larger than ${limit / 1000} KB")
        }
        return out.toString(Charsets.UTF_8.name())
    }
}

/** Layout files in the app's external folder, where `adb push` can put them. */
class FileImporter(context: Context) {
    val folder: File = context.getExternalFilesDir("layouts") ?: File(context.filesDir, "import")

    val adbPath: String get() = folder.absolutePath

    fun list(): List<File> {
        folder.mkdirs()
        return folder.listFiles { f -> f.isFile && f.extension.equals("json", ignoreCase = true) }
            ?.sortedByDescending { it.lastModified() }
            .orEmpty()
    }

    suspend fun read(file: File): String = withContext(Dispatchers.IO) { file.readText() }
}

object QrCode {
    fun bitmap(text: String, size: Int = 480): ImageBitmap {
        val matrix = QRCodeWriter().encode(text, BarcodeFormat.QR_CODE, size, size, mapOf(EncodeHintType.MARGIN to 1))
        val pixels = IntArray(size * size) { i -> if (matrix[i % size, i / size]) 0xFF000000.toInt() else 0xFFFFFFFF.toInt() }
        return Bitmap.createBitmap(pixels, size, size, Bitmap.Config.ARGB_8888).asImageBitmap()
    }
}
