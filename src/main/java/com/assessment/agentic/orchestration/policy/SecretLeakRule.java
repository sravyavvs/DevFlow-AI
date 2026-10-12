package com.assessment.agentic.orchestration.policy;

import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.regex.Pattern;

/** SECURITY: no stage output (agent- or human-authored) may contain credential-like material. */
public final class SecretLeakRule implements PolicyRule {

    private static final List<Pattern> PATTERNS = List.of(
            Pattern.compile("AKIA[0-9A-Z]{16}"),
            Pattern.compile("-----BEGIN [A-Z ]*PRIVATE KEY-----"),
            Pattern.compile("(?i)(password|passwd|secret|api[_-]?key|token)\\s*[:=]\\s*['\"]?[A-Za-z0-9_\\-]{8,}"),
            Pattern.compile("sk-[A-Za-z0-9]{20,}"));

    @Override public String id() { return "SEC-001-no-secrets-in-artifacts"; }
    @Override public String category() { return "SECURITY"; }
    @Override public boolean appliesTo(PolicyCheck check) { return check.point() == GatePoint.EXIT && check.result() != null; }

    @Override
    public PolicyDecision evaluate(PolicyCheck check) {
        List<String> values = new ArrayList<>();
        flatten(check.result().output(), values);
        values.add(check.result().summary());
        for (String value : values) {
            for (Pattern pattern : PATTERNS) {
                if (pattern.matcher(value).find()) {
                    return PolicyDecision.deny(this, "Output contains credential-like content matching " + pattern.pattern());
                }
            }
        }
        return PolicyDecision.allow(this, "No credential-like content in " + values.size() + " output values.");
    }

    static void flatten(Object value, List<String> out) {
        if (value == null) return;
        if (value instanceof Map<?, ?> map) map.values().forEach(v -> flatten(v, out));
        else if (value instanceof Collection<?> collection) collection.forEach(v -> flatten(v, out));
        else out.add(String.valueOf(value));
    }
}
