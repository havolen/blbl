package blbl.cat3399.feature.player

import android.content.Context
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.text.TextUtils
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.view.ViewOutlineProvider
import android.widget.FrameLayout
import android.widget.HorizontalScrollView
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.TextView
import androidx.recyclerview.widget.GridLayoutManager
import androidx.recyclerview.widget.RecyclerView
import blbl.cat3399.R
import blbl.cat3399.core.image.ImageLoader
import blbl.cat3399.core.image.ImageUrl
import blbl.cat3399.core.ui.ThemeColor
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import org.json.JSONArray
import org.json.JSONObject

data class MangoEpisode(
    val videoId: String,
    val clipId: String,
    val label: String,
    val title: String,
    val active: Boolean = false,
    val imageUrl: String = "",
    val date: String = "",
    val seriesLabel: String = "",
) {
    val pageUrl: String
        get() = "https://www.mgtv.com/b/$clipId/$videoId.html"

    val displayLabel: String
        get() {
            val clean = label.replace(Regex("\\s*(VIP|SVIP|预).*$"), "").trim().ifBlank { label }
            return when {
                clean.startsWith("第") -> clean
                clean.isNotEmpty() && clean.all { it.isDigit() } -> "第${clean}集"
                else -> clean
            }
        }
}

object MangoEpisodes {
    fun parse(json: String?): List<MangoEpisode> {
        if (json.isNullOrBlank()) return emptyList()
        val root = runCatching { JSONObject(json) }.getOrNull() ?: return emptyList()
        val items = root.optJSONArray("items") ?: return emptyList()
        val result = ArrayList<MangoEpisode>(items.length())
        for (i in 0 until items.length()) {
            val item = items.optJSONObject(i) ?: continue
            val videoId = item.optString("vid").trim()
            val clipId = item.optString("cid").trim()
            if (videoId.isBlank() || clipId.isBlank()) continue
            result.add(
                MangoEpisode(
                    videoId = videoId,
                    clipId = clipId,
                    label = item.optString("label").trim(),
                    title = item.optString("title").trim(),
                    active = item.optBoolean("active", false),
                    imageUrl = item.optString("imageUrl"),
                    date = item.optString("date"),
                    seriesLabel = item.optString("seriesLabel"),
                ),
            )
        }
        return result
    }

    fun toJson(episodes: List<MangoEpisode>): String {
        val items = JSONArray()
        episodes.forEach { episode ->
            items.put(
                JSONObject()
                    .put("vid", episode.videoId)
                    .put("cid", episode.clipId)
                    .put("label", episode.label)
                    .put("title", episode.title)
                    .put("active", episode.active)
                    .put("imageUrl", episode.imageUrl)
                    .put("date", episode.date)
                    .put("seriesLabel", episode.seriesLabel),
            )
        }
        return JSONObject().put("items", items).toString()
    }
}

/** Season/part-aware episode grid shown over the video. */
class EpisodePickerOverlay(
    private val context: Context,
    private val parent: ViewGroup,
    private val loadEpisodes: suspend (MangoSeason, MangoPart) -> List<MangoEpisode>,
    private val onSelect: (MangoEpisode) -> Unit,
) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private var loadJob: Job? = null
    private val root = FrameLayout(context).apply {
        setBackgroundColor(Color.argb(236, 0, 0, 0))
        isClickable = true
        visibility = View.GONE
    }
    private val card = LinearLayout(context).apply {
        orientation = LinearLayout.VERTICAL
        setPadding(dp(20), dp(18), dp(20), dp(18))
        background = GradientDrawable().apply {
            shape = GradientDrawable.RECTANGLE
            cornerRadius = dp(20).toFloat()
            setColor(Color.argb(236, 24, 24, 26))
            setStroke(dp(1), Color.argb(48, 255, 255, 255))
        }
    }
    private val seasonRow = LinearLayout(context).apply {
        orientation = LinearLayout.HORIZONTAL
        gravity = Gravity.CENTER_VERTICAL
    }
    private val partRow = LinearLayout(context).apply {
        orientation = LinearLayout.HORIZONTAL
        gravity = Gravity.CENTER_VERTICAL
    }
    private val progress = ProgressBar(context).apply {
        visibility = View.GONE
        indeterminateTintList = android.content.res.ColorStateList.valueOf(
            ThemeColor.resolve(context, R.attr.blblAccent, R.color.blbl_blue),
        )
    }
    private val recycler = RecyclerView(context).apply {
        layoutManager = GridLayoutManager(context, 4)
        setPadding(0, dp(6), 0, dp(4))
        clipToPadding = false
    }
    private val adapter = Adapter()
    private var catalog: MangoCatalog? = null
    private var selectedSeason: MangoSeason? = null
    private var selectedPart: MangoPart? = null
    private var activeVideoId: String? = null
    private var activeClipId: String? = null

    init {
        card.addView(
            TextView(context).apply {
                text = "播放列表"
                textSize = 24f
                typeface = Typeface.DEFAULT_BOLD
                setTextColor(Color.WHITE)
            },
            LinearLayout.LayoutParams(-1, -2),
        )
        card.addView(
            TextView(context).apply {
                text = "按返回键关闭"
                textSize = 12f
                setTextColor(Color.argb(150, 255, 255, 255))
                setPadding(0, dp(2), 0, dp(8))
            },
            LinearLayout.LayoutParams(-1, -2),
        )
        card.addView(scrollRow(seasonRow), LinearLayout.LayoutParams(-1, -2))
        card.addView(scrollRow(partRow), LinearLayout.LayoutParams(-1, -2))
        card.addView(progress, LinearLayout.LayoutParams(-1, dp(3)))
        recycler.adapter = adapter
        card.addView(recycler, LinearLayout.LayoutParams(-1, 0, 1f))
        root.addView(
            card,
            FrameLayout.LayoutParams(-1, -1).apply {
                marginStart = dp(40)
                topMargin = dp(24)
                marginEnd = dp(40)
                bottomMargin = dp(24)
            },
        )
        parent.addView(root, ViewGroup.LayoutParams(-1, -1))
    }

    val isShowing: Boolean get() = root.visibility == View.VISIBLE

    fun show(catalog: MangoCatalog, activeVideoId: String?, activeClipId: String?) {
        if (catalog.seasons.isEmpty()) return
        this.catalog = catalog
        this.activeVideoId = activeVideoId
        this.activeClipId = activeClipId
        root.visibility = View.VISIBLE
        root.bringToFront()
        seasonRow.removeAllViews()
        seasonRow.visibility = if (catalog.seasons.size > 1) View.VISIBLE else View.GONE
        catalog.seasons.forEach { season ->
            seasonRow.addView(seasonChip(season))
        }
        val season = catalog.seasons.firstOrNull {
            it.clipId == catalog.currentSeasonClipId ||
                it.parts.any { part -> part.clipId == activeClipId }
        } ?: catalog.seasons.first()
        selectSeason(season, focusGrid = true)
    }

    fun dismiss() {
        loadJob?.cancel()
        root.visibility = View.GONE
    }

    fun release() {
        dismiss()
        scope.cancel()
    }

    private fun selectSeason(season: MangoSeason, focusGrid: Boolean) {
        selectedSeason = season
        for (i in 0 until seasonRow.childCount) {
            val chip = seasonRow.getChildAt(i) as? TextView ?: continue
            chip.isSelected = i == catalog?.seasons?.indexOf(season)
            styleChip(chip)
        }
        partRow.removeAllViews()
        partRow.visibility = if (season.parts.size > 1) View.VISIBLE else View.GONE
        season.parts.forEach { part -> partRow.addView(partChip(part)) }
        val part = season.parts.firstOrNull { !it.extras && (it.clipId == activeClipId || it.name == "正片") }
            ?: season.parts.firstOrNull { !it.extras }
            ?: season.parts.first()
        selectPart(part, focusGrid)
    }

    private fun selectPart(part: MangoPart, focusGrid: Boolean) {
        selectedPart = part
        blbl.cat3399.core.log.AppLog.i(
            "MangoPlaylist",
            "select season=${selectedSeason?.name.orEmpty()} part=${part.name} extras=${part.extras}",
        )
        for (i in 0 until partRow.childCount) {
            val chip = partRow.getChildAt(i) as? TextView ?: continue
            chip.isSelected = i == selectedSeason?.parts?.indexOf(part)
            styleChip(chip)
        }
        loadJob?.cancel()
        adapter.submit(emptyList(), null)
        progress.visibility = View.VISIBLE
        val season = selectedSeason ?: return
        loadJob = scope.launch {
            val items = runCatching { loadEpisodes(season, part) }.getOrDefault(emptyList())
            progress.visibility = View.GONE
            adapter.submit(items, activeVideoId)
            if (focusGrid) {
                focusEpisode(items)
            }
        }
    }

    private fun focusEpisode(items: List<MangoEpisode>) {
        val index = items.indexOfFirst { it.videoId == activeVideoId }.takeIf { it >= 0 } ?: 0
        if (items.isEmpty()) return
        recycler.post {
            recycler.scrollToPosition(index)
            recycler.post {
                recycler.findViewHolderForAdapterPosition(index)?.itemView?.requestFocus()
            }
        }
    }

    private fun seasonChip(season: MangoSeason): TextView = chip(season.name) {
        selectSeason(season, focusGrid = true)
    }

    private fun partChip(part: MangoPart): TextView = chip(part.name) {
        selectPart(part, focusGrid = true)
    }

    private fun chip(label: String, onClick: () -> Unit): TextView = TextView(context).apply {
        text = label
        textSize = 14f
        gravity = Gravity.CENTER
        setTextColor(Color.WHITE)
        setPadding(dp(14), dp(7), dp(14), dp(7))
        isFocusable = true
        isFocusableInTouchMode = true
        stateListAnimator = android.animation.AnimatorInflater.loadStateListAnimator(
            context,
            blbl.cat3399.R.animator.blbl_focus_scale,
        )
        setOnClickListener { onClick() }
        setOnFocusChangeListener { _, _ -> styleChip(this) }
        layoutParams = LinearLayout.LayoutParams(-2, -2).apply { marginEnd = dp(8); topMargin = dp(4) }
        styleChip(this)
    }

    private fun styleChip(chip: TextView) {
        val focused = chip.hasFocus()
        val selected = chip.isSelected
        val focusColor = ThemeColor.resolve(context, R.attr.blblNavigationFocusBackground, R.color.blbl_blue)
        val selectedColor = ThemeColor.resolve(context, R.attr.blblAccent, R.color.blbl_blue)
        val selectedFill = ThemeColor.resolve(context, R.attr.blblNavigationSelectedBackground, R.color.blbl_accent_blue_container)
        val focusText = ThemeColor.resolve(context, R.attr.blblNavigationFocusText, R.color.blbl_text)
        val selectedText = ThemeColor.resolve(context, R.attr.blblNavigationSelectedText, R.color.blbl_text)
        chip.setTextColor(
            when {
                focused -> focusText
                selected -> selectedText
                else -> Color.WHITE
            },
        )
        chip.background = GradientDrawable().apply {
            shape = GradientDrawable.RECTANGLE
            cornerRadius = dp(24).toFloat()
            setColor(
                when {
                    focused -> focusColor
                    selected -> selectedFill
                    else -> Color.argb(160, 44, 44, 46)
                },
            )
            when {
                focused -> setStroke(dp(2), focusColor)
                selected -> setStroke(dp(1), selectedColor)
                else -> setStroke(dp(1), Color.argb(40, 255, 255, 255))
            }
        }
    }

    private fun scrollRow(child: View): HorizontalScrollView = HorizontalScrollView(context).apply {
        isHorizontalScrollBarEnabled = false
        clipToPadding = false
        addView(child, ViewGroup.LayoutParams(-2, -2))
    }

    private inner class Adapter : RecyclerView.Adapter<Adapter.Vh>() {
        private val items = ArrayList<MangoEpisode>()
        private var activeVideoId: String? = null

        fun submit(list: List<MangoEpisode>, active: String?) {
            items.clear()
            items.addAll(list)
            activeVideoId = active
            notifyDataSetChanged()
        }

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): Vh {
            val cardView = LinearLayout(context).apply {
                orientation = LinearLayout.VERTICAL
                setPadding(dp(8), dp(8), dp(8), dp(8))
                isFocusable = true
                isFocusableInTouchMode = true
                stateListAnimator = android.animation.AnimatorInflater.loadStateListAnimator(
                    context,
                    blbl.cat3399.R.animator.blbl_focus_scale,
                )
                layoutParams = RecyclerView.LayoutParams(-1, -2).apply {
                    setMargins(dp(5), dp(5), dp(5), dp(5))
                }
            }
            val image = ImageView(context).apply {
                scaleType = ImageView.ScaleType.CENTER_CROP
                layoutParams = LinearLayout.LayoutParams(-1, dp(74))
                background = GradientDrawable().apply {
                    cornerRadius = dp(10).toFloat()
                    setColor(Color.argb(90, 255, 255, 255))
                }
                clipToOutline = true
                outlineProvider = object : ViewOutlineProvider() {
                    override fun getOutline(view: View, outline: android.graphics.Outline) {
                        outline.setRoundRect(0, 0, view.width, view.height, dp(10).toFloat())
                    }
                }
            }
            val title = TextView(context).apply {
                textSize = 13f
                setTextColor(Color.WHITE)
                maxLines = 2
                ellipsize = TextUtils.TruncateAt.END
                setPadding(0, dp(6), 0, 0)
            }
            val meta = TextView(context).apply {
                textSize = 11f
                setTextColor(Color.argb(160, 255, 255, 255))
                maxLines = 1
                setPadding(0, dp(2), 0, 0)
            }
            cardView.addView(image)
            cardView.addView(title)
            cardView.addView(meta)
            return Vh(cardView, image, title, meta)
        }

        override fun onBindViewHolder(holder: Vh, position: Int) {
            val item = items[position]
            holder.bind(item, item.videoId == activeVideoId || item.active)
        }

        override fun getItemCount(): Int = items.size

        inner class Vh(
            private val cardView: LinearLayout,
            private val image: ImageView,
            private val title: TextView,
            private val meta: TextView,
        ) : RecyclerView.ViewHolder(cardView) {
            fun bind(item: MangoEpisode, active: Boolean) {
                title.text = item.title
                meta.text = listOf(item.seriesLabel, item.date).filter { it.isNotBlank() }.joinToString(" · ")
                if (item.imageUrl.isNotBlank()) {
                    ImageLoader.loadInto(image, ImageUrl.cover(item.imageUrl))
                } else {
                    image.setImageDrawable(null)
                }
                cardView.background = cardBackground(active, cardView.hasFocus())
                cardView.setOnFocusChangeListener { _, focused -> cardView.background = cardBackground(active, focused) }
                cardView.setOnClickListener { onSelect(item) }
            }

            private fun cardBackground(active: Boolean, focused: Boolean): GradientDrawable =
                GradientDrawable().apply {
                    shape = GradientDrawable.RECTANGLE
                    cornerRadius = dp(12).toFloat()
                    setColor(if (active) Color.argb(220, 60, 36, 48) else Color.argb(190, 44, 44, 46))
                    val focusColor = ThemeColor.resolve(context, R.attr.blblNavigationFocusBackground, R.color.blbl_blue)
                    val selectedColor = ThemeColor.resolve(context, R.attr.blblAccent, R.color.blbl_blue)
                    when {
                        focused -> setStroke(dp(2), focusColor)
                        active -> setStroke(dp(2), selectedColor)
                        else -> setStroke(dp(1), Color.argb(40, 255, 255, 255))
                    }
                }
        }
    }

    private fun dp(value: Int): Int = (value * context.resources.displayMetrics.density).toInt()
}
