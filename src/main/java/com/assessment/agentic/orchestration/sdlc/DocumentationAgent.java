package com.assessment.agentic.orchestration.sdlc;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import com.assessment.agentic.orchestration.ArtifactVersion;
import com.assessment.agentic.orchestration.StageContext;
import com.assessment.agentic.orchestration.StageHandler;
import com.assessment.agentic.orchestration.StageResult;

/**
 * DOCUMENTATION: renders delivery notes (API reference, change set, traceability, risks) from
 * upstream artifacts and writes them inside the artifact directory. Writing a file is a side
 * effect, so the agent provides a compensating delete used by rollback.
 */
public class DocumentationAgent implements StageHandler {

    private final Path root;

    public DocumentationAgent(Path root) {
        this.root = root.toAbsolutePath().normalize();
    }

    @Override
    public StageResult execute(StageContext context) {
        Map<String, Object> requirements = context.input(SdlcStages.REQUIREMENTS);
        Map<String, Object> design = context.input(SdlcStages.ARCHITECTURE);
        Map<String, Object> plan = context.input(SdlcStages.TEST_PLAN);
        Map<String, Object> implementation = context.input(SdlcStages.IMPLEMENTATION);

        StringBuilder md = new StringBuilder();
        md.append("# Delivery notes\n\n")
          .append("**Pipeline:** ").append(context.pipelineId()).append("  \n")
          .append("**Scenario:** ").append(Content.str(requirements, "scenario")).append("  \n")
          .append("**Requirement:** ").append(Content.str(requirements, "normalizedRequirement")).append("\n\n")
          .append("## API reference\n\n| Method | Path | Request | Responses |\n|---|---|---|---|\n");
        for (Map<String, Object> e : Content.maps(design, "apiContract")) {
            md.append("| ").append(Content.str(e, "method")).append(" | `").append(Content.str(e, "path")).append("` | ")
              .append(Content.str(e, "request")).append(" | ").append(Content.str(e, "responses")).append(" |\n");
        }
        if (!Content.maps(design, "impactAnalysis").isEmpty()) {
            md.append("\n## Impact analysis\n\n");
            for (Map<String, Object> m : Content.maps(design, "impactAnalysis")) {
                md.append("- `").append(Content.str(m, "path")).append("` (").append(Content.str(m, "layer")).append("): ")
                  .append(Content.str(m, "reason")).append('\n');
            }
        }
        md.append("\n## Change set (proposal, not auto-applied)\n\n");
        for (Map<String, Object> c : Content.maps(implementation, "changeSet")) {
            md.append("- ").append(Content.str(c, "changeType")).append(" `").append(Content.str(c, "path")).append("`: ")
              .append(Content.str(c, "reason")).append('\n');
        }
        md.append("\n## Acceptance criteria traceability\n\n| Criterion | Test case | Level | Automated test |\n|---|---|---|---|\n");
        for (Map<String, Object> t : Content.maps(plan, "testCases")) {
            md.append("| ").append(Content.str(t, "criterion")).append(" | ").append(Content.str(t, "id")).append(" | ")
              .append(Content.str(t, "level")).append(" | ").append(Content.str(t, "automatedTest")).append(" |\n");
        }
        md.append("\n## Risks\n\n");
        Content.strings(design, "risks").forEach(r -> md.append("- ").append(r).append('\n'));
        md.append("\n## Assumptions\n\n");
        Content.strings(requirements, "assumptions").forEach(a -> md.append("- ").append(a).append('\n'));

        Path file = root.resolve(context.pipelineId()).resolve("DELIVERY_NOTES.md").normalize();
        if (!file.startsWith(root)) throw new IllegalStateException("Refusing to write outside " + root);
        try {
            Files.createDirectories(file.getParent());
            Files.writeString(file, md.toString(), StandardCharsets.UTF_8);
        } catch (IOException io) {
            throw new UncheckedIOException(io);
        }
        Map<String, Object> output = new LinkedHashMap<>();
        output.put("writtenFile", file.toString());
        output.put("sections", List.of("API reference", "Impact analysis", "Change set", "Traceability", "Risks", "Assumptions"));
        output.put("markdown", md.toString());
        return StageResult.passed("Delivery notes written to " + file.getFileName(), output);
    }

    @Override
    public boolean hasSideEffects() {
        return true;
    }

    @Override
    public void compensate(String pipelineId, ArtifactVersion artifact) throws IOException {
        Object written = artifact.content().get("writtenFile");
        if (written == null) return;
        Path file = Path.of(written.toString()).toAbsolutePath().normalize();
        if (!file.startsWith(root)) throw new IllegalStateException("Refusing to delete outside " + root);
        Files.deleteIfExists(file);
    }
}
