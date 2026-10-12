package com.miniworld.model

/**
 * 纯数据矩形。
 *
 * 故意**不用** android.graphics.Rect：布局是这套方案里最需要被验证的部分，
 * 而纯 Kotlin 数据类可以在 JVM 上直接跑单元测试，不用 Robolectric、不用真机。
 */
data class Bounds(val left: Int, val top: Int, val right: Int, val bottom: Int) {

    val width: Int get() = (right - left).coerceAtLeast(0)
    val height: Int get() = (bottom - top).coerceAtLeast(0)
    val area: Long get() = width.toLong() * height.toLong()
    val isValid: Boolean get() = right > left && bottom > top

    /** `am start --bounds` 需要的 `l,t,r,b` 逗号形式 */
    fun toShellArg(): String = "$left,$top,$right,$bottom"

    /**
     * `am task resize` 需要的是**四个分开的数字**，不是逗号串。
     * 真机实测（Xiaomi 25098PN5AC / Android 17 / OS4.0.0.41）：逗号形式会抛
     * `java.lang.NumberFormatException: For input string: "0,0,540,1200"`，
     * 栈顶为 com.android.server.am.ActivityManagerShellCommand.getBounds。
     */
    fun toResizeArgs(): String = "$left $top $right $bottom"

    fun overlaps(other: Bounds): Boolean =
        left < other.right && other.left < right && top < other.bottom && other.top < bottom

    override fun toString(): String = "[$left,$top,$right,$bottom]"
}

/**
 * 布局预设。
 *
 * 只有一种：**一个全屏 + 其余隐形保活**。这是真机验证下来唯一可用的形态——
 * 用户要的是"App 挂在后台跑、一键浮出看/操作"，而不是屏幕上同时铺好几个窗口
 * （平铺那套在真机上被明确否决过，见开发历程回顾）。
 */
enum class LayoutPreset {
    SOLO_KEEPALIVE;

    companion object {
        /**
         * 从持久化的名字还原。认不出来的（老版本写进去的栅格预设名）
         * 一律落到 [SOLO_KEEPALIVE]——也就是这个 App 的最终形态。
         */
        fun fromName(name: String?): LayoutPreset =
            entries.firstOrNull { it.name == name } ?: SOLO_KEEPALIVE
    }
}

/**
 * 工作台的能力档位。
 *
 * 只留"真同屏平铺"这一条：它是唯一被本机验证能跑通的自由窗口路径
 * （`am start --windowingMode 5` + `am task resize`）。别的档位要么依赖厂商小窗、
 * 要么退化成"不并发渲染"，都在真机上被否过。
 */
enum class WorkbenchMode(val label: String) {
    FREEFORM_TILING("真同屏平铺")
}

/** 场景里的一个槽位。顺序即主位优先级，index = 0 是主位。 */
data class Slot(
    val index: Int,
    val packageName: String,
    val component: String? = null,
    val label: String = packageName,
    /**
     * Android 用户号：0 = 主系统，999 = 小米「手机分身」(XSpace)。
     * 同一份安装包跑在分身里时**包名完全相同**，只能靠这个字段区分两个实例，
     * 否则启动/保活/改窗口都会抓到主系统那一份（真机踩过）。
     */
    val userId: Int = 0
)

data class Scene(
    val id: String,
    val name: String,
    val preset: LayoutPreset,
    val slots: List<Slot>
)

/** 已启动窗口的运行时记录：把场景槽位与系统任务 id 关联起来。 */
data class LiveWindow(
    val slotIndex: Int,
    val packageName: String,
    val component: String?,
    val taskId: Int,
    val bounds: Bounds,
    val launchedBy: String
)
