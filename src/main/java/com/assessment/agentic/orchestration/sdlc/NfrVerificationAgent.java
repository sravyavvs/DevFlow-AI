package com.assessment.agentic.orchestration.sdlc;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import com.assessment.agentic.orchestration.StageContext;
import com.assessment.agentic.orchestration.StageFailureException;
import com.assessment.agentic.orchestration.StageHandler;
import com.assessment.agentic.orchestration.StageResult;
import com.assessment.agentic.url.UrlMapping;
import com.assessment.agentic.url.UrlShortenerService;

/**
 * NFR_VERIFICATION: produces measured evidence for quality goals, or an explicit BLOCKED verdict
 * when the goal has no measurable target. It never reports a pass it did not measure.
 */
public class NfrVerificationAgent implements StageHandler {

    private static final int WARMUP = 20;
    private static final int SAMPLES = 200;
    private static final List<String> HOSTILE_DESTINATIONS = List.of(
            "javascript:alert(1)", "data:text/html;base64,PHNjcmlwdD4=", "file:///etc/passwd", "ftp://example.com/x", "http://", "https:///no-host");

    private final UrlShortenerService service;

    public NfrVerificationAgent(UrlShortenerService service) {
        this.service = service;
    }

    @Override
    public StageResult execute(StageContext context) {
        Map<String, Object> nfr = Content.map(context.input(SdlcStages.REQUIREMENTS), "nfr");
        Map<String, Object> evidence = new LinkedHashMap<>();
        List<String> failures = new ArrayList<>();

        Object target = nfr.get("latencyP95TargetMs");
        if (target != null) {
            double p95 = measureRedirectP95Millis(context.pipelineId());
            boolean ok = p95 <= ((Number) target).doubleValue();
            evidence.put("latency", Map.of("method", "In-process microbenchmark: " + SAMPLES + " lookups after " + WARMUP + " warm-up calls",
                    "p95Ms", Math.round(p95 * 1000) / 1000.0, "targetMs", target, "status", ok ? "PASSED" : "FAILED",
                    "caveat", "Not a load test: single-threaded, no network hop, local H2."));
            if (!ok) failures.add("p95 " + p95 + " ms exceeds target " + target + " ms");
        }
        if (Boolean.TRUE.equals(nfr.get("securityHardening"))) {
            List<String> accepted = HOSTILE_DESTINATIONS.stream().filter(this::accepts).toList();
            evidence.put("security", Map.of("probes", HOSTILE_DESTINATIONS, "accepted", accepted,
                    "status", accepted.isEmpty() ? "PASSED" : "FAILED"));
            if (!accepted.isEmpty()) failures.add("Hostile destinations accepted: " + accepted);
        }
        List<String> unmeasurable = Content.strings(nfr, "unmeasurableConcerns");
        evidence.put("notVerifiable", unmeasurable);

        if (!failures.isEmpty()) throw new StageFailureException("Quality goals not met: " + failures, evidence);
        if (!unmeasurable.isEmpty()) {
            return StageResult.blocked("No measurable target for " + unmeasurable + "; refusing to report a pass", evidence);
        }
        return StageResult.passed("Quality goals verified with measured evidence", evidence);
    }

    private double measureRedirectP95Millis(String pipelineId) {
        UrlMapping fixture = ServiceProbe.call("create benchmark fixture",
                () -> service.createShortUrl("https://example.com/devflow/" + pipelineId + "/benchmark"));
        try {
            for (int i = 0; i < WARMUP; i++) service.getOriginalUrl(fixture.getShortCode());
            long[] samples = new long[SAMPLES];
            for (int i = 0; i < SAMPLES; i++) {
                long start = System.nanoTime();
                ServiceProbe.call("benchmark lookup", () -> service.getOriginalUrl(fixture.getShortCode()));
                samples[i] = System.nanoTime() - start;
            }
            java.util.Arrays.sort(samples);
            return samples[(int) Math.ceil(SAMPLES * 0.95) - 1] / 1_000_000.0;
        } finally {
            service.deleteShortUrl(fixture.getShortCode());
        }
    }

    private boolean accepts(String destination) {
        try {
            UrlMapping created = service.createShortUrl(destination);
            service.deleteShortUrl(created.getShortCode());
            return true;
        } catch (IllegalArgumentException rejected) {
            return false;
        }
    }
}
