
package com.assessment.agentic.url;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

import java.time.Instant;

/**
 * Represents a shortened URL and its associated metadata.
 *
 * Each mapping is persisted in the url_mappings database table.
 * The short code uniquely identifies the original URL.
 */
@Entity
@Table(name = "url_mappings")
public class UrlMapping {

    /**
     * Unique identifier used in the shortened URL.
     * Example: https://our-domain.com/3bbc59e1
     */
    @Id
    @Column(name = "short_code", length = 8)
    private String shortCode;

    /** Original destination URL supplied by the user. */
    @Column(name = "original_url", nullable = false, length = 2048)
    private String originalUrl;

    /** Time at which the short URL was created, stored in UTC. */
    @Column(name = "created_at", nullable = false)
    private Instant createdAt;

    /**
     * Number of times the redirect endpoint has been accessed.
     * This prototype uses a simple counter; concurrent updates
     * require additional database-level protection.
     */
    @Column(name = "click_count", nullable = false)
    private long clickCount;

    /**
     * Required by JPA when loading an entity from the database.
     * Application code should use the parameterized constructor.
     */
    protected UrlMapping() {
    }

    /**
     * Creates a new URL mapping with an initial click count of zero.
     *
     * @param shortCode unique code assigned to the URL
     * @param originalUrl validated HTTP or HTTPS destination
     */
    public UrlMapping(String shortCode, String originalUrl) {
        this.shortCode = shortCode;
        this.originalUrl = originalUrl;
        this.createdAt = Instant.now();
        this.clickCount = 0;
    }

    public String getShortCode() {
        return shortCode;
    }

    public String getOriginalUrl() {
        return originalUrl;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }

    public long getClickCount() {
        return clickCount;
    }

    /**
     * Records a redirect attempt for this mapping.
     *
     * @return the updated click count
     */
    public long recordClick() {
        return ++clickCount;
    }
}
