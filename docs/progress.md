# 项目进度

## 2026-08-13 系统深浅主题适配

1. 已确认：目标车机 Android 标准 `Configuration.uiMode` 当前可返回夜间配置；厂商另有主题键，但不进入应用正式主链。
2. 已完成：设置页颜色收敛为 `values` / `values-night` 语义资源，背景、文字、分隔线、按钮、选项和开关随系统主题选择。
3. 已完成：常驻歌词服务在配置变化时向 WebView 事件下发 `light` / `dark`，不重载歌词页，不改歌曲、歌词行或播放时间线状态。
4. 已完成：顶栏与桌面歌词使用统一语义色变量，浅色模式改用深色文字与亮色对比阴影，深色模式保留原有亮色歌词。
5. 已沉淀：普通 Android 施工在最短本机验证后自动保留数据覆盖安装 debug APK，由 AI 完成基础与功能最简 smoke，通过后交给用户主测；用户反馈无问题并明确要求提交后才进入 Git 提交。
6. 已新增：`scripts/install-and-smoke.mjs` 机械执行设备连接、保留数据覆盖安装、设置页启动、版本/进程、运行中服务恢复和应用致命日志检查；`task-closeout` 已同步该流程。
7. 本机最短验证：`assembleDebug` 成功，悬浮层 JavaScript 语法、项目文档、项目 Skill 和 Git 文本检查通过。
8. 车机基础 smoke：已覆盖安装 `1.14-icar03`（versionCode `114`）；设置页启动、应用进程、歌词服务恢复和应用致命日志检查通过。
9. 主题功能 smoke：实际完成深色 → 浅色 → 深色切换；设置页与歌词表面均换色，应用 PID 和歌词服务全程保持连续，未发现崩溃或 WebView 错误；测试后已恢复深色。
10. Skill 校验口径：项目强制使用 `node scripts/check-skills.mjs`；Codex `quick_validate.py` 只在当前环境可用时作外部复核，不建立或维护额外 Python 环境。
11. 待用户主测：在车机真实主题入口切换深浅色，确认设置页、顶栏歌词和壁纸歌词的视觉可读性与歌词连续性。
12. 未执行：未提交、推送或发布。

## 2026-08-13 项目上下文底座

1. 目标：让后续 Codex 对话不再依赖一整段启动文案即可进入 `03lyrics`。
2. 已确认：项目自己的 GitHub 远端为 `buqun1994-sudo/03lyrics`；上游历史保留为来源追溯。
3. 已确认：当前 Android 主链包含车机状态适配、顶栏/桌面歌词、同步歌词匹配、缓存和开机恢复。
4. 本轮新增：根入口、文档路由、项目/产品/验证/安全/运维真值、通用工作流 Skill、机械快检和本机上下文入口。
5. 本轮明确排除：`03桌面` 的应用抽屉、APK 安装、卸载、快捷控制和右侧抽屉需求。
6. 文档检查：`node scripts/check-project-docs.mjs` 通过。
7. Skill 检查：`node scripts/check-skills.mjs` 通过；四个 Skill 曾通过 Codex `quick_validate.py` 外部复核。
8. 本机环境：JDK 17、Android SDK 34、Build Tools 34.0.0、首选 ADB 和 Gradle Wrapper 检查通过。
9. Android 验证：`testDebugUnitTest lintDebug assembleDebug` 成功，共执行 30 个 JVM 测试、0 失败；lint 为 0 error、40 warning；debug APK 构建成功。
10. 本轮没有安装车机、提交、推送或发布。

## 当前工程事实

1. 当前版本：`1.14-icar03`（versionCode `114`）。
2. 当前包名：`com.tcrrry.desktoplyrics`。
3. 目标车机：Android 9、`1920 x 1080`、`S56_HQX`、高通 8155。
4. 最近业务提交：`740c4dd Add iCar lyrics integration and caching`。

## 已知文档差异

1. 根 README 仍主要保留上游公开版本介绍和链接，不是当前车机适配版本的完整产品真值。
2. 当前车机适配的工程事实以 `docs/`、`app/build.gradle.kts` 和源码为准。

## 记录规则

1. 只记录已发生事实、实际验证、客观阻断和下一步。
2. 不复制长日志，不把未来计划写成已完成。
