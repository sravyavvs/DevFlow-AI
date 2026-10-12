package com.assessment.agentic.orchestration.sdlc;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import com.assessment.agentic.orchestration.ArtifactVersion;
import com.assessment.agentic.orchestration.StageContext;
import com.assessment.agentic.orchestration.StageHandler;
import com.assessment.agentic.orchestration.StageResult;

/**
 * RELEASE_READINESS: assembles the go/no-go checklist from upstream evidence. Deployment is outside
 * the autonomy boundary, so the output is a release decision record, not a deployment.
 */
public class ReleaseReadinessAgent implements StageHandler {

    @Override
    public StageResult execute(StageContext context) {
        List<Map<String, Object>> checklist = new ArrayList<>();
        for (ArtifactVersion artifact : context.inputs().values()) {
            checklist.add(item(artifact.stageId() + " evidence v" + artifact.version(), artifact.verdict() == StageResult.Verdict.PASSED, artifact.summary()));
        }
        Map<String, Object> testing = context.input(SdlcStages.TESTING);
        checklist.add(item("Test fixtures cleaned up", Boolean.TRUE.equals(testing.get("fixtureCleanedUp")), "No residual test data"));
        String docs = Content.str(context.input(SdlcStages.DOCUMENTATION), "writtenFile");
        checklist.add(item("Delivery notes present", !docs.isBlank() && Files.exists(Path.of(docs)), docs));
        checklist.add(item("Change set applied by an engineer, not an agent",
                Boolean.FALSE.equals(context.input(SdlcStages.IMPLEMENTATION).get("appliedToWorkingTree")), "Autonomy boundary respected"));

        boolean go = checklist.stream().allMatch(i -> Boolean.TRUE.equals(i.get("passed")));
        Map<String, Object> record = new LinkedHashMap<>();
        record.put("decision", go ? "READY_FOR_RELEASE" : "NOT_READY");
        record.put("checklist", checklist);
        record.put("deploymentPerformed", false);
        record.put("rollbackPlan", List.of("Revert the reviewed change-set PR.", "Generated delivery notes are removed automatically by compensation."));
        record.put("lineage", context.inputs().values().stream().map(ArtifactVersion::artifactId).toList());
        return go ? StageResult.passed("Release checklist passed (" + checklist.size() + " items)", record)
                  : StageResult.blocked("Release checklist has failing items", record);
    }

    private static Map<String, Object> item(String name, boolean passed, String evidence) {
        Map<String, Object> item = new LinkedHashMap<>();
        item.put("item", name);
        item.put("passed", passed);
        item.put("evidence", evidence);
        return item;
    }
}
