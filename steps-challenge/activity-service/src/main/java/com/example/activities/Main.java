package com.example.activities;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import io.vertx.rxjava3.core.Vertx;

public class Main {

    private static final Logger logger = LoggerFactory.getLogger(Main.class);

    public static void main(String[] args) {
        Vertx vertx = Vertx.vertx();
        vertx.rxDeployVerticle(new EventsVerticle())
                .flatMap(id -> vertx.rxDeployVerticle(new ActivityApiVerticle()))
                .subscribe(
                        ok -> logger.info("HTTP server started on port {}", ActivityApiVerticle.HTTP_PORT),
                        err -> logger.error("Failed to deploy verticle", err));
    }
}
