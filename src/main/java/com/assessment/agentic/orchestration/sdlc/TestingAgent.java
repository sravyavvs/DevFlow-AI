package com.assessment.agentic.orchestration.sdlc;

import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import com.assessment.agentic.orchestration.StageContext;
import com.assessment.agentic.orchestration.StageFailureException;
import com.assessment.agentic.orchestration.StageHandler;
import com.assessment.agentic.orchestration.StageResult;
import com.assessment.agentic.url.UrlExceptionHandler;
import com.assessment.agentic.url.UrlMapping;
import com.assessment.agentic.url.UrlNotFoundException;
import com.assessment.agentic.url.UrlShortenerService;

/**
 * TESTING: executes every RUNTIME_CHECK acceptance criterion against the real service bean using an
 * isolated fixture that is always deleted (self-compensating). A failed check is a permanent
 * failure; a transient data-access error is retried by the orchestrator.
 */
public class TestingAgent implements StageHandler {

    /** Generated codes are lowercase hex, so this code can never collide with real data. */
    static final String UNKNOWN_CODE = "zzzzzzzz";

    private final UrlShortenerService service;

    public TestingAgent(UrlShortenerService service) {
        this.service = service;
    }

    @Override
    public StageResult execute(StageContext context) {
        List<Map<String, Object>> criteria = Content.maps(context.input(SdlcStages.REQUIREMENTS), "acceptanceCriteria").stream()
                .filter(c -> "RUNTIME_CHECK".equals(c.get("verification"))).toList();
        String target = "https://example.com/devflow/" + context.pipelineId() + "/attempt-" + context.attempt();
        UrlMapping fixture = null;
        List<Map<String, Object>> checks = new ArrayList<>();
        boolean cleanedUp = true;
        try {
            fixture = ServiceProbe.call("create fixture", () -> service.createShortUrl(target));
            String code = fixture.getShortCode();
            String resolved = ServiceProbe.call("resolve fixture", () -> service.getOriginalUrl(code));
            long clicks = ServiceProbe.call("read analytics", () -> service.getAnalytics(code)).getClickCount();
            for (Map<String, Object> criterion : criteria) {
                String id = Content.str(criterion, "id");
                switch (id) {
                    case "AC-CREATE" -> checks.add(check(id, code != null && code.matches("[0-9a-f]{8}"), "Created code " + code));
                    case "AC-REDIRECT" -> checks.add(check(id, target.equals(resolved), "Resolved " + resolved));
                    case "AC-ANALYTICS" -> checks.add(check(id, clicks == 1, "clickCount after one redirect = " + clicks));
                    case "AC-VALIDATION" -> checks.add(check(id, rejects("ftp://invalid.example") && rejects("not-a-url"), "ftp:// and malformed URLs rejected"));
                    case "AC-404" -> checks.add(check(id, unknownCodeIsNotFound(), "Unknown code raises UrlNotFoundException handled as 404"));
                    case "AC-REGRESSION" -> checks.add(check(id, target.equals(resolved) && clicks == 1, "create -> resolve -> analytics chain intact"));
                    default -> checks.add(Map.of("criterion", id, "status", "NOT_RUN", "detail", "No runtime check defined"));
                }
            }
        } finally {
            if (fixture != null) {
                try {
                    service.deleteShortUrl(fixture.getShortCode());
                } catch (RuntimeException cleanupFailure) {
                    cleanedUp = false;
                }
            }
        }
        Map<String, Object> report = new LinkedHashMap<>();
        report.put("executionMode", "IN_PROCESS_SERVICE_CHECKS");
        report.put("attempt", context.attempt());
        report.put("checks", checks);
        report.put("fixtureCleanedUp", cleanedUp);
        report.put("limitations", "Service-level checks; HTTP-level contracts are covered by UrlShortenerIntegrationTest in the build.");
        List<String> failed = checks.stream().filter(c -> "FAILED".equals(c.get("status"))).map(c -> Content.str(c, "criterion")).toList();
        if (!failed.isEmpty()) throw new StageFailureException("Acceptance checks failed: " + failed, report);
        if (!cleanedUp) throw new StageFailureException("Test fixture could not be removed", report);
        boolean anyNotRun = checks.stream().anyMatch(c -> "NOT_RUN".equals(c.get("status")));
        return anyNotRun
                ? StageResult.blocked("Some criteria have no runtime check", report)
                : StageResult.passed(checks.size() + "/" + checks.size() + " acceptance checks passed", report);
    }

    private boolean rejects(String url) {
        try {
            service.createShortUrl(url);
            return false;
        } catch (IllegalArgumentException expected) {
            return true;
        }
    }

    private boolean unknownCodeIsNotFound() {
        try {
            service.getOriginalUrl(UNKNOWN_CODE);
            return false;
        } catch (UrlNotFoundException expected) {
            return Arrays.stream(UrlExceptionHandler.class.getDeclaredMethods())
                    .map(Method::getParameterTypes)
                    .anyMatch(types -> types.length == 1 && types[0] == UrlNotFoundException.class);
        }
    }

    private static Map<String, Object> check(String criterion, boolean passed, String detail) {
        Map<String, Object> check = new LinkedHashMap<>();
        check.put("criterion", criterion);
        check.put("status", passed ? "PASSED" : "FAILED");
        check.put("detail", detail);
        return check;
    }
}
