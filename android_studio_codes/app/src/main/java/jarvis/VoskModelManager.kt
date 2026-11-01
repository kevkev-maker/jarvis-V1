package com.example.myapp.jarvis

import android.content.Context
import android.util.Log
import java.io.BufferedInputStream
import java.io.File
import java.io.FileOutputStream
import java.net.HttpURLConnection
import java.net.URL
import java.util.zip.ZipInputStream

/**
 * Downloads the free, offline Vosk English model (about 40 MB, Apache 2.0 licence)
 * once, and unpacks it into the app's private storage. After that, wake-word
 * detection works without internet.
 */
object VoskModelManager {

    private const val TAG = "JARVIS_MODEL"
    const val MODEL_URL = "https://alphacephei.com/vosk/models/vosk-model-small-en-us-0.15.zip"
    private const val MODEL_DIR = "vosk-model"
    private const val DONE_MARKER = ".ready"

    private fun baseDir(context: Context) = File(context.filesDir, MODEL_DIR)

    fun isReady(context: Context): Boolean = File(baseDir(context), DONE_MARKER).exists()

    /** Folder that contains the model files (am/, conf/, graph/ ...). */
    fun modelPath(context: Context): String? {
        val base = baseDir(context)
        if (!isReady(context)) return null
        if (File(base, "am").exists()) return base.absolutePath
        // The zip contains one top folder, e.g. vosk-model-small-en-us-0.15/
        val inner = base.listFiles()?.firstOrNull { it.isDirectory && File(it, "am").exists() }
        return inner?.absolutePath
    }

    /**
     * Blocking download + unzip. Call from a background thread.
     * [onProgress] gets 0..100. Throws an exception on failure.
     */
    fun download(context: Context, onProgress: (Int) -> Unit) {
        val base = baseDir(context)
        base.deleteRecursively()
        base.mkdirs()
        val zipFile = File(context.cacheDir, "vosk-model.zip")

        val conn = openFollowingRedirects(URL(MODEL_URL))
        val total = conn.contentLengthLong
        conn.inputStream.use { input ->
            FileOutputStream(zipFile).use { out ->
                val buf = ByteArray(64 * 1024)
                var done = 0L
                var lastPct = -1
                while (true) {
                    val n = input.read(buf)
                    if (n < 0) break
                    out.write(buf, 0, n)
                    done += n
                    if (total > 0) {
                        val pct = (done * 90 / total).toInt()   // 0-90% = download
                        if (pct != lastPct) { lastPct = pct; onProgress(pct) }
                    }
                }
            }
        }
        conn.disconnect()

        // Unzip (90-100%)
        val basePath = base.canonicalPath + File.separator
        ZipInputStream(BufferedInputStream(zipFile.inputStream())).use { zip ->
            while (true) {
                val entry = zip.nextEntry ?: break
                val outFile = File(base, entry.name)
                // Safety: never write outside the model folder
                if (!outFile.canonicalPath.startsWith(basePath)) {
                    throw SecurityException("Bad zip entry: ${entry.name}")
                }
                if (entry.isDirectory) {
                    outFile.mkdirs()
                } else {
                    outFile.parentFile?.mkdirs()
                    FileOutputStream(outFile).use { zip.copyTo(it) }
                }
                zip.closeEntry()
            }
        }
        zipFile.delete()
        onProgress(100)

        File(base, DONE_MARKER).writeText("ok")
        if (modelPath(context) == null) {
            File(base, DONE_MARKER).delete()
            throw IllegalStateException("Model files missing after unzip")
        }
        Log.d(TAG, "Model ready at ${modelPath(context)}")
    }

    private fun openFollowingRedirects(start: URL): HttpURLConnection {
        var url = start
        repeat(5) {
            val conn = url.openConnection() as HttpURLConnection
            conn.connectTimeout = 20_000
            conn.readTimeout = 30_000
            conn.instanceFollowRedirects = true
            val code = conn.responseCode
            if (code == HttpURLConnection.HTTP_OK) return conn
            val next = conn.getHeaderField("Location")
            conn.disconnect()
            if (code in 300..399 && next != null) {
                url = URL(url, next)
            } else {
                throw IllegalStateException("Download failed (HTTP $code)")
            }
        }
        throw IllegalStateException("Too many redirects")
    }

    /**
     * Checks whether the word "jarvis" exists in the model's vocabulary.
     * Returns null if the vocabulary file can't be found (we then just try).
     */
    fun vocabularyHasJarvis(modelPath: String): Boolean? {
        val words = File(modelPath, "graph/words.txt")
        if (!words.exists()) return null
        return try {
            words.useLines { lines -> lines.any { it.startsWith("jarvis ") } }
        } catch (e: Exception) { null }
    }
}
