#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""
布局引擎交叉校验（开发期工具，不是最终测试）。

为什么要有一份 Python 移植：
  LayoutEngine 是最容易算错的部分，而它又是纯函数。把它逐行移植到 Python 后，
  可以在没有 JDK / Android SDK 的机器上瞬间跑完"每种预设 × 每种槽位数 × 多种屏幕尺寸"
  的不变量检查，先把数学问题杀掉，再上真机。
  权威测试仍是 app/src/test/.../LayoutEngineTest.kt —— 两边的不变量必须一致。

它已经抓到过一个真实缺陷：等份切分的权重没有归一化（[1,1] 被算成"第一个占满、其余为 0"），
会产生零宽窗口。

同时顺带做一次 .kt 文件的括号配平安检（剥掉注释、字符串、字符字面量后计数），
用来抓"少一个大括号"这种低级但致命的错误。
"""

import sys

try:
    sys.stdout.reconfigure(encoding="utf-8", errors="replace")
except Exception:
    pass

import re
from pathlib import Path

GAP = 8

# ------------------------------------------------------------------ 布局引擎移植


def _sizes(total, weights):
    """按相对权重分配像素，取整余量给第 0 位（主位）。"""
    n = len(weights)
    sum_w = sum(weights) if sum(weights) > 0 else float(n)
    sizes = [0] * n
    assigned = 0
    for i in range(1, n):
        sizes[i] = max(0, int(total * (weights[i] / sum_w)))
        assigned += sizes[i]
    sizes[0] = max(0, total - assigned)
    return sizes


def split_vertical(area, weights):
    """area = (left, top, right, bottom)"""
    n = len(weights)
    if n == 0:
        return []
    if n == 1:
        return [area]

    left, top, right, bottom = area
    total = (bottom - top) - GAP * (n - 1)
    if total <= 0:
        return [(left, top, right, top) for _ in range(n)]

    sizes = _sizes(total, weights)
    out = []
    y = top
    for i in range(n):
        b = min(y + sizes[i], bottom)
        out.append((left, y, right, b))
        y = b + GAP
    return out


def split_horizontal(area, weights):
    n = len(weights)
    if n == 0:
        return []
    if n == 1:
        return [area]

    left, top, right, bottom = area
    total = (right - left) - GAP * (n - 1)
    if total <= 0:
        return [(left, top, left, bottom) for _ in range(n)]

    sizes = _sizes(total, weights)
    out = []
    x = left
    for i in range(n):
        r = min(x + sizes[i], right)
        out.append((x, top, r, bottom))
        x = r + GAP
    return out


def stack_preset(a, n):
    if n == 1:
        return [a]
    if n == 2:
        return split_vertical(a, [0.5, 0.5])
    rows = split_vertical(a, [0.6, 0.4])
    return [rows[0]] + split_horizontal(rows[1], [1.0] * (n - 1))


def side_preset(a, n):
    if n == 1:
        return [a]
    if n == 2:
        return split_horizontal(a, [0.5, 0.5])
    cols = split_horizontal(a, [0.6, 0.4])
    return [cols[0]] + split_vertical(cols[1], [1.0] * (n - 1))


def main_side(a, n):
    if n == 1:
        return [a]
    rows = split_vertical(a, [0.62, 0.38])
    return [rows[0]] + split_horizontal(rows[1], [1.0] * (n - 1))


def main_three_mini(a, n):
    if n == 1:
        return [a]
    cols = split_horizontal(a, [0.66, 0.34])
    return [cols[0]] + split_vertical(cols[1], [1.0] * (n - 1))


def grid(a, n):
    if n == 1:
        return [a]
    if n == 2:
        return split_vertical(a, [0.5, 0.5])
    if n == 3:
        rows = split_vertical(a, [0.5, 0.5])
        return [rows[0]] + split_horizontal(rows[1], [0.5, 0.5])
    if n == 4:
        rows = split_vertical(a, [0.5, 0.5])
        return split_horizontal(rows[0], [0.5, 0.5]) + split_horizontal(rows[1], [0.5, 0.5])

    rows = split_vertical(a, [0.4, 0.6])
    rest = n - 1
    row_count = (rest + 1) // 2
    row_strips = split_vertical(rows[1], [1.0] * row_count)
    out = [rows[0]]
    placed = 0
    for r in range(row_count):
        remaining = rest - placed
        if remaining <= 0:
            break
        if remaining == 1:
            out.append(row_strips[r])
            placed += 1
        else:
            out.extend(split_horizontal(row_strips[r], [0.5, 0.5]))
            placed += 2
    return out


PRESETS = {
    "STACK_2": stack_preset,
    "SIDE_2": side_preset,
    "MAIN_SIDE": main_side,
    "GRID_4": grid,
    "MAIN_3MINI": main_three_mini,
}


def area_of(b):
    return max(0, b[2] - b[0]) * max(0, b[3] - b[1])


def overlaps(x, y):
    return x[0] < y[2] and y[0] < x[2] and x[1] < y[3] and y[1] < x[3]


AREAS = [
    ("portrait 1080x2400 (with insets)", (0, 120, 1080, 2328)),
    ("portrait 1080x2400 (full)", (0, 0, 1080, 2400)),
    ("small 720x1280", (0, 60, 720, 1220)),
    ("large 1440x3200", (0, 140, 1440, 3100)),
    ("tablet 2560x1600", (0, 80, 2560, 1520)),
    ("with side inset", (48, 120, 1032, 2328)),
]


def verify_layouts():
    failures = []
    checks = 0
    for label, area in AREAS:
        for name, fn in PRESETS.items():
            for n in range(1, 8):
                result = fn(area, n)
                checks += 1

                if len(result) != n:
                    failures.append(f"{label} / {name} n={n}: got {len(result)}, want {n}")
                    continue

                for i, b in enumerate(result):
                    if not (b[2] > b[0] and b[3] > b[1]):
                        failures.append(f"{label} / {name} n={n} #{i}: invalid rect {b}")
                    if not (b[0] >= area[0] and b[1] >= area[1]
                            and b[2] <= area[2] and b[3] <= area[3]):
                        failures.append(f"{label} / {name} n={n} #{i}: out of bounds {b} in {area}")

                for i in range(n):
                    for j in range(i + 1, n):
                        if overlaps(result[i], result[j]):
                            failures.append(
                                f"{label} / {name} n={n}: #{i}{result[i]} overlaps #{j}{result[j]}")

                if n > 1:
                    pa = area_of(result[0])
                    for i in range(1, n):
                        if pa < area_of(result[i]):
                            failures.append(
                                f"{label} / {name} n={n}: primary {pa} < #{i} {area_of(result[i])}")
    return checks, failures


def verify_tiny_area():
    """极小可用区属于降级路径：允许零面积矩形，但不允许崩溃或数量不对。"""
    problems = []
    for name, fn in PRESETS.items():
        for n in range(1, 8):
            try:
                result = fn((0, 0, 40, 40), n)
            except Exception as exc:  # noqa: BLE001
                problems.append(f"{name} n={n}: raised {exc}")
                continue
            if len(result) != n:
                problems.append(f"{name} n={n}: got {len(result)}")
    return problems


# ------------------------------------------------------------------ Kotlin 安检

KT_STRINGS = re.compile(
    r'"""[\s\S]*?"""'      # raw string
    r'|"(?:\\.|[^"\\])*"'  # normal string
    r"|'(?:\\.|[^'\\])*'"  # char literal
)
KT_BLOCK_COMMENT = re.compile(r'/\*[\s\S]*?\*/')
KT_LINE_COMMENT = re.compile(r'//[^\n]*')


def strip_kt(source: str) -> str:
    source = KT_BLOCK_COMMENT.sub(' ', source)
    source = KT_LINE_COMMENT.sub(' ', source)
    source = KT_STRINGS.sub('""', source)
    return source


def verify_kt_balance(root: Path):
    report = []
    for path in sorted(root.rglob("*.kt")):
        raw = path.read_text(encoding="utf-8")
        code = strip_kt(raw)
        pairs = {'{': '}', '(': ')', '[': ']'}
        counts = {c: code.count(c) for c in '{}()[]'}
        bad = [f"{o}{c}={counts[o]}!={counts[c]}" for o, c in pairs.items() if counts[o] != counts[c]]
        report.append((str(path), bad))
    return report


def main():
    root = Path(__file__).resolve().parent.parent
    print("=== layout invariants ===")
    checks, failures = verify_layouts()
    print(f"combinations checked: {checks}")
    if failures:
        print(f"FAILED: {len(failures)}")
        for f in failures[:40]:
            print("  [FAIL]", f)
    else:
        print("[OK] count / validity / in-bounds / no-overlap / primary-is-largest")

    tiny = verify_tiny_area()
    print("\n=== tiny area degradation ===")
    print("[OK] count correct (zero-area rects allowed)" if not tiny
          else "\n".join("  [FAIL] " + t for t in tiny))

    print("\n=== kotlin bracket balance ===")
    report = verify_kt_balance(root)
    bad_files = [(p, b) for p, b in report if b]
    print(f"files checked: {len(report)}")
    if bad_files:
        for p, b in bad_files:
            print("  [FAIL]", Path(p).name, "; ".join(b))
    else:
        print("[OK] all balanced")

    return 0 if not failures and not bad_files else 1


if __name__ == "__main__":
    sys.exit(main())
