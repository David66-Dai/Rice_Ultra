# 数智稻安 AstrBot 设备控制 v2

本插件通过 Rice Ultra Java 后端控制 S01 的智能喷药和驱虫灯。
所有操作使用服务器上的 AstrBot 身份映射，最终仍由平台账号启用状态和
`app.devices.control-users` 决定权限。成功指令会进入网页共享状态并通知所有网页用户。
需要 AstrBot 4.18 或更高版本，支持个人微信、OneBot v11/NapCat、QQ 官方 WebSocket
和 QQ 官方 Webhook 适配器。

插件不读取服务器的 `conf/config.yaml`，也不使用平台账号密码或网页 JWT。
它只保存一个受限的 AstrBot 集成令牌；该令牌只用于 `/api/astrbot/**` 下的设备控制与只读农业查询，
不能替代网页 JWT 访问其他平台 API。

## 安装

在 AstrBot WebUI 的插件管理中上传仓库里的：

```text
integrations/astrbot_plugin_agri_control.zip
```

插件使用 `httpx`；WebUI 如未自动安装依赖，可在 AstrBot 的 Python 环境执行
`pip install -r requirements.txt`。源码方式安装时，将整个
`astrbot_plugin_agri_control` 文件夹复制到 AstrBot 的 `data/plugins` 后重载插件。

AstrBot 官方当前配置机制会读取 `_conf_schema.json`，在 WebUI 生成插件设置并将配置传给
插件构造函数。v2 插件已按这个接口接收配置，不再读取老项目的 JSON 文件。

## 配置

1. Java 后端的实际配置在 `conf/config.yaml`：

   ```yaml
   app:
     devices:
       control-users: [admin, danglong, dzh]
     astrbot-control:
       enabled: true
       api-token: "至少32位的独立随机令牌"
       allow-test-control: false
       confirmation-ttl: "2m"
       default-spray-duration-seconds: 60
       max-duration-seconds: 300
       max-query-range-days: 31
       identities:
         - umo: "真实 UMO"
           sender-id: "真实发送者 ID"
           username: "danglong"
   ```

   `username` 必须是已经存在的平台登录用户名。后端每次操作都会重新检查账号是否存在、
   是否启用、是否仍在设备控制名单中。UMO 与 sender ID 必须同时精确匹配，不支持 `*`。

2. 在 AstrBot WebUI 的本插件设置中填写：

   - `api_base`：同机部署为 `http://127.0.0.1:8185`。
   - `api_token`：复制服务器 `app.astrbot-control.api-token` 的值。
   - `default_station`：当前为 `S01`。
   - `allow_test_command`：默认关闭。

   普通 HTTP 只允许回环地址；Java 后端不在同一主机时必须使用 HTTPS，避免集成令牌明文传输。
   修改 Java YAML 后重启后端；修改 AstrBot 设置后重载插件。

3. 在真实机器人会话中执行 `/agri_control_status`。返回内容包含 UMO、sender ID、映射的平台账号、
   `can_control` 和设备链路状态，但不包含令牌。身份映射不正确时，此命令仍会显示当前消息的本地身份，
   便于管理员修正服务器配置。

本仓库的实际配置已迁移老项目中唯一的一组 AstrBot 身份，并暂时映射到 `danglong`。
集成令牌已在本机服务器配置中生成，但不会写入插件包或版本控制。

## 二次确认

普通开启分成两步：

```text
/agri_start 喷药 5
/agri_confirm 返回的确认编号
```

第一步只读取共享状态并生成确认记录，不发送串口指令。确认必须来自同一 UMO 与 sender ID，
默认 2 分钟过期；如果确认前设备版本被网页或其他机器人更新，本次确认会拒绝，需重新发起。

自然语言工具同样先调用 `start_agri_device`，只有用户随后明确确认，Agent 才能调用
`confirm_agri_device`。把 `PERSONA.md` 的规则加入当前人格，避免模型替用户自动确认。

未指定时长时，喷药按后端下发的默认值运行 60 秒并自动停止；驱虫灯持续开启，直到网页、后端或
AstrBot 明确发送关闭。用户显式指定时长时，喷药和驱虫灯都按该时长运行并自动停止，但不能超过
`max-duration-seconds`。测试命令未指定时长仍固定使用 5 秒，不允许无限测试开启。

停止不需要确认：

```text
/agri_stop 喷药
/agri_stop 驱虫灯
/agri_stop 全部
```

## 测试命令

只有需要明确测试时，才同时打开两处开关：

```yaml
# Java conf/config.yaml
app:
  astrbot-control:
    allow-test-control: true
```

并在 AstrBot 插件设置中启用 `allow_test_command`。然后由授权用户明确发送：

```text
/agri_test 喷药 5
```

测试命令只跳过聊天端二次确认。它仍检查：

- UMO + sender ID 到平台用户名的服务器映射；
- 平台账号存在且启用；
- 用户名在设备控制名单中；
- S01 串口在线、设备版本未冲突、时长不超过上限；
- 同一消息的请求防重复，以及后端定时关闭。

普通指令被拒绝或连接失败时，插件绝不会自动改用测试命令。

## 自动关闭与同步

有限开启的持续时间由 Java 后端计时，不依赖模型等待。定时关闭成功后会更新共享设备版本并广播通知。
如果后端进程在计时期间重启，数据库中的未完成关闭任务会在启动后优先补发停止；如果网页已经执行了
更新的设备操作，版本保护会放弃旧定时任务，避免覆盖新操作。

插件对每次控制先读取最新共享状态，再提交 `expectedRevision`。HTTP 响应丢失时会标记结果未知，
不会自动重复开启。可用 `/agri_result` 查看插件保存的请求结果，并以网页共享状态和现场反馈为准。
当前串口协议没有执行回执，“指令已发送”不代表设备物理状态已经改变。

## 命令与工具

| 命令 | 用途 |
| --- | --- |
| `/agri_control_status` | 查看当前身份映射、权限和设备链路，不操作设备 |
| `/agri_start 喷药 5` | 创建普通开启请求，等待二次确认 |
| `/agri_confirm [确认编号]` | 确认最近或指定的待确认请求 |
| `/agri_stop 喷药` | 立即发送停止指令 |
| `/agri_result [请求UUID]` | 查看本地保存的请求结果 |
| `/agri_test 喷药 5` | 在双开关启用后跳过二次确认执行测试 |
| `/agri_confirm_alert 确认UUID` | 确认一条已送达的病虫害 AstrBot 消息告警 |

对应 LLM 工具为 `start_agri_device`、`confirm_agri_device`、`stop_agri_device`、
`query_agri_control`、`confirm_diagnosis_control` 和 `test_agri_device`。

斜杠命令面向 AstrBot 聊天用户，返回中文操作结果而不是原始 JSON；LLM 工具仍返回结构化 JSON，供 Agent
读取确认编号、状态和安全拦截原因。

## 病虫害识别 AstrBot 消息确认

大屏“设备管理”提供“识别联动消息确认”开关。开关开启时，红色叶害/虫害识别只创建持久化确认单，
不会立即喷药或开灯。Java 后端通过 AstrBot `/api/v1/im/message` 向配置的告警 UMO 推送确认编号；
收到告警的授权用户发送：

```text
/agri_confirm_alert 完整确认UUID
```

也可以在明确回复确认时由 Agent 调用 `confirm_diagnosis_control`。后端会再次检查确认期限、告警接收
UMO、平台账号权限、设备版本和喷药风速；任一条件不满足均不会开启。相同确认编号成功后再次提交
只返回已确认状态，不会重复发送开启指令。

主动告警凭据只配置在 Java 后端 `app.prevention-control` 下，不写进插件：

```yaml
astrbot-alert-enabled: true
astrbot-base-url: "http://127.0.0.1:6185"
astrbot-api-key: "AstrBot WebAPI Key"
alert-umos: ["接收 AstrBot 消息告警的完整 UMO"]
```

喷药安全阈值同样由后端控制。默认要求 2 分钟内的实时风速不超过 3.0 m/s；人工网页或 AstrBot
喷药还必须存在 24 小时内的最新红色叶害识别。喷药运行中收到超阈值风速会立即发送停止指令并同步大屏。
为兼容不同消息平台的文本展示，病虫害确认告警会拆成四条连续的独立消息：摘要、识别与拟开启设备、
确认编号、确认说明。达到设定时长自动关闭、服务重启后的安全关闭，以及风速联锁关闭
或失败，都会向告警 UMO 主动发送一条简短中文设备反馈；直接 `/agri_*` 命令则在当前聊天中立即返回中文结果。

## 验证边界

插件测试全部使用 `httpx.MockTransport` 和 AstrBot API 桩；Java 测试使用 H2 与模拟串口。
不会连接真实 AstrBot、MySQL、Redis 或 COM 设备。安装到实际 AstrBot 后，先运行只读的
`/agri_control_status`，再决定是否进行现场设备测试。

参考：[AstrBot 官方插件配置文档](https://docs.astrbot.app/dev/star/guides/plugin-config.html)、
[AstrBot 官方插件开发文档](https://docs.astrbot.app/dev/star/plugin.html)。
