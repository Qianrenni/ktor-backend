# 04-testing — 测试场景引导

> 何时阅读：编写/运行/调试测试时。测试**不得依赖本地 MySQL/Redis**，一律使用 H2 内存库。

## 结构约定

- 测试位于 `src/test/kotlin/com/qianrenni/`，**镜像** main 目录结构（`common/`、`infrastructure/`、`modules/...`）。
- 命名：`TestXxx.kt`（如 `TestUserService.kt`），与被测类对应。
- 测试辅助类位于 `src/test/kotlin/com/qianrenni/testutil/`：`TestApp.kt`、`TestApplicationHelper.kt`、`TestCompat.kt`、`TestInfraSmoke.kt`。

## 如何编写

- 参考同模块已有测试（如 `TestBookService.kt`）——它们展示了 H2 建表、Service 装配、断言方式。
- 使用 H2 内存库初始化 Exposed 表；测试结束清理数据，保证用例隔离。
- 数据库相关测试：通过 `DatabaseManager`（H2 URL）建库建表后执行 Service 逻辑。
- 涉及 Ktor 路由的测试：用 `testApplication { ... }` + 测试辅助装配路由。

## 如何运行

```sh
./gradlew test                       # 全部测试
./gradlew test --tests "com.qianrenni.modules.user.*"   # 按包过滤
./gradlew test --tests "TestUserService"                # 按类过滤
```

- 单测失败定位：`build/reports/tests/test/index.html`（HTML 报告）或 `build/test-results/test/*.xml`。

## 约定

- 每个新 Service / 关键逻辑都应配套测试。
- 测试命名清晰：`fun 行为_条件_结果()` 或按现有风格。
- 不改测试也**必须**保证新代码不破坏现有测试（`./gradlew test` 全绿）。

## 检查清单

- [ ] 测试类在 `src/test/kotlin/com/qianrenni/` 镜像路径，命名 `TestXxx.kt`？
- [ ] 未连接本地 MySQL/Redis，仅用 H2？
- [ ] 用例间数据隔离（清理或独立库）？
- [ ] `./gradlew test` 全部通过？
