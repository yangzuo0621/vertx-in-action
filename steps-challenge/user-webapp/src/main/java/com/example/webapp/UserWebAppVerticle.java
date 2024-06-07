package com.example.webapp;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import io.reactivex.rxjava3.core.Completable;
import io.vertx.rxjava3.core.AbstractVerticle;
import io.vertx.rxjava3.core.Vertx;
import io.vertx.rxjava3.ext.web.Router;
import io.vertx.rxjava3.ext.web.handler.StaticHandler;

public class UserWebAppVerticle extends AbstractVerticle {

    private static final int HTTP_PORT = 8080;
    private static final Logger logger = LoggerFactory.getLogger(UserWebAppVerticle.class);

    @Override
    public Completable rxStart() {
        Router router = Router.router(vertx);
        router.route().handler(StaticHandler.create("webroot/assets"));
        router.get("/*").handler(ctx -> ctx.reroute("/index.html"));
        return vertx.createHttpServer()
                .requestHandler(router)
                .rxListen(HTTP_PORT)
                .ignoreElement();
    }

    public static void main(String[] args) {
        Vertx vertx = Vertx.vertx();
        vertx
                .rxDeployVerticle(new UserWebAppVerticle())
                .subscribe(
                        ok -> logger.info("HTTP server started on port {}", HTTP_PORT),
                        err -> logger.error("Failed to start HTTP server", err));
    }

}
