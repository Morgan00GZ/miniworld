#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""
TaskInspector.parse() 的 Python 等价移植 + 回归校验。

为什么要有这个文件：开发机上没有 JDK/Android SDK，跑不了 JVM 单测；
但解析逻辑是纯字符串处理，可以逐行移植到 Python 在这里跑，先把"正则写错了"
这类必然性 bug 挡住。Android 侧的真机单测在 app/src/test/java/.../TaskInspectorTest.kt，
两边的用例和期望值必须保持一致——改了一边就要改另一边。

用法：python tools/verify-taskinspector.py    （退出码 0 = 全绿）
"""

import re
import sys

# ---------------------------------------------------------------- 与 Kotlin 逐条对应的正则
TASK_HEADER = re.compile(r"^\*?\s*Task\{[^}]*#(\d+)[^}]*\}")
PKG = re.compile(r"A=(\d+):([A-Za-z0-9_.]+)")
PKG_FROM_INTENT = re.compile(r"I=([A-Za-z0-9_.]+)/")
MODE_WORD = re.compile(r"(?:windowingMode|mode)=([A-Za-z0-9_]+)")
BOUNDS = re.compile(r"mBounds=Rect\((-?\d+),\s*(-?\d+)\s*-\s*(-?\d+),\s*(-?\d+)\)")
ACTIVITY = re.compile(r"ActivityRecord\{[^}]*\s([A-Za-z0-9_.]+)/([A-Za-z0-9_.$]+)")


def header_at(line):
    return TASK_HEADER.search(line.lstrip())


class Task:
    def __init__(self):
        self.task_id = None
        self.pkg = ""
        self.activity = ""
        self.mode = ""
        self.is_freeform = False
        self.visible = False
        self.bounds = None

    def __repr__(self):
        return "#%s %s mode=%s freeform=%s bounds=%s" % (
            self.task_id, self.pkg, self.mode, self.is_freeform, self.bounds)


def parse(dump):
    """TaskInspector.parse 的等价实现。"""
    if not dump.strip():
        return []
    lines = dump.split("\n")
    out = []
    i = 0
    while i < len(lines):
        header_line = lines[i]
        hm = header_at(header_line)
        if hm is None:
            i += 1
            continue
        task_id = int(hm.group(1))

        m = PKG.search(header_line)
        pkg = m.group(2) if m else ""
        if not pkg:
            m = PKG_FROM_INTENT.search(header_line)
            pkg = m.group(1) if m else ""
        m = MODE_WORD.search(header_line)
        mode = m.group(1) if m else ""
        bounds = None
        visible = "visible=true" in header_line
        activity = ""
        top_activity = ""

        j = i + 1
        while j < len(lines) and header_at(lines[j]) is None:
            line = lines[j]
            if not pkg:
                m = PKG.search(line)
                if m:
                    pkg = m.group(2)
                else:
                    m = PKG_FROM_INTENT.search(line)
                    if m:
                        pkg = m.group(1)
            if not mode:
                m = MODE_WORD.search(line)
                if m:
                    mode = m.group(1)
            if bounds is None:
                m = BOUNDS.search(line)
                if m:
                    bounds = tuple(int(g) for g in m.groups())
            if not visible:
                visible = "visible=true" in line
            if not top_activity and "topResumedActivity" in line:
                m = ACTIVITY.search(line)
                if m:
                    top_activity = "%s/%s" % (m.group(1), m.group(2))
            elif not activity:
                m = ACTIVITY.search(line)
                if m:
                    activity = "%s/%s" % (m.group(1), m.group(2))
            j += 1

        t = Task()
        t.task_id = task_id
        t.pkg = pkg
        t.activity = top_activity if top_activity else activity
        t.mode = mode if mode else "unknown"
        t.is_freeform = ("freeform" in mode.lower()) or mode == "5"
        t.visible = visible
        t.bounds = bounds
        out.append(t)
        i = j
    return out


# ---------------------------------------------------------------- 真机 dump 片段（逐字，与 Kotlin 单测同源）
REAL_DUMP = """
ACTIVITY MANAGER ACTIVITIES (dumpsys activity activities)
Display #0 (activities from top to bottom):
  * Task{82ebf4 #125 type=standard A=10281:com.miui.calculator U=0 visible=true visibleRequested=true mode=freeform translucent=false sz=1}
    mBounds=Rect(306, 760 - 915, 1980)
    isAlwaysOnTop
    fullscreenRequestAllowMode=NONE
    mNonOccludedFreeformAreaRatio=100
    isSleeping=false
    topResumedActivity=ActivityRecord{129950536 u0 com.miui.calculator/.cal.CalculatorActivity t125}
    * Hist  #0: ActivityRecord{129950536 u0 com.miui.calculator/.cal.CalculatorActivity t125}
      packageName=com.miui.calculator processName=com.miui.calculator
      launchedFromUid=2000 launchedFromPackage=com.android.shell launchedFromFeature=null userId=0
  * Task{618a9ed #91 type=standard A=10332:com.tencent.nrc U=0 visible=true visibleRequested=true mode=freeform translucent=false sz=1}
    mBounds=Rect(44, 329 - 584, 1529)
    isAlwaysOnTop
    fullscreenRequestAllowMode=NONE
    mLastPausedActivity: ActivityRecord{70389491 u0 com.tencent.nrc/com.tencent.gcloud.msdk.core.policy.MSDKPolicyV3Activity t-1 f}}
    mLastNonFullscreenBounds=Rect(826, 462 - 1831, 842)
    mNonOccludedFreeformAreaRatio=83
    isSleeping=false
    topResumedActivity=ActivityRecord{29122565 u0 com.tencent.nrc/com.epicgames.ue4.GameActivity t91}
    * Hist  #0: ActivityRecord{29122565 u0 com.tencent.nrc/com.epicgames.ue4.GameActivity t91}
      packageName=com.tencent.nrc processName=com.tencent.nrc
      launchedFromUid=10332 launchedFromPackage=com.tencent.nrc launchedFromFeature=null userId=0
      app=ProcessRecord{b0f0d5f 32506:com.tencent.nrc/u0a332}
      Intent { flg=0x10000000 xflg=0x4 cmp=com.tencent.nrc/com.epicgames.ue4.GameActivity (has extras) }
      rootOfTask=true task=Task{618a9ed #91 type=standard A=10332:com.tencent.nrc}
      taskAffinity=10332:com.tencent.nrc
      mActivityComponent=com.tencent.nrc/com.epicgames.ue4.GameActivity
      dataDir=/data/user/0/com.tencent.nrc
  * Task{5568aa7 #2 type=home I=com.miui.home/.launcher.Launcher U=0 rootTaskId=1 visible=true visibleRequested=true mode=fullscreen translucent=false sz=1}
    mBounds=Rect(0, 0 - 1220, 2656)
    topResumedActivity=ActivityRecord{9e4b6b0 u0 com.miui.home/.launcher.Launcher t2}
""".strip("\n")

# ---------------------------------------------------------------- 断言表（与 Kotlin 单测一一对应）
results = []


def check(name, cond, detail=""):
    results.append((name, bool(cond), detail))


real = parse(REAL_DUMP)
by_pkg = {t.pkg: t for t in real}

check("真机 dump 解析出 3 个任务（引用行不算任务头）", len(real) == 3,
      "实际 %d 个：%s" % (len(real), [t.pkg or "(空)" for t in real]))

calc = by_pkg.get("com.miui.calculator")
check("计算器任务存在", calc is not None)
if calc:
    check("计算器 taskId=125", calc.task_id == 125, str(calc.task_id))
    check("计算器 mode=freeform", calc.mode == "freeform", calc.mode)
    check("计算器 isFreeform", calc.is_freeform)
    check("计算器 visible", calc.visible)
    check("计算器 bounds=(306,760,915,1980)", calc.bounds == (306, 760, 915, 1980), str(calc.bounds))
    check("计算器 activity 取 topResumedActivity",
          calc.activity == "com.miui.calculator/.cal.CalculatorActivity", calc.activity)

game = by_pkg.get("com.tencent.nrc")
check("游戏任务存在", game is not None)
if game:
    check("游戏 taskId=91", game.task_id == 91, str(game.task_id))
    check("游戏 isFreeform", game.is_freeform)
    check("游戏 bounds=(44,329,584,1529)（引用行没截断块）",
          game.bounds == (44, 329, 584, 1529), str(game.bounds))
    check("游戏 activity 是 GameActivity 而不是 MSDKPolicyV3Activity",
          game.activity == "com.tencent.nrc/com.epicgames.ue4.GameActivity", game.activity)

home = by_pkg.get("com.miui.home")
check("桌面任务用 I= 兜底解析出包名", home is not None)
if home:
    check("桌面 taskId=2", home.task_id == 2, str(home.task_id))
    check("桌面 mode=fullscreen", home.mode == "fullscreen", home.mode)
    check("桌面 isFreeform=False", not home.is_freeform)

check("自由窗口任务列表 = 计算器 + 游戏",
      {t.pkg for t in real if t.is_freeform} == {"com.miui.calculator", "com.tencent.nrc"},
      str(sorted(t.pkg for t in real if t.is_freeform)))

# 负对照 1：mLastNonFullscreenBounds 不能被当成窗口坐标
t7 = parse("* Task{aabbcc #7 type=standard A=10001:com.example.app U=0 visible=true mode=freeform sz=1}\n"
           "mLastNonFullscreenBounds=Rect(1, 2 - 3, 4)")
check("mLastNonFullscreenBounds 不当成 mBounds", len(t7) == 1 and t7[0].bounds is None,
      str(t7[0].bounds) if t7 else "解析不到任务")

# 负对照 2：mode=数字 5 也识别为自由窗口
t8 = parse("* Task{aabbcc #8 type=standard A=10002:com.foo.bar U=0 visible=true mode=5}\n"
           "mBounds=Rect(0, 0 - 100, 200)")
check("mode=5 识别为自由窗口", len(t8) == 1 and t8[0].is_freeform and t8[0].bounds == (0, 0, 100, 200),
      str(t8[0]) if t8 else "解析不到任务")

# 负对照 3：解析不出来时返回空列表，不抛异常
ok = True
for bad in ("", "   \n  \n", "Error: java.lang.SecurityException: Permission Denial",
            "Task{没有井号也没有右括号"):
    try:
        if parse(bad) != []:
            ok = False
    except Exception as exc:  # noqa: BLE001
        ok = False
        print("解析 %r 时抛异常：%s" % (bad, exc))
check("空/乱文本返回空列表且不抛异常", ok)

# ---------------------------------------------------------------- 输出
failed = 0
for name, passed, detail in results:
    if passed:
        print("  [PASS] %s" % name)
    else:
        failed += 1
        print("  [FAIL] %s%s" % (name, ("  → " + detail) if detail else ""))
print("")
print("共 %d 项检查，通过 %d，失败 %d" % (len(results), len(results) - failed, failed))
sys.exit(1 if failed else 0)
