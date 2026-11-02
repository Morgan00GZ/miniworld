package com.miniworld.window

/**
 * 开工"补课循环"的判据（纯函数，便于单测）。
 *
 * 背景：有些游戏会甩掉我们设的自由窗口（nrc 用 `--windowingMode 5` 拉起的是策略页，
 * 它随后在新任务里起全屏游戏 Activity），所以开工后要连着校正几轮。
 *
 * 原来的参数是 800 ms × 至少 4 轮 —— 也就是说**点完「启动」后 3.2 秒内**
 * 工作线程全在"睡 800 ms、查一遍、再睡"，期间点 Dock / 点收回都在排队，
 * 用户感受就是"点了没反应"。而应用抢窗口集中在启动后 1 秒内，
 * 多看一轮足够，剩下的交给看门狗（2 s 一拍）接。
 */
internal object SettlePolicy {

    /** 每轮之间的间隔。保持 800 ms 是为了给 ROM 的落位留时间，别动。 */
    const val ROUND_INTERVAL_MS = 800L

    /** 最多补几轮（最坏情况兜底，正常 2 轮就退）。 */
    const val MAX_ROUNDS = 12

    /** 漂移清零后**再看几轮**才收工：1 = 再确认一次。 */
    const val QUIET_ROUNDS = 1

    /**
     * 这一轮结束能不能收工。
     *
     * @param attempt 从 0 开始的轮次
     * @param drifted 这一轮里还有几个窗口位置不对
     */
    fun shouldStop(attempt: Int, drifted: Int): Boolean = drifted == 0 && attempt >= QUIET_ROUNDS
}

/**
 * 「任务不见了」的判定与自愈上限（纯函数）。
 *
 * 以前这一路是**静默**的：`find(...) ?: return@forEachIndexed` 直接跳过，
 * 界面还写着「运行中」，日志一行都没有 —— 挂机掉了用户完全不知道。
 */
internal object DropPolicy {

    /** 连续多少拍找不到任务才认定掉线。亮屏 2 s 一拍 → 约 6 s。 */
    const val MISS_TICKS_TO_LOST = 3

    /** 最多自动重开几次；超过就交给用户（界面与常驻通知会一直挂着「已掉线」）。 */
    const val MAX_RELAUNCH = 2

    fun isLost(misses: Int): Boolean = misses >= MISS_TICKS_TO_LOST

    fun mayRelaunch(misses: Int, tries: Int): Boolean = isLost(misses) && tries < MAX_RELAUNCH
}