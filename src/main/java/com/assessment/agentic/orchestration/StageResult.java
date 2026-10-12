package com.assessment.agentic.orchestration;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Output of a successful stage execution. Hard failures are signalled with exceptions;
 * BLOCKED means "the stage ran but could not produce verifiable evidence", which downstream
 * gates must treat as not-passed rather than silently passed.
 */
public record StageResult(Verdict verdict, String summary, Map<String, Object> output) {

    public enum Verdict { PASSED, BLOCKED }

    public StageResult {
        if (verdict == null) throw new IllegalArgumentException("Verdict is required.");
        summary = summary == null ? "" : summary;
        output = output == null ? Map.of() : Collections.unmodifiableMap(new LinkedHashMap<>(output));
    }

    public static StageResult passed(String summary, Map<String, Object> output) {
        return new StageResult(Verdict.PASSED, summary, output);
    }

    public static StageResult blocked(String summary, Map<String, Object> output) {
        return new StageResult(Verdict.BLOCKED, summary, output);
    }
}
