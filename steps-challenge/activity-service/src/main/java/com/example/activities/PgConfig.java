package com.example.activities;

import io.vertx.pgclient.PgConnectOptions;

class PgConfig {
    public static PgConnectOptions pgConnectOptions() {
        return new PgConnectOptions()
                .setHost("localhost")
                .setDatabase("postgres")
                .setUser("postgres")
                .setPassword("vertx-in-action");
    }
}
