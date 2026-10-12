package com.onestep.workbench.model

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

    fun inset(dx: Int, dy: Int): Bounds =
        Bounds(left + dx, top + dy, right - dx, bottom - dy)

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

/** 布局预设。label 用于 UI，description 用于给用户解释"这个预设长什么样"。 */
enum class LayoutPreset(
    val label: String,
    val description: String
) {
    STACK_2("上下两格", "主位在上，其余竖排在下"),
    SIDE_2("左右两格", "主位在左，其余横排/竖排在右"),
    MAIN_SIDE("主位 + 侧排", "主位占上方 68%，下方横排其余窗口（适合主位游戏 + 视频）"),
    ROW_EQUAL("横屏多窗（等分）", "所有窗口各占整宽、高度等分——横屏游戏挂机首选"),
    SOLO_KEEPALIVE("一个全屏 + 其余隐形保活", "当前 App 占满屏，其他 App 缩成屏幕角落的一个小点继续运行（看不见、不碍眼），点 Dock 图标切换"),
    GRID_4("四宫格", "2×2 均分，适合几个轻量挂机位"),
    MAIN_3MINI("主位 + 3 迷你", "主位占左侧 66%，右侧竖排 3 个迷你位（窄屏上迷你列会很窄，慎用）");

    companion object {
        fun fromName(name: String?): LayoutPreset =
            entries.firstOrNull { it.name == name } ?: MAIN_3MINI
    }
}

/** 最终采用的能力档位，由自检结果决定，也允许用户手动覆盖。 */
enum class WorkbenchMode(val label: String, val explanation: String) {
    FREEFORM_TILING(
        "真同屏平铺",
        "用 Freeform 把多个 App 铺成栅格，所有窗口同时可见、同时运行"
    ),
    VENDOR_FREEFORM(
        "厂商小窗",
        "ROM 不允许自建自由窗口，改用 HyperOS 自带小窗 + 保活 + 一键切换"
    ),
    SPLIT_SCREEN(
        "系统分屏",
        "只支持两个应用分屏，退化为双窗口方案"
    ),
    QUICK_SWITCH(
        "快速切换",
        "不并发渲染，只保证后台不被冻结、切回即续上"
    )
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
    val slots: List<Slot>,
    val keepAlive: Boolean = false,
    val autoStart: Boolean = false,
    val modeOverride: WorkbenchMode? = null
) {
    val primary: Slot? get() = slots.firstOrNull()
}

/** 自检单项结果。 */
enum class Status { PASS, WARN, FAIL, SKIP }

data class ProbeItem(
    val id: String,
    val title: String,
    val status: Status,
    val detail: String,
    val raw: String = ""
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
