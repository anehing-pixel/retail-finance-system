# API 文档（静态 OpenAPI + Swagger UI）

本目录存放**静态 OpenAPI 3.0.3** 规范与**离线 Swagger UI**。
不引入 springdoc / swagger 运行时依赖，不改各服务的 `pom.xml` 与安全白名单。

## 目录

```text
api-docs/
├─ README.md                        本文件
├─ assets/
│  └─ swagger-ui/                   Swagger UI 5.17.14（Apache-2.0，本地化）
└─ retail-core/
   ├─ openapi.yaml                  OpenAPI 3.0.3 规范（YAML）
   ├─ openapi.json                  同一规范的 JSON 版本
   ├─ swagger-ui.html               内嵌 spec 的离线 Swagger UI
   ├─ gen_openapi.py                生成器（规范 + JSON + HTML）
   └─ README.md                     retail-core 接口说明
```

## 打开 Swagger UI

直接双击：

```text
api-docs/retail-core/swagger-ui.html
```

- spec **内嵌**在 HTML 中，不需要浏览器 fetch 本地 YAML（避免 `file://` 的 CORS 限制）
- CSS / JS 走相对路径 `../assets/swagger-ui/`，仓库内离线可用
- 若把 HTML 单独拷出仓库，需要一并带上 `api-docs/assets/swagger-ui/`

也可以起一个本地静态服务器（可选）：

```bash
python -m http.server 18083 --directory api-docs
# 打开 http://localhost:18083/retail-core/swagger-ui.html
```

## 重新生成

```bash
python api-docs/retail-core/gen_openapi.py
```

生成器会同时写出：

- `openapi.yaml`
- `openapi.json`
- `swagger-ui.html`（内嵌最新 spec）

## 当前覆盖范围

- **retail-core**：19 个 operation（17 条 path），覆盖债权、还款计划、代付、入金核销、
  结清与对拍、产品档位、到期滚转、health/whoami。
- 其他服务（application / underwriting / contract / customer / bureau / servicing /
  title-release / recon）尚未纳入；后续可按 `retail-core/` 同结构扩展。

## 验证口径

```bash
# 1) OpenAPI 3.0.3 schema 校验（jsonschema Draft4Validator + 官方 OAS 3.0 schema）
# 2) 引用完整性：所有 $ref 必须命中 components
# 3) 示例覆盖：每个 operation 至少 1 个 200 示例；每个 POST/PUT 至少 1 个请求示例
# 4) Swagger UI 渲染：Edge headless 加载 swagger-ui.html，operation 数量 = 19、0 error widget
```

> 本文不记录测试数口径；构建/单测数字以 `PROGRESS.md` 为准。

## 许可

Swagger UI 资源来自 `swagger-ui-dist@5.17.14`，Apache License 2.0；
许可证原文见 `api-docs/assets/swagger-ui/LICENSE`。
