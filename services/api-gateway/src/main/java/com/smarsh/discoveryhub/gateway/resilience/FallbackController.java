package com.smarsh.discoveryhub.gateway.resilience;

import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.time.Instant;
import java.util.Map;

/**
 * Target of every route's {@code CircuitBreaker} filter {@code fallbackUri}
 * (execution-plan Day 4: resiliency demo — a downed downstream must degrade
 * gracefully at the gateway instead of the caller hanging or getting a raw
 * connection-refused). Always 503 — the caller retries later rather than the
 * gateway silently returning a fabricated "success".
 */
@RestController
public class FallbackController {

    @RequestMapping("/fallback")
    public ResponseEntity<Map<String, Object>> fallback() {
        return ResponseEntity.status(HttpStatus.SERVICE_UNAVAILABLE).body(Map.of(
                "status", 503,
                "code", "UPSTREAM_UNAVAILABLE",
                "detail", "The upstream service is currently unavailable (circuit open or request timed out). Retry shortly.",
                "timestamp", Instant.now().toString()));
    }
}
