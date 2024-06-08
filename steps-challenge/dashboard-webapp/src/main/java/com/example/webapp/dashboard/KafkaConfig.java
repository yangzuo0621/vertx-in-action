package com.example.webapp.dashboard;

import java.util.Map;

class KafkaConfig {
    static Map<String, String> consumerConfig(String group) {
        return Map.of(
                "bootstrap.servers", "localhost:9092",
                "key.deserializer", "org.apache.kafka.common.serialization.StringDeserializer",
                "value.deserializer", "io.vertx.kafka.client.serialization.JsonObjectDeserializer",
                "auto.offset.reset", "earliest",
                "enable.auto.commit", "true",
                "group.id", group);
    }
}
