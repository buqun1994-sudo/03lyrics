#!/usr/bin/env node

import { existsSync, readFileSync } from "node:fs";
import { dirname, join, resolve } from "node:path";
import { fileURLToPath } from "node:url";

const root = resolve(dirname(fileURLToPath(import.meta.url)), "..");
const failures = [];

const requiredFiles = [
  "AGENTS.md",
  ".gitignore",
  ".codex/README.md",
  ".codex/config.toml",
  ".codex/local-context.properties.example",
  "docs/README.md",
  "docs/architecture/项目长期总纲.md",
  "docs/product/产品需求基线.md",
  "docs/testing/验证矩阵.md",
  "docs/operations/本地开发环境.md",
  "docs/security/安全与密钥边界.md",
  "docs/plans/codex-task-intake-template.md",
  "docs/progress.md",
  "docs/architecture/rules/README.md",
  "docs/architecture/rules/code.md",
  "docs/architecture/rules/design.md",
  "docs/architecture/rules/product.md",
  "docs/architecture/rules/testing.md",
  "docs/architecture/rules/security.md",
  "docs/architecture/rules/operations.md",
  "docs/architecture/rules/ai-collaboration.md",
  "scripts/check-skills.mjs",
  "scripts/check-local-environment.mjs",
  "scripts/install-and-smoke.mjs",
];

function read(relativePath) {
  const path = join(root, relativePath);
  if (!existsSync(path)) {
    failures.push(`缺少文件：${relativePath}`);
    return "";
  }
  return readFileSync(path, "utf8");
}

for (const file of requiredFiles) read(file);

const agents = read("AGENTS.md");
const docsIndex = read("docs/README.md");
const architecture = read("docs/architecture/项目长期总纲.md");
const product = read("docs/product/产品需求基线.md");
const testing = read("docs/testing/验证矩阵.md");
const operations = read("docs/operations/本地开发环境.md");
const security = read("docs/security/安全与密钥边界.md");
const appBuild = read("app/build.gradle.kts");
const manifest = read("app/src/main/AndroidManifest.xml");
const ignore = read(".gitignore");

for (const [file, content] of [
  ["AGENTS.md", agents],
  ["docs/architecture/项目长期总纲.md", architecture],
  ["docs/product/产品需求基线.md", product],
]) {
  if (!content.includes("03lyrics")) failures.push(`${file} 未写入项目名称 03lyrics`);
  if (/待初始化状态|本仓库复制后默认处于 `INIT_REQUIRED`/.test(content)) {
    failures.push(`${file} 错误保留了新项目模板状态`);
  }
}

const requiredRoutes = [
  "docs/architecture/项目长期总纲.md",
  "docs/product/产品需求基线.md",
  "docs/testing/验证矩阵.md",
  "docs/operations/本地开发环境.md",
  "docs/security/安全与密钥边界.md",
];
for (const route of requiredRoutes) {
  if (!docsIndex.includes(route)) failures.push(`docs/README.md 缺少路由：${route}`);
}

const versionName = appBuild.match(/versionName\s*=\s*"([^"]+)"/)?.[1];
const versionCode = appBuild.match(/versionCode\s*=\s*(\d+)/)?.[1];
const applicationId = appBuild.match(/applicationId\s*=\s*"([^"]+)"/)?.[1];
for (const [label, value] of [
  ["versionName", versionName],
  ["versionCode", versionCode],
  ["applicationId", applicationId],
]) {
  if (!value) failures.push(`app/build.gradle.kts 无法读取 ${label}`);
  else if (!architecture.includes(value)) failures.push(`架构总纲未同步 ${label}=${value}`);
}

for (const permission of [
  "android.permission.SYSTEM_ALERT_WINDOW",
  "android.permission.RECEIVE_BOOT_COMPLETED",
]) {
  if (!manifest.includes(permission)) failures.push(`Manifest 缺少预期权限：${permission}`);
}

for (const marker of [
  ".codex/local-context.properties",
  "local.properties",
  "keystore.properties",
  "*.jks",
  "*.keystore",
]) {
  if (!ignore.includes(marker)) failures.push(`.gitignore 缺少本机/密钥排除项：${marker}`);
}

for (const [file, content] of [
  ["AGENTS.md", agents],
  ["docs/testing/验证矩阵.md", testing],
  ["docs/operations/本地开发环境.md", operations],
  ["docs/security/安全与密钥边界.md", security],
]) {
  if (!content.includes(".codex/local-context.properties")) {
    failures.push(`${file} 未声明本机上下文入口`);
  }
}

const forbiddenPersonalPaths = /(?:\/Users\/[^/]+\/|[A-Za-z]:\\\\Users\\\\[^\\\\]+\\\\)/;
for (const file of requiredFiles) {
  if (file === "scripts/check-project-docs.mjs") continue;
  const content = read(file);
  if (forbiddenPersonalPaths.test(content)) failures.push(`${file} 写入了个人绝对路径`);
}

if (failures.length > 0) {
  console.error("项目文档检查失败：");
  for (const failure of failures) console.error(`- ${failure}`);
  process.exit(1);
}

console.log("项目文档检查通过。");
