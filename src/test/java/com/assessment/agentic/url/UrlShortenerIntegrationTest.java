
package com.assessment.agentic.url;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

@SpringBootTest(properties = {
        "spring.datasource.url=jdbc:h2:mem:urlshortener-test;DB_CLOSE_DELAY=-1",
        "spring.datasource.driver-class-name=org.h2.Driver",
        "spring.datasource.username=sa",
        "spring.datasource.password=",
        "spring.jpa.hibernate.ddl-auto=create-drop",
        "spring.h2.console.enabled=false"
})
@AutoConfigureMockMvc
class UrlShortenerIntegrationTest {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private ObjectMapper objectMapper;

    @Test
    void shouldCreateAndRetrieveUrlMapping() throws Exception {
        // Create a mapping through the real HTTP endpoint.
        MvcResult createResult = mockMvc.perform(post("/api/urls")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"originalUrl":"https://example.com/integration-test"}
                                """))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.shortCode").exists())
                .andReturn();

        JsonNode response = objectMapper.readTree(
                createResult.getResponse().getContentAsString());
        String shortCode = response.get("shortCode").asText();

        // Verify that the API can retrieve the saved mapping.
        mockMvc.perform(get("/api/urls/" + shortCode + "/analytics"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.shortCode").value(shortCode))
                .andExpect(jsonPath("$.originalUrl")
                        .value("https://example.com/integration-test"))
                .andExpect(jsonPath("$.clickCount").value(0));
    }

    @Test
    void shouldIncrementClickCountAfterRedirect() throws Exception {
        MvcResult createResult = mockMvc.perform(post("/api/urls")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"originalUrl":"https://example.com/click-test"}
                                """))
                .andExpect(status().isCreated())
                .andReturn();

        JsonNode response = objectMapper.readTree(
                createResult.getResponse().getContentAsString());
        String shortCode = response.get("shortCode").asText();

        // MockMvc observes the redirect response without following it.
        mockMvc.perform(get("/" + shortCode))
                .andExpect(status().is3xxRedirection());

        // The click count should be saved in the test database.
        mockMvc.perform(get("/api/urls/" + shortCode + "/analytics"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.clickCount").value(1));
    }

    @Test
    void unknownShortCodeReturnsHttp404() throws Exception {
        mockMvc.perform(get("/zzzzzzzz"))
                .andExpect(status().isNotFound());
    }

    @Test
    void shouldRejectInvalidUrlWithBadRequest() throws Exception {
        mockMvc.perform(post("/api/urls")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"originalUrl":"not-a-valid-url"}
                                """))
                .andExpect(status().isBadRequest());
    }
}
