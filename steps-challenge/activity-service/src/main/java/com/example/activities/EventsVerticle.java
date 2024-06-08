package com.example.activities;

import java.time.LocalDateTime;
import java.util.concurrent.TimeUnit;

import org.reactivestreams.Publisher;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import io.reactivex.rxjava3.core.Completable;
import io.reactivex.rxjava3.core.Flowable;
import io.vertx.core.json.JsonObject;
import io.vertx.pgclient.PgException;
import io.vertx.rxjava3.core.AbstractVerticle;
import io.vertx.rxjava3.core.RxHelper;
import io.vertx.rxjava3.kafka.client.consumer.KafkaConsumer;
import io.vertx.rxjava3.kafka.client.consumer.KafkaConsumerRecord;
import io.vertx.rxjava3.kafka.client.producer.KafkaProducer;
import io.vertx.rxjava3.kafka.client.producer.KafkaProducerRecord;
import io.vertx.rxjava3.pgclient.PgPool;
import io.vertx.rxjava3.sqlclient.Tuple;
import io.vertx.sqlclient.PoolOptions;

public class EventsVerticle extends AbstractVerticle {

    private static final Logger logger = LoggerFactory.getLogger(EventsVerticle.class);

    private KafkaConsumer<String, JsonObject> eventConsumer;
    private KafkaProducer<String, JsonObject> updateProducer;
    private PgPool pgPool;

    @Override
    public Completable rxStart() {
        eventConsumer = KafkaConsumer.create(vertx, KafkaConfig.consumer("activity-service"));
        updateProducer = KafkaProducer.create(vertx, KafkaConfig.producer());
        pgPool = PgPool.pool(vertx, PgConfig.pgConnectOptions(), new PoolOptions());

        eventConsumer
                .handler(v -> {
                })
                .toFlowable()
                .flatMap(this::insertRecord)
                .flatMap(this::generateActivityUpdate)
                .flatMap(this::commitKafkaConsumerOffset)
                .doOnError(err -> logger.error("handle incoming.steps failed", err))
                .retryWhen(this::retryLater)
                .subscribe();

        eventConsumer.subscribe("incoming.steps");

        return Completable.complete();
    }

    private Flowable<KafkaConsumerRecord<String, JsonObject>> insertRecord(
            KafkaConsumerRecord<String, JsonObject> record) {
        JsonObject data = record.value();
        Tuple values = Tuple.of(
                data.getString("deviceId"),
                data.getLong("deviceSync"),
                data.getInteger("stepsCount"));

        return pgPool
                .preparedQuery(SqlQueries.insertStepEvent())
                .rxExecute(values)
                .map(rs -> record)
                .onErrorReturn(err -> {
                    if (duplicateKeyInsert(err)) {
                        return record;
                    }
                    throw new RuntimeException(err);
                })
                .toFlowable();
    }

    private Flowable<KafkaConsumerRecord<String, JsonObject>> generateActivityUpdate(
            KafkaConsumerRecord<String, JsonObject> record) {
        String deviceId = record.value().getString("deviceId");
        LocalDateTime now = LocalDateTime.now();
        String key = deviceId + ":" + now.getYear() + "-" + now.getMonth() + "-" + now.getDayOfMonth();

        return pgPool
                .preparedQuery(SqlQueries.stepsCountForToday())
                .rxExecute(Tuple.of(deviceId))
                .map(rs -> rs.iterator().next())
                .map(row -> new JsonObject()
                        .put("deviceId", deviceId)
                        .put("timestamp", row.getTemporal(0).toString())
                        .put("stepsCount", row.getLong(1)))
                .flatMap(json -> updateProducer.rxSend(KafkaProducerRecord.create("daily.step.updates", key, json)))
                .map(rs -> record)
                .toFlowable();
    }

    private Publisher<?> commitKafkaConsumerOffset(KafkaConsumerRecord<String, JsonObject> record) {
        return eventConsumer.rxCommit().toFlowable();
    }

    private boolean duplicateKeyInsert(Throwable err) {
        return (err instanceof PgException) && "23505".equals(((PgException) err).getSqlState());
    }

    private Flowable<Throwable> retryLater(Flowable<Throwable> errs) {
        return errs.delay(10, TimeUnit.SECONDS, RxHelper.scheduler(vertx));
    }

}
