package blbl.cat3399.feature.search

import android.content.Intent
import android.os.Bundle
import android.view.View
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.GridLayoutManager
import blbl.cat3399.R
import blbl.cat3399.core.api.MangoSearchApi
import blbl.cat3399.core.api.MangoSearchItem
import blbl.cat3399.core.log.AppLog
import blbl.cat3399.core.model.VideoCard
import blbl.cat3399.core.net.BiliClient
import blbl.cat3399.core.ui.BaseActivity
import blbl.cat3399.core.ui.Immersive
import blbl.cat3399.databinding.ActivityMangoSearchBinding
import blbl.cat3399.feature.player.MangoVideoActivity
import blbl.cat3399.feature.video.VideoCardAdapter
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch

/** Native grid for Mango's PC search results, opened from the two-key search page. */
class MangoSearchActivity : BaseActivity() {
    private lateinit var binding: ActivityMangoSearchBinding
    private lateinit var adapter: VideoCardAdapter
    private val api by lazy { MangoSearchApi(this) }
    private val items = ArrayList<MangoSearchItem>()
    private var query: String = ""
    private var nextPage: Int = 1
    private var hasMore: Boolean = true
    private var loading: Boolean = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityMangoSearchBinding.inflate(layoutInflater)
        setContentView(binding.root)
        Immersive.apply(this, BiliClient.prefs.fullscreenEnabled)

        query = intent.getStringExtra(EXTRA_QUERY)?.trim().orEmpty()
        if (query.isBlank()) {
            finish()
            return
        }
        binding.tvQuery.text = "“$query”"
        binding.btnBack.setOnClickListener { finish() }
        adapter = VideoCardAdapter(
            onClick = { card, _ -> openMangoItem(card) },
            stableIdKey = { it.bvid },
        )
        binding.recycler.adapter = adapter
        binding.recycler.layoutManager = GridLayoutManager(this, spanCount())
        binding.recycler.addOnScrollListener(
            object : androidx.recyclerview.widget.RecyclerView.OnScrollListener() {
                override fun onScrolled(recyclerView: androidx.recyclerview.widget.RecyclerView, dx: Int, dy: Int) {
                    if (dy <= 0 || loading || !hasMore) return
                    val layout = recyclerView.layoutManager as? GridLayoutManager ?: return
                    if (layout.findLastVisibleItemPosition() >= adapter.itemCount - 4) load(reset = false)
                }
            },
        )
        binding.btnBack.requestFocus()
        load(reset = true)
    }

    private fun spanCount(): Int {
        val target = (300 * resources.displayMetrics.density).toInt().coerceAtLeast(1)
        return (resources.displayMetrics.widthPixels / target).coerceIn(2, 6)
    }

    private fun load(reset: Boolean) {
        if (loading || (!reset && !hasMore)) return
        loading = true
        val page = if (reset) 1 else nextPage
        if (items.isEmpty()) binding.progress.visibility = View.VISIBLE
        binding.tvEmpty.visibility = View.GONE
        lifecycleScope.launch {
            try {
                val result = api.search(query, page)
                if (reset) items.clear()
                val known = items.mapTo(HashSet()) { it.pageUrl }
                items.addAll(result.items.filter { it.pageUrl.isNotBlank() && known.add(it.pageUrl) })
                hasMore = result.hasMore
                nextPage = page + 1
                adapter.submit(items.map { it.toVideoCard() })
                if (items.isEmpty() && !hasMore) {
                    binding.tvEmpty.setText(R.string.mango_search_empty)
                    binding.tvEmpty.visibility = View.VISIBLE
                    binding.tvEmpty.requestFocus()
                } else if (reset) {
                    focusFirstItem()
                }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (error: Throwable) {
                AppLog.w("MangoSearch", "load failed page=$page", error)
                if (items.isEmpty()) {
                    binding.tvEmpty.setText(R.string.mango_search_loading_failed)
                    binding.tvEmpty.visibility = View.VISIBLE
                    binding.tvEmpty.requestFocus()
                }
            } finally {
                loading = false
                binding.progress.visibility = View.GONE
            }
        }
    }

    private fun focusFirstItem() {
        binding.recycler.post {
            binding.recycler.scrollToPosition(0)
            binding.recycler.post {
                binding.recycler.findViewHolderForAdapterPosition(0)?.itemView?.requestFocus()
            }
        }
    }

    private fun openMangoItem(card: VideoCard) {
        val url = card.bvid.removePrefix(MANGO_PREFIX).takeIf { it.isNotBlank() } ?: return
        startActivity(
            Intent(this, MangoVideoActivity::class.java)
                .putExtra(MangoVideoActivity.EXTRA_URL, url),
        )
    }

    private fun MangoSearchItem.toVideoCard(): VideoCard = VideoCard(
        bvid = "$MANGO_PREFIX$pageUrl",
        cid = null,
        title = title,
        coverUrl = coverUrl,
        durationSec = durationSec,
        ownerName = "芒果 TV",
        ownerFace = null,
        view = null,
        danmaku = null,
        pubDate = null,
        pubDateText = subtitle.ifBlank { "芒果 TV" },
    )

    companion object {
        const val EXTRA_QUERY = "mango_search_query"
        private const val MANGO_PREFIX = "mgtv:"
    }
}
