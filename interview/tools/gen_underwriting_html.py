#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""从 interview/underwriting-service-代码流程.md 的 Mermaid 代码块生成离线 HTML 图集。

用法（仓库根目录）：
    python interview/tools/gen_underwriting_html.py

输出：
    interview/underwriting-service-代码流程图集.html

依赖：
    docs/assets/mermaid-10.9.1.min.js（Mermaid 10.9.1，MIT；已随仓库本地化）
"""
from __future__ import annotations

import html
import re
from pathlib import Path

from gallery_nav import NAV_CSS, NAV_JS, build_nav

ROOT = Path(__file__).resolve().parents[2]
SRC = ROOT / "interview" / "underwriting-service-代码流程.md"
OUT = ROOT / "interview" / "underwriting-service-代码流程图集.html"

CSS = r"""
:root {
  --bg: #f6f7fb;
  --panel: #ffffff;
  --ink: #0f172a;
  --muted: #64748b;
  --line: #dde3ef;
  --brand: #7c3aed;
  --brand-2: #1d4ed8;
  --soft: #f3e8ff;
  --shadow: 0 10px 30px rgba(30, 41, 59, .07);
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
  background: radial-gradient(1100px 420px at 8% -20%, #4c1d95 0%, #111827 58%, #0f172a 100%);
  color: #e2e8f0;
  padding: 42px 32px 34px;
}
.hero-inner { max-width: 1280px; margin: 0 auto; }
.hero h1 { margin: 0 0 8px; font-size: 30px; letter-spacing: .02em; }
.hero p { margin: 0; color: #ddd6fe; max-width: 980px; }
.hero .stats { display: flex; flex-wrap: wrap; gap: 10px; margin-top: 20px; }
.chip {
  display: inline-flex; align-items: center; gap: 6px;
  padding: 6px 11px; border: 1px solid rgba(148,163,184,.35);
  border-radius: 999px; background: rgba(15,23,42,.35); color: #ede9fe;
  font-size: 13px;
}
.layout {
  display: grid; grid-template-columns: 280px minmax(0, 1fr);
  gap: 24px; max-width: 1280px; margin: 24px auto 52px; padding: 0 24px;
}
.sidebar {
  position: sticky; top: 18px; align-self: start;
  max-height: calc(100vh - 36px); overflow: auto;
  background: var(--panel); border: 1px solid var(--line);
  border-radius: 16px; padding: 16px 14px; box-shadow: var(--shadow);
}
.sidebar h2 { font-size: 13px; letter-spacing: .08em; color: var(--muted); margin: 4px 6px 10px; }
.nav-group { margin: 12px 0 16px; }
.nav-group > span {
  display: block; font-size: 12px; font-weight: 700; color: var(--brand);
  margin: 0 6px 5px; letter-spacing: .04em;
}
.nav-group a {
  display: block; padding: 5px 8px; border-radius: 8px;
  color: #334155; font-size: 13px; line-height: 1.35;
}
.nav-group a:hover { background: var(--soft); text-decoration: none; color: var(--brand); }
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
  color: var(--brand); background: var(--soft); border: 1px solid #ddd6fe;
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
  background: #111827; color: #ede9fe; border-radius: 10px;
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
      warn('Mermaid 资源未加载。请确认 ../docs/assets/mermaid-10.9.1.min.js 存在，或联网后刷新。');
      return;
    }
    mermaid.initialize({
      startOnLoad: false,
      securityLevel: 'loose',
      theme: 'base',
      themeVariables: {
        fontFamily: '"Noto Sans SC","PingFang SC","Microsoft YaHei",sans-serif',
        primaryColor: '#ede9fe',
        primaryBorderColor: '#7c3aed',
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


def extract_diagrams(path: Path) -> list[dict]:
    """按 H2 分组提取 Mermaid 图；每张图记录 H2 / H3 / 源码。"""
    lines = path.read_text(encoding="utf-8").splitlines()
    h2 = ""
    h3 = ""
    diagrams: list[dict] = []
    i = 0
    while i < len(lines):
        line = lines[i]
        m2 = re.match(r"^##\s+(.*)$", line)
        m3 = re.match(r"^###\s+(.*)$", line)
        if m2:
            h2 = m2.group(1).strip()
            h3 = ""
        elif m3:
            h3 = m3.group(1).strip()
        if line.strip().startswith("```mermaid"):
            caption = ""
            for back in range(i - 1, max(-1, i - 5), -1):
                candidate = lines[back].strip()
                if not candidate:
                    continue
                m_caption = re.match(r"^\*\*(.+?)\*\*\s*[:：]?\s*$", candidate)
                if m_caption:
                    caption = m_caption.group(1).strip()
                break
            i += 1
            buffer: list[str] = []
            while i < len(lines) and not lines[i].strip().startswith("```"):
                buffer.append(lines[i])
                i += 1
            code = "\n".join(buffer).strip()
            if code:
                diagrams.append({
                    "h2": h2 or path.stem,
                    "h3": caption or h3 or h2 or path.stem,
                    "code": code,
                })
        i += 1
    return diagrams


def slug(index: int) -> str:
    return f"diagram-{index}"


def main() -> None:
    if not SRC.exists():
        raise SystemExit(f"缺少源文件：{SRC}")
    diagrams = extract_diagrams(SRC)
    if not diagrams:
        raise SystemExit("源文件中没有 Mermaid 代码块")

    groups: list[dict] = []
    group_index: dict[str, int] = {}
    for idx, d in enumerate(diagrams, 1):
        key = d["h2"]
        if key not in group_index:
            group_index[key] = len(groups)
            groups.append({"title": key, "items": []})
        groups[group_index[key]]["items"].append({
            "anchor": slug(idx),
            "title": d["h3"],
            "heading": d["h2"],
            "code": d["code"],
        })

    nav = build_nav(groups)

    body_parts = []
    for group in groups:
        body_parts.append('<section class="doc-group">')
        body_parts.append(
            '<div class="doc-head">'
            f'<h2>{html.escape(group["title"])}</h2>'
            '<span class="source">来源：interview/underwriting-service-代码流程.md</span>'
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
                f'<p class="meta">章节：<code>{html.escape(item["heading"])}</code>'
                f' · 图：<code>{html.escape(item["title"])}</code></p>'
                f'<pre class="mermaid">{escaped}</pre>'
                '<details><summary>查看 Mermaid 源码</summary>'
                f'<pre class="source-code"><code>{escaped}</code></pre>'
                '</details>'
                '</article>'
            )
        body_parts.append('</section>')

    body = "\n".join(body_parts)

    page = f"""<!doctype html>
<!-- 自动生成：python interview/tools/gen_underwriting_html.py；请修改 interview/underwriting-service-代码流程.md，不要手改本文件 -->
<html lang="zh-CN">
<head>
<meta charset="utf-8">
<meta name="viewport" content="width=device-width, initial-scale=1">
<title>underwriting-service 代码流程图集 · 审查授信</title>
<style>{CSS}
{NAV_CSS}</style>
<script src="../docs/assets/mermaid-10.9.1.min.js"></script>
<script>
if (!window.mermaid) {{
  document.write('<script src="https://cdn.jsdelivr.net/npm/mermaid@10.9.1/dist/mermaid.min.js"><\\/script>');
}}
</script>
</head>
<body>
<header class="hero">
  <div class="hero-inner">
    <h1>underwriting-service 代码流程图集</h1>
    <p>审查案件、支付能力评估、决定 / 复核 / SOD、代付前置校验与 DMS 结果通知的代码走读图集，
       源文件：<code>interview/underwriting-service-代码流程.md</code>。</p>
    <div class="stats">
      <span class="chip">端口 8094</span>
      <span class="chip">core / integration schema</span>
      <span class="chip">C-03 挡板策略点</span>
      <span class="chip">SOD 硬阻断</span>
      <span class="chip">{len(diagrams)} 张 Mermaid 图</span>
      <span class="chip">Mermaid 10.9.1 · 离线可渲染</span>
    </div>
  </div>
</header>
<div id="mermaid-warning"></div>
<div class="layout">
  <aside class="sidebar" id="rf-sidebar">{nav}</aside>
  <main>
    <section class="overview">
      <div class="card"><b>核心边界</b><span>只做审查，不执行征信查询、不放款</span></div>
      <div class="card"><b>状态机</b><span>PENDING → 查询 → 评估 → 决定</span></div>
      <div class="card"><b>SOD</b><span>复核人 ≠ 决定人，硬阻断</span></div>
      <div class="card"><b>DMS</b><span>结果通知失败落 outbound_tasks</span></div>
      <div class="card"><b>宽图查看</b><span>点「原始尺寸」横向滚动</span></div>
    </section>
    {body}
  </main>
</div>
<footer class="footer">
  <p>生成命令：<code>python interview/tools/gen_underwriting_html.py</code> ·
     图源：<code>interview/underwriting-service-代码流程.md</code> 中的 Mermaid 代码块。</p>
  <p>Mermaid 10.9.1（MIT）已本地化到 <code>docs/assets/mermaid-10.9.1.min.js</code>；
     若本地资源缺失会尝试 CDN。离线打开时图仍可渲染，源码可在每张图下展开查看。</p>
</footer>
<script>{JS}
{NAV_JS}</script>
</body>
</html>
"""

    OUT.write_text(page, encoding="utf-8", newline="\n")
    print(f"已生成 {OUT.relative_to(ROOT)}：{len(diagrams)} 张图，{len(page):,} 字符")


if __name__ == "__main__":
    main()
