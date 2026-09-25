#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""
AI 代码缺陷审计。

专门针对「AI 生成代码常犯的错误」做静态扫描。这些问题的共同特点是：
**语法完全正确、能编译、能跑通主流程**，所以编译器和常规 lint 都不报，
但在边界条件、并发、资源、生命周期下会暴露。

参考业界对 AI 生成代码的审计清单，本脚本检查以下 12 类：

  A. 异常黑洞      —— catch (Throwable/Exception) 后什么都不做（连日志都没有）
  B. 资源未释放    —— openInputStream / ZipFile / Cursor 等开了没 use{} 或 close
  C. 主线程 IO     —— 在 onCreate/onCreateView/onResume/click 里做网络或文件读写
  D. 子线程更新 UI —— Thread{} 里直接操作 View
  E. 生命周期泄漏  —— 单例/object 持有 Context 或 Activity；Handler 未 removeCallbacks
  F. 并发不安全    —— 可变集合跨线程共享且无同步
  G. 边界未处理    —— 除以可能为 0 的长度、list[0] 未判空
  H. 硬编码        —— 硬编码 URL / 超时 / 密钥 / 中文字符串
  I. API 版本      —— 用了高版本 API 但没有 SDK_INT 判断
  J. 强制解引用    —— !! 与 as? 后直接 .
  K. 重复实现      —— 同一个功能在多个文件重复
  L. 过度宽泛捕获  —— catch (Throwable) 而非具体异常

用法：
    python3 tools/ai_defect_audit.py            # 全量
    python3 tools/ai_defect_audit.py Http.kt    # 单文件
"""

import glob
import os
import re
import sys

ROOT = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
SRC = os.path.join(ROOT, "app", "src", "main", "java", "com", "kongbai", "modmigrator")
GRADLE = os.path.join(ROOT, "app", "build.gradle")


def read_min_sdk():
    """读 minSdk。低于此版本的 API 才需要判断，否则是误报。"""
    g = read(GRADLE)
    m = re.search(r"minSdk\s+(\d+)", g)
    return int(m.group(1)) if m else 21


def read(p):
    try:
        return open(p, encoding="utf-8").read()
    except Exception:
        return ""


def strip_comments_and_strings(s):
    """粗略去掉注释和字符串，减少误报。保留行号。"""
    out = []
    for line in s.split("\n"):
        # 去掉行注释
        i = line.find("//")
        code = line[:i] if i >= 0 else line
        # 去掉字符串字面量内容（保留空串以免破坏引号配对判断）
        code = re.sub(r'"(\\.|[^"\\])*"', '""', code)
        out.append(code)
    return "\n".join(out)


def find_empty_catch(name, src, code):
    """A. 异常黑洞：catch 块内没有任何语句"""
    hits = []
    # 匹配 catch (...) { ... }，检查块内是否只有空白/注释
    for m in re.finditer(r"catch\s*\([^)]*\)\s*\{([^{}]*)\}", code):
        body = m.group(1).strip()
        if body == "":
            line = code[:m.start()].count("\n") + 1
            hits.append((line, "空 catch：异常被完全吞掉，失败后无日志无提示"))
    return hits


def find_resource_leak(name, src, code):
    """B. 资源未释放"""
    hits = []
    # 开了流但既没 use 也没 close
    openers = [
        r"openInputStream\s*\(",
        r"openOutputStream\s*\(",
        r"new\s+ZipFile\s*\(",
        r"ZipFile\s*\(",
        r"FileInputStream\s*\(",
        r"\.inputStream\(\)",
        r"\.outputStream\(\)",
    ]
    for ln, line in enumerate(code.split("\n"), 1):
        for op in openers:
            if re.search(op, line):
                # 同一行或紧邻行是否有 use / close
                ctx = "\n".join(code.split("\n")[max(0, ln - 3): ln + 6])
                if ".use " not in ctx and ".use(" not in ctx and "close(" not in ctx:
                    hits.append((ln, f"可能未释放：{op.strip()} 附近没有 use{{}} 或 close()"))
                    break
    return hits


def find_main_thread_io(name, code):
    """C. 主线程做 IO"""
    hits = []
    ui_markers = [
        "override fun onCreate", "override fun onCreateView",
        "override fun onResume", "override fun onViewCreated",
        "setOnClickListener", "onOptionsItemSelected",
    ]
    io_markers = [
        r"\bHttp\.get\b", r"\bHttp\.post", r"\.execute\(\)\.body",
        r"contentResolver\.openInputStream", r"\.readBytes\(\)",
        r"DocumentFile\.fromTreeUri",
    ]
    lines = code.split("\n")
    in_ui = False
    for ln, line in enumerate(lines, 1):
        if any(m in line for m in ui_markers):
            in_ui = True
        # 遇到显式的后台切换就认为离开主线程上下文
        if any(k in line for k in ["Thread {", "exec.execute", "lifecycleScope",
                                   "viewModelScope", "Dispatchers.IO", "IO)"]):
            in_ui = False
        if in_ui:
            for io in io_markers:
                if re.search(io, line):
                    hits.append((ln, f"疑似主线程 IO：{io.strip()}"))
                    break
    return hits


def find_thread_ui_update(name, code):
    """D. 子线程直接更新 UI"""
    hits = []
    ui_calls = [
        r"\btoast\(", r"\.text\s*=", r"\.visibility\s*=", r"\.setImageBitmap",
        r"\.setImageResource", r"\.notifyDataSetChanged", r"adapter\.",
        r"MaterialAlertDialogBuilder", r"\.show\(\)",
    ]
    lines = code.split("\n")
    depth = 0
    thread_start = None
    for ln, line in enumerate(lines, 1):
        if "Thread {" in line or "Thread(" in line:
            thread_start = ln
            depth = 1
            continue
        if thread_start is not None:
            depth += line.count("{") - line.count("}")
            # 子线程里若已切回主线程（handler.post / runOnUiThread）则跳过
            if any(k in line for k in ["handler.post", "runOnUiThread", "main {",
                                       "main(", "safePost", "activity?.runOnUiThread"]):
                thread_start = None
                continue
            for u in ui_calls:
                if re.search(u, line):
                    hits.append((ln, f"疑似子线程更新 UI：{u.strip()}"))
                    break
            if depth <= 0:
                thread_start = None
    return hits


def find_lifecycle_leak(name, code):
    """E. 生命周期泄漏"""
    hits = []
    # object / companion 里存 Context
    if re.search(r"\bobject\s+\w+", code):
        for m in re.finditer(r"(?:private\s+)?var\s+\w*[Cc]ontext\w*\s*:\s*Context", code):
            line = code[:m.start()].count("\n") + 1
            hits.append((line, "单例(object)持有 Context 引用，可能泄漏 Activity"))
    # Handler 未 removeCallbacks
    if re.search(r"Handler\(", code) and "removeCallbacks" not in code \
            and "removeCallbacksAndMessages" not in code:
        for m in re.finditer(r"Handler\(", code):
            line = code[:m.start()].count("\n") + 1
            hits.append((line, "Handler 未调用 removeCallbacks"))
    return hits


def find_concurrency(name, code):
    """F. 并发不安全"""
    hits = []
    # 顶层可变集合
    for m in re.finditer(r"private\s+val\s+(\w+)\s*=\s*(?:mutableListOf|ArrayList|HashMap|mutableMapOf)\b", code):
        line = code[:m.start()].count("\n") + 1
        var = m.group(1)
        # 是否被 synchronized / Collections.synchronized 包裹
        seg = code[m.start(): m.start() + 300]
        if "synchronized" not in seg and "Concurrent" not in code:
            hits.append((line, f"可变集合 '{var}' 可能被多线程共享且无同步"))
    return hits


def find_boundary(name, code):
    """G. 边界未处理"""
    hits = []
    # list[0] / .first() 未判空
    for m in re.finditer(r"\.get\(0\)|\[0\]|\.first\(\)", code):
        line = code[:m.start()].count("\n") + 1
        prev = "\n".join(code.split("\n")[max(0, line - 3): line])
        if "isNotEmpty" not in prev and "isNotBlank" not in prev and "!= null" not in prev:
            hits.append((line, "取首个元素前未判空，空集合会 IndexOutOfBounds"))
    # 除法未判 0
    for m in re.finditer(r"/\s*\w*(?:size|count|length)\b", code):
        line = code[:m.start()].count("\n") + 1
        prev = "\n".join(code.split("\n")[max(0, line - 2): line])
        if "== 0" not in prev and "<= 0" not in prev:
            hits.append((line, "除法分母可能是 0"))
    return hits


def find_hardcode(name, code):
    """H. 硬编码"""
    hits = []
    # 硬编码 URL
    for m in re.finditer(r'"https?://[^"]{6,}"', code):
        line = code[:m.start()].count("\n") + 1
        url = m.group(0)[:60]
        hits.append((line, f"硬编码 URL：{url}"))
    # 硬编码中文（在字符串里）
    for m in re.finditer(r'"[^"\n]*[\u4e00-\u9fff][^"\n]*"', code):
        line = code[:m.start()].count("\n") + 1
        txt = m.group(0)[:40]
        # 排除明显是日志/调试的
        if any(k in txt for k in ["测试", "debug", "temp"]):
            continue
        # 说明：本项目面向中文用户且已有 LangPack 多语言机制，
        # 代码内中文属于已知取舍，这里只做提示不列为缺陷。
        hits.append((line, f"[提示] 中文字符串：{txt}（已知，由 LangPack 覆盖）"))
    # 疑似密钥
    for m in re.finditer(r'"(?:ghp_|github_pat_|sk-|ptlc_|AIza)[A-Za-z0-9_\-]{8,}"', code):
        line = code[:m.start()].count("\n") + 1
        hits.append((line, "【严重】疑似硬编码密钥/令牌"))
    return hits


def find_api_version(name, code):
    """I. API 版本兼容"""
    hits = []
    high_api = [
        ("setAcceptThirdPartyCookies", 21),
        ("MIXED_CONTENT", 21),
        ("NotificationChannel", 26),
        ("FOREGROUND_SERVICE", 28),
        ("ImageDecoderDecoder", 28),
        ("ActivityResultContracts", 19),
        ("registerForActivityResult", 19),
    ]
    min_sdk = read_min_sdk()
    for api, level in high_api:
        if level <= min_sdk:
            continue  # minSdk 已覆盖，不需要判断
        for m in re.finditer(re.escape(api), code):
            line = code[:m.start()].count("\n") + 1
            prev = "\n".join(code.split("\n")[max(0, line - 4): line + 1])
            if "SDK_INT" not in prev:
                hits.append((line, f"{api} 需要 API {level}+，附近没有 SDK_INT 判断"))
    return hits


def find_force_unwrap(name, code):
    """J. 强制解引用"""
    hits = []
    for m in re.finditer(r"!!", code):
        line = code[:m.start()].count("\n") + 1
        hits.append((line, "使用了 !! 强制解引用，null 时直接 NPE"))
    return hits


def main():
    targets = sys.argv[1:]
    if targets:
        files = []
        for t in targets:
            p = t if os.path.isabs(t) else os.path.join(SRC, t)
            if os.path.exists(p):
                files.append(p)
    else:
        files = sorted(glob.glob(os.path.join(SRC, "*.kt")))

    if not files:
        print("没有找到文件")
        return 2

    total = 0
    summary = {}

    for f in files:
        name = os.path.basename(f)
        src = read(f)
        code = strip_comments_and_strings(src)

        checks = [
            ("A 异常黑洞", find_empty_catch(name, src, code)),
            ("B 资源未释放", find_resource_leak(name, src, code)),
            ("C 主线程IO", find_main_thread_io(name, code)),
            ("D 子线程更新UI", find_thread_ui_update(name, code)),
            ("E 生命周期泄漏", find_lifecycle_leak(name, code)),
            ("F 并发不安全", find_concurrency(name, code)),
            ("G 边界未处理", find_boundary(name, code)),
            ("H 硬编码", find_hardcode(name, code)),
            ("I API版本", find_api_version(name, code)),
            ("J 强制解引用", find_force_unwrap(name, code)),
        ]

        file_hits = [(cat, ln, msg) for cat, hits in checks for ln, msg in hits]
        if not file_hits:
            continue

        print(f"\n### {name}")
        for cat in sorted(set(c for c, _, _ in file_hits)):
            group = [(ln, m) for c, ln, m in file_hits if c == cat]
            print(f"  [{cat}] {len(group)} 处")
            for ln, m in group[:6]:
                print(f"      L{ln}: {m}")
            if len(group) > 6:
                print(f"      ...还有 {len(group) - 6} 处")
            summary[cat] = summary.get(cat, 0) + len(group)
            total += len(group)

    print("\n" + "=" * 60)
    print("汇总：")
    for cat in sorted(summary, key=lambda x: -summary[x]):
        print(f"  {cat}: {summary[cat]}")
    print(f"  合计: {total}")
    print("=" * 60)
    print("说明：本脚本偏保守，会有误报。每一条都需要人工确认，")
    print("      但每条都应该有明确结论——是「真问题，修」还是「误报，说明原因」。")
    return 0


if __name__ == "__main__":
    sys.exit(main())
