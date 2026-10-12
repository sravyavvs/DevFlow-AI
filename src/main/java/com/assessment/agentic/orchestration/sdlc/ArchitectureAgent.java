package com.assessment.agentic.orchestration.sdlc;

import static com.assessment.agentic.orchestration.sdlc.RequirementAnalyzer.*;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import com.assessment.agentic.orchestration.StageContext;
import com.assessment.agentic.orchestration.StageHandler;
import com.assessment.agentic.orchestration.StageResult;

/** ARCHITECTURE: API contract + data model for greenfield, module impact analysis for brownfield. */
public class ArchitectureAgent implements StageHandler {

    private static final String BASE = "src/main/java/com/assessment/agentic/url/";

    @Override
    public StageResult execute(StageContext context) {
        Map<String, Object> requirements = context.input(SdlcStages.REQUIREMENTS);
        List<String> capabilities = Content.strings(requirements, "capabilities");
        String scenario = Content.str(requirements, "scenario");

        Map<String, Object> design = new LinkedHashMap<>();
        design.put("scenario", scenario);
        design.put("apiContract", contract(capabilities));
        design.put("dataModel", Map.of("table", "url_mappings", "columns", List.of(
                "short_code VARCHAR(8) PRIMARY KEY", "original_url VARCHAR(2048) NOT NULL",
                "created_at TIMESTAMP NOT NULL", "click_count BIGINT NOT NULL")));
        design.put("decisions", List.of(
                "Short code: 8 hex chars from a random UUID; collisions retried up to 5 times using the primary key as the arbiter.",
                "Click analytics: atomic UPDATE ... SET click_count = click_count + 1, avoiding lost updates under concurrency.",
                "Errors: one @RestControllerAdvice maps domain exceptions to 400/404/409 with a stable JSON body.",
                "Persistence: H2 for the prototype; a managed RDBMS with migrations is required for production."));
        if ("BROWNFIELD".equals(scenario)) design.put("impactAnalysis", impact(capabilities));
        design.put("risks", List.of(
                "Open-redirect abuse: only http(s) destinations with a host are accepted; no reputation check yet.",
                "Hot-key write contention on click_count for viral links (mitigation: buffered counters).",
                "8-hex code space (4.3e9) needs monitoring of collision retries at scale."));
        String summary = "BROWNFIELD".equals(scenario)
                ? "Impact analysis across " + ((List<?>) design.get("impactAnalysis")).size() + " modules"
                : "API contract with " + ((List<?>) design.get("apiContract")).size() + " endpoints";
        return StageResult.passed(summary, design);
    }

    private static List<Map<String, Object>> contract(List<String> capabilities) {
        List<Map<String, Object>> endpoints = new ArrayList<>();
        if (capabilities.contains(CREATE)) endpoints.add(endpoint("POST", "/api/urls", "{\"originalUrl\": \"https://...\"}", "201 {shortCode, originalUrl, shortUrl}; 400 invalid URL"));
        if (capabilities.contains(REDIRECT)) endpoints.add(endpoint("GET", "/{shortCode}", "-", "302 Location: originalUrl; 404 unknown code"));
        if (capabilities.contains(ANALYTICS)) endpoints.add(endpoint("GET", "/api/urls/{shortCode}/analytics", "-", "200 {shortCode, originalUrl, clickCount, createdAt}; 404 unknown code"));
        return endpoints;
    }

    private static Map<String, Object> endpoint(String method, String path, String request, String responses) {
        Map<String, Object> endpoint = new LinkedHashMap<>();
        endpoint.put("method", method);
        endpoint.put("path", path);
        endpoint.put("request", request);
        endpoint.put("responses", responses);
        return endpoint;
    }

    private static List<Map<String, Object>> impact(List<String> capabilities) {
        List<Map<String, Object>> modules = new ArrayList<>();
        modules.add(module("UrlShortenerController.java", "HTTP contract for create/redirect/analytics", "API"));
        modules.add(module("UrlShortenerService.java", "Lookup path throws UrlNotFoundException for unknown codes; click increment", "SERVICE"));
        modules.add(module("UrlMappingRepository.java", "findById/incrementClickCount data flow; no schema change expected", "DATA"));
        if (capabilities.contains(NOT_FOUND)) {
            modules.add(module("UrlNotFoundException.java", "Domain exception that must map to HTTP 404", "ERROR_CONTRACT"));
            modules.add(module("UrlExceptionHandler.java", "Maps UrlNotFoundException to 404 JSON body", "ERROR_CONTRACT"));
        }
        return modules;
    }

    private static Map<String, Object> module(String file, String reason, String layer) {
        Map<String, Object> module = new LinkedHashMap<>();
        module.put("path", BASE + file);
        module.put("layer", layer);
        module.put("reason", reason);
        return module;
    }
}
