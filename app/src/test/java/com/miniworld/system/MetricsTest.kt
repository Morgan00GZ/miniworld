package com.miniworld.system

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * 自检面板背后的环形缓冲与分位数。
 *
 * 这里唯一容易错的地方是「样本不足时还算 P95」——三五个样本插值出来的数字
 * 看着精确、其实没意义，所以约定不足一窗就退化成最大值。
 */
class MetricsTest {

    @Before
    fun clear() = Metrics.reset()

    @Test
    fun percentile_of_empty_is_zero() {
        assertEquals(0L, Metrics.percentile(emptyList(), 50))
    }

    @Test
    fun percentile_of_single_value() {
        assertEquals(7L, Metrics.percentile(listOf(7L), 95))
    }

    @Test
    fun p50_of_odd_count_is_the_middle_one() {
        assertEquals(3L, Metrics.p50(listOf(1L, 2L, 3L, 4L, 5L)))
    }

    @Test
    fun p50_of_even_count_takes_the_lower_middle() {
        assertEquals(20L, Metrics.p50(listOf(40L, 10L, 30L, 20L)))
    }

    @Test
    fun p50_does_not_depend_on_input_order() {
        assertEquals(Metrics.p50(listOf(3L, 1L, 2L)), Metrics.p50(listOf(1L, 2L, 3L)))
    }

    @Test
    fun p95_falls_back_to_max_when_samples_are_few() {
        assertEquals(100L, Metrics.p95(listOf(5L, 100L, 9L)))
    }

    @Test
    fun p95_uses_percentile_when_the_window_is_full() {
        assertEquals(19L, Metrics.p95((1L..Metrics.WINDOW).toList()))
    }

    @Test
    fun ring_keeps_only_the_latest_window() {
        repeat(30) { Metrics.recordDump(it, it.toLong()) }
        val samples = Metrics.snapshot()
        assertEquals(Metrics.WINDOW, samples.size)
        // 30 - 20 = 10，最早留下的应该是第 10 次
        assertEquals(10, samples.first().bytes)
        assertEquals(29, samples.last().bytes)
        assertEquals(30, Metrics.dumpCount)
    }

    @Test
    fun corrections_are_counted() {
        Metrics.bumpCorrection()
        Metrics.bumpCorrection()
        assertEquals(2, Metrics.correctionCount)
    }

    @Test
    fun reset_clears_samples_and_counters() {
        Metrics.recordDump(100, 5)
        Metrics.bumpCorrection()
        Metrics.reset()
        assertTrue(Metrics.snapshot().isEmpty())
        assertEquals(0, Metrics.dumpCount)
        assertEquals(0, Metrics.correctionCount)
    }
}