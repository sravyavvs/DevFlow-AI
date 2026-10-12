
package com.assessment.agentic.url;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

/**
 * Provides database operations for URL mappings.
 *
 * Spring Data JPA generates the implementation automatically.
 * The repository uses shortCode as the entity's primary key.
 */
@Repository
public interface UrlMappingRepository
        extends JpaRepository<UrlMapping, String> {

    // JpaRepository already provides save(), findById(),
    // existsById(), and deleteById(); no manual implementation
    // is needed for these standard operations.
    // Atomically increment the click count inside the database.
@Modifying
@Query("""
       update UrlMapping u
       set u.clickCount = u.clickCount + 1
       where u.shortCode = :shortCode
       """)
int incrementClickCount(@Param("shortCode") String shortCode);
}
