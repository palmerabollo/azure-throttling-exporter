package com.baikalplatform.azure;

import com.microsoft.azure.AzureEnvironment;
import com.microsoft.azure.credentials.ApplicationTokenCredentials;
import io.prometheus.client.Counter;
import io.prometheus.client.Gauge;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.HashMap;
import java.util.Map;
import java.util.Optional;
import java.util.stream.Stream;

public class MetricsRetriever implements Runnable {
    private static final Logger logger = LoggerFactory.getLogger(MetricsRetriever.class);

    private static final int MAX_CONSECUTIVE_FAILURES = 2;
    private static final int AZURE_CONNECTION_TIMEOUT_MILLIS = 4000;

    private static final String AZURE_CLIENT_ID;
    private static final String AZURE_CLIENT_SECRET;
    private static final String AZURE_TENANT_ID;

    private static final Gauge gauge = Gauge.build()
            .name("ms_ratelimit_remaining_resource_gauge")
            .help("Remaining resource reads before reaching the throttling threshold")
            .labelNames("rate")
            .register();

    private static final Counter failuresCounter = Counter.build()
            .name("ms_ratelimit_failures_total")
            .help("Number of failures trying to obtain Azure rate limits")
            .register();

    private int consecutiveFailures = 0;
    private final String subscription;
    private final URI uri;

    static {
        AZURE_CLIENT_ID = Optional.ofNullable(System.getenv("AZURE_CLIENT_ID")).orElseThrow();
        AZURE_CLIENT_SECRET = Optional.ofNullable(System.getenv("AZURE_CLIENT_SECRET")).orElseThrow();
        AZURE_TENANT_ID = Optional.ofNullable(System.getenv("AZURE_TENANT_ID")).orElseThrow();
    }

    public MetricsRetriever(String subscription) {
        this.subscription = subscription;
        this.uri = URI.create("https://management.azure.com/subscriptions/" + subscription + "/providers/Microsoft.Compute/virtualMachineScaleSets?api-version=2019-12-01");
    }

    public void run() {
        try {
            logger.debug("Run");
            var rates = getRateLimits();
            rates.forEach((rate, value) -> gauge.labels(rate).set(value));
        } catch (Exception e) {
            failuresCounter.inc();

            if (consecutiveFailures++ > MAX_CONSECUTIVE_FAILURES) {
                throw new RuntimeException("Unable to get rates after multiple retries", e);
            } else {
                logger.warn("Unable to get rates. Waiting for the next retry: {}", e.getMessage());
            }
        }
    }

    private String requestAccessToken() throws IOException {
        var credentials = new ApplicationTokenCredentials(
                AZURE_CLIENT_ID, AZURE_TENANT_ID, AZURE_CLIENT_SECRET, AzureEnvironment.AZURE);

        logger.debug("Requesting new token");
        var token = credentials.getToken("https://management.azure.com");
        logger.debug("Requested token ok {}...", token.substring(0, 10));
        return token;
    }

    private Map<String, Integer> getRateLimits() throws MetricsException {
        var client = HttpClient.newBuilder()
                .connectTimeout(Duration.ofMillis(AZURE_CONNECTION_TIMEOUT_MILLIS))
                .version(HttpClient.Version.HTTP_2)
                .build();

        HttpResponse<Void> response;
        try {
            HttpRequest request = HttpRequest.newBuilder()
                    .GET()
                    .uri(uri)
                    .setHeader("Authorization", "Bearer " + requestAccessToken())
                    .build();

            response = client.send(request, HttpResponse.BodyHandlers.discarding());
        } catch (IOException | InterruptedException e) {
            throw new MetricsException("Failure sending HTTP request", e);
        }

        var metrics = new HashMap<String, Integer>();

        if (response.statusCode() < 300) {
            var optionalHeader = response.headers().firstValue("x-ms-ratelimit-remaining-resource");
            logger.info("Health probe ok {}", response.statusCode());

            optionalHeader.ifPresent(header -> {
                var elements = header.split(",");
                Stream.of(elements).forEach(s -> {
                    var values = s.split(";");
                    metrics.put(values[0], Integer.parseInt(values[1]));
                });
            });
        } else {
            throw new MetricsException("Unexpected response code " + response.statusCode());
        }

        return metrics;
    }
}
