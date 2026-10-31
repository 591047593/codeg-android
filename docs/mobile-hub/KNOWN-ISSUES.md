# KNOWN-ISSUES

- R3：本地服务端源码快照可能落后四台真机部署版本，施工前需 health 对账（未做，需要设备在线）。
- 服务端无幂等键/无时区接口/任务列表无分页：按降级方案处理，见 Risk-Register。
- fork 版 v121 steering 依赖比本地 repo 快照新的服务端行为（turn 中 prompt 在快照里是 409）：v139 任务操作不依赖 steering，不受影响；v141 交接不用 steering 通道。
