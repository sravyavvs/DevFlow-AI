
package com.assessment.agentic.url;

import java.util.Map;

import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.servlet.support.ServletUriComponentsBuilder;
import org.springframework.web.servlet.view.RedirectView;

import jakarta.validation.Valid;

@RestController
public class UrlShortenerController {

    private final UrlShortenerService service;

    public UrlShortenerController(UrlShortenerService service) {
        this.service = service;
    }

    @PostMapping("/api/urls")
    public ResponseEntity<Map<String, String>> createShortUrl(
            @Valid @RequestBody CreateUrlRequest request) {

        UrlMapping mapping =
                service.createShortUrl(request.getOriginalUrl());

        Map<String, String> response = Map.of(
                "shortCode", mapping.getShortCode(),
                "originalUrl", mapping.getOriginalUrl(),
                "shortUrl", ServletUriComponentsBuilder.fromCurrentContextPath()
                        .path("/{code}").buildAndExpand(mapping.getShortCode()).toUriString()
        );

        return ResponseEntity.status(HttpStatus.CREATED).body(response);
    }

    @GetMapping("/{shortCode:[a-zA-Z0-9]{8}}")
    public RedirectView redirect(@PathVariable String shortCode) {
        String originalUrl = service.getOriginalUrl(shortCode);

        RedirectView redirectView = new RedirectView(originalUrl);
        redirectView.setStatusCode(HttpStatus.FOUND);

        return redirectView;
    }

    @GetMapping("/api/urls/{shortCode}/analytics")
    public ResponseEntity<Map<String, Object>> getAnalytics(
            @PathVariable String shortCode) {

        UrlMapping mapping = service.getAnalytics(shortCode);

        Map<String, Object> response = Map.of(
                "shortCode", mapping.getShortCode(),
                "originalUrl", mapping.getOriginalUrl(),
                "clickCount", mapping.getClickCount(),
                "createdAt", mapping.getCreatedAt().toString()
        );

        return ResponseEntity.ok(response);
    }
}
