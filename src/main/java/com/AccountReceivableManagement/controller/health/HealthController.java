package com.AccountReceivableManagement.controller.health;

import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

import java.time.Instant;
import java.util.Map;

/**
 * Lightweight liveness check for load balancers / container health checks.
 * Served at {@code GET /paves/ar/health} (context path is {@code /paves/ar}).
 */
@RestController
public class HealthController {

    @GetMapping("/health")
    public ResponseEntity<Map<String, Object>> health() {
        return ResponseEntity.ok(Map.of(
                "status", "UP",
                "service", "account-receivable",
                "timestamp", Instant.now().toString()
        ));
    }
}
