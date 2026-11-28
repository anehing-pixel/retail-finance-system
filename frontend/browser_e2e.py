# -*- coding: utf-8 -*-
"""浏览器点击级 E2E（W-FE）：Playwright 驱动真实 Edge，逐按钮点击 S1–S12 + 负例 + 客户自助，
断言每步页面状态（✅/数据渲染/请求日志），并截图留证。
运行前提：serve.py(18082) / 网关(18081) / 业务服务栈在跑。
用法：python frontend/browser_e2e.py
"""
import pathlib
import sys

from playwright.sync_api import sync_playwright

BASE = "http://127.0.0.1:18082/"
SHOTS = pathlib.Path(__file__).parent / "screenshots"
SHOTS.mkdir(exist_ok=True)

results = []


def check(name, ok, detail=""):
    results.append((name, bool(ok), detail))
    print(("PASS  " if ok else "FAIL  ") + name + ("  | " + detail if detail else ""))


def click_and_wait(page, btn_text, state_id, timeout_ms, must="✅", scope="#pane-staff"):
    """点击按钮并等待状态 span 出现 must 文本；返回最终文本。

    S8a/S8b 共用 s8-state、S11 三步共用 s11-state：点击前必须清空状态位，
    否则会立刻匹配到上一步的旧 ✅（2026-11-26 实测：S8b 正例未完成就点了负例，
    两个代付请求并发，偶发网关 502）。
    """
    page.evaluate(
        "(sel) => { const el = document.querySelector(sel); if (el) el.textContent = ''; }",
        scope + " #" + state_id)
    page.click(scope + " button.act:has-text(\"" + btn_text + "\")")
    sel = scope + " #" + state_id + ":has-text(\"" + must + "\")"
    try:
        page.wait_for_selector(sel, timeout=timeout_ms)
    except Exception:
        # 失败诊断：状态实际文本 + 截图 + 请求日志尾部
        try:
            actual = page.inner_text(scope + " #" + state_id)
        except Exception:
            actual = "(状态元素无文本)"
        shot = SHOTS / ("FAIL-" + state_id + ".png")
        page.screenshot(path=str(shot), full_page=True)
        logtail = page.inner_text("#log-body")[-800:]
        raise AssertionError(
            "步骤超时: 按钮=%s 状态=%s 实际文本=%r 截图=%s\n日志尾部:\n%s"
            % (btn_text, state_id, actual, shot, logtail))
    return page.inner_text(scope + " #" + state_id)


with sync_playwright() as p:
    browser = p.chromium.launch(channel="msedge", headless=True)
    page = browser.new_page(viewport={"width": 1500, "height": 1050})
    page.on("pageerror", lambda e: results.append(("JS运行时错误: " + str(e), False, "")))
    failed_req = []
    page.on("requestfailed", lambda r: failed_req.append(r.url))

    page.goto(BASE, wait_until="networkidle")
    # 未登录访问控制台应由 session.js 守卫跳到员工登录页
    check("00 未登录访问控制台 → 跳转员工登录页", "login.html" in page.url, page.url)
    check("00b 登录页账号/密码表单渲染",
          page.is_visible("#login-user") and page.is_visible("#login-pass"))
    page.fill("#login-user", "admin")
    page.fill("#login-pass", "admin123")
    page.click("#login-submit")
    page.wait_for_url("**/index.html", timeout=15000)
    check("00c 员工登录成功 → 进入控制台", "运营控制台" in page.title(), page.url)
    check("01 页面加载", "运营控制台" in page.title())
    check("02 无 JS 加载错误", not any(r[2] == "" and r[0].startswith("JS") for r in results))

    # Tab 切换
    page.click("#tab-self")
    check("03 Tab→客户自助", page.is_visible("#pane-self") and not page.is_visible("#pane-staff"))
    page.click("#tab-help")
    check("04 Tab→说明", page.is_visible("#pane-help"))
    page.click("#tab-staff")
    check("05 Tab→运营台", page.is_visible("#pane-staff"))

    # S1–S8b 主链路逐按钮点击
    check("06 S1 登录", click_and_wait(page, "登录", "s1-state", 15000).find("✅") >= 0)
    check("07 S2 注册", click_and_wait(page, "注册客户", "s2-state", 15000).find("✅") >= 0)
    check("08 S3 核验", click_and_wait(page, "一键核验", "s3-state", 20000).find("✅") >= 0)
    check("09 S4 同意", click_and_wait(page, "取得同意", "s4-state", 15000).find("✅") >= 0)
    check("10 S5 申请", click_and_wait(page, "提交申请", "s5-state", 15000).find("✅") >= 0)
    check("11 S6 审查", click_and_wait(page, "一键审查", "s6-state", 60000).find("✅") >= 0)
    check("12 S7 合同", click_and_wait(page, "合同四步", "s7-state", 30000).find("✅") >= 0)
    check("13 S8a 建债权", click_and_wait(page, "建债权", "s8-state", 20000).find("✅") >= 0)
    check("14 S8b 代付", click_and_wait(page, "执行代付", "s8-state", 30000).find("✅") >= 0)

    # ID chips 渲染
    chips_txt = page.inner_text(".chips")
    check("15 ID chips 渲染", all(k in chips_txt for k in
          ["customerId", "applicationId", "caseId", "contractId", "loanId", "titleId"]),
          chips_txt.replace("\n", " · ")[:120])

    # S8 负例：假合同 → 门禁 fail-closed
    check("16 S8 负例 假合同", click_and_wait(page, "负例：假合同", "s8-state", 15000,
          must="RF_LOAN_001").find("✅") >= 0)

    check("17 S9 还款", click_and_wait(page, "一键：滚转", "s9-state", 60000).find("✅") >= 0)
    check("18 S9 计划表 3 期", page.locator("#s9-plan table tr").count() == 4)  # 表头+3期
    check("19 S10 结清", click_and_wait(page, "结清", "s10-state", 30000).find("✅") >= 0)
    check("20 S11a 保留登记", click_and_wait(page, "保留登记", "s11-state", 20000).find("✅") >= 0)
    check("21 S11b 事件建单", click_and_wait(page, "等事件建单", "s11-state", 60000).find("✅") >= 0)
    check("22 S11c 解押闭环", click_and_wait(page, "一键解押闭环", "s11-state", 60000).find("✅") >= 0)
    check("23 S12 贷后", click_and_wait(page, "跑快照", "s12-state", 30000).find("✅") >= 0)

    page.screenshot(path=str(SHOTS / "browser-e2e-staff.png"), full_page=True)

    # 客户自助（S.ids.customer_id 自动带入）
    page.click("#tab-self")
    page.click("#pane-self button.act:has-text(\"登录\")")
    page.wait_for_selector("#pane-self #self-state:has-text(\"✅\")", timeout=15000)
    check("24 客户登录", True)
    page.click("#pane-self button.act:has-text(\"查询本人视图\")")
    page.wait_for_selector("#self-view table", timeout=15000)  # 等表格真正渲染
    n_rows = page.locator("#self-view table tr").count()
    check("25 脱敏视图渲染", n_rows >= 4, str(n_rows) + " 行")
    view_txt = page.inner_text("#self-view")
    name_cell = view_txt.split("姓名")[1].split("电话")[0] if "姓名" in view_txt else "?"
    check("26 脱敏生效", "山田太郎" not in name_cell, "姓名格=" + name_cell.strip())
    check("27 负例 主体隔离", click_and_wait(page, "负例：员工", "self-state", 15000,
          scope="#pane-self", must="✅").find("✅") >= 0)
    page.screenshot(path=str(SHOTS / "browser-e2e-self.png"), full_page=True)

    # 请求日志面板
    log_lines = page.locator("#log-body div").count()
    check("28 请求日志面板", log_lines >= 45, str(log_lines) + " 条请求记录")

    js_errs = [r[0] for r in results if r[0].startswith("JS运行时错误")]
    check("29 无 JS 运行时错误", not js_errs, "; ".join(js_errs[:3]))
    check("30 无网络层失败请求", not failed_req, "; ".join(failed_req[:3]))

    # 顶部员工身份
    staff_name = page.inner_text("#staff-name").strip()
    staff_title = page.get_attribute("#staff-chip", "title") or ""
    check("31 顶部显示当前员工",
          page.is_visible("#staff-chip") and staff_name not in ("", "未登录") and "admin" in staff_title,
          "{} | {}".format(staff_name, staff_title))

    # ---- 客户管理页（/api/customers）----
    page.goto(BASE + "customers.html", wait_until="networkidle")
    check("32 客户管理页加载", "客户管理" in page.title(), page.title())

    page.fill("#reg-name", "山田花子")
    page.fill("#reg-kana", "ヤマダハナコ")
    page.click("#reg-submit")
    page.wait_for_selector('#reg-state:has-text("✅")', timeout=15000)
    reg_txt = page.inner_text("#reg-state")
    check("33 客户建档成功", "customerId=" in reg_txt, reg_txt)
    cid = reg_txt.split("customerId=")[-1].strip()

    page.wait_for_selector("#masked-view table", timeout=10000)
    masked = page.inner_text("#masked-view")
    check("34 脱敏视图渲染且不含明文姓名",
          "山田花子" not in masked and "***" in masked, masked.replace("\n", " · ")[:140])
    check("35 建档后客户号回填查询", page.input_value("#lookup-id") == cid, cid)

    page.fill("#full-purpose", "E2E_PII_VIEW")
    page.click("#full-btn")
    page.wait_for_selector("#full-view table", timeout=10000)
    full = page.inner_text("#full-view")
    check("36 全量视图含明文姓名（PII_VIEW_FULL）", "山田花子" in full,
          full.replace("\n", " · ")[:140])

    page.click("#idv-create")
    page.wait_for_selector('#idv-status:has-text("PENDING")', timeout=10000)
    page.click("#idv-doc")
    page.wait_for_selector('#idv-status:has-text("DOCUMENTS_RECEIVED")', timeout=10000)
    page.click("#idv-start")
    page.wait_for_selector('#idv-status:has-text("VERIFYING")', timeout=10000)
    page.click("#idv-approve")
    page.wait_for_selector('#idv-status:has-text("VERIFIED")', timeout=10000)
    check("37 身份核验 → VERIFIED", "VERIFIED" in page.inner_text("#idv-status"),
          page.inner_text("#idv-info"))

    page.click("#consent-grant")
    page.wait_for_selector('#consent-status:has-text("有效")', timeout=10000)
    consent_txt = page.inner_text("#consent-info")
    check("38 征信同意有效（CREDIT_INQUIRY）", "consentId=" in consent_txt, consent_txt)
    page.click("#consent-withdraw")
    page.wait_for_selector('#consent-status:has-text("无效")', timeout=10000)
    check("39 撤回后同意无效", "无效" in page.inner_text("#consent-status"),
          page.inner_text("#consent-info"))
    page.screenshot(path=str(SHOTS / "browser-e2e-customers.png"), full_page=True)

    # 返回控制台（同一标签页，会话保持）并退出登录
    page.goto(BASE + "index.html", wait_until="networkidle")
    check("40 客户页返回控制台且会话保持", "运营控制台" in page.title(), page.url)
    page.click("#logout-btn")
    page.wait_for_url("**/login.html", timeout=10000)
    check("41 退出登录 → 回到员工登录页", "login.html" in page.url, page.url)
    check("42 退出后会话已清除",
          page.evaluate("window.sessionStorage.getItem('retail.staff.session')") is None)
    browser.close()

fails = [r for r in results if not r[1]]
print("\n===== browser_e2e: %d checks, %d PASS, %d FAIL =====" %
      (len(results), len(results) - len(fails), len(fails)))
sys.exit(1 if fails else 0)
