package com.assessment.agentic.orchestration.policy;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * CHANGE_CONTROL: a proposed change set may only touch allow-listed paths. Build files, CI config,
 * runtime configuration and path traversal are always denied.
 */
public final class ChangeScopeRule implements PolicyRule {

    private static final List<String> ALWAYS_FORBIDDEN = List.of("pom.xml", ".github/", "application.properties", "..");
    private final List<String> allowedPrefixes;

    public ChangeScopeRule(List<String> allowedPrefixes) {
        this.allowedPrefixes = List.copyOf(allowedPrefixes);
    }

    @Override public String id() { return "CHG-001-change-set-scope"; }
    @Override public String category() { return "CHANGE_CONTROL"; }

    @Override
    public boolean appliesTo(PolicyCheck check) {
        return check.point() == GatePoint.EXIT && check.result() != null && check.result().output().get("changeSet") instanceof List<?>;
    }

    @Override
    public PolicyDecision evaluate(PolicyCheck check) {
        List<String> violations = new ArrayList<>();
        int count = 0;
        for (Object entry : (List<?>) check.result().output().get("changeSet")) {
            count++;
            Object path = entry instanceof Map<?, ?> map ? map.get("path") : null;
            String text = path == null ? "" : path.toString().replace('\\', '/');
            boolean forbidden = text.isBlank() || ALWAYS_FORBIDDEN.stream().anyMatch(text::contains);
            boolean allowed = allowedPrefixes.stream().anyMatch(text::startsWith);
            if (forbidden || !allowed) violations.add(text.isBlank() ? "<missing path>" : text);
        }
        return violations.isEmpty()
                ? PolicyDecision.allow(this, count + " proposed file changes are inside the allow-listed scope.")
                : PolicyDecision.deny(this, "Change set touches paths outside the approved scope: " + violations);
    }
}
