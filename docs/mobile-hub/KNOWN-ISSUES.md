# KNOWN-ISSUES

- R3：本地服务端源码快照可能落后四台真机部署版本，施工前需 health 对账（未做，需要设备在线）。
- 服务端无幂等键/无时区接口/任务列表无分页：按降级方案处理，见 Risk-Register。
- fork 版 v121 steering 依赖比本地 repo 快照新的服务端行为（turn 中 prompt 在快照里是 409）：v139 任务操作不依赖 steering，不受影响；v141 交接不用 steering 通道。
- v140 Low 未修项：定时选择过去时间无客户端拦截（服务端会立即触发，属既有语义）；probe 进行中无 UI 指示；文件夹 chips 超长横向滚动可用性一般；IdeaStore/DispatchStore JSON 解码失败静默返回空表为既有 store 模式（下次 upsert 会覆盖旧数据）。
- 回查匹配限制：服务端无幂等键，回查靠 标题+folder+时间窗（-5min 时钟冗余）；同名任务在窗内多条时保持 UNKNOWN 拒绝自动确认（fail-safe）。
- v141：交接包编辑内容进程被杀会丢（未入 SavedStateHandle，接受为已知限制）；交接/收件箱派发 sheet 均不内嵌 probe，UNKNOWN 回查统一在想法页派发记录卡。
