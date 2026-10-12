package com.assessment.agentic.orchestration.sdlc;

import java.util.List;
import java.util.Map;

/** Small typed accessors for artifact content maps. */
final class Content {

    private Content() {
    }

    static String str(Map<String, Object> map, String key) {
        Object value = map.get(key);
        return value == null ? "" : value.toString();
    }

    @SuppressWarnings("unchecked")
    static List<Map<String, Object>> maps(Map<String, Object> map, String key) {
        Object value = map.get(key);
        return value instanceof List<?> list ? (List<Map<String, Object>>) list : List.of();
    }

    @SuppressWarnings("unchecked")
    static List<String> strings(Map<String, Object> map, String key) {
        Object value = map.get(key);
        return value instanceof List<?> list ? (List<String>) list : List.of();
    }

    @SuppressWarnings("unchecked")
    static Map<String, Object> map(Map<String, Object> map, String key) {
        Object value = map.get(key);
        return value instanceof Map<?, ?> m ? (Map<String, Object>) m : Map.of();
    }
}
