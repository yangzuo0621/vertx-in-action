package com.example.eventstats;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import io.reactivex.rxjava3.core.Completable;
import io.vertx.core.json.JsonObject;
import io.vertx.rxjava3.core.AbstractVerticle;
import io.vertx.rxjava3.ext.web.Router;
import io.vertx.rxjava3.ext.web.RoutingContext;

public class FakeUserService extends AbstractVerticle {
    private static final Logger logger = LoggerFactory.getLogger(FakeUserService.class);

    private String deviceId;

    @Override
    public Completable rxStart() {
        Router router = Router.router(vertx);
        router.get("/owns/:deviceId").handler(this::owns);
        router.get("/:username").handler(this::username);
        return vertx.createHttpServer()
                .requestHandler(router)
                .rxListen(3000)
                .ignoreElement();
    }

    private void username(RoutingContext ctx) {
        logger.info("User data request {}", ctx.request().path());
        JsonObject notAllData = new JsonObject()
                .put("username", "Foo")
                .put("email", "foo@mail.tld")
                .put("deviceId", deviceId)
                .put("city", "Lyon")
                .put("makePublic", true);
        ctx.response()
                .putHeader("Content-Type", "application/json")
                .end(notAllData.encode());
    }

    private void owns(RoutingContext ctx) {
        logger.info("Device ownership request {}", ctx.request().path());
        deviceId = ctx.pathParam("deviceId");
        JsonObject notAllData = new JsonObject()
                .put("username", "Foo")
                .put("deviceId", deviceId);
        ctx.response()
                .putHeader("Content-Type", "application/json")
                .end(notAllData.encode());
    }
}
