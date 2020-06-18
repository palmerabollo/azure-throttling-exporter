package com.baikalplatform.azure;

import io.prometheus.client.exporter.HTTPServer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.util.Optional;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

public class AzureMetricsExporter {
    private static final Logger logger = LoggerFactory.getLogger(MetricsRetriever.class);

    private static final int DEFAULT_PROMETHEUS_PORT = 8080;
    private static final int DEFAULT_POLL_INTERVAL_MILLIS = 60000;

    public static void main(String[] args) throws IOException {
        int pollInterval = env("POLL_INTERVAL_MILLIS", DEFAULT_POLL_INTERVAL_MILLIS);
        int prometheusPort = env("PROMETHEUS_PORT", DEFAULT_PROMETHEUS_PORT);

        var subscription = Optional.ofNullable(System.getenv("AZURE_SUBSCRIPTION_ID")).orElseThrow();
        logger.info("Starting Azure throttling exporter (subscription={})", subscription);
        var retriever = new MetricsRetriever(subscription);

        var executor = Executors.newSingleThreadScheduledExecutor();
        executor.scheduleAtFixedRate(retriever, 0, pollInterval, TimeUnit.MILLISECONDS);

        logger.info("Starting HTTP server on port {}", prometheusPort);
        new HTTPServer(prometheusPort, true);
    }

    private static int env(String name, int defaultValue) {
        String value = System.getenv(name);
        return value == null ? defaultValue : Integer.parseInt(value);
    }
}
