# 03T 时长修复与网易云专项诊断

## 1. 目标与边界

1. 用户已授权实现：修复 03T 蓝牙时长解释；诊断助手只保留网易云播放状态取证。QQ 音乐的车载蓝牙歌词字段是用户已确认的预期行为，不在本轮解析或修复。
2. 03T 公开 BluetoothMediaBrowserService 的实际原始时长为 249773 / 206326 / 140000 毫秒。删除按 Android 版本指定秒的假设；保留 A2dp、公开 Browser 补齐、统一仲裁、时间线、缓存和自动歌词 2000ms 门槛。
3. 网易云公开暂停并不证明实际音频暂停。报告同时保存用户标记、外部公开会话事件和可访问的新版本歌词运行记录，不伪造暂停原因或把诊断自己的仲裁结果当成主应用结果。

## 2. 物理锚点与职责

所有 Kotlin 文件采用 UTF-8，namespace 保持现有 ASCII 包声明 `com.ninepointnine.desktoplyrics`。新增主源从 `MediaSessionMetadataPolicy.kt` / `LyricsOverlayService.kt` 复制包声明；新增诊断源从 `DiagnosticActivity.kt` 复制包声明；测试文件头从 `MediaSessionMetadataPolicyTest.kt` 复制。结构新增后先编译再继续业务。

| 层级 | 来源 / 目标与入口 | 不变量 |
|---|---|---|
| 媒体适配 | 原位 `PublicMediaBrowserSessionRegistry.kt` / `durationUnitFor` | 两个已取证公开蓝牙服务均为毫秒，移除 SDK 参数；其余契约不凭歌词候选推断 |
| 有界记录 | 新增主源 `MediaDiagnosticTrace.kt` / `MediaDiagnosticTrace` | 统一毫秒单调时基，最多 256 事件 / 192 KiB；淘汰数量显式进入报告 |
| 应用观测 | 新增主源 `LyricsRuntimeDiagnostics.kt` / `MediaDiagnosticsProvider`；原位 `LyricsOverlayService.kt` | 同签名权限门禁，最多 60 秒；被动记录实际仲裁、播放和歌词可用状态，不启动或控制媒体、不包含歌词正文、许可或设备秘密 |
| 外部适配 | 原位诊断源 `MediaContractDiagnosticCollector.kt` / `start`、`finish`、`mark` | 先取连接前基线，注册全部活动会话回调；复用公开 Browser 注册表、最多 8 连接，记录连接与会话生命周期；不计算第二份选源结论 |
| 限时运行 | 新增诊断源 `MediaDiagnosticService.kt` | 用户启动后前台服务录制 60 秒，切到网易云后继续工作；完成 / 中止 / 销毁释放回调和连接，不请求音频焦点、不发送媒体控制 |
| 界面与产物 | 原位 `DiagnosticActivity.kt`、`DiagnosticReportWriter.kt`、诊断 Manifest / strings | 只保留开始、网易云入口、现场标记、结束与 ZIP 导出；原子写入并验证 JSON / ZIP / 字节数 / SHA-256，保留最近 3 份 |
| 伴随取证 | `collect-desktopcast-diagnostics.mjs` 替换为 `collect-netease-diagnostics.mjs` | 可选一次性 ADB 会话 / 音频焦点 / 相关日志；不清日志、不控制播放器、不成为运行依赖 |

移除 `CastCapabilityDiagnosticCollector.kt`、仪表 Presentation、Framework / AndroidX 双重探测及对应专用依赖；移除车辆、网络发现和投屏专用权限。应用身份、商业主链、已验收设置页骨架和正式歌词显示规则不变。

## 3. 证据契约

1. 报告 schema 2，记录助手版本、系统版本、网易云版本、采样起止单调时间与墙钟、完整结束原因、事件保留 / 丢弃数量。会话 ID 是本次记录内的稳定编号，不输出原始 Token。
2. 元数据记录公开 ID、原始 / 展示三字段、原始时长、共享归一时长和单位；状态记录原始枚举、位置、速度、发布时刻、相对采样时刻的年龄和公开错误字段。
3. 用户标记只说明用户声明的动作 / 听感，不冒充程序检测到的事实。首次基线先于 Browser 连接，报告能判断暂停是否早已存在。
4. 主应用只向持同签名权限的助手提供有限记录；未安装、旧版本、不匹配签名、主服务未运行或接口不可访问均作为证据缺口记录，不伪装成功。不采集通知正文、账号、网络地址或音频。报告保存在助手私有 `files/diagnostics`，通过系统文件选择器导出；开发脚本使用诊断包的 `run-as` 读取同一份文件，避免依赖 Android 11 外部目录访问。
5. 未采到原始暂停转变时不能推导原因。真实音频与公开会话矛盾时可追加 ADB 音频焦点与日志；第三方内部暂停调用原因可能不在公开接口中。

## 4. 必须通过的验证

1. 编译：新增主源 / 诊断源与 Manifest 的最小编译；最终 debug / diagnostic 构建、受影响 lint。
2. 定向 JVM：两种蓝牙服务毫秒契约与三组 03T 数据、2000ms 匹配边界、既有仲裁 / 适配 / 时间线回归；事件容量、UTF-8 字节预算、序号和截断标记；ZIP 原子生成、摘要、完整解析与保留策略。
3. 运行 smoke：保留数据 debug 安装及已有基础 smoke；诊断包授权缺失状态、用户启动、后台录制、标记、正常 / 提前完成与完整 ZIP，确认旧功能入口已删除、进程无致命错误。相关接口权限门禁不得向 shell 或异签名应用泄露运行数据。
4. 文档 / Skill / diff 检查；可执行环境内全部必需验证通过才交付。目标车机离线先尝试重连；仍不可用时报告客观阻断，并使用可用模拟器覆盖 Android 运行闭环。
5. 03T 与车内网易云最终用户确认限于正常标题蓝牙自动歌词和网易云实际播放 / 暂停 / 恢复 / 切歌的一份完整记录，不以其它设备冒充验证。
6. 新增 androidTest 源 MediaDiagnosticFixtureInstrumentationTest.kt，文件头从 LyricsCacheInstrumentationTest.kt 复制；只允许 Google Android 模拟器，建立无音频、无焦点请求的公开测试 MediaSession，生成暂停 / 播放 / 两组毫秒时长 / 切歌 / 销毁事件。独立助手报告必须读到完整回调与相同会话编号；不在日常车机运行，也不把夹具报告当成网易云现场证据。

暂无明确次生风险；同签名观测接口是可见性边界，已作为报告证据缺口显式记录。

## 5. 用户采集与判读

1. 先打开新版 03歌词并保持歌词服务运行，再打开诊断助手并授权媒体读取。要观察主应用内部状态，两者必须来自同一签名渠道；开发 Debug 诊断包不能读取正式签名应用的运行记录。
2. 点击开始记录，再打开车机网易云，复现播放、暂停、恢复和切歌。录制期间可通过通知栏记录是否听到声音；返回助手后也可标记已点播放、已点暂停或已切下一首。这些标记表示用户声明，不替代原始回调时间。
3. 60 秒自动结束，也可提前结束。返回助手，点击导出报告，将完整 ZIP 发回；不要复制或截取 JSON 文本。ZIP 内 `report.json` 保存证据，`integrity.json` 保存完整性摘要。
4. 判读先检查完整结束、事件是否截断和 `lyricsRuntimes` 可见性，再对齐用户听感、公开状态 / 更新时间、主应用真实选源和 WebView 投递。用户仍听到音乐而网易云持续发布旧 PAUSED，只能认定公开媒体契约停滞；音频确实停止时，使用可选 ADB 音频焦点和相关日志继续区分，不能用公开 PAUSED 枚举直接命名内部暂停原因。

## 6. 诊断助手品牌与构建

1. 用户于 2026-09-07 指定诊断应用改名“03诊断助手”。名称通过 diagnostic 的 `strings.xml` 同时覆盖应用和启动 Activity；包名、namespace、签名与升级身份保持原值，网易云专项录制范围不变。
2. Logo 使用 `https://9.9studio.fun/icar03/tutorial` 顶栏实际引用的 `/vehicle-solutions/icar03/03app-install-logo.png`。源图为 `1024 x 1024`，SHA-256 为 `53e32bc7455fd93951b53fc6ead9b409e1215f2eecfbf5ae2a022ab45caea3a5`，已与官网在线文件逐字节核对；仅缩放至 `512 x 512` 后进入 `app/src/diagnostic/res/drawable-nodpi/ic_launcher_art.png`，摘要为 `8950291b45bb56187a24edc5f65e6b7b64f276565b856024d24e7e0deb4aff8b`。诊断界面复用该资源且不再着色；主应用图标保持独立。
3. `assembleDiagnostic` 继续继承 debug 构建属性，产物可以调试，版本后缀仍为 `-diagnostic`；这不是主应用的 staging 包。该包沿用开发签名时，可以采集公开媒体与听感标记；与 Release 的正式签名不一致，正式主应用内部观测会明确报告 `signature_mismatch`，不能把这部分说成已采集。
