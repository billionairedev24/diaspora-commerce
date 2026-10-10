package com.afrotransact.platform.security.relationship.openfga;

import java.util.List;

import com.afrotransact.platform.security.relationship.RelationshipTuple;
import com.afrotransact.platform.security.relationship.RelationshipWriter;

/**
 * {@link RelationshipWriter} adapter for OpenFGA's {@code /write} ({@code on_duplicate} / {@code on_missing} =
 * ignore, so replays are harmless). Clears this instance's check cache after every write.
 */
class OpenFgaRelationshipWriter implements RelationshipWriter {

    private final OpenFgaClient client;
    private final RelationshipCheckCache cache;

    OpenFgaRelationshipWriter(OpenFgaClient client, RelationshipCheckCache cache) {
        this.client = client;
        this.cache = cache;
    }

    @Override
    public void write(List<RelationshipTuple> writes, List<RelationshipTuple> deletes) {
        if (writes.isEmpty() && deletes.isEmpty()) {
            return;
        }
        if (writes.size() + deletes.size() > MAX_TUPLES_PER_CALL) {
            throw new IllegalArgumentException("At most " + MAX_TUPLES_PER_CALL + " tuples per write");
        }
        try {
            client.write(writes, deletes);
        } finally {
            cache.clear();
        }
    }
}
