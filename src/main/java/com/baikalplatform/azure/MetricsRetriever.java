package com.baikalplatform.azure;

import com.microsoft.aad.adal4j.AuthenticationException;
import com.microsoft.azure.AzureEnvironment;
import com.microsoft.azure.credentials.ApplicationTokenCredentials;
import io.prometheus.client.Counter;
import io.prometheus.client.Gauge;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.net.HttpURLConnection;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpHeaders;
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

    private static final int HTTP_TOO_MANY_REQUESTS = 429;

    private static final String HEADER_REMAINING_RESOURCE = "x-ms-ratelimit-remaining-resource";

    // XXX Gathering "x-ms-ratelimit-remaining-subscription-writes" would be useful as well.
    //     It is included in response to write operations, which we don't make yet.
    private static final String HEADER_REMAINING_SUBSCRIPTION_READS = "x-ms-ratelimit-remaining-subscription-reads";

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
    private final URI uri;
    private final HttpClient client;

    static {
        AZURE_CLIENT_ID = Optional.ofNullable(System.getenv("AZURE_CLIENT_ID")).orElseThrow();
        AZURE_CLIENT_SECRET = Optional.ofNullable(System.getenv("AZURE_CLIENT_SECRET")).orElseThrow();
        AZURE_TENANT_ID = Optional.ofNullable(System.getenv("AZURE_TENANT_ID")).orElseThrow();
    }

    public MetricsRetriever(String subscription) {
        this.uri = URI.create("https://management.azure.com/subscriptions/" + subscription + "/providers/Microsoft.Compute/virtualMachineScaleSets?api-version=2019-12-01");

        this.client = HttpClient.newBuilder()
                .connectTimeout(Duration.ofMillis(AZURE_CONNECTION_TIMEOUT_MILLIS))
                .version(HttpClient.Version.HTTP_2)
                .build();
    }

    public void run() {
        try {
            logger.debug("Run metrics retriever");
            var rates = getRateLimits();
            rates.forEach((rate, value) -> gauge.labels(rate).set(value));
        } catch (MetricsException e) {
            failuresCounter.inc();

            if (consecutiveFailures++ > MAX_CONSECUTIVE_FAILURES) {
                throw new RuntimeException("Unable to get rates after multiple retries", e);
            } else {
                logger.warn("Unable to get rates. Waiting for the next retry: {}", e.getMessage());
            }
        }
    }

    private Map<String, Integer> getRateLimits() throws MetricsException {
        HttpResponse<Void> response = sendHttpRequest();
        var statusCode = response.statusCode();
        logger.info("Health probe response ({})", statusCode);

        HttpHeaders headers = response.headers();
        var resourcesHeader = headers.allValues(HEADER_REMAINING_RESOURCE);

        var metrics = new HashMap<String, Integer>();

        if (statusCode == HttpURLConnection.HTTP_OK || statusCode == HTTP_TOO_MANY_REQUESTS) {
            var subscriptionReads = headers.firstValue(HEADER_REMAINING_SUBSCRIPTION_READS).orElse("0");
            logger.info("Header: {} = {}", HEADER_REMAINING_SUBSCRIPTION_READS, subscriptionReads);
            metrics.put(HEADER_REMAINING_SUBSCRIPTION_READS, Integer.parseInt(subscriptionReads));

            resourcesHeader.forEach(header -> {
                logger.info("Header: {}", header);
                var elements = header.split(",");
                Stream.of(elements).forEach(s -> {
                    var values = s.split(";");
                    metrics.put(values[0], Integer.parseInt(values[1]));
                });
            });
        } else {
            metrics.clear();
            throw new MetricsException("Unexpected response code " + statusCode);
        }

        return metrics;
    }

    private HttpResponse<Void> sendHttpRequest() throws MetricsException {
        HttpResponse<Void> response;
        try {
            HttpRequest request = HttpRequest.newBuilder()
                    .GET()
                    .uri(uri)
                    .setHeader("Authorization", "Bearer " + requestAccessToken())
                    .setHeader("User-Agent", "palmerabollo/azure-throttling-exporter") // XXX include version
                    .build();

            response = client.send(request, HttpResponse.BodyHandlers.discarding());
        } catch (IOException | InterruptedException | AuthenticationException e) {
            throw new MetricsException("Failure sending HTTP request", e);
        }
        return response;
    }

    private String requestAccessToken() throws IOException {
        var credentials = new ApplicationTokenCredentials(
                AZURE_CLIENT_ID, AZURE_TENANT_ID, AZURE_CLIENT_SECRET, AzureEnvironment.AZURE);

        logger.debug("Requesting new token");
        var token = credentials.getToken("https://management.azure.com");
        logger.debug("Requested token ok {}...", token.substring(0, 10));
        return token;
    }
}
