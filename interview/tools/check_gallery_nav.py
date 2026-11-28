#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""检查 interview 下所有代码流程图集是否包含「左侧目录水平折叠」。

用法（仓库根目录）：
    python interview/tools/check_gallery_nav.py
"""
from __future__ import annotations

from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]
FILES = sorted(ROOT.glob("*代码流程图集.html"))

REQUIRED = {
    "侧栏 id": 'id="rf-sidebar"',
    "收起按钮": 'id="nav-toggle"',
    "窄栏恢复按钮": 'id="nav-rail-toggle"',
    "折叠样式": "nav-collapsed",
    "共享组件标记": "gallery_nav",
}


def main() -> int:
    if not FILES:
        print("未找到 interview/*代码流程图集.html")
        return 1

    failed = 0
    for path in FILES:
        text = path.read_text(encoding="utf-8")
        missing = [label for label, needle in REQUIRED.items() if needle not in text]
        if missing:
            failed += 1
            print(f"[FAIL] {path.name}  缺失：{', '.join(missing)}")
        else:
            print(f"[PASS] {path.name}")

    print(f"汇总：{len(FILES)} 个图集，FAIL {failed}")
    return 1 if failed else 0


if __name__ == "__main__":
    raise SystemExit(main())
