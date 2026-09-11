# 🌾 数智稻安 — 大数据驱动下基于大模型的水稻农田智能监测预警平台

Monorepo：**React 网页 + Expo 独立 App + Java 后端 + MySQL + Python 推理**。

## 目录结构

```
Smart-Rice-Security/
├── apps/
│   ├── mobile/          # Expo (React Native) 独立 App ★
│   └── web/             # React + Vite 网页端
├── packages/
│   └── shared/          # 前后端共享类型
├── server/              # Spring Boot 业务 API（Java 21）
├── inference/           # FastAPI 叶害/虫害推理服务骨架
└── model_train/         # 训练脚本与权重（leaf / pest）
```

## 环境要求

| 组件 | 版本建议 |
|------|----------|
| Node.js | ≥ 20（已验证 24） |
| JDK | 21（已安装 Microsoft OpenJDK，`JAVA_HOME` 指向 `C:\Program Files\Microsoft\jdk-21.0.12.101-hotspot`） |
| Python | ≥ 3.10（推理服务） |
| MySQL | 8.x（正式库；本地默认用 H2 可先不装） |

手机真机调试需安装 **Expo Go**。

## 一键安装前端依赖

在仓库根目录：

```bash
npm install
```

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

打开即为登录页；开发模式下 `/api` 由 Vite 代理到 `http://127.0.0.1:8080`，无需处理 CORS。后端不在本机时复制 `apps/web/.env.example` 为 `.env.local` 并填写 `VITE_API_BASE`。

### 3. Java 后端

```bash
cd server
.\mvnw.cmd spring-boot:run
```

- 默认已切 **MySQL** 库 `smart_rice_security`（账号见 `application-mysql.properties`）
- 健康检查：http://127.0.0.1:8080/api/health
- 首次需执行 `server/sql/init.sql` 建表并写入管理员；之后启动会自动连这张库
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

首次启动且用户表为空时也会自动创建管理员（`init.sql` 中也有同样的 INSERT）：

| 账号 | 密码 | 配置项 |
|------|------|--------|
| `admin` | `SmartRice@2026` | `app.auth.bootstrap-admin.*`（首次登录后请立即修改） |

- 密码：BCrypt 加盐哈希入库（`{bcrypt}` 委托格式，可无痛升级算法），永不存明文
- 访问令牌：HS256 JWT，默认 2 小时；正式环境用环境变量 `APP_AUTH_JWT_SECRET` 固定密钥（≥ 32 字节）
- 记住密码：不保存密码，而是下发 30 天有效、每次使用即轮换、可撤销的持久令牌（库中只存 SHA-256）；令牌被重放视为盗用，自动吊销该用户全部记住登录
- 防爆破：连续 5 次密码错误锁定 15 分钟；账号不存在与密码错误返回同一提示
- 除 `/api/health` 与 `/api/auth/**` 外，所有 `/api/**` 都需要 `Authorization: Bearer <accessToken>`

| 接口 | 说明 |
|------|------|
| `POST /api/auth/login` | `{username, password, rememberMe}` → `{accessToken, expiresIn, rememberToken?, user}` |
| `POST /api/auth/remember` | `{rememberToken}` → 新 `accessToken` + 轮换后的 `rememberToken` |
| `POST /api/auth/logout` | `{rememberToken?}` 吊销记住登录 |
| `GET /api/auth/me` | 当前用户信息 |

#### 历史数据

服务首次连接空库时会从 `server/src/main/resources/data/agri_history_cleaned.csv`
自动批量导入 24,570 条 S01–S10 日数据；已有数据时自动跳过，避免重复写入。

| 接口 | 说明 |
|------|------|
| `GET /api/history/range?stationId=S01` | 查询站点可用日期范围与记录数 |
| `GET /api/history/daily?stationId=S01&date=2026-09-10` | 查询当天及前一天的环境、土壤、病虫害和光谱归档 |

当天实时传感器记录单独写入 `realtime_sensor_reading`，不会用假数据填充：

| 接口 | 说明 |
|------|------|
| `GET /api/realtime/today?stationId=S01` | 查询当天全部分时传感器记录，供 5 个专业图表使用 |
| `GET /api/realtime/latest?stationId=S01` | 查询站点最新一条传感器记录 |

后端默认以 `COM4 / 9600 / 8N1 / GBK` 持续读取传感器文本帧，过滤前导空字节，
将光照、风速、空气温湿度、土壤 N/P/K、pH、EC 组装为完整记录。降雨量每 5 分钟
从 Open-Meteo 当前小时预报更新；未配置固定经纬度时按服务器公网 IP 定位。
配置项位于 `application.properties` 的 `app.realtime.*`。

重新清洗并生成服务端导入文件：

```bash
python scripts/clean_history_data.py --output server/src/main/resources/data/agri_history_cleaned.csv
```

### 4. 推理服务（可选）

```bash
pip install -r inference/requirements.txt
npm run inference
```

http://127.0.0.1:8001/health

诊断转发：`POST /api/diagnosis/leaf|pest`（multipart `file`）→ 推理服务。

## 当前状态

- [x] Monorepo 骨架与脚本
- [x] Expo 独立 App 启动页
- [x] React Web 登录页（记住密码 / 自动登录）
- [x] Spring Boot API + CORS + 诊断转发占位
- [x] 账号密码登录、JWT 鉴权、记住登录令牌、账号锁定（MySQL `smart_rice_security`）
- [x] S01–S10 历史日数据入库、JWT API 与 Web 时间轴溯源
- [x] Inference stub
- [ ] 接入 ResNet18 / YOLO 真实推理
- [ ] 站点监测、告警、看板业务

## 图片来源

- `apps/web/src/assets/rice_blast.jpg`：Donald Groth / Louisiana State University AgCenter / USDA Forest Service，来自 Wikimedia Commons，Public Domain。
