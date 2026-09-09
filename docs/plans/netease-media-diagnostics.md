# 03T 时长修复与网易云专项诊断

## 1. 目标与边界

1. 用户已授权实现：修复 03T 蓝牙时长解释；诊断助手只保留网易云播放状态取证。QQ 音乐的车载蓝牙歌词字段是用户已确认的预期行为，不在本轮解析或修复。
2. 03T 公开 BluetoothMediaBrowserService 的实际原始时长为 249773 / 206326 / 140000 毫秒。删除按 Android 版本指定秒的假设；保留 A2dp、公开 Browser 补齐、统一仲裁、时间线、缓存和自动歌词 2000ms 门槛。
3. 网易云公开暂停并不证明实际音频暂停。用户只点击开始并在网易云内复现；助手自动保存外部公开会话事件、限时补读和可访问的新版本歌词运行记录，不要求用户打标记，不伪造暂停原因或把诊断自己的仲裁结果当成主应用结果。

## 2. 物理锚点与职责

所有 Kotlin 文件采用 UTF-8，namespace 保持现有 ASCII 包声明 `com.ninepointnine.desktoplyrics`。新增主源从 `MediaSessionMetadataPolicy.kt` / `LyricsOverlayService.kt` 复制包声明；新增诊断源从 `DiagnosticActivity.kt` 复制包声明；测试文件头从 `MediaSessionMetadataPolicyTest.kt` 复制。结构新增后先编译再继续业务。

| 层级 | 来源 / 目标与入口 | 不变量 |
|---|---|---|
| 媒体适配 | 原位 `PublicMediaBrowserSessionRegistry.kt` / `durationUnitFor` | 两个已取证公开蓝牙服务均为毫秒，移除 SDK 参数；其余契约不凭歌词候选推断 |
| 有界记录 | 新增主源 `MediaDiagnosticTrace.kt` / `MediaDiagnosticTrace` | 统一毫秒单调时基，最多 256 事件 / 192 KiB；淘汰数量显式进入报告 |
| 应用观测 | 新增主源 `LyricsRuntimeDiagnostics.kt` / `MediaDiagnosticsProvider`；原位 `LyricsOverlayService.kt` | 同签名权限门禁，最多 60 秒；被动记录实际仲裁、播放和歌词可用状态，不启动或控制媒体、不包含歌词正文、许可或设备秘密 |
| 外部适配 | 原位诊断源 `MediaContractDiagnosticCollector.kt` / `start`、`finish`、自动采样 | 先取连接前基线，注册全部活动会话回调；复用公开 Browser 注册表，诊断限时最多 16 连接，正式主链默认仍为 8；每 2000ms 补读状态并只记录变化，每 10 秒保存检查点，不计算第二份选源结论 |
| 限时运行 | 新增诊断源 `MediaDiagnosticService.kt` | 用户启动后前台服务录制 60 秒，切到网易云后继续工作；完成 / 中止 / 销毁释放回调和连接，不请求音频焦点、不发送媒体控制 |
| 界面与产物 | 原位 `DiagnosticActivity.kt`、`DiagnosticReportWriter.kt`、诊断 Manifest / strings | 只保留开始与录制后 ZIP 导出；缺授权时由开始动作进入系统授权，返回后自动开始；原子写入并验证 JSON / ZIP / 字节数 / SHA-256，保留最近 3 份 |
| 伴随取证 | `collect-desktopcast-diagnostics.mjs` 替换为 `collect-netease-diagnostics.mjs` | 可选一次性 ADB 会话 / 音频焦点 / 相关日志；不清日志、不控制播放器、不成为运行依赖 |

移除 `CastCapabilityDiagnosticCollector.kt`、仪表 Presentation、Framework / AndroidX 双重探测及对应专用依赖；移除车辆、网络发现和投屏专用权限。应用身份、商业主链、已验收设置页骨架和正式歌词显示规则不变。

## 3. 证据契约

1. 自动录制报告 schema 3，伴随脚本继续兼容 schema 2。记录助手版本、系统版本、采样起止单调时间与墙钟、完整结束原因、事件保留 / 丢弃数量。会话 ID 是本次记录内的稳定编号，不输出原始 Token。诊断外部轨迹限 512 条 / 384 KiB，主应用轨迹仍限 256 条 / 192 KiB，完整报告仍限 1 MiB。
2. 元数据记录公开 ID、原始 / 展示三字段、原始时长、共享归一时长和单位；状态记录原始枚举、位置、速度、发布时刻、相对采样时刻的年龄和公开错误字段。
3. 不采集跨应用触摸事件，不录音，不把全局音频状态归属于网易云。播放 / 暂停 / 切歌通过原始回调和自动补读判断；补读无法证明两秒间隙中的全部动作，缺口显式进入报告。首次基线先于 Browser 连接，报告能判断暂停是否早已存在。额外保存音量、静音、输出类型和匿名音频配置，单字段查询异常不得抹掉其它字段。
4. 主应用只向持同签名权限的助手提供有限记录；未安装、旧版本、不匹配签名、主服务未运行或接口不可访问均作为证据缺口记录，不伪装成功。不采集通知正文、账号、网络地址或音频。报告保存在助手私有 `files/diagnostics`，通过系统文件选择器导出；开发脚本使用诊断包的 `run-as` 读取同一份文件，避免依赖 Android 11 外部目录访问。
5. 未采到原始暂停转变时不能推导原因。真实音频与公开会话矛盾时可追加 ADB 音频焦点与日志；第三方内部暂停调用原因可能不在公开接口中。
6. 开始和结束时记录当前 Android 用户可见的启动应用（最多 128 个）、实际包名 / 版本 / 启动组件，以及公开媒体服务的完整发现清单 / 版本 / 权限 / 连接结果。查询异常保留类型，`installed=null` 表示未知；`NameNotFoundException` 明确为未找到或不可见，不直接宣称已卸载。不申请全应用查询、使用情况访问或新的高风险权限。

## 4. 必须通过的验证

1. 编译：新增主源 / 诊断源与 Manifest 的最小编译；最终 debug / diagnostic 构建、受影响 lint。
2. 定向 JVM：两种蓝牙服务毫秒契约与三组 03T 数据、2000ms 匹配边界、既有仲裁 / 适配 / 时间线回归；事件容量、UTF-8 字节预算、序号和截断标记；ZIP 原子生成、摘要、完整解析与保留策略。
3. 运行 smoke：保留数据 debug 安装及已有基础 smoke；诊断包授权缺失状态、一次启动、后台自动采样、60 秒自然完成与完整 ZIP，确认手动标记 / 提前结束 / 播放器入口及通知按钮已删除、进程无致命错误。相关接口权限门禁不得向 shell 或异签名应用泄露运行数据。
4. 文档 / Skill / diff 检查；可执行环境内全部必需验证通过才交付。目标车机离线先尝试重连；仍不可用时报告客观阻断，并使用可用模拟器覆盖 Android 运行闭环。
5. 03T 与车内网易云最终用户确认限于正常标题蓝牙自动歌词和网易云实际播放 / 暂停 / 恢复 / 切歌的一份完整记录，不以其它设备冒充验证。本轮 03T 用户已完成并确认上述手动播放 / 暂停 / 恢复 / 切歌验证通过。
6. 新增 androidTest 源 MediaDiagnosticFixtureInstrumentationTest.kt，文件头从 LyricsCacheInstrumentationTest.kt 复制；只允许 Google Android 模拟器，建立无音频、无焦点请求的公开测试 MediaSession，生成暂停 / 播放 / 两组毫秒时长 / 切歌 / 销毁事件。独立助手报告必须读到完整回调与相同会话编号；不在日常车机运行，也不把夹具报告当成网易云现场证据。

暂无明确次生风险；同签名观测接口是可见性边界，已作为报告证据缺口显式记录。

## 5. 用户采集与判读

1. 停车后保持 03歌词开启，打开 03诊断助手，点击“开始记录”。首次出现系统授权页时允许媒体读取，再返回助手，录制自动开始。
2. 返回桌面，打开平时使用的网易云，播放一首歌约 10 秒。
3. 在网易云里暂停约 3 秒，再继续播放约 10 秒。
4. 切换到下一首，继续播放约 10 秒。过程中不需要返回诊断助手，也不用点击任何标记。
5. 从开始记录起 60 秒后，回到 03诊断助手，点击“导出报告”，把导出的完整 ZIP 发回。

判读先检查完整结束、事件截断、实际应用身份和 `lyricsRuntimes` 可见性，再对齐外部状态变化、全局音频 / 音量、主应用真实选源和 WebView 投递。要观察主应用内部状态，两者必须来自同一签名渠道；开发 Debug 助手不能读取正式签名应用的运行记录，报告保留 `signature_mismatch`，不要求车主在采集流程中选择通道。报告不能还原其它应用的每次点击或代替人的实际听感；音频确实停止时，使用可选 ADB 音频焦点和日志继续区分，不能用公开 PAUSED 枚举直接命名内部暂停原因。

## 6. 诊断助手品牌与构建

1. 用户于 2026-09-07 指定诊断应用改名“03诊断助手”。名称通过 diagnostic 的 `strings.xml` 同时覆盖应用和启动 Activity；包名、namespace、签名与升级身份保持原值，网易云专项录制范围不变。
2. Logo 使用 `https://9.9studio.fun/icar03/tutorial` 顶栏实际引用的 `/vehicle-solutions/icar03/03app-install-logo.png`。源图为 `1024 x 1024`，SHA-256 为 `53e32bc7455fd93951b53fc6ead9b409e1215f2eecfbf5ae2a022ab45caea3a5`，已与官网在线文件逐字节核对；仅缩放至 `512 x 512` 后进入 `app/src/diagnostic/res/drawable-nodpi/ic_launcher_art.png`，摘要为 `8950291b45bb56187a24edc5f65e6b7b64f276565b856024d24e7e0deb4aff8b`。诊断界面复用该资源且不再着色；主应用图标保持独立。
3. `assembleDiagnostic` 继续继承 debug 构建属性，产物可以调试，版本后缀仍为 `-diagnostic`；这不是主应用的 staging 包。该包沿用开发签名时，可以采集公开媒体与自动音频观测；与 Release 的正式签名不一致，正式主应用内部观测会明确报告 `signature_mismatch`，不能把这部分说成已采集。

## 7. 03T 现场结果（2026-09-09）

1. 03T 多媒体中心版本为 `com.tencent.wecarflow`，真实网易云公开服务为 `com.tencent.wecarflow/com.mychery.cloudmusic.service.CloudMusicService`。该服务未提供标准 Browser intent-filter，且公开状态出现 `active=false / PAUSED`；补充发现规则仍能通过公开 Browser 连接验证，正式主链在与蓝牙旧暂停会话并存时选中网易云。
2. 现场播放轨迹观察到公开位置持续增长，并实际切换到“电台情歌 / 莫文蔚 / 做自己”等歌曲。主应用轨迹记录了同源歌曲、`248373ms` 时长、位置推进、`CACHED_AUTOMATIC`、`hasLyrics=true`、`matchingLyrics=true`、`playbackState=playing`，原生与 WebView 投递代际均为 `11`。用户随后确认网易云播放、暂停、恢复、切歌及歌词查找页面验证通过。
3. 外部诊断轨迹完整保存 `60071ms`、`49` 条事件且未截断；主应用轨迹保留 `256` 条、丢弃 `377` 条并明确 `truncated=true`。该报告证明持续播放和匹配投递，不覆盖切歌后旧歌词清空全过程；切歌清空结论只引用用户手测，不从报告越界推导。诊断自身外部 Browser 清单未注入补充 resolver，因此报告清单可能缺少 `CloudMusicService`，但主应用 `lyricsRuntimes` 已直接记录实际选中端点。
