package com.huseyin.taskhub.ping;

import java.util.Map;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

/** Temporary wiring check (SPEC § Session 1, requirement 6). Removed in T6 once real endpoints exist. */
@RestController
class PingController {

    @GetMapping("/api/v1/ping")
    Map<String, String> ping() {
        return Map.of("status", "ok");
    }
}
