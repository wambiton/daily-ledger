# API 契约 v1（2026-09-10手动备份版）

基路径 `/api/v1/`，JSON UTF-8；金额为整数分。错误形态为 `error.code`、`error.message`。账号由Bearer会话确定，不接收客户端指定的所属用户。

| 方法及路径 | 请求 | 成功结果 |
| --- | --- | --- |
| GET health/ | 无 | status、database |
| POST auth/code/ | email、purpose（register/reset） | 202、retry_after |
| POST auth/register/ | email、code、password | 201、user、access_token、refresh_token |
| POST auth/login/ | email、password | user、access_token、refresh_token |
| POST auth/refresh/ | refresh_token | 新令牌，旧令牌失效 |
| POST auth/logout/ | refresh_token | 204 |
| POST auth/reset/ | email、code、password | status；撤销旧会话 |
| GET auth/me/ | Bearer | user |
| POST backup/ | operation_id、base_revision、payload；Bearer | revision、created_at、entry_count、sha256 |
| GET backup/latest/ | Bearer | 最新备份信息；无备份revision=0 |
| GET backup/download/ | Bearer | 最新备份信息及payload；无备份404 |

payload包含format_version=1、categories、entries，可选import_sources（最多50000个原本机账目UUID，用于重复导入检测；旧快照缺省为空）。分类字段：id、type、name、default_key。有效账目字段：id、type、category_id、amount_fen、date（YYYY-MM-DD）、note、created_at、updated_at（Unix毫秒）。不上传其他账号信息或手机令牌。最大20MB、2000分类、50000账目。

每次上传形成不可变完整快照。base_revision必须等于当前账号电脑版本；否则409 backup_conflict，不覆盖。相同operation_id和内容重试返回原结果；不同内容复用ID返回409。客户端保留上传中的完整请求供失败重试，不伪造备份成功时间。

换机登录自动下载快照，以事务写入账号数据库后才显示账号账本。同机已有未上传修改时不自动替换；不自动合并其他设备未上传修改。原本机账目通过追加导入处理，保留原账本；重复确认后每笔生成新UUID计入统计。旧的sync/push与sync/pull设计暂缓，未提供这些路由，不宣称自动同步可用。

常规状态码：400无效输入，401登录失效，404无备份，409版本/唯一性冲突，429限频，503电脑或邮件服务不可用。未配置SMTP时验证码请求返回503，不返回虚假成功。登录令牌不记录日志，响应不缓存。

## Windows电脑端配对

启用电脑端服务后，所有接口额外要求`X-Ledger-Pair`二维码能力密钥，缺失或错误403 pair_required；账号Bearer认证仍独立必需。`GET /api/v1/computer/`返回computer_id及protocol=1，用于扫码实查。电脑ID永久保存，与临时外网地址无关。

二维码JSON：type=personal-ledger-pair、version=1、server（HTTPS根地址）、computer_id（UUID）、pair_key（随机能力密钥）。不包含账号密码、邮箱授权码或账目。手机将配对信息加密保存，不在请求URL中传递密钥。旧USB独立服务不自动变成公网服务。

后台上传保留operation_id和快照供重试，使用数据库身份核对防止切换电脑误传；离线/5xx/429重试，认证/配对/版本冲突需要用户处理。取消不删除账目，已到达电脑的请求不能撤回。
