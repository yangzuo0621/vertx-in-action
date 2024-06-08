package com.example.congrats;

import java.util.concurrent.TimeUnit;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import io.reactivex.rxjava3.core.Completable;
import io.reactivex.rxjava3.core.Flowable;
import io.reactivex.rxjava3.core.Single;
import io.vertx.core.json.JsonObject;
import io.vertx.ext.mail.MailMessage;
import io.vertx.ext.mail.MailResult;
import io.vertx.rxjava3.core.AbstractVerticle;
import io.vertx.rxjava3.core.RxHelper;
import io.vertx.rxjava3.core.Vertx;
import io.vertx.rxjava3.ext.mail.MailClient;
import io.vertx.rxjava3.ext.web.client.HttpResponse;
import io.vertx.rxjava3.ext.web.client.WebClient;
import io.vertx.rxjava3.ext.web.codec.BodyCodec;
import io.vertx.rxjava3.kafka.client.consumer.KafkaConsumer;
import io.vertx.rxjava3.kafka.client.consumer.KafkaConsumerRecord;

public class CongratsVerticle extends AbstractVerticle {

    private static final Logger logger = LoggerFactory.getLogger(CongratsVerticle.class);

    private MailClient mailClient;
    private WebClient webClient;

    @Override
    public Completable rxStart() {
        mailClient = MailClient.createShared(vertx, MailerConfig.config());
        webClient = WebClient.create(vertx);

        KafkaConsumer<String, JsonObject> consumer = KafkaConsumer.create(vertx,
                KafkaConfig.consumerConfig("congrats-service"));
        consumer
                .handler(record -> {
                })
                .toFlowable()
                .filter(this::above10k)
                .distinct(KafkaConsumerRecord::key)
                .flatMapSingle(this::sendmail)
                .doOnError(err -> logger.error("send email failed", err))
                .retryWhen(this::retryLater)
                .subscribe(mailResult -> logger.info("Congratulated {}",
                        mailResult.getRecipients()));
        consumer.subscribe("daily.step.updates");

        return Completable.complete();
    }

    private boolean above10k(KafkaConsumerRecord<String, JsonObject> record) {
        return record.value().getInteger("stepsCount") >= 10_000;
    }

    private Flowable<Throwable> retryLater(Flowable<Throwable> errs) {
        return errs.delay(10, TimeUnit.SECONDS, RxHelper.scheduler(vertx));
    }

    private Single<MailResult> sendmail(KafkaConsumerRecord<String, JsonObject> record) {
        String deviceId = record.value().getString("deviceId");
        Integer stepsCount = record.value().getInteger("stepsCount");
        return webClient
                .get(3000, "localhost", "/owns/" + deviceId)
                .as(BodyCodec.jsonObject())
                .rxSend()
                .map(HttpResponse::body)
                .map(json -> json.getString("username"))
                .flatMap(this::getEmail)
                .map(email -> makeEmail(stepsCount, email))
                .flatMap(mailClient::rxSendMail);
    }

    private Single<String> getEmail(String username) {
        return webClient
                .get(3000, "localhost", "/" + username)
                .as(BodyCodec.jsonObject())
                .rxSend()
                .map(HttpResponse::body)
                .map(json -> json.getString("email"));
    }

    private MailMessage makeEmail(Integer stepsCount, String email) {
        return new MailMessage()
                .setFrom("noreply@tenksteps.tld")
                .setTo(email)
                .setSubject("You made it!")
                .setText("Congratulations on reaching " + stepsCount + " steps today!\n\n- The 10k Steps Team\n");
    }

    public static void main(String[] args) {
        Vertx vertx = Vertx.vertx();
        vertx.rxDeployVerticle(new CongratsVerticle())
                .subscribe(ok -> logger.info("Ready to notify people reaching more than 10k steps"));
    }

}
