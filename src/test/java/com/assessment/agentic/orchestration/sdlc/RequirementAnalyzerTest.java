package com.assessment.agentic.orchestration.sdlc;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.Test;

class RequirementAnalyzerTest {

    private final RequirementAnalyzer analyzer = new RequirementAnalyzer();

    @Test
    void greenfieldRequirementYieldsFunctionalCriteria() {
        Map<String, Object> result = analyzer.analyze("Build a URL shortener API that creates short links, redirects users, and tracks click analytics.");
        assertEquals("GREENFIELD", result.get("scenario"));
        assertTrue(Content.strings(result, "capabilities").containsAll(List.of(
                RequirementAnalyzer.CREATE, RequirementAnalyzer.REDIRECT, RequirementAnalyzer.ANALYTICS, RequirementAnalyzer.VALIDATION)));
    }

    @Test
    void brownfieldFixIncludesRegressionBaseline() {
        Map<String, Object> result = analyzer.analyze("Fix the existing URL shortener so unknown short codes return 404 and add regression tests.");
        assertEquals("BROWNFIELD", result.get("scenario"));
        assertTrue(Content.strings(result, "capabilities").containsAll(List.of(RequirementAnalyzer.NOT_FOUND, RequirementAnalyzer.REGRESSION)));
    }

    @Test
    void vagueQualityGoalsAreAmbiguousUntilClarified() {
        String vague = "Make the URL shortener faster and more secure for users.";
        assertEquals("AMBIGUOUS", analyzer.analyze(vague).get("scenario"));
        Map<String, Object> clarified = analyzer.analyze(vague
                + "\nClarification: Fix the existing URL shortener so unknown short codes return 404 and add regression checks; defer performance and security improvements.");
        assertEquals("BROWNFIELD", clarified.get("scenario"));
        assertEquals(List.of("performance", "security"), Content.strings(clarified, "deferredConcerns"));
    }

    @Test
    void measurableTargetsBecomeVerifiableCriteria() {
        Map<String, Object> result = analyzer.analyze("Make redirects faster: p95 under 50 ms, and reject javascript and data URLs.");
        assertEquals("GREENFIELD", result.get("scenario"));
        Map<String, Object> nfr = Content.map(result, "nfr");
        assertEquals(50, nfr.get("latencyP95TargetMs"));
        assertEquals(Boolean.TRUE, nfr.get("securityHardening"));
        assertTrue(Content.strings(nfr, "unmeasurableConcerns").isEmpty());
    }

    @Test
    void concreteFeatureWithVagueSecurityIsNotFalselyVerifiable() {
        Map<String, Object> result = analyzer.analyze("Build a secure URL shortener that creates short links and redirects users.");
        assertFalse("AMBIGUOUS".equals(result.get("scenario")));
        assertEquals(List.of("security"), Content.strings(Content.map(result, "nfr"), "unmeasurableConcerns"));
    }
}
