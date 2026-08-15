# 06-committing — 提交 / Commit Message 引导

> 何时阅读：生成 commit message、提交被 `commit-msg` hook 拒绝时。

## 规范（强制）

本项目强制 **Conventional Commits**，由 `githooks/commitlint.js` 在提交时自动校验，格式不符会被拒绝。

```
type(scope)!: subject
```

- `type`（必填）：`feat` / `fix` / `docs` / `style` / `refactor` / `perf` / `test` / `build` / `ci` / `chore` / `revert`
- `scope`（可选）：变更范围，如 `auth`、`cache`、`book`
- `!`（可选）：破坏性变更
- `subject`（必填）：小写字母开头，简述本次变更

**示例：**

```
feat(auth): add jwt login
fix(cache): fix ttl expiry bug
docs: update readme
feat!: drop legacy api
```

**规则要点**：Header ≤ 100 字符；subject 非空、不允许大写/帕斯卡/首字母大写开头；正文前需空行（警告）。
`Merge` / `Revert` / `fixup!` / `squash!` / `amend!` 提交自动跳过。

## 提交被拒怎么办

1. hook 会打印具体的规则错误（如 `type-enum`、`subject-case`）。
2. 按提示修改提交信息后重新提交（`git commit` 未完成时可重新编辑，或 `git commit --amend`）。
3. 手动校验任意信息：
   ```sh
   node githooks/commitlint.js .git/COMMIT_EDITMSG
   ```

## 环境注意（提交相关）

- 父目录 `guga_reading/package.json` 是 `"type": "module"`——本仓库 `.js` 会被 Node 按 **ESM** 解析，
  编写/修改脚本必须用 `import`，不能用 `require`（除非改成 `.cjs`）。
- 若团队环境未启用 hook，先执行 `git config core.hooksPath githooks`。

## 检查清单

- [ ] subject 小写字母开头、≤100 字符？
- [ ] type 在枚举内、格式 `type(scope)!: subject`？
- [ ] 需同步的 DDL / 文档（`database.sql`、`README`、`ai-doc/`）已一并纳入提交？
