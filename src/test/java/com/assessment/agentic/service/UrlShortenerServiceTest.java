
package com.assessment.agentic.service;

import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import com.assessment.agentic.url.UrlMapping;
import com.assessment.agentic.url.UrlMappingRepository;
import com.assessment.agentic.url.UrlNotFoundException;
import com.assessment.agentic.url.UrlShortenerService;

/**
 * Unit tests for URL shortening business logic.
 *
 * Mockito isolates the service from the database so that
 * each test can verify behavior without starting Spring Boot.
 */
class UrlShortenerServiceTest {

    private UrlShortenerService service;
    private UrlMappingRepository repository;

    /**
     * Creates a fresh mocked repository before each test.
     */
    @BeforeEach
    void setUp() {
        repository = mock(UrlMappingRepository.class);
        service = new UrlShortenerService(repository);
    }

    /**
     * Verifies that a valid URL generates a short code
     * and saves the resulting mapping.
     */
    @Test
    void shouldCreateShortUrl() {
        when(repository.existsById(any(String.class)))
                .thenReturn(false);

       when(repository.saveAndFlush(any(UrlMapping.class)))
        .thenAnswer(invocation -> invocation.getArgument(0));

        UrlMapping mapping =
                service.createShortUrl("https://www.google.com");

        assertNotNull(mapping.getShortCode());
        assertEquals(8, mapping.getShortCode().length());
        assertEquals(
                "https://www.google.com",
                mapping.getOriginalUrl()
        );
        assertEquals(0, mapping.getClickCount());

       verify(repository).saveAndFlush(any(UrlMapping.class));
    }

    /**
     * Verifies that redirect lookup increments the click count
     * and saves the updated mapping.
     */
    @Test
    void shouldIncrementClickCount() {
        UrlMapping mapping =
                new UrlMapping("abc12345", "https://www.google.com");

        when(repository.findById("abc12345"))
        .thenReturn(Optional.of(mapping));

when(repository.incrementClickCount("abc12345"))
        .thenReturn(1);

String originalUrl = service.getOriginalUrl("abc12345");

assertEquals("https://www.google.com", originalUrl);
verify(repository).incrementClickCount("abc12345");
    }

    /**
     * Verifies that an unknown code produces the expected exception.
     */
    @Test
    void shouldThrowExceptionForUnknownShortCode() {
        when(repository.findById("unknown1"))
                .thenReturn(Optional.empty());

        assertThrows(
                UrlNotFoundException.class,
                () -> service.getOriginalUrl("unknown1")
        );
    }

    /**
     * Verifies that malformed URLs are rejected before persistence.
     */
    @Test
    void shouldRejectInvalidUrl() {
        assertThrows(
                IllegalArgumentException.class,
                () -> service.createShortUrl("not-a-valid-url")
        );

        verifyNoInteractions(repository);
    }

    /**
     * Verifies that unsupported protocols such as FTP are rejected.
     */
    @Test
    void shouldRejectUrlWithoutHttpScheme() {
        assertThrows(
                IllegalArgumentException.class,
                () -> service.createShortUrl("ftp://example.com")
        );

        verifyNoInteractions(repository);
    }
}
