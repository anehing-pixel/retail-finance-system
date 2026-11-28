# interview 图集生成器约定

本目录存放 `interview/` 下代码流程图集的生成器。

## 统一要求：所有图集必须有「左侧目录水平折叠」

所有 `interview/*代码流程图集.html` 必须支持：

- 侧栏顶部「‹ 收起」按钮：把目录**向左收齐为 52px 窄栏**
- 窄栏中的「目录」按钮：恢复侧栏
- 收起后右侧流程图主区域自动加宽（`max-width: 1600px`）
- 收起状态写入 `localStorage`，刷新后保持
- 打印时自动隐藏侧栏与按钮

## 共享组件

水平折叠逻辑统一放在：

```text
interview/tools/gallery_nav.py
```

其中提供：

| 名称 | 作用 |
|---|---|
| `NAV_CSS` | 水平折叠/窄栏/收起按钮样式 |
| `NAV_JS` | 收起/恢复 + `localStorage` 记忆 |
| `build_nav(groups)` | 侧栏 HTML：标题、收起按钮、分组链接、窄栏恢复按钮 |

生成器用法：

```python
from gallery_nav import NAV_CSS, NAV_JS, build_nav

nav = build_nav(groups)
# HTML 模板中：
# <style>{CSS}\n{NAV_CSS}</style>
# <aside class="sidebar" id="rf-sidebar">{nav}</aside>
# <script>{JS}\n{NAV_JS}</script>
```

**新生成器不要再复制一份折叠 CSS/JS**；否则两份逻辑会漂移。  
现有七个生成器仍有少量历史内联样式，但折叠行为统一由 `gallery_nav.py` 提供；
后续清理内联样式时，以共享模块为准。

## 当前生成器

| 生成器 | 输出 |
|---|---|
| `gen_retail_core_html.py` | `interview/retail-core-代码流程图集.html` |
| `gen_servicing_html.py` | `interview/servicing-service-代码流程图集.html` |
| `gen_customer_html.py` | `interview/customer-service-代码流程图集.html` |
| `gen_bureau_html.py` | `interview/bureau-service-代码流程图集.html` |
| `gen_underwriting_html.py` | `interview/underwriting-service-代码流程图集.html` |
| `gen_auth_html.py` | `interview/auth-service-代码流程图集.html` |
| `gen_gateway_html.py` | `interview/gateway-service-代码流程图集.html` |

重新生成：

```bash
python interview/tools/gen_retail_core_html.py
python interview/tools/gen_servicing_html.py
python interview/tools/gen_customer_html.py
python interview/tools/gen_bureau_html.py
python interview/tools/gen_underwriting_html.py
python interview/tools/gen_auth_html.py
python interview/tools/gen_gateway_html.py
```

## 校验

```bash
python interview/tools/check_gallery_nav.py
```

检查每个 `interview/*代码流程图集.html` 是否包含：

- `id="rf-sidebar"`
- `id="nav-toggle"`
- `id="nav-rail-toggle"`
- `nav-collapsed`
- `gallery_nav` 标记

新增图集时，先通过该检查，再交付。
