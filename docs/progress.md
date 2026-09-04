# 项目进度

## 2026-09-04 03T 独立诊断 APK 与 03投屏能力取证

1. 已完成 Debug-only 诊断入口和能力采集主链设计落地：独立诊断 Activity、设备/网络/mDNS/SSDP/端口/MediaCodec/Automotive/窗口采集、Framework 与 AndroidX MediaBrowser 采集，以及本地 JSON/TXT 报告写入。诊断 APK 不加载 03投屏 native 库、不启动投屏服务、不进入 Release 主链。
2. 已新增一次性主机侧 ADB 取证脚本 `scripts/collect-desktopcast-diagnostics.mjs`，覆盖 03投屏正式/Debug 包、ABI/native 库静态检查、Launcher 启动、15 秒 Activity/Service/PID/exit-info、端口、窗口、媒体、codec、Automotive 和筛选 logcat。脚本默认保留既有 logcat，只有显式 `--clear-logcat` 才清理；已修正 native ELF 提取为字节流，避免二进制被文本解码破坏。
3. 诊断变体已完成 Manifest 合并、Kotlin 编译、诊断 JVM 测试、lint 和 APK 打包；最终包名为 `com.ninepointnine.desktoplyrics.diagnostic`，版本为 `1.0.8-icar03-diagnostic / versionCode 122`。诊断 APK 已在 `S56_HQX / Android 9` 保留数据覆盖安装并启动，真实窗口为 `1230 x 810px`，进程稳定且没有诊断包致命日志。
4. 已通过 `cmd notification allow_listener` 幂等追加诊断通知监听授权，原 `03歌词.test` 监听组件保持不变；系统对诊断 `MediaListenerService` 的绑定为 `requested=true / received=true / hasBound=true`。授权后报告确认直接活动会话为 `1` 个并选中 `com.android.bluetooth`，Framework 与 AndroidX Browser 均连接成功并读取《火力全开》的歌名、歌手、专辑和毫秒时长。
5. 车机报告同时确认当前 `03投屏.test` 包含 Main/Fullscreen Activity、CastService 和四个预期 arm64 native 库，设备支持 arm64，Wi-Fi 多播锁可获取，SSDP 和 `_airplay._tcp` / `_raop._tcp` 发现链可工作。真实 03T Android 11 和正式版 03投屏的最终兼容结论仍需在目标用户设备上按同一流程采集，不以 Android 9 样机冒充通过。
6. 根据诊断流程复核，APK 界面已移除不能自行完成授权的系统设置跳转和非必要分享面板；授权统一由 ADB 命令完成，报告仍写入车机本地目录，并由 `collect-desktopcast-diagnostics.mjs` 自动拉取 JSON/TXT。
7. 最终去冗余版本已重新构建、保留数据覆盖安装并完成真实页面冒烟；APK 为 `com.ninepointnine.desktoplyrics.diagnostic / 03歌词诊断 / 1.0.8-icar03-diagnostic / versionCode 122`，桌面副本文件名为 `03lyrics-03cast-diagnostic.apk`；本轮重新构建后的哈希和签名信息以最终产物核验为准。诊断 APK 进程、Activity、通知监听绑定和完整报告生成均通过；ADB 脚本成功拉回车机 JSON/TXT 并完成 03投屏启动取证。
8. 本轮按网易云适配取证需求扩展诊断媒体契约：扫描所有公开 `MediaBrowserService`，记录组件、实际包名、版本、导出状态、权限并对最多 `8` 个公开服务做 Framework / AndroidX 有界连接探测；活动 MediaSession 增加实际包名集合和 `sessionKey`，动态采样可判断同一会话是否发生字段串线。本机诊断变体编译、单测和 lint 通过；目标车机新增实机验证待无线 ADB 从 `offline` 恢复后补做，未将该项写成已通过。

## 2026-09-04 顶栏歌词双行字号五档设置（已完成）

1. 顶栏字号设置已收敛为按显示行数展示：一行只显示“歌词字号”，两行显示“一行歌词字号”和“二行歌词字号”；两组均为“小 / 略小 / 标准 / 微大 / 大”五档，标准值保持主行 `32px`、辅助行 `20px`。
2. 实际字号档位固定为主行 `26 / 29 / 32 / 35 / 37px`、辅助行 `16 / 18 / 20 / 22 / 23px`；一行模式下翻译沿用主行对应比例，两行模式下翻译和下一句共用二行字号。行数切换时第一行字号继承当前一行字号，隐藏的旧分组不会覆盖用户选择。
3. 已完成 `testDebugUnitTest`（325 条通过、2 条既有跳过）和 `assembleDebug`；项目文档、Skill、Git 文本检查和悬浮层 JavaScript 语法检查均通过。
4. 已按用户授权卸载旧测试包、安装当前 Debug 包并完成悬浮窗、通知监听和窗口避让无障碍授权；用户人工验证通过。安装 smoke 最终确认设置页、唯一前台歌词服务、表面占用租约、播放状态监听和唯一 `APPLICATION_OVERLAY` 均正常，未发现应用致命日志。
5. 已递增唯一版本真值至 `1.0.4-icar03` / `versionCode 118`，并通过 03 APP 统一入口生成新的 staging 测试包：`03歌词-staging-v1.0.4-icar03-test.apk`（APK SHA-256：`71718d86f858026883cd346f6222fe68295fb83c117bcc64f2f68e521ad591df`；ZIP SHA-256：`149a347c2fbeab38165dff60786b9eab778f102786d8634ce4f433b2005526a2`）。
6. 新 staging 包身份、长期 staging 证书和 APK v2 均已由统一入口校验。本轮未安装新 staging 包（用户仅要求提交与导出）；此前车机上的功能 Debug 包安装 smoke 和用户人工验证仍有效，车机授权与数据状态未再操作。

## 2026-09-04 退款后重新获取 Pro：staging 车机复验（客户端完成，待 Cloud staging 修正）

1. 已按用户要求通过 Cloud 03 APP 统一入口重新构建最终 staging 测试包；包名为 `com.ninepointnine.desktoplyrics.test`，版本为 `1.0.3-icar03-test` / `versionCode 117`，staging 证书摘要为 `1eb136fffd3f1e4c204d0933cab66c51ee4536a29e949b9c080925c01563b51d`，APK SHA-256 为 `8877140f94114ef11c250570fe4a3caae879fc1cafc284795f0dfc0f6fc89e0b`，统一入口校验了单 signer、APK v2 和 ZIP 单 APK 条目。
2. 已保留数据覆盖安装最终 staging APK；车机安装版本、测试包身份、系统授权、服务绑定和页面启动检查通过，基础 smoke 按预期停在当前退款基线的商业门禁恢复态，未清数据、未卸载、未重启、未运行商业 instrumentation。
3. 已确认客户端发送的 staging challenge 身份为测试包名；当前线上 `api-staging.9studio.fun` 却按正式包名校验 03lyrics。相同 staging 证书下，`com.ninepointnine.desktoplyrics.test` 返回 `403 app_signature_mismatch`，正式包名才返回 `200`；公开 staging trust bundle 也返回正式包名。这与 Cloud 源码、双轨登记和 03 APP 协议要求的 `.test` staging 身份不一致。
4. 因服务端身份配置漂移，车机尚未取得权威 `revoked` 响应，不能据此验收“权益已撤销 / 获取Pro / 最新报价 / 订单 / 二维码 / 返回后不重入”真实闭环；未把普通 `challenge 403` 映射为撤权，也未把正式包名临时写入客户端绕过服务端门禁。
5. 客户端本轮改动、定向商业状态机 / 布局测试、全量 `testDebugUnitTest`、`lintDebug`、`assembleDebug`、staging 统一打包、保留数据覆盖安装和最简车机 smoke 均已完成；待 Cloud staging 按双轨规则部署正确的 03lyrics `.test` 包名配置后，继续从当前退款状态复验真实购买入口闭环。

## 2026-09-03 1.0.3 双轨产物打包（已完成）

1. Release 版本真值由 `1.0.2-icar03 / versionCode 116` 升级为 `1.0.3-icar03 / versionCode 117`；Debug/staging 按统一规则显示为 `1.0.3-icar03-test / versionCode 117`。
2. staging 通过 Cloud 03 APP 统一入口构建并写入桌面测试包目录，生成 `03歌词-staging-v1.0.3-icar03-test.apk` 与同名 ZIP；统一入口已核对测试包名、版本、长期 staging 证书、单 signer、APK Signature Scheme v2 和单 APK ZIP。
3. production 使用正式公开信任配置和独立正式签名完成 `assembleRelease`；`minifyReleaseWithR8`、`shrinkReleaseRes`、`optimizeReleaseResources` 和 `assembleRelease` 均成功，`mapping.txt` 已生成。正式目录新增 `03歌词-v1.0.3-icar03.apk` 与同名 ZIP。
4. 独立回读确认：staging 为 `com.ninepointnine.desktoplyrics.test / 1.0.3-icar03-test / 117`，APK 大小 `6574630` bytes、SHA-256 `5d0f327064e4e6a692876c0638f3842726fc694a3897c5b2351d4bbd7f497971`，ZIP 大小 `5490868` bytes、SHA-256 `09313b95099c25b948089765083563b56129ecc31e2ae21545f290bf03ebd2e7`。
5. production 为 `com.ninepointnine.desktoplyrics / 1.0.3-icar03 / 117`，APK 大小 `2357915` bytes、SHA-256 `3b1efe89060282672949a6f1eff311a75e2a5fb383ee0f97ffc88ecc1182453f`，ZIP 大小 `1373851` bytes、SHA-256 `451bb7b64968905e371dfcc63d6a7e65d08fcd84cb687764df6102c0b1dd165b`；两个 ZIP 解包后的 APK SHA-256 均与外部 APK 完全一致。
6. `bump-release-version --check`、项目文档检查、Skill 检查和 Git 文本检查通过。本轮按用户要求只打包，未安装、未连接车机执行 smoke、未推送或发布。

## 2026-09-03 壁纸歌词左右位置、SR 动态避让与按实际位置 Dock 裁剪（已完成）

1. 设置页“歌词设置”在现有“歌词排版”后新增“歌词位置：左侧 / 右侧”分段项，默认右侧并持久化；右侧沿用现有排版，左侧同步镜像文字对齐、内容 padding 和原文缩放原点。
2. 壁纸歌词右侧窗口保持 `[660,90]..[1890,900]`，左侧窗口为水平对称的 `[30,90]..[1260,900]`。只读无障碍在目标 Launcher 内读取 SR 把手矩形，系统 `80ms` 事件合并后，相邻位置达到 `8px` 方向阈值便发布瞬时展开或收回提示，立即驱动 `250ms` 三次贝塞尔 `(0.2, 0.8, 0.2, 1)` 位移；最后一次事件后 `320ms` 复核并清除提示，再由 `window_mode=0/1/2/3` 稳定状态兜底。不跟随手势百分比、不新增轮询。
3. 原车 Dock 分类扩展为左、中、右独立状态；左侧歌词原位采用左 Dock，SR 推到右侧安全位置后采用中间 Dock，右侧歌词始终采用右 Dock。有效 Dock 的真实上沿只裁剪外层 Overlay，并保持完整 `810px` WebView 与动态 mask 终点；其它 Dock 不改变歌词几何，未知有效 Dock 继续保守退回顶栏。
4. 纯策略回归已覆盖默认右侧、左右镜像、SR 四个稳定值与未知值、目标车机 `8px` 方向阈值、低于阈值的抖动抑制、`320ms` 提示退场、固定 `250ms` 动画参数、左 / 中 / 右 Dock 独立分类和实际位置裁剪。前序完整 Debug JVM、Lint 与 debug APK 构建已通过；参数契约收口后再次定向运行 `IcarWindowAvoidancePolicyTest` 并执行 `assembleDebug`，结果成功。文档检查、Skill 检查和 Git 文本检查均通过。
5. 安装脚本已跟随双轨身份规则从 APK 读取 `.test` 包名和版本、按实际应用身份与源码 namespace 组合组件，并显式拒绝 Release 包。最新源码已保留数据覆盖安装为 `com.ninepointnine.desktoplyrics.test 1.0.2-icar03-test (116)`；设置页启动、应用进程、两类表面租约、窗口避让无障碍、播放状态监听、歌词服务恢复和致命日志基础 smoke 全部通过，正式包保持不存在，未清数据或重启车机。
6. 无截图运行级功能 smoke 已观察到：SR 展开时 Overlay 在 `window_mode` 仍为 `0` 时先从 `x=30` 移到采样点 `x=192`，随后终态变为 `1` 并到达 `x=660`；收回时同样在稳定值仍为 `1` 时提前向左移动。`window_mode=0` 且歌词原位时，左 Dock 令底边 `900 -> 570 -> 900`；`window_mode=1` 且歌词位于 `x=660` 时，中间 Dock 令底边 `900 -> 570 -> 900`。自动证据已覆盖早期响应与裁剪归属；用户完成多轮操作后授权提交。

## 2026-09-03 蓝牙字段语义恢复为平台无关的唯一解释（已完成）

1. 用户确认 QQ 音乐异常来自手机端“车载蓝牙歌词”开关；关闭该开关后，QQ 通过蓝牙恢复标准字段投影。因此车机端不再根据音乐平台、横杠或展示文本猜测字段边界。
2. `MediaSessionMetadataPolicy` 已移除蓝牙复合 `ARTIST` 拆分、平台能力画像和全部连字符 / 语言阈值；所有传输统一遵循 `TITLE` = 歌曲名、`ARTIST` = 完整歌手名、`ALBUM` = 专辑名。蓝牙 Browser 会话、时长单位归一、位置证据和时间线能力保持不变。
3. 回归测试覆盖 Apple Music 的 `A-Lin`、常见带横杠艺人、Unicode 破折号以及形如 `歌曲名-AAAAAA-BBBBBBBBB` 的完整艺人字段，证明任何横杠都不会被车机端拆解释。
4. 隔离 worktree 中定向媒体 / 设置 / 选源测试和完整 `testDebugUnitTest` 均通过，`assembleDebug` 成功；按用户授权卸载正式包并安装 `1.0.2-icar03-test (versionCode 116)` debug 包，启动和致命日志 smoke 通过。用户随后在手机切换 QQ 音乐与 Apple Music，歌词均正常显示。

## 2026-09-03 03 APP 测试身份简化（施工中，未发布）

1. 正式包名保持 `com.ninepointnine.desktoplyrics`；Debug/staging 测试包统一为 `com.ninepointnine.desktoplyrics.test`，版本名在同一正式版本后追加 `-test`。
2. Debug/staging 与 Release 共用根目录 `release-version.properties`（当前 `1.0.2-icar03` / `versionCode=116`）；每次只递增这一份版本文件即可连续覆盖更新测试包。测试包与正式包可并存，不能互相覆盖升级。
3. 本轮只同步构建、台账、检查和文档规则，不生成、不上传、不部署、不上线产物。

## 2026-09-03 蓝牙公开 Browser 兜底适配施工

1. 新增 `BluetoothMediaBrowserSessionBridge`，沿公开 `android.media.browse.MediaBrowserService` 动态发现 `com.android.bluetooth` 的已导出 `A2dpMediaBrowserService` / `BluetoothMediaBrowserService`，在 A2DP / BLE 路由存在且系统活动会话未返回蓝牙包时取得同一 `MediaSession.Token` 对应的 `MediaController`；系统控制器按 Token 去重并保持原顺序，桥接器单路由单连接，`3000ms` 超时、最多一次 `1000ms` 重试，挂起、路由移除、会话销毁和服务停止均有生命周期收敛与迟到回调隔离。
2. `MediaSessionMetadataPolicy` 增加显式 `MediaSessionDurationUnit` 与 `reportedPositionMs`：Android 9 A2DP 保持既有毫秒语义，Android 10/11 Browser 画像按秒归一；未知单位不猜测，重复毫秒值可由可信位置证据保留，零 / 负值、溢出和 `86_400_000ms` 上限均有规则测试。选源、录音代际、缓存、查词、设置和 WebView 主链未拆出第二套状态机。
3. 新增 Browser 服务画像、导出与包过滤、Token 去重、超时 / 重试 / 挂起 / 断开 / 迟到回调测试，并补充标准时长、秒转毫秒、已是毫秒、未知单位和边界证据回归；本机 `testDebugUnitTest` 共 `311` 项，`0` 失败、`2` 项按既有设计跳过，Debug 编译打包和 lint 通过，项目文档 / Skill / diff 检查通过。
4. 只读复核目标车机 `S56_HQX` 当前安装为 production signer、`versionCode=116`；本轮 Debug 产物为不同 signer、`versionCode=114`。按签名与版本安全边界未执行覆盖安装、降级、卸载或清数据；随后受控运行 `node scripts/install-and-smoke.mjs` 在 `get-state` 发现目标设备为 `offline` 后安全退出，未触碰安装数据。因此 Android 9 本轮仅完成公开会话矩阵复核，未把运行 smoke 写成新代码已通过；当前环境没有可用于 03T Android 11 Browser 兜底的独立实机，需后续提供同签名且更高版本测试包后再验证。
5. 共享 03 APP 管家检查已执行；登记库仍为 production `1.0.1 / versionCode 115` 且记录工作树 clean，而本仓库现有真值为 `1.0.2 / versionCode 116`、工作树 dirty。该登记漂移不由本轮包名或 namespace 改动造成，未擅自改写共享登记库。

## 2026-09-02 测试包与正式包版本升级打包

1. Release 版本真值已由 `1.0.1-icar03 / versionCode 115` 升级为 `1.0.2-icar03 / versionCode 116`；Debug / Staging 继续保持项目固定基线 `1.14-icar03 / versionCode 114`。
2. 以 staging 公开信任配置和仓库外 staging 签名生成测试 APK；以 production 公开信任配置、正式签名生成 Release APK，`minifyReleaseWithR8`、`shrinkReleaseRes` 和 `optimizeReleaseResources` 均成功。
3. 两个 APK 均核对为包名 `com.ninepointnine.desktoplyrics`、单 signer、APK Signature Scheme v2 有效；测试包证书 SHA-256 为登记的 staging 摘要，正式包证书 SHA-256 为登记的 production 摘要。测试 APK SHA-256 为 `22875bcdec85843f76b3050149aa07916ac2ff40f56c19f85877d9784bd1bb4f`，正式 APK SHA-256 为 `0845ed30ec98511f092b948b7b61a8df9d49b4b669face0a9920d4d6037a5999`；正式包调试夹具标记隔离扫描通过。
4. 已在桌面测试包目录替换 `03歌词-staging-v1.14-icar03.apk` 及对应 ZIP，并在正式发布包目录新增 `03歌词-v1.0.2-icar03.apk` 及对应 ZIP；正式目录中的 `v1.0.1` APK / ZIP 保留，测试目录校验清单已追加本轮 03歌词 哈希。两个 ZIP 均为 UTF-8 文件名、根目录单一同名 APK，解压字节与对应 APK 一致；测试 ZIP SHA-256 为 `d6028cbb732c015b0f544d977e99fb89e217560299bd68cdb60e162d8c178501`，正式 ZIP SHA-256 为 `2e16808a80412e7290256dcef6d252547bae13a1530f90d8ed3813199bad9de9`。
5. 本轮只完成构建、签名 / 元数据 / ZIP 校验和桌面产物更新；用户已完成车机主测，因此未重复安装或截图，未清数据、未卸载、未重启、未运行商业 instrumentation。未修改 Cloud 登记库，未提交、未推送或发布。

## 2026-09-02 蓝牙字段能力分流与 Apple Music 回归修正

1. 用户反馈 Apple Music 通过同一 `com.android.bluetooth/A2dpMediaBrowserService` 会话无法识别。目标车机只读 `dumpsys media_session` 显示该会话公开描述为“歌名、歌手、专辑”四字段；车机不会把手机端播放器包名透传给应用，因此不能用 QQ / Apple 包名区分这两种来源。
2. 修正 `MediaSessionMetadataPolicy` 的唯一入口：蓝牙先判定公开字段能力。原始 `ARTIST` 无复合分隔符时按标准独立 `TITLE` / `DISPLAY_TITLE` / `ARTIST` 映射；含连字符且完整 `ALBUM_ARTIST` 佐证时仍按标准字段保留完整艺人；只有复合边界有明确结构证据时才解码“录音名-艺人”，无法确认时保持空歌名，不把动态展示文本冒充身份。`ALBUM` 与 `DURATION` 始终只取各自原始键。
3. 新增 Apple Music 标准投影、标准中文连字符艺人、原始标题优先于派生展示行等回归用例；QQ 复合字段、动态歌词行、艺人内部连字符和非法边界用例继续保留。定向 `MediaSessionMetadataPolicyTest` 与全量 JVM 单测均通过。
4. 按用户授权，已用同一正式签名的 Release 包保留数据覆盖安装到车机（`1.0.1-icar03` / `versionCode=115`）；安装脚本启动设置页、核对两个表面租约、窗口避让无障碍、播放状态监听和进程状态均通过，未发现致命日志。
5. 本轮按用户要求不执行截图或界面操作；剩余人工确认由用户播放 Apple Music、QQ 音乐及其它平台，核对歌名、歌手、专辑和歌词连续稳定。

## 2026-09-02 QQ 蓝牙 AVRCP 元数据串线根因与归一修正

1. 目标车机只读 `dumpsys media_session` 与车机公开媒体卡日志确认：`com.android.bluetooth/A2dpMediaBrowserService` 的四字段投影中，`TITLE` 会随 QQ 音乐逐句歌词变化，`ARTIST` 采用“录音名-艺人”复合值（艺人内部还可能含连字符），`ALBUM` 与时长保持独立稳定。原实现把控制器描述标题直接当歌名、把复合副标题直接当歌手；每次歌词行变化都被 `MediaRecordingStateTracker` 判定为切歌，取消旧查词并刷新设置输入，形成“歌词短暂出现后消失”和字段串线。
2. 已在既有 `MediaSessionMetadataPolicy` 主链增加显式 `MediaSessionTransport` 画像。标准传输保持原有 `MediaDescription` 优先级；蓝牙 AVRCP 先判定独立字段或复合字段能力：独立能力使用原始 `TITLE` / `DISPLAY_TITLE` / `ARTIST`，复合能力才从原始 `ARTIST` 按 Unicode 连字符、非空两侧至少 `2` 个归一字符、多艺人分隔符和艺人内部连字符规则解出独立 `track` / `artist`，展示副标题不再作为第二解析输入；只有稳定字段提示、明确多艺人分隔符或已验证传输形状足以证明边界时才拆分，歧义值保留可确认的原始独立字段，不能确认时不发布动态 `TITLE`，`album` / `durationMs` 单独保留。服务的选源、录音代际、缓存、查词、设置和 WebView 均继续只消费这一份归一状态。
3. 新增回归覆盖目标样本《星梦》《无期》《逝去的爱》《Cold Blooded》《My job (老本行) (Live)》《好男儿志在远方, 投名状 (The Oath)》《The Gentlemen (绅士们) (Live)》、艺人内部连字符、标题逗号、非法短边界、动态歌词行（含恰好等于标题片段的行）以及标准播放器路径。定向 JVM 测试已通过；本轮尝试保留数据覆盖安装时，车机正式包为 `versionCode=115` 且使用正式签名，本轮 debug 包为 `versionCode=114` 且签名不同，Android 以 `INSTALL_FAILED_VERSION_DOWNGRADE` 拒绝安装；未执行降级、卸载或清数据，剩余车机确认需使用同签名且版本高于 `115` 的测试包。
4. 现场连续只读快照（间隔 `3s`）进一步复核同一曲目：`TITLE` 从“`不稀罕enemy`”变为“`Bring the new beat in`”，而 `ARTIST` 始终为“`Trouble Maker (麻烦你了) (Live)-那奇沃夫/Yamy郭颖`”、`ALBUM` 始终为“`说唱巅峰对决2026 第3期`”；这正是展示通道变化而录音身份不变的证据。
5. 归一边界进一步收紧：蓝牙复合能力不读取 `TITLE`、`DISPLAY_TITLE`、`MediaDescription` 副标题或 `AUTHOR` 作为录音身份提示；独立能力仍按原始标题 / 歌手字段映射。复合能力仅以原始 `ARTIST` 的已验证形状、独立 `ALBUM_ARTIST` 佐证、明确多艺人分隔符或非拉丁目录形状解码。现场 Release 包以正式签名和 `versionCode=115` 保留数据覆盖安装成功；应用重启后连续约 `40s` 的 QQ AVRCP 歌词/制作信息回调没有再次推进录音代际，查找页继续保持同一歌曲三字段，未见致命日志。剩余主测仍是用户在 QQ 音乐切换歌曲时观察至少 `30s` 的字段稳定性。

## 2026-09-01 测试包与正式包重新打包

1. 在当前客户端提交前完成 `testDebugUnitTest lintDebug assembleDebug`，JVM `274` 项通过、`2` 项按设计跳过，Lint 无错误；随后以 staging 信任配置生成测试 APK（`1.14-icar03` / versionCode `114`），以 production 信任配置和正式签名生成 Release APK（`1.0.1-icar03` / versionCode `115`），Release 的 R8、资源收缩和资源优化任务均成功。
2. 两个 APK 均核对为包名 `com.ninepointnine.desktoplyrics`、单 signer、APK Signature Scheme v2 有效；测试包证书摘要为登记的 staging 摘要，正式包证书摘要为登记的 production 摘要。Release 未包含 fixture / 调试入口标记。
3. 已重新生成各自只含同名 APK 的 ZIP，并覆盖桌面现有 `03歌词-staging-v1.14-icar03.apk`、`03歌词-staging-v1.14-icar03.zip`、`03歌词-v1.0.1-icar03.apk` 和 `03歌词-v1.0.1-icar03.zip`；四个文件均回读校验，ZIP 内单一条目与对应 APK 字节一致。
4. 本轮未修改 `cloud`；未安装 Release 到车机、未清数据、未卸载、未重启、未运行商业 instrumentation。提交和推送仅包含本客户端仓库。

## 2026-09-01 试用权益时间边界验证

1. 采用 Debug fixture 注入可控 `nowEpochMs`，无需等待真实时间：首次领取后推进到 `24h + 1ms`，客户端通过 `license/check` / `trial/start` 取得新的 24 小时租约，同时保留原始七天 `trialEndsAt`；推进到七天截止后返回过期并关闭本地门禁。
2. 定向试用网关测试 `22` 项、许可证安全边界 `2` 项和设置行为边界 `1` 项均通过，另有完整 Debug JVM `274` 项、`0` 失败、`2` 项按设计跳过；未调用 `license/refresh`。
3. 实时发现车机 `S56_HQX` 后只读启动设置页，当前 staging 包显示“权益已撤销，当前无法继续使用”，没有 `LyricsOverlayService` 或歌词悬浮窗口。该设备的试用已消费且当前权益已撤销，不能在不清数据、不卸载和不改 Cloud 的前提下再次领取新试用。
4. 本轮未修改 `cloud`，未改系统时间、未清数据、未卸载、未重启车机、未运行商业 instrumentation、未提交、未推送或发布。

## 2026-09-01 真实车机购买与退款闭环

1. 通过实时 ADB 设备发现完成 staging Debug 安装与授权；安装包为 `com.ninepointnine.desktoplyrics 1.14-icar03`，悬浮窗、通知监听和窗口避让无障碍均已启用，既有无障碍组件保持不变。
2. 真实支付后，设置页显示 `Pro / 权益生效中 · 永久`，歌词服务和 Overlay 恢复；`node scripts/install-and-smoke.mjs` 通过设置页、两个表面租约、服务绑定和致命日志检查。
3. 后台退款后执行真实的“返回退出设置页 → 重新打开设置页”生命周期，在线复核返回撤权；设置页显示“权益已撤销，当前无法继续使用”，撤权通知显示“Pro 权益已撤销”。
4. 撤权后定向运行态断言全部通过：`LyricsOverlayService` 和本应用 `APPLICATION_OVERLAY` 消失，`MediaListenerService` 与窗口避让无障碍仍保持有效绑定；`no_backup` 中仅保留设备密钥版本和观测时钟，许可证、device token、撤销/待复核及订单轮询记录均已清理。
5. 本轮未清数据、未卸载、未重启车机、未运行商业 instrumentation；未修改 `cloud`，未提交、未推送、未发布。后续测试可在此撤权基线继续。

## 2026-09-01 永久 PRO 客户端与生命周期在线复核

1. 本轮仅修改 `03lyrics` 客户端，未修改 `cloud`。客户端已按云端公开契约接入 `license/check`，启动、歌词服务生命周期、设置页打开和用户重启服务均通过只读在线复核，不再调用 `license/refresh`。
2. 永久 PRO 许可证要求 `validity=permanent`，`expiresAt`、`offlineGraceUntil`、`trialEndsAt` 均为 `null`；`active` 复核保持本地许可证原始 bytes、`licenseId` 和时间不变，不生成新签发记录。
3. 退款撤权收到 `revoked` 后清除本地许可证、device token、待复核和支付状态，并使运行服务 fail closed、释放歌词资源；网络失败只保留有效本地凭证并记录待复核状态。
4. 七天试用保留固定截止时间；单张试用许可证最长 24 小时，租约到期时使用当前设备密钥重新取得许可证，不轮换密钥。云端 `not_started` 时不静默沿用本地权益。
5. 自动化验证：`testDebugUnitTest lintDebug assembleDebug` 全部通过，JVM 共 `274` 项、`0` 失败、`2` 项按设计跳过；项目文档检查、Skill 检查和 `git diff --check` 通过，03 APP 登记检查仅因登记快照为 clean 而当前工作树有本轮未提交改动报告不一致。
6. 已按项目默认授权尝试保留数据 debug 覆盖安装和最简 smoke；目标车机 `192.168.0.203:5555` 当前不在 ADB 设备列表，脚本在安装前安全退出，未改动车机。仍不清数据、不卸载、不重启车机、不运行商业 instrumentation、不提交、不推送、不发布。

## 2026-08-24 Release 版本规则

1. Release 版本真值固定在根目录 `release-version.properties`，当前 `versionName=1.0.1-icar03`；Debug / Staging 继续使用原有 `1.14-icar03` 基线。
2. 未指定版本时由 `scripts/bump-release-version.mjs` 递增 patch 并同步递增 `versionCode`；明确指定版本时使用传入值。构建过程不会自动改写版本文件。

## 2026-08-21 全媒体 MediaSession 归一、选源与设置状态刷新

1. 用户实测发现：切到爱趣听后顶栏没有歌词，设置页“歌词查找”的歌名输入框会出现歌词正文或“制作人”等动态文本。只读取证确认爱趣听已经公开标准 MediaSession，控制器描述能给出真实歌名和歌手；根因是旧实现直接优先使用播放器会动态改写的原始 `TITLE`，并把该文本同时当成设置字段、查词参数和切歌身份，造成连续取消 / 重查与错误展示。另一个切换窗口来自车机活动会话变化通知不稳定，设置命令曾直接读取服务内存中的旧控制器。
2. 新增 `MediaSessionMetadataPolicy` 与 `MediaRecordingStateTracker`：控制器标准描述优先，原始标题 / 歌手 / 专辑只作字段缺失兜底；当前会话 Token 与归一字段共同生成稳定录音代际。动态原始标题不再推进切歌，字段补齐或已知时长相对上次查询累计变化超过 `2000ms` 只生成查询修订。设置页、缓存、人工查找、自动查词、播放时间线和 WebView 共用这一份规范化状态；WebView 只回传录音代际、查询修订和请求号，不再回传媒体文本。
3. `LyricsOverlayService` 以公开 `MediaSessionManager` / `MediaController` 为唯一播放入口，全部候选按媒体语义、播放状态和系统会话顺序选择，并为每个候选注册元数据、播放状态、音频信息和销毁回调。`AudioManager.AudioPlaybackCallback` 只作重读唤醒；候选变化以 `80ms` 合并，来源变化后以 `250ms / 1000ms` 两次有界复核收敛，另保留 `5s` 兜底。设置打开、状态请求、人工搜索 / 点选、恢复自动和清理缓存均先同步重读活动会话；停止或消失后不回接无关暂停会话。
4. 标准媒体会话进度统一由 `MediaSessionTimelineTracker` 处理零值、未来时间戳、未知位置、暂停冻结、seek 和时长封顶；蓝牙保留 AVRCP 专用补偿。WebView 在首次 `timelineReady=false` 时只保留已解析歌词，不猜测第一行，收到可信位置后才开始同步呈现。旧歌词返回必须同时通过运行代际、录音代际、查询修订和请求号校验，不能覆盖新录音。
5. 自动验证已通过 `MediaSessionMetadataPolicyTest`、`MediaSessionSelectionPolicyTest`、`MediaSessionTimelineTrackerTest`、`LyricsSettingsModelsTest`、`SettingsBehaviorTest` 和全量 `testDebugUnitTest`；默认 Debug 与同签名 staging Debug 均完成 `assembleDebug`。staging APK 为单 signer、APK v2，证书摘要与目标车机既有安装一致。默认 Debug 首次覆盖被 Android 正确拒绝，随后没有卸载或清数据，改用既有 staging 构建入口保留数据覆盖安装 `1.14-icar03`（versionCode `114`），基础安装 smoke 全部通过。
6. 爱趣听专项 smoke 与用户主测均通过：无需重启应用即可从蓝牙切入爱趣听，服务选中公开 `com.tencent.wecarflow/MusicService` 会话，顶栏按进度显示同步歌词；设置页三个输入框分别显示当前歌名、歌手和专辑，不再显示歌词正文或制作信息。当前曲目持续播放的 `32s` 日志窗口内没有来源重选、录音代际变化、重复查词或致命异常。用户确认“切换后表现正常，设置页面表现也正常”。
7. 只读会话矩阵已观察到蓝牙 `A2dpMediaBrowserService`、本机音乐 `HddPlayerService`、U 盘 `UsbPlayerService`、爱趣听 `MusicService` 及其它公开媒体会话；本轮受当前测试条件限制，U 盘和本机音乐仍只有会话发现证据，没有完成播放级歌词验收，不得表述为已通过。
8. 用户主测通过后明确授权提交本组全媒体接入与设置修复；提交范围包含上述实现、回归测试和同步文档，未授权推送或发布。

## 2026-08-20 包名迁移与签名身份准备

1. 已将 Android 应用身份从 `com.tcrrry.desktoplyrics` 迁移为 `com.ninepointnine.desktoplyrics`，同步更新源码命名空间、Manifest 组件入口、跨应用 Action、自定义控件、测试包和本机安装脚本；旧包名仅保留在历史记录中用于追溯。
2. 开发阶段继续复用现有 staging keystore，证书 SHA-256 为 `1eb136fffd3f1e4c204d0933cab66c51ee4536a29e949b9c080925c01563b51d`。
3. 已在仓库外生成独立 production RSA-4096 签名身份，证书 SHA-256 为 `934b9151fe62b39a3474a11f00c2114c7f392b18fec85f39f8d71b9596860e03`；私钥、keystore 和口令未进入仓库。
4. 云端在接收新包前必须把 `03lyrics` 的 `expectedPackageName` 更新为 `com.ninepointnine.desktoplyrics`，并分别注入 staging / production 的证书摘要。由于当前尚无用户使用旧身份，本次无需迁移已有许可证；以后若旧身份产生购买，必须由云端按设备恢复规则重新签发新包许可证，不能把旧包名直接视为同一应用。
5. 本轮不执行 Cloud 部署、数据库迁移、真实支付或 production 发布；客户端 staging 构建与签名核对已完成，production APK 等待 Cloud production trust bundle 后再构建。
6. 已将 staging Debug APK（`com.ninepointnine.desktoplyrics`，`1.14-icar03` / `114`）保留数据安装到车机并启动设置页；新包的窗口避让无障碍已绑定，但最简 smoke 在播放状态通知监听处停止。车机当前仍只授权旧包 `com.tcrrry.desktoplyrics` 的通知监听，包名迁移不会自动继承该系统授权；未卸载旧包、清数据或重启车机。

## 2026-08-19 统一权益快照与试用最终边界修正

1. 通过 Debug 诊断主链读取车机加密许可证的签名时间：`trialEndsAt = 2026-08-23 08:06:26`（车机本地时间）；在 `2026-08-19 06:06:04` 读取时剩余 `352821554ms`，即约 `4天2小时0分21秒`。诊断只输出层级和时间边界，不输出许可证正文、设备指纹或密钥。
2. 根因不是本地试用记录缺失，而是客户端验签把 TRIAL 也按 `offlineGraceUntil` 判定最终过期；车机签名许可证的 `offlineGraceUntil = 2026-08-19 00:13:15` 早于 `trialEndsAt`，因此原逻辑在试用仍有四天时错误进入“试用已到期 / 无法确认”分支。
3. 已完成：许可证最终边界按权益层级选择，TRIAL 只接受签名 `trialEndsAt`，PRO 继续接受签名 `offlineGraceUntil`；`expiresAt` 仍只负责自动续签起点。新增 `CommercialEntitlementCoordinator`，设置页和歌词服务共用同一门禁、单飞刷新和权益快照，监听器异常不会把成功查询改写成未知；共享快照清除待支付会话时，设置页同步取消旧订单轮询，避免已完成或已失效订单继续请求。
4. 已新增回归：试用在 Pro 离线宽限字段早于试用截止时仍保持有效；网络失败保留本地试用快照；共享快照诊断准确给出剩余时间；纯文本 `HTTP 530` 仍按瞬时网络失败处理；协调器取消请求不会被吞成未知失败。完整 JVM `228` 项（`226` 通过、`2` 项显式网络用例按设计跳过）、staging 构建、保留数据覆盖安装和最简 smoke 已重新执行。
5. 最新 staging `1.14-icar03`（versionCode `114`）覆盖安装后，车机设置页、表面租约、窗口避让无障碍、通知监听和歌词服务均正常，未发现应用致命日志；商业 API 仍返回 Cloudflare `530 / 1033`，但本地签名试用正常放行，smoke 已通过。本轮未截图、未代点、未清数据、未卸载或重启车机。

## 2026-08-19 权益状态文案居中与测试环境可用性排查

1. 已确认自动化安装脚本没有注入权益失效、恢复失败或 Debug 权益场景；脚本只执行保留数据覆盖安装、启动和商业门禁检查。Debug 场景入口只接受显式 Activity extra，默认构建夹具为试用态。
2. 对当前 staging 商业 API 做只读连通性检查：`https://api-staging.9studio.fun` 及其健康 / 活动路径均返回 Cloudflare `HTTP 530`（`error code: 1033`，源站不可用），因此车机的“当前无法确认权益”与“重新确认”失败是测试环境服务端不可用造成的，不是自动化主动把权益改成失效；待用户手测回报后再确认是否还叠加本地试用到期状态。
3. 权益主页状态组已改为条件布局：无报价、确认中、无法确认、过期无报价和恢复反馈时，位于页面顶部留白与底部动作组之间的可用区域中心，并与按钮保持间距；有可购买报价时保留顶部状态位并让出中部营销组。恢复反馈移入该状态组，底部只保留动作入口；“正在确认权益 / 请稍后重试”也统一为“确认权益中 / 请稍后再试”；PRO 独立完成态与订单 / 二维码页面结构不变。
4. 客户端适配层已把纯文本上游 `5xx`（包括 Cloudflare `530/1033`）归类为瞬时网络失败，保留已有本地权益并允许用户重试；新增回归测试覆盖该响应形态。服务端源站仍需恢复，客户端不伪造权益成功。
5. 已同步产品基线、车机 UI 规范、验证矩阵和设计规则；新增布局契约测试覆盖状态反馈归属与顶部 / 中部切换。全量 JVM `222` 项中 `220` 项通过、`2` 项显式网络用例按设计跳过、`0` 失败；`lintDebug`、fixture Debug `assembleDebug`、staging 配置 `assembleDebug`、项目文档检查、Skill 检查和 `git diff --check` 均通过。随后使用仓库外既有 staging 签名完成保留数据覆盖安装 `1.14-icar03`（versionCode `114`），设置页进程、前台页面、表面租约、窗口避让无障碍和通知监听均可建立，未发现应用致命日志；标准 smoke 在商业门禁处停止，因为 staging API 仍返回 Cloudflare `530 / 1033`，未绕过门禁伪造权益成功。按用户要求未截图、未代点权益页面，已把前台设置页交回用户主测。
6. 根据用户对视觉位置的补充，将无报价状态组从整页中心改为“页面顶部留白到动作组顶部”的可用区域中心，并增加与底部操作组的 `24dp` 间距；报价营销态和 PRO 完成态保持原有几何。最新 staging 签名 APK 已再次保留数据覆盖安装，编译版本仍为 `1.14-icar03`（versionCode `114`）；安装、启动、租约和绑定检查完成，标准 smoke 仍因 staging 商业门禁未开放而停止，未绕过、未截图、未代点权益按钮。

## 2026-08-19 关于页与构建期用户协议二维码

1. 已完成：设置页左侧新增底部“关于”入口，仍在同一 Activity 内切换；右侧协议卡只显示“用户协议”标题和二维码，不显示明文网址或额外说明。
2. 二维码由 `TermsQrCodeGenerator` 在客户端离线生成，地址由构建配置注入：Debug 默认使用 `https://staging.9studio.fun/icar03/terms`，可通过 `userAgreementEnvironment=production` 构建正式地址；Release 固定使用 `https://9.9studio.fun/icar03/terms`。环境判断不读取版本号，不依赖运行时下发。
3. 自动验证：二维码矩阵可被 ZXing 重新解码为完整测试地址，正式地址与测试地址生成的矩阵不同；全量 JVM `220` 项中 `218` 项通过、`2` 项显式网络用例按设计跳过、`0` 失败；`lintDebug`、`assembleDebug`、项目文档检查、Skill 检查和 `git diff --check` 均通过。
4. 车机验证：使用仓库外既有 staging 签名构建并完成保留数据覆盖安装，包版本仍为 `1.14-icar03`（versionCode `114`），设置页位于标准 `[660,90]..[1890,900]` 窗口；实机 UI 树和截图确认“关于”固定在左栏底部，协议卡只显示标题与二维码，截图二维码重新解码为测试地址。标准安装 smoke 因车机既有商业权益处于恢复态而按安全门禁停止，未绕过权益、未清数据、未卸载、未重启车机、未提交、未推送或发布。
5. 剩余最小主测：用户用手机扫描车机“关于”页二维码，确认扫码内容为 `staging.9studio.fun/icar03/terms`；正式包发布前再用 production 构建确认扫码域名为 `9.9studio.fun/icar03/terms`。

## 2026-08-18 设置授权提示改为窗口内普通弹窗

1. 已完成：服务状态页的“悬浮歌词服务”开关和“重启悬浮歌词”在授权不足时，改由设置 Activity 根层内的普通信息弹窗呈现；授权判断、开关回退、服务启动和重启动作保持不变，不再调用平台 Toast 或系统 Dialog。
2. 弹窗复用 `03投屏` 已验证的车机内嵌结构：全窗遮罩、`860dp` 居中内容面、`48dp` 内边距和 `95dp` 大触控按钮；浅色 / 深色背景和正文跟随设置主题，按钮跟随当前强调色，不使用危险操作红色语义。系统返回和“知道了”均只关闭弹窗。
3. 自动验证：结构资源解析与 Kotlin 编译、`testDebugUnitTest lintDebug assembleDebug`、项目文档检查、项目 Skill 检查和 `git diff --check` 均通过；JVM 共 `218` 项，其中 `216` 项通过、`2` 项显式网络用例按设计跳过，`0` 失败。
4. 车机验证：以仓库外既有 staging 签名重建并保留数据覆盖安装 `1.14-icar03`（versionCode `114`）。标准安装 smoke 两次通过，设置页、两个表面租约、窗口避让无障碍、通知监听和歌词服务正常，未发现应用致命日志。为触发本次路径临时将悬浮窗 AppOp 设为拒绝，实机确认弹窗资源边界 `[660,90]..[1890,900]`、标题 / 正文 / 按钮可见；按钮和系统返回关闭均通过，随后恢复 AppOp 为允许并再次 smoke 通过。未清数据、未卸载、未重启车机、未提交、未推送或发布。
5. 剩余最小主测：在目标车机授权不足的状态下，分别点击“悬浮歌词服务”开关和“重启悬浮歌词”，确认弹窗文字易读、底层控件不可触发、浅色主题观感与深色截图一致；恢复授权后确认服务开关可正常开启。

## 2026-08-18 全屏独占租约与 03投屏避让

1. 在已有“桌面区域占用” bound Service 租约之上新增版本 `1` 的“全屏独占”租约；两类占用保持独立状态，不传递媒体、任务或窗口控制指令。
2. `IcarLyricsPresentationPolicy` 仍是歌词呈现的唯一 owner：普通桌面占用只使歌词退到顶栏，全屏独占期间则整体隐藏；租约释放后依据最新车机窗口、Dock 和用户偏好重新计算，不执行反向“恢复”动作。
3. 全屏独占 Action 与协议元数据已从 Manifest 导出；合作应用按 Action 和版本发现 provider，不写死包名，不扫描前台应用，不扩大无障碍窗口监听范围。
4. 相关租约状态与呈现策略 JVM 测试、全量 `testDebugUnitTest`、`lintDebug assembleDebug`、项目文档 / Skill / Git 文本检查均通过。实际覆盖安装前拦截了默认 Debug 证书不一致；随后使用仓库外既有 staging 身份重建，证书与车机已安装包一致，保留数据覆盖安装和基础 smoke 通过。
5. 车机实测中，系统可发现 `FullDisplayOccupancyLeaseService`；`03投屏` 进入全屏后出现来自其进程的真实绑定记录，歌词 `APPLICATION_OVERLAY` 为不可见；退出全屏后绑定记录消失，歌词按当前窗口状态恢复为可见顶栏。未清数据、未卸载、未重启车机、未提交、未推送或发布。

## 2026-08-18 歌词阴影与开机自启动默认值

1. 按用户要求，壁纸歌词“歌词阴影”和“开机自启动”的未保存偏好默认值均改为开启。新增 `LyricsOverlayService.AUTO_START_DEFAULT` 作为唯一默认 owner，设置页、`BootReceiver` 和运行态重启统一读取；用户已保存的关闭值以及 `ACTION_STOP` 写入的关闭意图不会被覆盖。
2. `WALLPAPER_SHADOW_DEFAULT` 已改为 `true`，未保存阴影偏好时设置页和运行态均开启；已保存的关闭值仍按用户选择保留。
3. 自动验证通过：`testDebugUnitTest assembleDebug`、`lintDebug`、`node scripts/check-project-docs.mjs`、`node scripts/check-skills.mjs` 和 `git diff --check` 均通过。
4. 同签名 staging Debug APK 已重新构建并通过 signer 一致性检查；保留数据覆盖安装 `1.14-icar03`（versionCode `114`）和基础 smoke 通过。车机设置页位于前台、表面占用租约已发现、窗口避让无障碍与播放状态监听已绑定、歌词服务运行中，未发现应用致命日志。
5. 覆盖安装后只读复核 `wallpaper_shadow_enabled_v1=true` 与 `auto_start=true`。本轮未清数据、未卸载、未重启车机、未提交、未推送或发布；真实车机重启闭环留待用户在方便时进行最后确认。

## 2026-08-18 轻量抗重打包与低频权益续签

1. 用户明确不购买商业加固，并要求车机不增加常驻检测或高频商业网络负担。本轮采用构建期 R8、一次性 APK signer 门禁和签名许可证时间窗，不引入加固壳、原生反调试、Root / Hook 轮询、APK 全量哈希、Play Integrity 依赖或新增后台任务。
2. Release 已固定启用 R8 优化、混淆和资源收缩，并显式关闭调试与 JNI 调试；应用整体关闭 Android 备份。实际 `assembleRelease` 已执行 `minifyReleaseWithR8 / shrinkReleaseRes / optimizeReleaseResources` 并生成内部 mapping，未对签名后的 APK 做二次处理。当前无 production 签名配置的验证产物为未签名 Release，只用于本机结构验证，不安装车机或对外发布。
3. 新增 APK signer 单一 owner，设备指纹与非 fixture 商业装配复用同一读取结果。staging / production 必须从构建入口注入预期 signer SHA-256，运行包 signer 不匹配或商业配置缺失时 fail closed；Debug fixture 保持本地测试边界，不冒充正式包完整性。
4. 自动权益复核改为读取签名许可证时间窗：`expiresAt` 前只返回本地验签权益，本地缺失或到点后才进入既有进程级单飞请求；连续运行的歌词服务复用主线程 Handler 安排一个续签点回调，不创建轮询、新线程或 WorkManager。瞬时失败且本地仍有效时，以 AES-GCM 记录下一次自动尝试不得早于 `24h`，运行守卫按新边界只重排一次且不改变 `offlineGraceUntil`。用户主动重试使用强制复核入口，绕过冷却但继续共享已开始的云端请求；退款撤销、原试用原子恢复和运行时最终门禁保持不变。
5. Debug HTTP / JSON fixture 现模拟试用许可证一次覆盖原七天截止、Pro 第 `7` 天进入续签且签发后第 `90` 天为最终离线终点；production 仍只接受服务端签名时间。新增纯规则与协议用例覆盖 signer 格式 / 匹配 / 重签不匹配、续签点前零权益请求、到点请求、失败冷却、本地缺失不受冷却、冷却结束后的权威撤销，以及 Release R8 配置不回退。
6. 本机完整验证通过：Debug JVM 共 `216` 项、`0` 失败，`lintDebug`、`assembleDebug`、`assembleRelease` 全部成功；新增用例证明连续服务的同一续签回调只触发一次。Debug APK 为约 `5.9 MiB`，R8 后未签名 Release APK 为约 `2.1 MiB`，mapping 已生成并确认正式包身份检查仍存在于混淆产物。未运行商业 instrumentation，因为本轮没有修改其隔离设施且 JVM / 构建已经覆盖直接规则。
7. 用户指出仓库外测试环境目录后，已定位 staging 公开信任配置、长期 signing properties 及其 keystore，并把前两个稳定文件指针补入被 Git 忽略的本机上下文；可提交示例与运维规则只保留占位符和键名。强制关闭 Kotlin 增量并重跑 `assembleDebug` 成功，实际 APK 为 staging 配置、单 signer、APK v2 有效，证书同时匹配构建注入摘要、仓库外 keystore 和车机原安装包；签名口令、keystore 路径/文件名及私钥载荷标记扫描无命中，没有应用源码晚于产物。
8. 当前 staging Debug APK SHA-256 为 `4963ba7b92cc8e96ccf3da8b03652a13512236e5eb9a912e79fb2fa4e5e3f0fe`。标准脚本已保留数据覆盖安装 `1.14-icar03`（versionCode `114`），并通过设置页启动、版本/进程、表面占用租约、窗口避让无障碍实际绑定、通知监听、致命日志和歌词服务恢复检查；从车机只读拉取的已安装 APK 与工作区产物哈希一致。
9. 安装后专项 smoke 保持同一 PID 观察 `20s`：始终只有 `1` 个 `LyricsOverlayService` 和 `1` 个有 Surface 的 `APPLICATION_OVERLAY`，商业恢复通知、商业拒绝日志、续签点回调、重复刷新完成和致命日志均为 `0`；临时启用的应用专属 INFO 日志已恢复系统默认。未安装 Release、未运行商业 instrumentation、未清数据、未卸载、未重启车机、未提交、未推送或发布。

## 2026-08-17 权益唯一门禁、运行期访问守卫与退款试用原子恢复

1. 用户观察到“权益已撤销”后未点击重新确认、关闭设置页却重新出现歌词，要求确认权益是否为歌词显影唯一真值。代码审计确认设置页实例会自动查询权益，关闭动作本身不授权；所有歌词资源只由 `LyricsOverlayService` 创建且每个服务命令都先经过 `CommercialAccessGate`。同时发现两个需要补齐的严格边界：已有运行资源在本地门禁变为 denied 且云端复核仍进行时不能继续等待，以及服务连续运行跨过签名许可证最终有效时间时不能等下一次命令才退出。
2. 服务现以轻量 `CommercialRuntimeAccessGuard` 保存统一门禁已经返回的单个 `Allowed?`：新许可证替换唯一到期回调，用户停止、系统授权恢复态、商业恢复态和服务销毁都先清除运行许可；到点、屏幕重新点亮、系统时间变化、Overlay 创建、表面更新和播放快照下发均受同一内存边界保护，到期重新读取统一门禁。撤销、过期、时钟、存储或其它 denied 结果沿既有 `COMMERCIAL_RECOVERY` 同步销毁歌词仓库、协调器、缓存、协程、MediaSession、WebView、Overlay 和监听；守卫不生成权益、不延长有效期，也没有新增高频联网轮询或第二套 UI 权益状态。
3. 第一次修正版覆盖安装真实复现了更深层根因：Cloud 对退款设备的许可证刷新会返回 `entitlement_revoked`，旧客户端先停用，下一次独立查询才通过 `startTrial` 接回原试用，导致服务与设置页请求先后不同时时而撤销、时而恢复。网关现将该过程收敛到同一次核心查询：先写加密撤销标记使旧 Pro fail closed，再立即请求原试用；只有新签名试用完成验签、许可证与可信时间写入并成功删除撤销标记后才返回 `Ready(Trial)`。原试用过期返回 `Expired`；网络、验签或持久化失败继续返回原撤销结果，不能回退旧 Pro。
4. 自动化已补充“已有资源遇到 denied 不等待”“有限/已到期/无终点运行许可”“退款后旧许可证删除失败仍在同一查询恢复原截止试用”“连续复核不在撤销与试用间交替”“新试用未完整持久化继续撤销”和“原截止后保持过期”。当前完整 Debug JVM 共 `209` 项、`0` 失败、`2` 项显式公网测试按设计跳过；`lintDebug`、Debug 与 androidTest APK 打包通过，之前的 Release 隔离编译结果继续有效。
5. 最终 staging 应用与 androidTest APK 已关闭 Kotlin 增量后强制重建；公开 staging 信任配置落包，应用/测试 APK 均为单 signer、APK v2 有效，并与仓库外 staging keystore 和车机现有应用证书一致，签名口令与服务端秘密扫描无命中。没有应用源码晚于最终 APK。
6. 车机先后执行两次保留数据覆盖安装，均由标准脚本完整通过设置页、版本/进程、表面占用租约、窗口避让无障碍实际绑定、通知监听实际绑定、致命日志与歌词服务恢复检查；第三次独立冷启动日志继续为 `localAllowed=true -> result=ready / localAllowed=true -> outcome=running`。最终只有 `1` 个 `LyricsOverlayService`、`1` 个 `APPLICATION_OVERLAY`、`1` 个 `MediaListenerService` 和 `1` 个 `IcarDockAccessibilityService`，商业恢复通知不存在，临时日志标签已恢复系统默认。
7. 用户授权清理车机残留 androidTest 辅助包。只读确认 `com.tcrrry.desktoplyrics.test` 使用独立 UID、仅以 instrumentation 指向正式包且不共享应用数据后，旧证书辅助包已卸载。首次临时安装本轮测试 APK 后，`CommercialSecurityInstrumentationTest` 为 `2` 项通过、`1` 项失败；失败根因不是权益门禁回归，而是该 fixture 用例复用了 staging 运行时单例和正式安全存储，前置重置误删了本地许可证与 device token。测试包随即卸载，Cloud 恢复购买重新写回原试用签名许可证，撤销标记被清除，连续正式包覆盖安装与冷启动 smoke 均恢复唯一歌词服务和 Overlay。
8. 实机商业测试已改为显式注入独立 AES 存储前缀、独立设备 EC 密钥和独立 fixture 签名密钥，不再读取构建环境或复用正式运行时单例；每条用例结束删除全部测试记录、AES 密钥、设备密钥及恢复派生密钥、fixture 签名密钥。商业运行时装配仍只有一条主链，staging / production 默认入口继续创建正式存储与设备身份，测试只通过内部注入入口替换基础设施。
9. 关闭 Kotlin 增量后的 staging 全量重建通过 `209` 项 JVM 单测、`lintDebug`、Debug / androidTest 打包和 Release Kotlin 隔离编译；应用、车机已安装包和测试 APK 均为同一单 signer 且 APK v2 有效。隔离后的 `CommercialSecurityInstrumentationTest` 为 `4/4` 通过，正式权益加密记录测试前后 SHA-256 清单逐项一致，测试前缀记录为空，辅助包最终已卸载且 instrumentation 注册为空。
10. 随后复核发现旧安装 smoke 的绑定解析会把 WebView `ServiceRecord` 中的 `requested=true received=true hasBound=true` 与后续目标 `DEAD ConnectionRecord` 混为一段，从而把窗口避让无障碍误报为已绑定。当前脚本已改为只解析目标组件自己的 `ServiceRecord`；商业事务测试也已删除显式 `am force-stop`，改为测试前确认真实绑定、只暂停目标组件并等待记录完全消失，正式权益记录则以连续两次哈希一致作为稳定快照。
11. 当前磁盘版本已重新完成 `73` 个 Gradle 任务，包含 `209` 项 JVM、`lintDebug`、staging Debug 与 androidTest 打包，全部成功。严格安装 smoke 已保留数据覆盖安装当前 APK，并正确拦截车机遗留状态：授权名单仍完整保留 `MBMonitor / 03桌面全局返回 / 03歌词窗口避让`，通知监听与歌词服务正常，但系统只剩窗口避让服务的历史 `DEAD ConnectionRecord`，没有真实绑定记录。该状态来自此前 instrumentation 强制结束目标应用，Android 9 无障碍管理器在本次会话内无法通过重写授权或覆盖安装清除；未清数据、卸载、重启车机、提交、推送或发布。
12. 用户随后自行强制重启车机；只读复核确认窗口避让无障碍恢复自己的有效 `ServiceRecord`，包含 `requested=true received=true hasBound=true`，目标 `DEAD ConnectionRecord` 已消失。通知监听和歌词前台服务正常，当前只有一个 `1230 x 810px` 歌词 Overlay；复核未重新安装、未运行商业 instrumentation、未修改授权或其它车机状态。
13. 根据用户要求，商业 instrumentation 已从普通开发收尾中移除：总规则、验证矩阵、安全边界、运维说明和 `task-closeout` 均明确默认只做相关 JVM / 构建 / 正式应用最简 smoke。事务脚本改为只读取独立 `COMMERCIAL_TEST_ADB_SERIAL`，配置缺失或误指向日常车机时在任何 ADB 操作前退出；用户车机例外同时需要当次明确授权、`persistent-user-vehicle` 角色和 `--user-approved-persistent-vehicle` 参数。当前本机未配置独立测试设备，脚本拒绝路径已在任何 ADB 调用前通过；项目文档、Skill、脚本语法和 Git 文本检查均通过。本轮未运行 Gradle、未安装 APK、未连接或改动车机。

## 2026-08-17 原车右侧 Dock 裁剪与空调页隐藏

1. 用户确认三类窗口必须保持不同结果：空调页展开、过渡或状态未知时隐藏顶栏和壁纸全部歌词；标准浮窗与跨应用占用租约继续把壁纸歌词切到顶栏；只有“壁纸歌词 + 原车最右侧 Dock 展开”按其真实上沿裁剪窗口下边界，左侧和中央 Dock 不处理。任何恢复都按当前全部状态重新计算，不机械恢复进入前的模式。
2. 已新增纯策略与只读适配器：`IcarWindowAvoidancePolicy` 集中分类右侧 Dock 并生成唯一歌词 presentation；`IcarDockAccessibilityService` 只接收 `com.mengbo.launcher3` 的窗口/结构事件，只读取交互窗口边界与根节点包名，使用 `80ms` 合并和 `320ms` 动画终态复核，不读取文字、不执行节点动作或手势。`IcarDisplayStateMonitor` 继续独立只读监听公开 Settings，并新增空调页占用状态；无障碍不可用时壁纸态保守回顶栏。
3. `LyricsOverlayService` 已成为唯一应用者：空调隐藏优先于表面切换，右侧 Dock 只向桌面几何提供下边界；设计空间安全间距固定为 `16px`。桌面 WebView 始终保持原 `810px` 完整布局高度，由外层 Overlay 裁剪，避免 Dock 展开后歌词重新排版；快速表面往返期间只要当前表面仍是最新目标，也会立即提交最新几何，不保留上一帧边界。
4. 部署脚本已把窗口避让授权并入综合命令：覆盖安装后读取完整 `enabled_accessibility_services`，幂等追加本组件、原样重写列表以刷新系统绑定，再逐项确认旧组件未丢失和新服务已实际绑定；不使用 `pm grant`。系统授权页使用独立“歌词窗口避让”名称，并明确说明只观察窗口边界、不读文字、不点击、不模拟手势。
5. 自动验证通过：`IcarWindowAvoidancePolicyTest` 与 `IcarDisplayStateMonitorTest` 定向测试、全量 `testDebugUnitTest`、`lintDebug`、关闭 Kotlin 增量后的同签名 staging `assembleDebug assembleDebugAndroidTest --rerun-tasks`、安装脚本语法、项目文档、项目 Skills 和 Git 文本检查均通过。应用包、androidTest 包、车机原安装包和仓库外 staging keystore 证书一致，均为单 signer、APK v2 有效；公开信任参数已落包，签名口令、私钥和 keystore 扫描无命中。
6. 同签名 staging APK 已保留数据覆盖安装，安装时原有 `MBMonitor` 与 `03桌面返回键测试` 两个无障碍组件均保留，本组件作为第三项追加并由系统绑定；`dumpsys accessibility` 只显示窗口内容读取能力和三类窗口事件，没有手势能力。表面占用租约可发现，设置页、包版本与进程正常。
7. 原车信号 smoke 已通过：右侧 Dock 上滑增加 `[1275,586]..[1920,1080]` 窗口并发布 `EXPANDED top=586`，下滑移除该窗口并发布 `COLLAPSED`；左侧 `[0,586]..[645,1080]` 与中央 `[645,586]..[1275,1080]` 展开期间右侧状态不变。空调页慢速上拉实采状态序列为 `2 -> 3 -> 1`，下拉完全收回恢复为 `2`，与策略测试中的隐藏优先级一致；测试结束后三个 Dock 与空调页均已收起。
8. 商业门禁随后经完整权威查询恢复为 `result=ready / localAllowed=true`，未绕过安全链。真实 Overlay smoke 已通过：壁纸基线为 `[660,90]..[1890,900]`，最右侧 Dock 展开时精确裁为 `[660,90]..[1890,570]`，收起后恢复 `y=900`；左侧和中央 Dock 展开时几何不变。空调页状态 `2 -> 3 -> 1` 期间根视图从可见切为 `GONE` 且释放 Surface，回到 `2` 后恢复壁纸窗口；标准浮窗 `window_mode=2` 时继续显示 `[546,0]..[881,72]` 顶栏，右侧 Dock 不改变其几何。全程保持唯一歌词服务、唯一 Overlay 与唯一窗口避让监听，未发现致命日志。
9. 设置页专项 UI smoke 已保留缓存和人工选择数据：缓存页只显示全宽“删除当前歌曲缓存”，未出现全量清空入口；概览显示 `0.8 MB / 128 MB`，按当前平均条目大小估算约剩余 `33427` 首。查找页放大镜与“搜索”组成单行水平居中组合，未发现错位。
10. 壁纸阴影开启时已关闭设置页核对真实歌词画面：当前、近邻和过去歌词行均有阴影；“顶部”焦点实际约位于壁纸可视高度 `15%`，未进入上方 `64px` 渐隐带。为验证结束时已把阴影开关恢复为关闭，并从应用偏好复核 `wallpaper_shadow_enabled_v1=false`；未点选候选、删除缓存或恢复自动。
11. 阴影关闭状态的第二张真实 Overlay 对照图受客观商业门禁阻断：重新创建 Overlay 前，staging 云端复核已返回“权益已撤销，当前无法继续使用”，未尝试恢复权益或绕过门禁，因此仅完成了设置状态和偏好真值复核。

## 2026-08-17 设置分类、独立歌词偏好、缓存管理与人工查找

1. 设置页已按车机场景收敛为五类导航：`歌词设置 / 服务状态 / 歌词缓存 / 歌词查找 / 权益中心`。歌词设置内部固定为`顶栏歌词 / 壁纸歌词 / 通用`三板块；顶栏一行字号与两行第一 / 第二行字号各提供小 / 略小 / 标准 / 微大 / 大五档，壁纸保持独立字号，另支持启用、模糊、全句阴影、密集 / 标准 / 宽松排版和顶部 / 中间焦点。双模同显已从范围中删除，仍由唯一 WebView 和唯一 Overlay 在顶栏、壁纸两种表面间互斥切换；权益中心业务逻辑未改。
2. `MainActivity` 只保留导航、偏好持久化和服务动作转发；新增集中设置渲染器负责四个歌词相关页面。服务状态页保留运行开关和全宽重启动作；缓存页显示总占用、自动 / 人工数量、预计剩余缓存歌曲数和当前歌曲来源与版本，只提供当前歌曲删除和恢复自动；查找页使用同一行的歌曲名、歌手、专辑输入与搜索按钮，两行结果项展示来源和时长。
3. 人工查找由现有三来源仓库并行执行，每来源最多 `8` 条、总计最多 `24` 条；目录搜索阶段不下载正文，点选后才读取同步歌词。人工选择不放宽自动候选准入规则，而是写入独立人工覆盖表；读取优先级固定为`人工覆盖 > 自动缓存 > 在线匹配`，恢复自动只删除人工覆盖，原自动缓存继续可用。
4. `LyricsCache` 数据库版本升至 `4`，`3 -> 4` 迁移保留自动缓存并创建人工覆盖表。自动与人工缓存共享 `128 MiB` 总预算，人工覆盖最多 `128` 首；状态广播只携带播放身份、候选元数据和缓存摘要，不传歌词正文。普通服务启动不再偷偷开启开机自启动，用户主动偏好仍由既有入口维护。
5. 自动验证通过：悬浮页 JavaScript 语法检查、`compileDebugKotlin`、`compileDebugAndroidTestKotlin`、全量 `testDebugUnitTest`、`assembleDebug`、`assembleDebugAndroidTest`、项目文档检查、Skill 检查和 `git diff --check` 均通过。最终又以关闭 Kotlin 增量编译的独立 staging 构建完整重跑 `assembleDebug assembleDebugAndroidTest --rerun-tasks`，避免共享构建目录的并发参数污染。
6. 最终 staging 应用与 androidTest APK 均为单 signer、APK v2 有效，并与仓库外 staging keystore 证书一致；车机现有正式应用证书相同，公开 API 信任配置已正确落包，服务端私钥、pepper、折扣密钥、签名口令和 keystore 文件扫描无命中。车机残留 androidTest 包仍是旧证书，按保留数据边界未覆盖、未卸载，因此 `LyricsCacheInstrumentationTest` 只完成编译，未在车机执行。
7. 同证书 staging APK 已保留数据覆盖安装，版本为 `1.14-icar03`（versionCode `114`）。基础 smoke 确认设置页位于前台、歌词服务恢复且无致命日志；专项 smoke 确认窗口边界为 `1230 x 810px`，五类导航和三板块无文字重叠，缓存页显示 `0.7 MB / 128 MB`、`191` 首及当前歌曲详情，人工查找以车机当前填充的《趁早》信息返回 `24` 条结果，结果项完整显示歌名、歌手、专辑、来源和时长。
8. 运行态只有 `1` 个 `LyricsOverlayService`、`1` 个歌词 Overlay 和 `1` 个 `MediaListenerService`，搜索后未发现应用或 WebView 致命日志。为避免改写用户当前人工覆盖与自动缓存，本轮未点选候选或执行清理 / 恢复；剩余最小主测仅为点选一条正确歌词确认即时替换，再到缓存页执行恢复自动，并观察壁纸字号、模糊、阴影、排版和焦点的主观效果。

## 2026-08-17 每次启动本地放行并云端复核权益

1. 用户确认最终规则：每个新的设置页实例和每个新的歌词服务生命周期都必须同时完成本地权益检查与云端健康复核。本地签名许可证负责立即决定是否运行；网络、超时或服务端异常不得破坏仍有效的本地权益；云端明确退款撤销或确认过期时才覆盖本地运行判定并立即释放歌词资源。
2. 已完成商业查询分层：新增轻量 `CommercialAccessRefreshResult` 与进程级 `SingleFlightCommercialAccessRefresh`。设置页完整权益查询与歌词服务启动复核共享同一次核心云端请求，任一等待方取消不终止请求；设置页在核心结果后继续读取待支付会话和活动报价，服务启动不访问报价接口，请求完成后不缓存到下一次启动。
3. 已完成服务启动接线：服务继续先履行 Android 前台契约，随后同步读取本地门禁并异步复核云端。本地有效时歌词立即运行；本地无效时不创建歌词仓库、协调器、缓存、MediaSession、WebView 或 Overlay，只等待当次复核，新签名试用 / Pro 持久化后恢复一套资源，失败则进入商业恢复。系统授权缺失时仍优先展示授权恢复态，但进程级云端请求继续完成并可更新本地许可证。
4. 权威结果边界已固定：普通网络失败、存储查询失败和非撤销业务失败重新读取并尊重本地门禁；`entitlement_revoked` 强制映射为撤销并复用现有立即退出链；云端明确返回过期时映射为本地过期拒绝。服务同一生命周期接收设置或显示命令不重复复核；新的服务生命周期和新的设置页实例仍可分别发起下一轮复核。
5. 自动化通过：新增并发单飞、等待方取消不取消共享请求、启动只复核一次、网络失败保留本地、撤销 / 过期覆盖本地的用例；`CloudDeviceCommercialGatewayTest` 的退款和离线场景改由轻量启动复核入口验证。定向测试通过，完整 `testDebugUnitTest lintDebug assembleDebug assembleRelease` 通过，Lint 为零错误。
6. 同证书 staging APK 强制重新打包并核对：BuildConfig 为 staging，API 基址为公开测试环境；APK 与车机现有应用证书 SHA-256 一致，单 signer、APK v2 有效。保留数据覆盖安装 `1.14-icar03`（versionCode `114`）成功，基础安装 smoke 通过；安装后从车机只读拉取的 APK 与工作区最终 APK SHA-256 同为 `2f5e05140e353d715760178ac6f961b5ce59ec8d5ca51039159bf205ca77e421`，且没有应用源码晚于该产物。
7. 车机专项 smoke 通过：设置页与服务同一冷启动时只记录 1 次复核开始和 1 次完成，设置页再次前后台切换不重复复核；随后以应用自身 UID 模拟 `boot_completed` 服务独立冷启动，不打开设置页也记录 `localAllowed=true -> outcome=running -> result=ready`。两轮均只有 1 个歌词服务和 1 个 Overlay，`PREF_AUTO_START=true` 保持，未发现致命日志。目标车机全局只记录 Error，验证期间临时把单一 `DesktopLyrics` 标签提升到 Info，结束后已清空恢复原策略。
8. 用户随后完成真实退款，车机按不打开设置页的方式强制结束 `03歌词`，再以应用自身 UID 和 `boot_completed` 来源独立启动歌词服务。`15:51:26` 服务先凭原本地权益进入 `outcome=running`，`15:51:30` 云端复核明确返回 `failure_entitlement_revoked / localAllowed=false`，服务立即进入 `COMMERCIAL_RECOVERY`；这已实证“退款发生在软件未运行期间，下一次自动启动无需设置页即可发现并停用”。
9. 该次撤销完成约 `5.3s` 后，`03桌面`（`com.tcrrry.desktop`，UID `10077`）另行启动了 `03歌词` 设置页；新的设置页实例按既定规则再次请求云端并取得有效权益，随后恢复 1 个服务和 1 个 Overlay。这不是自启动漏判：退款撤销已经先发生，后续恢复来自独立的设置页查询，与“原七天试用截止前可取回剩余试用”规则一致。最终 `PREF_AUTO_START=true`，日志标签已恢复原值，未发现应用崩溃；未清数据、卸载、实际重启车机、提交、推送或发布。

## 2026-08-17 壁纸歌词上下边缘静态渐隐

1. 根因确认：壁纸歌词列表只使用 `overflow:hidden` 截断可视区域，远端歌词行到达上下边界时仍保持原透明度，因此字形和阴影会在边界处被直接切断。
2. 已采用最低运行成本的呈现层实现：仅在桌面 `.lyrics-viewport` 上增加一张固定四段 alpha mask，上下渐隐带各 `64px`，中部完全不透明；目标 WebView 为 Chromium `66.0.3359.158`，使用其支持的 `-webkit-mask-image` 并保留标准属性。未新增 JavaScript、定时器、坐标测量、动画 mask、实时模糊或 `will-change`，顶栏计算样式仍为无 mask。
3. 自动验证：同证书 staging `assembleDebug assembleDebugAndroidTest --rerun-tasks` 通过；新增 instrumentation 断言覆盖桌面存在静态线性 mask、切回顶栏后为 `none`。应用 APK 为单 signer、v2 有效，与车机现有 staging 证书一致，签名口令及私密配置标记扫描无命中。
4. 已保留数据覆盖安装 `1.14-icar03`（versionCode `114`），设置页、表面占用租约、歌词服务和致命日志基础 smoke 通过。壁纸实机截图确认上下文字自然淡出，壁纸未染色，中部当前行不受影响；运行态只有一个歌词 Overlay，未发现 WebView 致命日志。
5. 改动前后分别执行 `15s` `gfxinfo` 采样；两次媒体内容与歌词更新次数不同，因此不把绝对数当微基准。改动后没有出现接近 `60fps` 的持续重绘，中位帧耗时为 `46ms`（改动前 `57ms`），P90 均为 `150ms`，P95 为 `150ms`（改动前 `200ms`）；GPU 缓存仍处于改动前已经出现的约 `5.3MB` 区间，未观察到明确性能回退。剩余最小确认仅为用户观察上下渐隐强度是否符合“细微”的主观预期。
6. 车机残留 androidTest 包仍为旧证书，按保留数据边界未覆盖或卸载，因此新增 instrumentation 只完成编译；真实 WebView CSS 支持和最终画面已由正式 staging 应用 smoke 覆盖。未清数据、卸载、重启、提交、推送或发布。

## 2026-08-17 顶栏与壁纸歌词克制表面交接

1. 根因确认：顶栏与壁纸歌词原先直接提交悬浮窗位置、尺寸和 WebView 布局，视觉上只有跨区域位移，缺少能够遮蔽换位瞬间的表面透明度交接。
2. 已由 `LyricsOverlayService` 在唯一原生悬浮窗根表面集中实现：旧表面 `120ms` 线性淡出，完全透明后提交几何与 WebView 模式，下一帧以 `160ms` 线性淡入新表面；动画期间禁用触控，结束后按目标表面恢复。
3. 快速连续切换使用代际令牌只允许最新目标回调生效；Overlay 销毁会取消动画。歌词时间轴、顶栏长句横向滚动、WebView 换句动画和车机表面策略均保持原 owner，不新增第二套状态机。
4. `IcarDisplayStateMonitorTest` 与强制 Kotlin 重编译通过；随后共享工作区的完整 JVM、Lint、Debug、androidTest 与 Release 构建通过。同签名 staging APK 已保留数据覆盖安装，车机已安装 APK 与工作区最终 APK 的 SHA-256 完全一致，且没有应用源码晚于该产物。
5. 用户已在车机主测顶栏与壁纸歌词往返，确认透明度交接效果通过。按用户指令停止额外录屏与逐帧测试，并清理本轮生成的车机录屏、本机视频、截图、抽帧目录和 APK 对比临时目录；未清数据、卸载、重启、提交、推送或发布。

## 2026-08-17 退款撤销后歌词立即退出

1. 真实退款复盘确认：车机已经显示“权益已撤销”后，歌词仍持续正常显示，不是自动接回试用，而是撤销页面与歌词运行态发生分裂。旧流程虽会删除本地许可证并发送普通门禁刷新，但撤销结果先渲染，歌词退出仍依赖服务随后重新读取本地文件；支付轮询和恢复失败也没有统一的强制撤销事件，旧许可证删除失败时缺少独立 fail-closed 真值。
2. 已完成根因级收敛：许可证仓库新增 AES-GCM 加密的撤销标记，并在当前进程立即记为撤销；该状态优先于任何残留 Pro 许可证。Controller 将撤销建模为独立 `REVOKED` 更新，在渲染“权益已撤销”前发送；`LyricsOverlayService` 对专用撤销动作不再信任或重读旧许可证，直接进入既有 `COMMERCIAL_RECOVERY`，释放歌词仓库、协调器、缓存、协程、MediaSession、WebView、Overlay 和监听，保留 `PREF_AUTO_START` 并显示撤销通知。只有云端新签发且本地验签、持久化均成功的试用或 Pro 许可证可以重新开放门禁；云端确认原试用已过期时只收敛为无许可证的过期态，仍不开放歌词。
3. 退款后的试用规则保持不变：Debug HTTP / JSON fixture 新增撤销返回，自动化覆盖“旧许可证模拟删除失败仍封门”“原七天截止前重新确认只接回原剩余时间”“超过原截止后保持过期”，没有冻结、重置或顺延试用。
4. 自动验证通过：商业网关、安全门禁、Controller 事件顺序和服务启动策略定向测试通过；完整 `testDebugUnitTest lintDebug assembleDebug assembleDebugAndroidTest assembleRelease` 通过，Lint 为零错误，Debug、androidTest 与 Release 隔离编译成功，Release 调试金额、fixture 域名和调试入口扫描无命中。
5. 同证书 staging APK 强制重新打包后，实际应用 APK、androidTest APK、车机已安装应用和仓库外 staging keystore 证书核对一致；应用 APK 为单 signer、v2 有效，BuildConfig 为 staging，服务端私钥、pepper、折扣密钥和签名口令扫描无命中。首次默认 Debug 覆盖因证书不同被系统拒绝，未改变车机应用或数据；随后同证书 staging APK 保留数据覆盖安装成功，版本仍为 `1.14-icar03`（versionCode `114`），基础 smoke 通过。
6. 车机专项 smoke 连续执行两轮：撤销前为 1 个歌词服务和 1 个 Overlay；应用自身发送撤销事件后，歌词服务、Overlay 和 MediaSession 均为 0，`PREF_AUTO_START=true` 保持，撤销通知存在；再用现有有效试用许可证触发普通门禁后恢复为 1 个服务和 1 个 Overlay，撤销通知清除，未发现致命日志。该 smoke 不调用 Cloud 退款，也不修改本地许可证或试用时钟。
7. 车机残留 androidTest 包仍使用旧证书，与新测试 APK 不一致；按验证规则未覆盖、未卸载，因此未执行定向 instrumentation。真实退款网络返回到本地撤销标记由同协议 fixture 自动化覆盖，服务资源退出由车机专项 smoke 覆盖。未清数据、卸载、重启、提交、推送或发布。

## 2026-08-17 中等加粗 Logo 矢量化与运行资源收敛

1. 用户确认采用中等加粗的绿灰 `03 / LYRICS` 定稿；新增 `03lyrics-logo.svg` 作为唯一矢量母版，数字、连接点和英文字标均为独立路径，不嵌入位图、不依赖本机字体。
2. 根目录 `03lyrics-logo.png` 由母版导出为 `1024 x 1024` RGB 预览图；Android 清单继续引用 `@drawable/ic_launcher_art`，运行资源改为由同一母版直接导出的 `216 x 216` RGB PNG，适配原车应用中心实际 `108 x 108px` 显示，避免运行时从 `1024px` 进行约 `9.5` 倍缩小。
3. SVG 结构、禁用位图 / 文本节点、PNG 尺寸与色彩模式、清单引用和 `108px` 实际显示尺寸均已静态核对；按用户要求，本轮未重新打包、未覆盖安装车机，后续随其它功能覆盖安装时观察实机效果。

## 2026-08-17 顶栏连续长句切换复位

1. 根因确认：顶栏切句时会保留上一句约 `180ms` 的淡出动画，但横向滚动重算同时选中了当前行和退出行；已滚到末尾的退出行因此先被移除滚动状态并复位到起点，形成肉眼可见的快速反向回滚。
2. 已完成：横向滚动重算只作用于新的当前行。上一句保持末尾位移直至退出节点移除；下一句以独立零位移节点进入，继续沿用 `90ms` 提前切行、原始时间戳开放滚动和 `0.75s` 起始驻留，不改桌面歌词、翻译副行或播放时间轴 owner。
3. 回归覆盖：`LyricsOverlayTimingInstrumentationTest` 新增连续两句长歌词用例，直接断言退出行保留滚动类与负位移、下一句进入时无滚动类且位移为零；staging 应用 APK 与 androidTest APK 均构建通过。
4. 本机真实 Chromium 以 `560 x 72px` 加载同一生产悬浮页完成关键帧 smoke：上一句停在末尾退出，下一句从起点进入并等待自身时间点，页面无 JavaScript 异常。
5. 覆盖安装前已确认实际 staging APK 与车机现有应用、仓库外 staging keystore 证书一致，应用与测试 APK 均为单 signer、v2 有效，服务端秘密与签名口令扫描无命中。保留数据覆盖安装成功，版本为 `1.14-icar03`（versionCode `114`）、应用 PID `620`；设置页、歌词服务、表面占用租约和致命日志基础 smoke 通过。
6. 车机残留 androidTest 包仍为旧证书，未覆盖或卸载，因此未在车机执行定向 instrumentation；该阻断不影响正式应用覆盖安装。剩余最小确认仅为用户播放连续长句歌曲，观察切句时不再出现反向回滚。未清数据、卸载、重启、提交、推送或发布。

## 2026-08-16 歌词检索核心整体重构

1. 根因确认：旧流程把搜索变体、第三方协议、候选准入、排序、正文下载和跨来源时序集中在少数大文件中；来源扩展依赖全局阶段切换，某个快来源已经查空时仍会等待慢来源，诊断只给分值而不能说明拒绝原因。单纯继续扩充分段近似规则无法解决《千年泪》召回缺口，也无法让后续修改保持清晰。
2. 已完成 owner 重构：`LyricsSearchPlanner` 只生成有上限的来源无关查询计划，`PublicLyricsSources` 只解析 QQ / 网易云 / LRCLIB 协议，`LyricsResolutionSession` 负责单次请求中每来源独立推进、完成顺序、最多两条不同来源正文通道、LRCLIB 模糊兜底和阶段诊断，`DirectLyricsRepository` 收敛为来源装配、取消、关闭和封面查询；名称结构、带原因四级证据和候选准入分别由 `RecordingIdentity`、`RecordingEvidence`、`LyricsCandidateSelector` 承担。
3. 搜索计划固定按“标题 + 原始歌手、标题 + 专辑核心、标题 + 歌手主体、仅标题”生成、归一去重并截断为每来源最多三项。当前项没有合格候选或正文为空时只推进该来源，不等待其它来源；LRCLIB 精确请求仍在 `1800ms` 窗口内时模糊兜底不得抢跑，精确与目录均完成且无正文在途，或首轮截止到达后才启动模糊列表。总截止仍为 `3800ms`，未加入歌曲、歌手、专辑或来源特判表。
4. 候选裁决继续保留时长 `±2000ms`、确认标题、明确版本冲突和明确不同歌手等硬边界；标题、歌手、专辑现在同时输出证据等级与具体原因，排序分只作用于已经准入的候选。`Tank Lu / Tank` 与 `少女时代-太蒂徐 / 少女时代-TaeTiSeo` 均由有序连续主体形成近似证据；无分隔同文字子串、非连续拼接、错误歌手、错误版本和超时长候选仍拒绝。
5. 确定性验证通过：全量 JVM 共 `166` 项、`0` 失败、`2` 项显式公网测试默认跳过；Lint 为 `0` error，debug 与 androidTest APK 均构建成功。新增计划、真实字段协议 fixture、同来源即时扩展、正文为空继续、精确窗口内模糊兜底不抢跑、阶段诊断和目标歌曲正反例覆盖。
6. 最终代码的显式公网闭环通过：`Twinkle / 少女时代-太蒂徐` 在 `394ms` 内取得本地化组合候选与同步歌词；`千年泪 / Tank Lu` 在 `1071ms` 内取得 `Tank` 的正确同步歌词。当前 QQ 目录首项已返回同一录音的原声带发行，因此公网测试按录音证据验收，不绑定可变曲目 ID；确定性编排用例仍单独证明首轮错误歌手后同来源立即进入专辑查询并命中 `Fighting！生存之道`。
7. 最终 staging 应用 APK 与 androidTest APK 的单 signer、v2 签名、仓库外证书一致性、公开信任配置和服务端秘密扫描均通过。车机残留测试包仍为旧证书，遵守保留状态边界未覆盖、未卸载，因此本轮未在车机执行定向 instrumentation；真实公网生产仓库已在本机独立跑通。
8. 同签名 staging APK 已使用 ADB 非流式方式保留数据覆盖安装；`1.14-icar03`（versionCode `114`）应用 PID `24159`，设置页、表面占用租约、唯一歌词服务、唯一媒体监听、唯一歌词 Overlay、悬浮权限、通知监听和致命日志检查均通过。安装脚本已固定使用 `--no-streaming -r`，消除目标车机流式安装无输出卡住的重复失败。
9. 车机正式应用缓存只读审计进一步确认了目标链路：以 `Twinkle / 少女时代-太蒂徐 / 'Twinkle' Mini Album / 206796ms` 和 `千年泪 / Tank Lu / Fighting! 生存之道 / 260000ms` 按生产算法计算的缓存键，与车机数据库两条目标记录逐字一致；记录分别保存 QQ `少女时代-TaeTiSeo / 208000ms` 与 `Tank / Fighting！生存之道 / 260000ms` 的同步歌词、匹配策略版本 `3` 和单来源可重放证明，写入时间为 `22:20:00` 与 `22:19:48`。最终同策略 staging 包覆盖安装后这些记录保持有效；当前车机没有活动媒体会话，因此未伪造播放状态，也未把可见歌词和切歌画面误记为已自动观察。
10. 用户暂时测试通过并明确授权提交；本组歌词检索核心重构改动纳入本次提交。未推送或发布。

## 2026-08-16 标题与歌手有序连续分段证据

1. 根因确认：旧版候选总分允许标题与专辑抵消歌手差异；新版将歌手 `DIFFERENT` 升为硬拒绝后，仍先删除全部格式符并用压平字符串推断差异，使 `少女时代-太蒂徐 / 少女时代-TaeTiSeo` 等结构相关名称被误判。此前只为 `_` 保存无序单片段仍无法表达跨空格、点、连字符和多个连续片段的主体关系。
2. 已完成：`RecordingIdentity` 在标题版本解析和歌手合作信息解析后，按空格、点、下划线、连字符及文字体系切换保存有序分段；一方完整序列连续包含于另一方，或双方共享占主体的连续序列且等量剩余部分跨文字时记为 `NEAR`。没有分隔的同文字任意字符子串、非连续片段拼接和明确版本冲突仍拒绝，未增加歌手或歌曲特判。
3. 时长硬门槛保持不变：车机《童话镇》为 `219196 ms`，QQ 候选 `212000 ms`、网易云候选 `212571 ms`，两者仍因超过 `±2000 ms` 被拒绝；车机《Twinkle》为 `206796 ms`，QQ `208000 ms` 与网易云 `208720 ms` 均在边界内，真实来源歌手 `少女时代-TaeTiSeo` 现与车机 `少女时代-太蒂徐` 形成近似证据。匹配策略版本由 `2` 升为 `3`，旧缓存证明按未命中处理并由正常网络结果替换。
4. 搜索兜底：首轮“标题主体 + 歌手”没有合格结果时，QQ 与网易云在原总截止内补查“标题主体 + 专辑核心”；《千年泪 / Tank Lu / Fighting! 生存之道》可由该路径召回 QQ `Tank / Fighting！生存之道 / 260000 ms` 正确候选，不扩大首轮目录窗口，也不绕过录音核验。
5. 自动验证：`DirectLyricsRepositoryTest` 共 `53` 项、`0` 失败；新增覆盖连续多分段、`Tank Lu / Tank`、真实《Twinkle》本地化后缀、无分隔同文字子串和非连续分段反例。目录专辑兜底调度测试与全量 JVM 测试均通过；构建与车机覆盖安装结果见本节后续记录。
6. Staging APK 的单 signer、v2 签名、车机已安装包与仓库外 keystore 证书一致性、公开信任配置和服务端秘密扫描均通过。标准安装脚本的流式 ADB 安装在车机上无输出卡住，终止后旧包、数据和服务保持完整；改用同一 APK 的非流式 `-r` 保留数据安装成功。
7. 第二次保留数据覆盖安装与车机基础 smoke 通过：`1.14-icar03`（versionCode `114`）应用 PID `16419`，设置页、唯一前台歌词服务、通知监听、表面占用租约和唯一歌词 Overlay 正常，未发现应用致命日志。由于当前已知《童话镇》候选仍受时长硬门槛阻断，本轮不以该曲出现歌词作为实机验收条件；未清数据、卸载、重启、提交、推送或发布。

## 2026-08-16 顶栏长首句滚动时间对齐

1. 根因确认：同步歌词没有歌名、歌手、作词或编曲等前置行时，悬浮页会在前奏阶段把第一句提前选为顶栏预览行；旧状态只记录显示索引，横向滚动随文本渲染立即启动，后续调度又直接等待第二句，导致首句真正到点前已经滚到中后段。
2. 已完成：顶栏当前行拆分为“显示索引”和“是否到达该句时间点”两个状态。下一句继续按现有 `90ms` 提前量切入画面，但主行只有在原始时间戳到达后才取得滚动资格；跳播回该句时间点之前时关闭资格并复位。字体自适应、`0.75s` 起始驻留、翻译 / 下一句副行、桌面歌词、解析、匹配和缓存主链均未改变。
3. 回归覆盖：新增 `LyricsOverlayTimingInstrumentationTest`，在真实 Activity 窗口加载生产悬浮页，用 `10s` 前奏和超长首句覆盖前奏静止、到点滚动、回退复位。悬浮层 JavaScript 语法、`assembleDebug` 与 `assembleDebugAndroidTest` 均通过。
4. 本机真实 Chromium 以 `560 x 72px` 顶栏运行同一生产资源：前奏阶段长句溢出但无动画，`10s` 到点后动画开启且文字产生负向位移，回退到 `0s` 后动画关闭并恢复零位移；页面无 JavaScript 异常，截图未见高度变化或内容重叠。
5. 车机正式 Staging APK 已与当前安装证书复核一致并保留数据覆盖安装；`1.14-icar03`（versionCode `114`）应用 PID `9357`，设置页、歌词服务、表面占用租约和致命日志基础 smoke 通过。定向 instrumentation 因车机残留的旧测试 APK 使用不同证书而无法覆盖测试包；遵守不自动卸载边界，未清数据、卸载、重启、提交、推送或发布。
6. 已将本次测试包证书冲突沉淀为项目验证规则：物理车机定向 instrumentation 在安装前必须同时核对正式包与测试包的新旧证书，冲突即停止，不以自动卸载、清数据或临时换包名绕过；该约束只服务本项目保留授权与数据的车机验证，不回流通用项目模板。

## 2026-08-16 商业权益客户端云端合同本地接线

1. 已按 `device-commerce-contract.md` 接通 challenge、公开活动、报价、试用、创建订单、购买轮询、许可证刷新和同设备恢复八个接口的客户端适配层；Activity 与领域层不持有域名、JSON 字段或错误码，金额始终使用服务端展示值，客户端不做折扣或货币计算。
2. 设置页已按用户车机画面再次纠偏：左栏“03歌词”居中，试用 / 过期 / Pro 位于标题右侧；基于截图像素把角标上移 `8dp` 后，角标底框与标题实际字形底边同为 `y = 184px`。“显示设置 / 系统设置 / 权益中心”采用项目自有图标、固定图标列和四字左对齐标签。非 PRO 时，“显示设置 / 系统设置”顶部为中性大底框综合卡，左侧状态垂直居中，右侧购买标题紧邻折扣角标，下方价格对比与折扣共用右边界 `x = 1775px`；点击只进入权益主页，进入权益中心或 PRO 后卡片及占位完全消失。
3. 权益主页、订单详情和二维码支付为互斥整页状态：权益主页已删除 slogan，顶部居中显示试用权益状态和剩余时间；中间营销组把购买标题、右侧折扣和下方双列价格对比作为一个整体水平居中，并在顶部状态与底部购买组之间垂直居中，边界为 `[1247,393]..[1606,565]`，限时价与折扣右缘一致，不保留方向箭头。顶部状态组和底部购买组距对应页面边缘均为 `56px`。PRO 生效后改用 `[1040,408]..[1814,582]` 的独立居中完成组，以金色皇冠点缀“Pro 权益已生效 / 权益生效中 · 永久”，营销和支付入口全部隐藏。订单继续使用左说明 / 右结果对齐、紧凑折扣码控件和独立最终价格行；订单明细顶部与支付组底部同样距页面 `56px`，不再强制“支付”与“前往支付”同位。创建订单后仍切换到服务端二维码，支付成功自动返回权益页。
4. Debug fixture 在正式 HTTP / JSON、设备签名和许可证验签主链上覆盖有效试用、过期、查询失败、无活动、无效 / 过期 / 不可用码、改价确认、待支付、支付成功、PRO、订单过期和三种恢复结果。默认报价为 `¥0.02 / 5折 / ¥0.01`，另有 `¥49.00 / 6折 / ¥29.40` 与最低支付 1 分边界；这些金额、活动码、二维码、fixture `keyId` 和调试 Activity extras 只存在于 Debug / 测试。
5. 设备安全主链使用 P-256 AndroidKeyStore 设备键、SPKI 导出、公钥摘要、固定设备指纹、原始 challenge 签名、四行购买轮询签名和非阻断 Key Attestation 探测；许可证先验签后解析并核对 `keyId`、产品、设备公钥摘要、密钥版本及时间边界。
6. 许可证、device token、poll token、未完成支付会话、试用时钟、许可证可信时间、设备密钥版本和恢复别名由独立 AES-GCM AndroidKeyStore 键加密后写入 `noBackupFilesDir`；没有实现或保存 `recoveryCredential`，没有账号、邮箱或跨设备迁移入口。
7. 歌词启动新增第二层商业门禁：系统悬浮 / 通知使用权先通过，商业准入再通过后才初始化歌词来源、协调器、缓存、协程、MediaSession 监听、WebView 和窗口。过期 / 查询失败现场确认歌词服务与 Overlay 均释放、`PREF_AUTO_START=true` 保留；恢复成功后只存在一个歌词服务和一个 Overlay。
8. Debug 默认装配进程内 fixture；只有显式提供 staging 公钥与 `keyId` 时才使用固定 staging 基址。Release 固定选择 production，正式 API 基址、公钥或 `keyId` 缺失即 fail closed；默认构建不会连接 staging 或 production。
9. 上一轮 UI 迭代执行的 `143` 条 JVM 测试、`lintDebug` 和 `assembleDebug` 均零失败；本轮营销组、底部锚点、PRO 完成态与综合卡隐藏间距纠偏追加执行商业布局契约定向测试和 `assembleDebug`，均通过。此前商业安全定向 instrumentation `3` 条、androidTest / Release 构建与 Release 解包隔离也已通过，未访问 staging 或 production。
10. 车机选择粉色时公开主题键实测为 `33`，切回紫色后为 `32`；两种状态下导航、分段、开关、折扣标签、箭头和主按钮均与系统主题一致。显示页、权益页和订单页截图及运行态边界确认无重叠或裁切：标题实际字形与角标底框共同结束于 `y = 184px`，综合卡报价与折扣右缘同为 `x = 1775px`，权益营销组水平中点与右侧页面中点一致；权益顶部 / 底部组和订单顶部 / 底部组到页面边缘均为 `56px`。PRO 完成组为 `[1040,408]..[1814,582]`；综合卡隐藏时显示页首个分段为 `y = 207..279px`、系统卡为 `y = 146..272px`，综合卡恢复后显示页首个分段回到 `y = 338..410px`。保留数据覆盖安装后应用进程、前台歌词服务、设置窗口和致命日志检查通过。
11. 既有商业功能 smoke 已覆盖权益 / 订单 / 二维码整页切换、有效 / 无效折扣、原价继续支付、二维码会话恢复、支付成功、PRO 综合卡消失、PRO 复位回试用、过期、查询失败、不同设备恢复未找到及同设备恢复成功；最终车机保留 PRO 态，只存在一个歌词服务和一个歌词 Overlay。
12. 用户最终视觉验收通过：综合卡、权益页、订单页、PRO 居中完成态和综合卡隐藏后的普通设置页留白均无需继续调整。
13. 用户最终视觉验收通过并明确授权提交；本组商业权益客户端、设置页与验证文档改动纳入本次提交。本轮未修改 cloud，未执行真实支付、真实退款、staging / production 部署、Release 安装或推送。
14. 已建立仓库外长期 staging APK 签名身份，并将公开信任包中的 HTTPS API、P-256 许可证公钥和 `keyId` 通过现有集中装配入口注入实际 staging APK。默认 Debug fixture 与 Release production 分支未改；签名配置缺失时 staging 构建会 fail closed，不会回落到开发机 Debug 证书。
15. staging APK 的单 signer、v2 签名、证书 SHA-256 与 keystore 一致性、公开配置落包和服务端秘密扫描均通过。目标车机原开发版证书不同；用户后续明确授权卸载旧包并全新安装 staging APK，旧设置、缓存、商业状态和应用密钥已随卸载清除。新包 `1.14-icar03`（versionCode `114`）证书复核一致，设置页位于前台且无致命日志；悬浮窗和通知使用权均保持未授权，未自动修改系统设置。等待 Cloud staging 可用后再执行真实联调。本阶段未提交、推送或接入 production 身份。
16. Cloud staging 上线后，用户已完成真实扫码支付并取得 PRO 永久权益。首次干净重装暴露“启动查询遇到同指纹新设备密钥时只显示无法确认、必须手动恢复”的客户端缺口；现已限定为仅对云端明确的 `device_key_mismatch` 自动调用一次既有恢复主链，网络、撤权、验签、存储、时钟、指纹不一致及恢复失败均不递归。完整 JVM 测试、Lint、Debug / androidTest / Release 隔离编译、staging 单 signer v2 签名与秘密扫描通过；新 staging APK 在车机再次干净重装后未点击任何恢复动作即自动取得新 device token 和 PRO 许可证，页面直接显示“Pro 权益已生效 · 永久”。悬浮窗、通知使用权、自动启动和唯一歌词服务已恢复；未修改 Cloud 后台、未接入 production。用户已明确授权提交，本组 staging 接线与自动恢复改动纳入本次提交，未推送。
17. 所有界面固定文案已统一使用 `Pro`，订单页对 Cloud / fixture 下发商品名中的独立 `PRO` 词元也只在渲染边界规范化为 `Pro`；内部 `CommercialTier.PRO`、许可证声明和协议值保持不变。商业布局契约测试、完整 Debug JVM 测试与打包、项目文档检查、Skill 检查和 diff 检查通过；同签名 staging APK 已保留数据覆盖安装，车机确认左栏角标与权益中心标题均显示 `Pro`，既有永久权益、前台歌词服务和 Overlay 保持正常，无致命日志。

## 2026-08-15 极简绿灰 Logo 替换

1. 根图稿 `03lyrics-logo.png` 与 Android 资源 `drawable-nodpi/ic_launcher_art.png` 已同步替换为同一张 `1024 x 1024` RGB PNG，SHA-256 均为 `23f2873e015a15fa44291dfdd4800b75773614d9d75e84109506bdf62e4c74b0`。
2. 当前图稿为白底、绿色线性“03”和深灰“LYRICS”字标，继续复用清单中现有完整位图入口，不改包名、签名身份、应用名或升级链。
3. 用户确认该替换已由另一个对话完成，并明确授权与本轮歌词解析改动一并提交；本轮提交审计未把此前歌词 APK 的构建和安装结果误记为新 Logo 验证。

## 2026-08-15 单侧录音版本标题证据

1. 根因确认：播放侧《晒》未携带现场版标记，QQ 与网易云正确候选分别为“晒 (Live)”和“晒 (LIVE版)”；旧选择器在标题证据计算前要求版本集合完全相等，因此基础歌名、歌手、专辑和时长证据均未参与即被拒绝。
2. 版本关系现已并入标题四级证据：双方版本相同或都省略为 `EXACT`，仅一侧标注为 `NEAR`，双方明确标注且类别不同为 `DIFFERENT`。只有明确冲突继续硬拒绝；单侧省略可凭已确认歌手或专辑单来源入选，不要求第二来源，也未添加歌曲、歌手、专辑、`Live` 或来源特判。
3. 合格候选先按版本证据等级排序，再沿用原综合分与括号、来源数、时长差等次序；因此已有明确同版候选仍优先于单侧省略版本的候选，普通双语标题桥接和括号排序不变。匹配策略版本由 `1` 递增为 `2`，旧缓存证明按未命中处理并在正常网络命中后替换，未清数据或升级数据库。
4. 自动验证：`DirectLyricsRepositoryTest` 共 `47` 项、全量 JVM 共 `95` 项，均为 `0` 失败；新增覆盖单来源《晒》、双向单侧版本省略、明确 `Live / Remix` 冲突、版本证据排序和证明重放。debug 与 androidTest APK 构建通过。
5. 车机受控网络 smoke：以“晒 / Tizzy T & GALI / 中国说唱巅峰对决 第三期 / 220264 ms”执行定向用例，`1879 ms` 命中网易云 `1962368708`“晒 (LIVE版)”，同步歌词、`49 ms` 时长差和 `NEAR` 标题证据均通过。
6. 正式 debug APK 已保留数据覆盖安装 `1.14-icar03`（versionCode `114`），应用 PID `15872`；设置页、表面占用租约、歌词服务恢复和致命日志基础 smoke 通过。最终运行截图中当前媒体卡为《晒》，顶栏已显示歌词文本“Back on my block”。
7. 用户主测通过：《晒》已正常显示并随播放工作；用户明确授权提交本轮修改。未清数据、卸载、重启、推送或发布。

## 2026-08-15 歌词解析完成顺序与证明缓存重构

1. 已将原来集中在 `DirectLyricsRepository.kt` 的领域结果、录音身份、候选选择、来源协议和请求协调拆成独立 owner；外部协议不再与 Unicode 归一、准入规则、服务生命周期和 SQLite 载荷混在同一个文件。
2. 首轮 LRCLIB 精确、QQ 目录和网易云目录仍同时启动，但改为按实际完成顺序消费；目录结果到达即可启动正文，最多两条不同来源正文通道，首个有效同步歌词立即返回。首轮全部耗尽且没有正文在途时可立即兜底，到 `1800ms` 仍未解决也会启动 LRCLIB 模糊兜底，总截止保持 `3800ms`。
3. 新播放请求现在同时取消旧协程、调度代际和正在阻塞的 `HttpURLConnection`，不只在回调阶段丢弃旧结果；结果明确区分 `Found / NoMatch / InvalidMetadata / RetryableFailure / Cancelled`。只有瞬时失败在请求仍为最新时等待 `1000ms` 后重试一次，完成无匹配不重试，生产获取链没有其它 `sleep` 或人为同步屏障。
4. 候选准入改为显式安全门槛：时长、录音版本和确认标题继续硬校验，歌手 `DIFFERENT` 一律拒绝；歌手或专辑已确认可单源入选，两个独立来源只能补充 `UNKNOWN`。原证据分值仅用于合格候选排序，不再出现“标题和专辑相等抵消错误歌手”的边界。
5. 每个在线结果携带匹配策略版本及最多两条支持候选；缓存数据库升级到版本 `3`，清理旧无证明条目，读取时重放完整证明。目标车机独立测试库的版本升级、时长复核、错误候选拒绝、翻译持久化和双来源证明持久化共 `5` 项通过。
6. LRCLIB 精确查询的专辑参数改用剥离独立 `Single / EP` 发行后缀后的核心文本；未添加歌曲、歌手、专辑或来源特判。远程封面只在最终歌词结果没有封面时补查，不再与歌词正文争抢首轮网络池。
7. 自动验证：全量 JVM 共 `93` 项、`0` 失败；其中候选规则、完成顺序调度、最新请求取消、一次重试和缓存策略定向共 `57` 项通过。debug 与 androidTest APK 构建通过。
8. 车机真实网络 `4/4` 通过：`错错错` 首次 `746ms`、同进程第二次 `375ms`；`MOYA 647ms` 命中网易云 `27506834` 且未选错误录音；`摩天动物园 291ms` 命中网易云 `1409382131`；`마리아 268ms` 命中网易云 `1459013594`。四项均通过同步时间轴、时长和录音版本核验。
9. 正式 debug APK 已保留数据覆盖安装 `1.14-icar03`（versionCode `114`），应用 PID `32716`；设置页、表面占用租约、歌词服务恢复和致命日志基础 smoke 通过。未清数据、卸载、重启、提交本轮重构、推送或发布。
10. 剩余最小主测：连续快速切换两至三首本地未缓存歌曲，确认只显示最后一首歌词且旧歌不延迟覆盖；再播放《摩天动物园》，确认正确同步歌词仍能快速出现并随进度切行。

## 2026-08-15 跨文字歌手展示名与发行后缀匹配

1. 现场根因确认：车机上报《摩天动物园》为“邓紫棋 / 摩天动物园 - Single”，QQ 与网易云目录返回“G.E.M.邓紫棋 / 摩天动物园”并提供有效同步歌词；旧规则把两个完整字段分别归一为单一字符串，未识别其中的完整歌手名片段和独立发行类型后缀，导致综合证据不足而静默。
2. 歌手证据新增跨文字展示名桥接：按 Unicode 文字体系边界拆出完整名称片段，仅当一方完整歌手项等于另一方展示名中的完整片段时记为 `NEAR`；同文字任意子字符串仍不匹配，不维护人物或歌手特判表。
3. 专辑证据新增发行类型核心比较：只有末尾由破折号、冒号或括号独立分隔的 `Single / EP` 才被剥离，核心专辑名完全相等时记为 `NEAR`；无独立分隔的普通专辑词不剥离。标题、明确录音版本和正负 `2000 ms` 时长边界均未放宽。
4. 自动回归：`DirectLyricsRepositoryTest` 共 `44` 项、全量 JVM 共 `85` 项，均为 `0` 失败；新增真实 QQ / 网易云候选的单来源放行用例，以及同文字歌手子串和无分隔专辑词两个拒绝用例。`assembleDebug` 与定向测试 APK 构建通过。
5. 车机网络 smoke：以“摩天动物园 / 邓紫棋 / 摩天动物园 - Single / 270676 ms”执行唯一受控用例，`1324 ms` 命中网易云曲目 `1409382131`，同步歌词、候选时长和录音版本核验全部通过。
6. 正式 debug APK 已保留数据覆盖安装 `1.14-icar03`（versionCode `114`），应用 PID `31728`；设置页、表面占用租约、歌词服务恢复和致命日志基础 smoke 通过。
7. 剩余最小主测：重新播放邓紫棋《摩天动物园》，确认顶栏或壁纸区域出现正确同步歌词并随播放进度切行。未清数据、卸载、重启、提交、推送或发布。

## 2026-08-15 同源逐句歌词翻译

1. 歌词结果新增可选同步翻译：QQ 读取当前歌词响应的 `trans`，网易云读取 `tlyric.lyric`；LRCLIB、候选排序、精确短路、请求数量和截止时间保持不变，不跨来源拼接或接入机器翻译。
2. “显示 → 其他”新增与“壁纸歌词”并排的“歌词翻译”开关，默认开启；卡片空白区和开关本体均能即时切换同一偏好。实机截图确认两张卡尺寸一致、文案完整且默认开启。
3. 桌面歌词把原文与下方小字翻译作为同一个滚动和高亮项；顶栏一行显示当前原文与翻译，双行按“当前原文、当前翻译、下一句原文”排列。翻译与原文按时间戳在 `120ms` 内一对一配对，缺行不顺延，翻译不改变主时间轴。
4. 顶栏翻译高度以 `72px` 为基线并封顶标准内容起点 `90px`；设置页两组五档字号标准值保持 `32px / 20px`，高度不足时优先收起下一句。固定测试歌词的 `560 x 90` 顶栏和 `1230 x 810` 桌面截图均无裁切、重叠或顺序错误。
5. 缓存继续使用现有 SQLite 与 JSON 载荷：翻译随原文保存；旧载荷仍先返回原文但只触发一次后台刷新，刷新后的空翻译字段表示来源已核验，未清库或升级数据库版本。
6. 自动验证：全量 JVM 共 `82` 项、`0` 失败；`assembleDebug` 与 androidTest APK 编译通过；悬浮层 JavaScript 语法、`120ms` 容差/缺行用例和公开网易云样本检查通过，样本 `48` 个有效原文行中配对 `42` 行、其余 `6` 行保持原文。独立测试数据库的 `LyricsCacheInstrumentationTest` 共 `4` 项通过。
7. 首次 instrumentation 误用 Gradle `connectedDebugAndroidTest`，该任务同时命中两台在线设备并按测试流程重装应用，导致车机数据与两项外部授权被清除；手机上的测试包已由任务自动移除。车机已通过受控 ADB 部署恢复原有悬浮 AppOp 与通知监听授权，并重新建立“两行、标准、壁纸歌词开启、翻译开启、自动恢复”状态；被清除的旧歌词缓存无法恢复，将在后续播放时按正常查询流程重新建立。项目规则已明确禁止在保留状态的物理车机上再使用该 Gradle 入口。
8. 最终正式 debug 包已保留数据覆盖安装 `1.14-icar03`（versionCode `114`），应用 PID `22316`；设置页、表面占用租约、翻译开关关闭/恢复、唯一歌词服务、唯一通知监听、唯一 `APPLICATION_OVERLAY` 和致命日志 smoke 通过，最终翻译开关保持开启。
9. 用户已确认本轮主测通过；同源逐句歌词翻译、默认开启设置以及桌面与顶栏呈现进入已验收状态。
10. 用户已明确授权提交；本组同源逐句歌词翻译改动纳入本次提交。未重启车机、推送或发布；除第 7 条已经恢复并单独记录的测试任务重装外，未再卸载或清除应用数据。

## 2026-08-15 可信歌词暂停保持显示

1. 显隐策略已从“所有非播放状态隐藏”收敛为：同一曲目已有可信同步歌词、暂停前已经可见时，暂停后冻结当前行并保持显示；暂停时首次出现的新曲目仍等待真正播放，切歌、停止、无会话和未命中歌词继续隐藏。
2. 蓝牙媒体类型没有被猜测或硬分类；目标车机只提供 `USAGE_MEDIA / CONTENT_TYPE_UNKNOWN`，因此继续以现有严格同步歌词匹配结果作为“值得显示歌词”的证据。
3. `assembleDebug` 通过；保留数据覆盖安装 `1.14-icar03`（versionCode `114`）成功，应用 PID `29902`，设置页、歌词服务、表面占用租约和致命日志基础 smoke 通过。播放、暂停、恢复和切歌画面交由用户当前主测。
4. 用户已明确授权提交；本组暂停保持显示改动已纳入本次提交。未清数据、卸载、重启车机、推送或发布。

## 2026-08-15 强制重启后的授权恢复与前台服务契约

1. 根因修复：`LyricsOverlayService` 现在在 `onCreate()` 中先创建通知渠道并立即履行 `startForeground()`，所有授权判断、恢复退化、停止和运行资源创建均位于前台服务契约之后；Android 9 缺少悬浮授权时不再因提前 `stopSelf()` 触发 `RemoteServiceException`。
2. 启动状态收敛：服务集中按双授权与动作判定 `RUNNING / RECOVERY / USER_STOPPED`。任一授权缺失时释放歌词运行资源、返回 `START_NOT_STICKY`、保留 `PREF_AUTO_START` 并留下常驻恢复通知；只有 `ACTION_STOP` 清除自动恢复意图，`ACTION_RESTART` 继续与主动停止严格分离。
3. 恢复状态可观察：恢复通知明确区分悬浮权限、通知使用权或两者缺失，点击进入现有 `MainActivity`；授权有效的下一次合法启动清除恢复通知，并沿唯一 `LyricsOverlayService` 实例重建一套 MediaSession、车机监听、WebView 和 Overlay。启动日志只记录来源、两项授权和最终状态。
4. 自动验证：`SettingsBehaviorTest` 共 `6` 项、`0` 失败，其中四条启动用例分别覆盖双授权有效、悬浮权限缺失、通知使用权缺失和用户主动停止；`lintDebug` 与 `assembleDebug` 通过。
5. 车机缺权 smoke：安装后现场悬浮 AppOp 处于失效状态、通知使用权仍有效；模拟 `boot_completed` 来源并观察超过 `10` 秒，日志为 `overlayAccess=false / notificationAccess=true / outcome=recovery`，没有出现本轮新的 `RemoteServiceException`，`LyricsOverlayService` 正常结束，恢复通知存在，`PREF_AUTO_START=true`，`MediaListenerService` 持续由系统 live 绑定。随后又完成一次运行态撤权与恢复，结果一致。
6. 车机恢复与最终状态：恢复悬浮授权并显式启动设置页后记录 `outcome=running`，恢复通知清除，严格只有一个歌词服务和一个 `APPLICATION_OVERLAY` 窗口；最终保留数据覆盖安装 `1.14-icar03`（versionCode `114`）成功，应用 PID `17741`，设置页、表面占用租约、通知监听与歌词服务正常，未发现本轮致命日志。
7. 临时悬浮授权与日志标签均已恢复到验证后的正常状态；未清数据、卸载、真正重启车机、提交、推送或发布。真正重启闭环仍等待用户单独授权。

## 2026-08-15 全项目主测与收尾复核

1. 用户最终主测结论：03歌词深浅主题已经测试成功，主题色没有适配成功；除主题色外，当前已经实现的功能全部测试成功。
2. 深浅主题通过范围包括设置页、顶栏歌词和壁纸歌词随系统深色 / 浅色切换，歌词行、播放进度与悬浮表面保持连续；该能力正式进入已验收状态。
3. 主题色跟随未通过用户主测。现有 `com.mb.provider.theme_key` 与 `IcarThemeColorPalette` 映射保留为未完成实现，不再表述为已经适配成功，后续若继续施工必须重新完成运行态输入、控件最终颜色和深浅组合的实机闭环。
4. Android 8.0 兼容性收尾：将 API 27 才支持的 `android:windowLightNavigationBar` 从基础主题移入 `values-v27`，默认主题与版本主题共同继承 `Theme03LyricsBase`；当前 `lintDebug`、资源链接和 `assembleDebug` 通过。
5. 资源收尾：删除无引用的旧设置页背景、自适应图标链和无使用颜色，前台服务改用独立的歌词通知小图标；最终 lint 无错误，原有未使用资源告警已清除。
6. Git 历史收尾：已改写尚未推送的最后一个本地提交，从提交历史移除 `output/` 下 `103` 个图稿探索文件，并将 `output/` 加入忽略规则；磁盘上的本地图稿保持原样，未删除、移动或改写。
7. 车机收尾：保留数据覆盖安装 `1.14-icar03`（versionCode `114`）成功，应用 PID `26511`；设置页、歌词服务和表面占用租约正常，未发现致命日志。未清数据、卸载或重启车机，未提交本轮其余改动、推送或发布。
8. 项目文档检查、Skill 检查和 Git 文本检查均通过；按用户要求未修改根 README、英文 README、更新记录或隐私说明。

## 2026-08-15 恢复原始 Logo 与应用名

1. 用户提供的原始 Logo 文件现作为唯一图稿真值；根图稿与 Android 位图资源均逐字节使用该文件，保留粗体 `03 / LYRICS`、蓝色切角、底部红线和完整留白，SHA-256 均为 `15d35bd046898a1be43c51f9157dc976aa489db7631f132e785543ec5ed035a2`。
2. 根因确认：Git 中位图与用户原图的可见像素虽然一致，但原有 `@mipmap/ic_launcher` 自适应图标入口会在车机中再次放大和裁切，使完整原图看起来被修改。Android 清单现直接引用 `@drawable/ic_launcher_art`，绕过自适应前景缩放，系统只对整张方图等比显示。
3. 应用显示名称由“桌面歌词”统一改为“03歌词”；清单继续从 `app_name` 读取名称，设置 Activity、前台服务通知和悬浮页标题同步生效，包名、签名身份和升级链不变。
4. 本机 `assembleDebug` 通过，APK 内图标 SHA-256 与用户原图一致，资源解析确认 application label 为“03歌词”。首次安装因 ADB 临时离线中止，重新连接后保留数据覆盖安装 `1.14-icar03`（versionCode `114`）成功，应用 PID `11529`；车机系统应用详情页确认完整原图和“03歌词”名称均已生效，设置页、歌词服务、表面占用租约正常，未发现致命日志。
5. 未清数据、卸载或重启车机，未提交、推送或发布。

## 2026-08-14 应用 Logo 正式接入

1. 首次车机应用管理截图确认原稿在 `96px` 图标中存在视觉层级问题：两侧 `0/3` 占比过大，中间 `LYRICS` 字高不足。已沿用 `gpt-image-2` 位图链重新生成正式稿，保留白底、微圆角棱角框架、斜体字标和青绿色圆点，同时缩小两侧数字并提高中间字标的字高与笔画重量。
2. 根图稿 `03lyrics-logo.png`、Android 完整位图资源和 `output/logo` 正式母版保持相同 `1024 x 1024px` 内容，SHA-256 均为 `efaa40049ff2d99e0952c15531bff56313db2a372c597005071b82e6b7f4c862`；应用清单继续直接引用完整方图，不经过自适应前景裁切。前台服务仍使用独立的单色通知小图标。
3. 本机验证：`assembleDebug` 通过；debug APK 内的 application icon PNG 与正式母版 SHA-256 一致。车机应用管理实机截图确认新图标无裁切，`LYRICS` 成为第一阅读层级，缩小后的 `0/3` 仍可辨。
4. 车机基础 smoke：保留数据覆盖安装 `1.14-icar03`（versionCode `114`）成功，应用 PID `18048`；设置页、歌词服务和表面占用租约正常，未发现致命日志。未清数据、卸载或重启车机，待用户确认最终视觉。

## 2026-08-14 设置页系统菜单收敛

1. 实机根因确认：通知使用权系统页已经获得焦点和有效触摸区域，但车机 `Settings.apk` 的 `AppSwitchPreference` 对根视图启用遮挡触摸过滤；同时存在覆盖全屏且不属于 Android 9 受信任覆盖类型的 `com.tencent.supercar` `SYSTEM_ERROR` 窗口，系统因此丢弃敏感页面触摸。任务栈、按钮大小和应用点击监听不是根因。
2. 产品决策：目标车机授权改由外部受控部署流程完成，应用设置页不再展示或打开通知使用权和悬浮窗授权页面；正式运行仍不依赖 ADB、Root、无障碍或私有系统组件。
3. 已完成：左侧菜单“权限与服务”改为“系统”，右侧删除“读取播放状态”和“允许显示歌词”，只保留占满可用内容宽度的“重启悬浮歌词”操作。
4. 已完成：移除授权状态渲染、系统授权 Intent 和独立任务策略，`MainActivity` 恢复 `singleTop`；两项授权仍作为自动恢复和手动重启的真实前置条件，缺少授权时重启按钮只显示提示。
5. 本机验证：`testDebugUnitTest assembleDebug` 通过，设置资源、Kotlin 编译、授权前置规则和服务重启动作测试均正常。
6. 初次本机交付按用户要求未操作车机；用户随后明确授权覆盖安装。
7. 车机基础 smoke：通过 `node scripts/install-and-smoke.mjs` 保留数据覆盖安装成功，版本为 `1.14-icar03`（versionCode `114`）、应用 PID `24742`；设置页位于前台，歌词服务和表面占用租约正常，未发现致命日志。
8. 设置页最简 smoke：已切换到“系统”菜单，运行态视图树只存在一个 `restart_lyrics_setting`，局部边界为 `x = 42..890`、宽 `848px`，占满右侧可用内容宽度；未发现已删除的两项授权控件，本轮未点击重启动作。

## 2026-08-14 多值窗口占用与跨应用桌面租约

1. 根因确认：歌词原先把 `window_mode` 当作“无窗口 / 有窗口”的二元状态，左侧 ADAS / 充电卡片会被错误视为桌面歌词区域冲突；同时已安装歌词 APK 没有租约接收端，因此 `03桌面` 的右侧面板无法主动请求歌词退回顶栏。
2. 车机实测：壁纸桌面上左侧充电卡片单独展开时为 `launcherState=1 / window_mode=1`；左侧卡片与右侧标准确认窗口同时存在时为 `launcherState=1 / window_mode=3`。因此 `1` 是无冲突左侧区域，`3` 确实包含标准浮窗，不能按同一规则处理。
3. 已完成：`IcarDisplayStateMonitor` 将 `0/1` 映射为桌面区域清晰、`2/3` 映射为占用、其它值和读取失败映射为未知；`IcarLyricsSurfacePolicy` 将未知和占用保守退回顶栏。
4. 已完成：新增协议版本 `1` 的 `SurfaceOccupancyLeaseService`。它只接受固定 Action、返回无业务方法的 Binder，并以绑定存续作为占用租约；`LyricsOverlayService` 是唯一订阅者和歌词窗口 owner，租约出现、动画期间和释放后均重新走统一表面策略。
5. 已完成：新增租约状态单测、窗口多值和租约优先级用例；安装脚本在覆盖安装后必须查询并发现本应用的协议 Service，避免发送端存在而接收端缺失。
6. 本机验证：`testDebugUnitTest assembleDebug` 通过；Git 文本检查通过。
7. 车机基础 smoke：保留数据覆盖安装当前 debug APK 成功，设置页、歌词服务、协议 Service 可发现性和致命日志检查通过；未清数据、卸载、重启、提交、推送或发布。
8. 用户主测通过：左侧卡片单独展开时桌面歌词保持；`03桌面` 右侧面板开始展开、展开和收起动画期间始终顶栏，完全收回后按 `window_mode` 与“壁纸歌词”偏好恢复。
9. 规则沉淀：多值窗口语义与跨应用租约已写入架构总纲、UI 规范、代码规则、产品基线、验证矩阵和安全边界；该协议属于本项目和车机专有适配，不创建新 Skill 或模板回流。

## 2026-08-14 设置页权限与服务及原车开关参数

1. 已完成：设置页左侧保留“显示”并新增“权限与服务”，两类内容在同一 `MainActivity` 中切换，不新增独立页面；菜单补齐选中态、状态保持与无障碍描述。
2. 已完成：通知使用权和悬浮窗状态分别读取 Android 系统真值，页面进入或重新显示时刷新；无论当前是否授权，点击整行或开关本体均进入对应系统设置，界面不提前伪造状态。两项均有效时沿用设置页自动恢复歌词服务。
3. 已完成：新增服务自有 `ACTION_RESTART`。重启在同一前台服务实例内使旧运行代际失效，注销播放、音频、AVRCP 与车机状态监听，移除并销毁旧 WebView/窗口后重建；保留显示偏好和 `PREF_AUTO_START`，不复用 `ACTION_STOP`。
4. 原车取证：实际布局绑定组件为 `com.mbuiteam.mbui.widget.compoundButton.MBSwitch`；资源与截图交叉确认轨道 `64 x 36px`、thumb 外框 `30px`、透明 `8px` stroke 后白色核心约 `22px`，开启紫色为 `#5C66BF`，深色关闭轨道为 `#66727272`。
5. 已完成：用项目自有 `IcarSwitch` 直接复现上述真实参数，并实现标准 Android 开关 checked 与无障碍语义；替换“壁纸歌词”和两项权限状态，消除 `SwitchCompat` 内部几何造成的压缩，不链接车厂私有类或资源 ID。
6. 规则沉淀：原车控件已有最终绑定链时按资源真值和物理渲染尺寸落地；标准控件若额外 inset/缩放，则使用项目自有可访问控件复现参数。已同步 UI 设计规范与设计规则，不创建新 Skill。
7. 本机验证：全量 JVM 共 `69` 项、`0` 失败，其中新增 `SettingsBehaviorTest` 3 项覆盖双授权恢复条件、原车开关几何和重启动作不复用停止动作；`assembleDebug`、项目文档检查、项目 Skill 检查和 Git 文本检查均通过。
8. 车机基础 smoke：已通过 `node scripts/install-and-smoke.mjs` 保留数据覆盖安装当前 debug APK，安装后版本为 `1.14-icar03`（versionCode `114`）、应用 PID `5533`，设置页已启动并位于前台；歌词前台服务和表面占用租约正常，未发现致命日志。两项授权入口已改为无论当前状态如何均可点击，完整系统页面跳转与手动重启闭环交由用户主测。未撤销授权、清数据、卸载或重启车机，未提交、推送或发布。
9. 后续修正：系统授权页改为独立系统任务，设置页改为唯一任务根；该修正解决系统 Activity 污染应用任务与重开仍停留在系统页，不把此前点击异常未经取证地归因于任务栈。触摸焦点与输入区域作为独立实机证据验证。

## 2026-08-14 当前车机歌词授权恢复

1. 按用户当次指令，仅在当前已连接开发车机恢复了本应用的悬浮窗特殊访问和通知使用权；未清除应用数据、卸载应用、重启车机、安装 release、提交、推送或发布。
2. 运行验证：打开设置页后，`LyricsOverlayService` 以此前台服务状态运行，`MediaListenerService` 保持系统绑定，顶栏 `APPLICATION_OVERLAY` 窗口可见；系统同时存在可读取的活动媒体会话。
3. 该授权仅属于当前设备，不能随 APK 分发到其它车机；普通发行的用户侧授权路径须另行按发行约束处理。

## 2026-08-14 《MOYA》错误歌词与候选共识边界修正

1. 根因确认：当前播放为 `MOYA / AOA / MOYA - EP / 220427 ms`，错误缓存实际保存了网易云 `29719782 / 사뿐사뿐 / AOA / 사뿐사뿐 / 219533 ms`。它不是切歌残留；上一版让标题 `UNKNOWN` 候选凭歌手一致和错误的来源共识达到阈值，又把括号形式排在综合分之前，因此抢在三个正确《MOYA》候选前面。
2. 已完成：标题 `UNKNOWN` 只保留为等待双语标题桥接的中间态，未经桥接不得入选或参与来源共识；候选排序改为综合分优先，普通括号附注只在同分时裁决。`MOYA` 与 `모야` 仍可通过 `MOYA (모야)` 的双语标题桥接，不回退到逐字段完全一致。
3. 已完成：缓存读取复用当前候选选择规则复核标题、歌手、专辑、版本和时长。旧错误行无需清库或迁移，读取时自动失效；随后网络命中的正确结果会覆盖同一播放身份。
4. 性能边界：未增加网络请求、候选数量或截止时间；新增缓存复核只对最多 `5` 个时长 key 的命中行执行一次现有单候选规则，不进行网络访问。
5. 自动验证：`DirectLyricsRepositoryTest` 共 `40` 项、全量 JVM 共 `66` 项均为 `0` 失败；新增《MOYA》真实候选组合、标题未知不可补分和综合证据优先用例。`LyricsCacheInstrumentationTest` 在目标车机共 `3` 项、`0` 失败，覆盖旧错误缓存自动失效。
6. 目标车机受控网络 smoke 通过：《MOYA》在 `2721 ms` 命中网易云正确曲目 `27506834 / MOYA (모야)`，返回同步歌词并明确排除错误曲目 `29719782`。
7. 规则沉淀：标题未知态、来源共识前置条件、综合证据排序和缓存复核已写入架构总纲、产品验收、验证矩阵与代码规则；机械约束由单元测试和 Android 缓存集成测试承接，不创建新 Skill，也不回流跨项目模板。

## 2026-08-14 歌词多维近似证据匹配

1. 根因确认：车机将华莎《Maria》上报为 `마리아 / HWASA / María - EP / 199000 ms`，QQ 音乐返回 `마리아 / 华莎 / María / 199000 ms`，网易云返回 `마리아 (Maria) / 华莎 / María / 199053 ms`；三个来源均有同步歌词，旧规则因名称逐字段硬核验把正确候选提前淘汰。
2. 已完成：标题、歌手、专辑统一改为 `EXACT / NEAR / UNKNOWN / DIFFERENT` 四级证据和可解释分值；总分至少 `5` 才入池，两个独立来源只加 `2` 分，同源重复不算共识。时长 `±2000 ms`、明确录音版本、有效歌手和同步时间轴继续作为不可补偿边界。
3. 已完成：近似统一覆盖 Unicode/大小写/标点/空格/重音、至少 `8` 字符的编辑相似度、专辑核心文本覆盖和来源明确给出的双语括号标题；不新增歌手别名、歌曲特判或第三方依赖。
4. 性能边界：未新增网络请求或改变 `1800 / 3800 ms` 截止；只在现有目录池出现两个以上来源时对最多 `17` 条候选做轻量两两比较，继续按排序逐条取正文、首条同步歌词立即停止。
5. 自动验证：`DirectLyricsRepositoryTest` 共 `37` 项、全量 JVM 共 `63` 项均为 `0` 失败；新增用例覆盖《마리아》、拼写近似、发行后缀、跨文字体系未知态、两源共识、同源重复、标题强冲突、歌手/专辑双冲突、占位歌手、错误版本和超时长。`assembleDebug`、`assembleDebugAndroidTest`、项目文档、项目 Skill 和 Git 文本检查均通过。
6. 目标车机受控网络 smoke 最终通过：《마리아》在 `2628 ms` 命中 QQ 音乐曲目 `000rxu503rIonP`，返回同步歌词且录音时长通过 `±2000 ms` 核验；临时测试包移除后再次保留数据覆盖安装正式 debug，当前为 `1.14-icar03`（versionCode `114`），应用 PID `31964`、设置页、歌词服务恢复和致命日志检查通过。
7. 规则沉淀：组合证据、不做逐字段一票否决、来源共识有限加分和硬边界已写入架构总纲、产品验收、验证矩阵与代码规则；机械约束由单测承接，不创建新的 Skill，也不回流跨项目模板。

## 2026-08-14 标准浮窗占用与顶栏歌词保留

1. 根因确认：服务原先只知道本应用设置页生命周期，无法识别其它 `1230 x 810px` 标准浮窗；同时 `settingsOpen` 被错误用于把整个歌词根视图设为 `GONE`，导致设置页打开后虽然表面已切到顶栏，顶栏歌词仍整体消失。
2. 车机事实：目标设备的 `Settings.Secure` 键 `com.mengbo.launcher3.settings.secure.window_mode` 在桌面为 `0`，本应用设置页、原车设置、行车记录仪和应用中心前台时均为 `2`，窗口关闭回桌面后恢复为 `0`；正式代码只读订阅该键，不写回、不轮询、不连接厂商私有 AIDL。
3. 已完成：`IcarDisplayStateMonitor` 将标准浮窗占用并入纯显示状态，`IcarLyricsSurfacePolicy` 统一处理启动器、窗口占用、本应用设置页与用户偏好；实测值 `2` 退回顶栏，其它未验证值和读取失败也保守退回顶栏。
4. 已完成：歌词根视图可见性只由当前安全几何是否有效决定。任意窗口打开只能触发 `DESKTOP -> TOPBAR`，不能再隐藏整个歌词层；窗口关闭后按当前启动器状态和“壁纸歌词”偏好重新计算。
5. 跨应用契约：本应用 `MainActivity` 以清单元数据 `com.tcrrry.icar.window.STANDARD_FLOATING_WINDOW = true` 声明标准浮窗身份，供实际启动方通用识别；本轮未修改 `03桌面`，也未在任何一方加入歌词包名特判。
6. 本机验证：`IcarDisplayStateMonitorTest`、`assembleDebug`、项目文档检查、项目 Skill 检查和 Git 文本检查均通过；新增用例覆盖标准浮窗占用、未知窗口模式保守顶栏和“只有无效几何才能隐藏歌词层”。
7. 车机基础 smoke：保留数据覆盖安装 `1.14-icar03`（versionCode `114`）成功，应用 PID `13982`；设置页、歌词服务恢复、版本和致命日志检查通过。
8. 车机功能 smoke：地图态下，本应用设置页、行车记录仪和应用中心打开时 `window_mode=2`，歌词 Overlay 始终 `VISIBLE`，边界为顶栏安全区 `(546, 0) .. (881, 72)`；各窗口关闭后 `window_mode=0`，地图态继续保持顶栏。
9. 壁纸往返 smoke：无窗口时 `launcherState=1 / window_mode=0`，歌词为 `(660, 90) .. (1890, 900)`；打开行车记录仪后 `launcherState=1 / window_mode=2`，歌词实时缩回 `(546, 0) .. (881, 72)` 且 `VISIBLE`；关闭后恢复桌面区域。
10. 用户主测通过：系统设置、行车记录仪或应用中心打开期间顶栏歌词文本持续显示，窗口关闭后的表面恢复和过渡符合预期。未清数据、卸载、重启、推送或发布。

## 2026-08-14 iCAR 标准浮窗进出动效

1. 已将标准页面窗口动效落实到 Activity 主题层，不在页面内容或关闭回调中维护第二套动画；窗口显示、Activity/任务前后台与壁纸前后台转换统一使用同一对资源，底层页面对应分支保持静止。
2. 窗口进入固定为整窗 `100%p -> 0` 的向上位移、时长 `150ms`；退出固定为 `0 -> 100%p` 的向下位移、时长 `300ms`，不叠加透明度、缩放、横向位移或回弹。
3. 已把该行为写入 `iCAR车机UI设计规范.md`、设计规则和验证矩阵；适用边界限定为 `1230 x 810px` 标准右侧页面浮窗，不扩散到歌词悬浮层、Toast、局部弹窗或控件内部动画。
4. 本机验证：`assembleDebug`、项目文档检查、项目 Skill 检查和 Git 文本检查均通过；APK 编译资源确认主题已绑定自有窗口动画样式，进入/退出参数与设计规范一致。
5. 车机验证：保留数据覆盖安装 `1.14-icar03`（versionCode `114`）成功；应用进程、设置页、歌词服务恢复和致命日志检查通过。运行态窗口样式已切换为项目自有动画，静止边界仍为 `(660, 90) .. (1890, 900)`，即 `1230 x 810px`。
6. 用户首测通过：设置页打开时从底部向上进入，关闭时向下退出，窗口大小与现有设置页一致。本任务无需继续逐帧取证。
7. 未执行：未清数据、卸载、重启车机、提交、推送或发布。

## 2026-08-14 iCAR 车机 UI 规范扩展至应用中心

1. 已将原 `iCAR设置页UI规范.md` 升级并改名为 `iCAR车机UI设计规范.md`，保留已通过用户主测的设置双栏规格，新增全局基础、页面模式提升规则、应用中心五列网格和智能场景三列卡片变体。
2. 新增取证对象为原车 `com.android.launcherMB` `56.24.4.9`（versionCode `9`），APK SHA-256 为 `4b1940c18e1fd9bb0ca96ae54b02f2e6565fd827eb3e3a7c7ce84d9d69d741ec`；窗口仍为 `1230 x 810px`、`141dpi`。
3. 资源与运行态交叉确认：顶部双标签高 `84px`、距顶 `42px`；应用中心为五列，单项 `168px`、图标 `108px`、应用名 `21px`；智能场景为三列，单卡 `356 x 168px`、标题 `25px`、说明 `18px`。
4. 背景根因边界确认：应用中心根布局独立绑定 `layout_app_applist01_bg`；浅色主体是不透明白，深色由顶部约 `#F90E0E0E` 过渡到底部 `#FF0A0A0A`。它与设置页左右双栏透明背景不是同一套 token，禁止跨页面套用。
5. 有效浅色基准仅在 Android `uiMode`、厂商主题服务与应用初始化同时为浅色时采集；此前标准配置浅色但厂商皮肤仍深色的混合截图未进入规范。
6. 车机状态已通过原车“显示模式 → 深色”入口恢复；最终 `Night mode: yes`、`persist.mb.theme = 32_2`，应用中心接收 `themeId = 32_2` 并保持打开，未清数据、卸载或重启车机。
7. 本轮只改文档与设计规则，未修改 Android 代码、未构建、安装、提交、推送或发布。

## 2026-08-14 iCAR 设置页背景透明度根治

1. 根因确认：原车 `activity_main.xml` 根布局通过 `mbui_skin_background -> ?layout_app_carset_bg` 绑定 `layout_app_carset_bg_day/night` 整窗背景资产；上一版误把未形成该根布局最终绑定的通用 `layout_app_bg_02/03` token 当成设置窗口背景，导致左右区域都比原车更透。
2. 已完成：不复制原车位图，改用项目自己的 XML 背景复现原车根背景稳定区。左栏采用浅色 `#EBF3F3F3`、深色 `#EB151515`；右侧采用顶部、中部、底部三点纵向渐变，浅色为 `#F6FCFBFC -> #F9FDFDFD -> #FFFFFFFF`，深色为 `#FA0C0D0E -> #FD0B0C0D -> #FF0A0B0C`；根容器和窗口背景继续透明，左右背景不重叠。
3. 已完成：设置页记录创建视图时的 `uiMode`；页面从后台返回时若系统深浅配置已变化而旧视图仍保留，则重建一次并重新加载当前主题资源，避免旧任务被带回前台后继续显示过期主题。
4. 本机验证：`IcarThemeColorPaletteTest` 和 `assembleDebug` 通过，资源链接无残留旧名称。
5. 车机视觉验证：在同一主题、同一地图底图、相邻时间内连续采集底图、原车设置页和本项目设置页；浅色与深色左右空白区的关键采样点通常与原车完全一致或仅差 `1` 个色阶，左右分界和圆角无第三层叠色带。
6. 车机运行验证：保留数据覆盖安装 `1.14-icar03`（versionCode `114`）成功；设置页、应用进程和歌词前台服务正常，浅色页面在后台经历浅转深后无需重启进程即可正确重建为深色，未发现应用致命日志。
7. 用户主测通过：用户确认布局无问题，背景透明度已经完全调好。
8. 用户偏好保持为“两行 / 标准 / 壁纸歌词开启”；未提交、推送或发布。

## 2026-08-14 未缓存歌词快速路径

1. 已完成：缓存未命中后并发启动 LRCLIB 精确单条、QQ 目录和网易云目录；LRCLIB 精确结果仍需通过现有标题、录音版本、歌手/专辑和 `±2000 ms` 时长核验，通过后立即返回。
2. 已完成：QQ/网易云候选改为按现有质量排序逐条获取歌词正文，第一条有效同步歌词立即停止，不再并发下载并等待全部合格候选。
3. 已完成：LRCLIB 模糊列表移至最后兜底，只有精确结果及 QQ/网易云候选全部失败时才下载，保留冷门歌曲召回能力。
4. 性能基准：目标车机上《错错错》原 LRCLIB 模糊搜索返回 `16` 条、约 `157 KiB`、约 `1.52 s`；精确请求返回 `1` 条、约 `9.7 KiB`、约 `0.99 s`，数据量下降约 `94%`。车机网络 smoke 最新实测匹配器冷启动 `81 ms`、首次精确歌词 `1302 ms`、同进程第二次 `524 ms`，结果均为 LRCLIB 曲目 `23978299`。
5. 根因修正：此前语言版本别名首次初始化扫描全部 Android 地区变体，单独耗时 `1431 ms`；现改为 ISO 语言集合，且合作歌手括号不参与版本解析，保留韩语版与 `Korean Version` 的跨语言核验。
6. 自动验证：`DirectLyricsRepositoryTest` 新增 LRCLIB 精确参数、合作歌手去重和候选短路测试，当前共 `24` 项、`0` 失败；显式网络开关下的 `DirectLyricsRepositoryNetworkInstrumentationTest` 已在目标车机通过。
7. 自动验证完成：全量 JVM 单测、debug 与 androidTest APK 构建、项目文档、项目 Skill、Git 文本检查均通过；正式 debug APK 已保留数据覆盖安装，设置页前台、应用进程、歌词服务恢复和致命日志检查通过。
8. 剩余最小主测：播放一首当前本地缓存未命中的正常歌曲，确认首次歌词出现时间明显短于此前约 `3` 秒，且正确歌词仍显示；再次播放同一首歌，确认缓存命中仍立即显示。
9. 用户主测确认通过并授权提交；本组歌词检索改动已纳入本次提交，未推送或发布。

## 2026-08-14 标题括号分级与合作歌手核验

1. 已完成：歌词搜索改用标题主体，移除 `feat.` 等合作歌手括号和普通附注，保留现场、混音、语言版等明确录音版本，提高目录召回且不丢失版本精度。
2. 已完成：标题主体仍为硬门槛；明确录音版本按标准化类别核验，类别冲突或单侧缺失时拒绝，同类版本的地点、年份等不同描述可继续参与候选排序。
3. 已完成：普通括号信息固定按“完全一致、相似或省略、完全不一致”排序，三类候选只要通过标题主体、时长和歌手/专辑确认都可保留。
4. 已完成：`feat.`、`ft.`、`featuring`、`with` 统一解析为合作歌手，允许其在歌名括号与歌手栏之间移动或被来源省略；未维护歌手别名或歌曲特判。
5. 回归覆盖：`错错错 (feat. 陈娟儿) / 六哲 / 被伤过的心还可以爱谁 / 289250 ms` 可接受网易云 `289186 ms` 和 LRCLIB `289000 ms` 的同步歌词；搜索源实测均存在正确歌词，原失败属于搜索词与括号规则问题。
6. 自动验证：`DirectLyricsRepositoryTest` 共 `21` 项、全量 JVM 共 `44` 项均为 `0` 失败；`assembleDebug`、项目文档、项目 Skill 和 Git 文本检查通过。
7. 车机基础 smoke：最终 debug 包已保留数据覆盖安装，`1.14-icar03`（versionCode `114`）设置页、应用进程、歌词服务恢复和致命日志检查通过。
8. 用户主测通过：《错错错 (feat. 陈娟儿)》已正常显示正确同步歌词且时间轴可接受。
9. 用户主测确认通过并授权提交；本组歌词匹配改动已纳入本次提交，未推送或发布。

## 2026-08-14 歌词候选独立核验

1. 已完成：歌词候选改为逐个与当前播放歌曲独立核验，不再按候选专辑互相分组或因出现多个发行专辑而整体清空结果；单一歌词源也可独立命中。
2. 已完成：标题基础名与版本标记必须一致、时长差仍不得超过 `2000 ms`；歌手直接一致或专辑标准化一致即可确认，专辑差异只降低排序优先级，不再否决已由歌手确认的录音。
3. 已完成：候选固定按“歌手与专辑均一致、歌手一致、仅专辑一致”排序，同级再按时长差、来源和来源曲目 ID 排序；新增候选只能增加备选，不能使已有合格候选失效。
4. 回归覆盖：Apple Music 上报的 `Super Girl / SUPER JUNIOR-M / Super Girl / 216827 ms` 可接受 QQ `218000 ms` 与 LRCLIB `218720 ms` 的国语歌词；标题标明韩语版的候选和网易云 `220290 ms` 的超时长候选仍被拒绝。
5. 自动验证：`DirectLyricsRepositoryTest` 共 `12` 项、全量 JVM 共 `35` 项均为 `0` 失败；`assembleDebug`、项目文档、项目 Skill 和 Git 文本检查通过。
6. 车机基础 smoke：保留数据覆盖安装 `1.14-icar03`（versionCode `114`）成功；设置页、应用进程、歌词服务恢复和致命日志检查通过。
7. 用户主测通过：Apple Music 国语版《Super Girl》已正常显示同步歌词，未误用韩语版。
8. 用户主测确认通过并授权提交；本组歌词候选改动已纳入本次提交，未推送或发布。

## 2026-08-13 歌词录音版本核验

1. 已完成：歌词源只负责返回目录候选和来源曲目 ID，公共选择器按标题基础名、版本标记、专辑、时长与必要时的直接歌手名确认录音；删除标题/歌手综合评分、来源共识加分和冠军分差。
2. 已完成：匹配要求车机时长至少 `1000 ms`，候选与当前播放时长差不得超过 `2000 ms`；跨语言歌手展示在标题、专辑和时长已确认时不单独否决，不维护人物别名表。
3. 已完成：缓存身份加入候选录音秒级时长，读取时只探测正负 `2` 秒并复核实际时长；数据库升级到版本 `2` 时清空旧评分规则留下的缓存结果。
4. 已完成：WebView 不再直接请求 LRCLIB 兜底，所有正式歌词统一经过原生候选确认链；同一播放元数据的时长变化超过 `2` 秒时立即清空旧歌词再查询。
5. 自动验证：全量 JVM 单测、悬浮层 JavaScript 语法、debug 与 androidTest APK 构建、项目文档、项目 Skill 和 Git 文本检查通过。
6. 受控网络核对：`Twinkle / 少女时代-太蒂徐 / 'Twinkle' Mini Album / 206796 ms` 可召回 QQ `208000 ms` 与网易云 `208720 ms` 的同专辑候选，均在 `2000 ms` 边界内。
7. 车机设备测试：独立测试数据库的旧缓存升级清理和时长复核共 `2` 项通过；临时测试包已移除，正式应用数据未清除。
8. 车机基础 smoke：已覆盖安装 `1.14-icar03`（versionCode `114`）；设置页、应用进程、歌词服务恢复和致命日志检查通过。
9. 用户主测通过：《Twinkle》正常显示歌词；时长相差 `3.2` 秒的错版案例保持空白。
10. 未执行：未推送或发布。

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
11. 后续用户主测通过：03歌词深浅主题切换成功，设置页、顶栏歌词和壁纸歌词的视觉可读性与歌词连续性符合预期。
12. 未执行：未提交、推送或发布。

## 2026-08-13 iCAR 设置页视觉与主题色适配

1. 已完成：以目标 iCAR 03 原车设置页的深色、浅色实机截图为样本，新增设置页 UI 规范（现已升级为 `docs/architecture/iCAR车机UI设计规范.md`），定义左侧类别导航、右侧内容、分段选项、开关尺寸、语义色与主题色边界。
2. 已完成：原生设置页保留可扩展的左侧类别导航和右侧内容区；当前仅“显示”一项，后续设置类别可直接向左栏扩展。右侧分段条和半宽开关卡按原车当前同类控件的尺度实现，不放置关闭、返回或重复“完成”按钮。
3. 已实现但未验收通过：`MainActivity` 只读监听车机 `com.mb.provider.theme_key`，由 `IcarThemeColorPalette` 尝试驱动分段选中态与开关开启态；深浅模式仍仅使用 `Configuration.uiMode`。后续用户主测结论为主题色没有适配成功，该链路保持未完成状态。
4. 自动验证：`IcarThemeColorPaletteTest`、`assembleDebug`、项目文档检查、Skill 检查和 Git 文本检查通过。
5. 车机基础 smoke：保留数据覆盖安装 `1.14-icar03`（versionCode `114`）成功；设置页在前台、应用 PID 与歌词服务正常、无应用致命日志。
6. 车机功能 smoke：当前主题键 `32` 的紫色已在设置页实机截图中作用于分段选中态和开启开关；一行/两行、字号“大”和壁纸歌词关闭/开启均即时生效，最后恢复为“两行、标准、开启”。
7. 后续用户主测结论：深浅主题已经测试成功，系统主题色没有适配成功；纯规则测试和单一资源对应不能替代最终实机结论。未提交本轮后续修正、推送或发布。
8. 后续修正：实机复核后移除左栏与右侧内容之间错误的第三层间隔；左栏固定 `345dp` 并直接连接右侧内容面。根容器透明，左栏使用较低有效 alpha、右侧内容区使用较高有效 alpha，避免透明色叠加后左栏反而不透明。

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

1. 当前版本：`1.0.4-icar03`（versionCode `118`）；Debug/staging 显示为 `1.0.4-icar03-test`，共用同一版本码。
2. 当前包名：`com.ninepointnine.desktoplyrics`。
3. 目标车机：Android 9、`1920 x 1080`、`S56_HQX`、高通 8155。
4. 最近业务提交：顶栏歌词双行字号五档设置提交；该提交尚未推送。

## 已知文档差异

1. 根 README 仍主要保留上游公开版本介绍和链接，不是当前车机适配版本的完整产品真值。
2. 当前车机适配的工程事实以 `docs/`、`app/build.gradle.kts` 和源码为准。

## 记录规则

1. 只记录已发生事实、实际验证、客观阻断和下一步。
2. 不复制长日志，不把未来计划写成已完成。
