# 充电桩平台设备接入网关设计说明

## 一 目标与范围

本系统实现登录 101、心跳 102 和实时数据推送 103 的完整可运行链路。设备先用用户名密码登录，平台建立 5 分钟活动会话；未登录设备不能执行心跳或实时推送。103 使用“充电枪列表—点位列表—oneof 值”通用结构，支持公共、实时、闲时、充电中和特殊时点五类数据，并提供周期推送、变化推送、失败重试、完整载荷持久化、摘要查询和可视化。HTTP 用于无 Broker 的本地演示，MQTT 5.0 用于真实主题收发，两者复用同一协议和业务服务。

## 二 架构

```text
设备模拟器或 MQTT 5.0 设备
          |
          v
HTTP 调试通道或 Mqtt5GatewayAdapter
          |
          v
DeviceProtocolService  101/102/103 路由
          |
          v
ChargingProtocolCodec + ChargingValueCodec
          |
          v
DeviceSessionService + ReportService
          |
          v
完整数据仓储 + 摘要仓储 + 监控大屏
```

代码按 `http`、`transport/mqtt`、`protocol`、`service`、`repository`、`simulator` 和 `domain` 分层。HTTP 与 MQTT 5.0 只负责传输，统一调用 `DeviceProtocolService`；协议层处理外层帧和点位结构；会话服务管理登录态；调度器实现设备侧周期与重试；仓储层同时保存完整载荷和网页摘要。

## 三 内置监控大屏

`GET /` 返回项目内置的单页监控界面，页面资源位于 `src/main/resources/web/dashboard.html`。它不依赖外部 CDN 或前端构建工具，由 Java 服务与 API 同源提供，因此没有跨域配置和额外部署步骤。页面通过 `GET /api/devices` 获取登记设备清单，通过 latest 和 history 接口展示最新遥测、历史表格及电压电流趋势，通过 simulator 接口完成网页模拟上报。

页面提供服务健康指示、设备切换、状态卡片、趋势图、历史记录、模拟上报和运行日志，并默认每 5 秒刷新。响应设置禁止缓存和内容类型保护；页面采用严格的同源资源策略，只允许当前服务的脚本、样式和接口连接。

## 四 协议实现

协议头使用附件定义的字段：帧序号、功能码、UTC 秒级时间戳、请求或响应标志、结果信息、业务数据和签名。101 登录请求使用 `UserName=1`、`Pwd=2`；文档示例把两个字段都写成 12345，无法形成合法 proto，因此在实现中修正。102 不带业务数据。103 使用枪列表、点位列表和 oneof 值，并携带数据类别区分五种场景。每个请求均返回同帧序号、同功能码的正式响应帧。签名按附件要求计算为 `MD5(密钥 + "_" + 时间戳)`。

附件中的签名字段编号文本不是合法 Protobuf 字段号，本实现将签名字段约定为 11，并在编码和解码两端保持一致。103 点位支持 bool、int32、float、double 和 string；完整载荷写入 `realtime-pushes.tsv`，其中常用状态、电压、电流和故障码另存为 `reports.tsv`，供最新状态、历史记录和趋势图快速查询。

## 五 字段与异常校验

- 单帧不超过 65535 字节。
- 设备 SN 为 1 到 32 位 ASCII 字母或数字，并且必须出现在 `gateway.credentials` 凭据清单中。
- 登录用户名必须等于主题或路径中的设备 SN，密码采用常量时间比较；5 分钟无消息后会话失效。
- 102 和 103 只有在登录成功后才允许处理，任何合法消息都会刷新最后活动时间。
- 状态仅允许 IDLE、CHARGING、FAULT、OFFLINE。
- 电压和电流必须为有限数值，范围均为 0 到 1000。
- 故障码为空或属于协议定义的 1xxx、3xxx、4xxx、5xxx 范围；FAULT 状态必须带故障码。
- 协议时间戳允许最多落后服务器 5 分钟或领先 1 分钟。
- 功能码错误、wire type 错误、畸形长度、响应帧冒充上报、签名错误、字段缺失、非法 JSON、越界数据和无效 SN 均返回结构化 400 响应，不写入存储。
- 未找到设备最新状态时返回 404；方法不匹配时返回 405。

## 六 存储选型

系统使用两份 UTF-8 TSV 追加日志。`realtime-pushes.tsv` 无损保存完整枪/点位载荷的 Base64，`reports.tsv` 保存网页查询需要的八列摘要，并在启动时恢复为按设备分组的内存索引。追加写不会覆盖既有记录。

这一方案适合单实例和小数据量演示，不适合大规模生产。生产环境建议把 `ReportRepository` 替换为时序数据库或关系数据库实现，引入唯一消息键 `(deviceSn, packageSequence, protocolTimestamp)` 实现幂等，并使用事务或消息队列保证写入一致性。

## 七 选型理由

选择 Java 21 是因为本机已配置该版本，并可使用虚拟线程为每个 HTTP 请求提供简单的并发模型。选择 JDK 内置 `HttpServer` 和手写最小 Protobuf wire codec，是为了在没有 Maven、Gradle和网络依赖下载的机器上仍能完整编译、测试和演示。项目同时提供标准 `pom.xml`，便于 IDEA 识别目录结构，后续也可平滑迁移到 Spring Boot、Eclipse Paho MQTT 和 protobuf-java。

## 八 风险与改进

- MQTT 适配：已实现 MQTT 5.0 QoS 0 的 CONNECT、订阅、发布、PING 和自动重连。生产环境仍需补充 TLS、Broker 认证、共享订阅和集群消费。
- MD5 强度：附件要求 MD5，因此当前按协议实现。若协议允许升级，应迁移到 HMAC-SHA256，并提供密钥轮换。
- 重放攻击：当前使用时间窗口降低重放风险；生产版还应持久化最近帧序号并按设备去重。
- 文件增长：TSV 会持续增长。生产版需分区、归档、保留策略和容量告警。
- 单实例：内存索引不跨实例共享。生产版需使用外部数据库和分布式消息基础设施。
- 可观测性：生产版应增加结构化日志、指标、链路追踪和死信队列。
- 协议来源：附件只有伪代码和截图，没有可编译 `.proto`。本实现按页面字段建立 wire 映射；拿到正式 `.proto` 后应生成 Java 类并做互操作测试。

## 九 自测

`AllTests` 提供 24 项自动化检查，除原有边界外新增覆盖：

1. 报文编码和解码往返，验证 SN、状态、电压等字段。
2. 篡改签名后拒绝报文。
3. 两条上报落库后，验证最新状态、历史数量和重启加载。
4. 无效格式 SN、未登记 SN、未定义故障码和 FAULT 缺失故障码时拒绝入库。
5. 错误 wire type、超大长度和缺失帧序号时拒绝报文。
6. 严格 JSON 解析，拒绝非 JSON、尾随内容和重复字段。
7. HTTP 请求体上限覆盖 65535 字节协议帧的 Base64 膨胀。
8. 监控大屏资源可加载，包含趋势图、设备接口和网页模拟上报功能。
9. 登记设备清单可序列化为合法 JSON，页面不依赖远程资源。
10. 101 登录成功、错误密码失败和正式响应帧序号一致。
11. 未登录 103 拦截、102 心跳刷新会话、5 分钟无消息失效。
12. 完整 103 枪/点位保存、摘要生成和五种 oneof 类型往返。
13. 实时、闲时、充电中变化与周期推送间隔及重试规则。
14. MQTT 上行主题正确提取设备 SN。

执行 `build.ps1` 成功时输出 `PASS: 24 tests`。`protocol-demo.ps1` 一次验证未登录拦截、101、102 和五类 103；`device-simulator.ps1` 持续执行登录校时、30 秒心跳、周期推送、变化推送和失败重试。

## 十 文件职责对照

| 文件 | 实现内容 |
| --- | --- |
| `GatewayApplication.java` | 配置读取、依赖装配、HTTP 与可选 MQTT 启动 |
| `ChargingProtocolCodec.java` | 101/102/103 外层帧、登录数据、MD5 和正式响应 |
| `ChargingValueCodec.java` | 103 枪、点位和五种 oneof 值 |
| `DeviceSessionService.java` | 凭据校验、登录态、心跳刷新和 5 分钟超时 |
| `DeviceProtocolService.java` | 功能码路由、登录前置约束、响应码和双重持久化 |
| `PushSchedulePolicy.java` | 文档规定的周期、变化和重试时间 |
| `DeviceSimulatorApplication.java` | 登录校时、心跳、连续周期推送和重试 |
| `Mqtt5GatewayAdapter.java` | MQTT 5.0 QoS 0 上下行主题、保活和重连 |
| `FileRealtimePushRepository.java` | 完整 103 原始点位数据持久化 |
| `FileReportRepository.java` | 最新状态和历史查询用摘要持久化 |
| `GatewayHttpServer.java` | HTTP 演示入口、会话、查询接口和监控页面 |
| `dashboard.html` | 网关健康、登录设备、状态、趋势和历史可视化 |

## 十一 汇报建议

演示时先运行 `run.ps1`，再执行 `protocol-demo.ps1` 展示 101、102、五类 103 和未登录拦截，最后打开监控大屏查看登录状态、趋势和历史。需要展示周期规则时运行 `device-simulator.ps1`。讲解顺序建议为：登录会话、正式响应、点位模型、调度重试、双重存储、MQTT 适配、自动测试和生产化风险。
