---
name: task-closeout
description: 当 03lyrics Android 施工需要收尾、自动覆盖安装车机、执行最简 smoke、交给用户主测、更新进度、检查 Git 状态或在用户确认后准备提交时使用；不得自动清数据、重启、提交、推送或发布。
---

# Task Closeout

## 1. 输入

1. 当前任务目标和完成标准。
2. 本轮实际改动及用户已有改动。
3. 已执行的本机验证、车机 smoke 和客观阻断。
4. 用户是否已反馈主测结果，以及是否明确授权提交、推送或发布。

## 2. 步骤

1. 读取根 `AGENTS.md`、`docs/testing/验证矩阵.md` 和 `docs/progress.md`。
2. 执行 `git status --short --branch`，区分本轮改动与已有改动。
3. 汇总实际改动，必要时更新产品、架构、验证、安全或进度文档。
4. 复用本轮有效本机验证；Android 工程行为改变后运行 `node scripts/install-and-smoke.mjs` 自动保留数据覆盖安装并完成基础 smoke。
5. 商业 instrumentation 不属于普通收尾，不得仅因改动商业权益、安全存储或设备身份而自动运行；优先复用相关 JVM 测试、构建和普通安装 smoke。只有改动直接触及 instrumentation 隔离、AndroidKeyStore 真机行为且较低层级无法覆盖时，才在一次性模拟器或独立测试设备执行。
6. 根据本次改动追加一个可观察的最简功能 smoke；失败时回到修复、构建、安装和 smoke，不把失败版本交给用户主测。
7. 文档和 Skill 底座至少执行：
   - `node scripts/check-project-docs.mjs`
   - `node scripts/check-skills.mjs`
   - `git diff --check`
8. 自动验证通过后，把剩余完整场景压缩为具体动作交给用户主测。
9. 用户反馈有问题时继续迭代；用户反馈无问题并明确要求提交后，才暂存和提交授权范围。推送仍需明确指令。

## 3. 强制边界

1. 默认只安装当前 debug APK 且使用保留数据覆盖安装；不卸载、清除数据、降级、安装 release 或重启设备。
2. 不默认创建提交、推送、GitHub Release、上传 APK 或公开发布。
3. 不回退或暂存用户未授权改动。
4. 不以“请整体手测”代替可执行验证。
5. 设备离线、安装失败或基础 smoke 失败时明确阻断，不绕过到用户主测阶段。
6. 不默认安装 androidTest APK、暂停无障碍、强制结束应用或运行商业 instrumentation；用户车机上的例外必须有当次明确授权，且脚本必须使用独立测试设备配置与显式确认参数，禁止复用默认 `VEHICLE_ADB_SERIAL` 静默执行。

## 4. 交付

说明改动摘要、本机与车机验证结果、安装版本、Git 状态、提交/推送/发布状态、客观阻断和用户主测清单。
