#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""
文档一致性自检（S0 文档轮等价「冒烟」）。

检查项（与 docs/00-文档索引.md §2.4 声明一一对应）：
  C1  R 文档编号连续唯一             C2  ADR 编号连续唯一
  C3  索引登记文件全部存在           C4  跨文档引用编号可达
  C5  C-xx 全部在 R01 登记           C6  端口不与 floorplan 冲突 / 端口段口径
  C7  全文中文（零日文假名）         C8  禁用日文汉字词零命中
  C9  架构口径统一为微服务           C10 R 文档三节齐备
  C11 ADR 四节齐备                  C12 自检脚本存在且术语映射幂等
  C13 索引登记文件数与实际一致
  C14 Flyway 号段口径与实际迁移一致   C15 测试数口径唯一（类级 surefire 汇总）

输出 PASS / FAIL + 逐项明细；EXIT 非 0 表示失败（供 CI / 收口门禁使用）。

用法：
  python scripts/check_docs.py
"""
from __future__ import annotations

import re
import sys
from datetime import date
from pathlib import Path

if hasattr(sys.stdout, "reconfigure"):
    sys.stdout.reconfigure(encoding="utf-8")

ROOT = Path(__file__).resolve().parents[1]
DOCS = ROOT / "docs"
ADR = DOCS / "adr"
SCRIPTS = ROOT / "scripts"
INDEX = DOCS / "00-文档索引.md"

KANA = (
    "ぁあぃいぅうぇえぉおかがきぎくぐけげこごさざしじすずせぜそぞ"
    "ただちぢっつづてでとどなにぬねのはばぱひびぴふぶぷへべぺほぼぽ"
    "まみむめもゃやゅゆょよらりるれろゎわゐゑをんゔゕゖ"
    "ァアィイゥウェエォオカガキギクグケゲコゴサザシジスズセゼソゾ"
    "タダチヂッツヅテデトドナニヌネノハバパヒビピフブプヘベペホボポ"
    "マミムメモャヤュユョヨラリルレロヮワヰヱヲンヴヵヶヷヸヹヺー"
)

# 日文汉字词（无假名，假名扫描抓不到）。均为已被 normalize_terms.py 替换掉的词，
# 任何命中都意味着「日文回灌」或「替换漏网」。
FORBIDDEN_HANZI = [
    "債権", "実行", "補正", "覆核", "手動", "貸後", "作成", "締結", "電話", "住所",
    "生年月", "資金", "勤务先", "開示", "訂正", "電文", "送信", "終態", "与信", "過剰",
    "消費者", "金融庁", "貸金業協会", "結清", "審査", "申込", "契約", "書面", "書類",
    "立替", "払込", "完済", "返済", "消込", "口座", "振替", "振込", "収納", "均等払",
    "元金均等", "初回", "営業", "条項", "勢力", "情報", "登録", "登記", "照会", "異議",
    "所有権", "車検証", "名義", "取引", "割販", "貸金", "総量", "義務", "記載項目",
    "運用", "留保", "回執", "抵当権", "委任状", "印鑑", "譲渡", "納車", "一括", "残高",
    "明細", "依頼", "委託", "購入者", "債務者", "解約", "済", "自動引落", "車種", "店舗",
    "納税", "確定申告", "青色申告", "源泉徴収", "給与明細", "年金証書", "所得証明",
    "運転免許", "個人番号", "個人情報", "個人データ", "保有個人データ", "第三者提供",
    "開示請求", "利用目的", "適用法規", "表示義務", "業者登録", "法定書面", "原状回復",
    "車両返還", "割引額", "事務手数料", "分割払い", "一括返済", "支払能力", "出出生",
    "查義務", "クーリングオフ", "マイナンバー", "コンビニ収納", "ボーナス払",
]

# 旧「模块化单体起步」口径的精确措辞。历史记录里「模块化单体起步、按触发器演进为微服务」
# 是被否决方案/修订说明，不在本表内，不会被误伤。
FORBIDDEN_ARCH = [
    "起步只有一个业务进程",
    "不启用 Feign",
    "起步不启用 Feign",
    "模块间走本地用例编排",
    "不建空服务骨架",
    "不是独立部署进程",
    "§7 是将来拆分时的契约",
    "起步同进程",
    "Feign 暂不启用",
]

SERVICES = [
    "retail-core", "auth-service", "customer-service", "application-service",
    "underwriting-service", "bureau-service", "contract-service",
    "title-release-service", "servicing-service", "recon-service",
]

CORE_DOCS = [
    "README.md", "AGENTS.md", "PROGRESS.md", "docs/00-文档索引.md",
    "docs/R00-设计基线与命名定源.md", "docs/R02-总体架构设计.md",
    "docs/R09-接口与事件契约.md", "docs/R11-演进路线图与开工门禁.md",
    "docs/adr/ADR-RF-001-采用微服务架构与floorplan对齐.md",
]

# 跨项目共享端口事实（改动须同步 PROGRESS.md §2）
RF_PORTS = {"biz": (8090, 8099), "gateway": 18081, "dmz": 18091, "db": 5435}
FP_PORTS = {"biz": (8080, 8088), "gateway": 18080, "dmz": 18090, "db": 5434}

results: list[tuple[str, str, bool, str]] = []


def record(cid: str, name: str, ok: bool, detail: str) -> None:
    results.append((cid, name, ok, detail))


def md_files() -> list[Path]:
    return sorted(
        list(ROOT.glob("*.md"))
        + list(DOCS.glob("*.md"))
        + list(ADR.glob("*.md"))
    )


def read(p: Path) -> str:
    return p.read_text(encoding="utf-8")


# ---------------------------------------------------------------------------
# C1 / C2：编号连续唯一
# ---------------------------------------------------------------------------
def check_numbering() -> None:
    r_files = sorted(DOCS.glob("R*.md"))
    nums = []
    for p in r_files:
        m = re.match(r"R(\d{2})-", p.name)
        if m:
            nums.append(int(m.group(1)))
    expect = list(range(0, 12))
    ok = len(r_files) == 12 and sorted(nums) == expect and len(set(nums)) == 12
    record("C1", "R 文档编号连续唯一", ok,
           f"实际 {len(r_files)} 份，编号 {sorted(nums)}，期望 R00–R11")

    a_files = sorted(ADR.glob("ADR-RF-*.md"))
    anums = []
    for p in a_files:
        m = re.match(r"ADR-RF-(\d{3})-", p.name)
        if m:
            anums.append(int(m.group(1)))
    ok2 = len(a_files) == 13 and sorted(anums) == list(range(1, 14)) and len(set(anums)) == 13
    record("C2", "ADR 编号连续唯一", ok2,
           f"实际 {len(a_files)} 份，编号 {sorted(anums)}，期望 001–013")


# ---------------------------------------------------------------------------
# C3：索引登记文件存在
# ---------------------------------------------------------------------------
def check_index_refs() -> None:
    text = read(INDEX)
    # Markdown 的 ``` 代码块会让朴素的 `...` 正则跨块误匹配（奇偶错位吞掉表格引用），
    # 因此先剥离代码块，再从代码块文本里单独提取文件名（文件树是纯文本，无反引号）。
    blocks = re.findall(r"```[\s\S]*?```", text)
    cleaned = re.sub(r"```[\s\S]*?```", "", text)
    refs = re.findall(r"`([^`\n]+)`", cleaned)
    for block in blocks:
        # 允许文件名里的中文与全角标点（如 ADR 标题中的「：」），避免被切成半截文件名
        refs += re.findall(r"[^\s│├└─`]*\.(?:md|py|sh|gitignore)", block)
    candidates = [ROOT, DOCS, ADR, SCRIPTS]
    missing = []
    checked = 0
    for raw in refs:
        ref = raw.strip()
        if ".." in ref or " " in ref or "\n" in ref:
            continue
        if not (ref.endswith(".md") or ref.endswith(".py") or ref.endswith(".gitignore")
                or ref.endswith(".sh")):
            continue
        if "/" in ref:
            p = ROOT / ref
            if p.exists():
                checked += 1
                continue
        base = ref.split("/")[-1]
        if any((d / base).exists() for d in candidates):
            checked += 1
        elif any(f.name.endswith(base) for d in candidates for f in d.iterdir() if f.is_file()):
            # 兼容「只截取到文件名后半段」的引用（全角标点切分等）
            checked += 1
        else:
            missing.append(ref)
    record("C3", "索引登记文件全部存在", not missing,
           f"校验 {checked} 个引用；缺失：{missing}" if missing else f"校验 {checked} 个引用，全部可达")


# ---------------------------------------------------------------------------
# C4 / C5：引用编号可达、C-xx 登记完整
# ---------------------------------------------------------------------------
def check_refs_and_cxx() -> None:
    r_ok = set(range(0, 12))
    a_ok = set(range(1, 14))
    bad_r, bad_a = set(), set()
    for p in md_files():
        t = read(p)
        for n in re.findall(r"\bR(\d{2})\b", t):
            if int(n) not in r_ok:
                bad_r.add(n)
        for n in re.findall(r"ADR-RF-(\d{3})", t):
            if int(n) not in a_ok:
                bad_a.add(n)
    ok = not bad_r and not bad_a
    record("C4", "跨文档引用编号可达", ok,
           f"越界 R 编号 {sorted(bad_r)}；越界 ADR 编号 {sorted(bad_a)}" if not ok
           else "所有 Rxx / ADR-RF-xxx 引用均落在登记表内")

    r01 = read(DOCS / "R01-业务定源与法务裁决清单.md")
    registered = set(re.findall(r"C-(\d{2})", r01))
    all_c = set()
    per_file: dict[str, set[str]] = {}
    for p in md_files():
        s = set(re.findall(r"C-(\d{2})", read(p)))
        if s:
            per_file[p.name] = s
            all_c |= s
    not_registered = sorted(all_c - registered)
    ok2 = not not_registered
    record("C5", "C-xx 全部在 R01 登记", ok2,
           f"R01 登记 {len(registered)} 项；未登记编号 {not_registered}" if not ok2
           else f"全仓出现 {len(all_c)} 个 C 编号，全部已在 R01 §5 登记")


# ---------------------------------------------------------------------------
# C6：端口不冲突 + 端口段口径
# ---------------------------------------------------------------------------
def check_ports() -> None:
    rf, fp = RF_PORTS, FP_PORTS
    overlap = not (rf["biz"][1] < fp["biz"][0] or fp["biz"][1] < rf["biz"][0])
    port_clash = (rf["gateway"] == fp["gateway"] or rf["dmz"] == fp["dmz"]
                  or rf["db"] == fp["db"])
    ok = not overlap and not port_clash

    # 旧端口段 8090–8098 只允许出现在「原 / 由…扩」的历史说明行
    stale = []
    for p in md_files():
        for i, line in enumerate(read(p).splitlines(), 1):
            if "8090–8098" in line and not any(k in line for k in ("原", "由", "扩")):
                stale.append(f"{p.name}:{i}")

    # 当前口径 8090–8099 必须出现
    current = any("8090–8099" in read(p) for p in md_files())
    ok = ok and not stale and current
    detail = (f"业务段 {rf['biz']} vs {fp['biz']} 不重叠；网关/DMZ/DB 均不冲突；"
              f"当前口径 8090–8099 {'已登记' if current else '缺失'}；"
              f"旧口径越界行 {stale if stale else '无'}")
    record("C6", "端口不与 floorplan 冲突", ok, detail)


# ---------------------------------------------------------------------------
# C7 / C8：全文中文
# ---------------------------------------------------------------------------
def check_language() -> None:
    kana_hits = []
    hanzi_hits = []
    for p in md_files():
        for i, line in enumerate(read(p).splitlines(), 1):
            if any(ch in KANA for ch in line):
                kana_hits.append(f"{p.name}:{i}")
            for w in FORBIDDEN_HANZI:
                if w in line:
                    hanzi_hits.append(f"{p.name}:{i} → {w}")
    record("C7", "全文中文（零日文假名）", not kana_hits,
           f"假名命中 {kana_hits[:5]}" if kana_hits else "26 份 .md 零假名命中")
    record("C8", "禁用日文汉字词零命中", not hanzi_hits,
           f"命中 {hanzi_hits[:5]}" if hanzi_hits
           else f"{len(FORBIDDEN_HANZI)} 个禁用词零命中")


# ---------------------------------------------------------------------------
# C9：架构口径统一为微服务
# ---------------------------------------------------------------------------
def check_arch() -> None:
    arch_hits = []
    for p in md_files():
        for i, line in enumerate(read(p).splitlines(), 1):
            for w in FORBIDDEN_ARCH:
                if w in line:
                    arch_hits.append(f"{p.name}:{i} → {w}")

    weak = []
    keywords = ("微服务", "12 个部署单元", "12 个 JVM", "12 个进程", "Feign 门禁")
    for rel in CORE_DOCS:
        p = ROOT / rel
        if not p.exists() or not any(k in read(p) for k in keywords):
            weak.append(rel)

    r00 = read(DOCS / "R00-设计基线与命名定源.md")
    missing_services = [s for s in SERVICES if s not in r00]

    ok = not arch_hits and not weak and not missing_services
    detail = (f"旧口径命中 {arch_hits[:3] if arch_hits else '无'}；"
              f"缺微服务口径的核心文档 {weak if weak else '无'}；"
              f"R00 §3.1 缺失服务 {missing_services if missing_services else '无'}")
    record("C9", "架构口径统一为微服务", ok, detail)


# ---------------------------------------------------------------------------
# C10 / C11：文档结构齐备
# ---------------------------------------------------------------------------
def check_structure() -> None:
    bad_r = []
    for p in sorted(DOCS.glob("R*.md")):
        t = read(p)
        missing = [s for s in ("文档控制", "引用口径", "自检") if s not in t]
        if missing:
            bad_r.append(f"{p.name} 缺 {missing}")
    record("C10", "R 文档三节齐备", not bad_r,
           f"{bad_r}" if bad_r else "R00–R11 共 12 份均含「文档控制 / 引用口径 / 自检」")

    bad_a = []
    for p in sorted(ADR.glob("ADR-RF-*.md")):
        t = read(p)
        missing = [s for s in ("## 状态", "## 背景", "## 结论", "## 后果") if s not in t]
        if missing:
            bad_a.append(f"{p.name} 缺 {missing}")
    record("C11", "ADR 四节齐备", not bad_a,
           f"{bad_a}" if bad_a else "13 份 ADR 均含「状态 / 背景 / 结论 / 后果」四节")


# ---------------------------------------------------------------------------
# C12：脚本存在 + 术语映射幂等（防替换回灌 / 防非幂等映射回归）
# ---------------------------------------------------------------------------
def check_scripts() -> None:
    needed = [SCRIPTS / "check_docs.py", SCRIPTS / "normalize_terms.py"]
    missing = [str(p.relative_to(ROOT)) for p in needed if not p.is_file()]
    ok = not missing

    idem_detail = "跳过（脚本缺失）"
    if ok:
        sys.path.insert(0, str(SCRIPTS))
        import normalize_terms as nt  # noqa: E402
        drift = []
        for p in nt.md_files():
            t = p.read_text(encoding="utf-8")
            normalized, n = nt.normalize_text(t)
            if n or normalized != t:
                drift.append(f"{p.name} 仍有 {n} 处待规范化")
        if drift:
            ok = False
            idem_detail = f"非幂等 / 未规范化：{drift[:5]}"
        else:
            idem_detail = "normalize_terms.py 二次归一化 0 变更（幂等）"
    record("C12", "自检脚本存在且术语映射幂等", ok,
           f"缺失 {missing}；{idem_detail}" if missing else idem_detail)


# ---------------------------------------------------------------------------
# C13：索引登记文件数与实际一致
# ---------------------------------------------------------------------------
def check_count() -> None:
    root_items = ["README.md", "AGENTS.md", "PROGRESS.md", ".gitignore"]
    root_ok = all((ROOT / f).exists() for f in root_items)
    docs_n = len(list(DOCS.glob("*.md")))          # 00 索引 + R00–R11 = 13
    adr_n = len(list(ADR.glob("*.md")))            # 13
    scripts_n = len([p for p in SCRIPTS.glob("*.py") if p.name in
                     ("check_docs.py", "normalize_terms.py")])
    total = (len(root_items) if root_ok else 0) + docs_n + adr_n + scripts_n
    ok = root_ok and docs_n == 13 and adr_n == 13 and scripts_n == 2 and total == 32
    record("C13", "索引登记文件数与实际一致", ok,
           f"根 4 + docs {docs_n} + ADR {adr_n} + 脚本 {scripts_n} = {total} 项，期望 32")


# ---------------------------------------------------------------------------
# C14：Flyway 号段口径与实际迁移脚本一致
# ---------------------------------------------------------------------------
def check_migration_range() -> None:
    """文档里声称「本项目已落地 V1–Vxx」的表述，其上限必须等于磁盘上真实迁移脚本数。

    背景：V15（W9-A 报送执行闭环）落地后，README / PROGRESS / R02 / R06 / 00-文档索引
    多处仍写「V1–V14」，导致文档口径与实测（15 条）脱节。本项把该口径钉死。

    只校验「本项目已落地」语义的行；floorplan 的 V1–V26 与本项目「起步号段 V1–V9」
    等历史/他项目表述不在此列（故按上下文关键词过滤）。
    """
    mig_dir = ROOT / "infrastructure" / "src" / "main" / "resources" / "db" / "migration"
    scripts = sorted(p for p in mig_dir.glob("V*.sql") if p.is_file())
    actual_n = len(scripts)
    vers = sorted(int(re.match(r"V(\d+)__", p.name).group(1)) for p in scripts)
    contiguous = vers == list(range(1, actual_n + 1))

    # 只校验「声称当前终态已落地迁移数」的表述；
    # 历史阶段记录（如 W2 阶段只落了 V1–V9）、他项目号段、规划号段均不在此列。
    current_ctx = ("已落地", "当前状态", "终态", "实测 1", "共 60 张表", "Flyway V1\u2013V")
    exclude_ctx = ("floorplan", "起步", "预留", "不撞号", "独立库", "S1-W2", "S2 落", "W2", "W9-A 落",
                   "原记录")   # 历史轮次的实测记录不改写（标注「原记录」即豁免）

    stale = []
    pattern = re.compile(r"V1[\u2013-]V(\d+)")
    for p in md_files():
        for i, line in enumerate(read(p).splitlines(), 1):
            if not any(k in line for k in current_ctx):
                continue
            if any(k in line for k in exclude_ctx):
                continue
            for m in pattern.finditer(line):
                if int(m.group(1)) != actual_n:
                    stale.append("%s:%d \u2192 V1\u2013V%s" % (p.name, i, m.group(1)))

    ok = actual_n > 0 and contiguous and not stale
    detail = ("磁盘迁移脚本 %d 条（V1\u2013V%d，号段连续=%s）；文档号段越界行 %s"
              % (actual_n, actual_n, contiguous, stale if stale else "无"))
    record("C14", "Flyway 号段口径与实际迁移一致", ok, detail)


# ---------------------------------------------------------------------------
# C15：测试数口径唯一（防「系统计」虚高数字回流）
# ---------------------------------------------------------------------------
def check_test_count() -> None:
    """全仓出现的「构建/单测 N 个测试」类断言，N 必须唯一且等于权威口径。

    权威口径 = surefire 类级汇总（见 PROGRESS.md 顶部校正注：类级/模块级 surefire 汇总）。
    历史教训：曾长期以 surefire 系统计虚高值（706/748）对外表述，与实测 353 脱节。
    """
    allow_ctx = ("虚高", "校正", "原记录", "口径已校正")
    pattern = re.compile(r"(?:构建|单测|测试)\s*(\d{2,4})\s*(?:个)?(?:测试|单测)")
    hits = {}
    for p in md_files():
        for i, line in enumerate(read(p).splitlines(), 1):
            if any(k in line for k in allow_ctx):
                continue
            for m in pattern.finditer(line):
                hits.setdefault(m.group(1), []).append("%s:%d" % (p.name, i))

    prog = read(ROOT / "PROGRESS.md")
    m = re.search(r"构建\s*(\d{2,4})\s*单测\s*0\s*失败", prog)
    authority = int(m.group(1)) if m else None

    drift = dict((k, v) for k, v in hits.items() if int(k) != authority)
    ok = authority is not None and not drift
    detail = "权威口径 %s；出现过的其他测试数 %s" % (authority, drift if drift else "无")
    record("C15", "测试数口径唯一（类级 surefire 汇总）", ok, detail)


def main() -> int:
    print("=" * 74)
    print(f"文档一致性自检 check_docs.py · {date.today().isoformat()}")
    print("=" * 74)

    for fn in (
        check_numbering, check_index_refs, check_refs_and_cxx, check_ports,
        check_language, check_arch, check_structure, check_scripts, check_count,
        check_migration_range, check_test_count,
    ):
        fn()

    for cid, name, ok, detail in results:
        flag = "PASS" if ok else "FAIL"
        print(f"[{flag}] {cid:<4} {name}")
        print(f"         {detail}")

    passed = sum(1 for *_, ok, _ in results if ok)
    failed = len(results) - passed
    print("-" * 74)
    print(f"汇总：{len(results)} 项检查，PASS {passed} / FAIL {failed}")
    if failed:
        print("结果：FAIL（EXIT 1）")
        return 1
    print("结果：PASS（EXIT 0）")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
