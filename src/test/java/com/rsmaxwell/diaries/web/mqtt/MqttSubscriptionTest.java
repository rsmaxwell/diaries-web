package com.rsmaxwell.diaries.web.mqtt;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.awaitility.Awaitility.await;

import java.io.ByteArrayOutputStream;
import java.io.DataOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetAddress;
import java.net.ServerSocket;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.stream.Stream;

import org.eclipse.paho.mqttv5.common.MqttMessage;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

import com.rsmaxwell.diaries.web.config.AppConfig.MqttConfig;
import com.rsmaxwell.diaries.web.config.MqttCredentials;
import com.rsmaxwell.diaries.web.projection.ProjectionService;

class MqttSubscriptionTest {
    private static final List<String> FILTERS = new TopicParser("diaries").canonicalFilters();

    static Stream<Arguments> badSubacks() {
        return Stream.of(
                Arguments.of((Object) null), Arguments.of((Object) new int[0]),
                Arguments.of((Object) new int[] {1, 1, 1, 1}),
                Arguments.of((Object) new int[] {1, 1, 1, 1, 1, 1}),
                Arguments.of((Object) new int[] {1, 1, 1, 1, 0x87}),
                Arguments.of((Object) new int[] {1, 1, 1, 1, 3}),
                Arguments.of((Object) new int[] {1, 1, 1, 1, -1}));
    }

    @ParameterizedTest
    @MethodSource("badSubacks")
    void rejectsMissingTruncatedOrFailedAcknowledgements(int[] codes) {
        assertThatThrownBy(() -> MqttProjectionClient.requireSuccessfulSubAck(FILTERS, codes))
                .isInstanceOf(IllegalStateException.class);
    }

    @Test
    void acceptsOnlyCompleteSuccessfulAcknowledgements() {
        MqttProjectionClient.requireSuccessfulSubAck(FILTERS, new int[] {0, 1, 2, 1, 1});
    }

    @Test
    void rejectedImageSubackKeepsPartialReplayAndLateCallbacksOutOfActiveSnapshot() throws Exception {
        // File-ACL Mosquitto filters delivery without rejecting SUBACK. This tiny MQTT-5
        // peer exercises the real Paho callback path for brokers which explicitly reject it.
        var release = new CountDownLatch(1);
        try (var listener = new ServerSocket(0, 1, InetAddress.getLoopbackAddress());
                var executor = Executors.newSingleThreadExecutor()) {
            var broker = executor.submit(() -> {
                try (var socket = listener.accept()) {
                    socket.setSoTimeout(5000);
                    var input = socket.getInputStream();
                    var output = socket.getOutputStream();
                    assertThat(input.read()).isEqualTo(0x10); // CONNECT
                    packetBody(input);
                    output.write(new byte[] {0x20, 3, 0, 0, 0}); // CONNACK, success, no properties
                    output.flush();
                    assertThat(input.read()).isEqualTo(0x82); // SUBSCRIBE
                    byte[] subscribe = packetBody(input);
                    String payload = new String(subscribe, StandardCharsets.UTF_8);
                    for (String filter : FILTERS) assertThat(payload).contains(filter);
                    retainedDiary(output);
                    // Four grants followed by Not authorized for images/+.
                    output.write(new byte[] {(byte) 0x90, 8, subscribe[0], subscribe[1], 0, 1, 1, 1, 1, (byte) 0x87});
                    output.flush();
                    release.await(8, TimeUnit.SECONDS);
                }
                return null;
            });
            try (var projection = new ProjectionService(Duration.ofMillis(50), Duration.ofSeconds(3));
                    var reader = new MqttProjectionClient(
                            new MqttConfig(listener.getInetAddress().getHostAddress(), listener.getLocalPort(),
                                    "suback-test", "diaries", 10, 2, 1, true),
                            new MqttCredentials("test", "test-only"), projection)) {
                reader.start();
                await().atMost(Duration.ofSeconds(4)).untilAsserted(() -> {
                    assertThat(projection.status().reason()).contains("MQTT subscription failed", "diaries/images/+");
                    assertThat(projection.status().ready()).isFalse();
                    assertThat(projection.status().subscriptionsAcknowledged()).isFalse();
                });
                reader.messageArrived("diaries/diaries/11", new MqttMessage(diary()));
                await().during(Duration.ofMillis(150)).atMost(Duration.ofSeconds(1)).untilAsserted(() -> {
                    assertThat(projection.snapshot().diariesById()).isEmpty();
                    assertThat(projection.status().ready()).isFalse();
                });
            } finally {
                release.countDown();
            }
            broker.get(5, TimeUnit.SECONDS);
        }
    }

    private static byte[] diary() throws Exception {
        try (var input = MqttSubscriptionTest.class.getResourceAsStream("/fixtures/diary.json")) {
            return input.readAllBytes();
        }
    }

    private static byte[] packetBody(InputStream input) throws Exception {
        int size = 0;
        int multiplier = 1;
        for (int i = 0; i < 4; i++) {
            int digit = input.read();
            if (digit < 0) throw new java.io.EOFException();
            size += (digit & 127) * multiplier;
            if ((digit & 128) == 0) return input.readNBytes(size);
            multiplier *= 128;
        }
        throw new IllegalArgumentException("Invalid MQTT remaining length");
    }

    private static void retainedDiary(OutputStream output) throws Exception {
        var bytes = new ByteArrayOutputStream();
        var data = new DataOutputStream(bytes);
        data.writeUTF("diaries/diaries/11");
        data.writeByte(0); // MQTT-5 property length, QoS 0 has no packet identifier
        data.write(diary());
        output.write(0x31); // retained QoS-0 PUBLISH before SUBACK
        int size = bytes.size();
        do {
            int digit = size % 128;
            size /= 128;
            output.write(size > 0 ? digit | 128 : digit);
        } while (size > 0);
        bytes.writeTo(output);
    }
}
