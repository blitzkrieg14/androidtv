package io.github.abhishekkaisar.glassos

import android.content.ContentProvider
import android.content.ContentValues
import android.content.Context
import android.content.Intent
import android.database.Cursor
import android.database.MatrixCursor
import android.net.Uri
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.os.ParcelFileDescriptor
import android.provider.OpenableColumns
import org.json.JSONArray
import java.io.File
import java.io.FileOutputStream
import java.net.HttpURLConnection
import java.net.URL

/**
 * Checks https://github.com/blitzkrieg14/androidtv/releases automatically when GlassOS opens (and from Settings),
 * downloads new release APKs and starts the Android package installer.
 */
object AppUpdater {
    const val REPO = "blitzkrieg14/androidtv"
    const val REPO_URL = "https://github.com/$REPO"
    private const val RELEASES_API = "https://api.github.com/repos/$REPO/releases?per_page=5"
    private const val AUTO_CHECK_COOLDOWN_MS = 10 * 60_000L

    @Volatile
    private var lastCheckMs = 0L

    @Volatile
    var latestUpdate: UpdateInfo? = null
        private set

    data class UpdateInfo(
        val tagName: String,
        val versionName: String,
        val title: String,
        val notes: String,
        val apkUrl: String,
        val apkName: String
    )

    fun apkFile(ctx: Context): File =
        File(File(ctx.cacheDir, "updates").apply { mkdirs() }, "GlassOS-update.apk")

    /**
     * Checks GitHub Releases for a newer version than [BuildConfig.VERSION_NAME].
     * Calls [onResult] on the UI thread with `(UpdateInfo?, errorMessage?)`.
     */
    fun checkForUpdates(
        ctx: Context,
        force: Boolean = false,
        onResult: (UpdateInfo?, String?) -> Unit
    ) {
        val now = System.currentTimeMillis()
        if (!force && now - lastCheckMs < AUTO_CHECK_COOLDOWN_MS) {
            return
        }
        lastCheckMs = now
        val ui = Handler(Looper.getMainLooper())
        Thread {
            try {
                val (code, body) = HeroRepo.get(
                    RELEASES_API,
                    mapOf(
                        "Accept" to "application/vnd.github+json",
                        "User-Agent" to "GlassOS-TV/${BuildConfig.VERSION_NAME}"
                    )
                )
                if (code != 200 || body == null) {
                    ui.post { onResult(null, "GitHub returned HTTP $code") }
                    return@Thread
                }
                val arr = JSONArray(body)
                var found: UpdateInfo? = null
                for (i in 0 until arr.length()) {
                    val rel = arr.optJSONObject(i) ?: continue
                    if (rel.optBoolean("draft", false)) continue
                    val tag = rel.optString("tag_name").trim()
                    if (tag.isEmpty()) continue
                    val assets = rel.optJSONArray("assets")
                    var apkUrl = ""
                    var apkName = "GlassOS.apk"
                    if (assets != null) {
                        for (j in 0 until assets.length()) {
                            val a = assets.getJSONObject(j)
                            val name = a.optString("name")
                            val url = a.optString("browser_download_url")
                            if (name.endsWith(".apk", ignoreCase = true) && url.startsWith("https://github.com/")) {
                                apkUrl = url
                                apkName = name
                                break
                            }
                        }
                    }
                    if (apkUrl.isNotEmpty() && isNewerVersion(tag, BuildConfig.VERSION_NAME)) {
                        val cleanVer = tag.removePrefix("v").removePrefix("V")
                        found = UpdateInfo(
                            tagName = tag,
                            versionName = cleanVer,
                            title = rel.optString("name").ifBlank { "GlassOS $tag" },
                            notes = rel.optString("body").ifBlank { "Updated build from github.com/$REPO" },
                            apkUrl = apkUrl,
                            apkName = apkName
                        )
                        break
                    }
                }
                latestUpdate = found
                ui.post { onResult(found, null) }
            } catch (e: Throwable) {
                ui.post { onResult(null, e.message ?: "Network error") }
            }
        }.apply { priority = Thread.MIN_PRIORITY }.start()
    }

    /** Compares semantic version segments (e.g. "v1.2.0" > "1.1.0"). */
    fun isNewerVersion(remoteTag: String, localVersion: String): Boolean {
        val rParts = parseVersionParts(remoteTag)
        val lParts = parseVersionParts(localVersion)
        val maxLen = maxOf(rParts.size, lParts.size)
        for (i in 0 until maxLen) {
            val r = rParts.getOrElse(i) { 0 }
            val l = lParts.getOrElse(i) { 0 }
            if (r != l) return r > l
        }
        return false
    }

    private fun parseVersionParts(v: String): List<Int> {
        val cleaned = v.trim().removePrefix("v").removePrefix("V").substringBefore("-")
        return cleaned.split(".")
            .mapNotNull { it.filter { ch -> ch.isDigit() }.toIntOrNull() }
            .ifEmpty { listOf(0) }
    }

    /** Downloads the APK from GitHub Releases and triggers the Android Package Installer. */
    fun downloadAndInstall(
        ctx: Context,
        info: UpdateInfo,
        onProgress: (Int) -> Unit,
        onDone: () -> Unit,
        onError: (String) -> Unit
    ) {
        val ui = Handler(Looper.getMainLooper())
        Thread {
            try {
                val dest = apkFile(ctx)
                val tmp = File(dest.path + ".part")
                var currentUrl = info.apkUrl
                var redirects = 0
                var conn: HttpURLConnection? = null
                while (redirects < 6) {
                    val c = (URL(currentUrl).openConnection() as HttpURLConnection).apply {
                        instanceFollowRedirects = true
                        connectTimeout = 15_000
                        readTimeout = 30_000
                        setRequestProperty("User-Agent", "GlassOS-TV/${BuildConfig.VERSION_NAME}")
                    }
                    val status = c.responseCode
                    if (status in 301..308) {
                        val loc = c.getHeaderField("Location")
                        c.disconnect()
                        if (loc.isNullOrBlank()) break
                        currentUrl = loc
                        redirects++
                    } else {
                        conn = c
                        break
                    }
                }
                val activeConn = conn ?: throw IllegalStateException("Too many redirects")
                try {
                    if (activeConn.responseCode != 200) {
                        throw IllegalStateException("Download failed (HTTP ${activeConn.responseCode})")
                    }
                    val total = activeConn.contentLength
                    activeConn.inputStream.use { input ->
                        FileOutputStream(tmp).use { output ->
                            val buf = ByteArray(16 * 1024)
                            var downloaded = 0L
                            var lastPct = -1
                            while (true) {
                                val read = input.read(buf)
                                if (read <= 0) break
                                output.write(buf, 0, read)
                                downloaded += read
                                if (total > 0) {
                                    val pct = ((downloaded * 100L) / total).toInt().coerceIn(0, 100)
                                    if (pct != lastPct) {
                                        lastPct = pct
                                        ui.post { onProgress(pct) }
                                    }
                                }
                            }
                        }
                    }
                } finally {
                    activeConn.disconnect()
                }
                if (dest.exists()) dest.delete()
                tmp.renameTo(dest)
                ui.post {
                    onDone()
                    installApk(ctx, dest)
                }
            } catch (e: Throwable) {
                ui.post { onError(e.message ?: "Download failed") }
            }
        }.start()
    }

    private fun installApk(ctx: Context, file: File) {
        val uri = if (Build.VERSION.SDK_INT >= 24) {
            Uri.parse("content://${ctx.packageName}.updateprovider/${file.name}")
        } else {
            Uri.fromFile(file)
        }
        val intent = Intent(Intent.ACTION_VIEW).apply {
            setDataAndType(uri, "application/vnd.android.package-archive")
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        ctx.startActivity(intent)
    }
}

/** Serves the downloaded update APK to the system package installer on Android 7.0+ without AndroidX. */
class UpdateApkProvider : ContentProvider() {
    override fun onCreate(): Boolean = true

    override fun getType(uri: Uri): String = "application/vnd.android.package-archive"

    override fun query(
        uri: Uri,
        projection: Array<out String>?,
        selection: String?,
        selectionArgs: Array<out String>?,
        sortOrder: String?
    ): Cursor {
        val f = AppUpdater.apkFile(context!!)
        val cols = projection ?: arrayOf(OpenableColumns.DISPLAY_NAME, OpenableColumns.SIZE)
        val cursor = MatrixCursor(cols, 1)
        val row = cols.map { col ->
            when (col) {
                OpenableColumns.DISPLAY_NAME -> f.name
                OpenableColumns.SIZE -> f.length()
                else -> null
            }
        }.toTypedArray()
        cursor.addRow(row)
        return cursor
    }

    override fun openFile(uri: Uri, mode: String): ParcelFileDescriptor {
        val f = AppUpdater.apkFile(context!!)
        return ParcelFileDescriptor.open(f, ParcelFileDescriptor.MODE_READ_ONLY)
    }

    override fun insert(uri: Uri, values: ContentValues?): Uri? = null
    override fun delete(uri: Uri, selection: String?, selectionArgs: Array<out String>?): Int = 0
    override fun update(uri: Uri, values: ContentValues?, selection: String?, selectionArgs: Array<out String>?): Int = 0
}
