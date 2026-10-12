package com.assessment.agentic.service;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import org.springframework.web.client.RestClient;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

/**
 * Uses an OpenAI-compatible chat-completions endpoint when OPENAI_API_KEY is set.
 * If the key is absent or the call fails, it uses simple keyword-based rules.
 */
public class RequirementAnalysisService {

    private final String apiKey = System.getenv("OPENAI_API_KEY");
    private final String model = System.getenv().getOrDefault("OPENAI_MODEL", "gpt-4o-mini");
    private final RestClient restClient = RestClient.create();
    private final ObjectMapper objectMapper = new ObjectMapper();

    public Analysis analyze(String requirement) {
        if (apiKey != null && !apiKey.isBlank()) {
            try {
                return analyzeWithLlm(requirement);
            } catch (Exception ignored) {
                // Fall back to deterministic behavior if the provider is unavailable.
            }
        }
        return analyzeWithRules(requirement);
    }

    private Analysis analyzeWithLlm(String requirement) throws Exception {
        Map<String, Object> request = Map.of(
                "model", model,
                "temperature", 0,
                "messages", List.of(
                        Map.of("role", "system", "content",
                                "Analyze a software requirement. Reply only with JSON containing scenario (GREENFIELD, BROWNFIELD, or AMBIGUOUS) and assumptions (an array of short strings). Classify changes to existing systems, bug fixes, or refactors as BROWNFIELD. Broad or unclear goals are AMBIGUOUS. New features are GREENFIELD. Do not claim code was written or tests were run."),
                        Map.of("role", "user", "content", requirement)));

        String response = restClient.post()
                .uri("https://api.openai.com/v1/chat/completions")
                .header("Authorization", "Bearer " + apiKey)
                .body(request)
                .retrieve()
                .body(String.class);

        JsonNode root = objectMapper.readTree(response);
        String content = root.path("choices").path(0)
                .path("message").path("content").asText();
        JsonNode result = objectMapper.readTree(content);
        String scenario = result.path("scenario").asText("AMBIGUOUS").toUpperCase();
        if (!List.of("GREENFIELD", "BROWNFIELD", "AMBIGUOUS").contains(scenario)) {
            scenario = "AMBIGUOUS";
        }

        List<String> assumptions = new ArrayList<>();
        JsonNode values = result.path("assumptions");
        if (values.isArray()) {
            for (JsonNode value : values) {
                if (value.isTextual() && !value.asText().isBlank()) {
                    assumptions.add(value.asText());
                }
            }
        }
        if (assumptions.isEmpty()) {
            assumptions.add("Review the proposed scope and acceptance criteria before approval.");
        }
        return new Analysis(scenario, assumptions, "LLM");
    }

    private Analysis analyzeWithRules(String requirement) {
        String text = requirement.toLowerCase();
        String scenario;
        List<String> assumptions = new ArrayList<>();

        if (text.contains("existing") || text.contains("refactor")
                || text.contains("bug") || text.contains("enhance")
                || text.contains("fix") || text.contains("legacy")) {
            scenario = "BROWNFIELD";
            assumptions.add("Review existing modules, API contracts, data flows, and tests before changing code.");
        } else if (text.contains("improve") || text.contains("better")
                || text.contains("fast") || text.contains("secure")
                || text.contains("user-friendly") || text.contains("something")) {
            scenario = "AMBIGUOUS";
            assumptions.add("Clarification is recommended before implementation because the requirement is broad or underspecified.");
        } else {
            scenario = "GREENFIELD";
        }

        assumptions.add("Confirm acceptance criteria and failure cases with the reviewer.");
        return new Analysis(scenario, assumptions, "DETERMINISTIC_FALLBACK");
    }

    public record Analysis(String scenario, List<String> assumptions, String source) {}
}
