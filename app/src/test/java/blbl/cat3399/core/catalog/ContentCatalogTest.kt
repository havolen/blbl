package blbl.cat3399.core.catalog

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ContentCatalogTest {
    @Test
    fun matrixHasEighteenCategoriesInCoverageOrder() {
        assertEquals(18, ContentCategory.entries.size)
        assertEquals(
            listOf(6, 5, 4, 3),
            ContentCategory.entries.map { it.siteCount }.distinct().sortedDescending(),
        )
        assertEquals(7, ContentCategory.entries.count { it.siteCount == 6 })
        assertEquals(4, ContentCategory.entries.count { it.siteCount == 5 })
        assertEquals(1, ContentCategory.entries.count { it.siteCount == 4 })
        assertEquals(6, ContentCategory.entries.count { it.siteCount == 3 })
    }

    @Test
    fun canonicalNameIsAlwaysTheFirstAlias() {
        ContentCategory.entries.forEach { category ->
            assertEquals(category.aliasText.substringBefore(" / "), category.displayName)
        }
        assertEquals("动漫", ContentCategory.ANIME.displayName)
        assertEquals("动漫 / 动画 / 番剧", ContentCategory.ANIME.aliasText)
        assertEquals("知识 / 教育 / 公开课", ContentCategory.KNOWLEDGE.aliasText)
        assertEquals("少儿 / 亲子", ContentCategory.KIDS.aliasText)
    }

    @Test
    fun perCategorySiteMappingMatchesTheMatrix() {
        assertEquals(ContentCategory.entries.toSet().size, 18)
        assertEquals(allSites(), ContentCategory.TV.sites)
        assertEquals(allSites(), ContentCategory.GAME.sites)

        assertEquals(
            setOf(StreamingSite.IQIYI, StreamingSite.MANGO, StreamingSite.YOUKU, StreamingSite.TENCENT, StreamingSite.DOUYIN),
            ContentCategory.SHORT_DRAMA.sites,
        )
        assertFalse(StreamingSite.BILIBILI in ContentCategory.SHORT_DRAMA.sites)
        assertEquals(
            setOf(StreamingSite.IQIYI, StreamingSite.BILIBILI, StreamingSite.YOUKU, StreamingSite.TENCENT, StreamingSite.DOUYIN),
            ContentCategory.SPORTS.sites,
        )
        assertFalse(StreamingSite.MANGO in ContentCategory.SPORTS.sites)
        assertFalse(StreamingSite.TENCENT in ContentCategory.KNOWLEDGE.sites)
        assertFalse(StreamingSite.TENCENT in ContentCategory.LIVE.sites)
        assertEquals(
            setOf(StreamingSite.BILIBILI, StreamingSite.MANGO, StreamingSite.TENCENT, StreamingSite.DOUYIN),
            ContentCategory.MUSIC.sites,
        )
        assertEquals(
            setOf(StreamingSite.IQIYI, StreamingSite.BILIBILI, StreamingSite.TENCENT),
            ContentCategory.PODCAST.sites,
        )
        assertEquals(
            setOf(StreamingSite.BILIBILI, StreamingSite.YOUKU, StreamingSite.TENCENT),
            ContentCategory.TECH.sites,
        )
        listOf(ContentCategory.LIFE, ContentCategory.CAR, ContentCategory.ANIMAL, ContentCategory.FASHION).forEach {
            assertEquals(
                setOf(StreamingSite.BILIBILI, StreamingSite.YOUKU, StreamingSite.TENCENT, StreamingSite.DOUYIN),
                it.sites,
            )
        }
    }

    @Test
    fun siteSwitchOrderFollowsTheMatrixColumns() {
        assertEquals(
            listOf(
                StreamingSite.IQIYI,
                StreamingSite.BILIBILI,
                StreamingSite.MANGO,
                StreamingSite.YOUKU,
                StreamingSite.TENCENT,
                StreamingSite.DOUYIN,
            ),
            ContentCategory.TV.sitesInDisplayOrder(),
        )
        assertEquals(
            listOf(StreamingSite.IQIYI, StreamingSite.BILIBILI, StreamingSite.TENCENT),
            ContentCategory.PODCAST.sitesInDisplayOrder(),
        )
    }

    @Test
    fun onlyBilibiliIqiyiAndMangoAreIntegratedToday() {
        assertEquals(
            setOf(StreamingSite.BILIBILI, StreamingSite.IQIYI, StreamingSite.MANGO),
            StreamingSite.entries.filter { it.integrated }.toSet(),
        )
        assertEquals(
            listOf(StreamingSite.IQIYI, StreamingSite.BILIBILI, StreamingSite.MANGO),
            ContentCategory.TV.integratedSites,
        )
        assertTrue(StreamingSite.YOUKU.availableLabel == "暂未接入")
        assertTrue(StreamingSite.BILIBILI.availableLabel == "已接入")
    }

    @Test
    fun keyRoundTripAndUnknownKey() {
        assertEquals(ContentCategory.ANIME, ContentCategory.fromKey("anime"))
        assertNull(ContentCategory.fromKey("unknown"))
        assertEquals(ContentCategory.entries.map { it.key }.distinct().size, ContentCategory.entries.size)
    }

    private fun allSites(): Set<StreamingSite> = StreamingSite.entries.toSet()
}
