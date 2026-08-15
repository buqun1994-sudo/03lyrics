# AGENTS.md

核心：用中文沟通；磁盘中的现有源码是唯一工程真值。

## 1. 项目身份

1. 本仓库是 `03lyrics`，面向 iCAR 03 车机的轻量悬浮歌词应用。
2. Android 应用当前显示名称为“03歌词”，包名为 `com.tcrrry.desktoplyrics`；未经迁移方案确认，不改包名、签名身份或现有升级链。
3. 本项目已是正式 Android 工程，不处于模板初始化状态；禁止运行 `project-bootstrap` 或写入 `INIT_REQUIRED`。
4. `03桌面` 是独立产品，不是本项目模块。应用抽屉、APK 安装、应用卸载、快捷控制和右侧抽屉属于 `03桌面`，不得混入本仓库。
5. 本仓库保留上游 `tcrrry/desktop-lyrics` 的历史作为来源追溯；默认远端 `origin` 必须指向 `buqun1994-sudo/03lyrics`。

## 2. 新对话入口

1. 第一轮先读取本文件和 `docs/README.md`。
2. 按任务信号读取产品、架构、验证、安全、运维或规则文档，不一次性加载全部文档。
3. 涉及本机构建、Android SDK、ADB 或实机时，读取 `.codex/local-context.properties`；文件不存在时参考 `.codex/local-context.properties.example`，不得猜测个人路径。
4. 开工前检查 `git status --short --branch`，保留用户已有改动。
5. 代码行为以 `app/src/main/`、`app/build.gradle.kts` 和 `AndroidManifest.xml` 为准；README 或旧对话与源码冲突时，以源码为准并同步文档。
6. 涉及设置页菜单、布局、开关、颜色、透明度或窗口动效时，必须先读取 `docs/architecture/iCAR车机UI设计规范.md`；该文档中的设置双栏规格已经完成实机验证和用户主测，后续施工直接复用，不重新建立设置页视觉基线。
7. 设置页已确认事实：标准窗口为 `1230 x 810px`；左侧导航与右侧内容双栏、两侧背景透明度和深浅色值、页面间距及整窗进出动效均已定稿；原车标准开关为 `64 x 36px`、滑块 `30 x 30px`，项目目标资源为约 `73 x 41dp`、滑块 `34dp`。若运行画面不符合这些尺寸，应修正当前控件实现，不得重新打开原车设置页测量同一规格或反向改写既有规范。
8. 新增设置菜单、权限状态或操作项时，只在现有左侧导航、右侧内容容器以及集中维护的 `dimens`、style、drawable 和语义色资源上扩展；不得重做已验收的设置页骨架、背景、色板或已有控件体系。截图用于验证本次新增内容和回归，不用于重复推导上述已确认事实。

## 3. 物理边界

1. `app/src/main/kotlin/com/tcrrry/desktoplyrics/`：Android 业务、车机适配和基础设施主链。
2. `app/src/main/assets/lyrics_overlay.html`：歌词悬浮层的 WebView 呈现与交互。
3. `app/src/main/res/`：原生设置页、图标、主题与 Android 资源。
4. `app/src/test/`：不依赖车机的 JVM 单元测试。
5. `docs/`：产品、架构、验证、安全、运维、规则和进度真值。
6. `.agents/skills/`：需要动态判断的高频 AI 工作流。
7. `scripts/`：可重复执行的仓库检查；脚本不得写死个人路径、车机地址或密钥。
8. `.codex/`：项目级 Codex 配置和本机上下文入口；`local-context.properties` 永不提交。

## 4. 架构主链

1. `MainActivity` 只承接用户设置和向悬浮服务发送动作。
2. `LyricsOverlayService` 是运行态总协调者，负责 MediaSession、前台服务、窗口生命周期、车机表面切换和 WebView 桥接。
3. `LyricsResolutionCoordinator` 只保留最新歌词请求，负责取消旧请求并仅对瞬时失败退避重试一次。
4. `DirectLyricsRepository` 负责多来源完成顺序调度；`PublicLyricsSources` 负责外部协议解析和可断开的 HTTP 访问。
5. `RecordingIdentity` 负责录音身份归一；`LyricsCandidateSelector` 负责显式准入、排序和入选证明，不让排序分值充当安全门槛。
6. `LyricsCache` 负责本地歌词缓存与淘汰，并携带当前匹配策略可重放的入选证明；不把缓存策略复制到服务层。
7. `IcarDisplayStateMonitor` 只读观察已验证的公开系统状态，不写车辆状态，不连接 CAN、无障碍或猜测的私有接口。
8. `BootReceiver` 只在用户已开启自动恢复时重启歌词服务。
9. 跨两个以上调用点的规则必须回到上述 owner，不在 UI、广播接收器或临时分支中复制第二套状态机。

## 5. 目标设备与性能边界

1. 目标车机：Android 9（系统 SDK 28）、`1920 x 1080`、型号标识 `S56_HQX`、高通 8155。
2. 设备整机负载偏高：后台保持事件驱动，禁止高频轮询、持续截图、实时模糊、无障碍模拟操作和 ADB 常驻。
3. ADB 仅用于开发、安装、日志和实机验证，不得成为正式运行依赖。
4. 不要求 root、手机或电脑长期连接；不直接控制车辆安全相关能力。
5. 车机状态未知时必须保守回到顶栏模式，不能因猜测扩大悬浮区域。

## 6. 安全红线

1. 禁止提交真实签名文件、密码、token、私钥、`local.properties`、`keystore.properties`、本机绝对路径、车机地址、日志和构建产物。
2. 新增权限前必须说明用户价值、Android 版本边界和运行级验证；与歌词能力无关的高风险权限不得加入。
3. 外部歌词服务属于第三方依赖，核心层不得把单一服务返回格式扩散到 UI。
4. 上游仓库当前未附带许可证文件；公开发布、重新授权或移除来源说明前必须人工确认权利边界。
5. 普通施工完成后的 debug APK 保留数据覆盖安装与最简 smoke 属于本项目默认授权；清除数据、卸载、重启车机、修改签名、安装 release、发布 Release 或上传产物仍必须获得用户当次明确指令。

## 7. Skills 路由

1. 发现稳定复用规则、重复失败或模板级候选时，使用 `.agents/skills/rule-discovery/SKILL.md`。
2. 收尾、更新进度、准备提交或交接时，使用 `.agents/skills/task-closeout/SKILL.md`。
3. 创建、更新或审查项目级 Skill 时，使用 `.agents/skills/skill-authoring/SKILL.md`。
4. 将已验证的通用能力去专有化并回流 `NewProject` 时，使用 `.agents/skills/template-feedback/SKILL.md`。
5. 简单机械检查优先写脚本或测试，不为一次性事实创建 Skill。

## 8. 验证与收尾

1. 文档、规则、Skill 或脚本变更至少执行：
   - `node scripts/check-project-docs.mjs`
   - `node scripts/check-skills.mjs`
   - `git diff --check`
2. 本项目默认采用敏捷施工：先完成根因级实现，再执行 `docs/testing/验证矩阵.md` 中与改动直接相关的最短自动验证；不默认追加全量单测、lint 或重复构建。
3. Android 实现完成且本机最短验证通过后，AI 必须自动执行保留数据的 debug 覆盖安装，并完成应用启动、进程/版本、致命日志与本次功能直接相关的最简车机 smoke；自动验证通过后才交给用户主测。
4. 用户反馈有问题时继续修复并重复“构建 → 安装 → 最简 smoke”；用户反馈无问题并明确要求提交后，才允许执行 `git add` 和提交。未经明确要求，不推送或发布。
5. 收尾必须说明改动、实际验证、Git 状态、未执行项的客观原因和剩余最小实机确认。
6. 敏捷模式只缩短验证链路，不允许以此引入临时 Hack、复制状态机、降低架构质量或跳过编译/打包、自动安装和最简 smoke；安全、签名、数据迁移和发布变更仍按高风险边界单独验证。
