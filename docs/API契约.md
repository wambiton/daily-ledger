# API 契约 v1

基路径 `/api/v1/`，JSON UTF-8；金额 `amount_fen` 为整数。统一错误形态为 `error.code`、`error.message`、可选 `error.fields`。所有账号数据由 Authorization 会话鉴权后确定。

| 方法及路径 | 请求 | 成功结果 |
| --- | --- | --- |
| GET health/ | 无 | status、数据库探测结果 |
| POST auth/code/ | email、purpose（register/reset） | 202、retry_after |
| POST auth/register/ | username、email、code、password | 201、user、access_token、refresh_token |
| POST auth/login/ | username、password | 200、user、access_token、refresh_token |
| POST auth/refresh/ | refresh_token | 200、新令牌 |
| POST auth/logout/ | refresh_token | 204 |
| POST auth/reset/ | email、code、password | 200 |
| GET auth/me/ | Bearer 会话 | 200、本人资料 |
| POST sync/push/ | operations 数组 | 200、逐条 applied/conflict/rejected 结果 |
| GET sync/pull/ | cursor、limit | 200、changes、cursor、has_more |

同步操作包含 operation_id、entity_type（category/entry）、entity_id、base_version、action（upsert/delete）、payload。同一操作重试返回相同结果；同一操作 ID 更换载荷应拒绝。version 为服务端整数，客户端时间不能覆盖版本检查。

账目载荷：type（expense/income）、category_id、amount_fen、entry_date（YYYY-MM-DD）、note。分类载荷：type、name、default_key（仅服务端默认类别）。日期时间元数据由服务端记录，所属日期由用户提供并验证。

常规状态码：400 无效输入、401 无会话或会话过期、403 无权限、409 唯一性或版本冲突、429 限频、503 服务暂不可用。批量接口的单项错误放在结果中，失败项不影响已确认成功项。

首次恢复先使用 cursor=0 完整重放按序变更；首版保留全部变更和删除标记，直到形成经过测试的快照/日志压缩协议。恢复每页与游标原子落库。该方案取代早期分页快照建议，避免未实现一致性快照却声称安全恢复。

认证及同步接口在 P4 实现；P1 仅实现健康检查和模型，不提供假成功响应。
