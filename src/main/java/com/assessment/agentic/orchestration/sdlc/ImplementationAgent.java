package com.assessment.agentic.orchestration.sdlc;

import static com.assessment.agentic.orchestration.sdlc.RequirementAnalyzer.*;

import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import com.assessment.agentic.orchestration.StageContext;
import com.assessment.agentic.orchestration.StageHandler;
import com.assessment.agentic.orchestration.StageResult;

/**
 * IMPLEMENTATION: codebase reasoning. For each required capability the agent inspects the compiled
 * codebase (reflection over the url module) to decide whether it is already implemented, then emits
 * a reviewable change-set proposal. The change set is never applied to the working tree by an
 * agent; that is an explicit autonomy boundary. Any GAP yields a BLOCKED verdict so release cannot
 * proceed on unimplemented scope.
 */
public class ImplementationAgent implements StageHandler {

    private static final String PKG = "com.assessment.agentic.url.";
    private static final String SRC = "src/main/java/com/assessment/agentic/url/";
    private static final String TEST = "src/test/java/com/assessment/agentic/url/";

    private static final Map<String, List<String>> REQUIRED_SYMBOLS = Map.of(
            CREATE, List.of("UrlShortenerService#createShortUrl", "UrlShortenerController#createShortUrl"),
            REDIRECT, List.of("UrlShortenerService#getOriginalUrl", "UrlShortenerController#redirect"),
            ANALYTICS, List.of("UrlShortenerService#getAnalytics", "UrlMappingRepository#incrementClickCount"),
            VALIDATION, List.of("UrlShortenerService#validateUrl", "CreateUrlRequest#getOriginalUrl"),
            NOT_FOUND, List.of("UrlNotFoundException#<init>", "UrlExceptionHandler#handleUrlNotFound"),
            REGRESSION, List.of("UrlShortenerService#createShortUrl", "UrlShortenerService#getOriginalUrl", "UrlShortenerService#getAnalytics"),
            SECURITY_HARDENING, List.of("UrlShortenerService#validateUrl"));

    @Override
    public StageResult execute(StageContext context) {
        Map<String, Object> requirements = context.input(SdlcStages.REQUIREMENTS);
        List<Map<String, Object>> coverage = new ArrayList<>();
        List<Map<String, Object>> changeSet = new ArrayList<>();
        List<String> gaps = new ArrayList<>();

        for (String capability : Content.strings(requirements, "capabilities")) {
            List<String> symbols = REQUIRED_SYMBOLS.get(capability);
            if (symbols == null) continue; // e.g. REDIRECT_LATENCY: verified by measurement, not by code inspection
            List<String> missing = symbols.stream().filter(symbol -> !exists(symbol)).toList();
            Map<String, Object> entry = new LinkedHashMap<>();
            entry.put("capability", capability);
            entry.put("symbols", symbols);
            entry.put("status", missing.isEmpty() ? "IMPLEMENTED" : "GAP");
            entry.put("missingSymbols", missing);
            coverage.add(entry);
            if (!missing.isEmpty()) gaps.add(capability);
            for (String symbol : symbols) {
                String file = symbol.substring(0, symbol.indexOf('#')) + ".java";
                changeSet.add(change(SRC + file, missing.contains(symbol) ? "MODIFY" : "VERIFY_ONLY",
                        capability + (missing.contains(symbol) ? ": implement " + symbol : ": existing " + symbol + " satisfies the requirement")));
            }
        }
        if (Content.strings(requirements, "capabilities").contains(REGRESSION) || Content.strings(requirements, "capabilities").contains(NOT_FOUND)) {
            changeSet.add(change(TEST + "UrlShortenerIntegrationTest.java", "VERIFY_ONLY", "Regression tests must cover every acceptance criterion"));
        }

        Map<String, Object> output = new LinkedHashMap<>();
        output.put("mode", "CHANGE_SET_PROPOSAL");
        output.put("appliedToWorkingTree", false);
        output.put("capabilityCoverage", coverage);
        output.put("changeSet", dedupe(changeSet));
        output.put("gaps", gaps);
        output.put("reviewNote", "Engineer applies MODIFY entries via a normal PR; the pipeline re-verifies after the change.");
        return gaps.isEmpty()
                ? StageResult.passed("All " + coverage.size() + " capabilities are implemented in the codebase", output)
                : StageResult.blocked("Implementation gaps: " + gaps, output);
    }

    static boolean exists(String symbol) {
        String[] parts = symbol.split("#");
        try {
            Class<?> type = Class.forName(PKG + parts[0]);
            if ("<init>".equals(parts[1])) return type.getDeclaredConstructors().length > 0;
            return Arrays.stream(type.getDeclaredMethods()).map(Method::getName).anyMatch(parts[1]::equals);
        } catch (ClassNotFoundException missing) {
            return false;
        }
    }

    private static Map<String, Object> change(String path, String type, String reason) {
        Map<String, Object> change = new LinkedHashMap<>();
        change.put("path", path);
        change.put("changeType", type);
        change.put("reason", reason);
        return change;
    }

    private static List<Map<String, Object>> dedupe(List<Map<String, Object>> changes) {
        Map<String, Map<String, Object>> byPath = new LinkedHashMap<>();
        for (Map<String, Object> change : changes) {
            byPath.merge(Content.str(change, "path"), change, (a, b) -> "MODIFY".equals(b.get("changeType")) ? b : a);
        }
        return new ArrayList<>(byPath.values());
    }
}
