package blbl.cat3399.core.catalog

import androidx.annotation.DrawableRes
import blbl.cat3399.R

/**
 * The six video sites tracked by the cross-site category matrix.
 *
 * [integrated] means this build can already open real content for the site. Sites that are not
 * integrated still expose a switch button so the navigation layout matches the product matrix.
 */
enum class StreamingSite(
    val key: String,
    val displayName: String,
    @DrawableRes val iconRes: Int,
    val integrated: Boolean,
) {
    IQIYI("iqiyi", "爱奇艺", R.drawable.ic_platform_iqiyi, true),
    BILIBILI("bilibili", "哔哩哔哩", R.drawable.ic_platform_bilibili, true),
    MANGO("mango", "芒果 TV", R.drawable.ic_platform_mango, true),
    YOUKU("youku", "优酷", R.drawable.ic_platform_youku, false),
    TENCENT("tencent", "腾讯视频", R.drawable.ic_platform_tencent, false),
    DOUYIN("douyin", "抖音", R.drawable.ic_platform_douyin, false),
    ;

    val availableLabel: String
        get() = if (integrated) "已接入" else "暂未接入"
}

/**
 * Category matrix shared by the left-hand category rail and the site navigation row.
 *
 * Naming rule: the first alias is the canonical name. Sites that use a different word for the same
 * category are folded into that first alias (for example 动漫 covers 动画 and 番剧).
 *
 * Entries are ordered by coverage (the number of sites that expose the category), matching the
 * product matrix.
 */
enum class ContentCategory(
    val key: String,
    private val aliases: List<String>,
    val sites: Set<StreamingSite>,
) {
    TV("tv", listOf("电视剧", "剧集"), setOf(StreamingSite.IQIYI, StreamingSite.BILIBILI, StreamingSite.MANGO, StreamingSite.YOUKU, StreamingSite.TENCENT, StreamingSite.DOUYIN)),
    MOVIE("movie", listOf("电影"), setOf(StreamingSite.IQIYI, StreamingSite.BILIBILI, StreamingSite.MANGO, StreamingSite.YOUKU, StreamingSite.TENCENT, StreamingSite.DOUYIN)),
    VARIETY("variety", listOf("综艺", "真人秀"), setOf(StreamingSite.IQIYI, StreamingSite.BILIBILI, StreamingSite.MANGO, StreamingSite.YOUKU, StreamingSite.TENCENT, StreamingSite.DOUYIN)),
    ANIME("anime", listOf("动漫", "动画", "番剧"), setOf(StreamingSite.IQIYI, StreamingSite.BILIBILI, StreamingSite.MANGO, StreamingSite.YOUKU, StreamingSite.TENCENT, StreamingSite.DOUYIN)),
    DOCUMENTARY("documentary", listOf("纪录片"), setOf(StreamingSite.IQIYI, StreamingSite.BILIBILI, StreamingSite.MANGO, StreamingSite.YOUKU, StreamingSite.TENCENT, StreamingSite.DOUYIN)),
    KIDS("kids", listOf("少儿", "亲子"), setOf(StreamingSite.IQIYI, StreamingSite.BILIBILI, StreamingSite.MANGO, StreamingSite.YOUKU, StreamingSite.TENCENT, StreamingSite.DOUYIN)),
    GAME("game", listOf("游戏"), setOf(StreamingSite.IQIYI, StreamingSite.BILIBILI, StreamingSite.MANGO, StreamingSite.YOUKU, StreamingSite.TENCENT, StreamingSite.DOUYIN)),
    SHORT_DRAMA("short_drama", listOf("短剧"), setOf(StreamingSite.IQIYI, StreamingSite.MANGO, StreamingSite.YOUKU, StreamingSite.TENCENT, StreamingSite.DOUYIN)),
    SPORTS("sports", listOf("体育"), setOf(StreamingSite.IQIYI, StreamingSite.BILIBILI, StreamingSite.YOUKU, StreamingSite.TENCENT, StreamingSite.DOUYIN)),
    KNOWLEDGE("knowledge", listOf("知识", "教育", "公开课"), setOf(StreamingSite.IQIYI, StreamingSite.BILIBILI, StreamingSite.MANGO, StreamingSite.YOUKU, StreamingSite.DOUYIN)),
    LIVE("live", listOf("直播"), setOf(StreamingSite.IQIYI, StreamingSite.BILIBILI, StreamingSite.MANGO, StreamingSite.YOUKU, StreamingSite.DOUYIN)),
    MUSIC("music", listOf("音乐"), setOf(StreamingSite.BILIBILI, StreamingSite.MANGO, StreamingSite.TENCENT, StreamingSite.DOUYIN)),
    PODCAST("podcast", listOf("播客", "中视频"), setOf(StreamingSite.IQIYI, StreamingSite.BILIBILI, StreamingSite.TENCENT)),
    TECH("tech", listOf("科技", "数码", "AI"), setOf(StreamingSite.BILIBILI, StreamingSite.YOUKU, StreamingSite.TENCENT)),
    LIFE("life", listOf("生活", "生活Vlog"), setOf(StreamingSite.BILIBILI, StreamingSite.YOUKU, StreamingSite.TENCENT, StreamingSite.DOUYIN)),
    CAR("car", listOf("汽车"), setOf(StreamingSite.BILIBILI, StreamingSite.YOUKU, StreamingSite.TENCENT, StreamingSite.DOUYIN)),
    ANIMAL("animal", listOf("动物", "宠物"), setOf(StreamingSite.BILIBILI, StreamingSite.YOUKU, StreamingSite.TENCENT, StreamingSite.DOUYIN)),
    FASHION("fashion", listOf("时尚", "美妆"), setOf(StreamingSite.BILIBILI, StreamingSite.YOUKU, StreamingSite.TENCENT, StreamingSite.DOUYIN)),
    ;

    /** Canonical (first-alias) name used by the category rail and site switches. */
    val displayName: String
        get() = aliases.first()

    /** All names folded into this category, e.g. "动漫 / 动画 / 番剧". */
    val aliasText: String
        get() = aliases.joinToString(" / ")

    val siteCount: Int
        get() = sites.size

    val integratedSites: List<StreamingSite>
        get() = sitesInDisplayOrder().filter { it.integrated }

    /** Sites in the matrix column order, filtered to this category. */
    fun sitesInDisplayOrder(): List<StreamingSite> = StreamingSite.entries.filter { it in sites }

    companion object {
        fun fromKey(key: String?): ContentCategory? = entries.firstOrNull { it.key == key }
    }
}
