package com.assessment.agentic.orchestration.sdlc;

import java.util.Map;

import com.assessment.agentic.orchestration.StageContext;
import com.assessment.agentic.orchestration.StageHandler;
import com.assessment.agentic.orchestration.StageResult;

/** REQUIREMENTS: normalises the (possibly clarified) requirement into an engineering problem. */
public class RequirementsAgent implements StageHandler {

    private final RequirementAnalyzer analyzer;

    public RequirementsAgent(RequirementAnalyzer analyzer) {
        this.analyzer = analyzer;
    }

    @Override
    public StageResult execute(StageContext context) {
        Map<String, Object> analysis = analyzer.analyze(context.requirement());
        String scenario = Content.str(analysis, "scenario");
        String summary = "AMBIGUOUS".equals(scenario)
                ? "Ambiguous: " + Content.strings(analysis, "ambiguityReasons")
                : scenario + " with " + Content.maps(analysis, "acceptanceCriteria").size() + " acceptance criteria";
        return StageResult.passed(summary, analysis);
    }
}
