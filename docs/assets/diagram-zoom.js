/* 代码类流程图（制表符 / ASCII 图）的点击放大与全屏浮层。
 * 只读增强：不修改 Markdown 源，不影响代码复制。
 */
(function () {
  "use strict";

  var BOX_CHARS = /[┌┐└┘├┤┬┴┼│─━┏┓┗┛┣┫┳┻╋═║╔╗╚╝→←↑↓▼▲]/g;
  var ASCII_BOX_LINE = /^\s*[+|][-_+| ]{4,}/;
  var ARROW_LINE = /^\s*\S+\s*(->>|-->>|-->|==>)\s*\S+/;
  var scale = 1;

  function isDiagram(text) {
    if ((text.match(BOX_CHARS) || []).length >= 2) return true;
    var lines = text.split("\n");
    var ascii = 0;
    var arrows = 0;
    for (var i = 0; i < lines.length; i++) {
      if (ASCII_BOX_LINE.test(lines[i])) ascii++;
      if (ARROW_LINE.test(lines[i])) arrows++;
    }
    return ascii >= 2 || arrows >= 2;
  }

  function ensureDialog() {
    var dlg = document.getElementById("rf-zoom-dialog");
    if (dlg) return dlg;

    dlg = document.createElement("dialog");
    dlg.id = "rf-zoom-dialog";
    dlg.className = "rf-zoom-dialog";
    dlg.innerHTML = [
      '<div class="rf-zoom-shell">',
      '  <div class="rf-zoom-toolbar">',
      '    <span class="rf-zoom-title">图表放大</span>',
      '    <span class="rf-zoom-hint" data-rf-zoom-hint>Esc 关闭 · + / − 缩放</span>',
      '    <span class="rf-zoom-level" data-rf-zoom-level>100%</span>',
      '    <button type="button" data-rf-zoom-out aria-label="缩小">−</button>',
      '    <button type="button" data-rf-zoom-fit aria-label="适应窗口">适应</button>',
      '    <button type="button" data-rf-zoom-in aria-label="放大">+</button>',
      '    <button type="button" data-rf-zoom-close aria-label="关闭">关闭</button>',
      '  </div>',
      '  <div class="rf-zoom-body" tabindex="0"></div>',
      '</div>'
    ].join("");

    dlg.querySelector("[data-rf-zoom-in]").addEventListener("click", function () {
      setScale(dlg, scale + 0.15);
    });
    dlg.querySelector("[data-rf-zoom-out]").addEventListener("click", function () {
      setScale(dlg, scale - 0.15);
    });
    dlg.querySelector("[data-rf-zoom-fit]").addEventListener("click", function () {
      setScale(dlg, 1);
      var body = dlg.querySelector(".rf-zoom-body");
      body.scrollTop = 0;
      body.scrollLeft = 0;
    });
    dlg.querySelector("[data-rf-zoom-close]").addEventListener("click", function () {
      closeDialog(dlg);
    });
    dlg.addEventListener("close", function () {
      document.documentElement.classList.remove("rf-zoom-open");
    });
    dlg.addEventListener("click", function (event) {
      if (event.target === dlg) closeDialog(dlg);
    });

    var drag = null;
    var body = dlg.querySelector(".rf-zoom-body");
    body.addEventListener("pointerdown", function (event) {
      if (!body.classList.contains("rf-zoom-pannable") || event.button !== 0) return;
      drag = {
        x: event.clientX,
        y: event.clientY,
        left: body.scrollLeft,
        top: body.scrollTop
      };
      body.classList.add("rf-zoom-dragging");
      if (body.setPointerCapture) body.setPointerCapture(event.pointerId);
    });
    body.addEventListener("pointermove", function (event) {
      if (!drag) return;
      body.scrollLeft = drag.left - (event.clientX - drag.x);
      body.scrollTop = drag.top - (event.clientY - drag.y);
    });
    var endDrag = function () {
      drag = null;
      body.classList.remove("rf-zoom-dragging");
    };
    body.addEventListener("pointerup", endDrag);
    body.addEventListener("pointercancel", endDrag);

    document.body.appendChild(dlg);
    return dlg;
  }

  function setScale(dlg, value) {
    scale = Math.min(3, Math.max(0.6, value));
    dlg.style.setProperty("--rf-zoom-scale", String(scale));
    var level = dlg.querySelector("[data-rf-zoom-level]");
    if (level) level.textContent = Math.round(scale * 100) + "%";
  }

  function openDialog(node, label, initialScale) {
    var dlg = ensureDialog();
    var body = dlg.querySelector(".rf-zoom-body");
    body.innerHTML = "";
    body.appendChild(node);
    var isSvg = node && String(node.nodeName).toLowerCase() === "svg";
    body.classList.toggle("rf-zoom-pannable", !!isSvg);
    body.scrollTop = 0;
    body.scrollLeft = 0;
    var hint = dlg.querySelector("[data-rf-zoom-hint]");
    if (hint) hint.textContent = isSvg
      ? "Esc 关闭 · + / − 缩放 · 拖动查看"
      : "Esc 关闭 · + / − 缩放 · 滚动查看";
    dlg.querySelector(".rf-zoom-title").textContent = label || "图表放大";
    setScale(dlg, initialScale || 1);
    document.documentElement.classList.add("rf-zoom-open");
    if (!dlg.open) dlg.showModal();
    dlg.querySelector("[data-rf-zoom-close]").focus();
  }

  function closeDialog(dlg) {
    if (dlg && dlg.open) dlg.close();
    document.documentElement.classList.remove("rf-zoom-open");
  }

  function addButton(host, pre) {
    if (host.querySelector(":scope > .rf-zoom-btn")) return;
    var btn = document.createElement("button");
    btn.type = "button";
    btn.className = "rf-zoom-btn";
    btn.title = "放大查看";
    btn.setAttribute("aria-label", "放大查看图表");
    btn.innerHTML =
      '<svg viewBox="0 0 24 24" width="12" height="12" aria-hidden="true">' +
      '<path d="M4 9V4h5M20 15v5h-5M15 4h5v5M9 20H4v-5" ' +
      'fill="none" stroke="currentColor" stroke-width="2" stroke-linecap="round"/>' +
      "</svg> 放大";
    btn.addEventListener("click", function (event) {
      event.preventDefault();
      event.stopPropagation();
      var clone = pre.cloneNode(true);
      clone.querySelectorAll(".md-clipboard, .rf-zoom-btn").forEach(function (node) {
        node.remove();
      });
      openDialog(clone, "代码图 · 放大", 1.35);
    });
    host.appendChild(btn);
  }

  function bindCodeBlocks() {
    document.querySelectorAll(".md-typeset pre > code").forEach(function (code) {
      var pre = code.parentElement;
      var host = pre.closest(".highlight") || pre;
      if (host.classList.contains("rf-zoomable")) return;
      if (!isDiagram(code.textContent || "")) return;
      host.classList.add("rf-zoomable");
      addButton(host, pre);
    });
  }

  window.rfOpenMermaidZoom = function (svgText) {
    var svg = null;
    try {
      var doc = new DOMParser().parseFromString(svgText, "image/svg+xml");
      if (
        doc &&
        doc.documentElement &&
        String(doc.documentElement.nodeName).toLowerCase() === "svg" &&
        !doc.querySelector("parsererror")
      ) {
        svg = doc.documentElement;
      }
    } catch (e) {
      svg = null;
    }
    if (!svg) {
      // Mermaid SVG 可能包含 HTML 实体/命名空间细节，回退到 HTML 解析。
      var holder = document.createElement("div");
      holder.innerHTML = svgText;
      svg = holder.querySelector("svg");
    }
    if (!svg) return;
    openDialog(document.importNode(svg, true), "Mermaid 图 · 放大", 1);
  };

  function init() {
    bindCodeBlocks();
  }

  function start() {
    if (window.document$) {
      window.document$.subscribe(init);
    } else if (document.readyState !== "loading") {
      init();
    } else {
      document.addEventListener("DOMContentLoaded", init);
    }
  }

  document.addEventListener("keydown", function (event) {
    var dlg = document.getElementById("rf-zoom-dialog");
    if (!dlg || !dlg.open) return;
    if (event.key === "Escape") {
      closeDialog(dlg);
    } else if (event.key === "+" || event.key === "=") {
      event.preventDefault();
      setScale(dlg, scale + 0.15);
    } else if (event.key === "-") {
      event.preventDefault();
      setScale(dlg, scale - 0.15);
    }
  });

  start();
})();
