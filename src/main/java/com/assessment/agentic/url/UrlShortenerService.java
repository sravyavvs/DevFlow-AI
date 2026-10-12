
package com.assessment.agentic.url;

import java.net.URI;
import java.util.UUID;

import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class UrlShortenerService {

    private final UrlMappingRepository repository;

    private static final int MAX_CODE_GENERATION_ATTEMPTS = 5;

    public UrlShortenerService(UrlMappingRepository repository) {
        this.repository = repository;
    }

    
public UrlMapping createShortUrl(String originalUrl) {
    validateUrl(originalUrl);

    for (int attempt = 1;
         attempt <= MAX_CODE_GENERATION_ATTEMPTS;
         attempt++) {

        // Generate an eight-character hexadecimal short code.
        String shortCode = UUID.randomUUID()
                .toString()
                .replace("-", "")
                .substring(0, 8);

        // Avoid unnecessary inserts when a collision is already visible.
        if (repository.existsById(shortCode)) {
            continue;
        }

        UrlMapping mapping = new UrlMapping(shortCode, originalUrl);

        try {
            // Flush immediately so a database primary-key collision
            // is detected inside this try block.
            return repository.saveAndFlush(mapping);
        } catch (DataIntegrityViolationException exception) {
            // Another request may have inserted the same code between
            // existsById() and saveAndFlush(). Retry with a new code.
            if (attempt == MAX_CODE_GENERATION_ATTEMPTS) {
                throw new IllegalStateException(
                        "Unable to generate a unique short code after "
                                + MAX_CODE_GENERATION_ATTEMPTS
                                + " attempts.",
                        exception);
            }
        }
    }

    throw new IllegalStateException(
            "Unable to generate a unique short code.");
}


   
@Transactional
public String getOriginalUrl(String shortCode) {
    // Validate that the short code exists before updating its counter.
    UrlMapping mapping = repository.findById(shortCode)
            .orElseThrow(() ->
                    new UrlNotFoundException(
                            "Short URL not found: " + shortCode));

    // Perform the increment atomically in the database.
    int updatedRows = repository.incrementClickCount(shortCode);

    if (updatedRows == 0) {
        throw new UrlNotFoundException(
                "Short URL not found: " + shortCode);
    }

    return mapping.getOriginalUrl();
}



    public UrlMapping getAnalytics(String shortCode) {
        return repository.findById(shortCode)
                .orElseThrow(() ->
                        new UrlNotFoundException(
                                "Short URL not found: " + shortCode
                        ));
    }

    /** Compensating action used only for an isolated workflow smoke-test mapping. */
    @Transactional
    public void deleteShortUrl(String shortCode) {
        repository.deleteById(shortCode);
    }

    private void validateUrl(String originalUrl) {
        try {
            URI uri = URI.create(originalUrl);

            if (!("http".equalsIgnoreCase(uri.getScheme())
                    || "https".equalsIgnoreCase(uri.getScheme()))
                    || uri.getHost() == null
                    || uri.getHost().isBlank()) {
                throw new IllegalArgumentException(
                        "A valid HTTP or HTTPS URL is required"
                );
            }
        } catch (IllegalArgumentException ex) {
            throw new IllegalArgumentException(
                    "Invalid URL: " + originalUrl, ex
            );
        }
    }
}
