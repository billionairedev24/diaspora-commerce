package com.afrotransact.platform.security.relationship;

import java.util.List;

import com.afrotransact.platform.security.Actor;
import com.afrotransact.platform.security.PermissionChecker;
import com.afrotransact.platform.web.error.ForbiddenActionException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * FGA mode: the RBAC permission first (unchanged {@link PermissionChecker#require}), then the relationship. In
 * shadow mode ({@code enforce=false}) a denial or an unavailable store is logged and the call is allowed, so a
 * service can compare both models in an environment before enforcing.
 */
class RelationshipResourceAccessCheckerImpl implements ResourceAccessChecker {

    private static final Logger LOG = LoggerFactory.getLogger(RelationshipResourceAccessCheckerImpl.class);

    private final PermissionChecker permissions;
    private final RelationshipChecker relationships;
    private final FgaSubjectResolver subjects;
    private final boolean enforce;

    RelationshipResourceAccessCheckerImpl(PermissionChecker permissions, RelationshipChecker relationships,
                                          FgaSubjectResolver subjects, boolean enforce) {
        this.permissions = permissions;
        this.relationships = relationships;
        this.subjects = subjects;
        this.enforce = enforce;
    }

    @Override
    public Actor requireOwned(String permission, String relation, FgaObject resource, String ownerOrgId) {
        Actor actor = permissions.require(permission);
        relate(actor, relation, resource, List.of(RelationshipTuple.ownedBy(resource, ownerOrgId)));
        return actor;
    }

    @Override
    public Actor requireForOrg(String permission, String relation, String orgId) {
        Actor actor = permissions.require(permission);
        relate(actor, relation, FgaObject.organization(orgId), List.of());
        return actor;
    }

    @Override
    public Actor require(String permission, String relation, FgaObject resource,
                         RelationshipTuple... contextualTuples) {
        Actor actor = permissions.require(permission);
        relate(actor, relation, resource, List.of(contextualTuples));
        return actor;
    }

    @Override
    public boolean relationshipsEnabled() {
        return true;
    }

    private void relate(Actor actor, String relation, FgaObject object, List<RelationshipTuple> context) {
        String subject = subjects.subjectOf(actor);
        boolean allowed;
        try {
            allowed = relationships.check(subject, relation, object.key(), context);
        } catch (RuntimeException e) {
            if (enforce) {
                throw e;
            }
            LOG.warn("FGA shadow: check {} {} {} failed, allowing: {}", subject, relation, object, e.toString());
            return;
        }
        if (allowed) {
            return;
        }
        if (enforce) {
            throw new ForbiddenActionException("auth.relationship-denied",
                    "Not allowed to " + relation.replace('_', ' ') + " " + object.type() + " " + object.id());
        }
        LOG.warn("FGA shadow: {} lacks {} on {} (allowed because at.security.fga.enforce=false)",
                subject, relation, object);
    }
}
