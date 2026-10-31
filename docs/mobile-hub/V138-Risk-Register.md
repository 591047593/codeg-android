# V138 风险登记表

| # | 风险 | 等级 | 影响 | 缓解/降级 |
|---|---|---|---|---|
| R1 | work_task 创建无幂等键，超时重试会造重复任务 | **High** | 重复任务干扰执行 | 本地 request_id 记账 + 回查匹配 + UNKNOWN 需人工确认；绝不自动重发（Implementation-Plan §派发管线） |
| R2 | 无服务器时区接口 | Medium | 定时偏差（用户以为按服务器时区） | UI 明示"按手机时区换算为绝对时刻"；服务端按绝对 UTC 触发，实际不受服务器时区影响 |
| R3 | 服务端版本快照落后真机部署（本地 repo ≠ 四台实况） | **High** | 封装的端点在旧版设备上 404；steering 等新能力不可用 | 施工前四台 health 对账；能力矩阵逐端点版本标注；缺端点隐藏操作；RouteParityTest 只保客户端自洽 |
| R4 | work_task_list 无分页/过滤 | Medium | 任务多的设备全量拉取慢、流量大 | 列表是轻量 summary，先直接全量；若实测 >数百条再加本地缓存+增量（task://changed id 回查单条） |
| R5 | task://changed 只带 id | Medium | 看板实时性靠回查，多设备多事件可能风暴 | 节流合并（500ms 窗口批量回查 changed ids）；列表整表刷新兜底 |
| R6 | 状态枚举演进（未来版本加状态） | Medium | 未知状态被错误归类 | server_status 原文保存 + 未知→独立"未知"列，绝不归 done；Gap-Analysis 映射只做推断不做裁剪 |
| R7 | token 无 server_id 绑定校验（连接串台） | Low | 配置错误连错服务器 | health 返回 version 可作指纹参考；保持现有单服务器独立 client 架构 |
| R8 | merge/deliver_pr/cancel 是破坏性操作 | Medium | 误触丢工作 | 危险操作二次确认对话框；merging 状态服务端本来就不可取消 |
| R9 | v142 PBKDF2 600k 在低端机卡顿 | Low | 导出等待数秒 | 实测调整；解密侧参数上限（iterations≤1M）防恶意文本 DoS |
| R10 | SecretStore 当前无"导出明文"能力 | Low | v142 需临时读明文 token | 仅导出流程内存中转，不落日志/不落盘；不扩展持久化明文 |
| R11 | 剪贴板被第三方键盘/系统保留 | Low | 密文泄露面（密文无密码不可解） | 导出后提示清理剪贴板 + 风险告知（PRD 10.8） |
| R12 | HomeTab 五栏结构变更会动用户自定义排序 | Low | 导航回归 | v139 不加 tab，入口挂 ACTIVITY；若未来必须加，保序兼容 homeTabOrder |
| R13 | 大量未提交修改的设备做 Git 预检（交接） | Medium | 交接后代码不同步 | git_status 显示未提交数并强提示；绝不自动 commit/push |
