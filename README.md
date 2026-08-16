# ktor-backend

基于 **Ktor 3.5（Kotlin/JVM）** 的阅读平台后端服务，包名 `com.qianrenni`。
提供用户/认证、书籍/章节、书架/阅读进度、评论、作者创作、审核、权限与系统管理等能力。

## 技术栈

- **Web**：Ktor 3.5（Netty）、Content Negotiation、Status Pages、CORS、IP 限流（flaxoos rate-limiting）、JWT 认证
- **持久化**：Exposed ORM（生产 MySQL）+ HikariCP；测试用 H2 内存库
- **缓存/基础设施**：Redis（Lettuce）——缓存、分布式锁、动态配置中心（Pub/Sub 失效）
- **认证/安全**：Auth0 JWT、Kaptcha 验证码、Bcrypt 密码哈希、Jakarta Mail 邮箱验证
- **其他**：Micrometer/Prometheus 指标、OSHI 系统信息、内容存储（LSM 追加式 + msgpack + LZ4）、cron 定时任务 + Outbox 双写

## 环境要求

- JDK 21（Gradle toolchain）
- MySQL 8.x（生产）
- Redis（生产，及部分集成测试）
- Node.js ≥ 18（仅用于 commit-msg hook 校验）

## 快速开始

### 构建

```sh
./gradlew build
```

### 运行

```sh
./gradlew run   # 默认端口 8080
```

启动前需通过环境变量配置 `MYSQL_DSN`、`REDIS_URL`、`SECRET_KEY` 等（见下方「配置」），依赖 MySQL 与 Redis。

### 测试

```sh
./gradlew test
```

测试使用 H2 内存库（`jdbc:h2:mem:guga_test;MODE=MySQL`），Redis 指向隔离的 `db 15`（不可达时忽略），不依赖本地 MySQL。

## 配置

应用通过**环境变量**配置（忽略大小写，完整清单见 `src/main/kotlin/com/qianrenni/common/AppConfig.kt`），关键项：

| 环境变量 | 默认值 | 说明 |
| --- | --- | --- |
| `ENV` | `prod` | 运行环境（`dev` 输出 SQL 日志；默认 fail-closed 为 prod） |
| `MYSQL_DSN` | — | 生产 JDBC 连接串（必填） |
| `REDIS_URL` | — | Redis 连接串（必填） |
| `SECRET_KEY` | — | JWT 密钥（必填，≥ 32 字符，启动时校验） |
| `ALLOW_HOST` | `localhost` | 允许的主机 |
| `ALLOW_ORIGINS` | `*` | CORS 白名单 |
| `SERVER_URL` | `http://localhost:8080` | 对外访问地址 |
| `CONTENT_DIR` / `STATIC_DIR` | `store` / `static` | 内容存储 / 静态文件目录 |
| `SMTP_SERVER` / `SMTP_PORT` / `EMAIL_ACCOUNT` / `EMAIL_CODE` | — | 邮箱服务（验证码/通知） |
| `PERMISSION_BIT_LENGTH` | `32` | 权限位段长度（84 个权限需 2 段） |
| `IP_LIMIT_ENABLE` / `IP_LIMIT_WINDOW` / `IP_LIMIT_COUNT` | 开 / `60` / `30` | IP 限流 |
| `CONTENT_STORE_COMPACT_*` | 开 | 内容存储自动 compact（阈值/Cron 等） |

## 目录结构

```
src/main/kotlin/com/qianrenni/
├── bootstrap/       # 应用入口、路由装配、服务组合根（configService / ServiceGroups / Services）
├── common/          # AppConfig、config/（动态配置）、ResponseModel、web/（权限/HTTP/限流）、util/
├── infrastructure/  # cache、config/（Redis 配置源）、database、mail、outbox、storage、task
├── models/          # domain/（领域对象）、tables/（Exposed 表定义）
└── modules/         # admin、author、book、system、user
src/test/kotlin/com/qianrenni/   # 测试（镜像 main 结构，H2 内存库）
```

## 主要模块

| 模块 | 能力 |
| --- | --- |
| `user` | 注册/登录（JWT + 验证码）、邮箱验证、密码修改/找回 |
| `book` | 书籍/章节浏览、书架、阅读进度、评论、阅读统计 |
| `author` | 作者申请、创建/更新书籍与章节、审核提交（含审核员邮件通知） |
| `admin` | 权限/角色管理（位图权限 + 角色继承）、书籍/用户管理、审核 |
| `system` | 系统状态、日志、动态配置管理（`/system/config`） |

## 开发约定

- 分层（Controller → Service → 基础设施）、手工组合根 + 领域分组等约定见 **`AGENTS.md`**；
- AI 场景决策树与分层文档见 **`ai-doc/`**（`ai-doc/README.md` 为入口）；
- 新增/修改数据库表需同步 `database.sql` 与 Exposed 表定义（生产默认值单一来源）；
- 提交信息遵循 Conventional Commits（`githooks/commitlint.js` 强制校验，见下文）。

## Commit Message 规范

项目使用 [Conventional Commits](https://www.conventionalcommits.org/zh-hans/) 规范，
由零依赖的 Node 脚本 `githooks/commitlint.js` 通过 `commit-msg` hook 在每次提交时自动校验。

### 格式

```
type(scope)!: subject
```

- `type`：必填，必须是下方枚举之一
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

示例：

```
feat(auth): add jwt login
fix(cache): fix ttl expiry bug
docs: update readme
feat!: drop legacy api
```

### 启用 Hook

每个 clone 执行一次：

```sh
git config core.hooksPath githooks
```

> - Linux / macOS 还需执行 `chmod +x githooks/commit-msg`
> - 依赖 Node.js ≥ 18（`node` 需在 PATH 中）

### 手动校验

```sh
node githooks/commitlint.js .git/COMMIT_EDITMSG
```

校验规则（对齐 `@commitlint/config-conventional` 默认值）：
Header 长度 ≤ 100、type 必须在枚举内、subject 非空且不允许大写开头、正文前需空行（警告级别）。
`Merge` / `Revert` / `fixup!` / `squash!` / `amend!` 提交自动跳过。
