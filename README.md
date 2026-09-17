# 充电桩平台设备接入网关

这是一个可直接运行和讲解的 Java 21 最小系统，覆盖任务要求中的报文解析、严格协议校验、设备登记校验、字段校验、异常处理、持久化、最新状态查询和历史记录查询。项目没有第三方运行时依赖，IntelliJ IDEA 可直接打开项目根目录或 `pom.xml`。

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

也可在 IDEA 中运行 `com.example.gateway.GatewayApplication`。正式自测入口是 `build.ps1`，成功时应显示 `PASS: 15 tests`；在 IDEA 中也可直接运行 `com.example.gateway.AllTests`。

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
-Dgateway.devices=pile001,pile002,pile003
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

```text
src/main/java      业务源码
src/main/resources 内置监控大屏页面
src/test/java      15 项无第三方依赖的自测
DESIGN.md          架构、选型、风险和自测说明
build.ps1          编译并运行测试
run.ps1            编译、测试并启动服务
demo.ps1           端到端演示脚本
```
