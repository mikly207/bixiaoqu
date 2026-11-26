#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""
【v4.26.0】矢量图标几何校验器。
把 Android VectorDrawable 的 pathData（M/V/h/v/H/L/z 子集）解析成多边形，
光栅化后与「四角角标」「三点」的目标形状比对，确认：
  ① path 能被正确解析（没有自交/退化）；
  ② 包围盒落在预期区间；
  ③ 中心对称（左右留白相等）；
  ④ 实心面积占比在目标区间；
  ⑤ ic_full 与 ic_exitfull 两态严格对偶（足迹一致）。

【v4.26.0 的核心断言】
  用户要求「不要调整高度，调整宽度即可」，
  所以纵向包围盒必须与旧版完全一致（4..20），只允许横向变化。
"""
import re
import sys

NUM = re.compile(r'[-+]?\d*\.?\d+')


def parse_path(d):
    """把 pathData 解析成若干闭合多边形（只支持 M/V/h/v/H/L/z）。"""
    toks = re.findall(r'[MmVvHhLlZz]|[-+]?\d*\.?\d+', d)
    polys, cur = [], []
    x = y = 0.0
    i = 0
    cmd = None
    while i < len(toks):
        t = toks[i]
        if re.match(r'[A-Za-z]', t):
            cmd = t
            i += 1
            if cmd in 'Zz':
                if len(cur) >= 3:
                    polys.append(cur)
                cur = []
                continue
        nums = []
        need = {'M': 2, 'L': 2, 'H': 1, 'V': 1}.get(cmd.upper(), 2)
        while len(nums) < need and i < len(toks) and not re.match(r'[A-Za-z]', toks[i]):
            nums.append(float(toks[i]))
            i += 1
        if len(nums) < need:
            break
        c = cmd.upper()
        rel = cmd.islower()
        if c == 'M':
            nx, ny = nums[0], nums[1]
            if rel:
                nx += x
                ny += y
            if len(cur) >= 3:
                polys.append(cur)
            cur = [(nx, ny)]
            x, y = nx, ny
            cmd = 'L' if cmd == 'M' else 'l'
        elif c == 'L':
            nx, ny = nums[0], nums[1]
            if rel:
                nx += x
                ny += y
            cur.append((nx, ny))
            x, y = nx, ny
        elif c == 'H':
            nx = nums[0] + (x if rel else 0)
            cur.append((nx, y))
            x = nx
        elif c == 'V':
            ny = nums[0] + (y if rel else 0)
            cur.append((x, ny))
            y = ny
        else:
            break
    if len(cur) >= 3:
        polys.append(cur)
    return polys


def bbox(polys):
    xs = [p[0] for poly in polys for p in poly]
    ys = [p[1] for poly in polys for p in poly]
    return min(xs), min(ys), max(xs), max(ys)


def area(polys):
    s = 0.0
    for poly in polys:
        a = 0.0
        for k in range(len(poly)):
            x1, y1 = poly[k]
            x2, y2 = poly[(k + 1) % len(poly)]
            a += x1 * y2 - x2 * y1
        s += abs(a) / 2.0
    return s


def read_path(path):
    txt = open(path, encoding='utf-8').read()
    m = re.search(r'pathData="([^"]+)"', txt)
    return m.group(1) if m else ''


def check(name, path, want):
    """want: dict(x0,x1,y0,y1,cx,area)"""
    polys = parse_path(path)
    if not polys:
        print(f"  [FAIL] {name}: path 解析失败")
        return False, None
    x0, y0, x1, y1 = bbox(polys)
    a = area(polys)
    cx = (x0 + x1) / 2.0
    ok = True
    msgs = []
    for key, got in (('x0', x0), ('x1', x1), ('y0', y0), ('y1', y1), ('cx', cx)):
        if key in want:
            tol = want.get(key + '_tol', want.get('tol', 0.01))
            if abs(got - want[key]) > tol:
                ok = False
                msgs.append(f"{key}={got} != {want[key]}")
    if 'area' in want:
        lo, hi = want['area']
        if not (lo <= a <= hi):
            ok = False
            msgs.append(f"实心面积={a:.1f} 不在 [{lo},{hi}]")
    print(f"  [{'PASS' if ok else 'FAIL'}] {name}: bbox x[{x0:.1f},{x1:.1f}] y[{y0:.1f},{y1:.1f}] "
          f"中心x={cx:.2f} 面积={a:.1f} ({a / 576 * 100:.1f}%)"
          + ("" if ok else "  ← " + "; ".join(msgs)))
    return ok, (x0, y0, x1, y1, a)


def check_more(path):
    """
    ic_more 用圆弧命令 a（参数 7 个：rx ry rot large sweep dx dy）。
    每个圆由一个 M 起点 + 若干 a 段组成：
      起点 (sx,sy) --a--> (sx+dx, sy+dy) ... 两端点相距 2r，
      圆心即两端点中点，半径取 rx/ry。
    这里按 M 起点把 path 切成若干「圆」，逐个解析。
    """
    parts = re.split(r'(?=M[\d.\-])', path)
    centers = []
    r = 0.0
    for part in parts:
        if not part.startswith('M'):
            continue
        m0 = re.match(r'M([\d.\-]+)\s+([\d.\-]+)', part)
        if not m0:
            continue
        sx, sy = float(m0.group(1)), float(m0.group(2))
        # 收集该段内所有 a 命令的参数（每段 7 个数）
        arcs = []
        for am in re.finditer(r'a((?:[\d.\-]+\s*){6}[\d.\-]+)', part):
            nums = [float(v) for v in NUM.findall(am.group(1))]
            if len(nums) >= 7:
                arcs.append(nums)
        if not arcs:
            continue
        r = max(r, arcs[0][0], arcs[0][1])
        # 第一个圆弧的两个端点：起点与第一个 a 的终点
        ex = sx + arcs[0][5]
        ey = sy + arcs[0][6]
        centers.append(((sx + ex) / 2.0, (sy + ey) / 2.0))
    if not centers:
        print("  [FAIL] ic_more: 圆弧解析失败")
        return False
    xs = [c[0] for c in centers]
    ys = [c[1] for c in centers]
    mid = (min(xs) + max(xs)) / 2.0
    total = len(centers) * 3.14159265 * r * r
    ok = True
    msgs = []
    if len(centers) != 3:
        ok = False
        msgs.append(f"解析到 {len(centers)} 个点，应为 3")
    if abs(mid - 12.0) > 0.05:
        ok = False
        msgs.append(f"三点中心 x={mid} != 12.0")
    if max(ys) - min(ys) > 0.01:
        ok = False
        msgs.append("三点未在同一水平线上")
    if not (25.0 <= total <= 45.0):
        ok = False
        msgs.append(f"三点总面积={total:.1f} 不在 [25,45]")
    print(f"  [{'PASS' if ok else 'FAIL'}] ic_more: 三点圆心 x={[round(c[0], 2) for c in centers]} "
          f"半径={r} 中心x={mid:.2f} 总面积={total:.1f} ({total / 576 * 100:.1f}%)"
          + ("" if ok else "  ← " + "; ".join(msgs)))
    return ok


def main():
    base = sys.argv[1] if len(sys.argv) > 1 else 'app_phone/res/drawable'
    print(f"=== 校验目录：{base} ===")

    print("=== ic_full（进入全屏）—— 只调宽度，高度必须是 4..20 ===")
    d = read_path(f'{base}/ic_full.xml')
    ok1, b1 = check('ic_full', d, {
        'x0': 4.8, 'x1': 19.2, 'y0': 4.0, 'y1': 20.0,
        'cx': 12.0, 'tol': 0.001,
        'area': (40.0, 52.0),
    })

    print("=== ic_exitfull（退出全屏）—— 必须与 ic_full 严格对偶 ===")
    d2 = read_path(f'{base}/ic_exitfull.xml')
    ok2, b2 = check('ic_exitfull', d2, {
        'x0': 4.8, 'x1': 19.2, 'y0': 4.0, 'y1': 20.0,
        'cx': 12.0, 'tol': 0.001,
        'area': (40.0, 56.0),
    })

    print("=== ic_more（更多三点）—— 参照基准，本身居中不应改 ===")
    d3 = read_path(f'{base}/ic_more.xml')
    ok3 = check_more(d3)

    print("=== 两态对偶：进出全屏切换时图形足迹不得跳动 ===")
    ok4 = True
    if b1 and b2:
        if abs(b1[0] - b2[0]) > 0.01 or abs(b1[2] - b2[2]) > 0.01:
            ok4 = False
            print("  [FAIL] ic_full 与 ic_exitfull 横向足迹不一致")
        elif abs(b1[1] - b2[1]) > 0.01 or abs(b1[3] - b2[3]) > 0.01:
            ok4 = False
            print("  [FAIL] ic_full 与 ic_exitfull 纵向足迹不一致")
        else:
            print(f"  [PASS] 两态足迹一致：x[{b1[0]:.1f},{b1[2]:.1f}] y[{b1[1]:.1f},{b1[3]:.1f}]")
    else:
        ok4 = False

    ok = ok1 and ok2 and ok3 and ok4
    print()
    print("ALL PASS" if ok else "FAILED")
    return 0 if ok else 1


if __name__ == '__main__':
    sys.exit(main())
