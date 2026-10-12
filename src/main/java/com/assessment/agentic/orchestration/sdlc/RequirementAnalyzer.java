package com.assessment.agentic.orchestration.sdlc;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Deterministic requirement normalizer. It is intentionally conservative: it never invents
 * acceptance criteria for vague quality goals; those become ambiguity or explicit NOT_VERIFIABLE
 * criteria. An LLM-backed analyzer could replace this behind the same output contract.
 */
public class RequirementAnalyzer {

    public static final String CREATE = "CREATE_SHORT_URL";
    public static final String REDIRECT = "REDIRECT";
    public static final String ANALYTICS = "CLICK_ANALYTICS";
    public static final String VALIDATION = "DESTINATION_VALIDATION";
    public static final String NOT_FOUND = "UNKNOWN_CODE_404";
    public static final String REGRESSION = "REGRESSION_SAFETY";
    public static final String SECURITY_HARDENING = "SECURITY_HARDENING";
    public static final String LATENCY = "REDIRECT_LATENCY";

    private static final Pattern LATENCY_TARGET = Pattern.compile("(?:p9[59]|latency|under|below|within|less than)[^\\d]{0,25}(\\d{1,5})\\s*ms");
    private static final List<String> DEFERRAL = List.of("defer", "out of scope", "not in scope", "postpone", "later phase");
    private static final List<String> BROWNFIELD = List.of("existing", "fix", "bug", "refactor", "regression", "legacy", "enhance", "current ");
    private static final List<String> PERF_WORDS = List.of("faster", "fast", "quick", "performance", "performant", "speed", "latency", "scalab");
    private static final List<String> SECURITY_WORDS = List.of("secure", "security", "safer", "safe ");
    private static final List<String> SECURITY_CONCRETE = List.of("javascript", "data:", "data url", "file:", "scheme", "allowlist", "blocklist");
    private static final List<String> GENERIC_VAGUE = List.of("better", "improve", "nicer", "user-friendly", "something", "robust", "optimi");

    public Map<String, Object> analyze(String requirement) {
        String normalized = requirement == null ? "" : requirement.trim().replaceAll("\\s+", " ");
        String lower = normalized.toLowerCase();
        Set<String> deferred = new LinkedHashSet<>();
        List<String> activeClauses = new ArrayList<>();
        for (String clause : lower.split("[.;\\n]")) {
            if (clause.isBlank()) continue;
            if (DEFERRAL.stream().anyMatch(clause::contains)) {
                if (containsAny(clause, PERF_WORDS)) deferred.add("performance");
                if (containsAny(clause, SECURITY_WORDS)) deferred.add("security");
            } else {
                activeClauses.add(clause);
            }
        }
        String active = String.join(" . ", activeClauses);

        Set<String> capabilities = new LinkedHashSet<>();
        if (containsAny(active, List.of("short link", "short url", "creates", "create short", "shorten url", "shorten long", "shorten a "))) capabilities.add(CREATE);
        if (containsAny(active, List.of("redirect", "resolve"))) capabilities.add(REDIRECT);
        if (containsAny(active, List.of("analytic", "click", "track"))) capabilities.add(ANALYTICS);
        if (containsAny(active, List.of("404", "not found", "unknown short", "unknown code", "invalid code"))) capabilities.add(NOT_FOUND);
        if (containsAny(active, List.of("invalid destination", "reject invalid", "validate"))) capabilities.add(VALIDATION);

        Integer latencyTarget = null;
        Matcher matcher = LATENCY_TARGET.matcher(active);
        if (matcher.find()) latencyTarget = Integer.valueOf(matcher.group(1));
        boolean securityConcrete = containsAny(active, SECURITY_CONCRETE);
        if (securityConcrete) capabilities.add(SECURITY_HARDENING);
        if (latencyTarget != null) capabilities.add(LATENCY);

        boolean brownfield = containsAny(active, BROWNFIELD);
        List<String> unmeasurable = new ArrayList<>();
        if (containsAny(active, PERF_WORDS) && latencyTarget == null && !deferred.contains("performance")) unmeasurable.add("performance");
        if (containsAny(active, SECURITY_WORDS) && !securityConcrete && !deferred.contains("security")) unmeasurable.add("security");

        List<String> ambiguity = new ArrayList<>();
        Set<String> functional = new LinkedHashSet<>(capabilities);
        functional.remove(LATENCY);
        functional.remove(SECURITY_HARDENING);
        if (normalized.split(" ").length < 5) ambiguity.add("Requirement is too short to derive acceptance criteria.");
        if (functional.isEmpty() && latencyTarget == null && !securityConcrete) {
            if (!unmeasurable.isEmpty()) ambiguity.add("Quality goals " + unmeasurable + " have no measurable target or observable behaviour.");
            if (containsAny(active, GENERIC_VAGUE)) ambiguity.add("Requirement uses subjective wording without observable behaviour.");
            if (ambiguity.isEmpty()) ambiguity.add("No concrete URL-shortener capability could be identified.");
        }
        boolean ambiguous = !ambiguity.isEmpty();

        if (brownfield) {
            capabilities.add(REGRESSION);
            if (!capabilities.contains(CREATE)) capabilities.add(CREATE);
            if (!capabilities.contains(REDIRECT)) capabilities.add(REDIRECT);
            if (!capabilities.contains(ANALYTICS)) capabilities.add(ANALYTICS);
        }
        if (!ambiguous) capabilities.add(VALIDATION); // destination validation is a non-negotiable safety baseline

        List<String> assumptions = new ArrayList<>();
        assumptions.add("Agents may analyse, plan, propose and verify; they may not modify source, run shell commands or deploy.");
        if (brownfield) assumptions.add("Existing create/redirect/analytics behaviour is part of the regression baseline.");
        if (!deferred.isEmpty()) assumptions.add("Explicitly deferred by requester: " + deferred);
        if (!unmeasurable.isEmpty() && !ambiguous) assumptions.add("Quality goals " + unmeasurable + " are recorded as NOT_VERIFIABLE until a measurable target is supplied.");

        Map<String, Object> nfr = new LinkedHashMap<>();
        nfr.put("latencyP95TargetMs", latencyTarget);
        nfr.put("securityHardening", securityConcrete);
        nfr.put("unmeasurableConcerns", unmeasurable);

        Map<String, Object> result = new LinkedHashMap<>();
        result.put("normalizedRequirement", normalized);
        result.put("scenario", ambiguous ? "AMBIGUOUS" : brownfield ? "BROWNFIELD" : "GREENFIELD");
        result.put("capabilities", List.copyOf(capabilities));
        result.put("acceptanceCriteria", ambiguous ? List.of() : criteria(capabilities, latencyTarget, unmeasurable));
        result.put("ambiguityReasons", ambiguity);
        result.put("deferredConcerns", List.copyOf(deferred));
        result.put("nfr", nfr);
        result.put("assumptions", assumptions);
        result.put("analysisSource", "DETERMINISTIC_RULES");
        return result;
    }

    private static List<Map<String, Object>> criteria(Set<String> capabilities, Integer latencyTarget, List<String> unmeasurable) {
        List<Map<String, Object>> criteria = new ArrayList<>();
        for (String capability : capabilities) {
            switch (capability) {
                case CREATE -> criteria.add(criterion("AC-CREATE", capability, "POST /api/urls with a valid http(s) URL returns 201 and an 8-character short code.", "RUNTIME_CHECK"));
                case REDIRECT -> criteria.add(criterion("AC-REDIRECT", capability, "GET /{shortCode} resolves to the original destination with HTTP 302.", "RUNTIME_CHECK"));
                case ANALYTICS -> criteria.add(criterion("AC-ANALYTICS", capability, "Each redirect increments the click count returned by GET /api/urls/{code}/analytics.", "RUNTIME_CHECK"));
                case VALIDATION -> criteria.add(criterion("AC-VALIDATION", capability, "Non-http(s) or host-less destinations are rejected with HTTP 400.", "RUNTIME_CHECK"));
                case NOT_FOUND -> criteria.add(criterion("AC-404", capability, "Unknown short codes return HTTP 404 and create no data.", "RUNTIME_CHECK"));
                case REGRESSION -> criteria.add(criterion("AC-REGRESSION", capability, "Existing create, redirect and analytics behaviour is unchanged.", "RUNTIME_CHECK"));
                case SECURITY_HARDENING -> criteria.add(criterion("AC-SEC", capability, "javascript:, data:, file:, ftp: and host-less destinations are rejected.", "SECURITY_PROBE"));
                case LATENCY -> criteria.add(criterion("AC-PERF", capability, "In-process p95 redirect lookup latency is at most " + latencyTarget + " ms.", "MICROBENCHMARK"));
                default -> { }
            }
        }
        for (String concern : unmeasurable) {
            criteria.add(criterion("AC-NFR-" + concern.toUpperCase(), concern, "Requester asked for better " + concern + " without a measurable target.", "NOT_VERIFIABLE"));
        }
        return criteria;
    }

    private static Map<String, Object> criterion(String id, String capability, String description, String verification) {
        Map<String, Object> criterion = new LinkedHashMap<>();
        criterion.put("id", id);
        criterion.put("capability", capability);
        criterion.put("description", description);
        criterion.put("verification", verification);
        return criterion;
    }

    private static boolean containsAny(String text, List<String> needles) {
        return needles.stream().anyMatch(text::contains);
    }
}
