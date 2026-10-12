package com.assessment.agentic.orchestration.sdlc;

import static com.assessment.agentic.orchestration.sdlc.SdlcStages.*;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import com.assessment.agentic.orchestration.ArtifactVersion;
import com.assessment.agentic.orchestration.GateType;
import com.assessment.agentic.orchestration.GraphPlanner;
import com.assessment.agentic.orchestration.PipelineGraph;
import com.assessment.agentic.orchestration.StageDefinition;

/**
 * The plan is an output of the requirements stage, not hard-coded at creation:
 * <ul>
 *   <li>no requirement artifact yet: only REQUIREMENTS</li>
 *   <li>ambiguous: REQUIREMENTS then a human CLARIFICATION gate</li>
 *   <li>otherwise the full SDLC graph, with NFR_VERIFICATION added only when quality goals exist</li>
 * </ul>
 */
public class SdlcPlanner implements GraphPlanner {

    @Override
    public PipelineGraph plan(Map<String, ArtifactVersion> artifacts) {
        StageDefinition requirements = StageDefinition.of(REQUIREMENTS, "Normalise requirement, detect ambiguity, derive acceptance criteria", GateType.NONE, 2);
        ArtifactVersion analysis = artifacts.get(REQUIREMENTS);
        if (analysis == null) return new PipelineGraph(List.of(requirements));
        if ("AMBIGUOUS".equals(Content.str(analysis.content(), "scenario"))) {
            return new PipelineGraph(List.of(requirements,
                    StageDefinition.of(CLARIFICATION, "Human supplies observable behaviour and measurable targets", GateType.HUMAN_CLARIFICATION, 1, REQUIREMENTS)));
        }
        Map<String, Object> nfr = Content.map(analysis.content(), "nfr");
        boolean needsNfr = nfr.get("latencyP95TargetMs") != null || Boolean.TRUE.equals(nfr.get("securityHardening"))
                || !Content.strings(nfr, "unmeasurableConcerns").isEmpty();

        List<StageDefinition> stages = new ArrayList<>();
        stages.add(requirements);
        stages.add(StageDefinition.of(ARCHITECTURE, "Design API/data model (greenfield) or impact analysis (brownfield)", GateType.NONE, 2, REQUIREMENTS));
        stages.add(StageDefinition.of(TEST_PLAN, "Map acceptance criteria to test cases (traceability)", GateType.NONE, 2, REQUIREMENTS));
        stages.add(StageDefinition.of(IMPLEMENTATION, "Codebase reasoning and change-set proposal (high impact: approval required)", GateType.HUMAN_APPROVAL, 1, ARCHITECTURE, TEST_PLAN));
        stages.add(StageDefinition.of(TESTING, "Execute requirement-linked checks against the real service", GateType.NONE, 3, IMPLEMENTATION));
        stages.add(StageDefinition.of(DOCUMENTATION, "Generate delivery notes, API reference and traceability matrix", GateType.NONE, 2, IMPLEMENTATION));
        List<String> releaseDeps = new ArrayList<>(List.of(TESTING, DOCUMENTATION));
        if (needsNfr) {
            stages.add(StageDefinition.of(NFR_VERIFICATION, "Verify quality goals with measured evidence", GateType.NONE, 2, IMPLEMENTATION));
            releaseDeps.add(NFR_VERIFICATION);
        }
        stages.add(new StageDefinition(RELEASE_READINESS, RELEASE_READINESS, "Release checklist and sign-off (no deployment)",
                releaseDeps, GateType.HUMAN_APPROVAL, 1));
        return new PipelineGraph(stages);
    }
}
