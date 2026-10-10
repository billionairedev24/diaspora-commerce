package com.afrotransact.trustops.domain;

import java.time.Instant;
import java.util.Objects;

/** An evidence attachment: a media-service asset id (photo, document, video) with who added it. */
public record Evidence(String assetId, PartyRole party, String addedBy, String note, Instant addedAt) {

    public Evidence {
        Objects.requireNonNull(assetId, "assetId");
        Objects.requireNonNull(party, "party");
        Objects.requireNonNull(addedAt, "addedAt");
    }
}
