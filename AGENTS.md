# AGENTS.md — ktor-backend

本文件为仓库级开发约定，供开发者与 AI 编码代理（GitHub Copilot 等）共同遵循。

## 项目概览

- **技术栈**：Ktor 3.5（Kotlin/JVM），Gradle 构建（Gradle 9.4.1，Java 21 toolchain），包名 `com.qianrenni`
- **构建/运行**：`./gradlew build` / `./gradlew run`（开发端口 8080，依赖 MySQL、Redis）
- **持久化**：Exposed ORM（生产 MySQL）+ H2 内存库（测试）；缓存 Redis（Lettuce）
- 详情见 `README.md`；**AI 场景引导文档见 `ai-doc/README.md`（决策树）**

## 目录结构

```
src/main/kotlin/com/qianrenni/
├── bootstrap/       # 应用入口、路由装配、服务组合根（Application.configService / ServiceGroups）
├── common/          # 通用：AppConfig、config/（动态配置）、ResponseModel、web/（权限/HTTP/限流）、util/
├── infrastructure/  # 基础设施：cache、config/（Redis 配置源）、database、mail、outbox、storage、task
├── models/          # domain/（领域对象）、tables/（Exposed 表定义）
└── modules/         # 业务模块：admin、author、book、system、user
src/test/kotlin/com/qianrenni/   # 测试（镜像 main 结构，使用 H2 内存库）
```

## 核心开发约定（必须遵守）

- **分层**：Controller（`Routing` 扩展函数：参数绑定/权限/响应）→ Service（业务逻辑）→ 基础设施。
- **依赖注入**：采用**手工组合根 + 领域分组**。Service 构造函数参数即其全部依赖；领域组定义在
  `bootstrap/ServiceGroups.kt`（Infra/User/Book/Admin/Author/System），新增 Service 需在
  `ServiceRegistrar.kt` 对应 `assembleXxx()` 中装配并加入所属领域组。访问约定
  `services.<group>.<service>`（如 `services.infra.cacheService`）。
  **禁止** Service 注入整个 `Application` 或运行时从 `attributes` 按需取依赖（旧 service-locator 写法）。
- **动态配置**：可运行期调整的配置走 Redis 配置中心（`common/config/` + `infrastructure/config/`），
  通过管理 API `GET/PUT /system/config/{domain}` 修改，多实例经 Redis Pub/Sub 失效本地缓存，
  缺省默认值兜底。静态敏感项（DSN/密钥等）仍走 `AppConfig` 环境变量，不进动态区。详见 `ai-doc/08-config.md`。
- **API**：统一返回 `com.qianrenni.common.ResponseModel`；请求/响应 DTO 用 `@Serializable data class`；
  需登录的路由包 `authenticate("auth-jwt")`；权限用 `call.requirePermission(...)` +
  `generatePermissionCode(resource, action, scope)`。
- **数据库**：新表定义在 `models/tables/`（Exposed `Table` 对象）；事务用
  `DatabaseManager.transaction` / `suspendedTransaction`；表结构变更需同步 `database.sql`。
- **测试**：测试类命名 `TestXxx.kt`，与 main 目录镜像；用 H2 内存库，不依赖本地 MySQL/Redis。

## AI 工作流：遇到情况该怎么做（决策树）

遇到下列情况，先阅读 `ai-doc/` 下对应分层文档再动手：

| 遇到的情况 | 阅读文档 |
| ---------- | -------- |
| 新增/修改业务代码、新模块 | `ai-doc/01-coding.md` |
| 数据库表 / Exposed 变更 | `ai-doc/02-database.md` |
| 新增/修改 HTTP API、权限 | `ai-doc/03-api.md` |
| 编写/运行测试 | `ai-doc/04-testing.md` |
| 构建、运行、配置环境 | `ai-doc/05-build-run.md` |
| 提交代码（生成 commit message） | `ai-doc/06-committing.md` |
| 编译/测试/提交报错排查 | `ai-doc/07-troubleshooting.md` |
| 动态配置（新增/修改可调配置项） | `ai-doc/08-config.md` |

## Commit Message 规范（必须遵守）

本项目强制执行 **Conventional Commits** 规范，由 `githooks/commitlint.js` 通过 `commit-msg` hook 在每次提交时自动校验；格式不符的提交会被拒绝。

**生成或修改任何 commit message 时，必须遵循以下格式：**

```
type(scope)!: subject
```

- `type`：必填，必须是下列枚举之一
- `scope`：可选，变更范围（如 `auth`、`cache`）
- `!`：可选，表示破坏性变更
- `subject`：必填，以小写字母开头，简要描述本次变更

| type | 含义 |
| ---- | ---- |
| feat | 新功能 |
| fix | 缺陷修复 |
| docs | 文档 |
| style | 代码风格（不影响逻辑） |
| refactor | 重构 |
| perf | 性能优化 |
| test | 测试 |
| build | 构建系统 / 外部依赖 |
| ci | CI 配置 |
| chore | 杂项 |
| revert | 回滚 |

**有效示例：**

```
feat(auth): add jwt login
fix(cache): fix ttl expiry bug
docs: update readme
feat!: drop legacy api
```

**校验要点**：Header 长度 ≤ 100；subject 非空且不允许大写 / 帕斯卡 / 首字母大写开头；正文前需空行（警告级别）。`Merge` / `Revert` / `fixup!` / `squash!` / `amend!` 提交自动跳过。

**手动校验**：`node githooks/commitlint.js .git/COMMIT_EDITMSG`

## 环境注意

- 启用 commit-msg hook（每个 clone 执行一次）：`git config core.hooksPath githooks`
  - Linux / macOS 另需 `chmod +x githooks/commit-msg`
  - 依赖 Node.js ≥ 18（`node` 需在 PATH）
- 父目录 `guga_reading/package.json` 设置了 `"type": "module"`，因此本仓库下的 `.js` 文件会被 Node 按 **ESM** 解析——编写 Node 脚本时必须用 `import`，不能使用 `require`（除非改用 `.cjs` 扩展名）。
