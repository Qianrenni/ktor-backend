# 01-coding — 编码场景引导

> 何时阅读：新增/修改业务代码、创建新模块、重构时。动手前先读此文档 + 参考同模块已有实现。

## 目录定位（先搞清楚改哪里）

- `modules/<domain>/` — 业务代码（Controller / Service），按领域拆分：`admin`、`author`、`book`、`system`、`user`。
- `models/domain/` — 领域对象（data class、枚举）。
- `models/tables/` — Exposed 表定义。
- `common/` — 跨模块通用：`AppConfig`、`config/`（动态配置）、`ResponseModel`、`web/`（权限/HTTP/限流）、`util/`。
- `infrastructure/` — 基础设施：cache、config/（Redis 配置源）、database、mail、outbox、storage、task。
- `bootstrap/` — 入口与装配（Routing、ServiceRegistrar、ServiceGroups 领域组）。

## 分层职责（必须遵守）

```
Controller (Routing 扩展)  →  Service (业务逻辑)  →  基础设施 / 表
  参数绑定 / 权限 / 响应         事务 / 规则 / 编排
```

- **Controller**：`Routing` 扩展函数（如 `fun Routing.user(...)`），只做参数绑定、权限校验、调用 Service、返回 `ResponseModel`。**不要写业务逻辑**。
- **Service**：业务逻辑与事务。构造函数参数即其全部依赖（依赖注入）。
- 业务模块之间的协作通过 Service 构造参数传递，不跨模块直接操作表。

## 新增业务模块的步骤

1. 在 `modules/<domain>/` 新建 `XxxService.kt`（业务逻辑）与 `XxxController.kt`（`Routing` 扩展函数）。
2. **登记依赖**（手工组合根 + 领域分组，见 `bootstrap/ServiceGroups.kt`）：
   - 判断该 Service 归属领域（Infra/User/Book/Admin/Author/System）；
   - 在 `bootstrap/ServiceRegistrar.kt` 对应 `assembleXxx()` 中构造并注入依赖；
   - 把 Service 加入 `bootstrap/ServiceGroups.kt` 中对应领域组类。
   - 调用侧按 `services.<group>.<service>` 访问。
3. Controller 通过函数参数拿到所需 Service（参考 `UserController.kt`），并在路由装配处调用该扩展。
4. 如需持久化：见 `02-database.md`。
5. 如需对外接口：见 `03-api.md`。
6. 补测试：见 `04-testing.md`。

## 修改现有代码

- 先阅读目标文件与调用方（用「查找引用」确认影响面）。
- 保持现有分层与命名风格一致（参考同模块相邻文件）。
- 不改变方法签名时，优先最小改动；改动签名需同步所有调用点与测试。
- 重构时保证 `./gradlew build` 通过。

## Kotlin 约定

- 包名 `com.qianrenni.<层>.<模块>`；类名 PascalCase、函数/变量 camelCase、常量 UPPER_SNAKE。
- 优先不可变（`val`）、null 安全（`?`/`?:`）、`data class` 表达 DTO/领域对象。
- 用 `@Serializable` 标注需要序列化的类（配合 `kotlinx.serialization`）。
- 错误处理：业务异常抛自定义异常，由 `common/web/StatusPages.kt` 统一转 `ResponseModel`。

## 检查清单

- [ ] Service 是否加入 `ServiceGroups.kt` 对应领域组并在 `ServiceRegistrar.kt` 的 `assembleXxx` 中装配？
- [ ] 没有注入整个 `Application`、没有从 `attributes` 运行时取依赖？
- [ ] 需要运行期可调的参数是否走动态配置（`common/config/`，见 `08-config.md`）？
- [ ] `./gradlew build` 编译通过？
- [ ] 相关测试 `./gradlew test` 通过？
