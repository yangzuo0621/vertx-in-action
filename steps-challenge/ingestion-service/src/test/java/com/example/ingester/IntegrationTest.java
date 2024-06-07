package com.example.ingester;

import java.io.File;
import java.util.Arrays;
import java.util.Collections;
import java.util.Map;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.testcontainers.containers.DockerComposeContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import io.restassured.builder.RequestSpecBuilder;
import io.restassured.filter.log.RequestLoggingFilter;
import io.restassured.filter.log.ResponseLoggingFilter;
import io.restassured.http.ContentType;
import io.restassured.specification.RequestSpecification;
import io.vertx.amqp.AmqpClientOptions;
import io.vertx.core.json.JsonObject;
import io.vertx.junit5.VertxExtension;
import io.vertx.junit5.VertxTestContext;
import io.vertx.rxjava3.amqp.AmqpClient;
import io.vertx.rxjava3.amqp.AmqpMessage;
import io.vertx.rxjava3.core.RxHelper;
import io.vertx.rxjava3.core.Vertx;
import io.vertx.rxjava3.kafka.admin.KafkaAdminClient;
import io.vertx.rxjava3.kafka.client.consumer.KafkaConsumer;

import static io.restassured.RestAssured.given;
import static org.assertj.core.api.Assertions.assertThat;

@ExtendWith(VertxExtension.class)
@Testcontainers
class IntegrationTest {

    @SuppressWarnings("resource")
    @Container
    private static final DockerComposeContainer<?> CONTAINERS = new DockerComposeContainer<>(
            new File("../docker-compose.yaml"))
            .withExposedService("kafka_1", 9092)
            .withExposedService("artemis_1", 5672);

    private static RequestSpecification requestSpecification;

    @BeforeAll
    static void prepareSpec() {
        requestSpecification = new RequestSpecBuilder()
                .addFilters(Arrays.asList(new ResponseLoggingFilter(), new RequestLoggingFilter()))
                .setBaseUri("http://localhost:3002/")
                .build();
    }

    static Map<String, String> kafkaConfig() {
        return Map.of(
                "bootstrap.servers", // "localhost:9092",
                CONTAINERS.getServiceHost("kafka_1", 9092) + ":" + CONTAINERS.getServicePort("kafka_1", 9092),
                "key.deserializer", "org.apache.kafka.common.serialization.StringDeserializer",
                "value.deserializer", "io.vertx.kafka.client.serialization.JsonObjectDeserializer",
                "group.id", "ingester-test-" + System.currentTimeMillis(),
                "auto.offset.reset", "earliest",
                "enable.auto.commit", "false");
    }

    private KafkaConsumer<String, JsonObject> kafkaConsumer;

    static AmqpClientOptions amqpConfig() {
        return new AmqpClientOptions()
                .setHost(CONTAINERS.getServiceHost("artemis_1", 5672))
                .setPort(CONTAINERS.getServicePort("artemis_1", 5672))
                // .setHost("localhost")
                // .setPort(5672)
                .setUsername("artemis")
                .setPassword("artemis");
    }

    private AmqpClient amqpClient;

    @BeforeEach
    void setup(Vertx vertx, VertxTestContext testContext) {
        kafkaConsumer = KafkaConsumer.create(vertx, kafkaConfig());
        amqpClient = AmqpClient.create(vertx, amqpConfig());
        KafkaAdminClient adminClient = KafkaAdminClient.create(vertx, kafkaConfig());
        vertx.rxDeployVerticle(new IngesterVerticle())
                .delay(500, TimeUnit.MILLISECONDS, RxHelper.scheduler(vertx))
                .flatMapCompletable(id -> adminClient.rxDeleteTopics(Collections.singletonList("incoming.steps")))
                .onErrorComplete()
                .subscribe(testContext::completeNow, testContext::failNow);
    }

    @Test
    @DisplayName("Ingest a well-formed AMQP message")
    void amqIngest(VertxTestContext testContext) {
        JsonObject body = new JsonObject()
                .put("deviceId", "123")
                .put("deviceSync", 1L)
                .put("stepsCount", 500);

        amqpClient.rxConnect()
                .flatMap(connection -> connection.rxCreateSender("step-events"))
                .subscribe(sender -> {
                    AmqpMessage msg = AmqpMessage.create().durable(true).ttl(5000).withJsonObjectAsBody(body).build();
                    sender.send(msg);
                }, testContext::failNow);

        kafkaConsumer
                .handler(v -> {
                })
                .toFlowable()
                .subscribe(
                        record -> testContext.verify(() -> {
                            assertThat(record.key()).isEqualTo("123");
                            JsonObject json = record.value();
                            assertThat(json.getString("deviceId")).isEqualTo("123");
                            assertThat(json.getLong("deviceSync")).isEqualTo(1L);
                            assertThat(json.getInteger("stepsCount")).isEqualTo(500);
                            testContext.completeNow();
                        }),
                        testContext::failNow);

        kafkaConsumer.subscribe("incoming.steps");
    }

    @Test
    @DisplayName("Ingest a badly-formed AMQP message and observe no Kafka record")
    void amqIngestWrong(Vertx vertx, VertxTestContext testContext) {
        JsonObject body = new JsonObject();

        amqpClient.rxConnect()
                .flatMap(connection -> connection.rxCreateSender("step-events"))
                .subscribe(
                        sender -> {
                            AmqpMessage msg = AmqpMessage.create()
                                    .durable(true)
                                    .ttl(5000)
                                    .withJsonObjectAsBody(body).build();
                            sender.send(msg);
                        },
                        testContext::failNow);

        kafkaConsumer
                .handler(v -> {
                })
                .toFlowable()
                .timeout(3, TimeUnit.SECONDS, RxHelper.scheduler(vertx))
                .subscribe(
                        record -> testContext.failNow(new IllegalStateException("We must not get a record")),
                        err -> {
                            if (err instanceof TimeoutException) {
                                testContext.completeNow();
                            } else {
                                testContext.failNow(err);
                            }
                        });
        kafkaConsumer.subscribe("incoming.steps");
    }

    @Test
    @DisplayName("Ingest a well-formed JSON data over HTTP")
    void httpIngest(VertxTestContext testContext) {
        JsonObject body = new JsonObject()
                .put("deviceId", "456")
                .put("deviceSync", 3L)
                .put("stepsCount", 125);

        given(requestSpecification)
                .contentType(ContentType.JSON)
                .body(body.encode())
                .post("/ingest")
                .then()
                .assertThat()
                .statusCode(200);

        kafkaConsumer
                .handler(v -> {
                })
                .toFlowable()
                .subscribe(
                        record -> testContext.verify(() -> {
                            assertThat(record.key()).isEqualTo("456");
                            JsonObject json = record.value();
                            assertThat(json.getString("deviceId")).isEqualTo("456");
                            assertThat(json.getLong("deviceSync")).isEqualTo(3L);
                            assertThat(json.getInteger("stepsCount")).isEqualTo(125);
                            testContext.completeNow();
                        }),
                        testContext::failNow);
        kafkaConsumer.subscribe("incoming.steps");
    }

    @Test
    @DisplayName("Ingest a badly-formed JSON data over HTTP and observe no Kafka record")
    void httpIngestWrong(Vertx vertx, VertxTestContext testContext) {
        JsonObject body = new JsonObject();

        given(requestSpecification)
                .contentType(ContentType.JSON)
                .body(body.encode())
                .post("/ingest")
                .then()
                .assertThat()
                .statusCode(400);

        kafkaConsumer
                .handler(v -> {
                })
                .toFlowable()
                .timeout(3, TimeUnit.SECONDS, RxHelper.scheduler(vertx))
                .subscribe(
                        record -> testContext.failNow(new IllegalStateException("We must not get a record")),
                        err -> {
                            if (err instanceof TimeoutException) {
                                testContext.completeNow();
                            } else {
                                testContext.failNow(err);
                            }
                        });

        kafkaConsumer.subscribe("incoming.steps");

    }
}
