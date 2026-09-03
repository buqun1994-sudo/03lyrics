# 运维规则

1. 触发条件：新对话需要构建或 ADB。动作：先读取 `.codex/local-context.properties` 并运行环境检查，不从聊天记忆猜路径。验证：JDK、SDK、Build Tools、ADB 和 Wrapper 均通过。边界：本机路径不提交。
2. 触发条件：首选 ADB 无法连接。动作：只读检查当前 server 和设备状态，再选择备用 ADB；切换前避免两套 server 冲突。验证：目标 serial 状态为 `device`。边界：连接失败时不得跳过自动安装 smoke 直接交付；不清数据或重启。
3. 触发条件：生成 APK。动作：区分 debug 构建、签名 release 和正式发布。验证：构建产物、签名身份和版本号分别核对。边界：构建成功不等于已安装或已发布。
4. 触发条件：共享工具链变化。动作：更新本机上下文和运维文档中的逻辑依赖。验证：环境脚本通过。边界：不把 JDK、SDK、ADB 二进制或缓存复制进仓库。
5. 触发条件：普通 Android 施工通过本机最短验证。动作：运行 `node scripts/install-and-smoke.mjs`，使用 debug APK 保留数据覆盖安装，向 `enabled_accessibility_services` 幂等追加项目的窗口避让组件后启动应用。验证：脚本确认安装成功、目标版本、前台页面、进程、应用致命日志、窗口避让服务已绑定，并逐项确认追加前的无障碍组件仍全部存在。边界：除用户已明确授权的窗口避让组件外，脚本不得替换或删除无障碍列表、卸载、清数据、重启、安装 release 或修改其它系统长期状态；功能专属 smoke 由当前任务另行执行并恢复临时测试状态。
6. 触发条件：向目标车机覆盖安装 APK。动作：固定使用 ADB `--no-streaming -r`，先推送再调用 Package Manager；不得重试已确认会无输出卡住的流式安装。验证：安装命令明确返回 `Success`，随后复核版本、进程、前台页面、服务和致命日志。边界：该规则针对当前目标车机与已配置 Platform Tools；不授权卸载、清数据、降级、安装 release 或改动其它设备。
7. 触发条件：构建依赖仓库外的公开配置、签名 properties 或其它长期本机输入。动作：在被忽略的 `.codex/local-context.properties` 中维护稳定文件指针，并在可提交示例与运维说明中只维护键名和占位符；新对话先读这些指针，不从聊天记忆猜路径。验证：目标文件存在、签名 properties 引用的 keystore 存在，实际 APK 证书与注入摘要一致。边界：本机绝对路径、口令、私钥和 keystore 不进入 Git；文件指针也不自动授权 production、发布或破坏性设备操作。
8. 触发条件：准备测试或 Release 版本。动作：所有变体读取仓库根 `release-version.properties`；未指定版本时运行 `node scripts/bump-release-version.mjs` 递增 patch 并同步递增 `releaseVersionCode`，有明确版本时传入 `--version`。Debug/staging 只追加 `applicationIdSuffix=".test"` 和 `versionNameSuffix="-test"`，不创建第二套版本文件。验证：`node scripts/bump-release-version.mjs --check`、按目标变体核对 APK 包名、版本和签名。边界：不把版本递增等同于部署、上线或车机安装。
