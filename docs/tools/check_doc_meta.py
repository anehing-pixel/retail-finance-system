#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""Docs-as-Code front matter 校验器。

校验范围：docs/00-文档索引.md + docs/R00–R11 + docs/adr/ADR-RF-*.md。
只读；不修改任何文档。与 scripts/check_docs.py 互补：
- check_docs.py 管既有文档口径（编号/章节/术语/迁移/测试数）；
- 本脚本管 front matter 元数据（schema/id/type/status/日期/关系/作用域）。

用法：
    python docs/tools/check_doc_meta.py
退出码：0 = PASS；1 = FAIL（逐条打印问题）。
"""
from __future__ import annotations

import datetime as dt
import re
import sys
from pathlib import Path

try:
    import yaml
except ImportError:  # pragma: no cover - CI 会先安装 PyYAML
    print("FAIL: 缺少 PyYAML；请先执行 python -m pip install pyyaml", file=sys.stderr)
    raise SystemExit(2)

ROOT = Path(__file__).resolve().parents[2]
DOCS = ROOT / "docs"
ADR = DOCS / "adr"

REQUIRED = ("schema", "id", "title", "type", "status", "date", "updated", "owner", "lang")
DESIGN_STATUS = {"active", "draft", "deprecated"}
ADR_STATUS = {"proposed", "accepted", "rejected", "superseded", "deprecated"}
REL_FIELDS = ("amends", "amended_by", "supersedes", "superseded_by")

errors: list[str] = []


def fail(path: Path, message: str) -> None:
    errors.append(f"{path.relative_to(ROOT)}: {message}")


def as_list(path: Path, fm: dict, key: str) -> list:
    value = fm.get(key, [])
    if value is None:
        return []
    if not isinstance(value, list):
        fail(path, f"{key} 必须是列表")
        return []
    return value


def load(path: Path):
    raw = path.read_bytes()
    if raw.startswith(b"\xef\xbb\xbf"):
        fail(path, "文件带 UTF-8 BOM")
    try:
        text = raw.decode("utf-8")
    except UnicodeDecodeError as exc:
        fail(path, f"不是 UTF-8：{exc}")
        return None, None, None
    if not text.startswith("---"):
        fail(path, "文件不以 YAML front matter 开头")
        return None, None, None
    parts = text.split("---", 2)
    if len(parts) != 3:
        fail(path, "front matter 未闭合")
        return None, None, None
    try:
        fm = yaml.safe_load(parts[1])
    except yaml.YAMLError as exc:
        fail(path, f"YAML 解析失败：{exc}")
        return None, None, None
    if not isinstance(fm, dict):
        fail(path, "front matter 不是映射")
        return None, None, None
    return text, fm, parts[2]


def h1_and_title(path: Path, body: str):
    lines = body.lstrip("\r\n").splitlines()
    if not lines or not lines[0].startswith("# "):
        fail(path, "正文缺少 H1")
        return None, None
    h1 = lines[0]
    if path == DOCS / "00-文档索引.md":
        return "INDEX", h1[2:].strip()
    m = re.match(r"^# (R\d{2})\s*·\s*(.+)$", h1)
    if m:
        return m.group(1), m.group(2).strip()
    m = re.match(r"^# (ADR-RF-\d{3})\s*[：·]\s*(.+)$", h1)
    if m:
        return m.group(1), m.group(2).strip()
    fail(path, f"无法从 H1 提取编号与标题：{h1}")
    return None, None


def check_date(path: Path, fm: dict, key: str) -> None:
    value = fm.get(key)
    if not isinstance(value, str) or not re.fullmatch(r"\d{4}-\d{2}-\d{2}", value):
        fail(path, f"{key} 必须是 YYYY-MM-DD 字符串")
        return
    try:
        dt.date.fromisoformat(value)
    except ValueError:
        fail(path, f"{key} 不是有效日期：{value}")


def main() -> int:
    docs_md = sorted(DOCS.glob("*.md"))
    adr_md = sorted(ADR.glob("ADR-RF-*.md"))
    if len(docs_md) != 13:
        errors.append(f"docs/*.md 应为 13 份，实际 {len(docs_md)} 份")
    if len(adr_md) != 15:
        errors.append(f"docs/adr/ADR-RF-*.md 应为 15 份，实际 {len(adr_md)} 份")

    loaded: dict[str, tuple[Path, dict, str]] = {}
    ids: set[str] = set()

    for path in docs_md + adr_md:
        text, fm, body = load(path)
        if fm is None:
            continue
        for key in REQUIRED:
            if key not in fm:
                fail(path, f"缺少字段 {key}")
        if type(fm.get("schema")) is not int or fm.get("schema") != 1:
            fail(path, "schema 必须为整数 1")
        if not isinstance(fm.get("title"), str) or not fm["title"].strip():
            fail(path, "title 必须是非空字符串")
        if not isinstance(fm.get("id"), str):
            fail(path, "id 必须是字符串")
        if fm.get("owner") not in {"设计轮", "架构侧"}:
            fail(path, "owner 只能是 设计轮 / 架构侧")
        if fm.get("lang") != "zh-CN":
            fail(path, "lang 必须为 zh-CN")
        check_date(path, fm, "date")
        check_date(path, fm, "updated")

        expected_id, expected_title = h1_and_title(path, body)
        if expected_id and fm.get("id") != expected_id:
            fail(path, f"id 与 H1/文件名不一致：{fm.get('id')} != {expected_id}")
        if expected_title and fm.get("title") != expected_title:
            fail(path, f"title 与 H1 不一致：{fm.get('title')!r} != {expected_title!r}")
        if fm.get("id"):
            if fm["id"] in ids:
                fail(path, f"id 重复：{fm['id']}")
            ids.add(fm["id"])

        if path.parent == ADR:
            if fm.get("type") != "adr":
                fail(path, "type 必须为 adr")
            if fm.get("status") not in ADR_STATUS:
                fail(path, f"status 非法：{fm.get('status')}")
            deciders = fm.get("deciders")
            if not isinstance(deciders, list) or not deciders or not all(isinstance(x, str) and x for x in deciders):
                fail(path, "deciders 必须是非空字符串列表")
            for section in ("## 状态", "## 背景", "## 结论", "## 后果"):
                if section not in text:
                    fail(path, f"缺少 {section}")
            loaded[fm["id"]] = (path, fm, text)
        elif path.name.startswith("R") and re.match(r"R\d{2}-", path.name):
            if fm.get("type") != "design":
                fail(path, "type 必须为 design")
            if fm.get("status") not in DESIGN_STATUS:
                fail(path, f"status 非法：{fm.get('status')}")
            if fm.get("id") != path.name[:3]:
                fail(path, f"id 与文件名不一致：{fm.get('id')} != {path.name[:3]}")
            for section in ("文档控制", "引用口径", "自检"):
                if section not in text:
                    fail(path, f"缺少 {section}")
        elif path == DOCS / "00-文档索引.md":
            if fm.get("type") != "index":
                fail(path, "type 必须为 index")
            if fm.get("status") not in DESIGN_STATUS:
                fail(path, f"status 非法：{fm.get('status')}")
            if fm.get("id") != "INDEX":
                fail(path, "id 必须为 INDEX")
        else:
            fail(path, "不在允许的文档路径内")

    # ADR 关系：只校验已加载的 ADR；缺失的目标一律视为错误。
    partial_edges: set[tuple[str, str]] = set()
    full_edges: set[tuple[str, str]] = set()
    for aid, (path, fm, text) in loaded.items():
        for key in REL_FIELDS:
            for target in as_list(path, fm, key):
                if not isinstance(target, str) or not re.fullmatch(r"ADR-RF-\d{3}", target):
                    fail(path, f"{key} 含非法 ADR 编号：{target!r}")
                    continue
                if target not in loaded:
                    fail(path, f"{key} 指向不存在的 ADR：{target}")
                    continue
                if target == aid:
                    fail(path, f"{key} 不能指向自身")
                    continue
                if key == "amends":
                    partial_edges.add((aid, target))
                elif key == "amended_by":
                    partial_edges.add((target, aid))
                elif key == "supersedes":
                    full_edges.add((aid, target))
                else:
                    full_edges.add((target, aid))

    for a, b in sorted(partial_edges):
        if b not in loaded:
            continue
        ap, afm, _ = loaded[a]
        bp, bfm, _ = loaded[b]
        if b not in as_list(ap, afm, "amends"):
            fail(ap, f"{a} 的 amends 缺少 {b}")
        if a not in as_list(bp, bfm, "amended_by"):
            fail(bp, f"{b} 的 amended_by 缺少 {a}")
        scope_prefix = f"{a} → {b} "
        a_scopes = as_list(ap, afm, "amendment_scope")
        b_scopes = as_list(bp, bfm, "amendment_scope")
        for owner_path, owner_id, scopes in ((ap, a, a_scopes), (bp, b, b_scopes)):
            if not any(isinstance(s, str) and s.startswith(scope_prefix) for s in scopes):
                fail(owner_path, f"缺少 {owner_id} 侧 amendment_scope：{scope_prefix}...")

    for a, b in sorted(full_edges):
        if b not in loaded:
            continue
        ap, afm, _ = loaded[a]
        bp, bfm, _ = loaded[b]
        if b not in as_list(ap, afm, "supersedes"):
            fail(ap, f"{a} 的 supersedes 缺少 {b}")
        if a not in as_list(bp, bfm, "superseded_by"):
            fail(bp, f"{b} 的 superseded_by 缺少 {a}")

    # 复用既有术语归一化，确保 front matter 不会引入 TERM_MAP 待替换内容。
    sys.path.insert(0, str(ROOT / "scripts"))
    try:
        import normalize_terms as nt
    except ImportError as exc:
        errors.append(f"无法导入 scripts/normalize_terms.py：{exc}")
    else:
        for path in docs_md + adr_md:
            text = path.read_text(encoding="utf-8")
            new, count = nt.normalize_text(text)
            if count or new != text:
                fail(path, f"术语归一化会改动 {count} 处")

    if errors:
        print(f"FAIL: {len(errors)} 个问题")
        for item in errors:
            print(" -", item)
        return 1

    print(f"PASS: {len(docs_md) + len(adr_md)} 份文档 front matter 校验通过")
    print(f"  docs/*.md = {len(docs_md)}（索引 + R00–R11）")
    print(f"  docs/adr/ADR-RF-*.md = {len(adr_md)}")
    print(f"  部分修订边 = {len(partial_edges)}；整体取代边 = {len(full_edges)}")
    for a, b in sorted(partial_edges):
        print(f"    amends: {a} -> {b}")
    for a, b in sorted(full_edges):
        print(f"    supersedes: {a} -> {b}")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
