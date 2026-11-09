package dev.wonderdesk.shared

import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class TextStatisticsTest {
    @Test fun countsWhitespaceLinesAndOverlappingMatches() {
        assertEquals(TextStatistics(0, 0, 0), textStatistics(""))
        assertEquals(TextStatistics(9, 3, 2), textStatistics("one two\n三"))
        assertEquals(TextStatistics(1, 1, 1), textStatistics("😀"))
        assertEquals(2, countTextMatches("aaa", "aa"))
        assertEquals(0, countTextMatches("abc", ""))
        assertEquals(0, countTextMatches("abc", "abcd"))
        assertEquals(4, countTextMatches("aaaaa", "aa"))
    }
}
