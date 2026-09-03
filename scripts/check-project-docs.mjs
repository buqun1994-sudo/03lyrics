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
  "release-version.properties",
  "scripts/check-skills.mjs",
  "scripts/check-local-environment.mjs",
  "scripts/install-and-smoke.mjs",
  "scripts/run-commercial-security-instrumentation.mjs",
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
const testingRules = read("docs/architecture/rules/testing.md");
const closeoutSkill = read(".agents/skills/task-closeout/SKILL.md");
const localContextExample = read(".codex/local-context.properties.example");
const installAndSmoke = read("scripts/install-and-smoke.mjs");
const commercialInstrumentation = read("scripts/run-commercial-security-instrumentation.mjs");
const appBuild = read("app/build.gradle.kts");
const manifest = read("app/src/main/AndroidManifest.xml");
const ignore = read(".gitignore");
const releaseVersion = read("release-version.properties");

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

const versionName = releaseVersion.match(/^releaseVersionName\s*=\s*(\S+)$/m)?.[1];
const versionCode = releaseVersion.match(/^releaseVersionCode\s*=\s*(\d+)$/m)?.[1];
const applicationId = appBuild.match(/applicationId\s*=\s*"([^"]+)"/)?.[1];
for (const [label, value] of [
  ["versionName", versionName],
  ["versionCode", versionCode],
  ["applicationId", applicationId],
]) {
  if (!value) failures.push(`app/build.gradle.kts 无法读取 ${label}`);
  else if (!architecture.includes(value)) failures.push(`架构总纲未同步 ${label}=${value}`);
}
if (!/applicationIdSuffix\s*=\s*["']\.test["']/.test(appBuild)) {
  failures.push("Debug 构建未追加 applicationIdSuffix = \".test\"");
}
if (!/versionNameSuffix\s*=\s*["']-test["']/.test(appBuild)) {
  failures.push("Debug 构建未追加 versionNameSuffix = \"-test\"");
}
if (!/versionCode\s*=\s*releaseVersionCode/.test(appBuild) ||
    !/versionName\s*=\s*releaseVersionName/.test(appBuild)) {
  failures.push("Debug / Release 未共用 release-version.properties 版本入口");
}
if (!/const packageName\s*=\s*apkIdentity\[1\]/.test(installAndSmoke) ||
    !/packageName !== debugPackageName/.test(installAndSmoke)) {
  failures.push("安装 smoke 未从 APK 读取并限制 Debug 测试身份");
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

for (const [file, content, marker] of [
  ["AGENTS.md", agents, "商业 instrumentation 不属于普通收尾"],
  ["docs/testing/验证矩阵.md", testing, "用户日常车机默认禁止"],
  ["docs/operations/本地开发环境.md", operations, "COMMERCIAL_TEST_ADB_SERIAL"],
  ["docs/security/安全与密钥边界.md", security, "--user-approved-persistent-vehicle"],
  ["docs/architecture/rules/testing.md", testingRules, "高风险改动不自动等于高破坏性测试"],
  [".agents/skills/task-closeout/SKILL.md", closeoutSkill, "不默认安装 androidTest APK"],
  [".codex/local-context.properties.example", localContextExample, "COMMERCIAL_TEST_DEVICE_ROLE="],
  ["scripts/install-and-smoke.mjs", installAndSmoke, 'argument === "--serial"'],
  [
    "scripts/run-commercial-security-instrumentation.mjs",
    commercialInstrumentation,
    "COMMERCIAL_TEST_ADB_SERIAL",
  ],
  [
    "scripts/run-commercial-security-instrumentation.mjs",
    commercialInstrumentation,
    "--user-approved-persistent-vehicle",
  ],
]) {
  if (!content.includes(marker)) failures.push(`${file} 缺少商业测试设备隔离门禁：${marker}`);
}

if (testing.includes("实机统一运行 `node scripts/run-commercial-security-instrumentation.mjs`")) {
  failures.push("docs/testing/验证矩阵.md 仍把商业 instrumentation 设为默认实机步骤");
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
