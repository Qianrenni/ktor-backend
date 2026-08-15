# 07-troubleshooting — 排错场景引导

> 何时阅读：编译、测试、运行、提交任一环节报错时。按「报错类型」定位，先读对应文档再改代码。

## 编译 / Gradle 报错

- 读完整错误栈，定位到具体文件与行号；`./gradlew build --stacktrace` 可看更详细栈。
- 编译错误多为：导入缺失、类型不匹配、签名改动未同步调用点、Exposed 表/查询类型错误。
- 改了方法签名 → 用「查找引用」同步所有调用点与测试；`Service` 依赖变动 → 同步 `ServiceRegistrar.kt` + `Services.kt`。
- 参考：`01-coding.md`、`05-build-run.md`。

## 测试失败

- 先看失败用例与断言信息：`build/reports/tests/test/index.html` 或 `build/test-results/test/*.xml`。
- 常见原因：H2 与 MySQL 行为差异、用例间数据未隔离、新代码破坏旧行为、Service 装配缺少依赖。
- 数据库相关测试失败 → 确认未连接真实 MySQL，且建表/清理逻辑正确（`02-database.md`、`04-testing.md`）。

## 提交被 hook 拒绝

- 属于 `commitlint` 规则问题：按 `06-committing.md` 修正提交信息。
- 若提示脚本模块/依赖问题（如 `require is not defined`）：`.js` 被当 ESM 解析，改用 `import`（见 `06-committing.md` 环境注意）。
- 若提示 `node: command not found`：确认 Node.js ≥ 18 且在 PATH，且执行过 `git config core.hooksPath githooks`。

## 启动失败 / 运行时错误

- **MySQL / Redis 连接失败**：检查 `.env` 中连接串、服务是否启动、端口是否正确（`05-build-run.md`）。
- 端口被占用：8080 被占 → 换端口或停掉占用进程。
- 接口报错：确认路由已挂载、Service 已在 `Services` 登记、权限枚举有效（`03-api.md`）。
- 看日志：`logs/` 与控制台输出（`logback.xml` 配置了日志级别）。

## 通用排查步骤

1. 复现 + 收集完整报错信息（含上下文行）。
2. 对照本文档定位所属场景，阅读对应 `ai-doc/` 文档。
3. 参考同模块同类代码的既有做法。
4. 修改后验证：`./gradlew build` → `./gradlew test` → 功能验证。

## 检查清单

- [ ] 已收集完整错误信息（栈/日志/退出码）？
- [ ] 定位到对应场景文档并遵循其约定？
- [ ] 修复后 `build` + `test` 通过？
- [ ] 若改了表/API/文档，相关同步项（`database.sql`、`documentation.yaml`、`ai-doc/`）已更新？
