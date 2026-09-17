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
    public static final String UP_TOPIC = "charging/tocloud/+/protobuf/general";
    private final String host;
    private final int port;
    private final String clientId;
    private final DeviceProtocolService protocolService;
    private volatile boolean running;
    private volatile Socket socket;

    public Mqtt5GatewayAdapter(String host, int port, String clientId,
                               DeviceProtocolService protocolService) {
        this.host = host;
        this.port = port;
        this.clientId = clientId;
        this.protocolService = protocolService;
    }

    public void start() {
        if (running) {
            return;
        }
        running = true;
        Thread.ofVirtual().name("mqtt5-gateway-adapter").start(this::reconnectLoop);
    }

    private void reconnectLoop() {
        while (running) {
            try {
                connectAndConsume();
            } catch (Exception exception) {
                if (running) {
                    System.err.println("MQTT 5.0 connection failed: " + exception.getMessage());
                    sleep(Duration.ofSeconds(5));
                }
            } finally {
                closeSocket();
            }
        }
    }

    private void connectAndConsume() throws Exception {
        Socket current = new Socket();
        current.connect(new InetSocketAddress(host, port), 5_000);
        current.setSoTimeout(1_000);
        socket = current;
        DataInputStream input = new DataInputStream(current.getInputStream());
        DataOutputStream output = new DataOutputStream(current.getOutputStream());
        writePacket(output, 0x10, connectBody());
        Packet connAck = readPacket(input);
        if ((connAck.header() >>> 4) != 2 || connAck.body().length < 3 || connAck.body()[1] != 0) {
            throw new IOException("Broker rejected MQTT 5.0 CONNECT");
        }
        writePacket(output, 0x82, subscribeBody(1, UP_TOPIC));
        Packet subAck = readPacket(input);
        if ((subAck.header() >>> 4) != 9) {
            throw new IOException("Expected MQTT SUBACK");
        }
        System.out.println("MQTT 5.0 connected: " + host + ":" + port + " topic=" + UP_TOPIC);
        long lastNetwork = System.currentTimeMillis();
        while (running && !current.isClosed()) {
            try {
                Packet packet = readPacket(input);
                lastNetwork = System.currentTimeMillis();
                int type = packet.header() >>> 4;
                if (type == 3) {
                    handlePublish(packet, output);
                } else if (type == 13) {
                    // PINGRESP
                } else if (type == 14) {
                    throw new EOFException("Broker sent DISCONNECT");
                }
            } catch (SocketTimeoutException timeout) {
                if (System.currentTimeMillis() - lastNetwork >= 30_000) {
                    writePacket(output, 0xC0, new byte[0]);
                    lastNetwork = System.currentTimeMillis();
                }
            }
        }
    }

    private void handlePublish(Packet packet, DataOutputStream output) throws Exception {
        int qos = (packet.header() >>> 1) & 3;
        if (qos != 0) {
            throw new IOException("Only MQTT QoS 0 is supported by the task protocol");
        }
        DataInputStream body = new DataInputStream(new ByteArrayInputStream(packet.body()));
        String topic = readUtf8(body);
        int propertyLength = readVariableByteInteger(body);
        body.skipNBytes(propertyLength);
        byte[] payload = body.readAllBytes();
        String deviceSn = extractDeviceSn(topic);
        byte[] response = protocolService.handle(deviceSn, payload).responseFrame();
        publish(output, "charging/todev/" + deviceSn + "/protobuf/general", response);
    }

    private static void publish(DataOutputStream output, String topic, byte[] payload) throws IOException {
        ByteArrayOutputStream bodyBytes = new ByteArrayOutputStream();
        DataOutputStream body = new DataOutputStream(bodyBytes);
        writeUtf8(body, topic);
        body.writeByte(0); // MQTT 5 PUBLISH properties length
        body.write(payload);
        writePacket(output, 0x30, bodyBytes.toByteArray());
    }

    public static String extractDeviceSn(String topic) {
        String[] parts = topic.split("/");
        if (parts.length != 5 || !"charging".equals(parts[0])
                || !"tocloud".equals(parts[1]) || !"protobuf".equals(parts[3])
                || !"general".equals(parts[4]) || parts[2].isBlank()) {
            throw new IllegalArgumentException("Unexpected charging topic: " + topic);
        }
        return parts[2];
    }

    private byte[] connectBody() throws IOException {
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        DataOutputStream out = new DataOutputStream(bytes);
        writeUtf8(out, "MQTT");
        out.writeByte(5);      // MQTT 5.0
        out.writeByte(0x02);   // clean start
        out.writeShort(60);    // keep alive
        out.writeByte(0);      // CONNECT properties length
        writeUtf8(out, clientId);
        return bytes.toByteArray();
    }

    private static byte[] subscribeBody(int packetIdentifier, String topicFilter) throws IOException {
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        DataOutputStream out = new DataOutputStream(bytes);
        out.writeShort(packetIdentifier);
        out.writeByte(0); // SUBSCRIBE properties length
        writeUtf8(out, topicFilter);
        out.writeByte(0); // QoS 0, no-local false, retain flags false
        return bytes.toByteArray();
    }

    private static void writePacket(DataOutputStream output, int header, byte[] body) throws IOException {
        output.writeByte(header);
        writeVariableByteInteger(output, body.length);
        output.write(body);
        output.flush();
    }

    private static Packet readPacket(DataInputStream input) throws IOException {
        int header = input.readUnsignedByte();
        int length = readVariableByteInteger(input);
        if (length > 1_048_576) {
            throw new IOException("MQTT packet is too large");
        }
        byte[] body = input.readNBytes(length);
        if (body.length != length) {
            throw new EOFException("Truncated MQTT packet");
        }
        return new Packet(header, body);
    }

    private static void writeVariableByteInteger(DataOutputStream output, int value) throws IOException {
        do {
            int digit = value % 128;
            value /= 128;
            if (value > 0) {
                digit |= 0x80;
            }
            output.writeByte(digit);
        } while (value > 0);
    }

    private static int readVariableByteInteger(DataInputStream input) throws IOException {
        int multiplier = 1;
        int value = 0;
        for (int count = 0; count < 4; count++) {
            int digit = input.readUnsignedByte();
            value += (digit & 127) * multiplier;
            if ((digit & 128) == 0) {
                return value;
            }
            multiplier *= 128;
        }
        throw new IOException("Malformed MQTT variable byte integer");
    }

    private static void writeUtf8(DataOutputStream output, String value) throws IOException {
        byte[] bytes = value.getBytes(StandardCharsets.UTF_8);
        output.writeShort(bytes.length);
        output.write(bytes);
    }

    private static String readUtf8(DataInputStream input) throws IOException {
        int length = input.readUnsignedShort();
        return new String(input.readNBytes(length), StandardCharsets.UTF_8);
    }

    private static void sleep(Duration duration) {
        try {
            Thread.sleep(duration.toMillis());
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
        }
    }

    @Override
    public void close() {
        running = false;
        closeSocket();
    }

    private void closeSocket() {
        Socket current = socket;
        socket = null;
        if (current != null) {
            try {
                current.close();
            } catch (IOException ignored) {
                // best effort
            }
        }
    }

    private record Packet(int header, byte[] body) {
        private Packet {
            body = Arrays.copyOf(body, body.length);
        }
    }
}
