#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""interview 图集共享的「水平折叠目录」组件。

约定：所有 `interview/*代码流程图集.html` 的生成器都必须使用本模块：
- `NAV_CSS`：向左收齐为窄栏的样式
- `NAV_JS`：收起/展开 + localStorage 记忆
- `build_nav(groups)`：侧栏 HTML（标题、收起按钮、分组链接、窄栏恢复按钮）

生成器只需保证：
- 页面有 `.layout` 容器
- 侧栏是 `<aside class="sidebar" id="rf-sidebar">`
- `groups` 形如 `[{"title": "...", "items": [{"anchor": "...", "title": "..."}]}]`
"""
from __future__ import annotations

import html

NAV_CSS = r"""
/* ===== gallery_nav: 左侧目录水平折叠（所有图集共用） ===== */
.layout { transition: grid-template-columns .18s ease, max-width .18s ease; }
.sidebar { transition: padding .18s ease; }
.layout.nav-collapsed {
  grid-template-columns: 52px minmax(0, 1fr);
  max-width: 1600px;
}
.sidebar-head {
  display: flex; align-items: center; justify-content: space-between;
  gap: 8px; margin: 0 0 8px;
}
.sidebar-head h2 {
  font-size: 13px; letter-spacing: .08em; color: var(--muted); margin: 0;
}
.nav-toggle {
  border: 1px solid var(--line); background: #fff; color: var(--brand);
  border-radius: 8px; padding: 3px 7px; font-size: 11px; cursor: pointer;
  white-space: nowrap;
}
.nav-toggle:hover { border-color: var(--brand); background: var(--soft); }
.nav-group { margin: 12px 0 16px; }
.nav-group > span {
  display: block; font-size: 12px; font-weight: 700; color: var(--brand);
  margin: 0 6px 5px; letter-spacing: .04em;
}
.nav-group a {
  display: block; padding: 5px 8px; border-radius: 8px;
  color: #334155; font-size: 13px; line-height: 1.35;
}
.nav-group a:hover {
  background: var(--soft); text-decoration: none; color: var(--brand);
}
.nav-rail-toggle {
  display: none;
  width: 100%; border: 1px solid var(--line); background: #fff;
  color: var(--brand); border-radius: 10px; padding: 10px 0;
  font-size: 12px; font-weight: 700; letter-spacing: .12em;
  writing-mode: vertical-rl; text-orientation: mixed;
  cursor: pointer;
}
.nav-rail-toggle:hover { border-color: var(--brand); background: var(--soft); }
.layout.nav-collapsed .sidebar { padding: 10px 6px; }
.layout.nav-collapsed .sidebar-head,
.layout.nav-collapsed .nav-group { display: none; }
.layout.nav-collapsed .nav-rail-toggle { display: block; }
@media (max-width: 960px) {
  .layout.nav-collapsed { grid-template-columns: 52px minmax(0, 1fr); }
}
@media print {
  .nav-toggle, .nav-rail-toggle, .sidebar { display: none; }
}
"""

NAV_JS = r"""
/* ===== gallery_nav: 左侧目录水平折叠（所有图集共用） ===== */
(function () {
  function init() {
    var layout = document.querySelector('.layout');
    var toggle = document.getElementById('nav-toggle');
    var rail = document.getElementById('nav-rail-toggle');
    if (!layout || !toggle || !rail || layout.dataset.navBound === '1') { return; }
    layout.dataset.navBound = '1';

    var key = 'rf-gallery-nav-collapsed';
    function setCollapsed(collapsed) {
      layout.classList.toggle('nav-collapsed', collapsed);
      toggle.setAttribute('aria-expanded', String(!collapsed));
      rail.setAttribute('aria-expanded', String(!collapsed));
      try {
        localStorage.setItem(key, collapsed ? '1' : '0');
      } catch (e) { /* file:// 或隐私模式下不可用，忽略 */ }
    }

    var stored = null;
    try { stored = localStorage.getItem(key); } catch (e) { /* ignore */ }
    setCollapsed(stored === '1');

    function bind(button) {
      button.addEventListener('click', function () {
        setCollapsed(!layout.classList.contains('nav-collapsed'));
      });
    }
    bind(toggle);
    bind(rail);
  }

  if (document.readyState === 'loading') {
    document.addEventListener('DOMContentLoaded', init);
  } else {
    init();
  }
})();
"""


def build_nav(groups: list[dict]) -> str:
    """生成侧栏 HTML：标题 + 收起按钮 + 分组 + 窄栏恢复按钮。"""
    parts = [
        '<div class="sidebar-head">',
        '<h2>图目录</h2>',
        '<button id="nav-toggle" class="nav-toggle" type="button"',
        '        aria-controls="rf-sidebar" aria-expanded="true"',
        '        title="向左收起图目录">‹ 收起</button>',
        '</div>',
    ]
    for group in groups:
        parts.append('<div class="nav-group">')
        parts.append(f'<span>{html.escape(group["title"])}</span>')
        for item in group["items"]:
            parts.append(f'<a href="#{item["anchor"]}">{html.escape(item["title"])}</a>')
        parts.append('</div>')
    parts.append(
        '<button id="nav-rail-toggle" class="nav-rail-toggle" type="button" '
        'aria-controls="rf-sidebar" aria-expanded="false" title="展开图目录">目录</button>'
    )
    return "\n".join(parts)
