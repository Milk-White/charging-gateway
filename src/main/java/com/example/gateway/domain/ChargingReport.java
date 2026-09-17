package com.example.gateway.domain;

/**
 * 一次充电桩实时上报的领域对象。
 *
 * <p>用途：在协议层、业务层和存储层之间传递已经结构化的数据，避免各层直接依赖
 * HTTP 请求或二进制报文。</p>
 *
 * @param deviceSn 设备唯一编号
 * @param status 充电桩当前状态
 * @param voltage 电压，单位 V
 * @param current 电流，单位 A
 * @param faultCode 四位十六进制故障码；非故障状态允许为空
 * @param protocolTimestamp 设备协议中的秒级时间戳
 * @param packageSequence 报文序号
 * @param receivedAt 网关成功解析报文的毫秒级时间戳
 */
public record ChargingReport(
        String deviceSn,
        ChargingStatus status,
        double voltage,
        double current,
        String faultCode,
        long protocolTimestamp,
        int packageSequence,
        long receivedAt
) {
}
