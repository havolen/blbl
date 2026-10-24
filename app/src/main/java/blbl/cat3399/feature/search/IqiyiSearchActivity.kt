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
import blbl.cat3399.feature.player.IqiyiWebPlayerActivity
import blbl.cat3399.feature.video.VideoCardAdapter
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch
import org.json.JSONArray
import org.json.JSONObject

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
        if (item.isShortVideo && item.videoPageUrl.isNotBlank()) {
            startActivity(
                IqiyiWebPlayerActivity.intent(
                    context = this,
                    url = item.videoPageUrl,
                    title = item.title,
                ),
            )
            return
        }
        resolving = true
        binding.progress.visibility = View.VISIBLE
        AppToast.show(this, getString(R.string.iqiyi_search_resolving))
        lifecycleScope.launch {
            try {
                val stream = playbackApi.resolveStream(item.tvId, item.videoId)
                if (stream == null) {
                    if (item.videoPageUrl.isNotBlank()) {
                        startActivity(
                            IqiyiWebPlayerActivity.intent(
                                context = this@IqiyiSearchActivity,
                                url = item.videoPageUrl,
                                title = item.title,
                            ),
                        )
                    } else {
                        AppToast.show(this@IqiyiSearchActivity, getString(R.string.iqiyi_search_play_failed))
                    }
                    return@launch
                }
                if (stream.isPreview && item.videoPageUrl.isNotBlank()) {
                    AppToast.show(
                        this@IqiyiSearchActivity,
                        getString(R.string.iqiyi_search_vip_web_fallback),
                    )
                    startActivity(
                        IqiyiWebPlayerActivity.intent(
                            context = this@IqiyiSearchActivity,
                            url = item.videoPageUrl,
                            title = item.title,
                            desktop = true,
                        ),
                    )
                    return@launch
                }
                val (playlistJson, playlistIndex) = nativePlaylist(item)
                startActivity(
                    IqiyiPlayerActivity.intent(
                        context = this@IqiyiSearchActivity,
                        streamUrl = stream.url,
                        title = item.title,
                        playlistJson = playlistJson,
                        playlistIndex = playlistIndex,
                    ),
                )
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (error: Throwable) {
                AppLog.w("IqiyiSearch", "resolve stream failed", error)
                if (item.videoPageUrl.isNotBlank()) {
                    startActivity(
                        IqiyiWebPlayerActivity.intent(
                            context = this@IqiyiSearchActivity,
                            url = item.videoPageUrl,
                            title = item.title,
                            desktop = true,
                        ),
                    )
                } else {
                    AppToast.show(this@IqiyiSearchActivity, getString(R.string.iqiyi_search_play_failed))
                }
            } finally {
                resolving = false
                binding.progress.visibility = View.GONE
            }
        }
    }

    /**
     * Snapshot the native (non-short) search results so the player can offer "next episode"
     * without re-querying the search API.
     */
    private fun nativePlaylist(current: IqiyiSearchItem): Pair<String?, Int> {
        val episodes = current.episodes.filter { it.tvId > 0L && it.videoId.isNotBlank() }
        if (episodes.size < 2) return null to 0
        val array = JSONArray()
        episodes.forEach { entry ->
            array.put(
                JSONObject()
                    .put("tvId", entry.tvId)
                    .put("videoId", entry.videoId)
                    .put("title", entry.title),
            )
        }
        val index = episodes.indexOfFirst { it.videoId == current.videoId }
        return array.toString() to index.coerceAtLeast(0)
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
