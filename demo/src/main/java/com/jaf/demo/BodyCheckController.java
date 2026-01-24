package com.jaf.demo;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api")
class BodyCheckController {

    private static final Logger log = LoggerFactory.getLogger(BodyCheckController.class);
    private static final String EXPECTED_BODY = "demo-secret";

    @PostMapping("/check-body")
    ResponseEntity<String> checkBody(@RequestBody String body) {
        if (EXPECTED_BODY.equals(body)) {
            log.info("Received expected POST body");
        }
        return ResponseEntity.ok("received");
    }
}
