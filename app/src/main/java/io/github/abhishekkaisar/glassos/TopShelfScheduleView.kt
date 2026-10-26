package io.github.abhishekkaisar.glassos

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Rect
import android.graphics.RectF
import android.text.TextPaint
import android.text.TextUtils
import android.view.Gravity
import android.view.KeyEvent
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.HorizontalScrollView
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast

/**
 * Interactive Top Shelf Schedule & Tracker for GlassOS:
 * - Each of the 3 sections (Upcoming Movies, Sports Today, UFC & Boxing) is a single focusable bubble by default.
 * - Pressing OK on a bubble enters it so you can scroll through its items and toggle its filter chips.
 * - Pressing Back exits the bubble back to the single-bubble selection level.
 */
class TopShelfScheduleView(private val act: HomeScreen) : FrameLayout(act.ctx) {
    private val prefs get() = act.prefs
    private var snapshot: TrackerSnapshot = TrackerRepo.loadImmediate(context)

    /** -1 when at the top-level 3-bubble view; 0 = Movies, 1 = Sports, 2 = UFC/Boxing when entered inside a bubble. */
    var activeColumn: Int = -1
        private set
    private var lastBubbleIndex: Int = 0

    private val headerBar = LinearLayout(context).apply {
        orientation = LinearLayout.HORIZONTAL
        gravity = Gravity.CENTER_VERTICAL
        clipChildren = false
        clipToPadding = false
    }
    private val columnsRow = LinearLayout(context).apply {
        orientation = LinearLayout.HORIZONTAL
        clipChildren = false
        clipToPadding = false
    }

    private val moviesBox = BubbleGlassBox(context, dp(18f)).apply {
        id = View.generateViewId()
        onClick = { enterColumn(0) }
        onBubbleFocus = { lastBubbleIndex = 0 }
    }
    private val sportsBox = BubbleGlassBox(context, dp(18f)).apply {
        id = View.generateViewId()
        onClick = { enterColumn(1) }
        onBubbleFocus = { lastBubbleIndex = 1 }
    }
    private val combatBox = BubbleGlassBox(context, dp(18f)).apply {
        id = View.generateViewId()
        onClick = { enterColumn(2) }
        onBubbleFocus = { lastBubbleIndex = 2 }
    }
    private val bubbles get() = listOf(moviesBox, sportsBox, combatBox)

    private lateinit var trackedChip: PillButton
    private lateinit var spoilersChip: PillButton
    private lateinit var sportsFilterBtn: PillButton
    private lateinit var addCustomBtn: PillButton
    private lateinit var refreshBtn: PillButton

    private val movieFilterChips = ArrayList<FilterChip>()
    private val sportFilterChips = ArrayList<FilterChip>()
    private val combatFilterChips = ArrayList<FilterChip>()

    private val moviesList = LinearLayout(context).apply { orientation = LinearLayout.VERTICAL; clipChildren = false; clipToPadding = false }
    private val sportsList = LinearLayout(context).apply { orientation = LinearLayout.VERTICAL; clipChildren = false; clipToPadding = false }
    private val combatList = LinearLayout(context).apply { orientation = LinearLayout.VERTICAL; clipChildren = false; clipToPadding = false }

    private val moviesScroll = makeScroll(moviesList)
    private val sportsScroll = makeScroll(sportsList)
    private val combatScroll = makeScroll(combatList)

    private val movieCountView = makeCountLabel()
    private val sportCountView = makeCountLabel()
    private val combatCountView = makeCountLabel()

    private val movieViews = ArrayList<View>()
    private val sportViews = ArrayList<View>()
    private val combatViews = ArrayList<View>()

    init {
        clipChildren = false
        clipToPadding = false
        buildHeader()
        buildColumns()
        applyBubbleMode()
        renderAll()
    }

    private fun makeScroll(inner: LinearLayout): ScrollView = ScrollView(context).apply {
        isVerticalScrollBarEnabled = false
        overScrollMode = View.OVER_SCROLL_NEVER
        isSmoothScrollingEnabled = true
        isFocusable = false
        clipChildren = true
        clipToPadding = false
        val padH = dpi(8f)
        inner.setPadding(padH, dpi(3f), padH, dpi(6f))
        addView(inner, ViewGroup.LayoutParams(-1, -2))
    }

    private fun makeCountLabel(): TextView = TextView(context).apply {
        textSize = 9.2f
        typeface = Fonts.medium
        setTextColor(0x99FFFFFF.toInt())
    }

    private fun buildHeader() {
        addView(headerBar, LayoutParams(-1, dpi(25f), Gravity.TOP).apply {
            rightMargin = dpi(144f)
        })

        val titleWrap = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        val dot = View(context).apply {
            background = object : android.graphics.drawable.Drawable() {
                private val p = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = 0xFF32D74B.toInt() }
                override fun draw(c: Canvas) {
                    val r = bounds.width() / 2f
                    c.drawCircle(bounds.exactCenterX(), bounds.exactCenterY(), r, p)
                }
                override fun setAlpha(a: Int) {}
                override fun setColorFilter(cf: android.graphics.ColorFilter?) {}
                @Deprecated("Deprecated in Java")
                override fun getOpacity(): Int = android.graphics.PixelFormat.TRANSLUCENT
            }
        }
        titleWrap.addView(dot, LinearLayout.LayoutParams(dpi(6f), dpi(6f)).apply { rightMargin = dpi(6f) })

        val mainTitle = TextView(context).apply {
            text = "Schedule & Tracker"
            textSize = 13.5f
            typeface = Fonts.medium
            setTextColor(Color.WHITE)
            setShadowLayer(dp(5f), 0f, dp(1f), 0x80000000.toInt())
        }
        titleWrap.addView(mainTitle)

        headerBar.addView(titleWrap, LinearLayout.LayoutParams(0, -2, 1f))

        trackedChip = PillButton(context, "All Schedule").apply {
            id = View.generateViewId()
            onClick = {
                prefs.trackedOnly = !prefs.trackedOnly
                renderAll()
            }
        }
        spoilersChip = PillButton(context, "No Spoilers: ON").apply {
            id = View.generateViewId()
            onClick = {
                prefs.noSpoilers = !prefs.noSpoilers
                renderAll()
            }
        }
        sportsFilterBtn = PillButton(context, "Sports Filter").apply {
            id = View.generateViewId()
            onClick = { act.trackerModal.openSportsFilter { renderAll() } }
        }
        addCustomBtn = PillButton(context, "+ Track").apply {
            id = View.generateViewId()
            onClick = {
                act.trackerModal.openAddCustom {
                    snapshot = TrackerRepo.loadImmediate(context)
                    renderAll()
                }
            }
        }
        refreshBtn = PillButton(context, "↻").apply {
            id = View.generateViewId()
            onClick = {
                Toast.makeText(context, "Refreshing live schedule…", Toast.LENGTH_SHORT).show()
                act.refreshTracker(force = true)
            }
        }

        val btnLp = { LinearLayout.LayoutParams(-2, dpi(21f)).apply { leftMargin = dpi(6f) } }
        headerBar.addView(trackedChip, btnLp())
        headerBar.addView(spoilersChip, btnLp())
        headerBar.addView(sportsFilterBtn, btnLp())
        headerBar.addView(addCustomBtn, btnLp())
        headerBar.addView(refreshBtn, btnLp())
    }

    private fun buildColumns() {
        addView(columnsRow, LayoutParams(-1, -1).apply {
            topMargin = dpi(30f)
        })
        val gap = dpi(10f)
        columnsRow.addView(moviesBox, LinearLayout.LayoutParams(0, -1, 1.0f))
        columnsRow.addView(sportsBox, LinearLayout.LayoutParams(0, -1, 1.04f).apply { leftMargin = gap; rightMargin = gap })
        columnsRow.addView(combatBox, LinearLayout.LayoutParams(0, -1, 1.08f))

        setupMoviesColumn()
        setupSportsColumn()
        setupCombatColumn()
    }

    private fun setupMoviesColumn() {
        val col = LinearLayout(context).apply { orientation = LinearLayout.VERTICAL; clipChildren = true }
        col.addView(makeColumnHeader("UPCOMING MOVIES", "Blu-ray & Streaming", movieCountView))

        val filterRow = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            setPadding(dpi(8f), 0, dpi(8f), dpi(3f))
            clipChildren = false; clipToPadding = false
        }
        val options = listOf("ALL" to "All", "BLURAY" to "Blu-ray / 4K", "STREAMING" to "Streaming")
        for ((code, label) in options) {
            val chip = FilterChip(context, label).apply {
                id = View.generateViewId()
                tag = code
                onClick = {
                    prefs.movieFilter = code
                    renderMovies()
                    wireFocusGraph()
                }
            }
            movieFilterChips.add(chip)
            filterRow.addView(chip, LinearLayout.LayoutParams(-2, dpi(18f)).apply { rightMargin = dpi(4f) })
        }
        col.addView(filterRow, LinearLayout.LayoutParams(-1, -2))
        col.addView(moviesScroll, LinearLayout.LayoutParams(-1, 0, 1f))
        moviesBox.addView(col, LayoutParams(-1, -1))
    }

    private fun setupSportsColumn() {
        val col = LinearLayout(context).apply { orientation = LinearLayout.VERTICAL; clipChildren = true }
        col.addView(makeColumnHeader("SPORTS TODAY", "Filter or multi-select", sportCountView))

        val chipScroll = HorizontalScrollView(context).apply {
            isHorizontalScrollBarEnabled = false
            overScrollMode = View.OVER_SCROLL_NEVER
            isFocusable = false
            clipChildren = false; clipToPadding = false
        }
        val filterRow = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            setPadding(dpi(8f), 0, dpi(8f), dpi(3f))
            clipChildren = false; clipToPadding = false
        }
        val allChip = FilterChip(context, "All").apply {
            id = View.generateViewId()
            tag = "ALL"
            onClick = {
                prefs.selectedSports = Prefs.ALL_SPORTS.toSet()
                renderSports()
                updateTopButtons()
                wireFocusGraph()
            }
        }
        sportFilterChips.add(allChip)
        filterRow.addView(allChip, LinearLayout.LayoutParams(-2, dpi(18f)).apply { rightMargin = dpi(4f) })

        for (sp in Prefs.ALL_SPORTS) {
            val chip = FilterChip(context, sp).apply {
                id = View.generateViewId()
                tag = sp
                onClick = {
                    val cur = prefs.selectedSports.toMutableSet()
                    if (cur.size == Prefs.ALL_SPORTS.size) {
                        cur.clear()
                        cur.add(sp)
                    } else if (sp in cur) {
                        if (cur.size > 1) cur.remove(sp) else cur.addAll(Prefs.ALL_SPORTS)
                    } else {
                        cur.add(sp)
                    }
                    prefs.selectedSports = cur
                    renderSports()
                    updateTopButtons()
                    wireFocusGraph()
                }
            }
            sportFilterChips.add(chip)
            filterRow.addView(chip, LinearLayout.LayoutParams(-2, dpi(18f)).apply { rightMargin = dpi(4f) })
        }
        chipScroll.addView(filterRow, ViewGroup.LayoutParams(-2, -2))
        col.addView(chipScroll, LinearLayout.LayoutParams(-1, -2))
        col.addView(sportsScroll, LinearLayout.LayoutParams(-1, 0, 1f))
        sportsBox.addView(col, LayoutParams(-1, -1))
    }

    private fun setupCombatColumn() {
        val col = LinearLayout(context).apply { orientation = LinearLayout.VERTICAL; clipChildren = true }
        col.addView(makeColumnHeader("UFC & BOXING", "This Month · Main & Co-Main", combatCountView))

        val filterRow = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            setPadding(dpi(8f), 0, dpi(8f), dpi(3f))
            clipChildren = false; clipToPadding = false
        }
        val options = listOf("ALL" to "All Cards", "UFC" to "UFC", "BOXING" to "Boxing")
        for ((code, label) in options) {
            val chip = FilterChip(context, label).apply {
                id = View.generateViewId()
                tag = code
                onClick = {
                    prefs.combatFilter = code
                    renderCombat()
                    wireFocusGraph()
                }
            }
            combatFilterChips.add(chip)
            filterRow.addView(chip, LinearLayout.LayoutParams(-2, dpi(18f)).apply { rightMargin = dpi(4f) })
        }
        col.addView(filterRow, LinearLayout.LayoutParams(-1, -2))
        col.addView(combatScroll, LinearLayout.LayoutParams(-1, 0, 1f))
        combatBox.addView(col, LayoutParams(-1, -1))
    }

    private fun makeColumnHeader(titleText: String, subText: String, countView: TextView): View {
        val wrap = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dpi(10f), dpi(7f), dpi(10f), dpi(3f))
        }
        val left = LinearLayout(context).apply { orientation = LinearLayout.VERTICAL }
        left.addView(TextView(context).apply {
            text = titleText
            textSize = 10.5f
            typeface = Fonts.medium
            letterSpacing = 0.04f
            setTextColor(0xF2FFFFFF.toInt())
        })
        left.addView(TextView(context).apply {
            text = subText
            textSize = 8.8f
            typeface = Fonts.regular
            setTextColor(0x8CFFFFFF.toInt())
        })
        wrap.addView(left, LinearLayout.LayoutParams(0, -2, 1f))
        wrap.addView(countView, LinearLayout.LayoutParams(-2, -2))
        return wrap
    }

    // ---------- Entering & Exiting a Bubble ----------

    /** Called when user clicks OK on one of the 3 bubbles (0 = Movies, 1 = Sports, 2 = UFC/Boxing). */
    fun enterColumn(colIdx: Int) {
        if (colIdx !in 0..2) return
        activeColumn = colIdx
        lastBubbleIndex = colIdx
        applyBubbleMode()
        updateCountLabels()
        val firstTarget = when (colIdx) {
            0 -> movieViews.firstOrNull() ?: movieFilterChips.firstOrNull()
            1 -> sportViews.firstOrNull() ?: sportFilterChips.firstOrNull()
            2 -> combatViews.firstOrNull() ?: combatFilterChips.firstOrNull()
            else -> null
        }
        firstTarget?.requestFocus()
    }

    /** Called on Back when inside a bubble: returns focus to the bubble card itself. */
    fun exitActiveColumn(): Boolean {
        if (activeColumn < 0) return false
        val colToFocus = activeColumn
        activeColumn = -1
        moviesScroll.scrollTo(0, 0)
        sportsScroll.scrollTo(0, 0)
        combatScroll.scrollTo(0, 0)
        applyBubbleMode()
        updateCountLabels()
        bubbles.getOrNull(colToFocus)?.requestFocus()
        return true
    }

    private fun applyBubbleMode() {
        bubbles.forEachIndexed { idx, box ->
            val insideThis = activeColumn == idx
            box.activeInside = insideThis
            if (activeColumn < 0) {
                // Card-level mode: each bubble is a single focusable item; children are not focusable until OK is pressed.
                box.isFocusable = true
                box.isClickable = true
                box.descendantFocusability = ViewGroup.FOCUS_BLOCK_DESCENDANTS
            } else if (insideThis) {
                // Inside this bubble: children (filter chips and schedule rows) can be scrolled & focused.
                box.isFocusable = false
                box.isClickable = false
                box.descendantFocusability = ViewGroup.FOCUS_AFTER_DESCENDANTS
            } else {
                // Other bubbles are locked while browsing inside the active bubble.
                box.isFocusable = false
                box.isClickable = false
                box.descendantFocusability = ViewGroup.FOCUS_BLOCK_DESCENDANTS
            }
        }
        headerBar.descendantFocusability = if (activeColumn < 0) ViewGroup.FOCUS_AFTER_DESCENDANTS else ViewGroup.FOCUS_BLOCK_DESCENDANTS
    }

    // ---------- Data Binding & Rendering ----------

    fun setSnapshot(snap: TrackerSnapshot) {
        snapshot = snap
        renderAll()
    }

    fun renderAll() {
        updateTopButtons()
        renderMovies()
        renderSports()
        renderCombat()
        updateCountLabels()
        wireFocusGraph()
        invalidateGlass()
    }

    private fun updateTopButtons() {
        val trackedCount = prefs.trackedIds.size
        trackedChip.label = if (prefs.trackedOnly) "★ Tracked ($trackedCount)" else "All (★ $trackedCount)"
        trackedChip.active = prefs.trackedOnly

        val hideSpoilers = prefs.noSpoilers
        spoilersChip.label = if (hideSpoilers) "No Spoilers: ON" else "Spoilers: Shown"
        spoilersChip.active = hideSpoilers

        val sel = prefs.selectedSports
        sportsFilterBtn.label = if (sel.size >= Prefs.ALL_SPORTS.size) "Sports: All"
        else "Sports: ${sel.joinToString("/")}"
        sportsFilterBtn.active = sel.size < Prefs.ALL_SPORTS.size
    }

    private fun updateCountLabels() {
        val noSpoilers = prefs.noSpoilers
        movieCountView.text = if (activeColumn == 0) "Back to exit" else "${movieViews.size} · OK to browse"
        movieCountView.setTextColor(if (activeColumn == 0) 0xFF64D2FF.toInt() else 0x90FFFFFF.toInt())

        val spTag = if (noSpoilers) "No Spoilers" else "Today"
        sportCountView.text = if (activeColumn == 1) "Back to exit" else "${sportViews.size} · $spTag"
        sportCountView.setTextColor(if (activeColumn == 1) 0xFF64D2FF.toInt() else 0x90FFFFFF.toInt())

        combatCountView.text = if (activeColumn == 2) "Back to exit" else "${combatViews.size} · OK to browse"
        combatCountView.setTextColor(if (activeColumn == 2) 0xFF64D2FF.toInt() else 0x90FFFFFF.toInt())
    }

    private fun renderMovies() {
        val filter = prefs.movieFilter
        movieFilterChips.forEach { it.chosen = it.tag == filter }

        val tracked = prefs.trackedIds
        val onlyTracked = prefs.trackedOnly
        val filtered = snapshot.movies.filter { m ->
            m.daysAway >= 0 && (filter == "ALL" || m.category == filter) && (!onlyTracked || m.id in tracked)
        }.sortedWith(compareByDescending<MovieRelease> { it.id in tracked }.thenBy { it.daysAway })

        moviesList.removeAllViews()
        movieViews.clear()

        if (filtered.isEmpty()) {
            moviesList.addView(emptyHint("No matching movie releases"))
            return
        }
        for (m in filtered) {
            val row = MovieRowView(context, m, m.id in tracked).apply {
                id = View.generateViewId()
                onClick = {
                    act.trackerModal.openMovieDetail(m) {
                        snapshot = TrackerRepo.loadImmediate(context)
                        renderAll()
                    }
                }
            }
            moviesList.addView(row, LinearLayout.LayoutParams(-1, dpi(36f)).apply { bottomMargin = dpi(3.5f) })
            movieViews.add(row)
        }
    }

    private fun renderSports() {
        val sel = prefs.selectedSports
        val noSpoilers = prefs.noSpoilers
        val allActive = sel.size >= Prefs.ALL_SPORTS.size
        sportFilterChips.forEach { chip ->
            val tag = chip.tag as? String ?: ""
            if (tag == "ALL") {
                chip.chosen = allActive
                chip.label = "All"
            } else {
                val on = tag in sel
                chip.chosen = on && !allActive
                chip.label = if (on && !allActive) "✓ $tag" else tag
            }
        }

        val tracked = prefs.trackedIds
        val onlyTracked = prefs.trackedOnly
        val filtered = snapshot.sports.filter { s ->
            s.sport in sel && (!onlyTracked || s.id in tracked)
        }.sortedWith(compareByDescending<SportGame> { it.id in tracked }.thenByDescending { it.isLive && !noSpoilers })

        sportsList.removeAllViews()
        sportViews.clear()

        if (filtered.isEmpty()) {
            sportsList.addView(emptyHint("No games for selected sport filter"))
            return
        }
        for (s in filtered) {
            val row = SportRowView(context, s, s.id in tracked, noSpoilers).apply {
                id = View.generateViewId()
                onClick = {
                    act.trackerModal.openSportDetail(s) {
                        snapshot = TrackerRepo.loadImmediate(context)
                        renderAll()
                    }
                }
            }
            sportsList.addView(row, LinearLayout.LayoutParams(-1, dpi(36f)).apply { bottomMargin = dpi(3.5f) })
            sportViews.add(row)
        }
    }

    private fun renderCombat() {
        val filter = prefs.combatFilter
        combatFilterChips.forEach { it.chosen = it.tag == filter }

        val tracked = prefs.trackedIds
        val onlyTracked = prefs.trackedOnly
        val filtered = snapshot.combat.filter { c ->
            c.daysAway >= 0 && (filter == "ALL" || c.promotion == filter) && (!onlyTracked || c.id in tracked)
        }.sortedWith(compareByDescending<CombatEvent> { it.id in tracked }.thenBy { it.daysAway })

        combatList.removeAllViews()
        combatViews.clear()

        if (filtered.isEmpty()) {
            combatList.addView(emptyHint("No matching fight cards this month"))
            return
        }
        for (c in filtered) {
            val card = CombatCardView(context, c, c.id in tracked).apply {
                id = View.generateViewId()
                onClick = {
                    act.trackerModal.openCombatDetail(c) {
                        snapshot = TrackerRepo.loadImmediate(context)
                        renderAll()
                    }
                }
            }
            combatList.addView(card, LinearLayout.LayoutParams(-1, dpi(56f)).apply { bottomMargin = dpi(4f) })
            combatViews.add(card)
        }
    }

    private fun emptyHint(msg: String): View = TextView(context).apply {
        text = msg
        textSize = 10.5f
        typeface = Fonts.regular
        setTextColor(0x80FFFFFF.toInt())
        gravity = Gravity.CENTER
        setPadding(dpi(8f), dpi(20f), dpi(8f), dpi(20f))
    }

    /** Connects focus transitions both for the 3-bubble top level and for scrolling inside an entered bubble. */
    private fun wireFocusGraph() {
        // 1. Top-level 3 bubbles: Left/Right steps across the 3 bubbles; Up goes to top header bar; Down goes to App Dock!
        moviesBox.nextFocusLeftId = moviesBox.id
        moviesBox.nextFocusRightId = sportsBox.id
        sportsBox.nextFocusLeftId = moviesBox.id
        sportsBox.nextFocusRightId = combatBox.id
        combatBox.nextFocusLeftId = sportsBox.id
        combatBox.nextFocusRightId = combatBox.id

        for (box in bubbles) {
            box.nextFocusUpId = trackedChip.id
            box.setOnKeyListener { _, keyCode, event ->
                if (event.action == KeyEvent.ACTION_DOWN && keyCode == KeyEvent.KEYCODE_DPAD_DOWN && activeColumn < 0) {
                    act.focusDock()
                    true
                } else false
            }
        }
        listOf(trackedChip, spoilersChip, sportsFilterBtn, addCustomBtn, refreshBtn).forEach { btn ->
            btn.nextFocusDownId = bubbles[lastBubbleIndex.coerceIn(0, 2)].id
        }

        // 2. Inside a bubble: chips at top, scrollable items below; stays inside the entered bubble until Back is pressed.
        fun bindInsideColumn(views: List<View>, chips: List<FilterChip>) {
            chips.forEachIndexed { idx, c ->
                c.nextFocusUpId = c.id
                c.nextFocusLeftId = chips.getOrNull(idx - 1)?.id ?: c.id
                c.nextFocusRightId = chips.getOrNull(idx + 1)?.id ?: c.id
                if (views.isNotEmpty()) c.nextFocusDownId = views.first().id
            }
            views.forEachIndexed { idx, v ->
                v.nextFocusLeftId = v.id
                v.nextFocusRightId = v.id
                if (idx == 0 && chips.isNotEmpty()) v.nextFocusUpId = chips.first().id
                if (idx == views.lastIndex) v.nextFocusDownId = v.id
            }
        }
        bindInsideColumn(movieViews, movieFilterChips)
        bindInsideColumn(sportViews, sportFilterChips)
        bindInsideColumn(combatViews, combatFilterChips)
    }

    fun requestDefaultFocus(): Boolean {
        if (activeColumn in 0..2) {
            val insideTarget = when (activeColumn) {
                0 -> movieViews.firstOrNull() ?: movieFilterChips.firstOrNull()
                1 -> sportViews.firstOrNull() ?: sportFilterChips.firstOrNull()
                else -> combatViews.firstOrNull() ?: combatFilterChips.firstOrNull()
            }
            if (insideTarget != null) return insideTarget.requestFocus()
        }
        val targetBubble = bubbles[lastBubbleIndex.coerceIn(0, 2)]
        return targetBubble.requestFocus()
    }

    fun invalidateGlass() {
        moviesBox.invalidate()
        sportsBox.invalidate()
        combatBox.invalidate()
    }
}

/**
 * A focusable Liquid Glass column bubble:
 * - At rest / card-level: acts as a single selectable bubble with spring focus scale and glowing white rim.
 * - When entered (OK pressed): highlights its border with a soft cyan rim while the user scrolls inside.
 */
private class BubbleGlassBox(ctx: Context, radius: Float) : GlassBox(ctx, radius) {
    var onClick: (() -> Unit)? = null
    var onBubbleFocus: (() -> Unit)? = null
    var activeInside: Boolean = false
        set(v) { if (field != v) { field = v; invalidate() } }

    private val focus = FocusAnim(this, 1.018f) { invalidate() }
    private val rimPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE }

    init {
        glass.tint = 0.05f
        glass.dark = 0.26f
        glass.sheen = 0.05f
        glass.highlight = 0.44f
        glass.border = 0.12f
        shadowAlpha = 0.20f
        clipChildren = true
        isFocusable = true
        isClickable = true
        setOnClickListener { if (!activeInside) onClick?.invoke() }
    }

    override fun onFocusChanged(gained: Boolean, direction: Int, previous: Rect?) {
        super.onFocusChanged(gained, direction, previous)
        focus.set(gained)
        if (gained) onBubbleFocus?.invoke()
    }

    override fun onDraw(c: Canvas) {
        val f = focus.amount
        glass.tint = 0.05f + 0.06f * f
        super.onDraw(c)
        if (f > 0.01f || activeInside) {
            rimPaint.strokeWidth = dp(if (activeInside) 1.5f else 1.8f)
            rimPaint.color = if (activeInside) 0xCC64D2FF.toInt() else alphaOf(Color.WHITE, 0.88f * f)
            val hs = rimPaint.strokeWidth / 2f
            c.drawRoundRect(hs, hs, width - hs, height - hs, radius - hs, radius - hs, rimPaint)
        }
    }
}

// ---------- Compact Schedule Row Views ----------

/** Compact row for an Upcoming Movie Release (Blu-ray / 4K UHD or Streaming / Digital with date). */
private class MovieRowView(
    ctx: Context,
    val item: MovieRelease,
    val tracked: Boolean
) : View(ctx) {
    var onClick: (() -> Unit)? = null
    private val focus = FocusAnim(this, 1.02f) { invalidate() }
    private val rect = RectF()
    private val badgeRect = RectF()
    private val fill = Paint(Paint.ANTI_ALIAS_FLAG)
    private val stroke = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE }
    private val titlePaint = TextPaint(Paint.ANTI_ALIAS_FLAG).apply { typeface = Fonts.medium; textSize = ctx.dp(10.2f) }
    private val subPaint = TextPaint(Paint.ANTI_ALIAS_FLAG).apply { typeface = Fonts.regular; textSize = ctx.dp(8.6f) }
    private val badgePaint = TextPaint(Paint.ANTI_ALIAS_FLAG).apply { typeface = Fonts.medium; textSize = ctx.dp(7.2f) }
    private val datePaint = TextPaint(Paint.ANTI_ALIAS_FLAG).apply {
        typeface = Fonts.medium; textSize = ctx.dp(9.2f); textAlign = Paint.Align.RIGHT
    }
    private val relPaint = TextPaint(Paint.ANTI_ALIAS_FLAG).apply {
        typeface = Fonts.regular; textSize = ctx.dp(7.8f); textAlign = Paint.Align.RIGHT
    }

    init {
        isFocusable = true
        isClickable = true
        setOnClickListener { onClick?.invoke() }
    }

    override fun onFocusChanged(gained: Boolean, direction: Int, previous: Rect?) {
        super.onFocusChanged(gained, direction, previous)
        focus.set(gained)
    }

    override fun onDraw(c: Canvas) {
        val f = focus.amount
        val w = width.toFloat()
        val h = height.toFloat()
        val r = dp(8.5f)
        rect.set(0f, 0f, w, h)

        fill.color = lerpColor(if (tracked) 0x22FFFFFF else 0x12FFFFFF, 0xF2FFFFFF.toInt(), f)
        c.drawRoundRect(rect, r, r, fill)

        if (tracked && f < 0.5f) {
            stroke.strokeWidth = dp(0.8f)
            stroke.color = 0x55FFD60A
            c.drawRoundRect(dp(0.5f), dp(0.5f), w - dp(0.5f), h - dp(0.5f), r, r, stroke)
        }

        // Format badge pill on the left
        val isBluray = item.category == "BLURAY"
        val badgeBg = if (isBluray) 0xFF0A84FF.toInt() else 0xFF30D158.toInt()
        val bw = badgePaint.measureText(item.badge) + dp(7.5f)
        val bh = dp(12.5f)
        val bx = dp(6.5f)
        val by = dp(5.5f)
        badgeRect.set(bx, by, bx + bw, by + bh)
        fill.color = if (f > 0.5f) badgeBg else alphaOf(badgeBg, 0.82f)
        c.drawRoundRect(badgeRect, bh / 2f, bh / 2f, fill)
        badgePaint.color = Color.WHITE
        c.drawText(item.badge, bx + dp(3.75f), by + bh / 2f - (badgePaint.ascent() + badgePaint.descent()) / 2f, badgePaint)

        // Date + countdown on the right
        val rightX = w - dp(7f)
        val dateText = if (tracked) "★ ${item.dateStr}" else item.dateStr
        datePaint.color = lerpColor(if (tracked) 0xFFFFD60A.toInt() else 0xFFFFFFFF.toInt(), 0xFF101218.toInt(), f)
        c.drawText(dateText, rightX, dp(15f), datePaint)

        relPaint.color = lerpColor(0x99FFFFFF.toInt(), 0xFF4A4E58.toInt(), f)
        c.drawText(item.relativeStr, rightX, dp(28f), relPaint)

        // Title and platform
        val textLeft = bx + bw + dp(5.5f)
        val maxTextW = (rightX - datePaint.measureText(dateText) - dp(6f) - textLeft).coerceAtLeast(dp(36f))
        titlePaint.color = lerpColor(Color.WHITE, 0xFF101218.toInt(), f)
        val ellipsizedTitle = TextUtils.ellipsize(item.title, titlePaint, maxTextW, TextUtils.TruncateAt.END).toString()
        c.drawText(ellipsizedTitle, textLeft, dp(15.2f), titlePaint)

        subPaint.color = lerpColor(0xA6FFFFFF.toInt(), 0xFF484C56.toInt(), f)
        val ellipsizedSub = TextUtils.ellipsize(item.platform, subPaint, maxTextW + bw, TextUtils.TruncateAt.END).toString()
        c.drawText(ellipsizedSub, bx, dp(28.5f), subPaint)
    }
}

/** Compact row for a Sport Game played today, respecting No Spoilers mode. */
private class SportRowView(
    ctx: Context,
    val item: SportGame,
    val tracked: Boolean,
    val noSpoilers: Boolean
) : View(ctx) {
    var onClick: (() -> Unit)? = null
    private val focus = FocusAnim(this, 1.02f) { invalidate() }
    private val rect = RectF()
    private val badgeRect = RectF()
    private val fill = Paint(Paint.ANTI_ALIAS_FLAG)
    private val stroke = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE }
    private val titlePaint = TextPaint(Paint.ANTI_ALIAS_FLAG).apply { typeface = Fonts.medium; textSize = ctx.dp(10.2f) }
    private val subPaint = TextPaint(Paint.ANTI_ALIAS_FLAG).apply { typeface = Fonts.regular; textSize = ctx.dp(8.6f) }
    private val badgePaint = TextPaint(Paint.ANTI_ALIAS_FLAG).apply { typeface = Fonts.medium; textSize = ctx.dp(7.2f) }
    private val timePaint = TextPaint(Paint.ANTI_ALIAS_FLAG).apply {
        typeface = Fonts.medium; textSize = ctx.dp(9.0f); textAlign = Paint.Align.RIGHT
    }

    init {
        isFocusable = true
        isClickable = true
        setOnClickListener { onClick?.invoke() }
    }

    override fun onFocusChanged(gained: Boolean, direction: Int, previous: Rect?) {
        super.onFocusChanged(gained, direction, previous)
        focus.set(gained)
    }

    private fun sportColor(s: String): Int = when (s) {
        "NBA" -> 0xFFFF9F0A.toInt()
        "NFL" -> 0xFF0A84FF.toInt()
        "Soccer" -> 0xFF30D158.toInt()
        "NHL" -> 0xFF64D2FF.toInt()
        "MLB" -> 0xFFFF453A.toInt()
        "F1" -> 0xFFFF375F.toInt()
        else -> 0xFF5E5CE6.toInt()
    }

    override fun onDraw(c: Canvas) {
        val f = focus.amount
        val w = width.toFloat()
        val h = height.toFloat()
        val r = dp(8.5f)
        rect.set(0f, 0f, w, h)

        fill.color = lerpColor(if (tracked) 0x22FFFFFF else 0x12FFFFFF, 0xF2FFFFFF.toInt(), f)
        c.drawRoundRect(rect, r, r, fill)

        if (tracked && f < 0.5f) {
            stroke.strokeWidth = dp(0.8f)
            stroke.color = 0x55FFD60A
            c.drawRoundRect(dp(0.5f), dp(0.5f), w - dp(0.5f), h - dp(0.5f), r, r, stroke)
        }

        val tag = item.leagueLabel
        val sColor = sportColor(item.sport)
        val bw = badgePaint.measureText(tag) + dp(7.5f)
        val bh = dp(12.5f)
        val bx = dp(6.5f)
        val by = dp(5.5f)
        badgeRect.set(bx, by, bx + bw, by + bh)
        fill.color = if (f > 0.5f) sColor else alphaOf(sColor, 0.82f)
        c.drawRoundRect(badgeRect, bh / 2f, bh / 2f, fill)
        badgePaint.color = Color.WHITE
        c.drawText(tag, bx + dp(3.75f), by + bh / 2f - (badgePaint.ascent() + badgePaint.descent()) / 2f, badgePaint)

        // Right-side time (or LIVE indicator when spoilers are allowed)
        val rightX = w - dp(7f)
        val rawTime = item.displayTime(noSpoilers)
        val statusStr = if (tracked) "★ $rawTime" else rawTime
        val showLiveRed = item.isLive && !noSpoilers
        timePaint.color = when {
            f > 0.5f -> if (showLiveRed) 0xFFD70015.toInt() else 0xFF101218.toInt()
            showLiveRed -> 0xFFFF453A.toInt()
            tracked -> 0xFFFFD60A.toInt()
            else -> 0xE6FFFFFF.toInt()
        }
        c.drawText(statusStr, rightX, dp(15.5f), timePaint)

        // Matchup & broadcast
        val textLeft = bx + bw + dp(5.5f)
        val maxW = (rightX - timePaint.measureText(statusStr) - dp(6f) - textLeft).coerceAtLeast(dp(36f))
        titlePaint.color = lerpColor(Color.WHITE, 0xFF101218.toInt(), f)
        val mText = TextUtils.ellipsize(item.matchup, titlePaint, maxW, TextUtils.TruncateAt.END).toString()
        c.drawText(mText, textLeft, dp(15.2f), titlePaint)

        subPaint.color = lerpColor(0xA6FFFFFF.toInt(), 0xFF484C56.toInt(), f)
        val sText = TextUtils.ellipsize(item.displaySubtitle(noSpoilers), subPaint, w - dp(14f), TextUtils.TruncateAt.END).toString()
        c.drawText(sText, bx, dp(28.5f), subPaint)
    }
}

/** Compact card for an Upcoming UFC or Boxing event this month, displaying both Main Event & Co-Main Event. */
private class CombatCardView(
    ctx: Context,
    val item: CombatEvent,
    val tracked: Boolean
) : View(ctx) {
    var onClick: (() -> Unit)? = null
    private val focus = FocusAnim(this, 1.02f) { invalidate() }
    private val rect = RectF()
    private val badgeRect = RectF()
    private val fill = Paint(Paint.ANTI_ALIAS_FLAG)
    private val stroke = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE }
    private val evPaint = TextPaint(Paint.ANTI_ALIAS_FLAG).apply { typeface = Fonts.medium; textSize = ctx.dp(9.6f) }
    private val mainPaint = TextPaint(Paint.ANTI_ALIAS_FLAG).apply { typeface = Fonts.medium; textSize = ctx.dp(9.8f) }
    private val coPaint = TextPaint(Paint.ANTI_ALIAS_FLAG).apply { typeface = Fonts.regular; textSize = ctx.dp(9.0f) }
    private val badgePaint = TextPaint(Paint.ANTI_ALIAS_FLAG).apply { typeface = Fonts.medium; textSize = ctx.dp(6.8f) }
    private val datePaint = TextPaint(Paint.ANTI_ALIAS_FLAG).apply {
        typeface = Fonts.medium; textSize = ctx.dp(8.4f); textAlign = Paint.Align.RIGHT
    }

    init {
        isFocusable = true
        isClickable = true
        setOnClickListener { onClick?.invoke() }
    }

    override fun onFocusChanged(gained: Boolean, direction: Int, previous: Rect?) {
        super.onFocusChanged(gained, direction, previous)
        focus.set(gained)
    }

    override fun onDraw(c: Canvas) {
        val f = focus.amount
        val w = width.toFloat()
        val h = height.toFloat()
        val r = dp(9.5f)
        rect.set(0f, 0f, w, h)

        fill.color = lerpColor(if (tracked) 0x24FFFFFF else 0x13FFFFFF, 0xF2FFFFFF.toInt(), f)
        c.drawRoundRect(rect, r, r, fill)

        if (tracked && f < 0.5f) {
            stroke.strokeWidth = dp(0.8f)
            stroke.color = 0x55FFD60A
            c.drawRoundRect(dp(0.5f), dp(0.5f), w - dp(0.5f), h - dp(0.5f), r, r, stroke)
        }

        // Line 1: Promotion badge (UFC / BOXING) + Event Title + Date
        val isUfc = item.promotion == "UFC"
        val pColor = if (isUfc) 0xFFFF3B30.toInt() else 0xFFD4AF37.toInt()
        val bw = badgePaint.measureText(item.promotion) + dp(7f)
        val bh = dp(11.5f)
        val bx = dp(6.5f)
        val by = dp(4.5f)
        badgeRect.set(bx, by, bx + bw, by + bh)
        fill.color = pColor
        c.drawRoundRect(badgeRect, bh / 2f, bh / 2f, fill)
        badgePaint.color = if (isUfc) Color.WHITE else 0xFF101218.toInt()
        c.drawText(item.promotion, bx + dp(3.5f), by + bh / 2f - (badgePaint.ascent() + badgePaint.descent()) / 2f, badgePaint)

        val rightX = w - dp(6.5f)
        val dStr = if (tracked) "★ ${item.dateStr}" else item.dateStr
        datePaint.color = lerpColor(if (tracked) 0xFFFFD60A.toInt() else 0xCCFFFFFF.toInt(), 0xFF3A3E48.toInt(), f)
        c.drawText(dStr, rightX, dp(13.5f), datePaint)

        val evLeft = bx + bw + dp(5f)
        val evMaxW = (rightX - datePaint.measureText(dStr) - dp(5f) - evLeft).coerceAtLeast(dp(28f))
        evPaint.color = lerpColor(0xE6FFFFFF.toInt(), 0xFF101218.toInt(), f)
        val evTitle = TextUtils.ellipsize(item.eventTitle, evPaint, evMaxW, TextUtils.TruncateAt.END).toString()
        c.drawText(evTitle, evLeft, dp(13.5f), evPaint)

        // Line 2: MAIN EVENT badge + Fighters
        val mainTag = "MAIN"
        val mtw = badgePaint.measureText(mainTag) + dp(6.5f)
        val mty = dp(20f)
        badgeRect.set(bx, mty, bx + mtw, mty + dp(11.5f))
        fill.color = if (f > 0.5f) 0xFF1C1F26.toInt() else 0x34FFFFFF
        c.drawRoundRect(badgeRect, dp(3.5f), dp(3.5f), fill)
        badgePaint.color = Color.WHITE
        c.drawText(mainTag, bx + dp(3.25f), mty + dp(5.75f) - (badgePaint.ascent() + badgePaint.descent()) / 2f, badgePaint)

        mainPaint.color = lerpColor(Color.WHITE, 0xFF101218.toInt(), f)
        val mainLine = "${item.mainEvent} · ${item.mainWeight}"
        val mainEllip = TextUtils.ellipsize(mainLine, mainPaint, w - (bx + mtw + dp(11f)), TextUtils.TruncateAt.END).toString()
        c.drawText(mainEllip, bx + mtw + dp(5f), dp(29f), mainPaint)

        // Line 3: CO-MAIN badge + Fighters
        val coTag = "CO-MAIN"
        val ctw = badgePaint.measureText(coTag) + dp(6.5f)
        val cty = dp(35.5f)
        badgeRect.set(bx, cty, bx + ctw, cty + dp(11.5f))
        fill.color = if (f > 0.5f) 0xFF50545E.toInt() else 0x20FFFFFF
        c.drawRoundRect(badgeRect, dp(3.5f), dp(3.5f), fill)
        badgePaint.color = if (f > 0.5f) Color.WHITE else 0xD9FFFFFF.toInt()
        c.drawText(coTag, bx + dp(3.25f), cty + dp(5.75f) - (badgePaint.ascent() + badgePaint.descent()) / 2f, badgePaint)

        coPaint.color = lerpColor(0xBFFFFFFF.toInt(), 0xFF3A3E48.toInt(), f)
        val coLine = "${item.coMainEvent} · ${item.coMainWeight}"
        val coEllip = TextUtils.ellipsize(coLine, coPaint, w - (bx + ctw + dp(11f)), TextUtils.TruncateAt.END).toString()
        c.drawText(coEllip, bx + ctw + dp(5f), dp(44.5f), coPaint)
    }
}

// ---------- Compact Filter Chips & Header Pill Buttons ----------

private class FilterChip(ctx: Context, labelText: String) : View(ctx) {
    var label: String = labelText
        set(v) { if (field != v) { field = v; requestLayout(); invalidate() } }
    var chosen: Boolean = false
        set(v) { if (field != v) { field = v; invalidate() } }
    var onClick: (() -> Unit)? = null
    private val focus = FocusAnim(this, 1.06f) { invalidate() }
    private val rect = RectF()
    private val fill = Paint(Paint.ANTI_ALIAS_FLAG)
    private val stroke = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE }
    private val textPaint = TextPaint(Paint.ANTI_ALIAS_FLAG).apply {
        typeface = Fonts.medium; textSize = ctx.dp(8.6f); textAlign = Paint.Align.CENTER
    }

    init {
        isFocusable = true
        isClickable = true
        setOnClickListener { onClick?.invoke() }
    }

    override fun onMeasure(wSpec: Int, hSpec: Int) {
        val w = (textPaint.measureText(label) + dp(12f)).toInt()
        val h = MeasureSpec.getSize(hSpec).let { if (it > 0) it else dpi(18f) }
        setMeasuredDimension(w, h)
    }

    override fun onFocusChanged(gained: Boolean, direction: Int, previous: Rect?) {
        super.onFocusChanged(gained, direction, previous)
        focus.set(gained)
    }

    override fun onDraw(c: Canvas) {
        val f = focus.amount
        val r = height / 2f
        rect.set(0f, 0f, width.toFloat(), height.toFloat())
        val baseBg = if (chosen) 0x38FFFFFF else 0x14FFFFFF
        fill.color = lerpColor(baseBg, Color.WHITE, f)
        c.drawRoundRect(rect, r, r, fill)

        if (chosen && f < 0.5f) {
            stroke.strokeWidth = dp(0.9f)
            stroke.color = 0xB3FFFFFF.toInt()
            val hs = stroke.strokeWidth / 2f
            c.drawRoundRect(hs, hs, width - hs, height - hs, r - hs, r - hs, stroke)
        }
        textPaint.color = lerpColor(if (chosen) Color.WHITE else 0xA6FFFFFF.toInt(), 0xFF101218.toInt(), f)
        c.drawText(label, width / 2f, height / 2f - (textPaint.ascent() + textPaint.descent()) / 2f, textPaint)
    }
}

private class PillButton(ctx: Context, labelText: String) : View(ctx) {
    var label: String = labelText
        set(v) { if (field != v) { field = v; requestLayout(); invalidate() } }
    var active: Boolean = false
        set(v) { if (field != v) { field = v; invalidate() } }
    var onClick: (() -> Unit)? = null
    private val focus = FocusAnim(this, 1.06f) { invalidate() }
    private val rect = RectF()
    private val fill = Paint(Paint.ANTI_ALIAS_FLAG)
    private val stroke = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE }
    private val textPaint = TextPaint(Paint.ANTI_ALIAS_FLAG).apply {
        typeface = Fonts.medium; textSize = ctx.dp(9.4f); textAlign = Paint.Align.CENTER
    }

    init {
        isFocusable = true
        isClickable = true
        setOnClickListener { onClick?.invoke() }
    }

    override fun onMeasure(wSpec: Int, hSpec: Int) {
        val w = (textPaint.measureText(label) + dp(14f)).toInt()
        val h = MeasureSpec.getSize(hSpec).let { if (it > 0) it else dpi(21f) }
        setMeasuredDimension(w, h)
    }

    override fun onFocusChanged(gained: Boolean, direction: Int, previous: Rect?) {
        super.onFocusChanged(gained, direction, previous)
        focus.set(gained)
    }

    override fun onDraw(c: Canvas) {
        val f = focus.amount
        val r = height / 2f
        rect.set(0f, 0f, width.toFloat(), height.toFloat())
        val baseBg = if (active) 0x38FFD60A else 0x20FFFFFF
        fill.color = lerpColor(baseBg, Color.WHITE, f)
        c.drawRoundRect(rect, r, r, fill)

        stroke.strokeWidth = dp(0.8f)
        stroke.color = if (active && f < 0.5f) 0xB3FFD60A.toInt() else 0x36FFFFFF
        val hs = stroke.strokeWidth / 2f
        c.drawRoundRect(hs, hs, width - hs, height - hs, r - hs, r - hs, stroke)

        textPaint.color = lerpColor(if (active) 0xFFFFD60A.toInt() else 0xF2FFFFFF.toInt(), 0xFF101218.toInt(), f)
        c.drawText(label, width / 2f, height / 2f - (textPaint.ascent() + textPaint.descent()) / 2f, textPaint)
    }
}

// ---------- Glass Modal for Details, Multi-Sport Filter & Adding Custom Items ----------

class TrackerModal(private val act: HomeScreen) : FrameLayout(act.ctx) {
    var isOpen = false
        private set
    private val scrim = View(context).apply { setBackgroundColor(0x80000000.toInt()) }
    val panel = GlassBox(context, dp(28f)).apply {
        glass.tint = 0.10f; glass.dark = 0.34f; glass.sheen = 0.09f; shadowAlpha = 0.5f
    }
    private val contentBox = LinearLayout(context).apply {
        orientation = LinearLayout.VERTICAL
        clipChildren = false; clipToPadding = false
    }
    private var returnFocus: View? = null
    private var onChanged: (() -> Unit)? = null

    init {
        visibility = View.GONE
        clipChildren = false
        addView(scrim, LayoutParams(-1, -1))
        addView(panel, LayoutParams(dpi(540f), LayoutParams.WRAP_CONTENT, Gravity.CENTER))
        val pad = dpi(24f)
        contentBox.setPadding(pad, dpi(20f), pad, dpi(18f))
        panel.addView(contentBox, LayoutParams(-1, -2))
    }

    private fun showPanel(changed: () -> Unit) {
        returnFocus = act.root.findFocus()
        onChanged = changed
        isOpen = true
        act.setHomeBlockedForModal(true)
        visibility = View.VISIBLE
        alpha = 0f
        animate().alpha(1f).setDuration(180).withEndAction { invalidateDeep(panel) }.start()
        invalidateDeep(panel)
    }

    fun close() {
        if (!isOpen) return
        isOpen = false
        animate().cancel()
        visibility = View.GONE
        contentBox.removeAllViews()
        act.setHomeBlockedForModal(false)
        val rf = returnFocus
        returnFocus = null
        val cb = onChanged
        onChanged = null
        cb?.invoke()
        if (rf != null && rf.isAttachedToWindow) rf.requestFocus() else act.scheduleView.requestDefaultFocus()
    }

    /** Multi-select Sports Filter dialog: lets user select one or multiple sports for Today's Sports. */
    fun openSportsFilter(changed: () -> Unit) {
        contentBox.removeAllViews()
        contentBox.addView(modalTitle("Filter Today's Sports"))
        contentBox.addView(modalSub("Select one or multiple sports to display in Today's Sports."))

        val rows = ArrayList<Pair<String, RowView>>()
        fun refreshChecks() {
            val sel = act.prefs.selectedSports
            for ((sp, r) in rows) {
                val on = sp in sel
                r.valueView.text = if (on) "✓ Selected" else "Off"
                r.valueView.setTextColor(if (on) 0xFF7CE38B.toInt() else 0x80FFFFFF.toInt())
            }
        }

        for (sp in Prefs.ALL_SPORTS) {
            val r = RowView(context, sp).apply {
                onClick = {
                    val cur = act.prefs.selectedSports.toMutableSet()
                    if (sp in cur) {
                        if (cur.size > 1) cur.remove(sp)
                        else Toast.makeText(context, "Keep at least one sport selected", Toast.LENGTH_SHORT).show()
                    } else cur.add(sp)
                    act.prefs.selectedSports = cur
                    refreshChecks()
                }
            }
            rows.add(sp to r)
            contentBox.addView(r, LinearLayout.LayoutParams(-1, dpi(40f)).apply { topMargin = dpi(4f) })
        }
        refreshChecks()

        val spoilerRow = RowView(context, "No Spoilers (Hide live scores)").apply {
            fun sync() {
                valueView.text = if (act.prefs.noSpoilers) "On" else "Off"
            }
            sync()
            onClick = {
                act.prefs.noSpoilers = !act.prefs.noSpoilers
                sync()
            }
        }
        contentBox.addView(spoilerRow, LinearLayout.LayoutParams(-1, dpi(40f)).apply { topMargin = dpi(8f) })

        val btnRow = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER
            clipChildren = false; clipToPadding = false
        }
        val selectAll = RowView(context, "Select All").apply {
            titleView.gravity = Gravity.CENTER
            onClick = { act.prefs.selectedSports = Prefs.ALL_SPORTS.toSet(); refreshChecks() }
        }
        val done = RowView(context, "Done").apply {
            titleView.gravity = Gravity.CENTER
            titleView.typeface = Fonts.medium
            onClick = { close() }
        }
        btnRow.addView(selectAll, LinearLayout.LayoutParams(dpi(170f), dpi(42f)).apply { rightMargin = dpi(10f) })
        btnRow.addView(done, LinearLayout.LayoutParams(dpi(170f), dpi(42f)))
        contentBox.addView(btnRow, LinearLayout.LayoutParams(-1, -2).apply { topMargin = dpi(12f) })

        showPanel(changed)
        rows.firstOrNull()?.second?.requestFocus()
    }

    /** Detail & action modal for an Upcoming Movie Release. */
    fun openMovieDetail(m: MovieRelease, changed: () -> Unit) {
        contentBox.removeAllViews()
        contentBox.addView(modalTitle(m.title))
        contentBox.addView(modalSub("${m.badge} · Release Date: ${m.dateStr} (${m.relativeStr}) · ${m.platform}"))
        contentBox.addView(modalBody(m.details))

        val isTracked = m.id in act.prefs.trackedIds
        val trackBtn = RowView(context, if (isTracked) "★ Untrack Release" else "★ Track / Pin Release").apply {
            titleView.gravity = Gravity.CENTER
            onClick = {
                act.prefs.trackedIds = if (isTracked) act.prefs.trackedIds - m.id else act.prefs.trackedIds + m.id
                close()
            }
        }
        contentBox.addView(trackBtn, LinearLayout.LayoutParams(-1, dpi(42f)).apply { topMargin = dpi(12f) })

        if (m.pkg != null) {
            val openAppBtn = RowView(context, "Open Streaming App").apply {
                titleView.gravity = Gravity.CENTER
                onClick = { close(); act.launch(m.pkg, null) }
            }
            contentBox.addView(openAppBtn, LinearLayout.LayoutParams(-1, dpi(42f)).apply { topMargin = dpi(5f) })
        }
        if (m.isCustom) {
            val delBtn = RowView(context, "Remove Custom Item").apply {
                titleView.gravity = Gravity.CENTER
                onClick = { TrackerRepo.removeCustomItem(context, m.id); close() }
            }
            contentBox.addView(delBtn, LinearLayout.LayoutParams(-1, dpi(42f)).apply { topMargin = dpi(5f) })
        }
        val closeBtn = RowView(context, "Close").apply {
            titleView.gravity = Gravity.CENTER
            onClick = { close() }
        }
        contentBox.addView(closeBtn, LinearLayout.LayoutParams(-1, dpi(42f)).apply { topMargin = dpi(5f) })

        showPanel(changed)
        trackBtn.requestFocus()
    }

    /** Detail & action modal for a Sport Game today, respecting No Spoilers mode. */
    fun openSportDetail(s: SportGame, changed: () -> Unit) {
        contentBox.removeAllViews()
        val noSpoilers = act.prefs.noSpoilers
        contentBox.addView(modalTitle("${s.leagueLabel}: ${s.matchup}"))
        val subView = modalSub("${s.displayTime(noSpoilers)} · Broadcast: ${s.network}")
        val bodyView = modalBody(s.displayDetails(noSpoilers).ifEmpty { "${s.matchup} (${s.displaySubtitle(noSpoilers)})" })
        contentBox.addView(subView)
        contentBox.addView(bodyView)

        val isTracked = s.id in act.prefs.trackedIds
        val trackBtn = RowView(context, if (isTracked) "★ Untrack Matchup" else "★ Track / Pin Matchup").apply {
            titleView.gravity = Gravity.CENTER
            onClick = {
                act.prefs.trackedIds = if (isTracked) act.prefs.trackedIds - s.id else act.prefs.trackedIds + s.id
                close()
            }
        }
        contentBox.addView(trackBtn, LinearLayout.LayoutParams(-1, dpi(42f)).apply { topMargin = dpi(12f) })

        if (noSpoilers && s.timeOrStatus != s.scheduledTime) {
            val peekBtn = RowView(context, "Reveal Live Score / Status").apply {
                titleView.gravity = Gravity.CENTER
                onClick = {
                    subView.text = "${s.timeOrStatus} · Broadcast: ${s.network}"
                    bodyView.text = s.details
                    visibility = View.GONE
                    trackBtn.requestFocus()
                }
            }
            contentBox.addView(peekBtn, LinearLayout.LayoutParams(-1, dpi(42f)).apply { topMargin = dpi(5f) })
        }

        if (s.pkg != null) {
            val openBtn = RowView(context, "Open Broadcast App").apply {
                titleView.gravity = Gravity.CENTER
                onClick = { close(); act.launch(s.pkg, null) }
            }
            contentBox.addView(openBtn, LinearLayout.LayoutParams(-1, dpi(42f)).apply { topMargin = dpi(5f) })
        }
        if (s.isCustom) {
            val delBtn = RowView(context, "Remove Custom Item").apply {
                titleView.gravity = Gravity.CENTER
                onClick = { TrackerRepo.removeCustomItem(context, s.id); close() }
            }
            contentBox.addView(delBtn, LinearLayout.LayoutParams(-1, dpi(42f)).apply { topMargin = dpi(5f) })
        }
        val closeBtn = RowView(context, "Close").apply {
            titleView.gravity = Gravity.CENTER
            onClick = { close() }
        }
        contentBox.addView(closeBtn, LinearLayout.LayoutParams(-1, dpi(42f)).apply { topMargin = dpi(5f) })

        showPanel(changed)
        trackBtn.requestFocus()
    }

    /** Detail & action modal for an Upcoming UFC or Boxing Card (showing Main, Co-Main & Main Card bouts, respecting No Spoilers). */
    fun openCombatDetail(c: CombatEvent, changed: () -> Unit) {
        contentBox.removeAllViews()
        val noSpoilers = act.prefs.noSpoilers
        contentBox.addView(modalTitle("${c.promotion} · ${c.eventTitle}"))
        contentBox.addView(modalSub("${c.dateStr} · ${c.venue} · ${c.broadcast}"))

        val cardSummary = buildString {
            append("MAIN EVENT: ").append(c.mainEvent).append(" (").append(c.mainWeight).append(")\n")
            append("CO-MAIN EVENT: ").append(c.coMainEvent).append(" (").append(c.coMainWeight).append(")")
            if (c.undercard.isNotEmpty()) {
                append("\n\nMAIN CARD BOUTS:\n")
                c.undercard.forEach { append("• ").append(it).append("\n") }
            }
            if (!noSpoilers && c.resultSummary.isNotEmpty()) {
                append("\nSTATUS / RESULTS: ").append(c.resultSummary)
            } else if (noSpoilers) {
                append("\n(No Spoilers enabled: live fight results hidden)")
            }
        }.trim()
        contentBox.addView(modalBody(cardSummary))

        val isTracked = c.id in act.prefs.trackedIds
        val trackBtn = RowView(context, if (isTracked) "★ Untrack Fight Card" else "★ Track / Pin Fight Card").apply {
            titleView.gravity = Gravity.CENTER
            onClick = {
                act.prefs.trackedIds = if (isTracked) act.prefs.trackedIds - c.id else act.prefs.trackedIds + c.id
                close()
            }
        }
        contentBox.addView(trackBtn, LinearLayout.LayoutParams(-1, dpi(42f)).apply { topMargin = dpi(12f) })

        if (c.pkg != null) {
            val openBtn = RowView(context, "Open Broadcast App").apply {
                titleView.gravity = Gravity.CENTER
                onClick = { close(); act.launch(c.pkg, null) }
            }
            contentBox.addView(openBtn, LinearLayout.LayoutParams(-1, dpi(42f)).apply { topMargin = dpi(5f) })
        }
        if (c.isCustom) {
            val delBtn = RowView(context, "Remove Custom Item").apply {
                titleView.gravity = Gravity.CENTER
                onClick = { TrackerRepo.removeCustomItem(context, c.id); close() }
            }
            contentBox.addView(delBtn, LinearLayout.LayoutParams(-1, dpi(42f)).apply { topMargin = dpi(5f) })
        }
        val closeBtn = RowView(context, "Close").apply {
            titleView.gravity = Gravity.CENTER
            onClick = { close() }
        }
        contentBox.addView(closeBtn, LinearLayout.LayoutParams(-1, dpi(42f)).apply { topMargin = dpi(5f) })

        showPanel(changed)
        trackBtn.requestFocus()
    }

    /** Modal to add a custom item to track (Movie Blu-ray/Streaming, Sport Game, or UFC/Boxing Card). */
    fun openAddCustom(changed: () -> Unit) {
        contentBox.removeAllViews()
        contentBox.addView(modalTitle("Add Custom Item to Track"))
        contentBox.addView(modalSub("Choose a category, type the title/matchup and date/details, then Save."))

        val types = listOf("Movie (4K Blu-ray)", "Movie (Streaming)", "NBA Today", "NFL Today", "Soccer Today", "UFC Card", "Boxing Card")
        var typeIdx = 0
        var primaryText = ""
        var secondaryText = ""
        var editingPrimary = true

        val typeRow = RowView(context, "Category").apply {
            valueView.text = types[typeIdx]
            onClick = {
                typeIdx = (typeIdx + 1) % types.size
                valueView.text = types[typeIdx]
            }
        }
        val field1 = RowView(context, "Title / Main Event").apply {
            valueView.text = "Tap to edit"
            onClick = { editingPrimary = true; valueView.text = if (primaryText.isEmpty()) "|" else "$primaryText|" }
        }
        val field2 = RowView(context, "Date / Co-Main").apply {
            valueView.text = "Oct 31"
            onClick = { editingPrimary = false; valueView.text = if (secondaryText.isEmpty()) "|" else "$secondaryText|" }
        }

        fun updateFields() {
            field1.valueView.text = if (editingPrimary) "${primaryText}|" else primaryText.ifEmpty { "Enter title…" }
            field2.valueView.text = if (!editingPrimary) "${secondaryText}|" else secondaryText.ifEmpty { "Nov 15 / Co-Main…" }
        }
        updateFields()

        contentBox.addView(typeRow, LinearLayout.LayoutParams(-1, dpi(38f)).apply { topMargin = dpi(5f) })
        contentBox.addView(field1, LinearLayout.LayoutParams(-1, dpi(38f)).apply { topMargin = dpi(4f) })
        contentBox.addView(field2, LinearLayout.LayoutParams(-1, dpi(38f)).apply { topMargin = dpi(4f) })

        val kb = Keyboard(context, digits = true, capitalise = true) { k ->
            val cur = if (editingPrimary) primaryText else secondaryText
            val next = when (k) {
                Keyboard.DEL -> cur.dropLast(1)
                Keyboard.CLEAR -> ""
                " " -> if (cur.isNotEmpty() && !cur.endsWith(" ")) (cur + " ").take(42) else cur
                else -> (cur + k).take(42)
            }
            if (editingPrimary) primaryText = next else secondaryText = next
            updateFields()
        }
        contentBox.addView(kb, LinearLayout.LayoutParams(-2, -2).apply {
            gravity = Gravity.CENTER_HORIZONTAL
            topMargin = dpi(6f)
        })

        val btnRow = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER
            clipChildren = false; clipToPadding = false
        }
        val cancel = RowView(context, "Cancel").apply {
            id = View.generateViewId()
            titleView.gravity = Gravity.CENTER
            onClick = { close() }
        }
        val save = RowView(context, "Save & Track").apply {
            id = View.generateViewId()
            titleView.gravity = Gravity.CENTER
            titleView.typeface = Fonts.medium
            onClick = {
                val t = primaryText.trim()
                val s = secondaryText.trim()
                if (t.isEmpty()) {
                    Toast.makeText(context, "Enter a title or matchup first", Toast.LENGTH_SHORT).show()
                } else {
                    when (typeIdx) {
                        0 -> TrackerRepo.addCustomMovie(context, t, s.ifEmpty { "Upcoming" }, isBluray = true, platform = "4K UHD / Blu-ray")
                        1 -> TrackerRepo.addCustomMovie(context, t, s.ifEmpty { "Upcoming" }, isBluray = false, platform = "Streaming")
                        2 -> TrackerRepo.addCustomSport(context, "NBA", t, s.ifEmpty { "8:00 PM" }, "NBA")
                        3 -> TrackerRepo.addCustomSport(context, "NFL", t, s.ifEmpty { "4:25 PM" }, "NFL")
                        4 -> TrackerRepo.addCustomSport(context, "Soccer", t, s.ifEmpty { "3:00 PM" }, "Soccer")
                        5 -> TrackerRepo.addCustomCombat(context, "UFC", t, "This Month", t, s.ifEmpty { "Co-Main Event TBA" })
                        6 -> TrackerRepo.addCustomCombat(context, "BOXING", t, "This Month", t, s.ifEmpty { "Co-Main Event TBA" })
                    }
                    close()
                }
            }
        }
        btnRow.addView(cancel, LinearLayout.LayoutParams(dpi(170f), dpi(40f)).apply { rightMargin = dpi(10f) })
        btnRow.addView(save, LinearLayout.LayoutParams(dpi(170f), dpi(40f)))
        contentBox.addView(btnRow, LinearLayout.LayoutParams(-1, -2).apply { topMargin = dpi(8f) })
        kb.downTo(save)

        showPanel(changed)
        typeRow.requestFocus()
    }

    /** Liquid Glass modal shown when a newer release is found on github.com/blitzkrieg14/androidtv. */
    fun openAppUpdate(info: AppUpdater.UpdateInfo) {
        contentBox.removeAllViews()
        contentBox.addView(modalTitle("Update Available · ${info.tagName}"))
        val sub = modalSub("Installed: v${BuildConfig.VERSION_NAME}  →  New: v${info.versionName} (${AppUpdater.REPO})")
        contentBox.addView(sub)
        contentBox.addView(modalBody(info.notes.take(400)))

        var downloading = false
        val installBtn = RowView(context, "Download & Install (${info.apkName})").apply {
            titleView.gravity = Gravity.CENTER
            titleView.typeface = Fonts.medium
        }
        installBtn.onClick = {
            if (!downloading) {
                downloading = true
                installBtn.titleView.text = "Downloading update… 0%"
                AppUpdater.downloadAndInstall(
                    ctx = context,
                    info = info,
                    onProgress = { pct ->
                        installBtn.titleView.text = "Downloading update… $pct%"
                    },
                    onDone = {
                        downloading = false
                        installBtn.titleView.text = "Opening installer…"
                        close()
                    },
                    onError = { err ->
                        downloading = false
                        installBtn.titleView.text = "Retry Download & Install"
                        Toast.makeText(context, "Update failed: $err", Toast.LENGTH_LONG).show()
                    }
                )
            }
        }
        contentBox.addView(installBtn, LinearLayout.LayoutParams(-1, dpi(42f)).apply { topMargin = dpi(12f) })

        val laterBtn = RowView(context, "Later").apply {
            titleView.gravity = Gravity.CENTER
            onClick = { close() }
        }
        contentBox.addView(laterBtn, LinearLayout.LayoutParams(-1, dpi(42f)).apply { topMargin = dpi(5f) })

        showPanel {}
        installBtn.requestFocus()
    }

    private fun modalTitle(t: String) = TextView(context).apply {
        text = t; textSize = 18f; typeface = Fonts.medium; setTextColor(Color.WHITE)
    }

    private fun modalSub(t: String) = TextView(context).apply {
        text = t; textSize = 12f; typeface = Fonts.medium; setTextColor(0xB3FFFFFF.toInt())
        setPadding(0, dpi(2f), 0, dpi(5f))
    }

    private fun modalBody(t: String) = TextView(context).apply {
        text = t; textSize = 12.5f; typeface = Fonts.regular; setTextColor(0xE6FFFFFF.toInt())
        setLineSpacing(0f, 1.18f)
    }
}
