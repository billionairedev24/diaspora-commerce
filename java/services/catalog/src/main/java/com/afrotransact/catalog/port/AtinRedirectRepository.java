package com.afrotransact.catalog.port;

import java.time.Instant;
import java.util.Map;
import java.util.Optional;

/** Where an ATIN went when two duplicate pages were merged: the page ATIN and each variant ATIN of the page merged away. */
public interface AtinRedirectRepository {

    /** Records each {@code from -> to} pair; an ATIN that already redirects keeps its first target. */
    void saveAll(Map<String, String> fromTo, String mergedBy, String reason, Instant at);

    /** The ATIN the given one now redirects to (one hop; merges are applied in order so chains collapse). */
    Optional<String> find(String fromAtin);

    /** Points everything that redirected to {@code oldTarget} at {@code newTarget} (a survivor was merged away itself). */
    void repoint(String oldTarget, String newTarget);
}
