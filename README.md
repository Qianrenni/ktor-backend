# ktor-backend

This project was created using the [Ktor Project Generator](https://start.ktor.io).

Here are some useful links to get you started:

- [Ktor Documentation](https://ktor.io/docs/home.html)
- [Ktor GitHub page](https://github.com/ktorio/ktor)
- [Ktor Slack chat](https://app.slack.com/client/T09229ZC6/C0A974TJ9). [Request an invite](https://surveys.jetbrains.com/s3/kotlin-slack-sign-up).

## Features

Here's a list of features included in this project:

| Name                                                                                  | Description                                                         |
| ------------------------------------------------------------------------------------- | ------------------------------------------------------------------- |
| [Rate Limiting](https://start.ktor.io/p/io.github.flaxoos/server-rate-limiting)       | Manage request rate limiting as you see fit                         |
| [Status Pages](https://start.ktor.io/p/io.ktor/server-status-pages)                   | Provides exception handling for routes                              |
| [Static Content](https://start.ktor.io/p/io.ktor/server-static-content)               | Serves static files from defined locations                          |
| [Resources](https://start.ktor.io/p/io.ktor/server-resources)                         | Provides type-safe routing                                          |
| [AutoHeadResponse](https://start.ktor.io/p/io.ktor/server-auto-head-response)         | Provides automatic responses for HEAD requests                      |
| [CSRF](https://start.ktor.io/p/io.ktor/server-csrf)                                   | Cross-site request forgery mitigation                               |
| [Authentication](https://start.ktor.io/p/io.ktor/server-auth)                         | Provides extension point for handling the Authorization header      |
| [Authentication JWT](https://start.ktor.io/p/io.ktor/server-auth-jwt)                 | Handles JSON Web Token (JWT) bearer authentication scheme           |
| [Swagger](https://start.ktor.io/p/io.ktor/server-swagger)                             | Serves Swagger UI for your project                                  |
| [Simple Cache](https://start.ktor.io/p/com.ucasoft/server-simple-cache)               | Provides API for cache management                                   |
| [Simple Memory Cache](https://start.ktor.io/p/com.ucasoft/server-simple-memory-cache) | Provides memory cache for Simple Cache plugin                       |
| [Partial Content](https://start.ktor.io/p/io.ktor/server-partial-content)             | Handles requests with the Range header                              |
| [Forwarded Headers](https://start.ktor.io/p/io.ktor/server-forwarded-header-support)  | Allows handling proxied headers (X-Forwarded-\*)                    |
| [Default Headers](https://start.ktor.io/p/io.ktor/server-default-headers)             | Adds a default set of headers to HTTP responses                     |
| [Conditional Headers](https://start.ktor.io/p/io.ktor/server-conditional-headers)     | Skips response body, depending on ETag and LastModified headers     |
| [Compression](https://start.ktor.io/p/io.ktor/server-compression)                     | Compresses responses using encoding algorithms like GZIP            |
| [Caching Headers](https://start.ktor.io/p/io.ktor/server-caching-headers)             | Provides options for responding with standard cache-control headers |
| [CORS](https://start.ktor.io/p/io.ktor/server-cors)                                   | Enables Cross-Origin Resource Sharing (CORS)                        |

## Building & Running

To build or run the project, use one of the following tasks:

| Task | Description |
| ---- | ----------- |

If the server starts successfully, you'll see the following output:

```
2024-12-04 14:32:45.584 [main] INFO  Application - Application started in 0.303 seconds.
2024-12-04 14:32:45.682 [main] INFO  Application - Responding at http://0.0.0.0:8080
```

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
