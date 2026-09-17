# 充电桩平台设备接入网关

这是一个可直接运行和讲解的 Java 21 最小系统，覆盖任务要求中的报文解析、严格协议校验、设备登记校验、字段校验、异常处理、持久化、最新状态查询和历史记录查询。项目没有第三方运行时依赖，IntelliJ IDEA 可直接打开项目根目录或 `pom.xml`。

## 快速开始

在 PowerShell 中执行：

```powershell
Set-ExecutionPolicy -Scope Process Bypass
.\build.ps1
.\run.ps1
```

服务默认监听 `http://localhost:8080`，数据追加写入 `data/reports.tsv`。另开一个 PowerShell 执行：

```powershell
.\demo.ps1
```

也可在 IDEA 中运行 `com.example.gateway.GatewayApplication`。正式自测入口是 `build.ps1`，成功时应显示 `PASS: 12 tests`；在 IDEA 中也可直接运行 `com.example.gateway.AllTests`。

## 接口

- `GET /health`：健康检查。
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
6. 打开项目目录的 PowerShell，执行 `Set-ExecutionPolicy -Scope Process Bypass`，再执行 `.\demo.ps1`。
7. 看到最后一行“演示完成”即表示完整流程通过。

如果提示 8080 端口被占用，可在 IDEA 运行配置的 VM options 中加入 `-Dgateway.port=8081`，并使用 `.\demo.ps1 -BaseUrl http://localhost:8081`。

## 目录

```text
src/main/java      业务源码
src/test/java      12 项无第三方依赖的自测
DESIGN.md          架构、选型、风险和自测说明
build.ps1          编译并运行测试
run.ps1            编译、测试并启动服务
demo.ps1           端到端演示脚本
```
