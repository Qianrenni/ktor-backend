#!/usr/bin/env node
/**
 * githooks/commitlint.js
 *
 * 轻量级 Conventional Commits 提交信息校验器（零第三方依赖，仅使用 Node 内置模块）。
 * 由 githooks/commit-msg 在每次提交时调用：
 *
 *     node commitlint.js <commit-message-file>
 *
 * 退出码：0 = 通过 / 自动跳过；1 = 校验失败（阻止提交）
 *
 * 校验规则（对齐 @commitlint/config-conventional 的企业级默认值）：
 *   - header-max-length : Header 不超过 100 字符
 *   - header-format     : 必须符合 `type(scope)!: subject` 格式
 *   - type-enum         : type 必须在 [feat, fix, docs, style, refactor, perf,
 *                          test, build, ci, chore, revert] 中
 *   - subject-empty     : subject 不能为空
 *   - subject-case      : subject 不允许大写 / 帕斯卡 / 首字母大写开头
 *   - body-leading-blank: 正文应以空行与 Header 分隔（警告级别）
 *   - 自动跳过 Merge / Revert / fixup! / squash! / amend! 提交
 */
import fs from 'node:fs';

// ============ 可调配置 ============
const CONFIG = {
  // 允许的 type 枚举
  types: [
    'feat', // 新功能
    'fix', // 缺陷修复
    'docs', // 文档
    'style', // 代码风格（不影响逻辑）
    'refactor', // 重构（不新增功能也不修 Bug）
    'perf', // 性能优化
    'test', // 测试
    'build', // 构建系统 / 外部依赖
    'ci', // CI 配置与脚本
    'chore', // 杂项维护
    'revert', // 回滚
  ],
  headerMaxLength: 100,
  // 命中这些前缀的提交自动跳过
  ignorePatterns: [/^Merge\b/, /^Revert\b/, /^fixup!/, /^squash!/, /^amend!/],
};

// ============ 状态 ============
const errors = [];
const warnings = [];
const fail = (rule, msg) => errors.push(`  ✖ ${rule}: ${msg}`);
const warn = (rule, msg) => warnings.push(`  ⚠ ${rule}: ${msg}`);

// 空行或 # 开头的模板注释行视为"无效行"
function isCommentOrBlank(line) {
  const t = line.trim();
  return t === '' || t.startsWith('#');
}

// ============ 主校验 ============
function lint(message) {
  const lines = message.replace(/\r\n/g, '\n').split('\n');

  // 取出第一条有效行作为 Header（忽略空行与模板注释）
  const headerIdx = lines.findIndex((l) => !isCommentOrBlank(l));
  if (headerIdx === -1) {
    if (message.trim() === '') return { skip: '空提交信息（allow-empty-message）' };
    fail('subject-empty', '未找到有效的提交信息（仅有注释或空白）');
    return {};
  }

  const header = lines[headerIdx].trim();
  if (CONFIG.ignorePatterns.some((re) => re.test(header))) {
    return { skip: `命中忽略规则：${header.split('\n')[0]}` };
  }

  // 1. Header 长度
  if (header.length > CONFIG.headerMaxLength) {
    fail('header-max-length', `Header 长度为 ${header.length}，超过上限 ${CONFIG.headerMaxLength}`);
  }

  // 2. 解析 Conventional Commits：type(scope)!: subject
  const m = header.match(/^([a-zA-Z0-9]+)(?:\(([^()]*)\))?(!)?:\s*(.*)$/);
  if (!m) {
    fail('header-format', `格式应为 type(scope)!: subject，收到："${header}"`);
    fail('header-format', '  示例：feat(auth): 新增 JWT 登录支持');
    return {};
  }
  const [, type, scope, , subject] = m;

  // 3. type 必须是小写字母/数字且在枚举内
  if (!/^[a-z0-9]+$/.test(type)) {
    fail('header-format', `type "${type}" 应只包含小写字母与数字`);
  } else if (!CONFIG.types.includes(type)) {
    fail('type-enum', `type "${type}" 不在允许列表 [${CONFIG.types.join(', ')}] 中`);
  }

  // 4. 空括号 scope 提示
  if (scope === '') {
    warn('scope-empty', '括号内 scope 为空，建议去掉空括号');
  }

  // 5. subject 非空 + 大小写
  if (subject.trim() === '') {
    fail('subject-empty', 'subject 不能为空（冒号后需有描述）');
  } else if (/^[A-Z]/.test(subject)) {
    fail('subject-case', `subject 不应以大写字母开头（当前："${subject}"）`);
  }

  // 6. body 前空行（警告级别）
  const bodyLines = lines.slice(headerIdx + 1).filter((l) => !isCommentOrBlank(l));
  if (bodyLines.length > 0 && !/^\s*$/.test(lines[headerIdx + 1] || '')) {
    warn('body-leading-blank', '正文应以空行与 Header 分隔');
  }

  return { skip: false, header };
}

// ============ CLI 入口 ============
const file = process.argv[2];
if (!file) {
  console.error('用法：node commitlint.js <commit-message-file>');
  process.exit(1);
}

let message;
try {
  message = fs.readFileSync(file, 'utf8');
} catch (e) {
  console.error(`无法读取提交信息文件 ${file}：${e.message}`);
  process.exit(1);
}

const result = lint(message);

if (result && result.skip) {
  console.log(`\x1b[90mcommitlint: 跳过 — ${result.skip}\x1b[0m`);
  process.exit(0);
}

const report = (color, label, items) => {
  if (items.length) {
    console.log(`\n${label}`);
    for (const item of items) console.log(`\x1b[${color}m${item}\x1b[0m`);
  }
};

if (errors.length || warnings.length) {
  console.log('\n\x1b[1mcommitlint — Conventional Commits 校验\x1b[0m');
  report('31', '错误：', errors);
  report('33', '警告：', warnings);
  console.log('\n格式：type(scope)!: subject');
  console.log('示例：feat(auth): 新增 JWT 登录支持');
  if (errors.length) {
    console.log('\n\x1b[31m✖ 提交被拒绝，请修正后重新提交。\x1b[0m');
    process.exit(1);
  }
} else if (result && result.header) {
  console.log(`\x1b[32m✔ commitlint 通过：${result.header}\x1b[0m`);
}
process.exit(0);
