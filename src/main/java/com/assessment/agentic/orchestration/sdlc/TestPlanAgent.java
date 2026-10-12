package com.assessment.agentic.orchestration.sdlc;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import com.assessment.agentic.orchestration.StageContext;
import com.assessment.agentic.orchestration.StageHandler;
import com.assessment.agentic.orchestration.StageResult;

/** TEST_PLAN: maps every acceptance criterion to runtime checks and existing automated tests. */
public class TestPlanAgent implements StageHandler {

    private static final String IT = "UrlShortenerIntegrationTest#";
    private static final Map<String, String> AUTOMATED = Map.of(
            "AC-CREATE", IT + "shouldCreateAndRetrieveUrlMapping",
            "AC-REDIRECT", IT + "shouldIncrementClickCountAfterRedirect",
            "AC-ANALYTICS", IT + "shouldIncrementClickCountAfterRedirect",
            "AC-404", IT + "unknownShortCodeReturnsHttp404",
            "AC-VALIDATION", IT + "shouldRejectInvalidUrlWithBadRequest",
            "AC-REGRESSION", IT + "* (full class)");

    @Override
    public StageResult execute(StageContext context) {
        List<Map<String, Object>> criteria = Content.maps(context.input(SdlcStages.REQUIREMENTS), "acceptanceCriteria");
        List<Map<String, Object>> cases = new ArrayList<>();
        List<String> untestable = new ArrayList<>();
        for (Map<String, Object> criterion : criteria) {
            String id = Content.str(criterion, "id");
            String verification = Content.str(criterion, "verification");
            Map<String, Object> testCase = new LinkedHashMap<>();
            testCase.put("id", "TC-" + id.substring(3));
            testCase.put("criterion", id);
            testCase.put("level", switch (verification) {
                case "RUNTIME_CHECK" -> "SERVICE_INTEGRATION";
                case "MICROBENCHMARK" -> "PERFORMANCE_SMOKE";
                case "SECURITY_PROBE" -> "SECURITY_NEGATIVE";
                default -> "NONE";
            });
            testCase.put("executedBy", "RUNTIME_CHECK".equals(verification) ? "TESTING" : "NOT_VERIFIABLE".equals(verification) ? "nobody" : "NFR_VERIFICATION");
            testCase.put("automatedTest", AUTOMATED.getOrDefault(id, "none yet"));
            cases.add(testCase);
            if ("NOT_VERIFIABLE".equals(verification)) untestable.add(id);
        }
        Map<String, Object> plan = new LinkedHashMap<>();
        plan.put("testCases", cases);
        plan.put("untestableCriteria", untestable);
        plan.put("strategy", List.of(
                "Unit: service rules with mocked repository (UrlShortenerServiceTest).",
                "Integration: MockMvc + in-memory H2 for HTTP contracts (UrlShortenerIntegrationTest).",
                "Pipeline runtime checks: TESTING stage exercises the live service bean with an isolated fixture."));
        return StageResult.passed(cases.size() + " test cases traced to " + criteria.size() + " criteria"
                + (untestable.isEmpty() ? "" : "; untestable: " + untestable), plan);
    }
}
