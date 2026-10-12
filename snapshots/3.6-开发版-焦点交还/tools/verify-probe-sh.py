#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""probe-hyperos.sh 的静态检查 + 关键解析逻辑等价验证。

为什么需要它：写脚本的机器是 Windows，没有 bash / awk / WSL 发行版，
无法直接执行 probe-hyperos.sh。于是用两件事提高把握：

  1. 静态检查 bash 关键字配平（if/then/fi、do/done、case/esac）与引号闭合
     ——这是最常见的语法崩溃来源；
  2. 把脚本里依赖 awk / grep 的两段解析逻辑（task_block、display_ids）等价移植到 Python，
     用构造数据验证其正确性，并额外证明"重复输出"这类缺陷确实能被检出
     （这个缺陷真的出现过：awk 的 exit 会跳到 END，END 里不判标志位就会打印两次）。

真实 bash / awk 行为仍必须在手机上用 `bash probe-hyperos.sh --selftest` 确认。
"""

import re
import sys
from pathlib import Path

try:
    sys.stdout.reconfigure(encoding="utf-8", errors="replace")
except Exception:
    pass

SH = Path(__file__).resolve().parent / "probe-hyperos.sh"

# ------------------------------------------------------------------ 静态检查


def strip_code(source: str):
    """去掉注释与字符串内容，返回纯代码文本 + 引号闭合情况。

    注意：脚本里有跨行的单引号字符串（selftest 的样例数据），所以扫描必须带状态。
    """
    out = []
    i = 0
    n = len(source)
    quote = None          # None | "'" | '"'
    closed = True
    while i < n:
        ch = source[i]
        if quote == "'":
            if ch == "'":
                quote = None
            i += 1
            continue
        if quote == '"':
            if ch == "\\":
                i += 2
                continue
            if ch == '"':
                quote = None
            i += 1
            continue
        if ch in ("'", '"'):
            quote = ch
            i += 1
            continue
        if ch == "#":
            # 只有位于词首或空白之后的 # 才是注释（避免误伤 ${#var}）
            if i == 0 or source[i - 1] in " \t\n;":
                while i < n and source[i] != "\n":
                    i += 1
                continue
        out.append(ch)
        i += 1
    if quote is not None:
        closed = False
    return "".join(out), closed


def count_word(code: str, word: str) -> int:
    return len(re.findall(r"(?<![\w-])" + word + r"(?![\w-])", code))


def static_checks():
    problems = []
    source = SH.read_text(encoding="utf-8")
    code, quotes_closed = strip_code(source)

    if not quotes_closed:
        problems.append("引号未闭合：文件结束时仍在字符串内部")

    pairs = [
        ("if", "fi", "if"),
        ("then", None, "then"),
        ("while", "done", "while"),
        ("case", "esac", "case"),
        ("do", "done", "do"),
    ]
    if_n = count_word(code, "if")
    elif_n = count_word(code, "elif")
    then_n = count_word(code, "then")
    fi_n = count_word(code, "fi")
    if then_n != if_n + elif_n:
        problems.append(f"then({then_n}) != if({if_n}) + elif({elif_n})")
    if fi_n != if_n:
        problems.append(f"fi({fi_n}) != if({if_n})")

    do_n = count_word(code, "do")
    done_n = count_word(code, "done")
    if do_n != done_n:
        problems.append(f"do({do_n}) != done({done_n})")

    case_n = count_word(code, "case")
    esac_n = count_word(code, "esac")
    if case_n != esac_n:
        problems.append(f"case({case_n}) != esac({esac_n})")

    # 函数定义数量应该是整数、且每个函数名唯一
    funcs = re.findall(r"^([A-Za-z_][A-Za-z0-9_]*)\s*\(\)\s*\{", code, re.M)
    if len(funcs) != len(set(funcs)):
        dupes = sorted({f for f in funcs if funcs.count(f) > 1})
        problems.append(f"重复定义的函数: {', '.join(dupes)}")

    # awk 的 task_block 必须有 found 标志位，否则 exit+END 会打印两次
    if "found = 1" not in source or "!found" not in source:
        problems.append("task_block 的 awk 缺少 found 标志位（exit 后 END 会重复打印）")

    return problems, {"if": if_n, "elif": elif_n, "then": then_n, "fi": fi_n,
                      "do": do_n, "done": done_n, "case": case_n, "esac": esac_n,
                      "functions": len(funcs)}


# ------------------------------------------------------------------ 逻辑等价移植

TASK_HEADER = re.compile(r"Task\{[^}]*#[0-9]+")


def task_block(dump: str, pkg: str, with_found_guard: bool = True) -> str:
    """忠实移植脚本里的 awk 程序。

    awk 的结构是：规则里 printf + exit → **跳到 END 规则继续执行**。
    所以 END 里如果不判标志位，同一块会被打印两次——这正是修复前的缺陷。
    with_found_guard=False 用来复现该缺陷，作为"这项检查有牙齿"的负对照。
    """
    buf = ""
    printed_before_exit = None

    for line in dump.split("\n"):
        if TASK_HEADER.search(line):
            if buf and re.search(pkg, buf):
                printed_before_exit = buf
                break
            buf = line + "\n"
            continue
        if buf:
            buf += line + "\n"

    if printed_before_exit is not None:
        result = printed_before_exit
        # END 规则：修复前没有 found 标志位，这里会再打印一次
        if not with_found_guard and buf and re.search(pkg, buf):
            result += buf
        return result

    # 循环正常走完，进入 END 规则
    if buf and re.search(pkg, buf):
        return buf
    return ""


def display_ids(dump: str):
    """等价于 grep -oE 'displayId[ =]+[0-9]+' | grep -oE '[0-9]+' | sort -n -u"""
    ids = set()
    for m in re.finditer(r"displayId[ =]+[0-9]+", dump):
        digit = re.search(r"[0-9]+", m.group(0))
        if digit:
            ids.add(int(digit.group(0)))
    return sorted(ids)


SAMPLE = (
    "  * Task{8f8d3c1 #123 type=standard A=10123:com.foo.bar U=0 visible=true}\n"
    "    mBounds=Rect(0, 0 - 540, 1200)\n"
    "    * ActivityRecord{111 u0 com.foo.bar/.Main t123} state=RESUMED\n"
    "  * Task{99 #456 type=standard A=10124:com.baz U=0 visible=false}\n"
    "    * ActivityRecord{222 u0 com.baz/.Main t456} state=STOPPED\n"
)


def logic_checks():
    results = []
    ok = True

    block1 = task_block(SAMPLE, "com.foo.bar")
    expected1 = (
        "  * Task{8f8d3c1 #123 type=standard A=10123:com.foo.bar U=0 visible=true}\n"
        "    mBounds=Rect(0, 0 - 540, 1200)\n"
        "    * ActivityRecord{111 u0 com.foo.bar/.Main t123} state=RESUMED\n"
    )
    if block1 == expected1:
        results.append(("OK", "task_block: 精确匹配目标块（含 mBounds 与 state）"))
    else:
        results.append(("FAIL", f"task_block: 精确匹配失败，得到 {block1!r}"))
        ok = False

    if block1.count("Task{") == 1:
        results.append(("OK", "task_block: 只出现一个 Task 头，没有串块/重复"))
    else:
        results.append(("FAIL", f"task_block: Task{{ 出现 {block1.count('Task{')} 次"))
        ok = False

    if "com.baz" not in block1:
        results.append(("OK", "task_block: 没有把下一个任务的包名带进来"))
    else:
        results.append(("FAIL", "task_block: 串到了下一个任务块"))
        ok = False

    block2 = task_block(SAMPLE, "com.baz")
    if "state=STOPPED" in block2 and "com.foo.bar" not in block2:
        results.append(("OK", "task_block: 第二个包解析正确"))
    else:
        results.append(("FAIL", f"task_block: 第二个包解析错误 {block2!r}"))
        ok = False

    if task_block(SAMPLE, "com.nothere") == "":
        results.append(("OK", "task_block: 包名不存在时返回空"))
    else:
        results.append(("FAIL", "task_block: 包名不存在时不该有输出"))
        ok = False

    # 证明这项检查有牙齿：复现修复前的版本，应当出现两次打印
    buggy = task_block(SAMPLE, "com.foo.bar", with_found_guard=False)
    if buggy.count("Task{") == 2:
        results.append(("OK", "回归验证: 去掉 found 标志位确实会重复打印 2 次（说明检查有效）"))
    else:
        results.append(("FAIL", f"回归验证: 复现失败，buggy 版本 Task{{ 出现 {buggy.count('Task{')} 次"))
        ok = False

    display_dump = (
        'DisplayDeviceInfo{"Built-in": uniqueId="local:0", 1080 x 2400, displayId 0}\n'
        'DisplayDeviceInfo{"Overlay #1": uniqueId="overlay:1", displayId 1}\n'
        'DisplayDeviceInfo{"Third": uniqueId="x:3", displayId 3}\n'
    )
    ids = display_ids(display_dump)
    if ids == [0, 1, 3]:
        results.append(("OK", "display_ids: 提取到 [0, 1, 3]"))
    else:
        results.append(("FAIL", f"display_ids: 期望 [0,1,3]，得到 {ids}"))
        ok = False

    if display_ids("no displays here") == []:
        results.append(("OK", "display_ids: 无匹配时返回空"))
    else:
        results.append(("FAIL", "display_ids: 无匹配时应为空"))
        ok = False

    return ok, results


def main():
    print("=== probe-hyperos.sh 静态检查 ===")
    problems, stats = static_checks()
    print("关键字计数: " + "  ".join(f"{k}={v}" for k, v in stats.items()))
    if problems:
        for p in problems:
            print("  [FAIL] " + p)
    else:
        print("  [OK] 关键字配平、引号闭合、函数名唯一、awk 有 found 保护")

    print("\n=== 解析逻辑等价验证（Python 移植）===")
    ok, results = logic_checks()
    for status, text in results:
        print(f"  [{'OK' if status == 'OK' else 'FAIL'}] {text}")

    print("\n真实 bash / awk 行为请再跑一次：bash tools/probe-hyperos.sh --selftest")
    return 0 if (not problems and ok) else 1


if __name__ == "__main__":
    sys.exit(main())
