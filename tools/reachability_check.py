#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""
可达性审计：找出"有代码但用户点不到"的功能。

用户反馈的核心问题之一：很多功能写了代码，但界面上根本没有入口，
或者按钮摆在那里点了没反应。这在构建里很难发现（编译能过、不崩），
但用户拿到手就是"这个功能是空的"。

本脚本静态检查三类问题：

  1. 孤儿界面：定义了 Fragment/Activity，但没有任何地方实例化它
     → 用户永远看不到这个页面
  2. 孤儿布局：写了 .xml 布局，但没有任何代码 inflate 它
     → 同样永远不显示
  3. 未绑定控件：布局里定义了 id，但代码里从未 findViewById
     → 按钮摆着但点了没反应

用法：
    python3 tools/reachability_check.py

每批改完都应该跑一次，把新引入的问题挡在构建之前。
"""

import glob
import os
import re
import sys

ROOT = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
SRC = os.path.join(ROOT, "app", "src", "main", "java", "com", "kongbai", "modmigrator")
LAY = os.path.join(ROOT, "app", "src", "main", "res", "layout")


def read(p):
    try:
        return open(p, encoding="utf-8").read()
    except Exception:
        return ""


def main():
    if not os.path.isdir(SRC):
        print("找不到源码目录：", SRC)
        return 2

    kt_files = sorted(glob.glob(os.path.join(SRC, "*.kt")))
    all_src = "\n".join(read(f) for f in kt_files)

    problems = 0

    # ---- 1. 孤儿界面 ----
    print("=== 1. 孤儿界面（定义了但没人实例化，用户永远看不到）===")
    orphan_ui = []
    for f in kt_files:
        base = os.path.basename(f)[:-3]
        s = read(f)
        m = re.search(
            r"class\s+(\w+)\s*:\s*(\w+Fragment|AppCompatActivity|Activity)",
            s,
        )
        if not m:
            continue
        cls = m.group(1)
        # 被引用的次数（排除自身声明）
        refs = len(re.findall(r"\b" + re.escape(cls) + r"\b", all_src))
        declares = len(re.findall(r"class\s+" + re.escape(cls) + r"\b", all_src))
        if refs <= declares:
            orphan_ui.append((cls, os.path.basename(f)))
    if orphan_ui:
        for cls, fn in orphan_ui:
            print(f"  [无入口] {cls}  ({fn})")
            problems += 1
    else:
        print("  无")
    print()

    # ---- 2. 孤儿布局 ----
    print("=== 2. 孤儿布局（写了 xml 但没人 inflate）===")
    orphan_lay = []
    for lay in sorted(glob.glob(os.path.join(LAY, "*.xml"))):
        name = os.path.basename(lay)[:-4]
        if not re.search(r"R\.layout\." + re.escape(name) + r"\b", all_src):
            orphan_lay.append(name)
    if orphan_lay:
        for n in orphan_lay:
            print(f"  [未使用] {n}.xml")
            problems += 1
    else:
        print("  无")
    print()

    # ---- 3. 未绑定控件 ----
    print("=== 3. 未绑定控件（布局有 id，代码没 findViewById，点了可能没反应）===")
    unbound = []
    for lay in sorted(glob.glob(os.path.join(LAY, "*.xml"))):
        name = os.path.basename(lay)[:-4]
        s = read(lay)
        ids = set(re.findall(r"@\+id/(\w+)", s))
        if not ids:
            continue
        # 找可能用到这个布局的代码：文件名相关，或显式引用 R.layout.xxx
        cand = []
        for k in kt_files:
            ks = read(k)
            if ("R.layout." + name) in ks or name.replace("fragment_", "") in os.path.basename(k).lower():
                cand.append(ks)
        src = "\n".join(cand)
        if not src:
            continue
        miss = sorted(i for i in ids if ("R.id." + i) not in src)
        if miss:
            unbound.append((name, miss))
    if unbound:
        for name, miss in unbound:
            print(f"  [{name}] 未绑定: {', '.join(miss)}")
            problems += len(miss)
    else:
        print("  无")
    print()

    print(f"合计可疑点：{problems}")
    print("说明：以上需要人工确认——有些是刻意的（如纯代码构建 UI 的页面），")
    print("      但每一个都应该有明确理由，不能是忘了接。")
    return 0 if problems == 0 else 1


if __name__ == "__main__":
    sys.exit(main())
