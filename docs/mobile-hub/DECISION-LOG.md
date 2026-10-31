# DECISION-LOG

- D1 (v138)：交接包内容生成用轻方案（拉 transcript 近 N turns 填模板 + 手动编辑），不做 AI 自动总结——与 PRD §17 排除项一致，主人此前拍板过该方向。
- D2 (v138)：v139 不新增底部 tab（避免动 HomeTab 用户自定义排序），入口挂 ACTIVITY 页。
- D3 (v138)：定时派发时区按手机本地时区换算为 UTC 绝对时刻并明示；服务端本来按绝对时刻触发。
- D4 (v138)：防重复提交不做服务端假设（无幂等键），本地记账+回查+UNKNOWN 人工确认。
- D5 (v140)：DispatchState 是派发请求的记账状态（PENDING/CONFIRMED/UNKNOWN），不是任务状态机——任务真实状态只在服务端，UNKNOWN 卡片只是"结果未知的请求"的待办提示。
- D6 (v140)：isAmbiguous 把 IOException/ApiError.Transport/5xx 全归"结果未知"禁止自动重发（服务端无幂等键，fail-safe）；仅 4xx 等确定失败才删账。
- D7 (v140)：回查（probe）匹配规则 = 精确标题(限120) + folderId 严格相等 + 发送时刻-5min 起的时间窗；多条候选或 folderId 缺失一律不确认，保持 UNKNOWN 交人工。
- D8 (v141)：v141 增量=结构化交接包→跨设备派发+Git预检；fork 既有 v121 同机重连式 handoff（引用+JSON导出+新会话播种）保留不动，两者互补。
- D9 (v141)：DispatchService 为唯一派发管线，收件箱与交接共用；交接派发 UNKNOWN 后在本面板禁止重发，回查统一走收件箱派发记录（ideaId=null 的 ref 渲染已验证安全）。
- D10 (v142)：加密文本只经系统剪贴板传递（API33+ 标记 IS_SENSITIVE）；密码字段用 remember 不入 SavedInstance Bundle；payload 只导 server_id/name/base_url/token/visible/include_in_all，其余不导。
- D11 (v142)：导入校验（解密+schema+字段+数量上限）全部先于首笔写入；写入按台顺序、失败即停并报已写入数（每台 save 为 profile upsert + Keystore put 两步，中途失败可能留下部分覆盖，文案已提示重编辑）。
