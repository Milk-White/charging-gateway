# 充电桩平台设备接入网关

这是一个可直接运行和讲解的 Java 21 充电桩设备接入网关，完整支持功能码 101 登录、102 心跳和 103 实时数据推送。系统包含登录会话、枪与点位通用数据模型、周期与变化推送、失败重试、HTTP 调试通道、可选 MQTT 5.0 接入、双层持久化、查询接口和中文监控大屏。项目没有第三方运行时依赖，IntelliJ IDEA 可直接打开项目根目录或 `pom.xml`。

## 快速开始

在 PowerShell 中执行：

```powershell
Set-ExecutionPolicy -Scope Process Bypass
.\build.ps1
.\run.ps1
```

服务默认监听 `http://localhost:8080`，数据追加写入 `data/reports.tsv`。启动后直接用浏览器打开 `http://localhost:8080/`，即可使用内置中文监控大屏。另开一个 PowerShell 执行：

```powershell
.\demo.ps1
```

也可在 IDEA 中运行 `com.example.gateway.GatewayApplication`。正式自测入口是 `build.ps1`，成功时应显示 `PASS: 24 tests`；在 IDEA 中也可直接运行 `com.example.gateway.AllTests`。

服务启动后执行 `protocol-demo.ps1`，可一次验证未登录拦截、101 登录、102 心跳以及 PUBLIC、REALTIME、IDLE、CHARGING、SPECIAL 五类 103 推送。执行 `device-simulator.ps1` 会持续运行设备侧调度器，执行登录校时、30 秒心跳、文档规定的周期/变化推送及失败重试。

## 监控大屏

浏览器打开 `http://localhost:8080/` 后，可直接完成：

- 查看网关健康状态和已登记设备清单。
- 切换 `pile001`、`pile002`、`pile003`，查看最新状态、接收时间、协议时间和帧序号。
- 查看电压、电流趋势图及最近 50 条历史记录。
- 在网页中填写状态、电压、电流和故障码，发送模拟设备上报。
- 每 5 秒自动刷新，也可以手动刷新。

页面由 Java 服务直接提供，HTML、CSS 和 JavaScript 均在项目内，无需安装 Node.js，也不依赖外部 CDN。网页模拟上报仍然先编码为正式二进制协议帧，再经过解码、验签、业务校验和持久化。

## 接口

- `GET /health`：健康检查。
- `GET /`：打开中文设备监控大屏。
- `GET /api/devices`：查询已登记设备编号清单。
- `POST /api/simulator/report`：使用 JSON 模拟设备上报，服务内部先编码为 Protobuf wire frame，再按正式接入链路解码、验签、校验和落库。
- `POST /api/reports`：接收 Base64 编码的二进制协议帧；请求体可直接为 Base64 文本，也可为 `{"frameBase64":"..."}`。
- `GET /api/devices/{sn}/latest`：查询设备最新状态。
- `GET /api/devices/{sn}/history?limit=100`：查询设备历史上报，最新记录在前，`limit` 范围为 1 到 1000。
- `POST /api/protocol/{sn}`：正式协议调试入口；请求和响应均为 Base64 编码的 Protobuf wire 帧，支持 101、102、103。
- `GET /api/sessions`：查询 5 分钟活动窗口内的设备登录会话。

正式生产传输可启用 MQTT 5.0：网关订阅 `charging/tocloud/+/protobuf/general`，并把同序号、同功能码响应发布到 `charging/todev/{sn}/protobuf/general`。MQTT 载荷为原始 Protobuf wire 字节，QoS 为 0。

模拟上报示例：

```json
{
  "deviceSn": "pile001",
  "status": "CHARGING",
  "voltage": 380.5,
  "current": 32.25,
  "faultCode": ""
}
```

默认登记设备为 `pile001`、`pile002`、`pile003`。状态值为 `IDLE`、`CHARGING`、`FAULT`、`OFFLINE`。当状态为 `FAULT` 时必须提供协议已定义范围内的故障码，例如 `3001`；未登记 SN、未定义故障码、非法 JSON 和畸形 Protobuf 都会被拒绝。

## 可配置项

通过 JVM 系统属性配置：

```text
-Dgateway.port=8080
-Dgateway.data=data
-Dgateway.secret=demo-secret
-Dgateway.credentials=pile001:pwd001,pile002:pwd002,pile003:pwd003
-Dgateway.mqtt.enabled=false
-Dgateway.mqtt.host=localhost
-Dgateway.mqtt.port=1883
-Dgateway.mqtt.clientId=charging-gateway
```

生产部署必须通过安全配置中心或环境注入替换默认密钥，不能把真实密钥提交到 Git。

## IDEA 新手运行

1. 在 IDEA 选择“打开”，选中本项目的 `pom.xml`。
2. 等待右下角索引完成，并确认项目 SDK 是 Java 21。
3. 打开 `src/main/java/com/example/gateway/GatewayApplication.java`。
4. 点击 `main` 方法左侧绿色三角，再点“运行 GatewayApplication.main()”。
5. 控制台看到 `Charging gateway started at http://localhost:8080` 后，不要关闭运行窗口。
6. 浏览器打开 `http://localhost:8080/`，先演示设备切换、趋势图和网页模拟上报。
7. 如需演示脚本，再打开项目目录的 PowerShell，执行 `Set-ExecutionPolicy -Scope Process Bypass`，然后执行 `.\demo.ps1`。
8. 网页上报成功或脚本出现“演示完成”，均表示完整链路通过。

如果提示 8080 端口被占用，可在 IDEA 运行配置的 VM options 中加入 `-Dgateway.port=8081`，并使用 `.\demo.ps1 -BaseUrl http://localhost:8081`。

## 目录

### 文件与职责

| 文件 | 负责的功能 |
| --- | --- |
| `GatewayApplication.java` | 读取端口、密钥、设备凭据和 MQTT 配置，装配并启动组件 |
| `GatewayHttpServer.java` | HTTP 路由、网页、Base64 协议入口、会话和查询接口 |
| `ChargingProtocolCodec.java` | 101/102/103 外层帧、MD5、登录数据和正式响应 |
| `ChargingValueCodec.java` | 103 枪列表、点位列表以及五种 oneof 值 |
| `DeviceSessionService.java` | 用户名密码校验、登录态、最后活动时间和 5 分钟失效 |
| `DeviceProtocolService.java` | 功能码分派、登录前置约束、响应码和双层持久化 |
| `PushSchedulePolicy.java` | 周期推送、变化推送、登录超时与失败重试时间 |
| `DeviceSimulatorApplication.java` | 登录校时、心跳、连续推送和重试 |
| `Mqtt5GatewayAdapter.java` | MQTT 5.0 CONNECT、SUBSCRIBE、QoS 0 PUBLISH、PING 和重连 |
| `FileRealtimePushRepository.java` | 完整 103 枪/点位载荷保存到 `realtime-pushes.tsv` |
| `FileReportRepository.java` | 网页摘要保存到 `reports.tsv` 并维护查询索引 |
| `dashboard.html` | 登录状态、设备状态、趋势图、历史表和快速模拟上报 |

### 小白源码阅读顺序

核心链路文件已经按“每个关键字段、判断、循环和调用”补充中文教学注释。建议不要同时打开全部文件，按下面顺序阅读：

1. `GatewayApplication.java`：先看系统如何把各层组件装配起来。
2. `GatewayHttpServer.java`：看 HTTP 请求如何进入项目，又如何返回响应。
3. `ChargingProtocolCodec.java`：看 101/102/103 公共帧、签名和正式响应。
4. `ProtoWire.java`：看每个二进制字段如何写入和读取。
5. `ChargingValueCodec.java`：看 103 的枪、点位及五种 oneof 值。
6. `DeviceSessionService.java`：看密码校验、登录态、心跳刷新和 5 分钟超时。
7. `DeviceProtocolService.java`：看三个功能码怎样分派并协调校验与双重存储。
8. `ReportService.java`：看设备白名单、数值、故障码和时间窗口校验。
9. `FileRealtimePushRepository.java` 与 `FileReportRepository.java`：看两份 TSV 怎样写入和查询。
10. `Mqtt5GatewayAdapter.java`：最后看同一套协议怎样通过 MQTT 5.0 收发。

注释重点解释“为什么这样写”和“这一行对前后流程有什么影响”；`package`、`import`、单独的花括号等 Java 语法行不重复添加无意义注释。

### PowerShell 脚本说明

| 脚本 | 作用 | 使用条件 |
| --- | --- | --- |
| `build.ps1` | 清理旧编译结果，编译正式代码和测试代码，复制网页资源并运行 24 项测试 | 可单独运行，不启动网关 |
| `run.ps1` | 先运行 `build.ps1`，测试通过后启动 `GatewayApplication` | 不使用 IDEA 启动时运行，并保持窗口打开 |
| `demo.ps1` | 演示健康检查、网页模拟上报、最新查询、历史查询和非法设备拦截 | 必须先启动网关 |
| `protocol-demo.ps1` | 演示未登录拦截、101 登录、102 心跳和五类 103 | 必须先启动网关，正式汇报优先使用 |
| `device-simulator.ps1` | 持续模拟登录校时、心跳、周期推送和失败重试 | 必须先启动网关，按 `Ctrl+C` 停止 |

如果已经在 IDEA 中运行 `GatewayApplication.main()`，不要再执行 `run.ps1`，否则两个网关会同时抢占 8080 端口。推荐做法是：IDEA 启动网关，PowerShell 执行 `protocol-demo.ps1`，最后打开 `http://localhost:8080/` 查看页面结果。

```text
src/main/java      业务源码
src/main/resources 内置监控大屏页面
src/test/java      24 项无第三方依赖的自测
DESIGN.md          架构、选型、风险和自测说明
build.ps1          编译并运行测试
run.ps1            编译、测试并启动服务
demo.ps1           端到端演示脚本
protocol-demo.ps1  101/102/五类 103 一次性演示
device-simulator.ps1 按协议周期持续运行设备模拟器
```
