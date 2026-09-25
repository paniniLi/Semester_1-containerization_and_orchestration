package org.panini.controllers;

import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.tracing.ScopedSpan;
import io.micrometer.tracing.Span;
import io.micrometer.tracing.Tracer;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.util.concurrent.ThreadLocalRandom;

@Slf4j
@RestController
public class SystemController {

    private final HttpClient httpClient = HttpClient.newHttpClient();
    private final Counter errorCounter;
    private final Tracer tracer;

    public SystemController(MeterRegistry meterRegistry, Tracer tracer) {
        this.errorCounter = Counter.builder("app.errors")
                .description("Total number of application errors")
                .register(meterRegistry);
        this.tracer = tracer;
    }

    @GetMapping("/health")
    public ResponseEntity<String> health() {
        log.atInfo()
                .addKeyValue("endpoint", "/health")
                .log("Health request");

        return ResponseEntity.ok("ok");
    }

    @GetMapping("/fail")
    public ResponseEntity<String> fail() {
        errorCounter.increment();

        IllegalStateException failure = new IllegalStateException("Intentional failure");

        Span currentSpan = tracer.currentSpan();
        if (currentSpan != null) currentSpan.error(failure);

        log.atError()
                .addKeyValue("endpoint", "/fail")
                .log("Intentional failure");

        return ResponseEntity.internalServerError().body("Internal server error");
    }

    @GetMapping("/slow")
    public ResponseEntity<String> slow() throws InterruptedException {
        int delaySeconds = ThreadLocalRandom.current().nextInt(1, 4);

        ScopedSpan slowSpan = tracer.startScopedSpan("slow-op");

        try {
            log.atInfo()
                    .addKeyValue("endpoint", "/slow")
                    .addKeyValue("delay_seconds", delaySeconds)
                    .log("Slow operation started");

            Thread.sleep(delaySeconds * 1000L);
        } catch (InterruptedException exception) {
            slowSpan.error(exception);
            Thread.currentThread().interrupt();
            throw exception;
        } finally {
            slowSpan.end();
        }

        return ResponseEntity.ok("Response delayed by " + delaySeconds + " seconds");
    }

    @GetMapping("/load")
    public ResponseEntity<String> load() {
        int requests = 20;

        log.atInfo()
                .addKeyValue("endpoint", "/load")
                .addKeyValue("generated_requests", requests)
                .log("Generating load");

        for (int i = 0; i < requests; i++) {
            HttpRequest request = HttpRequest.newBuilder()
                    .uri(URI.create("http://127.0.0.1:8080/health"))
                    .GET()
                    .build();

            httpClient.sendAsync(
                    request,
                    HttpResponse.BodyHandlers.discarding()
            );
        }

        return ResponseEntity.ok(
                "Generated " + requests + " requests"
        );
    }
}