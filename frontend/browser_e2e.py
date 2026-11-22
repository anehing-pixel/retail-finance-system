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
    """点击按钮并等待状态 span 出现 must 文本；返回最终文本。"""
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
    browser.close()

fails = [r for r in results if not r[1]]
print("\n===== browser_e2e: %d checks, %d PASS, %d FAIL =====" %
      (len(results), len(results) - len(fails), len(fails)))
sys.exit(1 if fails else 0)
