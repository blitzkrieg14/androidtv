package io.github.abhishekkaisar.glassos

import android.content.Context

class Prefs(ctx: Context) {
    private val sp = ctx.getSharedPreferences("glass", Context.MODE_PRIVATE)

    var sound: Boolean
        get() = sp.getBoolean("sound", true)
        set(v) { sp.edit().putBoolean("sound", v).apply() }

    /** When true, app tiles and logos use a frosted glass look that matches the background theme instead of default app colors. */
    var glassLogos: Boolean
        get() = sp.getBoolean("glassLogos", true)
        set(v) { sp.edit().putBoolean("glassLogos", v).apply() }

    /**
     * Fallback background, shown when the Top Shelf has no title art: a built-in scene (scene:..., default),
     * the user's own photos in Pictures/Glass (hero:photos) or one image (file:...).
     */
    var scene: String
        get() = sp.getString("scene_v2", DEFAULT_SCENE) ?: DEFAULT_SCENE
        set(v) { sp.edit().putString("scene_v2", v).apply() }

    /** Top Shelf source: [HeroRepo.SRC_TRACKER] (default), [HeroRepo.SRC_TMDB], [HeroRepo.SRC_APPLE] or [HeroRepo.SRC_SCENES]. */
    var topShelf: String
        get() = sp.getString("topShelf", HeroRepo.SRC_TRACKER) ?: HeroRepo.SRC_TRACKER
        set(v) { sp.edit().putString("topShelf", v).apply() }

    /** Multi-select sports filter for Today's Sports in the Top Shelf tracker. */
    var selectedSports: Set<String>
        get() {
            val raw = sp.getString("selectedSports", null) ?: return DEFAULT_SPORTS
            val parsed = raw.split(',').map { it.trim() }.filter { it.isNotEmpty() }.toSet()
            return if (parsed.isEmpty()) DEFAULT_SPORTS else parsed
        }
        set(v) { sp.edit().putString("selectedSports", v.joinToString(",")).apply() }

    /** Filter for Upcoming Movies in Top Shelf: ALL, BLURAY, or STREAMING. */
    var movieFilter: String
        get() = sp.getString("movieFilter", "ALL") ?: "ALL"
        set(v) { sp.edit().putString("movieFilter", v).apply() }

    /** Filter for UFC/Boxing in Top Shelf: ALL, UFC, or BOXING. */
    var combatFilter: String
        get() = sp.getString("combatFilter", "ALL") ?: "ALL"
        set(v) { sp.edit().putString("combatFilter", v).apply() }

    /** When true, only ★ Tracked items are shown across the Top Shelf schedule columns. */
    var trackedOnly: Boolean
        get() = sp.getBoolean("trackedOnly", false)
        set(v) { sp.edit().putBoolean("trackedOnly", v).apply() }

    /** When true, hides live scores, game clocks and fight results in Sports and UFC/Boxing (schedule only). */
    var noSpoilers: Boolean
        get() = sp.getBoolean("noSpoilers", true)
        set(v) { sp.edit().putBoolean("noSpoilers", v).apply() }

    /** IDs of items the user has marked ★ Tracked (pinned to top of each schedule list). */
    var trackedIds: Set<String>
        get() {
            val raw = sp.getString("trackedIds", null) ?: return DEFAULT_TRACKED_IDS
            return raw.split(',').map { it.trim() }.filter { it.isNotEmpty() }.toSet()
        }
        set(v) { sp.edit().putString("trackedIds", v.joinToString(",")).apply() }

    /** JSON array of custom user-added tracker items. */
    var customTrackerItemsJson: String
        get() = sp.getString("customTrackerItems", "[]") ?: "[]"
        set(v) { sp.edit().putString("customTrackerItems", v).apply() }

    /** TMDB key entered in Settings; the build's own key (BuildConfig.TMDB_API_KEY) is used when this is empty. */
    var tmdbKey: String
        get() = sp.getString("tmdbKey", "") ?: ""
        set(v) { sp.edit().putString("tmdbKey", v.trim()).apply() }

    val effectiveTmdbKey: String get() = tmdbKey.ifEmpty { BuildConfig.TMDB_API_KEY }

    /** ISO 3166 country code for the Top Shelf region (streaming availability). */
    var country: String
        get() = sp.getString("country", "") ?: ""
        set(v) { sp.edit().putString("country", v).apply() }

    var replaceHome: Boolean
        get() = sp.getBoolean("replaceHome", true)
        set(v) { sp.edit().putBoolean("replaceHome", v).apply() }

    var dock: List<String>?
        get() = sp.getString("dock", null)?.split(',')?.filter { it.isNotBlank() }
        set(v) { sp.edit().putString("dock", v?.joinToString(",")).apply() }

    /** The drawer's order once the user has moved an app (null: alphabetical). Apps not in it go at the end. */
    var order: List<String>?
        get() = sp.getString("order", null)?.split(',')?.filter { it.isNotBlank() }
        set(v) { sp.edit().putString("order", v?.joinToString(",")).apply() }

    /** Apps left out of the drawer, the top row and the top row picker (Settings > Activate / Hide apps). */
    var hidden: Set<String>
        get() = sp.getString("hidden", null)?.split(',')?.filter { it.isNotBlank() }?.toSet() ?: emptySet()
        set(v) { sp.edit().putString("hidden", v.joinToString(",")).apply() }

    /** True once the default hidden set (all apps except the top 6 dock apps) has been initialized. */
    var defaultHiddenInitialized: Boolean
        get() = sp.getBoolean("defaultHiddenInit_v1", false)
        set(v) { sp.edit().putBoolean("defaultHiddenInit_v1", v).apply() }

    /** Ensures that by default all apps besides the top 6 dock apps are hidden until activated in Settings. */
    fun ensureDefaultHidden(apps: List<AppEntry>) {
        if (defaultHiddenInitialized || apps.isEmpty()) return
        val savedDock = dock?.filter { p -> apps.any { it.pkg == p } }?.distinct()?.take(HomeScreen.COLS)
        val top6 = if (!savedDock.isNullOrEmpty()) savedDock else AppRepo.defaultDock(apps, HomeScreen.COLS)
        dock = top6
        val topSet = top6.toSet()
        hidden = apps.map { it.pkg }.filter { it !in topSet }.toSet()
        defaultHiddenInitialized = true
    }

    var name: String
        get() = sp.getString("name", "") ?: ""
        set(v) { sp.edit().putString("name", v).apply() }

    var setupDone: Boolean
        get() = sp.getBoolean("setupDone", false)
        set(v) { sp.edit().putBoolean("setupDone", v).apply() }

    var askedStorage: Boolean
        get() = sp.getBoolean("askedStorage", false)
        set(v) { sp.edit().putBoolean("askedStorage", v).apply() }

    companion object {
        const val DEFAULT_SCENE = "scene:navy"
        val ALL_SPORTS = listOf("NBA", "NFL", "Soccer", "NHL", "MLB", "F1")
        val DEFAULT_SPORTS = ALL_SPORTS.toSet()
        val DEFAULT_TRACKED_IDS = setOf("spt_nba_1", "spt_nfl_1")
    }
}
