package com.example.eventstats;

import java.io.File;
import java.time.LocalDateTime;
import java.util.Arrays;
import java.util.UUID;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.testcontainers.containers.DockerComposeContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import io.vertx.core.json.JsonObject;
import io.vertx.junit5.VertxExtension;
import io.vertx.junit5.VertxTestContext;
import io.vertx.rxjava3.core.Vertx;
import io.vertx.rxjava3.kafka.admin.KafkaAdminClient;
import io.vertx.rxjava3.kafka.client.consumer.KafkaConsumer;
import io.vertx.rxjava3.kafka.client.producer.KafkaProducer;
import io.vertx.rxjava3.kafka.client.producer.KafkaProducerRecord;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.data.Offset.offset;

@ExtendWith(VertxExtension.class)
@Testcontainers
@DisplayName("Tests for the events-stats service")
class EventStatsTest {

    @SuppressWarnings("resource")
    @Container
    private static final DockerComposeContainer<?> CONTAINERS = new DockerComposeContainer<>(
            new File("src/test/docker/docker-compose.yaml"))
            .withExposedService("kafka_1", 9092);

    private KafkaProducer<String, JsonObject> producer;
    private KafkaConsumer<String, JsonObject> consumer;

    @BeforeEach
    void prepare(Vertx vertx, VertxTestContext testContext) {
        producer = KafkaProducer.create(vertx, KafkaConfig.producer());
        consumer = KafkaConsumer.create(vertx, KafkaConfig.consumer(UUID.randomUUID().toString()));
        KafkaAdminClient adminClient = KafkaAdminClient.create(vertx, KafkaConfig.producer());
        adminClient
                .rxDeleteTopics(Arrays.asList("incoming.steps", "daily.step.updates"))
                .onErrorComplete()
                .andThen(vertx.rxDeployVerticle(new EventStatsVerticle()))
                .ignoreElement()
                .andThen(vertx.rxDeployVerticle(new FakeUserService()))
                .ignoreElement()
                .subscribe(testContext::completeNow, testContext::failNow);
    }

    private KafkaProducerRecord<String, JsonObject> dailyStepsUpdateRecord(String deviceId, long steps) {
        LocalDateTime now = LocalDateTime.now();
        String key = deviceId + ":" + now.getYear() + "-" + now.getMonth() + "-" + now.getDayOfMonth();
        JsonObject json = new JsonObject()
                .put("deviceId", deviceId)
                .put("timestamp", now.toString())
                .put("stepsCount", steps);
        return KafkaProducerRecord.create("daily.step.updates", key, json);
    }

    private KafkaProducerRecord<String, JsonObject> incomingStepsRecord(String deviceId, long syncId, long steps) {
        LocalDateTime now = LocalDateTime.now();
        String key = deviceId + ":" + now.getYear() + "-" + now.getMonth() + "-" + now.getDayOfMonth();
        JsonObject json = new JsonObject()
                .put("deviceId", deviceId)
                .put("syncId", syncId)
                .put("stepsCount", steps);
        return KafkaProducerRecord.create("incoming.steps", key, json);
    }

    @Test
    @DisplayName("Incoming activity throughput computation")
    void throughput(VertxTestContext testContext) {
        for (int i = 0; i < 10; i++) {
            producer.send(incomingStepsRecord("abc", (long) i, 10));
        }

        consumer
                .handler(v -> {
                })
                .toFlowable()
                .subscribe(
                        record -> testContext.verify(() -> {
                            JsonObject data = record.value();
                            assertThat(data.getInteger("seconds")).isEqualTo(5);
                            assertThat(data.getInteger("count")).isEqualTo(10);
                            assertThat(data.getDouble("throughput")).isCloseTo(2.0d, offset(0.01d));
                            testContext.completeNow();
                        }), testContext::failNow);

        consumer.subscribe("event-stats.throughput");
    }

    @Test
    @DisplayName("User activity updates")
    void userActivityUpdate(VertxTestContext testContext) {
        producer.send(dailyStepsUpdateRecord("abc", 2500));
        consumer
                .handler(v -> {
                })
                .toFlowable()
                .subscribe(
                        record -> testContext.verify(() -> {
                            JsonObject data = record.value();
                            assertThat(data.getString("deviceId")).isEqualTo("abc");
                            assertThat(data.getString("username")).isEqualTo("Foo");
                            assertThat(data.getInteger("stepsCount")).isEqualTo(2500);
                            assertThat(data.containsKey("timestamp")).isTrue();
                            assertThat(data.containsKey("city")).isTrue();
                            assertThat(data.containsKey("makePublic")).isTrue();
                            testContext.completeNow();
                        }), testContext::failNow);

        consumer.subscribe("event-stats.user-activity.updates");
    }

    @Test
    @DisplayName("City trend updates")
    void cityTrendUpdate(VertxTestContext testContext) {
        producer.send(dailyStepsUpdateRecord("abc", 2500));
        producer.send(dailyStepsUpdateRecord("abc", 2500));
        consumer
                .handler(v -> {
                })
                .toFlowable()
                .subscribe(
                        record -> testContext.verify(() -> {
                            JsonObject data = record.value();
                            assertThat(data.getInteger("seconds")).isEqualTo(5);
                            assertThat(data.getInteger("updates")).isEqualTo(2);
                            assertThat(data.getLong("stepsCount")).isEqualTo(5000L);
                            assertThat(data.getString("city")).isEqualTo("Lyon");
                            testContext.completeNow();
                        }), testContext::failNow);

        consumer.subscribe("event-stats.city-trend.updates");
    }
}
