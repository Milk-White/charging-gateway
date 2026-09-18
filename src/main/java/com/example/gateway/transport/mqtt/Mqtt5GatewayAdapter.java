package com.example.gateway.transport.mqtt;

import com.example.gateway.service.DeviceProtocolService;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.EOFException;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.net.SocketTimeoutException;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Arrays;

/**
 * 无第三方依赖的 MQTT 5.0 QoS 0 适配器。订阅设备上行主题，并把协议响应发布到下行主题。
 */
public final class Mqtt5GatewayAdapter implements AutoCloseable {
    // + 是 MQTT 单层通配符，用来同时订阅所有设备 SN 的上行主题。
    public static final String UP_TOPIC = "charging/tocloud/+/protobuf/general";
    // Broker 主机名或 IP。
    private final String host;
    // Broker TCP 端口，普通 MQTT 默认 1883。
    private final int port;
    // 当前网关在 Broker 上使用的客户端标识。
    private final String clientId;
    // 收到 PUBLISH 后调用统一 101/102/103 协议服务。
    private final DeviceProtocolService protocolService;
    // volatile 保证关闭线程修改 running 后，重连虚拟线程能立即看到。
    private volatile boolean running;
    // 保存当前 Socket，close() 可以从另一个线程主动关闭它。
    private volatile Socket socket;

    public Mqtt5GatewayAdapter(String host, int port, String clientId,
                               DeviceProtocolService protocolService) {
        // 保存 Broker 主机。
        this.host = host;
        // 保存 Broker 端口。
        this.port = port;
        // 保存 MQTT clientId。
        this.clientId = clientId;
        // 保存协议处理服务。
        this.protocolService = protocolService;
    }

    public void start() {
        // 已经启动时直接返回，避免创建两个重复订阅线程。
        if (running) {
            return;
        }
        // 先设置运行标志，再启动后台线程。
        running = true;
        // 使用 Java 21 虚拟线程执行长时间连接和自动重连。
        Thread.ofVirtual().name("mqtt5-gateway-adapter").start(this::reconnectLoop);
    }

    private void reconnectLoop() {
        // 只要没有调用 close()，连接断开后就会再次进入循环重连。
        while (running) {
            try {
                // 建立连接、订阅并持续消费；只有断线或异常才会返回。
                connectAndConsume();
            } catch (Exception exception) {
                // 主动 close 时不再打印错误，也不再等待重连。
                if (running) {
                    System.err.println("MQTT 5.0 connection failed: " + exception.getMessage());
                    // 连接失败后等待 5 秒，避免快速循环压垮 Broker 或日志。
                    sleep(Duration.ofSeconds(5));
                }
            } finally {
                // 无论正常退出还是异常都关闭旧 Socket，为下一轮重连清理资源。
                closeSocket();
            }
        }
    }

    private void connectAndConsume() throws Exception {
        // 创建尚未连接的 TCP Socket。
        Socket current = new Socket();
        // 在 5 秒内连接 Broker，超时会抛异常进入重连流程。
        current.connect(new InetSocketAddress(host, port), 5_000);
        // 每次读最多阻塞 1 秒，超时后有机会发送 MQTT PINGREQ。
        current.setSoTimeout(1_000);
        // 保存当前连接，close() 方法才能主动中断阻塞读取。
        socket = current;
        // DataInputStream 方便按无符号字节和固定长度读取 MQTT 包。
        DataInputStream input = new DataInputStream(current.getInputStream());
        // DataOutputStream 方便写包头、长度和正文。
        DataOutputStream output = new DataOutputStream(current.getOutputStream());
        // 固定头 0x10 表示 CONNECT，正文由 connectBody 生成。
        writePacket(output, 0x10, connectBody());
        // Broker 应立即返回 CONNACK。
        Packet connAck = readPacket(input);
        // 包类型必须是 2，正文至少 3 字节，原因码 0 才表示连接成功。
        if ((connAck.header() >>> 4) != 2 || connAck.body().length < 3 || connAck.body()[1] != 0) {
            throw new IOException("Broker rejected MQTT 5.0 CONNECT");
        }
        // 固定头 0x82 表示符合规范标志位的 SUBSCRIBE，包标识使用 1。
        writePacket(output, 0x82, subscribeBody(1, UP_TOPIC));
        // 读取订阅确认包。
        Packet subAck = readPacket(input);
        // SUBACK 的包类型是 9；其他类型说明协议流程错乱。
        if ((subAck.header() >>> 4) != 9) {
            throw new IOException("Expected MQTT SUBACK");
        }
        // 输出成功信息，演示时可以确认主题订阅已经完成。
        System.out.println("MQTT 5.0 connected: " + host + ":" + port + " topic=" + UP_TOPIC);
        // 记录最近一次网络活动时间，用于判断何时发送保活包。
        long lastNetwork = System.currentTimeMillis();
        // 适配器仍运行且当前 Socket 未关闭时持续读取 MQTT 包。
        while (running && !current.isClosed()) {
            try {
                // 读取一个完整 MQTT 控制包。
                Packet packet = readPacket(input);
                // 任何入站包都说明连接仍活跃，刷新时间。
                lastNetwork = System.currentTimeMillis();
                // 固定头高 4 位是 MQTT 控制包类型。
                int type = packet.header() >>> 4;
                // 类型 3 是 Broker 转发来的 PUBLISH。
                if (type == 3) {
                    handlePublish(packet, output);
                } else if (type == 13) {
                    // 类型 13 是 PINGRESP，收到即可，无需业务处理。
                } else if (type == 14) {
                    // 类型 14 是 Broker 主动断开，抛异常进入重连流程。
                    throw new EOFException("Broker sent DISCONNECT");
                }
            } catch (SocketTimeoutException timeout) {
                // 1 秒内没收到数据并不代表断线；累计 30 秒无活动才发送 PINGREQ。
                if (System.currentTimeMillis() - lastNetwork >= 30_000) {
                    // 固定头 0xC0 表示 PINGREQ，它没有正文。
                    writePacket(output, 0xC0, new byte[0]);
                    // 发送 PING 也算一次网络活动，避免每秒重复发送。
                    lastNetwork = System.currentTimeMillis();
                }
            }
        }
    }

    private void handlePublish(Packet packet, DataOutputStream output) throws Exception {
        // PUBLISH 固定头第 1、2 位组成 QoS 数字。
        int qos = (packet.header() >>> 1) & 3;
        // 工作任务只要求 QoS 0；QoS 1/2 还需要 PUBACK/PUBREC 状态机，本实现明确拒绝。
        if (qos != 0) {
            throw new IOException("Only MQTT QoS 0 is supported by the task protocol");
        }
        // 用输入流解析 PUBLISH 正文。
        DataInputStream body = new DataInputStream(new ByteArrayInputStream(packet.body()));
        // PUBLISH 正文第一项是 UTF-8 主题名。
        String topic = readUtf8(body);
        // MQTT 5 在主题后增加属性长度。
        int propertyLength = readVariableByteInteger(body);
        // 当前项目不使用 PUBLISH 属性，但必须按长度跳过才能对齐 payload。
        body.skipNBytes(propertyLength);
        // 剩余全部字节就是设备发送的 101/102/103 原始协议帧。
        byte[] payload = body.readAllBytes();
        // 从 charging/tocloud/{sn}/protobuf/general 中提取设备 SN。
        String deviceSn = extractDeviceSn(topic);
        // 调用与 HTTP 完全相同的协议服务，取得二进制响应帧。
        byte[] response = protocolService.handle(deviceSn, payload).responseFrame();
        // 把响应发布到该设备专属的下行主题。
        publish(output, "charging/todev/" + deviceSn + "/protobuf/general", response);
    }

    private static void publish(DataOutputStream output, String topic, byte[] payload) throws IOException {
        // 先在内存中构造 PUBLISH 可变头和载荷。
        ByteArrayOutputStream bodyBytes = new ByteArrayOutputStream();
        DataOutputStream body = new DataOutputStream(bodyBytes);
        // 写入下行主题。
        writeUtf8(body, topic);
        // MQTT 5 属性长度为 0，表示没有额外属性。
        body.writeByte(0); // MQTT 5 PUBLISH properties length
        // 追加网关生成的协议响应帧。
        body.write(payload);
        // 0x30 表示 QoS 0、非保留、非重复的 PUBLISH。
        writePacket(output, 0x30, bodyBytes.toByteArray());
    }

    public static String extractDeviceSn(String topic) {
        // 按斜杠拆分标准主题。
        String[] parts = topic.split("/");
        // 严格检查固定片段、总段数以及设备 SN 非空。
        if (parts.length != 5 || !"charging".equals(parts[0])
                || !"tocloud".equals(parts[1]) || !"protobuf".equals(parts[3])
                || !"general".equals(parts[4]) || parts[2].isBlank()) {
            throw new IllegalArgumentException("Unexpected charging topic: " + topic);
        }
        // 第三段就是设备 SN。
        return parts[2];
    }

    private byte[] connectBody() throws IOException {
        // 在内存中构造 CONNECT 正文。
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        DataOutputStream out = new DataOutputStream(bytes);
        // 协议名固定为 MQTT。
        writeUtf8(out, "MQTT");
        // 协议级别 5 代表 MQTT 5.0。
        out.writeByte(5);      // MQTT 5.0
        // 0x02 只设置 Clean Start，不带用户名、密码、遗嘱等标志。
        out.writeByte(0x02);   // clean start
        // Keep Alive 为 60 秒；本实现 30 秒无活动就主动 PING。
        out.writeShort(60);    // keep alive
        // CONNECT 属性长度为 0。
        out.writeByte(0);      // CONNECT properties length
        // 最后写客户端标识。
        writeUtf8(out, clientId);
        // 返回完整 CONNECT 正文字节。
        return bytes.toByteArray();
    }

    private static byte[] subscribeBody(int packetIdentifier, String topicFilter) throws IOException {
        // 在内存中构造 SUBSCRIBE 正文。
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        DataOutputStream out = new DataOutputStream(bytes);
        // 包标识用于把 SUBACK 和本次 SUBSCRIBE 对应起来。
        out.writeShort(packetIdentifier);
        // SUBSCRIBE 属性长度为 0。
        out.writeByte(0); // SUBSCRIBE properties length
        // 写入带 + 通配符的上行主题过滤器。
        writeUtf8(out, topicFilter);
        // 订阅选项 0 表示 QoS 0，其他标志均关闭。
        out.writeByte(0); // QoS 0, no-local false, retain flags false
        // 返回 SUBSCRIBE 正文。
        return bytes.toByteArray();
    }

    private static void writePacket(DataOutputStream output, int header, byte[] body) throws IOException {
        // MQTT 固定头第一个字节包含包类型和标志。
        output.writeByte(header);
        // Remaining Length 使用 MQTT Variable Byte Integer 编码。
        writeVariableByteInteger(output, body.length);
        // 长度后写入包正文。
        output.write(body);
        // 立即刷新 Socket 输出流，避免响应停留在缓冲区。
        output.flush();
    }

    private static Packet readPacket(DataInputStream input) throws IOException {
        // 先读取固定头第一个无符号字节。
        int header = input.readUnsignedByte();
        // 再读取 Remaining Length。
        int length = readVariableByteInteger(input);
        // 本演示限制单个 MQTT 包最多 1 MiB，避免异常包耗尽内存。
        if (length > 1_048_576) {
            throw new IOException("MQTT packet is too large");
        }
        // 按声明长度读取正文。
        byte[] body = input.readNBytes(length);
        // TCP 提前断开时 readNBytes 可能返回不足长度，必须明确报截断。
        if (body.length != length) {
            throw new EOFException("Truncated MQTT packet");
        }
        // 返回固定头和正文组成的内部不可变记录。
        return new Packet(header, body);
    }

    private static void writeVariableByteInteger(DataOutputStream output, int value) throws IOException {
        do {
            // 每个字节低 7 位保存当前余数。
            int digit = value % 128;
            // 去掉已经写出的低 7 位。
            value /= 128;
            // 仍有剩余值时把最高位置 1，表示后面还有字节。
            if (value > 0) {
                digit |= 0x80;
            }
            // 写出当前字节。
            output.writeByte(digit);
        // 没有剩余值时结束循环。
        } while (value > 0);
    }

    private static int readVariableByteInteger(DataInputStream input) throws IOException {
        // multiplier 分别为 1、128、16384、2097152。
        int multiplier = 1;
        // value 累加每个字节贡献的低 7 位。
        int value = 0;
        // MQTT Variable Byte Integer 最多 4 字节。
        for (int count = 0; count < 4; count++) {
            // 读取当前无符号字节。
            int digit = input.readUnsignedByte();
            // 低 7 位乘当前权重后加入结果。
            value += (digit & 127) * multiplier;
            // 最高位为 0 说明这是最后一个字节。
            if ((digit & 128) == 0) {
                return value;
            }
            // 下一字节权重乘 128。
            multiplier *= 128;
        }
        // 四个字节后仍未结束属于畸形长度编码。
        throw new IOException("Malformed MQTT variable byte integer");
    }

    private static void writeUtf8(DataOutputStream output, String value) throws IOException {
        // MQTT UTF-8 字符串先转换成原始字节。
        byte[] bytes = value.getBytes(StandardCharsets.UTF_8);
        // 前两个字节使用无符号短整数保存字符串长度。
        output.writeShort(bytes.length);
        // 长度后紧跟字符串内容。
        output.write(bytes);
    }

    private static String readUtf8(DataInputStream input) throws IOException {
        // 读取两个字节的无符号字符串长度。
        int length = input.readUnsignedShort();
        // 读取对应字节并按 UTF-8 还原字符串。
        return new String(input.readNBytes(length), StandardCharsets.UTF_8);
    }

    private static void sleep(Duration duration) {
        try {
            // 按传入时长暂停当前重连虚拟线程。
            Thread.sleep(duration.toMillis());
        } catch (InterruptedException exception) {
            // 恢复中断标志，让更上层代码仍能知道线程被要求中断。
            Thread.currentThread().interrupt();
        }
    }

    @Override
    public void close() {
        // 先阻止重连循环再次开始。
        running = false;
        // 再关闭当前连接，使阻塞中的读取立即结束。
        closeSocket();
    }

    private void closeSocket() {
        // 先复制 volatile 引用，保证本方法后续操作的是同一个 Socket。
        Socket current = socket;
        // 立即清空共享字段，表示当前没有可用连接。
        socket = null;
        // 从未连接成功时 current 可能为 null。
        if (current != null) {
            try {
                // 关闭输入输出流和底层 TCP 连接。
                current.close();
            } catch (IOException ignored) {
                // 清理阶段采用 best effort；旧连接已经不可用，无需阻止后续重连。
            }
        }
    }

    // Packet 保存 MQTT 固定头和正文；不对项目外公开。
    private record Packet(int header, byte[] body) {
        private Packet {
            // 构造时复制正文，避免传入数组之后被修改。
            body = Arrays.copyOf(body, body.length);
        }
    }
}
