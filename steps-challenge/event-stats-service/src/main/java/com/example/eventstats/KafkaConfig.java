package com.example.eventstats;

import java.util.Map;

class KafkaConfig {
    static Map<String, String> producer() {
        return Map.of(
                "bootstrap.servers", "localhost:9092",
                "key.serializer", "org.apache.kafka.common.serialization.StringSerializer",
                "value.serializer", "io.vertx.kafka.client.serialization.JsonObjectSerializer",
                "acks", "1");
    }

    static Map<String, String> consumer(String group) {
        return Map.of(
                "bootstrap.servers", "localhost:9092",
                "key.deserializer", "org.apache.kafka.common.serialization.StringDeserializer",
                "value.deserializer", "io.vertx.kafka.client.serialization.JsonObjectDeserializer",
                "auto.offset.reset", "earliest",
                "enable.auto.commit", "true",
                "group.id", group);
    }
}
