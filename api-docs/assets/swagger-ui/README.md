# Swagger UI 静态资源

- 来源：`swagger-ui-dist@5.17.14`
- 下载地址：`https://unpkg.com/swagger-ui-dist@5.17.14/`
- 许可：Apache License 2.0，原文见同目录 `LICENSE`
- 文件：
  - `swagger-ui.css`
  - `swagger-ui-bundle.js`
  - `swagger-ui-standalone-preset.js`
  - `LICENSE`

`api-docs/retail-core/swagger-ui.html` 通过相对路径 `../assets/swagger-ui/` 引用这些文件；
spec 内嵌在 HTML 中，离线打开不需要额外网络请求。

升级版本时：替换上述三份文件 + `LICENSE`，并同步本 README 与
`api-docs/README.md` 中的版本号；然后用 Edge headless 复跑 Swagger UI 渲染校验。
