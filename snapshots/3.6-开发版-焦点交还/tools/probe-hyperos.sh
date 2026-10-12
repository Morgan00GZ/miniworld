#!/usr/bin/env bash
# =============================================================================
# 澎湃 OS / HyperOS 多 App 同时运行能力探测（Termux 与 PC 通用）
#
# 与 tools/probe-hyperos.ps1 是同一套探测项（P0–P18）且行为等价，
# 区别只在"命令从哪发出去"：
#   · PC（Git Bash / WSL / Linux / macOS）：自动走 adb，需要 USB 或无线调试
#   · 手机 Termux：必须走 Shizuku 的 rish —— Termux 自己的 uid 改不了 global 设置、
#     开不了别的 display、看不到别人的任务，所以**没有 rish 就只能做只读项**
#
# 为什么要 rish：
#   rish 是 Shizuku 官方给的脚本，让你的 shell 以 shell(uid=2000) 身份执行命令。
#   这正是本 App 最终要用的同一条通路，所以用 rish 跑出来的结论是真结论。
#   获取方式：从 Shizuku 仓库取 rish 与 rish_shizuku.dex 放进 $PATH，并确保 Shizuku 已启动。
#
# 真机基线（Xiaomi 25098PN5AC / Android 17 / SDK 37 / HyperOS OS4.0.0.41.XBLCNXM）：
#   · am start --windowingMode 支持；am start --bounds 不存在 → 只能"启动后再 resize"
#   · am task resize <TASK_ID> <LEFT> <TOP> <RIGHT> <BOTTOM> 的四个参数必须是分开的数字；
#     写成 0,0,540,1200 会抛 java.lang.NumberFormatException
#   · am help 不列 task focus，但 am task focus <ID> 实测可用
#   · overlay_display_devices 能建出虚拟屏，但 shell 往这块系统持有的屏启动 App 会被
#     SecurityException at SafeActivityOptions.checkPermissions 拒
#     → overlay 屏不能当 D 档容器，D 档必须由 App 自建 VirtualDisplay(ownerUid=2000)
#   · Android 17 的 am stack list 已无 "Stack id"，改用 RootTask → P18.3b 降级为 SKIP（不是 FAIL）
#
# 用法:
#   bash probe-hyperos.sh --selftest             # 先验这个脚本自己的解析器（不碰手机）
#   bash probe-hyperos.sh                        # 只读探测
#   bash probe-hyperos.sh -p 你的游戏包名          # 指定被探测的 App（建议用你的挂机游戏）
#       参数直接写包名，不要加尖括号 —— `<` 在 bash 里是输入重定向
#   bash probe-hyperos.sh --vd                   # 追加虚拟屏实验 P18（临时改设置，退出自动还原）
#   bash probe-hyperos.sh --vd --vd-spec 540x1200/220   # 用低分辨率虚拟屏测（更省电）
#   bash probe-hyperos.sh --bounds 0,0,540,1200  # 自定义探测窗口尺寸
#   bash probe-hyperos.sh --apply                # 允许写 enable_freeform_support（改完需重启手机）
#   PROBE_MODE=rish|adb|native bash probe-hyperos.sh
#
# 退出码: 0=全通过  1=有 FAIL  2=参数错误  3=自检失败
# =============================================================================

set -u

TEST_PACKAGE="com.android.settings"
SECOND_PACKAGE="com.android.calculator2"
TEST_BOUNDS="0,0,540,1200"
VD_SPEC="1080x2400/440"
DO_VD=0
DO_APPLY=0
DO_SELFTEST=0
SERIAL=""
LOG_DIR="${TMPDIR:-/tmp}/probe-hyperos"

while [ $# -gt 0 ]; do
  case "$1" in
    -p|--package)           TEST_PACKAGE="$2"; shift 2 ;;
    --second-package)       SECOND_PACKAGE="$2"; shift 2 ;;
    -s|--serial)            SERIAL="$2"; shift 2 ;;
    -b|--bounds)            TEST_BOUNDS="$2"; shift 2 ;;
    --vd|--virtual-display) DO_VD=1; shift ;;
    --vd-spec)              VD_SPEC="$2"; shift 2 ;;
    --apply)                DO_APPLY=1; shift ;;
    --selftest)             DO_SELFTEST=1; shift ;;
    -h|--help)              sed -n '2,/^# =====/p' "$0"; exit 0 ;;
    *) echo "未知参数: $1（用 -h 看用法）" >&2; exit 2 ;;
  esac
done

# resize 的四个参数会被拆成四个独立数字（见 P3），所以这里必须先校验格式。
case "$TEST_BOUNDS" in
  [0-9]*,[0-9]*,[0-9]*,[0-9]*) ;;
  *) echo "窗口尺寸应为 LEFT,TOP,RIGHT,BOTTOM（四个数字，逗号分隔），当前: $TEST_BOUNDS" >&2; exit 2 ;;
esac

# ------------------------------------------------------------------ 依赖检查
# Termux 的基础环境不一定带 awk（它由 gawk 包提供），少了它任务解析会整段失效，
# 所以宁可在开头就拦住，也不要跑出一堆假 FAIL。

MISSING=""
for t in awk grep sed sort head tr; do
  command -v "$t" >/dev/null 2>&1 || MISSING="$MISSING $t"
done
if [ -n "$MISSING" ]; then
  echo "缺少必需命令:$MISSING" >&2
  echo "Termux 上执行: pkg install gawk coreutils grep sed findutils" >&2
  exit 3
fi

# ------------------------------------------------------------------ 通路选择

MODE="${PROBE_MODE:-}"
RISH_STYLE=""   # dashc | plain —— rish 传命令的形式随版本/包装脚本不同，实测决定，不猜

detect_mode() {
  [ -n "$MODE" ] && return
  if command -v rish >/dev/null 2>&1; then
    MODE=rish
    if [ "$(rish -c 'echo __rish_probe_ok__' 2>/dev/null | tr -d '\r')" = "__rish_probe_ok__" ]; then
      RISH_STYLE=dashc
    elif [ "$(rish 'echo __rish_probe_ok__' 2>/dev/null | tr -d '\r')" = "__rish_probe_ok__" ]; then
      RISH_STYLE=plain
    else
      RISH_STYLE=unknown
    fi
    return
  fi
  if command -v adb >/dev/null 2>&1; then
    local dev
    if [ -n "$SERIAL" ]; then
      dev=$(adb -s "$SERIAL" get-state 2>/dev/null)
    else
      dev=$(adb devices 2>/dev/null | awk 'NR>1 && $2=="device" {print "device"; exit}')
    fi
    if [ "$dev" = "device" ]; then MODE=adb; return; fi
  fi
  MODE=native
}

# 统一执行入口。tr -d '\r' 是必须的：Windows 上的 adb shell 输出带回车，会毁掉解析
sh_run() {
  case "$MODE" in
    rish)
      case "$RISH_STYLE" in
        plain) rish "$1" 2>&1 ;;
        *)     rish -c "$1" 2>&1 ;;
      esac ;;
    adb)
      if [ -n "$SERIAL" ]; then adb -s "$SERIAL" shell "$1" 2>&1
      else adb shell "$1" 2>&1; fi ;;
    *) sh -c "$1" 2>&1 ;;
  esac | tr -d '\r'
}

has() { printf '%s' "$1" | grep -q -F -e "$2"; }        # 固定串匹配
matches() { printf '%s' "$1" | grep -q -E -e "$2"; }    # 正则匹配
first_line() { printf '%s' "$1" | awk 'NF { print; exit }'; }

# ------------------------------------------------------------------ 报告

PASS_N=0; WARN_N=0; FAIL_N=0
RESULT_MAP=""   # 每行 "ID STATUS"，供最后按关键项（P2/P3/P4）下结论

report() { # id title status [detail] [raw]
  local id="$1" title="$2" status="$3" detail="${4:-}" raw="${5:-}" c=""
  case "$status" in
    PASS) c=$'\033[32m'; PASS_N=$((PASS_N + 1)) ;;
    WARN) c=$'\033[33m'; WARN_N=$((WARN_N + 1)) ;;
    FAIL) c=$'\033[31m'; FAIL_N=$((FAIL_N + 1)) ;;
    *)    c=$'\033[90m' ;;
  esac
  printf '%s[%-4s]%s %-30s %s\n' "$c" "$status" $'\033[0m' "$id" "$title"
  [ -n "$detail" ] && printf '        └ %s\n' "$detail"
  if [ -n "$raw" ]; then
    local rawh
    rawh=$(first_line "$raw")
    [ -n "$rawh" ] && printf '        │ %s\n' "$rawh"
  fi
  RESULT_MAP="${RESULT_MAP}${id} ${status}"$'\n'
  return 0
}

# 按探测项编号取状态（.ps1 里的 Get-Status）
status_of() { # $1=id
  printf '%s' "$RESULT_MAP" | awk -v want="$1" '$1 == want { print $2; hit = 1; exit } END { if (!hit) print "SKIP" }'
}

save_raw() { mkdir -p "$LOG_DIR" 2>/dev/null; cat > "$LOG_DIR/$1.txt"; }

# ------------------------------------------------------------------ 解析器

# 取某个包对应的整段任务块（不同 ROM 排版差异大，按"块"判断比按行可靠）
task_block() { # $1=package ; stdin=dumpsys activity activities
  awk -v pkg="$1" '
    /Task\{[^}]*#[0-9]+/ {
      if (buf != "" && buf ~ pkg) { printf "%s", buf; found = 1; exit }
      buf = $0 "\n"; next
    }
    { if (buf != "") buf = buf $0 "\n" }
    END { if (!found && buf != "" && buf ~ pkg) printf "%s", buf }
  '
}

# 从 dumpsys display 里取出所有 displayId（输出形如 "0 1 3 "）。
# 两个坑：
#   1. 不能对 DisplayDeviceInfo{...} 做"块匹配"——那一行极长（含 supportedModes）且内部
#      还有嵌套花括号，会提前截断，于是"虚拟屏明明建出来了却报 FAIL"。必须逐行找 displayId。
#   2. 数字必须限定 1–3 位：有些行里是 19 位的 display token 长整型
#      （例如 4630946949513469331），不限定就会被误当成 displayId。
#      另外 grep 默认区分大小写，mDisplayId=7 不会被误收——**不要加 -i**。
display_ids() { # stdin=dumpsys display
  grep -oE 'displayId[ =]+[0-9]{1,3}([^0-9]|$)' | grep -oE '[0-9]+' | sort -n -u | tr '\n' ' '
}

# 输出每个"自由窗口任务"的 Task 头行（一行一个）
freeform_headers() { # stdin=dumpsys activity activities
  awk '
    /Task\{[^}]*#[0-9]+/ {
      if (buf != "" && buf ~ /freeform/) print buf
      buf = $0; next
    }
    { if (buf != "") buf = buf "\n" $0 }
    END { if (buf != "" && buf ~ /freeform/) print buf }
  '
}

# am help 里子命令是按"行首缩进 + 子命令名 + 参数占位"罗列的。
# 教训：不能用 `am help | grep resize` 这种松散匹配（既是假阴性来源，也会误伤
# `runTaskResize` 这类同名片段）；按用法行的行首格式判断才准。
am_usage_of() { # $1=am help 文本 $2=用法行正则
  printf '%s' "$1" | grep -qE -e "$2"
}
am_usage() { am_usage_of "$AM_HELP" "$1"; }

resolve_component() { # $1=package
  local out
  out=$(sh_run "cmd package resolve-activity --brief -c android.intent.category.LAUNCHER $1")
  case "$out" in
    *Error*|*Unknown*|*"No activity"*) out=$(sh_run "cmd package resolve-activity --brief $1") ;;
  esac
  printf '%s\n' "$out" | grep '/' | head -n 1 | awk '{print $1}'
}

pkg_installed() { # $1=package ; 输出 0/1
  sh_run "pm list packages $1" | grep -cE "^package:${1}[[:space:]]*$" || true
}

# ------------------------------------------------------------------ 自检
# 本脚本在有 bash 的机器上才能被验证；作者环境没有 bash，所以把解析器的
# 单元测试做成 --selftest，让你在手机上一条命令就验掉它。

selftest() {
  local fail=0

  local sample
  sample='  * Task{8f8d3c1 #123 type=standard A=10123:com.foo.bar U=0 visible=true}
    mBounds=Rect(0, 0 - 540, 1200)
    * ActivityRecord{111 u0 com.foo.bar/.Main t123} state=RESUMED
  * Task{99 #456 type=standard A=10124:com.baz U=0 visible=false}
    * ActivityRecord{222 u0 com.baz/.Main t456} state=STOPPED'

  local b1 b2
  b1=$(printf '%s\n' "$sample" | task_block 'com.foo.bar')
  b2=$(printf '%s\n' "$sample" | task_block 'com.baz')

  if has "$b1" 'state=RESUMED' && ! has "$b1" 'com.baz'; then
    echo "[ OK ] task_block: 命中目标块的 RESUMED，且没有串到下一块"
  else
    echo "[FAIL] task_block: 第一块解析错误"; fail=1
  fi

  # awk 的 exit 会跳到 END：如果 END 里不判标志位，同一块会被打印两次。
  # 这个重复不会影响"包含 RESUMED"之类的松散判断，所以必须单独查一次。
  local n_task
  n_task=$(printf '%s' "$b1" | grep -c 'Task{')
  if [ "$n_task" = "1" ]; then
    echo "[ OK ] task_block: 输出不重复（exit 与 END 之间没有二次打印）"
  else
    echo "[FAIL] task_block: 输出重复了（Task{ 出现 $n_task 次，应为 1）"; fail=1
  fi

  if has "$b2" 'state=STOPPED'; then
    echo "[ OK ] task_block: 第二块状态识别正确"
  else
    echo "[FAIL] task_block: 第二块解析错误"; fail=1
  fi

  # display_ids：正常的 displayId 要抓到，19 位 display token 与大小写不同的
  # mDisplayId 都不能混进来（真机上就是这两个坑让虚拟屏误报 FAIL）。
  local d dnorm
  d=$(printf '%s\n' \
    'DisplayDeviceInfo{"Built-in": uniqueId="local:0", 1080 x 2400, displayId 0}' \
    'DisplayDeviceInfo{"Overlay #1": uniqueId="overlay:1", displayId 1}' \
    'DisplayDeviceInfo{"Third": uniqueId="x:3", displayId 3}' \
    '  mDisplayId=4630946949513469331 (display token)' \
    '  mDisplayId=7' | display_ids)
  dnorm=$(printf '%s' "$d" | tr -s ' ' | sed 's/^ //; s/ $//')
  if [ "$dnorm" = "0 1 3" ]; then
    echo "[ OK ] display_ids: 提取到 [0 1 3]，19 位 token 与 mDisplayId 都已排除"
  else
    echo "[FAIL] display_ids: 期望 [0 1 3]，得到 [$dnorm]"; fail=1
  fi

  # am help 的用法行判定：行首缩进的子命令才算存在，正文里出现的同名片段不算
  local amh
  amh='am help:
      start [options] <INTENT>
      task [COMMAND]
      resize <TASK_ID> <LEFT> <TOP> <RIGHT> <BOTTOM>
      move-stack <STACK_ID> <DISPLAY_ID>
      move-task <TASK_ID> <STACK_ID>
      runTaskResize <TASK_ID>'
  if am_usage_of "$amh" '^[[:space:]]*resize[[:space:]]*<TASK_ID>'; then
    echo "[ OK ] am_usage: 识别到 resize <TASK_ID> 用法行"
  else
    echo "[FAIL] am_usage: 漏掉了 resize 用法行"; fail=1
  fi
  if am_usage_of "$amh" '^[[:space:]]*move-stack[[:space:]]*<STACK_ID>'; then
    echo "[ OK ] am_usage: 识别到 move-stack 用法行"
  else
    echo "[FAIL] am_usage: 漏掉了 move-stack 用法行"; fail=1
  fi
  if am_usage_of "$amh" '^[[:space:]]*resizeable[[:space:]]*<TASK_ID>'; then
    echo "[FAIL] am_usage: 把不存在的 resizeable 判成存在了"; fail=1
  else
    echo "[ OK ] am_usage: 不存在的子命令不会误判（runTaskResize 也没被当成 resize）"
  fi

  # resize 参数：必须是 4 个分开的数字，逗号串会被真机拒绝
  local rargs rn
  rargs=$(printf '%s' '0,0,540,1200' | tr ',' ' ')
  rn=$(printf '%s' "$rargs" | awk '{ print NF }')
  if [ "$rargs" = "0 0 540 1200" ] && [ "$rn" = "4" ] && ! has "$rargs" ','; then
    echo "[ OK ] resize 参数: 逗号串已拆成 4 个独立数字 [$rargs]"
  else
    echo "[FAIL] resize 参数: 拆分错误（得到 [$rargs]，字段数 $rn）"; fail=1
  fi

  if has 'abc --windowingMode 5' '--windowingMode' && ! has 'abc' '--display'; then
    echo "[ OK ] has: 以 '-' 开头的模式匹配正确"
  else
    echo "[FAIL] has: 模式匹配有问题"; fail=1
  fi

  return $fail
}

if [ "$DO_SELFTEST" = 1 ]; then
  echo "=== probe-hyperos.sh 自检（不接触手机）==="
  if selftest; then
    echo "自检通过。可以继续跑真实探测。"
    exit 0
  else
    echo "自检失败：先别信后面的探测结果。"
    exit 3
  fi
fi

# ------------------------------------------------------------------ 虚拟屏清理
# .ps1 是在 try/finally 里收尾；这里既在 P18 结束后显式调用一次（这样 P18.5 会计入汇总），
# 也用 trap 兜住中途退出（Ctrl-C / 提前 exit）的情况。

VD_TOUCHED=0
VD_CLEANED=0

cleanup_vd() {
  [ "$DO_VD" = 1 ] || return 0
  [ "$VD_CLEANED" = 1 ] && return 0
  VD_CLEANED=1
  [ "$VD_TOUCHED" = 1 ] || return 0
  sh_run 'settings delete global overlay_display_devices' >/dev/null 2>&1
  sleep 1
  local ids base_n after_n verdict
  ids=$(sh_run 'dumpsys display' | display_ids)
  base_n=$(printf '%s' "${BASE_IDS:-}" | awk 'NF { n += NF } END { print n + 0 }')
  after_n=$(printf '%s' "$ids" | awk 'NF { n += NF } END { print n + 0 }')
  if [ "$after_n" -le "$base_n" ]; then verdict=PASS; else verdict=WARN; fi
  report 'P18.5' '清理虚拟屏' "$verdict" \
    "已执行 settings delete global overlay_display_devices；现有 displayId: ${ids:-无}"
  return 0
}

trap cleanup_vd EXIT INT TERM

# ------------------------------------------------------------------ 开跑

detect_mode

echo
echo "=== 澎湃 OS 多 App 同时运行能力探测 ==="
case "$MODE" in
  adb)  echo "通路: adb（PC 连接手机）" ;;
  rish) echo "通路: rish（手机 + Shizuku，shell 身份）" ;;
  *)    echo "通路: 本机 shell（权限受限）" ;;
esac
echo "被探测的 App: $TEST_PACKAGE"
echo "窗口尺寸: $TEST_BOUNDS"
echo

IDENTITY=$(sh_run 'id')
case "$MODE" in
  adb) report 'P.0' '通路 adb' PASS "$IDENTITY" ;;
  rish)
    if [ "$RISH_STYLE" = unknown ]; then
      report 'P.0' '通路 rish' WARN "rish 在，但两种传参形式都没跑通（$IDENTITY）→ 确认 Shizuku 服务已启动"
    else
      report 'P.0' '通路 rish (Shizuku)' PASS "$IDENTITY  [命令形式: $RISH_STYLE]"
    fi ;;
  *)
    report 'P.0' '通路 本机 shell' FAIL '既没有 rish 也没有可用的 adb，多数探测项会失败'
    echo "  Termux 上请先准备 rish（Shizuku 仓库的 rish + rish_shizuku.dex 放进 \$PATH），"
    echo "  启动 Shizuku 服务后重跑：PROBE_MODE=rish bash $0"
    ;;
esac

# --- P0 命令面 ---------------------------------------------------------------

AM_HELP=$(sh_run 'am help')
AM_TASK=$(sh_run 'am task')
printf '%s' "$AM_HELP" | save_raw am-help

if has "$AM_HELP" '--windowingMode'; then
  report 'P0.1' 'am start --windowingMode' PASS '支持以指定窗口模式启动'
else
  report 'P0.1' 'am start --windowingMode' FAIL '不支持 → 只能走 App 内 ActivityOptions 策略'
fi

HAS_BOUNDS=no
has "$AM_HELP" '--bounds' && HAS_BOUNDS=yes
if [ "$HAS_BOUNDS" = yes ]; then
  report 'P0.2' 'am start --bounds' PASS '可一步到位带尺寸启动，最省事'
else
  report 'P0.2' 'am start --bounds' WARN '无此选项 → 启动后再 resize（真机实测就是这条）'
fi

# 教训：am help 里子命令是缩进罗列的，根本不会出现 'task resize' 这种带父命令的写法；
# 真机上写的是 `resize <TASK_ID> <LEFT> <TOP> <RIGHT> <BOTTOM>`。按用法行匹配才准。
HAS_RESIZE=no
am_usage '^[[:space:]]*resize[[:space:]]*<TASK_ID>' && HAS_RESIZE=yes
HAS_RESIZEABLE=no
am_usage '^[[:space:]]*resizeable[[:space:]]*<TASK_ID>' && HAS_RESIZEABLE=yes
HAS_MOVE_STACK=no
am_usage '^[[:space:]]*move-stack[[:space:]]*<STACK_ID>' && HAS_MOVE_STACK=yes
HAS_MOVE_TASK=no
am_usage '^[[:space:]]*move-task[[:space:]]*<TASK_ID>' && HAS_MOVE_TASK=yes
HAS_DISPLAY_OPT=no
has "$AM_HELP" '--display' && HAS_DISPLAY_OPT=yes

if [ "$HAS_RESIZE" = yes ]; then
  report 'P0.3' 'am task resize' PASS \
    '可以事后调整窗口大小。注意参数是 4 个分开的数字：am task resize ID LEFT TOP RIGHT BOTTOM'
else
  report 'P0.3' 'am task resize' FAIL '不可用 → 只能"启动时带尺寸"'
fi

# am help 里**没有** focus 子命令，但真机上 `am task focus <id>` 确实能跑
# （实测输出 "Setting focus to task 91"）→ 不靠 help 判断，交给 P4 实测。
report 'P0.4' 'am task focus' PASS 'am help 不列这个子命令，但真机可用；结论由 P4 实测决定'

if [ "$HAS_DISPLAY_OPT" = yes ]; then
  report 'P0.5' 'am start --display' PASS \
    '语法上支持指定 display 启动（但 shell 往系统持有的虚拟屏启动会被 SecurityException 拒，见 P18.3）'
else
  report 'P0.5' 'am start --display' FAIL '不支持指定屏启动'
fi

if [ "$HAS_MOVE_STACK" = yes ]; then
  report 'P0.6' 'am display move-stack' PASS \
    '可把整个 stack 搬到另一个屏（比"启动到虚拟屏"更可能被放行，因为没有 ActivityOptions 权限校验）'
else
  report 'P0.6' 'am display move-stack' FAIL '无此子命令'
fi

if [ "$HAS_MOVE_TASK" = yes ]; then
  report 'P0.7' 'am stack move-task' PASS '可把单个 task 搬到指定 stack'
else
  report 'P0.7' 'am stack move-task' FAIL '无此子命令'
fi

MW_OUT=$(sh_run 'am supports-multiwindow')
case "$MW_OUT" in
  *[Tt][Rr][Uu][Ee]*) HAS_MULTIWINDOW=yes ;;
  *)                  HAS_MULTIWINDOW=no ;;
esac
if [ "$HAS_MULTIWINDOW" = yes ]; then
  report 'P0.8' 'am supports-multiwindow' PASS "设备声明支持多窗口：$MW_OUT"
else
  report 'P0.8' 'am supports-multiwindow' FAIL "设备未声明支持多窗口：$MW_OUT"
fi

# --- P1 Freeform 开关 --------------------------------------------------------

FF_SETTING=$(sh_run 'settings get global enable_freeform_support' | tr -d '[:space:]')
FR_SETTING=$(sh_run 'settings get global force_resizable_activities' | tr -d '[:space:]')
FEATURES=$(sh_run 'pm list features')
printf '%s' "$FEATURES" | save_raw pm-list-features
FF_FEATURE=no
has "$FEATURES" 'android.software.freeform_window_management' && FF_FEATURE=yes

P1_DETAIL="enable_freeform_support=${FF_SETTING:-?} force_resizable_activities=${FR_SETTING:-?} FEATURE_FREEFORM_WINDOW_MANAGEMENT=$FF_FEATURE"
if [ "$FF_FEATURE" = yes ]; then
  report 'P1' 'Freeform 开关' PASS "$P1_DETAIL（系统特性已声明，无需重启）"
elif [ "$FF_SETTING" = "1" ]; then
  report 'P1' 'Freeform 开关' PASS "$P1_DETAIL（设置已开）"
else
  report 'P1' 'Freeform 开关' WARN "$P1_DETAIL（未开，且系统特性未声明）"
  if [ "$DO_APPLY" = 1 ]; then
    echo "  正在写入 enable_freeform_support=1 ..."
    sh_run 'settings put global enable_freeform_support 1' >/dev/null
    sh_run 'settings put global force_resizable_activities 1' >/dev/null
    echo "  enable_freeform_support -> $(sh_run 'settings get global enable_freeform_support')"
    echo "  force_resizable_activities -> $(sh_run 'settings get global force_resizable_activities')"
    echo "  AMS 在开机时读取该值，请重启手机后重新运行本脚本。"
  else
    echo '  提示：加 --apply 参数可自动写入（改完需要重启手机）。'
  fi
fi

# --- P2–P4 自由窗口启动与事后调尺寸 ------------------------------------------

BOUNDS_ARG=""
if [ "$HAS_BOUNDS" = yes ]; then BOUNDS_ARG=" --bounds $TEST_BOUNDS"; fi

PKG_INSTALLED=$(pkg_installed "$TEST_PACKAGE")
COMPONENT=$(resolve_component "$TEST_PACKAGE")
TASK_ID=""

if [ "$PKG_INSTALLED" = "0" ]; then
  report 'P2.0' '目标 App 是否存在' FAIL \
    "$TEST_PACKAGE 没装（pm list packages 查不到）→ 先确认真实包名；包名不对时 P2/P3/P18 的结论全部不可信"
  report 'P2' '自由窗口启动' FAIL "$TEST_PACKAGE 没装，启动不了自由窗口"
  report 'P3' 'am task resize' SKIP 'P2 未拿到 taskId'
  report 'P4' 'am task focus' SKIP 'P2 未拿到 taskId'
else
  if [ -n "$COMPONENT" ]; then
    report 'P2.0' '解析启动组件' PASS "$COMPONENT"
  else
    COMPONENT="$TEST_PACKAGE/"
    report 'P2.0' '解析启动组件' WARN "解析不到 $TEST_PACKAGE 的启动组件，退回包名（可能失败）"
  fi

  echo '  正在尝试以自由窗口启动测试应用 ...'
  LAUNCH_OUT=$(sh_run "am start -n $COMPONENT --windowingMode 5$BOUNDS_ARG")
  if matches "$LAUNCH_OUT" 'Error|Exception|does not exist'; then
    # 有些 ROM 需要先启动过该应用；用 monkey 兜底拉起一次再试
    sh_run "monkey -p $TEST_PACKAGE -c android.intent.category.LAUNCHER 1" >/dev/null
    sleep 1
    LAUNCH_OUT=$(sh_run "am start -n $COMPONENT --windowingMode 5$BOUNDS_ARG")
  fi
  sleep 2

  DUMP=$(sh_run 'dumpsys activity activities')
  printf '%s' "$DUMP" | save_raw dumpsys-activity-activities
  BLOCK=$(printf '%s' "$DUMP" | task_block "$TEST_PACKAGE")
  TASK_HEAD=$(printf '%s' "$BLOCK" | awk 'NR == 1 { print }')
  TASK_ID=$(printf '%s' "$TASK_HEAD" | grep -oE '#[0-9]+' | head -n 1 | tr -d '#')
  TASK_MODE=$(printf '%s' "$TASK_HEAD" | grep -oE '(windowingMode|mode)=[A-Za-z]+' | head -n 1)
  TASK_BOUNDS=$(printf '%s' "$BLOCK" | grep -oE 'mBounds=Rect\([^)]*\)' | head -n 1 | sed 's/^mBounds=Rect(//; s/)$//')

  if [ -z "$BLOCK" ]; then
    report 'P2' '自由窗口启动' FAIL "任务列表里找不到 $TEST_PACKAGE" "$LAUNCH_OUT"
  elif has "$BLOCK" 'freeform'; then
    report 'P2' '自由窗口启动' PASS "taskId=${TASK_ID:-?} mode=${TASK_MODE:-?} bounds=${TASK_BOUNDS:-?}"
  else
    report 'P2' '自由窗口启动' FAIL \
      "窗口起来了但不是自由窗口：taskId=${TASK_ID:-?} mode=${TASK_MODE:-?}（ROM 可能忽略了 --windowingMode）"
  fi

  if [ -n "$TASK_ID" ]; then
    # 真机实测（Android 17）：参数必须是四个分开的数字。
    # 逗号串会抛 java.lang.NumberFormatException: For input string: "0,0,540,1200"
    RESIZE_ARGS=$(printf '%s' "$TEST_BOUNDS" | tr ',' ' ')
    EXPECT_W=$(printf '%s' "$TEST_BOUNDS" | awk -F, '{ print $3 }')
    RESIZE_OUT=$(sh_run "am task resize $TASK_ID $RESIZE_ARGS")
    sleep 1
    DUMP3=$(sh_run 'dumpsys activity activities')
    printf '%s' "$DUMP3" | save_raw dumpsys-after-resize
    BLOCK3=$(printf '%s' "$DUMP3" | task_block "$TEST_PACKAGE")
    BOUNDS3=$(printf '%s' "$BLOCK3" | grep -oE 'mBounds=Rect\([^)]*\)' | head -n 1 | sed 's/^mBounds=Rect(//; s/)$//')

    if printf '%s' "$BOUNDS3" | tr -d ' ' | grep -qF "$EXPECT_W"; then
      report 'P3' 'am task resize' PASS "taskId=$TASK_ID bounds=$BOUNDS3"
    elif matches "$RESIZE_OUT" 'Error|Exception|not.*resiz'; then
      report 'P3' 'am task resize' FAIL \
        '命令被拒（参数必须是 4 个分开的数字：am task resize ID LEFT TOP RIGHT BOTTOM）' "$RESIZE_OUT"
    else
      report 'P3' 'am task resize' WARN \
        "命令没报错，但 bounds 未按预期变化（期望宽度 $EXPECT_W，实际 ${BOUNDS3:-读不到}）→ 任务可能不在 freeform 栈"
    fi

    FOCUS_OUT=$(sh_run "am task focus $TASK_ID")
    if matches "$FOCUS_OUT" 'Error|Exception|Unknown'; then
      report 'P4' 'am task focus' WARN '不可用 → 回退 am start --activity-reorder-to-front' "$FOCUS_OUT"
    else
      report 'P4' 'am task focus' PASS '命令执行成功' "$FOCUS_OUT"
    fi
  else
    report 'P3' 'am task resize' SKIP 'P2 未拿到 taskId'
    report 'P4' 'am task focus' SKIP 'P2 未拿到 taskId'
  fi
fi

# --- P5 多自由窗口共存 -------------------------------------------------------

SECOND_INSTALLED=$(pkg_installed "$SECOND_PACKAGE")
if [ "$SECOND_INSTALLED" = "0" ]; then
  report 'P5' '多自由窗口共存' SKIP \
    "第二个应用（$SECOND_PACKAGE）没装 → 用 --second-package 指定一个已安装的应用重跑，否则这条测不出来"
else
  SECOND_COMPONENT=$(resolve_component "$SECOND_PACKAGE")
  if [ -n "$SECOND_COMPONENT" ]; then
    sh_run "am start -n $SECOND_COMPONENT --windowingMode 5$BOUNDS_ARG" >/dev/null
  else
    sh_run "monkey -p $SECOND_PACKAGE -c android.intent.category.LAUNCHER 1" >/dev/null
  fi
  sleep 2
  DUMP5=$(sh_run 'dumpsys activity activities')
  printf '%s' "$DUMP5" | save_raw dumpsys-two-windows
  FF_HEADERS=$(printf '%s' "$DUMP5" | freeform_headers)
  FF_COUNT=$(printf '%s' "$FF_HEADERS" | awk '/Task\{/ { n++ } END { print n + 0 }')
  FF_LIST=$(printf '%s' "$FF_HEADERS" | awk '
    {
      id = ""; pkg = ""
      if (match($0, /#[0-9]+/)) id = substr($0, RSTART + 1, RLENGTH - 1)
      if (match($0, /A=[0-9]+:/)) {
        rest = substr($0, RSTART + RLENGTH)
        sub(/[^A-Za-z0-9_.].*$/, "", rest)
        pkg = rest
      }
      if (out != "") out = out ", "
      out = out pkg "#" id
    }
    END { print out }')

  if [ "$FF_COUNT" -ge 2 ]; then
    report 'P5' '多自由窗口共存' PASS "$FF_COUNT 个自由窗口：$FF_LIST"
  elif [ "$FF_COUNT" -eq 1 ]; then
    report 'P5' '多自由窗口共存' WARN '只检测到 1 个自由窗口 → ROM 可能限制为单小窗，方案降级为"1 小窗 + 快速切换"'
  else
    report 'P5' '多自由窗口共存' SKIP "第二个应用（$SECOND_PACKAGE）没能拉起自由窗口"
  fi
fi

# --- P6 multi-resume（需要人眼） ---------------------------------------------

report 'P6' '失焦仍在运行' SKIP '需要人眼确认：让两个窗口同屏，观察非焦点窗口的动画/计时是否继续'

# --- P7 保活命令 -------------------------------------------------------------

IDLE_LIST=$(sh_run 'dumpsys deviceidle whitelist')
APPOPS_OUT=$(sh_run "cmd appops get $TEST_PACKAGE RUN_ANY_IN_BACKGROUND")
BUCKET_OUT=$(sh_run "am get-standby-bucket $TEST_PACKAGE")
FREEZER=$(sh_run 'settings get global cached_apps_freezer')
P7_OK=yes
matches "$IDLE_LIST" 'Unknown|Error' && P7_OK=no
matches "$APPOPS_OUT" 'Unknown|Error' && P7_OK=no
matches "$BUCKET_OUT" 'Unknown|Error' && P7_OK=no
P7_DETAIL="deviceidle=$(first_line "$IDLE_LIST") appops=$(first_line "$APPOPS_OUT") bucket=$(first_line "$BUCKET_OUT") freezer=$FREEZER"
if [ "$P7_OK" = yes ]; then
  report 'P7' '保活命令' PASS "$P7_DETAIL"
else
  report 'P7' '保活命令' WARN "$P7_DETAIL"
fi

# --- P8 厂商小窗广播（可选） -------------------------------------------------

MODULE_OUT=$(sh_run 'pm list packages name.liuyi')
if has "$MODULE_OUT" 'liuyi'; then
  report 'P8' '厂商小窗广播' SKIP "检测到相关包：$(first_line "$MODULE_OUT")（可选通路，本项目不依赖它）"
else
  report 'P8' '厂商小窗广播' SKIP '未安装 hyper_freeform_x，跳过（不影响主流程）'
fi

# --- P9 小米私有小窗服务 mi_freeform ----------------------------------------

SERVICES=$(sh_run 'service list')
printf '%s' "$SERVICES" | save_raw service-list
if has "$SERVICES" 'mi_freeform'; then
  MI_DUMP=$(sh_run 'dumpsys mi_freeform')
  printf '%s' "$MI_DUMP" | save_raw dumpsys-mi-freeform
  report 'P9' 'mi_freeform 服务' PASS \
    '找到小米原生小窗服务。Mi-FreeForm 就是通过 binder 调它的 startAppInFreeform（尺寸/DPI 可控）；AIDL 需按 ROM 版本适配' \
    "$(first_line "$MI_DUMP")"
else
  report 'P9' 'mi_freeform 服务' SKIP '没有 mi_freeform：本机不是小米系，或该版本改名了。不影响 A/D 档'
fi

# --- P10 虚拟屏通路（D 档） --------------------------------------------------

case "$IDENTITY" in
  *uid=2000*) IS_SHELL=yes ;;
  *uid=0*)    IS_SHELL=root ;;
  *)          IS_SHELL=other ;;
esac
HAS_DISPLAY_SERVICE=no
if matches "$SERVICES" '(^|[[:space:]])display[[:space:]]' || matches "$SERVICES" 'display:'; then
  HAS_DISPLAY_SERVICE=yes
fi
VD_NOTE='虚拟屏必须用 shell(uid=2000) 身份创建：部分 ROM 的 DisplayManagerService 会校验 packageName 与调用者 uid，以 root 身份创建会抛 "packageName must match the calling uid"。'
if [ "$HAS_DISPLAY_SERVICE" = yes ] && [ "$IS_SHELL" = yes ]; then
  report 'P10' '虚拟屏通路' PASS "$VD_NOTE 当前身份：$IDENTITY"
else
  report 'P10' '虚拟屏通路' WARN "$VD_NOTE 当前身份：$IDENTITY ；display 服务=$HAS_DISPLAY_SERVICE"
fi

# --- P11 am 的 display 能力面（一键切前台的关键） ----------------------------

# 教训：`am display` 不带子命令会直接抛
#   java.lang.IllegalArgumentException: Argument expected after "display"
# 所以不能拿它的输出去判断能力面（早先版本就是用它 grep move-stack，永远查不到），只能读 am help。
AM_DISPLAY=$(sh_run 'am display')
printf '%s' "$AM_DISPLAY" | save_raw am-display
if [ "$HAS_DISPLAY_OPT" = yes ] && [ "$HAS_MOVE_STACK" = yes ]; then
  report 'P11' 'am display 能力面' PASS \
    "指定屏启动 --display=$HAS_DISPLAY_OPT ；跨屏搬 stack=$HAS_MOVE_STACK ；stack move-task=$HAS_MOVE_TASK" "$AM_DISPLAY"
elif [ "$HAS_DISPLAY_OPT" = yes ]; then
  report 'P11' 'am display 能力面' WARN \
    "指定屏启动 --display=$HAS_DISPLAY_OPT ；跨屏搬 stack=$HAS_MOVE_STACK ；stack move-task=$HAS_MOVE_TASK" "$AM_DISPLAY"
else
  report 'P11' 'am display 能力面' FAIL \
    "指定屏启动 --display=$HAS_DISPLAY_OPT ；跨屏搬 stack=$HAS_MOVE_STACK ；stack move-task=$HAS_MOVE_TASK" "$AM_DISPLAY"
fi

# --- P12 当前 display 列表（虚拟屏创建前后的基线） ---------------------------

DISPLAY_DUMP=$(sh_run 'dumpsys display')
printf '%s' "$DISPLAY_DUMP" | save_raw dumpsys-display
DISPLAY_COUNT=$(printf '%s' "$DISPLAY_DUMP" | grep -c 'DisplayDeviceInfo{' || true)
DISPLAY_BASE_IDS=$(printf '%s' "$DISPLAY_DUMP" | display_ids)
report 'P12' '当前 display 列表' PASS \
  "检测到 $DISPLAY_COUNT 个显示设备（displayId: ${DISPLAY_BASE_IDS:-解析失败}）。虚拟屏创建成功后这个数字应该增加；它也是判断'屏建出来没有'的基线"

# --- P17 目标 App 是否自带前台服务（决定要不要为它建虚拟屏） ------------------

SVC_DUMP=$(sh_run "dumpsys activity services $TEST_PACKAGE")
printf '%s' "$SVC_DUMP" | save_raw dumpsys-services-testpkg
if has "$SVC_DUMP" 'isForeground=true'; then
  report 'P17' '目标 App 自带前台服务' PASS \
    "$TEST_PACKAGE 有前台服务 → 轻量保活通常就够，不必为它建虚拟屏（省电省事）"
else
  report 'P17' '目标 App 自带前台服务' WARN \
    "$TEST_PACKAGE 没有前台服务 → 切到后台后大概率被暂停/冻结，需要虚拟屏才能持续运行"
fi

# --- P18 虚拟屏实验（M2 的 go/no-go，默认不跑） ------------------------------

if [ "$DO_VD" = 0 ]; then
  report 'P18' '虚拟屏实验' SKIP \
    '未启用。加 --vd 运行：它会临时写入 overlay_display_devices 造一块虚拟屏，跑完自动清理'
elif [ "$PKG_INSTALLED" = "0" ]; then
  report 'P18' '虚拟屏实验' SKIP \
    "目标 App（$TEST_PACKAGE）没装，先确认真实包名再跑，否则 P18.3 会给出误导性结论"
else
  echo
  echo '--- P18 虚拟屏实验（会临时修改系统设置，结束时自动清理）---'

  BASE_IDS=$(sh_run 'dumpsys display' | display_ids)
  report 'P18.1' 'display 基线' PASS "现有 displayId: ${BASE_IDS:-解析失败}"

  COMPONENT=$(resolve_component "$TEST_PACKAGE")
  if [ -z "$COMPONENT" ]; then COMPONENT="$TEST_PACKAGE/"; fi

  # 先正常启动一次，保证任务已存在，便于对比它在哪块屏上是 RESUMED
  sh_run "am start -n $COMPONENT" >/dev/null
  sleep 2

  sh_run "settings put global overlay_display_devices $VD_SPEC" >/dev/null
  VD_TOUCHED=1
  sleep 3

  VD_DUMP=$(sh_run 'dumpsys display')
  printf '%s' "$VD_DUMP" | save_raw dumpsys-display-with-vd
  VD_IDS=$(printf '%s' "$VD_DUMP" | display_ids)
  NEW_ID=""
  for id in $VD_IDS; do
    case " $BASE_IDS " in
      *" $id "*) ;;
      *) NEW_ID="$id"; break ;;
    esac
  done

  if [ -z "$NEW_ID" ]; then
    report 'P18.2' '虚拟屏是否创建' FAIL \
      "写入 overlay_display_devices=$VD_SPEC 后没有出现新的 displayId（现有：${VD_IDS:-无}）。本机虚拟屏通路可能被 ROM 关闭"
  else
    report 'P18.2' '虚拟屏是否创建' PASS "新增 displayId=$NEW_ID（规格 $VD_SPEC）"

    # 实测坑：往"系统持有"的 overlay display 里启动 App，shell 会被拒：
    #   java.lang.SecurityException: Permission Denial: starting Intent {...} from null
    #   (pid=..., uid=2000) with launchDisplayId=N
    #   at com.android.server.wm.SafeActivityOptions.checkPermissions
    # 含义：overlay_display_devices 造出来的屏 ownerUid=system，不能当作 D 档容器用。
    # 这不代表虚拟屏路线死了——生产实现必须由**我们自己**创建并持有那块屏（shell uid 为 owner）。
    VD_LAUNCH_OUT=$(sh_run "am start --display $NEW_ID -n $COMPONENT")
    sleep 3
    DENIED=no
    matches "$VD_LAUNCH_OUT" 'SecurityException|Permission Denial' && DENIED=yes

    VD_TASKS=$(sh_run 'dumpsys activity activities')
    printf '%s' "$VD_TASKS" | save_raw dumpsys-activity-with-vd
    VD_BLOCK=$(printf '%s' "$VD_TASKS" | task_block "$TEST_PACKAGE")
    VD_TASK_DISPLAY=$(printf '%s' "$VD_BLOCK" | grep -oE 'displayId[ =]+[0-9]{1,3}' | head -n 1 | grep -oE '[0-9]+')

    if [ "$DENIED" = yes ]; then
      report 'P18.3' '虚拟屏上的 App 状态' WARN \
        "屏建出来了（displayId=$NEW_ID），但 shell 无权往这块系统持有的屏启动 App：SecurityException at SafeActivityOptions.checkPermissions。结论：overlay_display_devices 的屏不能作为 D 档容器，必须自建（ownerUid=2000）。" \
        "$VD_LAUNCH_OUT"

      # 换一条不走 ActivityOptions 的路：把已有 stack 整体搬到虚拟屏
      STACK_OUT=$(sh_run 'am stack list')
      printf '%s' "$STACK_OUT" | save_raw am-stack-list
      STACK_ID=$(printf '%s' "$STACK_OUT" | grep -oE 'Stack id=[0-9]+' | head -n 1 | grep -oE '[0-9]+')
      if [ -n "$STACK_ID" ]; then
        MOVE_OUT=$(sh_run "am display move-stack $STACK_ID $NEW_ID")
        if matches "$MOVE_OUT" 'Error|Exception|Unknown'; then
          report 'P18.3b' "把已有 stack($STACK_ID) 搬到虚拟屏" WARN \
            '这条能过的话，D 档可改成「正常启动 → 搬到虚拟屏」，绕开 SafeActivityOptions 的权限校验' "$MOVE_OUT"
        else
          report 'P18.3b' "把已有 stack($STACK_ID) 搬到虚拟屏" PASS \
            '这条能过的话，D 档可改成「正常启动 → 搬到虚拟屏」，绕开 SafeActivityOptions 的权限校验' "$MOVE_OUT"
        fi
      else
        # Android 17 的 am stack list 已经没有 "Stack id" 概念了，改用 RootTask
        # （RootTask id=91 bounds=[0,0][540,1200] displayId=0）→ 这里降级为 SKIP，不是 FAIL。
        ROOTTASK_ID=$(printf '%s' "$STACK_OUT" | grep -oE 'RootTask id=[0-9]+' | head -n 1 | grep -oE '[0-9]+')
        report 'P18.3b' 'am stack list' SKIP \
          "读不到 Stack id（Android 17 已改用 RootTask${ROOTTASK_ID:+，当前 RootTask id=$ROOTTASK_ID}），跳过往虚拟屏搬 stack 的尝试"
      fi
    elif has "$VD_BLOCK" 'state=RESUMED'; then
      report 'P18.3' '虚拟屏上的 App 状态' PASS \
        "$TEST_PACKAGE 在 displayId=${VD_TASK_DISPLAY:-?} 上 state=RESUMED → 虚拟屏通路成立，架构定稿可推进"
    elif has "$VD_BLOCK" 'state=STOPPED'; then
      report 'P18.3' '虚拟屏上的 App 状态' FAIL \
        "$TEST_PACKAGE state=STOPPED → 虚拟屏留不住 App，需求要退到 D2 微窗或 C 档"
    else
      report 'P18.3' '虚拟屏上的 App 状态' WARN \
        "读不到明确的 state。先确认 P2.0（包名对不对）与 P18.2，再判断是不是虚拟屏的问题。原始片段：$(first_line "$VD_BLOCK")"
    fi

    report 'P18.4' '锁屏存活（手测）' SKIP \
      "保持现状 → 锁屏 10 分钟 → 解锁看 $TEST_PACKAGE 是否还在跑（对应 P13）"
  fi

  cleanup_vd
fi

# --- 汇总 --------------------------------------------------------------------

echo
echo "--- 汇总 ---"
printf 'PASS=%s  WARN=%s  FAIL=%s\n' "$PASS_N" "$WARN_N" "$FAIL_N"

# 结论只看**关键能力项**，不看 FAIL 个数：
# 早先版本曾因为三个 bug（am task resize 参数写法、displayId 解析、am help 匹配方式）
# 把一台完全支持自由窗口的机器误判成"走不通"。这里只认 P2 / P3 / P4。
S2=$(status_of P2); S3=$(status_of P3); S4=$(status_of P4)
echo
printf '关键三项：P2 自由窗口启动=%s   P3 事后改尺寸=%s   P4 一键前置=%s\n' "$S2" "$S3" "$S4"

if [ "$S2" = PASS ] && [ "$S3" = PASS ] && [ "$S4" = PASS ]; then
  echo '结论：【档位 A · 真同屏平铺】成立 —— 自由窗口起得来、尺寸改得动、任务前置得了。'
  echo '      下一步：装 App 直接试 MAIN_3MINI 预设。虚拟屏（D 档"后台不占屏"）是可选增强，不代表 A 档不可用。'
elif [ "$S2" = PASS ]; then
  echo '结论：自由窗口能起来，但"改尺寸/前置"有缺项 → 先看 P3/P4 的原始报错再定，别直接判"走不通"。'
  echo '      退路：档位 B/C（HyperOS 自带小窗 + Shizuku 保活 + 一键切换）。'
else
  echo '结论：自由窗口起不来 → 改用【档位 B/C】：HyperOS 自带小窗 + Shizuku 保活 + 一键切换。'
fi

echo
echo "原始输出: $LOG_DIR"
echo "提醒：默认只读；--apply 写 enable_freeform_support（需重启）；--vd 临时改 overlay_display_devices（退出自动还原）。"

[ "$FAIL_N" -eq 0 ] || exit 1
exit 0
