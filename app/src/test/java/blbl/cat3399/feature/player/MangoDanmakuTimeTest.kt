package blbl.cat3399.feature.player

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class MangoDanmakuTimeTest {
    @Test
    fun convertsGlobalSecondAndMillisecondTimesToPlaybackMilliseconds() {
        assertEquals(75_000L, MangoDanmakuTime.toMillis(75.0, durationMs = 600_000L, intervalSec = 60, index = 1))
        assertEquals(75_000L, MangoDanmakuTime.toMillis(75_000.0, durationMs = 600_000L, intervalSec = 60, index = 1))
    }

    @Test
    fun offsetsChunkLocalSecondAndMillisecondTimes() {
        assertEquals(75_000L, MangoDanmakuTime.toMillis(15.0, durationMs = 600_000L, intervalSec = 60, index = 1))
        assertEquals(75_000L, MangoDanmakuTime.toMillis(15_000.0, durationMs = 600_000L, intervalSec = 60, index = 1))
    }

    @Test
    fun rejectsInvalidAndOutOfDurationTimes() {
        assertNull(MangoDanmakuTime.toMillis(-1.0, durationMs = 600_000L, intervalSec = 60, index = 1))
        assertNull(MangoDanmakuTime.toMillis(Double.NaN, durationMs = 600_000L, intervalSec = 60, index = 1))
        assertNull(MangoDanmakuTime.toMillis(700.0, durationMs = 600_000L, intervalSec = 60, index = 11))
    }
}
