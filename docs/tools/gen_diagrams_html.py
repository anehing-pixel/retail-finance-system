#!/usr/bin/env python3
"""从 README/docs 的 Mermaid 代码块生成可离线打开的 HTML 图集。

用法（仓库根目录）：
    python docs/tools/gen_diagrams_html.py

输出：
    docs/architecture-diagrams.html

依赖：
    docs/assets/mermaid-10.9.1.min.js（Mermaid 10.9.1，MIT；已随仓库本地化）
"""
from __future__ import annotations

import html
import re
from pathlib import Path

ROOT = Path(__file__).resolve().parents[2]
OUT = ROOT / "docs" / "architecture-diagrams.html"

DOC_FILES = [
    ("README.md", "README · 项目总览"),
    ("docs/R01-业务定源与法务裁决清单.md", "R01 · 业务定源与法务裁决"),
    ("docs/R02-总体架构设计.md", "R02 · 总体架构设计"),
    ("docs/R04-领域模型与状态机设计.md", "R04 · 领域模型与状态机"),
    ("docs/R05-业务流程设计.md", "R05 · 业务流程设计"),
    ("docs/R06-数据库设计.md", "R06 · 数据库设计"),
]

CSS = r"""
:root {
  --bg: #f4f7fb;
  --panel: #ffffff;
  --ink: #0f172a;
  --muted: #64748b;
  --line: #dbe3ee;
  --brand: #0f766e;
  --brand-2: #1d4ed8;
  --soft: #eef6f6;
  --chip: #eef2ff;
  --shadow: 0 10px 30px rgba(15, 23, 42, .07);
}
* { box-sizing: border-box; }
html { scroll-behavior: smooth; }
body {
  margin: 0;
  background: var(--bg);
  color: var(--ink);
  font-family: "Noto Sans SC", "PingFang SC", "Microsoft YaHei", -apple-system,
               BlinkMacSystemFont, "Segoe UI", sans-serif;
  line-height: 1.65;
}
a { color: var(--brand-2); text-decoration: none; }
a:hover { text-decoration: underline; }
.hero {
  background: radial-gradient(1100px 420px at 8% -20%, #134e4a 0%, #0f172a 58%, #111827 100%);
  color: #e2e8f0;
  padding: 42px 32px 34px;
}
.hero-inner { max-width: 1280px; margin: 0 auto; }
.hero h1 { margin: 0 0 8px; font-size: 30px; letter-spacing: .02em; }
.hero p { margin: 0; color: #94a3b8; max-width: 900px; }
.hero .stats { display: flex; flex-wrap: wrap; gap: 10px; margin-top: 20px; }
.chip {
  display: inline-flex; align-items: center; gap: 6px;
  padding: 6px 11px; border: 1px solid rgba(148,163,184,.35);
  border-radius: 999px; background: rgba(15,23,42,.35); color: #dbeafe;
  font-size: 13px;
}
.layout {
  display: grid; grid-template-columns: 270px minmax(0, 1fr);
  gap: 24px; max-width: 1280px; margin: 24px auto 52px; padding: 0 24px;
}
.sidebar {
  position: sticky; top: 18px; align-self: start;
  max-height: calc(100vh - 36px); overflow: auto;
  background: var(--panel); border: 1px solid var(--line);
  border-radius: 16px; padding: 16px 14px; box-shadow: var(--shadow);
}
.sidebar h2 { font-size: 13px; letter-spacing: .08em; color: var(--muted); margin: 4px 6px 10px; }
.nav-doc { margin: 12px 0 16px; }
.nav-doc > span {
  display: block; font-size: 12px; font-weight: 700; color: var(--brand);
  margin: 0 6px 5px; text-transform: uppercase; letter-spacing: .04em;
}
.nav-doc a {
  display: block; padding: 5px 8px; border-radius: 8px;
  color: #334155; font-size: 13px; line-height: 1.35;
}
.nav-doc a:hover { background: var(--soft); text-decoration: none; color: var(--brand); }
main { min-width: 0; }
.overview {
  display: grid; grid-template-columns: repeat(auto-fit, minmax(190px, 1fr));
  gap: 12px; margin-bottom: 22px;
}
.overview .card {
  background: var(--panel); border: 1px solid var(--line); border-radius: 14px;
  padding: 14px 16px; box-shadow: var(--shadow);
}
.overview .card b { display: block; font-size: 13px; color: var(--muted); font-weight: 600; }
.overview .card span { display: block; margin-top: 3px; font-size: 15px; font-weight: 700; }
.doc-group { margin: 30px 0 10px; }
.doc-head {
  display: flex; align-items: baseline; gap: 12px; flex-wrap: wrap;
  border-bottom: 2px solid var(--line); padding-bottom: 8px; margin-bottom: 14px;
}
.doc-head h2 { margin: 0; font-size: 22px; }
.doc-head .source { color: var(--muted); font-size: 13px; }
.diagram-card {
  background: var(--panel); border: 1px solid var(--line); border-radius: 16px;
  padding: 18px 18px 12px; margin: 16px 0 22px; box-shadow: var(--shadow);
  scroll-margin-top: 18px;
}
.card-head { display: flex; align-items: center; justify-content: space-between; gap: 12px; }
.card-head h3 { margin: 0; font-size: 17px; }
.badge {
  flex: none; font-size: 11px; font-weight: 700; letter-spacing: .06em;
  color: var(--brand); background: var(--soft); border: 1px solid #cfe6e3;
  border-radius: 999px; padding: 3px 9px;
}
.meta { margin: 4px 0 12px; color: var(--muted); font-size: 12.5px; }
.meta code { color: #334155; }
pre.mermaid {
  margin: 0; padding: 12px; overflow: auto;
  background: #fbfdff; border: 1px dashed #cbd5e1; border-radius: 12px;
  text-align: center;
}
pre.mermaid svg { max-width: 100%; height: auto; }
.diagram-card.natural pre.mermaid svg { max-width: none; height: auto; }
.card-actions { display: flex; align-items: center; gap: 8px; flex: none; }
.zoom-btn {
  border: 1px solid var(--line); background: #fff; color: #334155;
  border-radius: 8px; padding: 4px 9px; font-size: 12px; cursor: pointer;
}
.zoom-btn:hover { border-color: var(--brand); color: var(--brand); background: var(--soft); }
details { margin-top: 10px; border-top: 1px solid var(--line); padding-top: 8px; }
summary { cursor: pointer; color: var(--brand); font-size: 13px; }
pre.source-code {
  margin: 10px 0 0; padding: 12px; overflow: auto;
  background: #0f172a; color: #dbeafe; border-radius: 10px;
  font-size: 12px; line-height: 1.5;
}
#mermaid-warning {
  display: none; max-width: 1280px; margin: 18px auto 0; padding: 12px 16px;
  background: #fff7ed; border: 1px solid #fdba74; color: #9a3412;
  border-radius: 12px;
}
body.mermaid-failed #mermaid-warning { display: block; }
.footer {
  max-width: 1280px; margin: 0 auto 40px; padding: 0 24px;
  color: var(--muted); font-size: 12.5px;
}
@media (max-width: 960px) {
  .layout { grid-template-columns: 1fr; padding: 0 14px; }
  .sidebar { position: static; max-height: none; }
  .hero { padding: 30px 18px 24px; }
}
@media print {
  body { background: #fff; }
  .hero { background: #fff; color: #000; padding: 0 0 12px; }
  .hero p, .hero .stats, .sidebar, .overview { display: none; }
  .layout { display: block; max-width: none; margin: 0; padding: 0; }
  .diagram-card { box-shadow: none; break-inside: avoid; page-break-inside: avoid; }
  pre.mermaid { border-color: #ccc; }
  details { display: none; }
}
"""

JS = r"""
(function () {
  function warn(message) {
    var el = document.getElementById('mermaid-warning');
    if (el) { el.textContent = message; }
    document.body.classList.add('mermaid-failed');
  }
  document.addEventListener('click', function (event) {
    var button = event.target.closest('.zoom-btn');
    if (!button) { return; }
    var card = button.closest('.diagram-card');
    if (!card) { return; }
    var natural = card.classList.toggle('natural');
    button.textContent = natural ? '适合宽度' : '原始尺寸';
  });
  document.addEventListener('DOMContentLoaded', function () {
    if (!window.mermaid) {
      warn('Mermaid 资源未加载。请确认 docs/assets/mermaid-10.9.1.min.js 存在，或联网后刷新。');
      return;
    }
    mermaid.initialize({
      startOnLoad: false,
      securityLevel: 'loose',
      theme: 'base',
      themeVariables: {
        fontFamily: '"Noto Sans SC","PingFang SC","Microsoft YaHei",sans-serif',
        primaryColor: '#e0f2fe',
        primaryBorderColor: '#0284c7',
        primaryTextColor: '#0f172a',
        secondaryColor: '#f1f5f9',
        tertiaryColor: '#f8fafc',
        lineColor: '#475569',
        clusterBkg: '#f8fafc',
        clusterBorder: '#cbd5e1'
      },
      flowchart: { htmlLabels: true, curve: 'basis', useMaxWidth: false },
      sequence: { useMaxWidth: false, wrap: true },
      state: { useMaxWidth: false }
    });
    mermaid.run({ nodes: document.querySelectorAll('pre.mermaid') })
      .then(function () { document.body.classList.add('mermaid-ready'); })
      .catch(function (err) {
        console.error(err);
        warn('部分图渲染失败。可展开每张图的「Mermaid 源码」查看原始定义。');
      });
  });
})();
"""


def extract_diagrams(path: Path) -> list[tuple[str, str]]:
    lines = path.read_text(encoding="utf-8").splitlines()
    heading = ""
    diagrams: list[tuple[str, str]] = []
    i = 0
    while i < len(lines):
        line = lines[i]
        match = re.match(r"^(#{1,6})\s+(.*)$", line)
        if match:
            heading = match.group(2).strip()
        if line.strip().startswith("```mermaid"):
            caption = ""
            for back in range(i - 1, max(-1, i - 5), -1):
                candidate = lines[back].strip()
                if not candidate:
                    continue
                match_caption = re.match(r"^\*\*(.+?)\*\*\s*[:：]?\s*$", candidate)
                if match_caption:
                    caption = match_caption.group(1).strip()
                break
            i += 1
            buffer: list[str] = []
            while i < len(lines) and not lines[i].strip().startswith("```"):
                buffer.append(lines[i])
                i += 1
            code = "\n".join(buffer).strip()
            if code:
                diagrams.append((caption or heading or path.stem, code))
        i += 1
    return diagrams


def slug(doc_index: int, diagram_index: int) -> str:
    return f"diagram-{doc_index}-{diagram_index}"


def main() -> None:
    groups = []
    total = 0
    for doc_index, (rel, title) in enumerate(DOC_FILES):
        path = ROOT / rel
        if not path.exists():
            print(f"[skip] 缺少 {rel}")
            continue
        diagrams = extract_diagrams(path)
        if not diagrams:
            continue
        counts: dict[str, int] = {}
        items = []
        for diagram_index, (heading, code) in enumerate(diagrams):
            counts[heading] = counts.get(heading, 0) + 1
            n = counts[heading]
            label = heading if n == 1 else f"{heading}（续 {n}）"
            items.append({
                "anchor": slug(doc_index, diagram_index),
                "title": label,
                "heading": heading,
                "code": code,
            })
            total += 1
        groups.append({"rel": rel, "title": title, "items": items})

    nav_parts = ['<h2>图目录</h2>']
    for group in groups:
        nav_parts.append('<div class="nav-doc">')
        nav_parts.append(f'<span>{html.escape(group["title"])}</span>')
        for item in group["items"]:
            nav_parts.append(
                f'<a href="#{item["anchor"]}">{html.escape(item["title"])}</a>'
            )
        nav_parts.append('</div>')

    body_parts = []
    for group in groups:
        body_parts.append('<section class="doc-group">')
        body_parts.append(
            '<div class="doc-head">'
            f'<h2>{html.escape(group["title"])}</h2>'
            f'<span class="source">来源：{html.escape(group["rel"])}</span>'
            '</div>'
        )
        for item in group["items"]:
            escaped = html.escape(item["code"], quote=False)
            body_parts.append(
                f'<article class="diagram-card" id="{item["anchor"]}">'
                '<div class="card-head">'
                f'<h3>{html.escape(item["title"])}</h3>'
                '<div class="card-actions">'
                '<span class="badge">Mermaid</span>'
                '<button class="zoom-btn" type="button" title="宽图可切换为原始尺寸并横向滚动">原始尺寸</button>'
                '</div>'
                '</div>'
                f'<p class="meta">来源：<code>{html.escape(group["rel"])}</code>'
                f' · 章节：{html.escape(item["heading"])}</p>'
                f'<pre class="mermaid">{escaped}</pre>'
                '<details><summary>查看 Mermaid 源码</summary>'
                f'<pre class="source-code"><code>{escaped}</code></pre>'
                '</details>'
                '</article>'
            )
        body_parts.append('</section>')

    nav = "\n".join(nav_parts)
    body = "\n".join(body_parts)

    page = f"""<!doctype html>
<!-- 自动生成：python docs/tools/gen_diagrams_html.py；请修改图源文档，不要手改本文件 -->
<html lang="zh-CN">
<head>
<meta charset="utf-8">
<meta name="viewport" content="width=device-width, initial-scale=1">
<title>RF 架构与流程图集 · 新车零售金融系统</title>
<style>{CSS}</style>
<script src="assets/mermaid-10.9.1.min.js"></script>
<script>
if (!window.mermaid) {{
  document.write('<script src="https://cdn.jsdelivr.net/npm/mermaid@10.9.1/dist/mermaid.min.js"><\\/script>');
}}
</script>
</head>
<body>
<header class="hero">
  <div class="hero-inner">
    <h1>RF 架构与流程图集</h1>
    <p>新车零售金融核心系统 · 从 README 与 docs/R00–R11 的 Mermaid 源自动生成。
       覆盖系统上下文、容器架构、限流故障、outbox 消费、出站韧性、数据库角色、迁移发布与解除八步。</p>
    <div class="stats">
      <span class="chip">18 个 Maven 模块</span>
      <span class="chip">12 个长驻部署单元 + db-migration</span>
      <span class="chip">661 测试 / 105 测试类</span>
      <span class="chip">Flyway V1–V22 / 62 表</span>
      <span class="chip">{total} 张 Mermaid 图</span>
    </div>
  </div>
</header>
<div id="mermaid-warning"></div>
<div class="layout">
  <aside class="sidebar">{nav}</aside>
  <main>
    <section class="overview">
      <div class="card"><b>运行期 DB 账号</b><span>app_rw / pii_rw / dmz_rw</span></div>
      <div class="card"><b>迁移顺序</b><span>先 db-migration，再滚动发布</span></div>
      <div class="card"><b>限流故障</b><span>生产 fail-closed 503</span></div>
      <div class="card"><b>事件真相源</b><span>outbox；MQ 仅削峰</span></div>
      <div class="card"><b>宽图查看</b><span>点「原始尺寸」横向滚动</span></div>
    </section>
    {body}
  </main>
</div>
<footer class="footer">
  <p>生成命令：<code>python docs/tools/gen_diagrams_html.py</code> ·
     图源：README.md 与 docs/R01/R02/R04/R05/R06 中的 Mermaid 代码块。</p>
  <p>Mermaid 10.9.1（MIT）已本地化到 <code>docs/assets/mermaid-10.9.1.min.js</code>；
     若本地资源缺失会尝试 CDN。离线打开时图仍可渲染，源码可在每张图下展开查看。</p>
</footer>
<script>{JS}</script>
</body>
</html>
"""

    OUT.write_text(page, encoding="utf-8", newline="\n")
    print(f"已生成 {OUT.relative_to(ROOT)}：{total} 张图，{len(page):,} 字符")


if __name__ == "__main__":
    main()
