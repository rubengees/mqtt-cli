/*
 * Copyright 2019 HiveMQ and the HiveMQ Community
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 *
 */
package com.hivemq.cli.mqtt.test;

import com.google.common.base.Strings;
import com.hivemq.cli.mqtt.test.results.ClientIdLengthTestResults;
import com.hivemq.cli.mqtt.test.results.PayloadTestResults;
import com.hivemq.cli.mqtt.test.results.QosTestResult;
import com.hivemq.cli.mqtt.test.results.TestResult;
import com.hivemq.cli.mqtt.test.results.TopicLengthTestResults;
import com.hivemq.cli.mqtt.test.results.WildcardSubscriptionsTestResult;
import com.hivemq.cli.utils.Tuple;
import com.hivemq.client.mqtt.MqttClientSslConfig;
import com.hivemq.client.mqtt.MqttGlobalPublishFilter;
import com.hivemq.client.mqtt.datatypes.MqttQos;
import com.hivemq.client.mqtt.mqtt3.Mqtt3Client;
import com.hivemq.client.mqtt.mqtt3.Mqtt3ClientBuilder;
import com.hivemq.client.mqtt.mqtt3.exceptions.Mqtt3ConnAckException;
import com.hivemq.client.mqtt.mqtt3.message.auth.Mqtt3SimpleAuth;
import com.hivemq.client.mqtt.mqtt3.message.connect.connack.Mqtt3ConnAck;
import com.hivemq.client.mqtt.mqtt3.message.connect.connack.Mqtt3ConnAckReturnCode;
import com.hivemq.client.mqtt.mqtt3.message.publish.Mqtt3Publish;
import com.hivemq.client.mqtt.mqtt3.message.subscribe.Mqtt3Subscribe;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.nio.ByteBuffer;
import java.util.Arrays;
import java.util.LinkedList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Consumer;

import static com.hivemq.client.mqtt.mqtt3.Mqtt3BlockingClient.*;

public class Mqtt3FeatureTester {

    private final int LONG_TIME_OUT = 10;
    private final int SHORT_TIME_OUT = 2;

    private int maxTopicLength = -1;
    private final String host;
    private final int port;
    private final String username;
    private final ByteBuffer password;
    private final MqttClientSslConfig sslConfig;

    public Mqtt3FeatureTester(final @NotNull String host,
                              final @NotNull Integer port,
                              final @Nullable String username,
                              final @Nullable ByteBuffer password,
                              final @Nullable MqttClientSslConfig sslConfig) {
        this.host = host;
        this.port = port;
        this.username = username;
        this.password = password;
        this.sslConfig = sslConfig;
    }

    // Test methods

    public @Nullable Mqtt3ConnAck testConnect() {
        //TODO Wrap with object instead of Mqtt3Connack
        final Mqtt3Client client = buildClient();

        try { return client.toAsync().connect().get(LONG_TIME_OUT, TimeUnit.SECONDS); }
        catch (final Mqtt3ConnAckException ex) { return ex.getMqttMessage(); }
        catch (final Exception ex) { return null; }
        finally {
            if (client.getConfig().getState().isConnected()) {
                client.toBlocking().disconnect();
            }
        }
    }

    public @NotNull WildcardSubscriptionsTestResult testWildcardSubscriptions() {
        final TestResult plusWildcardResult = testWildcard("+", "test");
        final TestResult hashWildcardResult = testWildcard("#", "test/subtopic");

        return new WildcardSubscriptionsTestResult(plusWildcardResult, hashWildcardResult);
    }

    public @NotNull TestResult testRetain() {
        final Mqtt3Client publisher = buildClient();
        final Mqtt3Client subscriber = buildClient();
        final String topic = (maxTopicLength == -1 ? generateTopicUUID() : generateTopicUUID(maxTopicLength));
        final CountDownLatch countDownLatch = new CountDownLatch(1);

        publisher.toBlocking().connect();

        try {
            publisher.toBlocking().publishWith()
                .topic(topic)
                .qos(MqttQos.AT_LEAST_ONCE)
                .retain(true)
                .payload("RETAIN".getBytes())
                .send();
        }
        catch (final Exception ex) { return TestResult.PUBLISH_FAILED; }

        subscriber.toBlocking().connect();

        subscriber.toAsync().subscribeWith()
                .topicFilter(topic)
                .qos(MqttQos.AT_LEAST_ONCE)
                .callback(publish -> {
                    if (publish.isRetain()) {
                        countDownLatch.countDown();
                    }
                })
                .send()
                .join();

        try { countDownLatch.await(LONG_TIME_OUT, TimeUnit.SECONDS); }
        catch (final InterruptedException ex) {
            // TODO Log
        }

        return countDownLatch.getCount() == 0 ? TestResult.OK : TestResult.TIME_OUT;
    }

    public @NotNull QosTestResult testQos(final @NotNull MqttQos qos, final int tries) {
        final Mqtt3Client publisher = buildClient();
        final Mqtt3Client subscriber = buildClient();
        final String topic = generateTopicUUID(maxTopicLength);
        final byte[] payload = qos.toString().getBytes();

        subscriber.toBlocking().connect();
        publisher.toBlocking().connect();

        final CountDownLatch countDownLatch = new CountDownLatch(tries);
        final AtomicInteger totalReceived = new AtomicInteger(0);

        subscriber.toAsync().subscribeWith()
                .topicFilter(topic)
                .qos(qos)
                .callback(publish -> {
                    if (publish.getQos() == qos
                            && Arrays.equals(publish.getPayloadAsBytes(), payload)) {
                        totalReceived.incrementAndGet();
                        countDownLatch.countDown();
                    }
                })
                .send()
                .join();

        final long before = System.nanoTime();

        for (int i = 0; i < tries; i++) {
            publisher.toAsync().publishWith()
                    .topic(topic)
                    .qos(qos)
                    .payload(payload)
                    .send();
        }

        try { countDownLatch.await(LONG_TIME_OUT, TimeUnit.SECONDS); }
        catch (InterruptedException e) { e.printStackTrace(); }

        final long after = System.nanoTime();
        final long timeToComplete = after - before;

        return new QosTestResult(totalReceived.get(), timeToComplete);
    }

    public @NotNull PayloadTestResults testPayloadSize(final int maxSize) {
        final Mqtt3Client subscriber = buildClient();
        final Mqtt3Client publisher = buildClient();
        final List<Tuple<Integer, TestResult>> testResults = new LinkedList<>();
        final String topic = (maxTopicLength == -1 ? generateTopicUUID() : generateTopicUUID(maxTopicLength));
        final Mqtt3Publishes publishes = subscriber.toBlocking().publishes(MqttGlobalPublishFilter.SUBSCRIBED);
        final String oneByte = "a";
        int top = maxSize;
        int bottom = 0;
        int mid = -1;

        subscriber.toBlocking().connect();
        publisher.toBlocking().connect();

        subscriber.toBlocking().subscribeWith()
                .topicFilter(topic)
                .qos(MqttQos.AT_LEAST_ONCE)
                .send();

        // Binary search the payload size
        while (bottom <= top) {
            mid = (bottom + top) / 2;
            final String currentPayload = Strings.repeat(oneByte, mid);
            final Mqtt3Publish publish = Mqtt3Publish.builder()
                    .topic(topic)
                    .qos(MqttQos.AT_LEAST_ONCE)
                    .payload(currentPayload.getBytes())
                    .build();

            try {
                publisher.toBlocking().publish(publish);
            }
            catch (final Exception ex) {
                testResults.add(new Tuple<>(mid, TestResult.PUBLISH_FAILED));
                top = mid -1;
                continue;
            }

            try {
                final Optional<Mqtt3Publish> receive = publishes.receive(LONG_TIME_OUT, TimeUnit.SECONDS);
                if (!receive.isPresent()) {
                    testResults.add(new Tuple<>(mid, TestResult.TIME_OUT));
                    top = mid - 1;
                    continue;
                }
                else if (!Arrays.equals(receive.get().getPayloadAsBytes(), currentPayload.getBytes())) {
                    testResults.add(new Tuple<>(mid, TestResult.WRONG_PAYLOAD));
                    top = mid - 1;
                    continue;
                }

            }
            catch (InterruptedException e) { e.printStackTrace(); }

            testResults.add(new Tuple<>(mid, TestResult.OK));
            bottom = mid + 1;
        }

        subscriber.toBlocking().disconnect();
        publisher.toBlocking().disconnect();

        return new PayloadTestResults(mid, testResults);
    }

    public @NotNull TopicLengthTestResults testTopicLength() {
        final Mqtt3Client subscriber = buildClient();
        final Mqtt3Client publisher = buildClient();
        final Mqtt3Publishes publishes = subscriber.toBlocking().publishes(MqttGlobalPublishFilter.SUBSCRIBED);
        final List<Tuple<Integer, TestResult>> testResults = new LinkedList<>();
        final String oneByte = "a";
        int top = 65535;
        int bottom = 0;
        int mid = -1;

        subscriber.toBlocking().connect();
        publisher.toBlocking().connect();

        // Binary search the right topic length
        while (bottom <= top) {
            mid = (bottom + top) / 2;
            final String currentTopicName = Strings.repeat(oneByte, mid);
            final Mqtt3Publish publish = Mqtt3Publish.builder()
                    .topic(currentTopicName)
                    .qos(MqttQos.AT_LEAST_ONCE)
                    .payload(currentTopicName.getBytes())
                    .build();
            final Mqtt3Subscribe subscribe = Mqtt3Subscribe.builder()
                    .topicFilter(currentTopicName)
                    .qos(MqttQos.AT_LEAST_ONCE)
                    .build();

            // Test subscribe to topic
            try { subscriber.toBlocking().subscribe(subscribe); }
            catch (final Exception ex) {
                testResults.add(new Tuple<>(mid, TestResult.SUBSCRIBE_FAILED));
                top = mid - 1;
                continue;
            }

            // Test publish to topic
            try {
                publisher.toBlocking().publish(publish);
            }
            catch (final Exception ex) {
                testResults.add(new Tuple<>(mid, TestResult.PUBLISH_FAILED));
                top = mid - 1;
                continue;
            }

            // Subscriber retrieves payload
            try {
                final Optional<Mqtt3Publish> receive = publishes.receive(LONG_TIME_OUT, TimeUnit.SECONDS);
                if (!receive.isPresent()) {
                    testResults.add(new Tuple<>(mid, TestResult.TIME_OUT));
                    top = mid - 1;
                    continue;
                }
                else if (!Arrays.equals(receive.get().getPayloadAsBytes(), currentTopicName.getBytes())) {
                    testResults.add(new Tuple<>(mid, TestResult.WRONG_PAYLOAD));
                    top = mid - 1;
                    continue;
                }
             }
            catch (InterruptedException e) { e.printStackTrace(); }

            // Everything successful
            testResults.add(new Tuple<>(mid, TestResult.OK));
            bottom = mid + 1;
        }

        subscriber.toBlocking().disconnect();
        publisher.toBlocking().disconnect();

        return new TopicLengthTestResults(mid, testResults);
    }

    public @NotNull ClientIdLengthTestResults testClientIdLength() {
        final Mqtt3Client client = buildClient();
        final List<Tuple<Integer, Mqtt3ConnAckReturnCode>> connectResults = new LinkedList<>();
        final String oneByte = "a";
        final Mqtt3ClientBuilder mqtt3ClientBuilder = Mqtt3Client.builder()
                .serverHost(client.getConfig().getServerHost())
                .serverPort(client.getConfig().getServerPort());
        int top = 65535;
        int bottom = 0;
        int mid = -1;

        // Binary search the right client id length
        while (bottom <= top) {
            mid = (bottom + top) / 2;
            final String currentIdentifier = Strings.repeat(oneByte, mid);
            final Mqtt3Client currClient = getClientBuilder()
                    .identifier(currentIdentifier)
                    .build();

            try {
                final Mqtt3ConnAck connAck = currClient.toBlocking().connect();
                connectResults.add(new Tuple<>(mid, connAck.getReturnCode()));
                if (connAck.getReturnCode() != Mqtt3ConnAckReturnCode.SUCCESS) {
                    top = mid - 1;
                    continue;
                }
            }
            catch (final Mqtt3ConnAckException connAckEx) {
                connectResults.add(new Tuple<>(mid, connAckEx.getMqttMessage().getReturnCode()));
                top = mid - 1;
                continue;
            }
            catch (final Exception ex) {
                // TODO What to return in this case?
                connectResults.add(new Tuple<>(mid, Mqtt3ConnAckReturnCode.SERVER_UNAVAILABLE));
                top = mid -1;
                continue;
            }

            bottom = mid + 1;
            if (currClient.getConfig().getState().isConnected()) { currClient.toBlocking().disconnect(); }
        }

        return new ClientIdLengthTestResults(mid, connectResults);
    }

    private @NotNull TestResult testWildcard(final String subscribeWildcardTopic, final String publishTopic) {
        final Mqtt3Client client = buildClient();
        final String topic = (maxTopicLength == -1 ? generateTopicUUID() : generateTopicUUID(maxTopicLength));
        final String subscribeToTopic = topic + "/" + subscribeWildcardTopic;
        final String publishToTopic = topic + "/" + publishTopic;
        final byte[] payload = "WILDCARD_TEST".getBytes();

        final CountDownLatch countDownLatch = new CountDownLatch(1);

        final Consumer<Mqtt3Publish> publishCallback = publish -> {
            if (Arrays.equals(publish.getPayloadAsBytes(), payload)) { countDownLatch.countDown(); }
        };

        client.toBlocking().connect();

        try {
            client.toAsync().subscribeWith()
                    .topicFilter(subscribeToTopic)
                    .qos(MqttQos.AT_LEAST_ONCE)
                    .callback(publishCallback)
                    .send()
                    .join();
        }
        catch (final Exception ex) { return TestResult.SUBSCRIBE_FAILED; }

        try {
            client.toBlocking().publishWith()
                    .topic(publishToTopic)
                    .qos(MqttQos.AT_LEAST_ONCE)
                    .payload(payload)
                    .send();
        }
        catch (final Exception ex) { return TestResult.PUBLISH_FAILED; }

        try {
            countDownLatch.await(SHORT_TIME_OUT, TimeUnit.SECONDS);
        } catch (InterruptedException e) {
            // TODO Log
        }

        if (countDownLatch.getCount() == 0) { return TestResult.OK; }
        else { return TestResult.TIME_OUT; }
    }
    
    public @NotNull String testAsciiCharsInClientId() {
        final String ASCII = " !\"#$%&\\'()*+,-./:;<=>?@[\\\\]^_`{|}~";
        final StringBuilder unsupportedChars = new StringBuilder();
        final List<Tuple<Character, Mqtt3ConnAckReturnCode>> connectResults = new LinkedList<>();

        for (int i = 0; i < ASCII.length(); i++) {
            final String currChar = String.valueOf(ASCII.charAt(i));
            final Mqtt3Client client = getClientBuilder()
                    .identifier(currChar)
                    .build();

            try { client.toBlocking().connect(); }
            catch (final Mqtt3ConnAckException ex) {
                connectResults.add(new Tuple<>(currChar.charAt(0), ex.getMqttMessage().getReturnCode()));
            }
            catch (final Exception ex) {
                unsupportedChars.append(currChar); }

            if (client.getConfig().getState().isConnected()) { client.toBlocking().disconnect(); }
        }

        return unsupportedChars.toString();
    }

    // Getter / Setter

    public void setMaxTopicLength(final int topicLength) { maxTopicLength = topicLength; }

    // Helpers

    private @NotNull Mqtt3Client buildClient() { return getClientBuilder().build(); }

    private @NotNull Mqtt3ClientBuilder getClientBuilder() {
        final Mqtt3ClientBuilder mqtt3ClientBuilder = Mqtt3Client.builder()
                .serverHost(host)
                .serverPort(port)
                .simpleAuth(buildAuth());

        if (sslConfig != null) {
            mqtt3ClientBuilder.sslConfig(sslConfig);
        }

        return mqtt3ClientBuilder;
    }

    private @Nullable  Mqtt3SimpleAuth buildAuth() {
        if (username != null && password != null) {
            return Mqtt3SimpleAuth.builder()
                    .username(username)
                    .password(password)
                    .build();
        }
        else if (username != null) {
            Mqtt3SimpleAuth.builder()
                    .username(username)
                    .build();
        }
        else if (password != null) {
            throw new IllegalArgumentException("Password-Only Authentication is not allowed in MQTT 3");
        }
        return null;
    }

    private @NotNull String generateTopicUUID() {
        final String uuid = UUID.randomUUID().toString();
        return uuid.replace("-","");
    }

    private @NotNull String generateTopicUUID(final int maxLength) {
        if (maxLength == -1) return generateTopicUUID();
        else return generateTopicUUID().substring(0, maxLength);
    }
}


