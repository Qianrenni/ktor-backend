# ai-doc — AI 引导文档（分层）

本目录为 AI 编码代理（GitHub Copilot 等）与开发者提供**场景化引导**：遇到什么情况，该如何做。
顶层全局约定见 `../AGENTS.md`；本文件为分层索引与通用工作流。

## 分层结构

| 文档 | 场景 | 何时阅读 |
| ---- | ---- | -------- |
| [01-coding.md](01-coding.md) | 编码 | 新增/修改业务代码、新模块、重构 |
| [02-database.md](02-database.md) | 数据库 | 新增/修改表、Exposed 查询、迁移 |
| [03-api.md](03-api.md) | API | 新增/修改路由、认证、权限、DTO |
| [04-testing.md](04-testing.md) | 测试 | 编写/运行/调试测试 |
| [05-build-run.md](05-build-run.md) | 构建运行 | 编译、启动服务、环境配置 |
| [06-committing.md](06-committing.md) | 提交 | 生成 commit message、提交被拒 |
| [07-troubleshooting.md](07-troubleshooting.md) | 排错 | 编译/测试/运行/hook 报错 |

## 通用工作流（大多数任务）

1. **读**：先读 `../AGENTS.md` 的「核心开发约定」，再读本目录对应场景文档。
2. **查**：参考同模块已有实现（如 `modules/user/` 的 Controller/Service、`src/test/` 的同名测试）。
3. **做**：按文档中的约束实现。
4. **验**：`./gradlew build`（编译）→ `./gradlew test`（测试通过）。
5. **提**：提交信息遵循 Conventional Commits（`type(scope)!: subject`），否则会被 `commit-msg` hook 拒绝。

## 决策速查

- 不知道改哪个文件 → 先看 `../AGENTS.md` 目录结构，定位到 `modules/<domain>/`。
- 新增功能模块 → `01-coding.md`。
- 要加表或改字段 → `02-database.md` + `database.sql` 同步。
- 要暴露/修改接口 → `03-api.md`。
- 要补测试 → `04-testing.md`。
- 本地起不来 / 构建失败 → `05-build-run.md`、`07-troubleshooting.md`。
- 要提交 / 提交被拒 → `06-committing.md`。
