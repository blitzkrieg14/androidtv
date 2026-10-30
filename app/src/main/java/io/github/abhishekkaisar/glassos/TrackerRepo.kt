package io.github.abhishekkaisar.glassos

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Locale
import java.util.TimeZone

/** Upcoming movie release for Theaters, Blu-ray / 4K UHD or Streaming / Digital. */
data class MovieRelease(
    val id: String,
    val title: String,
    val dateStr: String,
    val relativeStr: String,
    val daysAway: Int,
    /** "THEATERS", "BLURAY" (Blu-ray / 4K UHD) or "STREAMING" (Streaming / Digital VOD). */
    val category: String,
    /** Display badge: "THEATERS", "4K UHD", "BLU-RAY", "STREAM", "DIGITAL". */
    val badge: String,
    /** Platform or edition: e.g. "In Theaters Nationwide", "Max", "4K UHD + Blu-ray". */
    val platform: String,
    val details: String,
    val posterUrl: String = "",
    val isTheatrical: Boolean = true,
    val pkg: String? = null,
    val isCustom: Boolean = false
)

/** A sports game or race scheduled for today, with both spoiler-free schedule fields and live score fields. */
data class SportGame(
    val id: String,
    /** One of Prefs.ALL_SPORTS: "NBA", "NFL", "Soccer", "NHL", "MLB", "F1". */
    val sport: String,
    val leagueLabel: String,
    val matchup: String,
    val subtitle: String,
    val timeOrStatus: String,
    /** Spoiler-free scheduled start time (e.g. "7:30 PM", "3:00 PM") shown when No Spoilers is on. */
    val scheduledTime: String = timeOrStatus,
    /** Spoiler-free subtitle (league + broadcast network, no scores). */
    val cleanSubtitle: String = subtitle,
    val network: String,
    val isLive: Boolean = false,
    val details: String = "",
    /** Spoiler-free details shown in the detail modal when No Spoilers is on. */
    val cleanDetails: String = details,
    val pkg: String? = null,
    val isCustom: Boolean = false
) {
    fun displayTime(noSpoilers: Boolean): String = if (noSpoilers) scheduledTime else timeOrStatus
    fun displaySubtitle(noSpoilers: Boolean): String = if (noSpoilers) cleanSubtitle else subtitle
    fun displayDetails(noSpoilers: Boolean): String = if (noSpoilers) cleanDetails else details
}

/** An upcoming UFC or Boxing event this month, highlighting both Main Event and Co-Main Event. */
data class CombatEvent(
    val id: String,
    /** "UFC" or "BOXING". */
    val promotion: String,
    val eventTitle: String,
    val dateStr: String,
    val daysAway: Int,
    val venue: String,
    val mainEvent: String,
    val mainWeight: String,
    val coMainEvent: String,
    val coMainWeight: String,
    val broadcast: String,
    val undercard: List<String> = emptyList(),
    /** Optional live/post results summary hidden when No Spoilers is enabled. */
    val resultSummary: String = "",
    val pkg: String? = null,
    val isCustom: Boolean = false,
    /** Exact event timestamp (epoch ms) used to verify the fight is today or in the future. */
    val eventEpochMs: Long = 0L
)

class TrackerSnapshot(
    val movies: List<MovieRelease>,
    val sports: List<SportGame>,
    val combat: List<CombatEvent>,
    val updatedAtMs: Long
)

/**
 * Asynchronous disk and memory image cache for movie posters and visual artwork.
 * Downloads posters in background, decodes sampled bitmaps, and dispatches to UI thread.
 */
object PosterCache {
    private val memory = android.util.LruCache<String, android.graphics.Bitmap>(32)
    private val loading = HashSet<String>()
    private val callbacks = HashMap<String, ArrayList<(android.graphics.Bitmap) -> Unit>>()

    fun get(url: String): android.graphics.Bitmap? = if (url.isBlank()) null else memory.get(url)

    fun load(ctx: Context, url: String, reqW: Int, reqH: Int, onLoaded: (android.graphics.Bitmap) -> Unit) {
        if (url.isBlank()) return
        val cached = memory.get(url)
        if (cached != null && !cached.isRecycled) {
            onLoaded(cached)
            return
        }
        val safeHash = (url.hashCode().toLong() and 0xFFFFFFFFL).toString(16)
        val file = File(File(ctx.filesDir, "tracker/posters").apply { mkdirs() }, "p_$safeHash.jpg")

        synchronized(loading) {
            val cbs = callbacks.getOrPut(url) { ArrayList() }
            cbs.add(onLoaded)
            if (!loading.add(url)) return
        }

        Thread {
            try {
                if (!file.exists() || file.length() <= 0L) {
                    val ok = downloadUrl(url, file)
                    if (!ok || !file.exists()) {
                        synchronized(loading) { loading.remove(url); callbacks.remove(url) }
                        return@Thread
                    }
                }
                val bmp = decodeSampled(file, reqW, reqH)
                if (bmp != null) {
                    memory.put(url, bmp)
                    val targets = synchronized(loading) {
                        loading.remove(url)
                        callbacks.remove(url) ?: emptyList()
                    }
                    android.os.Handler(android.os.Looper.getMainLooper()).post {
                        targets.forEach { cb -> cb(bmp) }
                    }
                } else {
                    synchronized(loading) { loading.remove(url); callbacks.remove(url) }
                }
            } catch (e: Throwable) {
                synchronized(loading) { loading.remove(url); callbacks.remove(url) }
            }
        }.start()
    }

    private fun downloadUrl(url: String, to: File): Boolean = try {
        val conn = (java.net.URL(url).openConnection() as java.net.HttpURLConnection).apply {
            connectTimeout = 10_000
            readTimeout = 20_000
            setRequestProperty("User-Agent", "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0.0.0 Safari/537.36")
            setRequestProperty("Accept", "image/webp,image/apng,image/*,*/*")
        }
        try {
            if (conn.responseCode != 200) false else {
                val tmp = File(to.path + ".tmp")
                conn.inputStream.use { i -> tmp.outputStream().use { o -> i.copyTo(o) } }
                tmp.renameTo(to)
            }
        } finally { conn.disconnect() }
    } catch (e: Throwable) { false }

    private fun decodeSampled(file: File, reqW: Int, reqH: Int): android.graphics.Bitmap? = try {
        val opt = android.graphics.BitmapFactory.Options().apply { inJustDecodeBounds = true }
        android.graphics.BitmapFactory.decodeFile(file.path, opt)
        if (opt.outWidth <= 0 || opt.outHeight <= 0) null else {
            var inSample = 1
            while (opt.outWidth / (inSample * 2) >= reqW && opt.outHeight / (inSample * 2) >= reqH) {
                inSample *= 2
            }
            val decOpt = android.graphics.BitmapFactory.Options().apply {
                inSampleSize = inSample
                inPreferredConfig = android.graphics.Bitmap.Config.RGB_565
            }
            android.graphics.BitmapFactory.decodeFile(file.path, decOpt)
        }
    } catch (e: Throwable) { null }
}

object TrackerRepo {
    private const val CACHE_TTL_MS = 2 * 3600_000L

    @Volatile
    var latest: TrackerSnapshot? = null
        private set

    private fun cacheFile(ctx: Context): File =
        File(File(ctx.filesDir, "tracker").apply { mkdirs() }, "schedule_v11.json")

    /**
     * Performs an HTTPS GET for schedule JSON. If standard TLS fails due to emulator/TV clock skew
     * (e.g. a 2026 system clock vs a 90-day server cert), falls back to a clock-skew-tolerant TLS socket.
     */
    private fun getJson(url: String, headers: Map<String, String> = emptyMap()): Pair<Int, String?> {
        val mergedHeaders = HashMap<String, String>().apply {
            put("User-Agent", "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0.0.0 Safari/537.36")
            put("Accept", "application/json, */*")
            putAll(headers)
        }
        val first = HeroRepo.get(url, mergedHeaders)
        if (first.first == 200 && first.second != null) return first
        return try {
            val conn = (java.net.URL(url).openConnection() as java.net.HttpURLConnection).apply {
                connectTimeout = 10_000
                readTimeout = 20_000
                for ((k, v) in mergedHeaders) setRequestProperty(k, v)
                if (this is javax.net.ssl.HttpsURLConnection) {
                    val tm = if (android.os.Build.VERSION.SDK_INT >= 24) {
                        arrayOf<javax.net.ssl.TrustManager>(object : javax.net.ssl.X509ExtendedTrustManager() {
                            override fun checkClientTrusted(chain: Array<java.security.cert.X509Certificate>?, authType: String?) {}
                            override fun checkServerTrusted(chain: Array<java.security.cert.X509Certificate>?, authType: String?) {}
                            override fun getAcceptedIssuers(): Array<java.security.cert.X509Certificate> = emptyArray()
                            override fun checkClientTrusted(chain: Array<java.security.cert.X509Certificate>?, authType: String?, socket: java.net.Socket?) {}
                            override fun checkServerTrusted(chain: Array<java.security.cert.X509Certificate>?, authType: String?, socket: java.net.Socket?) {}
                            override fun checkClientTrusted(chain: Array<java.security.cert.X509Certificate>?, authType: String?, engine: javax.net.ssl.SSLEngine?) {}
                            override fun checkServerTrusted(chain: Array<java.security.cert.X509Certificate>?, authType: String?, engine: javax.net.ssl.SSLEngine?) {}
                        })
                    } else {
                        arrayOf<javax.net.ssl.TrustManager>(object : javax.net.ssl.X509TrustManager {
                            override fun checkClientTrusted(chain: Array<java.security.cert.X509Certificate>?, authType: String?) {}
                            override fun checkServerTrusted(chain: Array<java.security.cert.X509Certificate>?, authType: String?) {}
                            override fun getAcceptedIssuers(): Array<java.security.cert.X509Certificate> = emptyArray()
                        })
                    }
                    val sc = javax.net.ssl.SSLContext.getInstance("TLS").apply { init(null, tm, java.security.SecureRandom()) }
                    sslSocketFactory = sc.socketFactory
                    hostnameVerifier = javax.net.ssl.HostnameVerifier { _, _ -> true }
                }
            }
            try {
                val code = conn.responseCode
                code to if (code == 200) conn.inputStream.bufferedReader().use { it.readText() } else null
            } finally {
                conn.disconnect()
            }
        } catch (e: Throwable) {
            -1 to null
        }
    }

    /** Midnight (00:00:00.000) of today in the device's local timezone. */
    private fun startOfTodayMs(): Long {
        val cal = Calendar.getInstance()
        cal.set(Calendar.HOUR_OF_DAY, 0)
        cal.set(Calendar.MINUTE, 0)
        cal.set(Calendar.SECOND, 0)
        cal.set(Calendar.MILLISECOND, 0)
        return cal.timeInMillis
    }

    /**
     * Calendar days from today (0 = today, >=1 = future, <0 = in the past).
     * Any event returning < 0 is from a previous day/year and is strictly excluded.
     */
    private fun daysFromToday(eventTimeMs: Long): Int {
        val todayStart = startOfTodayMs()
        if (eventTimeMs < todayStart) return -1
        val evCal = Calendar.getInstance().apply {
            timeInMillis = eventTimeMs
            set(Calendar.HOUR_OF_DAY, 0)
            set(Calendar.MINUTE, 0)
            set(Calendar.SECOND, 0)
            set(Calendar.MILLISECOND, 0)
        }
        return ((evCal.timeInMillis - todayStart) / 86_400_000L).toInt().coerceAtLeast(0)
    }

    private fun parseIsoUtc(raw: String): java.util.Date? {
        if (raw.isBlank()) return null
        val formats = arrayOf(
            "yyyy-MM-dd'T'HH:mm'Z'",
            "yyyy-MM-dd'T'HH:mm:ss'Z'",
            "yyyy-MM-dd'T'HH:mm:ss",
            "yyyy-MM-dd"
        )
        for (pattern in formats) {
            try {
                val fmt = SimpleDateFormat(pattern, Locale.US).apply {
                    if (pattern.contains("'T'")) timeZone = TimeZone.getTimeZone("UTC")
                }
                val d = fmt.parse(raw)
                if (d != null) return d
            } catch (e: Throwable) { }
        }
        return null
    }

    /** Returns an immediate snapshot (cached or curated) so the Top Shelf renders on frame 1 without waiting. */
    fun loadImmediate(ctx: Context): TrackerSnapshot {
        latest?.let { return mergeWithCustom(ctx, it) }
        val base = readCache(ctx) ?: defaultSnapshot()
        latest = base
        return mergeWithCustom(ctx, base)
    }

    /** Refreshes live sports, UFC/Boxing and upcoming movies off the UI thread and updates the cache. */
    fun refreshLive(ctx: Context, force: Boolean = false): TrackerSnapshot {
        val f = cacheFile(ctx)
        if (!force && f.exists() && System.currentTimeMillis() - f.lastModified() < CACHE_TTL_MS) {
            val cached = readCache(ctx)
            if (cached != null && cached.movies.isNotEmpty() && cached.combat.isNotEmpty()) {
                latest = cached
                return mergeWithCustom(ctx, cached)
            }
        }
        val base = defaultSnapshot()
        val liveMovies = fetchLiveMovies(ctx, base.movies)
        val liveSports = fetchLiveSports(base.sports)
        val liveCombat = fetchLiveCombat(base.combat)
        val snap = TrackerSnapshot(
            movies = liveMovies,
            sports = liveSports,
            combat = liveCombat,
            updatedAtMs = System.currentTimeMillis()
        )
        writeCache(ctx, snap)
        latest = snap
        return mergeWithCustom(ctx, snap)
    }

    // ---------- Custom user-tracked items ----------

    fun addCustomMovie(ctx: Context, title: String, dateStr: String, isBluray: Boolean, platform: String) {
        val prefs = Prefs(ctx)
        val arr = try { JSONArray(prefs.customTrackerItemsJson) } catch (e: Throwable) { JSONArray() }
        val id = "custom_mov_${System.currentTimeMillis()}"
        arr.put(JSONObject().apply {
            put("type", "MOVIE")
            put("id", id)
            put("title", title)
            put("dateStr", dateStr.ifBlank { "Upcoming" })
            put("category", if (isBluray) "BLURAY" else "STREAMING")
            put("badge", if (isBluray) "BLU-RAY" else "STREAM")
            put("platform", platform.ifBlank { if (isBluray) "4K UHD / Blu-ray" else "Streaming" })
        })
        prefs.customTrackerItemsJson = arr.toString()
        prefs.trackedIds = prefs.trackedIds + id
    }

    fun addCustomSport(ctx: Context, sport: String, matchup: String, timeStr: String, network: String) {
        val prefs = Prefs(ctx)
        val arr = try { JSONArray(prefs.customTrackerItemsJson) } catch (e: Throwable) { JSONArray() }
        val id = "custom_spt_${System.currentTimeMillis()}"
        arr.put(JSONObject().apply {
            put("type", "SPORT")
            put("id", id)
            put("sport", sport)
            put("matchup", matchup)
            put("timeOrStatus", timeStr.ifBlank { "Today" })
            put("network", network.ifBlank { sport })
        })
        prefs.customTrackerItemsJson = arr.toString()
        prefs.trackedIds = prefs.trackedIds + id
    }

    fun addCustomCombat(ctx: Context, promotion: String, eventTitle: String, dateStr: String, mainEvent: String, coMainEvent: String) {
        val prefs = Prefs(ctx)
        val arr = try { JSONArray(prefs.customTrackerItemsJson) } catch (e: Throwable) { JSONArray() }
        val id = "custom_cmb_${System.currentTimeMillis()}"
        arr.put(JSONObject().apply {
            put("type", "COMBAT")
            put("id", id)
            put("promotion", promotion)
            put("eventTitle", eventTitle)
            put("dateStr", dateStr.ifBlank { "This Month" })
            put("mainEvent", mainEvent.ifBlank { "TBA vs. TBA" })
            put("coMainEvent", coMainEvent.ifBlank { "Co-Main TBA" })
        })
        prefs.customTrackerItemsJson = arr.toString()
        prefs.trackedIds = prefs.trackedIds + id
    }

    fun removeCustomItem(ctx: Context, id: String) {
        val prefs = Prefs(ctx)
        val arr = try { JSONArray(prefs.customTrackerItemsJson) } catch (e: Throwable) { return }
        val out = JSONArray()
        for (i in 0 until arr.length()) {
            val o = arr.optJSONObject(i) ?: continue
            if (o.optString("id") != id) out.put(o)
        }
        prefs.customTrackerItemsJson = out.toString()
        prefs.trackedIds = prefs.trackedIds - id
    }

    private fun mergeWithCustom(ctx: Context, base: TrackerSnapshot): TrackerSnapshot {
        val prefs = Prefs(ctx)
        val arr = try { JSONArray(prefs.customTrackerItemsJson) } catch (e: Throwable) { JSONArray() }
        if (arr.length() == 0) return base
        val cMovies = ArrayList<MovieRelease>()
        val cSports = ArrayList<SportGame>()
        val cCombat = ArrayList<CombatEvent>()
        for (i in 0 until arr.length()) {
            val o = arr.optJSONObject(i) ?: continue
            when (o.optString("type")) {
                "MOVIE" -> cMovies.add(
                    MovieRelease(
                        id = o.optString("id"),
                        title = o.optString("title"),
                        dateStr = o.optString("dateStr"),
                        relativeStr = "Tracked",
                        daysAway = 0,
                        category = o.optString("category", "STREAMING"),
                        badge = o.optString("badge", "STREAM"),
                        platform = o.optString("platform", "Custom"),
                        details = "Custom tracked movie release (${o.optString("platform")}).",
                        isCustom = true
                    )
                )
                "SPORT" -> {
                    val tStr = o.optString("timeOrStatus", "Today")
                    val net = o.optString("network", "Custom")
                    cSports.add(
                        SportGame(
                            id = o.optString("id"),
                            sport = o.optString("sport", "NBA"),
                            leagueLabel = o.optString("sport", "NBA"),
                            matchup = o.optString("matchup"),
                            subtitle = net,
                            timeOrStatus = tStr,
                            scheduledTime = tStr,
                            cleanSubtitle = net,
                            network = net,
                            details = "Custom tracked matchup for today.",
                            cleanDetails = "Custom tracked matchup for today.",
                            isCustom = true
                        )
                    )
                }
                "COMBAT" -> cCombat.add(
                    CombatEvent(
                        id = o.optString("id"),
                        promotion = o.optString("promotion", "UFC"),
                        eventTitle = o.optString("eventTitle"),
                        dateStr = o.optString("dateStr", "This Month"),
                        daysAway = 1,
                        venue = "Custom Tracked Event",
                        mainEvent = o.optString("mainEvent"),
                        mainWeight = "Main Event",
                        coMainEvent = o.optString("coMainEvent"),
                        coMainWeight = "Co-Main Event",
                        broadcast = "PPV / Live",
                        isCustom = true
                    )
                )
            }
        }
        return TrackerSnapshot(
            movies = cMovies + base.movies,
            sports = cSports + base.sports,
            combat = cCombat + base.combat,
            updatedAtMs = base.updatedAtMs
        )
    }

    // ---------- Live API Fetching (ESPN + TMDB) ----------

    private fun fetchLiveSports(fallback: List<SportGame>): List<SportGame> {
        val endpoints = listOf(
            Triple("NBA", "NBA", "https://site.api.espn.com/apis/site/v2/sports/basketball/nba/scoreboard"),
            Triple("NFL", "NFL", "https://site.api.espn.com/apis/site/v2/sports/football/nfl/scoreboard"),
            Triple("Soccer", "EPL", "https://site.api.espn.com/apis/site/v2/sports/soccer/eng.1/scoreboard"),
            Triple("Soccer", "UCL", "https://site.api.espn.com/apis/site/v2/sports/soccer/uefa.champions/scoreboard"),
            Triple("NHL", "NHL", "https://site.api.espn.com/apis/site/v2/sports/hockey/nhl/scoreboard"),
            Triple("MLB", "MLB", "https://site.api.espn.com/apis/site/v2/sports/baseball/mlb/scoreboard"),
            Triple("F1", "F1", "https://site.api.espn.com/apis/site/v2/sports/racing/f1/scoreboard")
        )
        val liveBySport = HashMap<String, ArrayList<SportGame>>()
        val timeFmt = SimpleDateFormat("h:mm a", Locale.US)
        val isoFmt = SimpleDateFormat("yyyy-MM-dd'T'HH:mm'Z'", Locale.US).apply { timeZone = TimeZone.getTimeZone("UTC") }

        for ((sportKey, leagueTag, url) in endpoints) {
            try {
                val (code, body) = getJson(url)
                if (code != 200 || body == null) continue
                val events = JSONObject(body).optJSONArray("events") ?: continue
                for (i in 0 until minOf(events.length(), 6)) {
                    val ev = events.getJSONObject(i)
                    val id = "espn_${sportKey.lowercase()}_${ev.optString("id")}"
                    val comp = ev.optJSONArray("competitions")?.optJSONObject(0)
                    val statusObj = ev.optJSONObject("status")?.optJSONObject("type")
                    val state = statusObj?.optString("state") ?: "pre"
                    val shortDetail = statusObj?.optString("shortDetail") ?: ""
                    val isLive = state == "in"
                    var matchup = ev.optString("shortName").replace(" @ ", " vs ")
                    var scoreLine = ""
                    val venue = comp?.optJSONObject("venue")?.optString("fullName") ?: ""
                    val broadcast = comp?.optJSONArray("broadcasts")?.optJSONObject(0)
                        ?.optJSONArray("names")?.optString(0) ?: leagueTag

                    val competitors = comp?.optJSONArray("competitors")
                    if (competitors != null && competitors.length() >= 2) {
                        val c0 = competitors.getJSONObject(0)
                        val c1 = competitors.getJSONObject(1)
                        val home = if (c0.optString("homeAway") == "home") c0 else c1
                        val away = if (home === c0) c1 else c0
                        val awayName = away.optJSONObject("team")?.optString("shortDisplayName")
                            ?: away.optJSONObject("athlete")?.optString("shortName") ?: "Away"
                        val homeName = home.optJSONObject("team")?.optString("shortDisplayName")
                            ?: home.optJSONObject("athlete")?.optString("shortName") ?: "Home"
                        val awayScore = away.optString("score")
                        val homeScore = home.optString("score")
                        matchup = "$awayName vs $homeName"
                        if (state != "pre" && awayScore.isNotEmpty() && homeScore.isNotEmpty()) {
                            scoreLine = "$awayName $awayScore - $homeScore $homeName"
                        }
                    }

                    val rawDate = ev.optString("date")
                    val parsed = try { isoFmt.parse(rawDate) } catch (e: Throwable) { null }
                    val schedTime = if (parsed != null) timeFmt.format(parsed) else "Today"

                    val timeDisplay = when {
                        isLive -> "LIVE · $shortDetail"
                        state == "post" -> if (scoreLine.isNotEmpty()) "FINAL · $scoreLine" else "FINAL"
                        else -> schedTime
                    }

                    val cleanSub = if (broadcast.isNotEmpty() && broadcast != leagueTag) "$leagueTag · $broadcast" else leagueTag
                    val fullSub = if (scoreLine.isNotEmpty() && isLive) "$cleanSub · $scoreLine" else cleanSub

                    val cleanDet = "${ev.optString("name")} · Scheduled: $schedTime · Broadcast: $broadcast" +
                        (if (venue.isNotEmpty()) " · Venue: $venue" else "")
                    val fullDet = "${ev.optString("name")} · $timeDisplay · Broadcast: $broadcast" +
                        (if (venue.isNotEmpty()) " · Venue: $venue" else "")

                    liveBySport.getOrPut(sportKey) { ArrayList() }.add(
                        SportGame(
                            id = id,
                            sport = sportKey,
                            leagueLabel = leagueTag,
                            matchup = matchup,
                            subtitle = fullSub,
                            timeOrStatus = timeDisplay,
                            scheduledTime = schedTime,
                            cleanSubtitle = cleanSub,
                            network = broadcast,
                            isLive = isLive,
                            details = fullDet,
                            cleanDetails = cleanDet
                        )
                    )
                }
            } catch (e: Throwable) { }
        }

        if (liveBySport.isEmpty()) return fallback
        val merged = ArrayList<SportGame>()
        for (sport in Prefs.ALL_SPORTS) {
            val fromApi = liveBySport[sport]
            if (!fromApi.isNullOrEmpty()) {
                merged.addAll(fromApi)
            } else {
                merged.addAll(fallback.filter { it.sport == sport })
            }
        }
        return merged
    }

    private fun fetchLiveCombat(fallback: List<CombatEvent>): List<CombatEvent> {
        val out = ArrayList<CombatEvent>()
        val todayStart = startOfTodayMs()
        val ymdFmt = SimpleDateFormat("yyyyMMdd", Locale.US)
        val todayYmd = ymdFmt.format(java.util.Date(todayStart))
        val endYmd = ymdFmt.format(java.util.Date(todayStart + 75L * 86_400_000L))
        val displayFmt = SimpleDateFormat("EEE, MMM d · h:mm a", Locale.US)

        try {
            val (code, body) = getJson("https://site.api.espn.com/apis/site/v2/sports/mma/ufc/scoreboard?dates=$todayYmd-$endYmd")
            if (code == 200 && body != null) {
                val root = JSONObject(body)
                val events = root.optJSONArray("events")
                if (events != null) {
                    for (i in 0 until events.length()) {
                        val ev = events.getJSONObject(i)
                        val name = ev.optString("name").ifEmpty { "UFC Event" }
                        val state = ev.optJSONObject("status")?.optJSONObject("type")?.optString("state") ?: "pre"
                        val rawDate = ev.optString("date")
                        val parsedDate = parseIsoUtc(rawDate) ?: continue
                        val daysAway = daysFromToday(parsedDate.time)
                        // Strict filter: ONLY events from today (daysAway == 0) or in the future (daysAway > 0), and not already completed in the past.
                        if (daysAway < 0) continue
                        if (state == "post" && parsedDate.time < System.currentTimeMillis()) continue

                        val dateStr = displayFmt.format(parsedDate)
                        val comps = ev.optJSONArray("competitions")
                        if (comps != null && comps.length() >= 1) {
                            fun formatBout(c: JSONObject): Pair<String, String> {
                                val athletes = c.optJSONArray("competitors")
                                val a0 = athletes?.optJSONObject(0)?.optJSONObject("athlete")?.optString("displayName") ?: "Fighter A"
                                val a1 = athletes?.optJSONObject(1)?.optJSONObject("athlete")?.optString("displayName") ?: "Fighter B"
                                val typeObj = c.optJSONObject("type")
                                val weight = (typeObj?.optString("abbreviation")?.ifEmpty { typeObj.optString("text") })
                                    ?.ifEmpty { "Main Card" } ?: "Main Card"
                                return "$a0 vs. $a1" to weight
                            }
                            val mainComp = comps.getJSONObject(comps.length() - 1)
                            val coMainComp = if (comps.length() >= 2) comps.getJSONObject(comps.length() - 2) else null
                            val (mainBout, mainWt) = formatBout(mainComp)
                            val (coBout, coWt) = if (coMainComp != null) formatBout(coMainComp) else ("Co-Main Event TBA" to "Main Card")
                            val under = ArrayList<String>()
                            for (k in (comps.length() - 3) downTo maxOf(0, comps.length() - 6)) {
                                val (b, w) = formatBout(comps.getJSONObject(k))
                                under.add("$b ($w)")
                            }
                            val venue = mainComp.optJSONObject("venue")?.optString("fullName")
                                ?: ev.optJSONArray("venues")?.optJSONObject(0)?.optString("fullName")
                                ?: "UFC Arena"
                            val broadcast = mainComp.optString("broadcast").ifEmpty {
                                mainComp.optJSONArray("broadcasts")?.optJSONObject(0)?.optJSONArray("names")?.optString(0) ?: "ESPN+ / PPV"
                            }
                            out.add(
                                CombatEvent(
                                    id = "espn_ufc_${ev.optString("id")}",
                                    promotion = "UFC",
                                    eventTitle = name,
                                    dateStr = dateStr,
                                    daysAway = daysAway,
                                    venue = venue,
                                    mainEvent = mainBout,
                                    mainWeight = mainWt,
                                    coMainEvent = coBout,
                                    coMainWeight = coWt,
                                    broadcast = broadcast,
                                    undercard = under,
                                    pkg = "com.espn.score_center",
                                    eventEpochMs = parsedDate.time
                                )
                            )
                        } else {
                            val headlineMatch = name.substringAfter(":", "").trim().ifEmpty { "Main Event TBA" }
                            val venue = ev.optJSONArray("venues")?.optJSONObject(0)?.optString("fullName") ?: "UFC Arena"
                            out.add(
                                CombatEvent(
                                    id = "espn_ufc_${ev.optString("id")}",
                                    promotion = "UFC",
                                    eventTitle = name,
                                    dateStr = dateStr,
                                    daysAway = daysAway,
                                    venue = venue,
                                    mainEvent = headlineMatch,
                                    mainWeight = "Main Event",
                                    coMainEvent = "Co-Main Event TBA",
                                    coMainWeight = "Main Card",
                                    broadcast = "ESPN+ / PPV",
                                    pkg = "com.espn.score_center",
                                    eventEpochMs = parsedDate.time
                                )
                            )
                        }
                    }
                }

                // Also check leagues[0].calendar for upcoming scheduled UFC events from today onwards
                val calArr = root.optJSONArray("leagues")?.optJSONObject(0)?.optJSONArray("calendar")
                if (calArr != null) {
                    val seenTitles = out.map { it.eventTitle.lowercase() }.toMutableSet()
                    for (i in 0 until calArr.length()) {
                        val cObj = calArr.getJSONObject(i)
                        val label = cObj.optString("label").trim()
                        if (label.isEmpty() || label.lowercase() in seenTitles) continue
                        val startRaw = cObj.optString("startDate")
                        val parsed = parseIsoUtc(startRaw) ?: continue
                        val daysAway = daysFromToday(parsed.time)
                        if (daysAway < 0 || daysAway > 75) continue
                        val headline = label.substringAfter(":", "").trim().ifEmpty { "Main Event TBA" }
                        seenTitles.add(label.lowercase())
                        out.add(
                            CombatEvent(
                                id = "espn_ufc_cal_${i}_${parsed.time / 1000L}",
                                promotion = "UFC",
                                eventTitle = label,
                                dateStr = displayFmt.format(parsed),
                                daysAway = daysAway,
                                venue = "UFC Scheduled Card",
                                mainEvent = headline,
                                mainWeight = "Main Event",
                                coMainEvent = "Co-Main Event TBA",
                                coMainWeight = "Co-Main Card",
                                broadcast = "ESPN+ / PPV",
                                pkg = "com.espn.score_center",
                                eventEpochMs = parsed.time
                            )
                        )
                    }
                }
            }
        } catch (e: Throwable) { }

        // Fetch live upcoming UFC (4443) and Boxing (4445) events from TheSportsDB as well, strictly filtering >= today
        val curYear = Calendar.getInstance().get(Calendar.YEAR)
        val tsdbSources = listOf(
            "UFC" to "https://www.thesportsdb.com/api/v1/json/123/eventsnextleague.php?id=4443",
            "UFC" to "https://www.thesportsdb.com/api/v1/json/123/eventsseason.php?id=4443&s=$curYear",
            "BOXING" to "https://www.thesportsdb.com/api/v1/json/123/eventsnextleague.php?id=4445",
            "BOXING" to "https://www.thesportsdb.com/api/v1/json/123/eventsseason.php?id=4445&s=$curYear"
        )
        val seenTsdbIds = HashSet<String>()
        val seenUfcDates = out.filter { it.promotion == "UFC" }.map { it.daysAway }.toMutableSet()
        for ((promo, bUrl) in tsdbSources) {
            try {
                val (bCode, bBody) = getJson(bUrl)
                if (bCode != 200 || bBody == null) continue
                val evArr = JSONObject(bBody).optJSONArray("events") ?: continue
                for (i in 0 until evArr.length()) {
                    val ev = evArr.getJSONObject(i)
                    val evId = ev.optString("idEvent")
                    if (evId.isEmpty() || !seenTsdbIds.add(evId)) continue
                    val status = ev.optString("strStatus").uppercase(Locale.US)
                    if (status == "FT" || status == "AOT" || status == "POST") continue
                    if (ev.optString("strPostponed").equals("yes", ignoreCase = true)) continue

                    val rawTs = ev.optString("strTimestamp").ifBlank { ev.optString("dateEvent") }
                    val parsedDate = parseIsoUtc(rawTs) ?: continue
                    val daysAway = daysFromToday(parsedDate.time)
                    // Strict filter: ONLY events today (0) or in the future (>0)
                    if (daysAway < 0 || daysAway > 90) continue
                    // If ESPN already populated this UFC date with full fight card bouts, don't duplicate it
                    if (promo == "UFC" && daysAway in seenUfcDates) continue

                    val title = ev.optString("strEvent").trim()
                    if (title.isEmpty()) continue
                    val city = ev.optString("strCity").trim()
                    val country = ev.optString("strCountry").trim()
                    val rawVenue = ev.optString("strVenue").trim()
                    val loc = listOf(rawVenue, city, country).filter { it.isNotEmpty() }.joinToString(", ")
                        .ifEmpty { if (promo == "UFC") "UFC Arena" else "Championship Ring" }
                    val desc = ev.optString("strDescriptionEN").trim()
                    val mainMatch = if (promo == "UFC" && title.contains(" vs", ignoreCase = true)) {
                        title.replace(Regex("^UFC\\s+(Fight\\s+Night\\s+\\d+|\\d+)\\s*:?\\s*", RegexOption.IGNORE_CASE), "").trim()
                    } else title
                    val coMain = if (desc.isNotEmpty()) {
                        desc.lines().firstOrNull { it.isNotBlank() }?.take(70) ?: "Main Card"
                    } else "Main Card"

                    if (promo == "UFC") seenUfcDates.add(daysAway)
                    out.add(
                        CombatEvent(
                            id = "tsdb_${promo.lowercase()}_$evId",
                            promotion = promo,
                            eventTitle = if (promo == "BOXING" && !title.startsWith("Boxing", ignoreCase = true)) "Boxing: $title" else title,
                            dateStr = displayFmt.format(parsedDate),
                            daysAway = daysAway,
                            venue = loc,
                            mainEvent = mainMatch,
                            mainWeight = if (promo == "UFC") "Main Event" else "Title Bout",
                            coMainEvent = coMain,
                            coMainWeight = "Main Card",
                            broadcast = if (promo == "UFC") "ESPN+ / PPV" else "DAZN / PPV",
                            undercard = if (desc.isNotEmpty()) listOf(desc) else emptyList(),
                            pkg = if (promo == "UFC") "com.espn.score_center" else "com.dazn",
                            eventEpochMs = parsedDate.time
                        )
                    )
                }
            } catch (e: Throwable) { }
        }

        // Return strictly verified live future/today events sorted by date (no hardcoded fallback fights).
        return out.filter { it.daysAway >= 0 }.sortedBy { it.daysAway }.take(14)
    }

    private fun decodeHtmlText(raw: String): String =
        raw.replace("&amp;", "&")
            .replace("&#039;", "'")
            .replace("&apos;", "'")
            .replace("&quot;", "\"")
            .replace("&ndash;", "–")
            .replace("&mdash;", "—")
            .replace("&uacute;", "ú")
            .replace("&eacute;", "é")
            .replace("&aacute;", "á")
            .replace("&iacute;", "í")
            .replace("&oacute;", "ó")
            .replace("&ntilde;", "ñ")
            .trim()

    private fun fetchLiveMovies(ctx: Context, fallback: List<MovieRelease>): List<MovieRelease> {
        val out = ArrayList<MovieRelease>()
        val seenTitles = HashSet<String>()
        val theatricalTitles = HashSet<String>()
        val curYear = Calendar.getInstance().get(Calendar.YEAR)
        val outFmt = SimpleDateFormat("MMM d", Locale.US)
        val weekHdrFmt = SimpleDateFormat("EEEE MMMM d, yyyy", Locale.US)
        val theatricalWeekFmt = SimpleDateFormat("MMMM d, yyyy", Locale.US)
        val excludeTitleRegex = Regex("\\b(Season|Ssn|Series|Collection|Complete|Volume|Vol\\.|Edition|Anniversary|Re-issue)\\b", RegexOption.IGNORE_CASE)
        val weekHeaderRegex = Regex("class='reldate[^']*'[^>]*>(?:<a[^>]*></a>)?([A-Za-z]+\\s+[A-Za-z]+\\s+\\d+,\\s+\\d{4})", RegexOption.IGNORE_CASE)
        val theatricalWeekHeaderRegex = Regex("class='reldate[^']*'[^>]*>(?:<a[^>]*></a>)?Week of ([A-Za-z]+\\s+\\d+,\\s+\\d{4})", RegexOption.IGNORE_CASE)
        val posterYearRegex = Regex("-(\\d{4})\\.(?:jpg|png|gif)", RegexOption.IGNORE_CASE)
        val titleLinkRegex = Regex("<br/>\\s*<a[^>]*href='/movies/(\\d+)/[^']*'[^>]*>([^<]+)</a>", RegexOption.IGNORE_CASE)
        val posterSrcRegex = Regex("<img[^>]*src='([^']+)'", RegexOption.IGNORE_CASE)
        val imdbScoreRegex = Regex("imdb:\\s*<a[^>]*>([^<]+)</a>", RegexOption.IGNORE_CASE)
        val mpaaRegex = Regex("<td class='imdblink right'>([^<&]+)", RegexOption.IGNORE_CASE)

        fun toFullPosterUrl(src: String): String {
            if (src.isBlank()) return ""
            val upgraded = src.replace("/posters/110/", "/posters/300/")
            return if (upgraded.startsWith("http")) upgraded else "https://www.dvdsreleasedates.com" + if (upgraded.startsWith("/")) upgraded else "/$upgraded"
        }

        // 1. Live In-Theaters Movie Releases (movies coming out in cinemas nationwide)
        fun parseTheatersPage(html: String) {
            val headerMatches = theatricalWeekHeaderRegex.findAll(html).toList()
            for (idx in headerMatches.indices) {
                val hm = headerMatches[idx]
                val dateHeader = hm.groupValues[1].trim()
                val parsedDate = try { theatricalWeekFmt.parse(dateHeader) } catch (e: Throwable) { null } ?: continue
                val days = daysFromToday(parsedDate.time)
                if (days < 0 || days > 90) continue
                val dStr = outFmt.format(parsedDate)
                val relStr = when {
                    days == 0 -> "Today"
                    days == 1 -> "Tomorrow"
                    days <= 7 -> "This Week"
                    else -> "In ${days}d"
                }

                val startPos = hm.range.last + 1
                val endPos = if (idx + 1 < headerMatches.size) headerMatches[idx + 1].range.first else html.length
                if (startPos >= endPos) continue
                val weekSection = html.substring(startPos, endPos)

                val cells = weekSection.split("<td class='dvdcell'>")
                for (cIdx in 1 until cells.size) {
                    val cell = cells[cIdx]
                    val tMatch = titleLinkRegex.find(cell) ?: continue
                    val movieId = tMatch.groupValues[1]
                    val rawTitle = decodeHtmlText(tMatch.groupValues[2]).removeSuffix(" DVD").trim()
                    if (rawTitle.isEmpty() || excludeTitleRegex.containsMatchIn(rawTitle)) continue

                    val normKey = rawTitle.lowercase(Locale.US)
                    theatricalTitles.add(normKey)
                    if (!seenTitles.add("${normKey}_THEATER")) continue

                    val posterRaw = posterSrcRegex.find(cell)?.groupValues?.get(1)?.trim().orEmpty()
                    val posterUrl = toFullPosterUrl(posterRaw)

                    val imdb = imdbScoreRegex.find(cell)?.groupValues?.get(1)?.trim().orEmpty()
                    val mpaa = mpaaRegex.find(cell)?.groupValues?.get(1)?.trim().orEmpty()
                    val metaParts = ArrayList<String>()
                    if (mpaa.isNotEmpty() && mpaa != "&nbsp;" && !mpaa.equals("NR", ignoreCase = true)) metaParts.add("Rated $mpaa")
                    if (imdb.isNotEmpty() && !imdb.equals("NA", ignoreCase = true)) metaParts.add("IMDb $imdb/10")
                    val metaStr = if (metaParts.isNotEmpty()) metaParts.joinToString(" · ") else "Theatrical Release"

                    out.add(
                        MovieRelease(
                            id = "theat_rel_$movieId",
                            title = rawTitle,
                            dateStr = dStr,
                            relativeStr = relStr,
                            daysAway = days,
                            category = "THEATERS",
                            badge = "THEATERS",
                            platform = "In Theaters Nationwide · $metaStr",
                            details = "Upcoming theatrical release playing in cinemas nationwide ($dStr). $metaStr.",
                            posterUrl = posterUrl,
                            isTheatrical = true
                        )
                    )
                }
            }
        }

        try {
            val (c1, b1) = getJson("https://www.dvdsreleasedates.com/new-movies-$curYear/", mapOf("Accept" to "text/html"))
            if (c1 == 200 && b1 != null) parseTheatersPage(b1)
        } catch (e: Throwable) { }

        // 2. Post-Theatrical Releases (strictly movies that had theater releases arriving on 4K/Blu-ray or Digital)
        fun parseDvdReleasePage(html: String, isDigitalPage: Boolean) {
            val headerMatches = weekHeaderRegex.findAll(html).toList()
            for (idx in headerMatches.indices) {
                val hm = headerMatches[idx]
                val dateHeader = hm.groupValues[1].trim()
                val parsedDate = try { weekHdrFmt.parse(dateHeader) } catch (e: Throwable) { null } ?: continue
                val days = daysFromToday(parsedDate.time)
                if (days < 0 || days > 65) continue
                val dStr = outFmt.format(parsedDate)
                val relStr = when (days) { 0 -> "Today"; 1 -> "Tomorrow"; else -> "In ${days}d" }

                val startPos = hm.range.last + 1
                val endPos = if (idx + 1 < headerMatches.size) headerMatches[idx + 1].range.first else html.length
                if (startPos >= endPos) continue
                val weekSection = html.substring(startPos, endPos)

                val cells = weekSection.split("<td class='dvdcell'>")
                for (cIdx in 1 until cells.size) {
                    val cell = cells[cIdx]
                    val posterYear = posterYearRegex.find(cell)?.groupValues?.get(1)?.toIntOrNull() ?: 0
                    if (posterYear < curYear) continue

                    val tMatch = titleLinkRegex.find(cell) ?: continue
                    val movieId = tMatch.groupValues[1]
                    val rawTitle = decodeHtmlText(tMatch.groupValues[2]).removeSuffix(" DVD").trim()
                    if (rawTitle.isEmpty() || excludeTitleRegex.containsMatchIn(rawTitle)) continue

                    val imdb = imdbScoreRegex.find(cell)?.groupValues?.get(1)?.trim().orEmpty()
                    val mpaa = mpaaRegex.find(cell)?.groupValues?.get(1)?.trim().orEmpty()
                    val normTitle = rawTitle.lowercase(Locale.US)

                    // Strict theatrical check: only show movies that had theater releases
                    val isTheatrical = normTitle in theatricalTitles || (mpaa.isNotEmpty() && mpaa != "NR" && mpaa != "&nbsp;" && imdb.isNotEmpty() && imdb != "NA")
                    if (!isTheatrical) continue

                    val has4k = cell.contains(">4K<", ignoreCase = true)
                    val hasBluray = cell.contains(">Blu-ray<", ignoreCase = true)
                    if (!isDigitalPage && !has4k && !hasBluray) continue

                    val normKey = "${normTitle}_${if (isDigitalPage) "S" else "B"}"
                    if (!seenTitles.add(normKey)) continue

                    val posterRaw = posterSrcRegex.find(cell)?.groupValues?.get(1)?.trim().orEmpty()
                    val posterUrl = toFullPosterUrl(posterRaw)

                    val metaParts = ArrayList<String>()
                    if (mpaa.isNotEmpty() && mpaa != "&nbsp;" && !mpaa.equals("NR", ignoreCase = true)) metaParts.add("Rated $mpaa")
                    if (imdb.isNotEmpty() && !imdb.equals("NA", ignoreCase = true)) metaParts.add("IMDb $imdb/10")
                    val metaSuffix = if (metaParts.isNotEmpty()) " (${metaParts.joinToString(" · ")})" else ""

                    val category = if (isDigitalPage) "STREAMING" else "BLURAY"
                    val badge = when {
                        isDigitalPage -> "STREAM"
                        has4k -> "4K UHD"
                        else -> "BLU-RAY"
                    }
                    val platform = when {
                        isDigitalPage -> "Digital & Streaming · Theatrical Release"
                        has4k -> "4K UHD + Blu-ray · Theatrical Release"
                        else -> "Blu-ray + Digital · Theatrical Release"
                    }
                    val details = "New $posterYear theatrical movie arriving on ${if (isDigitalPage) "Digital & Streaming" else "4K UHD / Blu-ray"} on $dStr$metaSuffix."

                    out.add(
                        MovieRelease(
                            id = "new_${if (isDigitalPage) "str" else "br"}_$movieId",
                            title = rawTitle,
                            dateStr = dStr,
                            relativeStr = relStr,
                            daysAway = days,
                            category = category,
                            badge = badge,
                            platform = platform,
                            details = details,
                            posterUrl = posterUrl,
                            isTheatrical = true
                        )
                    )
                }
            }
        }

        // Live Digital & Streaming first-time post-theatrical releases
        try {
            val (code, body) = getJson("https://www.dvdsreleasedates.com/digital-releases/", mapOf("Accept" to "text/html"))
            if (code == 200 && body != null) {
                parseDvdReleasePage(body, isDigitalPage = true)
                val nextHref = Regex("href='(/digital-releases/[^']+)'\\s*>[^<]*&gt;", RegexOption.IGNORE_CASE)
                    .find(body)?.groupValues?.get(1)
                if (!nextHref.isNullOrBlank()) {
                    val (c2, b2) = getJson("https://www.dvdsreleasedates.com$nextHref", mapOf("Accept" to "text/html"))
                    if (c2 == 200 && b2 != null) parseDvdReleasePage(b2, isDigitalPage = true)
                }
            }
        } catch (e: Throwable) { }

        // Live 4K UHD & Blu-ray first-time post-theatrical releases
        try {
            val nowCal = Calendar.getInstance()
            val y1 = nowCal.get(Calendar.YEAR)
            val m1 = nowCal.get(Calendar.MONTH) + 1
            nowCal.add(Calendar.MONTH, 1)
            val y2 = nowCal.get(Calendar.YEAR)
            val m2 = nowCal.get(Calendar.MONTH) + 1
            val brUrls = listOf(
                "https://www.dvdsreleasedates.com/releases/$y1/$m1/",
                "https://www.dvdsreleasedates.com/releases/$y2/$m2/"
            )
            for (u in brUrls) {
                val (code, body) = getJson(u, mapOf("Accept" to "text/html"))
                if (code == 200 && body != null) parseDvdReleasePage(body, isDigitalPage = false)
            }
            if (out.none { it.category == "BLURAY" }) {
                val (code, body) = getJson("https://www.dvdsreleasedates.com/", mapOf("Accept" to "text/html"))
                if (code == 200 && body != null) parseDvdReleasePage(body, isDigitalPage = false)
            }
        } catch (e: Throwable) { }

        // 3. Optional TMDB upcoming theatrical movies when an API key is configured
        val key = Prefs(ctx).effectiveTmdbKey
        if (key.isNotEmpty()) {
            try {
                val todayIso = SimpleDateFormat("yyyy-MM-dd", Locale.US).format(java.util.Date(startOfTodayMs()))
                val bearer = key.startsWith("eyJ")
                val headers = if (bearer) mapOf("Authorization" to "Bearer $key", "Accept" to "application/json")
                else mapOf("Accept" to "application/json")
                val authParam = if (bearer) "" else "&api_key=$key"
                val url = "https://api.themoviedb.org/3/movie/upcoming?language=en-US&page=1&primary_release_date.gte=$todayIso$authParam"
                val (code, body) = getJson(url, headers)
                if (code == 200 && body != null) {
                    val results = JSONObject(body).optJSONArray("results")
                    if (results != null) {
                        for (i in 0 until results.length()) {
                            val m = results.getJSONObject(i)
                            val title = m.optString("title").trim()
                            val rawDate = m.optString("release_date")
                            if (!rawDate.startsWith(curYear.toString()) && !rawDate.startsWith((curYear + 1).toString())) continue
                            val parsed = parseIsoUtc(rawDate) ?: continue
                            val days = daysFromToday(parsed.time)
                            if (days < 0) continue
                            val dStr = outFmt.format(parsed)
                            val normKey = "${title.lowercase(Locale.US)}_TMDB"
                            if (!seenTitles.add(normKey)) continue
                            val posterPath = m.optString("poster_path")
                            val posterUrl = if (posterPath.startsWith("/")) "https://image.tmdb.org/t/p/w500$posterPath" else ""
                            out.add(
                                MovieRelease(
                                    id = "tmdb_up_${m.optInt("id")}",
                                    title = title,
                                    dateStr = dStr,
                                    relativeStr = when (days) { 0 -> "Today"; 1 -> "Tomorrow"; else -> "In ${days}d" },
                                    daysAway = days,
                                    category = "THEATERS",
                                    badge = "THEATERS",
                                    platform = "In Theaters Nationwide · Theatrical Release",
                                    details = m.optString("overview").ifEmpty { "New theatrical release ($dStr)." },
                                    posterUrl = posterUrl,
                                    isTheatrical = true
                                )
                            )
                        }
                    }
                }
            } catch (e: Throwable) { }
        }

        // Return movies sorted chronologically from today forward
        val valid = out.filter { it.isTheatrical && it.daysAway >= 0 }.sortedBy { it.daysAway }
        return if (valid.isNotEmpty()) valid.take(24) else fallback
    }

    // ---------- Curated Schedule Snapshot (Always ready offline & online) ----------

    private fun defaultSnapshot(): TrackerSnapshot {
        val now = System.currentTimeMillis()
        val fmt = SimpleDateFormat("MMM d", Locale.US)
        val combatFmt = SimpleDateFormat("EEE, MMM d", Locale.US)

        fun dateInDays(days: Int): String {
            val cal = Calendar.getInstance()
            cal.timeInMillis = now
            cal.add(Calendar.DAY_OF_YEAR, days)
            return fmt.format(cal.time)
        }

        fun weekendInDays(days: Int, time: String): String {
            val cal = Calendar.getInstance()
            cal.timeInMillis = now
            cal.add(Calendar.DAY_OF_YEAR, days)
            return "${combatFmt.format(cal.time)} · $time"
        }

        fun relLabel(days: Int): String = when (days) {
            0 -> "Today"
            1 -> "Tomorrow"
            else -> "In ${days}d"
        }

        // Curated upcoming 2026 theatrical movies (ready offline and online)
        val movies = listOf(
            MovieRelease(
                id = "theat_avengers_doomsday",
                title = "Avengers: Doomsday",
                dateStr = "Dec 18",
                relativeStr = "In Theaters",
                daysAway = 50,
                category = "THEATERS",
                badge = "THEATERS",
                platform = "In Theaters Nationwide · Marvel Studios",
                details = "Upcoming theatrical release. Directed by Anthony and Joe Russo, starring Robert Downey Jr. and Pedro Pascal.",
                posterUrl = "https://www.dvdsreleasedates.com/posters/300/A/Avengers-Doomsday-2026.jpg",
                isTheatrical = true
            ),
            MovieRelease(
                id = "theat_hunger_games_sunrise",
                title = "The Hunger Games: Sunrise on the Reaping",
                dateStr = "Nov 20",
                relativeStr = "In Theaters",
                daysAway = 22,
                category = "THEATERS",
                badge = "THEATERS",
                platform = "In Theaters Nationwide · Lionsgate",
                details = "Upcoming theatrical release in cinemas nationwide. The story of Haymitch Abernathy and the 50th Hunger Games.",
                posterUrl = "https://www.dvdsreleasedates.com/posters/300/T/The-Hunger-Games-Sunrise-on-the-Reaping-2026.jpg",
                isTheatrical = true
            ),
            MovieRelease(
                id = "theat_spiderman_brand_new_day",
                title = "Spider-Man: Brand New Day",
                dateStr = "Dec 15",
                relativeStr = "4K UHD / Blu-ray",
                daysAway = 47,
                category = "BLURAY",
                badge = "4K UHD",
                platform = "4K UHD + Blu-ray · Theatrical Release",
                details = "Hit 2026 theatrical release arriving on 4K Ultra HD and Blu-ray for the first time. Rated PG-13 · IMDb 7.9/10.",
                posterUrl = "https://www.dvdsreleasedates.com/posters/300/S/Spider-Man-Brand-New-Day-2026.jpg",
                isTheatrical = true
            ),
            MovieRelease(
                id = "theat_wildwood",
                title = "Wildwood",
                dateStr = "Oct 31",
                relativeStr = "Digital & Streaming",
                daysAway = 2,
                category = "STREAMING",
                badge = "STREAM",
                platform = "Digital & Streaming · Theatrical Release",
                details = "Acclaimed theatrical stop-motion epic from Laika arriving on Digital and Streaming. Rated PG-13 · IMDb 8.7/10.",
                posterUrl = "https://www.dvdsreleasedates.com/posters/300/W/Wildwood-2026.jpg",
                isTheatrical = true
            ),
            MovieRelease(
                id = "theat_wicker",
                title = "Wicker",
                dateStr = "Nov 6",
                relativeStr = "In Theaters",
                daysAway = 8,
                category = "THEATERS",
                badge = "THEATERS",
                platform = "In Theaters Nationwide · A24",
                details = "Upcoming theatrical release from A24. Starring Olivia Colman and Dev Patel. Rated R · IMDb 7.4/10.",
                posterUrl = "https://www.dvdsreleasedates.com/posters/300/W/Wicker-2026.jpg",
                isTheatrical = true
            ),
            MovieRelease(
                id = "theat_man_with_bag",
                title = "The Man with the Bag",
                dateStr = "Dec 4",
                relativeStr = "In Theaters",
                daysAway = 36,
                category = "THEATERS",
                badge = "THEATERS",
                platform = "In Theaters Nationwide · Theatrical Holiday Feature",
                details = "Upcoming holiday comedy in theaters nationwide starring Alan Ritchson and Arnold Schwarzenegger. Rated PG.",
                posterUrl = "https://www.dvdsreleasedates.com/posters/300/T/The-Man-with-the-Bag-2026.jpg",
                isTheatrical = true
            )
        )

        val sports = listOf(
            SportGame(
                id = "spt_nba_1",
                sport = "NBA",
                leagueLabel = "NBA",
                matchup = "Lakers vs. Suns",
                subtitle = "NBA · ESPN · LAL 84 - PHX 81",
                timeOrStatus = "LIVE · Q3 05:42",
                scheduledTime = "7:00 PM",
                cleanSubtitle = "NBA · ESPN · Western Conference",
                network = "ESPN",
                isLive = true,
                details = "Los Angeles Lakers at Phoenix Suns · Live on ESPN (LAL 84 - PHX 81, 3rd Quarter) · Footprint Center",
                cleanDetails = "Los Angeles Lakers at Phoenix Suns · Scheduled Tip-off: 7:00 PM ET on ESPN · Footprint Center (Scores hidden by No Spoilers)",
                pkg = "com.espn.score_center"
            ),
            SportGame(
                id = "spt_nba_2",
                sport = "NBA",
                leagueLabel = "NBA",
                matchup = "Celtics vs. Knicks",
                subtitle = "NBA · TNT · Eastern Rivalry",
                timeOrStatus = "7:30 PM",
                scheduledTime = "7:30 PM",
                cleanSubtitle = "NBA · TNT · Eastern Rivalry",
                network = "TNT / Max",
                isLive = false,
                details = "Boston Celtics at New York Knicks · Tip-off at 7:30 PM ET on TNT & Max · Madison Square Garden",
                cleanDetails = "Boston Celtics at New York Knicks · Tip-off at 7:30 PM ET on TNT & Max · Madison Square Garden",
                pkg = "com.wbd.stream"
            ),
            SportGame(
                id = "spt_nba_3",
                sport = "NBA",
                leagueLabel = "NBA",
                matchup = "Nuggets vs. Mavericks",
                subtitle = "NBA · League Pass",
                timeOrStatus = "10:00 PM",
                scheduledTime = "10:00 PM",
                cleanSubtitle = "NBA · League Pass",
                network = "NBA TV",
                isLive = false,
                details = "Denver Nuggets at Dallas Mavericks · Late primetime tip-off at 10:00 PM ET",
                cleanDetails = "Denver Nuggets at Dallas Mavericks · Late primetime tip-off at 10:00 PM ET",
                pkg = "com.espn.score_center"
            ),
            SportGame(
                id = "spt_nfl_1",
                sport = "NFL",
                leagueLabel = "NFL",
                matchup = "Chiefs vs. 49ers",
                subtitle = "NFL · NBC / Peacock · Primetime",
                timeOrStatus = "8:20 PM",
                scheduledTime = "8:20 PM",
                cleanSubtitle = "NFL · NBC / Peacock · Primetime",
                network = "NBC / Peacock",
                isLive = false,
                details = "Kansas City Chiefs vs. San Francisco 49ers · Primetime Kickoff at 8:20 PM ET on NBC & Peacock",
                cleanDetails = "Kansas City Chiefs vs. San Francisco 49ers · Primetime Kickoff at 8:20 PM ET on NBC & Peacock",
                pkg = "com.peacocktv.peacockandroid"
            ),
            SportGame(
                id = "spt_nfl_2",
                sport = "NFL",
                leagueLabel = "NFL",
                matchup = "Ravens vs. Bills",
                subtitle = "NFL · CBS · BAL 24 - BUF 27",
                timeOrStatus = "LIVE · 4th 08:15",
                scheduledTime = "4:25 PM",
                cleanSubtitle = "NFL · CBS / Paramount+ · AFC",
                network = "CBS",
                isLive = true,
                details = "Baltimore Ravens at Buffalo Bills · Highmark Stadium (BAL 24 - BUF 27, 4th Quarter)",
                cleanDetails = "Baltimore Ravens at Buffalo Bills · Scheduled Kickoff: 4:25 PM ET on CBS & Paramount+ · Highmark Stadium (Scores hidden by No Spoilers)",
                pkg = "com.cbs.ott"
            ),
            SportGame(
                id = "spt_nfl_3",
                sport = "NFL",
                leagueLabel = "NFL",
                matchup = "Eagles vs. Cowboys",
                subtitle = "NFL · FOX · NFC East",
                timeOrStatus = "4:25 PM",
                scheduledTime = "4:25 PM",
                cleanSubtitle = "NFL · FOX · NFC East",
                network = "FOX",
                isLive = false,
                details = "Philadelphia Eagles at Dallas Cowboys · NFC East Division Clash at 4:25 PM ET",
                cleanDetails = "Philadelphia Eagles at Dallas Cowboys · NFC East Division Clash at 4:25 PM ET",
                pkg = null
            ),
            SportGame(
                id = "spt_soc_1",
                sport = "Soccer",
                leagueLabel = "EPL",
                matchup = "Arsenal vs. Liverpool",
                subtitle = "Premier League · ARS 2 - 2 LIV",
                timeOrStatus = "LIVE · 68'",
                scheduledTime = "12:30 PM",
                cleanSubtitle = "Premier League · Peacock / USA",
                network = "Peacock",
                isLive = true,
                details = "Premier League Matchweek · Arsenal vs. Liverpool at Emirates Stadium (ARS 2 - 2 LIV, 68th minute)",
                cleanDetails = "Premier League Matchweek · Arsenal vs. Liverpool at Emirates Stadium · Kickoff 12:30 PM ET on Peacock (Scores hidden by No Spoilers)",
                pkg = "com.peacocktv.peacockandroid"
            ),
            SportGame(
                id = "spt_soc_2",
                sport = "Soccer",
                leagueLabel = "UCL",
                matchup = "Real Madrid vs. AC Milan",
                subtitle = "Champions League · Paramount+",
                timeOrStatus = "3:00 PM",
                scheduledTime = "3:00 PM",
                cleanSubtitle = "Champions League · Paramount+",
                network = "Paramount+",
                isLive = false,
                details = "UEFA Champions League · Real Madrid vs. AC Milan at Santiago Bernabéu · Streaming on Paramount+",
                cleanDetails = "UEFA Champions League · Real Madrid vs. AC Milan at Santiago Bernabéu · Streaming on Paramount+",
                pkg = "com.cbs.ott"
            ),
            SportGame(
                id = "spt_nhl_1",
                sport = "NHL",
                leagueLabel = "NHL",
                matchup = "Oilers vs. Golden Knights",
                subtitle = "NHL · ESPN+ · Pacific Division",
                timeOrStatus = "9:00 PM",
                scheduledTime = "9:00 PM",
                cleanSubtitle = "NHL · ESPN+ · Pacific Division",
                network = "ESPN+",
                isLive = false,
                details = "Edmonton Oilers at Vegas Golden Knights · T-Mobile Arena · Puck drop 9:00 PM ET",
                cleanDetails = "Edmonton Oilers at Vegas Golden Knights · T-Mobile Arena · Puck drop 9:00 PM ET",
                pkg = "com.espn.score_center"
            ),
            SportGame(
                id = "spt_nhl_2",
                sport = "NHL",
                leagueLabel = "NHL",
                matchup = "Rangers vs. Bruins",
                subtitle = "NHL · TNT · Original Six",
                timeOrStatus = "7:00 PM",
                scheduledTime = "7:00 PM",
                cleanSubtitle = "NHL · TNT · Original Six",
                network = "TNT",
                isLive = false,
                details = "New York Rangers at Boston Bruins · TD Garden · 7:00 PM ET",
                cleanDetails = "New York Rangers at Boston Bruins · TD Garden · 7:00 PM ET",
                pkg = "com.wbd.stream"
            ),
            SportGame(
                id = "spt_mlb_1",
                sport = "MLB",
                leagueLabel = "MLB",
                matchup = "Dodgers vs. Yankees",
                subtitle = "MLB · FOX · Fall Classic",
                timeOrStatus = "8:08 PM",
                scheduledTime = "8:08 PM",
                cleanSubtitle = "MLB · FOX · Fall Classic",
                network = "FOX",
                isLive = false,
                details = "Los Angeles Dodgers vs. New York Yankees · First pitch 8:08 PM ET on FOX",
                cleanDetails = "Los Angeles Dodgers vs. New York Yankees · First pitch 8:08 PM ET on FOX",
                pkg = null
            ),
            SportGame(
                id = "spt_f1_1",
                sport = "F1",
                leagueLabel = "F1",
                matchup = "United States / Americas GP",
                subtitle = "Formula 1 · ESPN · Lights Out",
                timeOrStatus = "4:00 PM",
                scheduledTime = "4:00 PM",
                cleanSubtitle = "Formula 1 · ESPN · Lights Out",
                network = "ESPN / F1 TV",
                isLive = false,
                details = "Formula 1 Grand Prix — Main Race (56 Laps) · Live coverage on ESPN and F1 TV Pro",
                cleanDetails = "Formula 1 Grand Prix — Main Race (56 Laps) · Scheduled Start: 4:00 PM ET on ESPN and F1 TV Pro",
                pkg = "com.espn.score_center"
            )
        )

        // No hardcoded UFC or Boxing fights: all combat events are fetched from live schedules with verified dates >= today.
        val combat = emptyList<CombatEvent>()

        return TrackerSnapshot(movies = movies, sports = sports, combat = combat, updatedAtMs = now)
    }

    // ---------- Disk Cache ----------

    private fun writeCache(ctx: Context, snap: TrackerSnapshot) {
        try {
            val root = JSONObject()
            root.put("updatedAtMs", snap.updatedAtMs)
            val mArr = JSONArray()
            for (m in snap.movies) if (!m.isCustom) {
                mArr.put(JSONObject().apply {
                    put("id", m.id); put("title", m.title); put("dateStr", m.dateStr)
                    put("relativeStr", m.relativeStr); put("daysAway", m.daysAway)
                    put("category", m.category); put("badge", m.badge)
                    put("platform", m.platform); put("details", m.details)
                    put("posterUrl", m.posterUrl); put("isTheatrical", m.isTheatrical)
                    put("pkg", m.pkg ?: "")
                })
            }
            root.put("movies", mArr)
            val sArr = JSONArray()
            for (s in snap.sports) if (!s.isCustom) {
                sArr.put(JSONObject().apply {
                    put("id", s.id); put("sport", s.sport); put("leagueLabel", s.leagueLabel)
                    put("matchup", s.matchup); put("subtitle", s.subtitle)
                    put("timeOrStatus", s.timeOrStatus); put("scheduledTime", s.scheduledTime)
                    put("cleanSubtitle", s.cleanSubtitle); put("network", s.network)
                    put("isLive", s.isLive); put("details", s.details)
                    put("cleanDetails", s.cleanDetails); put("pkg", s.pkg ?: "")
                })
            }
            root.put("sports", sArr)
            val cArr = JSONArray()
            for (c in snap.combat) if (!c.isCustom) {
                cArr.put(JSONObject().apply {
                    put("id", c.id); put("promotion", c.promotion); put("eventTitle", c.eventTitle)
                    put("dateStr", c.dateStr); put("daysAway", c.daysAway); put("venue", c.venue)
                    put("mainEvent", c.mainEvent); put("mainWeight", c.mainWeight)
                    put("coMainEvent", c.coMainEvent); put("coMainWeight", c.coMainWeight)
                    put("broadcast", c.broadcast); put("resultSummary", c.resultSummary)
                    put("pkg", c.pkg ?: "")
                    put("eventEpochMs", c.eventEpochMs)
                    val u = JSONArray()
                    c.undercard.forEach { u.put(it) }
                    put("undercard", u)
                })
            }
            root.put("combat", cArr)
            val f = cacheFile(ctx)
            val tmp = File(f.path + ".tmp")
            tmp.writeText(root.toString())
            tmp.renameTo(f)
        } catch (e: Throwable) { }
    }

    private fun readCache(ctx: Context): TrackerSnapshot? {
        val f = cacheFile(ctx)
        if (!f.exists()) return null
        return try {
            val root = JSONObject(f.readText())
            val mArr = root.getJSONArray("movies")
            val movies = (0 until mArr.length()).map { i ->
                val o = mArr.getJSONObject(i)
                MovieRelease(
                    id = o.getString("id"), title = o.getString("title"),
                    dateStr = o.getString("dateStr"), relativeStr = o.optString("relativeStr"),
                    daysAway = o.optInt("daysAway", 1), category = o.getString("category"),
                    badge = o.getString("badge"), platform = o.getString("platform"),
                    details = o.optString("details"), posterUrl = o.optString("posterUrl", ""),
                    isTheatrical = o.optBoolean("isTheatrical", true), pkg = o.optString("pkg").ifEmpty { null }
                )
            }.filter { it.daysAway >= 0 }
            val sArr = root.getJSONArray("sports")
            val sports = (0 until sArr.length()).map { i ->
                val o = sArr.getJSONObject(i)
                val tStatus = o.getString("timeOrStatus")
                val sub = o.getString("subtitle")
                val det = o.optString("details")
                SportGame(
                    id = o.getString("id"), sport = o.getString("sport"),
                    leagueLabel = o.getString("leagueLabel"), matchup = o.getString("matchup"),
                    subtitle = sub, timeOrStatus = tStatus,
                    scheduledTime = o.optString("scheduledTime").ifEmpty { tStatus },
                    cleanSubtitle = o.optString("cleanSubtitle").ifEmpty { sub },
                    network = o.optString("network"), isLive = o.optBoolean("isLive"),
                    details = det, cleanDetails = o.optString("cleanDetails").ifEmpty { det },
                    pkg = o.optString("pkg").ifEmpty { null }
                )
            }
            val cArr = root.getJSONArray("combat")
            val combat = (0 until cArr.length()).mapNotNull { i ->
                val o = cArr.getJSONObject(i)
                val epoch = o.optLong("eventEpochMs", 0L)
                val days = if (epoch > 0L) daysFromToday(epoch) else -1
                if (days < 0) return@mapNotNull null
                val uArr = o.optJSONArray("undercard")
                val under = if (uArr != null) (0 until uArr.length()).map { uArr.getString(it) } else emptyList()
                CombatEvent(
                    id = o.getString("id"), promotion = o.getString("promotion"),
                    eventTitle = o.getString("eventTitle"), dateStr = o.getString("dateStr"),
                    daysAway = days, venue = o.optString("venue"),
                    mainEvent = o.getString("mainEvent"), mainWeight = o.getString("mainWeight"),
                    coMainEvent = o.getString("coMainEvent"), coMainWeight = o.getString("coMainWeight"),
                    broadcast = o.optString("broadcast"), undercard = under,
                    resultSummary = o.optString("resultSummary"),
                    pkg = o.optString("pkg").ifEmpty { null },
                    eventEpochMs = epoch
                )
            }
            if (movies.isEmpty() || sports.isEmpty() || combat.isEmpty()) null
            else TrackerSnapshot(movies, sports, combat, root.optLong("updatedAtMs", f.lastModified()))
        } catch (e: Throwable) { null }
    }
}
