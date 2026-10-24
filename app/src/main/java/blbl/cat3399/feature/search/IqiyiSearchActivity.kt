package blbl.cat3399.feature.search

import android.os.Bundle
import android.view.View
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.GridLayoutManager
import blbl.cat3399.R
import blbl.cat3399.core.account.IqiyiSessionStore
import blbl.cat3399.core.api.IqiyiApi
import blbl.cat3399.core.api.IqiyiSearchApi
import blbl.cat3399.core.api.IqiyiSearchItem
import blbl.cat3399.core.log.AppLog
import blbl.cat3399.core.model.VideoCard
import blbl.cat3399.core.net.BiliClient
import blbl.cat3399.core.ui.AppToast
import blbl.cat3399.core.ui.BaseActivity
import blbl.cat3399.core.ui.Immersive
import blbl.cat3399.databinding.ActivityIqiyiSearchBinding
import blbl.cat3399.feature.player.IqiyiPlayerActivity
import blbl.cat3399.feature.video.VideoCardAdapter
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch

/** Native grid for iQiyi web search results, using the same cards as Bilibili/Mango. */
class IqiyiSearchActivity : BaseActivity() {
    private lateinit var binding: ActivityIqiyiSearchBinding
    private lateinit var adapter: VideoCardAdapter
    private val sessionStore by lazy { IqiyiSessionStore(applicationContext) }
    private val searchApi by lazy { IqiyiSearchApi(sessionStore) }
    private val playbackApi by lazy { IqiyiApi(sessionStore.cookies) }
    private val items = ArrayList<IqiyiSearchItem>()
    private var query: String = ""
    private var resolving: Boolean = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityIqiyiSearchBinding.inflate(layoutInflater)
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
            onClick = { _, position -> openItem(position) },
            stableIdKey = { it.bvid },
        )
        binding.recycler.adapter = adapter
        binding.recycler.layoutManager = GridLayoutManager(this, spanCount())
        binding.btnBack.requestFocus()
        load()
    }

    private fun spanCount(): Int {
        val target = (300 * resources.displayMetrics.density).toInt().coerceAtLeast(1)
        return (resources.displayMetrics.widthPixels / target).coerceIn(2, 6)
    }

    private fun load() {
        if (query.isBlank()) return
        binding.progress.visibility = View.VISIBLE
        binding.tvEmpty.visibility = View.GONE
        lifecycleScope.launch {
            try {
                val result = searchApi.search(query)
                items.clear()
                items.addAll(result)
                adapter.submit(items.map { it.toVideoCard() })
                if (items.isEmpty()) {
                    binding.tvEmpty.setText(R.string.iqiyi_search_empty)
                    binding.tvEmpty.visibility = View.VISIBLE
                    binding.tvEmpty.requestFocus()
                } else {
                    focusFirstItem()
                }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (error: Throwable) {
                AppLog.w("IqiyiSearch", "load failed", error)
                binding.tvEmpty.setText(R.string.iqiyi_search_loading_failed)
                binding.tvEmpty.visibility = View.VISIBLE
                binding.tvEmpty.requestFocus()
            } finally {
                binding.progress.visibility = View.GONE
            }
        }
    }

    private fun openItem(position: Int) {
        val item = items.getOrNull(position) ?: return
        if (resolving) return
        resolving = true
        binding.progress.visibility = View.VISIBLE
        AppToast.show(this, getString(R.string.iqiyi_search_resolving))
        lifecycleScope.launch {
            try {
                val stream = playbackApi.resolveStream(item.tvId, item.videoId)
                if (stream == null) {
                    AppToast.show(this@IqiyiSearchActivity, getString(R.string.iqiyi_search_play_failed))
                    return@launch
                }
                startActivity(
                    IqiyiPlayerActivity.intent(
                        context = this@IqiyiSearchActivity,
                        streamUrl = stream.url,
                        title = item.title,
                    ),
                )
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (error: Throwable) {
                AppLog.w("IqiyiSearch", "resolve stream failed", error)
                AppToast.show(this@IqiyiSearchActivity, getString(R.string.iqiyi_search_play_failed))
            } finally {
                resolving = false
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

    private fun IqiyiSearchItem.toVideoCard(): VideoCard = VideoCard(
        bvid = "iqiyi:$tvId:$videoId",
        cid = null,
        title = title,
        coverUrl = coverUrl,
        durationSec = durationSec,
        ownerName = "爱奇艺",
        ownerFace = null,
        view = null,
        danmaku = null,
        pubDate = null,
        pubDateText = subtitle.ifBlank { "爱奇艺" },
    )

    companion object {
        const val EXTRA_QUERY = "iqiyi_search_query"
    }
}
