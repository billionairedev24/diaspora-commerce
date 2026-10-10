package com.afrotransact.platform.security.relationship.openfga;

import java.util.List;

import com.afrotransact.platform.security.relationship.RelationshipChecker;
import com.afrotransact.platform.security.relationship.RelationshipTuple;

/**
 * {@link RelationshipChecker} adapter for OpenFGA's {@code /check}, with the short-TTL {@link RelationshipCheckCache}
 * in front. The cache key includes the contextual tuples, so a different owner is never served a cached answer.
 */
class OpenFgaRelationshipChecker implements RelationshipChecker {

    private final OpenFgaClient client;
    private final RelationshipCheckCache cache;

    OpenFgaRelationshipChecker(OpenFgaClient client, RelationshipCheckCache cache) {
        this.client = client;
        this.cache = cache;
    }

    @Override
    public boolean check(String user, String relation, String object, List<RelationshipTuple> contextualTuples) {
        RelationshipTuple tuple = new RelationshipTuple(user, relation, object);
        if (!cache.enabled()) {
            return client.check(tuple, contextualTuples);
        }
        String key = cacheKey(tuple, contextualTuples);
        Boolean cached = cache.get(key);
        if (cached != null) {
            return cached;
        }
        boolean allowed = client.check(tuple, contextualTuples);
        cache.put(key, allowed);
        return allowed;
    }

    private static String cacheKey(RelationshipTuple tuple, List<RelationshipTuple> context) {
        StringBuilder key = new StringBuilder(tuple.user()).append('|').append(tuple.relation()).append('|')
                .append(tuple.object());
        for (RelationshipTuple t : context) {
            key.append('|').append(t.user()).append('>').append(t.relation()).append('>').append(t.object());
        }
        return key.toString();
    }
}
