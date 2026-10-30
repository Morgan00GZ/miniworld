package com.miniworld.system

/**
 * 运行期的轻量指标：最近若干次任务列表查询的大小/耗时，加上看门狗校正次数。
 *
 * 为什么只放内存：
 *  1. 这些只是「自检」面板要看的东西，不需要留档；
 *  2. diag.log 已经够挤了（512 KB 上限，超了整个文件被删重来，反而看不到历史），
 *     再往里写每拍的字节数会把真正有用的失败记录冲掉；
 *  3. 环形缓冲固定 [WINDOW] 个样本，满了挤掉最旧的，内存恒定，不会因为挂机一天而涨。
 */
object Metrics {

    /** 保留多少个样本。20 个够算 P50/P95，也够看出「突然变大」这种异常。 */
    const val WINDOW = 20

    data class Sample(val bytes: Int, val millis: Long)

    private val samples = ArrayDeque<Sample>()

    @Volatile
    private var dumps = 0

    @Volatile
    private var corrections = 0

    /** 记一次全量 dump。bytes 用字符数近似（dumpsys 输出是纯 ASCII，1 字符 ≈ 1 字节）。 */
    @Synchronized
    fun recordDump(bytes: Int, millis: Long) {
        dumps++
        samples.addLast(Sample(bytes, millis))
        while (samples.size > WINDOW) samples.removeFirst()
    }

    /** 看门狗真的动过窗口几何的次数（正常挂机应该一直是 0，涨了就说明有东西在抢）。 */
    @Synchronized
    fun bumpCorrection() {
        corrections++
    }

    val dumpCount: Int get() = dumps

    val correctionCount: Int get() = corrections

    @Synchronized
    fun snapshot(): List<Sample> = samples.toList()

    @Synchronized
    fun reset() {
        samples.clear()
        dumps = 0
        corrections = 0
    }

    /** 中位数。空列表返回 0。 */
    fun p50(values: List<Long>): Long = percentile(values, 50)

    /**
     * P95。样本不足 [WINDOW] 个时直接退化成最大值——
     * 三五个样本算出来的「95 分位」是插值编出来的数字，不如老实报最差那次。
     */
    fun p95(values: List<Long>): Long =
        if (values.size < WINDOW) values.maxOrNull() ?: 0L else percentile(values, 95)

    internal fun percentile(values: List<Long>, p: Int): Long {
        if (values.isEmpty()) return 0L
        val sorted = values.sorted()
        val rank = ((p / 100.0) * (sorted.size - 1)).toInt().coerceIn(0, sorted.size - 1)
        return sorted[rank]
    }
}