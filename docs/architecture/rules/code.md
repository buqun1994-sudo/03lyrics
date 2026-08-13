# 代码规则

1. 触发条件：修改播放、窗口、表面或生命周期状态。动作：沿 `LyricsOverlayService` 单一协调主链施工，不在 Activity、Receiver 或 WebView 复制状态机。验证：相关单测、构建和实机状态切换。边界：纯 UI 样式可留在对应呈现层。
2. 触发条件：新增或修改歌词源。动作：外部协议留在 `DirectLyricsRepository`，候选选择留在 `LyricsCandidateSelector`。验证：错误版本、占位内容、超时与竞争结果测试。边界：UI 不解析第三方 JSON。
3. 触发条件：修改车机状态。动作：`IcarDisplayStateMonitor` 保持只读、事件驱动和失败保守；未知值不得猜测为可见或桌面态。验证：纯状态单测和目标车机 smoke。边界：不得接入 CAN 或未经验证的私有写接口。
4. 触发条件：修改缓存。动作：预算和淘汰阈值集中在 `LyricsCachePolicy`。验证：策略单测；SQLite 行为变化补 Android 集成验证。边界：服务层只调用缓存接口。
5. 触发条件：需要跨两个以上调用点复用规则。动作：回到现有 owner 或新增边界清晰的抽象。验证：调用点不再存在重复实现。边界：不为单次文案或样式新增抽象。
