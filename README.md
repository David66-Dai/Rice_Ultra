# 🌾 数智稻安 — 大数据驱动下基于大模型的水稻农田智能监测预警平台

Monorepo：**React 网页 + Expo 独立 App + Java 后端 + MySQL + Hive 历史库 + Python 推理**。

## 目录结构

```
Smart-Rice-Security/
├── apps/
│   ├── mobile/          # Expo (React Native) 独立 App ★
│   └── web/             # React + Vite 网页端
├── packages/
│   └── shared/          # 前后端共享类型
├── conf/                # Java / Python / Vite 统一 YAML 配置
├── server/              # Spring Boot 业务 API（Java 21）
├── inference/           # FastAPI 叶害/虫害推理服务骨架
└── model_train/         # 训练脚本与权重（leaf / pest）
```

## 环境要求

| 组件    | 版本建议                                                                                                  |
| ------- | --------------------------------------------------------------------------------------------------------- |
| Node.js | ≥ 20（已验证 24）                                                                                        |
| JDK     | 21（已安装 Microsoft OpenJDK，`JAVA_HOME` 指向 `C:\Program Files\Microsoft\jdk-21.0.12.101-hotspot`） |
| Python  | ≥ 3.10（推理服务）                                                                                       |
| MySQL   | 8.x（默认 mysql；无数据库时使用 dev / H2）                                                                |

手机真机调试需安装 **Expo Go**。

## 一键安装前端依赖

在仓库根目录：

```bash
npm install
```

## 统一配置

日常配置集中在 `conf/config.yaml`。已有本机配置已从原 `application*.properties`
迁入；新克隆项目时先复制示例，再填写 MySQL 连接等本机值：

```powershell
Copy-Item conf/config.example.yaml conf/config.yaml
```

`config.yaml` 被 Git 忽略，包含本机凭据；`config.example.yaml` 可提交，不含实际密码。
示例默认关闭串口和初始管理员创建，按需要设置 `app.realtime.serial.enabled`、
`app.auth.bootstrap-admin.*`。已有账号不会因修改初始化配置而改变密码。

| 配置位置                                      | 生效用途                                |
| --------------------------------------------- | --------------------------------------- |
| `server.port`                               | Java API 端口；Vite 代理默认跟随此端口  |
| `web.dev-host / dev-port / api-host`        | Web 开发服务器监听与后端地址            |
| `spring.profiles.default`                   | 默认运行环境，当前为 mysql              |
| mysql 文档的`spring.datasource.*`           | MySQL URL、用户、密码                   |
| `app.auth.*`                                | JWT、令牌有效期、登录锁定、首次管理员   |
| `app.cors.*`                                | Java 允许的前端来源                     |
| `app.realtime.serial.*`                     | 串口启用、COM、波特率和采集站点         |
| `app.realtime.weather.*`                    | 固定农田经纬度，留空沿用 IP 定位        |
| `app.hive.*`                                | HiveServer2 URL、认证与查询/网络超时    |
| `app.dify.*`                                | 新版农业工作流 API 地址、密钥与超时    |
| `app.hdfs.*`                                | WebHDFS 地址、用户名、报告目录与超时  |
| `app.inference.host / port / reload / cors` | Python 推理监听、开发重载、CORS         |
| `app.inference.base-url`                    | Java 转发地址，默认引用上面的 host/port |

YAML 使用 Spring 原生键名，因此实时配置在 `app.realtime`，数据库在
`spring.datasource`。文件中的 `---` 分隔基础配置、mysql、dev 三份文档；
Python 和 Vite 读取首文档，数据库环境由 Spring 选择。不要在 profile 文档里覆盖
Python / Web 配置，也不要在同一文档重复写同名键。

Java 从工作目录向上查找 `conf/config.yaml`，可从根目录、`server/` 或其子目录启动；
Python 和 Vite 默认从源码位置定位。部署到其他目录时，三端都支持：

```powershell
$env:RICE_CONFIG_PATH = 'D:\RiceConfig\config.yaml'
```

相对的 `RICE_CONFIG_PATH` 相对于进程工作目录；通过 npm 启动时会进入相应 workspace，
因此推荐使用绝对路径。配置文件缺失或 YAML 损坏会报错，不会回退到示例凭据。
修改 YAML 后重启相关服务；推理代码的 reload 不代表 YAML 自动重载。

Java 保留 Spring 的环境变量/命令行覆盖。常用环境变量包括
`SPRING_PROFILES_ACTIVE`、`SPRING_DATASOURCE_URL/USERNAME/PASSWORD`、`SERVER_PORT`、
`APP_AUTH_JWT_SECRET`。Python 支持 `APP_INFERENCE_HOST/PORT/RELOAD` 和
`APP_INFERENCE_CORS_ALLOWED_ORIGINS`；Vite 支持 `WEB_DEV_HOST/WEB_DEV_PORT/WEB_API_TARGET`，
并跟随 `SERVER_PORT`。若仅用 Java 命令行修改端口，应同时设置 Vite 的 `WEB_API_TARGET`。
推理绑定 `0.0.0.0` 或部署到远端时，应将 `app.inference.base-url` 改为 Java 可访问的地址。

前端浏览器继续只调用 API；实际 YAML 不会打包，Vite 也禁止通过文件 URL 读取 `conf/`。
`VITE_API_BASE` 是可公开的浏览器 API 地址，仍放在 Web 的 `.env.local` 中。
`pom.xml`、`package.json`、Expo/TypeScript 配置、训练数据集 YAML 和模型元数据仍由各自工具管理。
农业 Dify 分析现已接入，其连接配置由 Java 后端读取。

## 启动

### 1. 独立 App（优先）

```bash
npm run mobile
```

扫码用 Expo Go 打开，或按终端提示开 Android 模拟器。

### 2. 网页端

```bash
npm run web
```

默认 http://localhost:5173 （若本机开着代理打不开，请用 http://127.0.0.1:5173 ，并把 `localhost/127.0.0.1` 加入代理绕过）

局域网给同学访问：本机同时开着网页端和 Java 后端，终端里会打印 `Network: http://192.168.x.x:5173`，把这个地址发给同一 Wi‑Fi 下的朋友即可（不要发 localhost）。改完 CORS / Vite 配置后请**重启** `npm run web` 和 Java 后端。若打不开，在 Windows 防火墙里允许 Node.js 的专用网络入站，或临时允许 5173 端口。

打开即为登录页；开发模式下 `/api` 由 Vite 代理到 `http://127.0.0.1:8185`，无需处理 CORS。后端不在本机时复制 `apps/web/.env.example` 为 `.env.local` 并填写 `VITE_API_BASE`。

### 3. Java 后端

```bash
cd server
.\mvnw.cmd spring-boot:run
```

- 默认使用 **MySQL** 库 `rice_ultra`（连接见 `conf/config.yaml` 的 mysql 文档）
- 健康检查：http://127.0.0.1:8185/api/health
- 首次需执行 `server/sql/init.sql` 建表，再通过初始化配置或账号脚本创建账号
- 没有 MySQL 时改回内存 H2：

```bash
.\mvnw.cmd spring-boot:run "-Dspring-boot.run.profiles=dev"
```

#### 登录与鉴权

系统**不开放注册**。新增账号用脚本写入 MySQL（密码自动做 BCrypt 哈希，库中无明文）：

```bat
cd server
.\create-user.cmd -Username zhangsan -Password "Secret#123" -DisplayName "张三"
```

请用 `.cmd`，不要直接运行 `.ps1`（Windows 默认禁止脚本）。账号已存在时加 `-Update` 可改密码。

首次启动、用户表为空且 `conf/config.yaml` 中 `app.auth.bootstrap-admin.enabled=true`
时，按该配置的 username/password/display-name 创建管理员。示例文件不预置密码。
`init.sql` 只建表，不再插入固定管理员。创建账号工具也读取同一份 YAML，支持
`RICE_CONFIG_PATH`、`SPRING_PROFILES_ACTIVE` 与现有 `CREATE_USER_DB_*` 覆盖。
已有账号用 `create-user.cmd -Update` 改密码。

- 密码：BCrypt 加盐哈希入库（`{bcrypt}` 委托格式，可无痛升级算法），永不存明文
- 访问令牌：HS256 JWT，默认 2 小时；正式环境用环境变量 `APP_AUTH_JWT_SECRET` 固定密钥（≥ 32 字节）
- 记住密码：不保存密码，而是下发 30 天有效、每次使用即轮换、可撤销的持久令牌（库中只存 SHA-256）；令牌被重放视为盗用，自动吊销该用户全部记住登录
- 防爆破：连续 5 次密码错误锁定 15 分钟；账号不存在与密码错误返回同一提示
- 除 `/api/health` 与 `/api/auth/**` 外，所有 `/api/**` 都需要 `Authorization: Bearer <accessToken>`

| 接口                        | 说明                                                                                       |
| --------------------------- | ------------------------------------------------------------------------------------------ |
| `POST /api/auth/login`    | `{username, password, rememberMe}` → `{accessToken, expiresIn, rememberToken?, user}` |
| `POST /api/auth/remember` | `{rememberToken}` → 新 `accessToken` + 轮换后的 `rememberToken`                     |
| `POST /api/auth/logout`   | `{rememberToken?}` 吊销记住登录                                                          |
| `GET /api/auth/me`        | 当前用户信息                                                                               |

设备控制现支持指定用户名授权、跨网页实时状态同步，以及右上角设备操作/病虫害通知。
配置和接口说明见 [设备权限与通知](docs/device-permissions-and-notifications.md)。
AstrBot 适配插件与安装说明见
[integrations/astrbot_plugin_agri_control](integrations/astrbot_plugin_agri_control/README.md)。

#### 历史数据

历史页面通过 Java API 只读查询 Hive 的 `farm.agri_env_data`，不再导入 CSV 或读取
MySQL 的旧历史表。已有本地 CSV 和数据库记录不会被删除；登录和实时采集继续使用 MySQL。

在 `conf/config.yaml` 的 `app.hive` 设置连接：

```yaml
app:
  hive:
    url: "jdbc:hive2://192.168.157.130:10000/farm"
    username: "填写用户名"
    password: "填写密码"
    query-timeout-seconds: 30
    socket-timeout-ms: 45000
```

这些键合并进已有 `app`，不要重复新增同名根节点。配置只存连接信息；表名及字段映射
固定在 `HiveHistoryRepository`，不接受前端传入。也可用 `APP_HIVE_URL`、
`APP_HIVE_USERNAME`、`APP_HIVE_PASSWORD` 覆盖本机值。用户名密码使用普通 HiveServer2
SASL 连接；仅服务端确实是 NOSASL 时才在 URL 中追加 `;auth=noSasl`。

前端 S01–S10 映射为表内 point_1–point_10。`date` 按 YYYY-MM-DD 读取，以北京时间
限定最大查询日期；Hive 仅投影/筛选必要记录，Java 流式读取并计算日期范围。
同站同日存在多行时在 Java 中逐指标求均值，缺失值不参与平均，0 保留。
日期范围通过实际查询计算，不使用可能过期的 Hive 表统计 `numRows`。

| Hive 字段 | 页面数据及单位 |
|-----------|----------------|
| `light_lux` | 日均光照，除以 1000 显示 klx |
| `temperature_celsius / humidity_percent` | 空气温度 °C / 湿度 %RH |
| `wind_speed_m_s` | 风速 m/s |
| `soil_temperature_celsius / soil_moisture_percent` | 土壤温度 °C / 湿度 % |
| `ph` | 土壤 pH |
| `electrical_conductivity_ds_m` | 电导率 mS/cm（与 dS/m 数值相同） |
| `nitrogen_concentration_ppm / phosphorus_concentration_ppm / potassium_concentration_ppm` | 氮磷钾浓度 ppm |

该表不含降雨、病虫害和光谱数据：不展示降雨卡，其他两类显示“暂无归档”。
环境字段为 null 时显示“—”；前一日没有记录时不生成对比值。
Hive 不可用返回明确错误，不回退到模拟数据；凭据只留在服务端。

构建时 Maven 自动获取官方完整 Hive JDBC 驱动，作为 `hive-driver/` 资源打包；
运行时单独加载，避免 Hive/Hadoop 与 Spring 依赖冲突，停止时释放连接和临时驱动文件。
首次构建需允许 Maven 下载依赖；无需手工将 Hive JAR 放入应用类路径。

| 接口                                                     | 说明                                           |
| -------------------------------------------------------- | ---------------------------------------------- |
| `GET /api/history/range?stationId=S01`                 | 查询站点可用日期范围与记录数                   |
| `GET /api/history/daily?stationId=S01&date=2026-09-10` | 查询当天及前一天的 Hive 环境与土壤日均数据 |

当天实时传感器记录单独写入 `realtime_sensor_reading`，不会用假数据填充：

| 接口                                       | 说明                                            |
| ------------------------------------------ | ----------------------------------------------- |
| `GET /api/realtime/today?stationId=S01`  | 查询当天全部分时传感器记录，供 5 个专业图表使用 |
| `GET /api/realtime/latest?stationId=S01` | 查询站点最新一条传感器记录                      |

后端默认以 `COM4 / 9600 / 8N1 / GBK` 持续读取传感器文本帧，过滤前导空字节，
将光照、风速、空气温湿度、土壤 N/P/K、pH、EC 组装为完整记录。降雨量每 5 分钟
从 Open-Meteo 当前小时预报更新；未配置固定经纬度时按服务器公网 IP 定位。
配置项位于 `conf/config.yaml` 的 `app.realtime.*`；dev 模式禁用串口。

### 4. 推理服务（可选）

```bash
pip install -r inference/requirements.txt
npm run inference
```

也可以在 `inference/` 中执行 `python run.py`。启动器读取统一 YAML 的监听端口，
不再通过写死的 `uvicorn --port` 启动。推理接口目前仍是 stub，本次只迁移配置读取。

默认健康检查为 http://127.0.0.1:8001/health 。

诊断转发：`POST /api/diagnosis/leaf|pest`（multipart `file`）→ 推理服务。

### 配置验证（不访问真实设备）

```powershell
npm run test:config --workspace=@smart-rice-security/web
node --experimental-strip-types --test apps/web/tests/history.test.ts
python -m unittest discover -s inference/tests -v
cd server
.\mvnw.cmd clean test
```

Web 配置测试使用 Node.js 22.6+ 的 TypeScript strip-types 支持（本机已验证 Node 24）。
Java 集成测试使用独立测试 YAML 和 H2，禁用串口，并与本机 `conf/config.yaml` 隔离。
默认测试不会连接真实 Hive。需要复查现场连接时，在填写本机凭据后从 server 目录运行
`$env:RICE_HIVE_LIVE_TEST='true'; .\mvnw.cmd '-Dtest=HiveHistoryLiveTests' test`。
此测试只读 Hive，不启动应用上下文、MySQL 连接或串口；结束后移除该环境变量。

### Dify 决策推演

大屏“决策推演”读取所选站点截至目标日最近 7、14 或 30 天的真实 Hive 环境数据。
气象环境、土壤水肥、病虫风险和综合建议由本机 Dify 生成；点击“开始分析”才执行模型调用。
输入保留原始 11 项指标的单位、逐日均值、统计有效天数及缺测日期，生育期可手动补充。
报告正文使用面向种植者的中文自然段，不展示英文、技术字段名或 Markdown 标记；日期和监测数值
保留阿拉伯数字，单位使用中文。综合建议固定包含“方案A：高成本高效率型”和
“方案B：低成本稳定型”，说明各自适用情况、具体措施、相对投入与响应速度。
后端在新报告保存前校验格式，不自动重试模型请求。历史归档保留原文；重新生成后采用新格式。

按当前过渡要求，病虫害继续读取 `farm.pest_data`：优先同日，无则取该站点之前最近记录；
产量沿用旧流程的 `farm.rice_yield` 第一季基线，优先目标年份，否则取之前最近有效年份。
页面和工作流均展示旧数据参考日期、年份，不把过去记录当成本窗口实测。
旧病害率的比例/百分数口径尚未确认，保留原值；虫量采样面积未知，不换算密度。
旧表暂不可用时明确提示，新环境分析仍可使用；没有演示数据回退。

配置位于首文档已有 `app` 下：

```yaml
dify:
  base-url: "http://localhost/v1"
  api-key: "" # 本机已填写；示例不包含密钥
  connect-timeout-seconds: 5
  timeout-seconds: 180
```

完整导入文件和输入契约见 [docs/dify/MIGRATION.md](docs/dify/MIGRATION.md)。
本机已创建并发布独立应用“数智稻安 · 环境与历史参考分析”，不会覆盖旧工作流。
迁到另一台 Dify 时导入 `docs/dify/rice_environment_analysis.yml`、选择已配置模型、发布，
再填写这个新应用的密钥。一个工作流包含四次模型调用；后端校验已发布的 `/parameters`
和四个输出，输入过长不会截断，失败不会自动重复收费请求。

所有接口都需要登录：

| 接口 | 用途 |
|---|---|
| `GET /api/ai/status` | 只返回连接配置是否完整，不暴露配置内容，也不等同于模型在线 |
| `GET /api/ai/evidence?stationId=S01&date=2026-09-11&windowDays=7&growthStage=unknown` | 读取环境及旧源证据，不调用模型 |
| `POST /api/ai/analyses` | 提交同样的四个字段，返回 202 和任务编号 |
| `GET /api/ai/analyses/{id}` | 读取自己的任务状态或 HDFS 中保存的报告 |
| `GET /api/ai/analyses?stationId=S01&generatedDate=2026-09-11` | 按生成日期查询往期报告，日期可省略，返回最新 20 条元数据 |

每次成功生成的报告只保存一个 HDFS JSON 文件：
`/rice/output/point_1/output_20260911_1447.json`。站点目录为 `point_1` 至 `point_10`；
文件名的日期与时间来自**生成完成时刻的北京时间**，精确到分钟，与报告分析的截止日期分别记录。
同一站点同一分钟重名时拒绝覆盖，保留原报告，需下一分钟再生成。先完整上传临时文件，
再改名为最终文件；HDFS 保存失败不会显示“分析成功”，也不会把报告正文退回内存缓存。
报告文件权限为 `766`，站点目录为 `755`，使其他 WebHDFS 用户能够进入目录并读取报告。
上传后显式设置并核验最终文件权限；已有站点目录也会恢复为可进入、可读取的权限。

报告超过文件名所示生成时间 **1 小时**后，显示“已过期”和重新生成提示；恰好 1 小时仍未超过。
过期不会删除 HDFS 文件，也不会阻止查询和阅读。大屏支持按站点、生成日期查询往期报告，
同时显示生成时间与有效期；浏览器保持打开时也会更新过期提示。重新生成需要手动点击，
不会因为查看旧报告自动调用模型。

HDFS 配置在首文档 `app.hdfs`：`web-url`、`user`、`base-path`（默认 `/rice/output`）、
`connect-timeout-seconds`、`timeout-seconds`、`data-node-host-overrides`（默认空）。
使用原项目的 WebHDFS simple 用户认证。文件内包含分析条件、数据证据、四份报告、生成时间、
有效期及用于账号隔离的身份摘要，不包含登录令牌、数据库密码或 Dify 密钥。
前端通过 Java 鉴权接口读取报告；重启后可从 HDFS 列表继续查询。

内存仅保留运行任务的轻量进度及已归档文件定位信息，最多 100 条；它们不包含报告正文。
同一用户相同参数的进行中请求会复用任务，同站点同时只执行一次，全局最多并行两次。
切换页面会停止前端轮询，已经提交的服务端分析可能继续完成。后端重启会中断尚未完成的任务，
已存入 HDFS 的报告不受影响。超时后先检查 Dify 运行记录再决定是否手动重试。

默认 Java 测试隔离外部服务。需要再次做真实 Hive → Dify 模型调用时，在 `server/` 执行：

```powershell
$env:RICE_DIFY_LIVE_TEST = 'true'
.\mvnw.cmd '-Dtest=AiDifyLiveTests' test
Remove-Item Env:RICE_DIFY_LIVE_TEST
```

此显式测试只读 Hive 并调用已配置 Dify，不启动 MySQL 或串口；生成的核对报告在
`server/target/ai-live-result.json`（已忽略，不提交）。前端交互测试：
`node --experimental-strip-types --test apps/web/tests/decision.test.ts`。

需要验证完整 HDFS 保存和重启读取时，可显式运行以下测试。它会调用模型并在指定路径
保留一份真实报告（验证账号独立于日常登录账号），不自动删除：

```powershell
$env:RICE_HDFS_REPORT_LIVE_TEST = 'true'
.\mvnw.cmd '-Dtest=AiHdfsLiveTests' test
Remove-Item Env:RICE_HDFS_REPORT_LIVE_TEST
```

HDFS 写入采用 [Apache WebHDFS 官方协议](https://hadoop.apache.org/docs/current/hadoop-project-dist/hadoop-hdfs/WebHDFS.html)
的两阶段 CREATE 和 RENAME；Java 直接请求 NameNode 与其返回的 DataNode，无需在后端引入 Hadoop 全套依赖。

## 当前状态

- [X] Monorepo 骨架与脚本
- [X] Expo 独立 App 启动页
- [X] React Web 登录页（记住密码 / 自动登录）
- [X] Spring Boot API + CORS + 诊断转发占位
- [X] 账号密码登录、JWT 鉴权、记住登录令牌、账号锁定（MySQL `smart_rice_security`）
- [X] S01–S10 Hive 历史日数据、JWT API 与 Web 时间轴溯源
- [X] 新环境表 + 旧病虫害/产量表 → Dify 四份报告 → 大屏决策推演
- [X] Inference stub
- [ ] 接入 ResNet18 / YOLO 真实推理
- [ ] 站点监测、告警、看板业务

## 图片来源

- `apps/web/src/assets/rice_blast.jpg`：Donald Groth / Louisiana State University AgCenter / USDA Forest Service，来自 Wikimedia Commons，Public Domain。

### Redis 实时同步

后端从 `conf/config.yaml` 的 `spring.data.redis` 读取连接配置（host、port、database、
username、password、connect-timeout、timeout）；该配置仅在服务端使用。
这是 Spring Boot 的[原生 Redis 配置](https://docs.spring.io/spring-boot/3.4/reference/data/nosql.html)。
`app.realtime.redis.enabled=true` 开启写入，`device-id` 对应旧采集器的设备标识。
示例和 dev profile 默认关闭 Redis；本机配置修改后需重启后端。

串口按旧站点协议接收十项指标，以下一条光照帧结束本轮；第十一项钾肥可选，缺失写 null。
保留土壤温湿度，电导率支持旧固件没有冒号的格式。缺帧、乱序、跨站点和超时的轮次会丢弃，
不会把多轮零散数据拼成一条当前记录。原始采样时间取最后一项读数时间。

完整采样在同一 MySQL 事务内保存到 `realtime_sensor_reading` 和 `redis_pending_sample`。
独立任务默认每秒读取最多 100 条待同步记录，写入成功后删除队列行；连接失败会保留记录并重试，
重启后也能继续同步。该机制依赖 MySQL 正常提交，不保证 MySQL 故障期间保存串口数据。

- 最新值：`farm:device:<device-id>:environment:latest`，默认从本轮开始采样起 30 秒有效。
- 历史值：`farm:device:<device-id>:environment:history:<采样微秒时间戳>:<UUID>`，从采样起保留 7 天。
- 两类值都是旧版 13 字段 JSON：`date`、`station` 和 11 项环境指标。站点 `S01` 映射为 `point_1`，
  光照从 klx 转回 lux，电导率 mS/cm 与 dS/m 数值相同，N/P/K 保留原传感器 ppm 数值。
  天气接口的降雨不混入旧归档格式。
- Lua 原子检查顺序及写入，重试复用相同历史键，不刷新已写历史的有效期；过期样本仅补历史，
  不恢复为最新值。超过 7 天的待同步样本按旧保留规则跳过。
- 自动建表使用现有 `ddl-auto=update`；手工维护库结构时需要增加两个土壤字段和上述队列表，
  新库建表见 `server/sql/init.sql`。

验证：`cd server` 后运行 `mvnw.cmd test`。`RedisSensorLiveTests` 默认跳过；显式设置
`RICE_REDIS_LIVE_TEST=true` 后才读取本机 Redis 配置并测试。实测只使用随机测试设备键，结束会清理；
不打开串口、不启动 Web 服务、不修改生产采样键。
