package com.afrotransact.platform.security.relationship;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.Set;

import com.afrotransact.platform.security.Actor;
import com.afrotransact.platform.security.DefaultPermissionChecker;
import com.afrotransact.platform.security.PermissionChecker;
import com.afrotransact.platform.security.PrincipalType;
import com.afrotransact.platform.web.error.ForbiddenActionException;
import org.junit.jupiter.api.Test;

class ResourceAccessCheckersTest {

    private static final FgaObject OFFER = FgaObject.of("offer", "o-1");

    private Actor current = seller("anne", "org_a");
    private final PermissionChecker permissions = new DefaultPermissionChecker(() -> current);
    private final RecordingChecker store = new RecordingChecker();

    @Test
    void rbacOnlyModeKeepsTodaysBehaviour() {
        ResourceAccessChecker access = ResourceAccessCheckers.rbacOnly(permissions);

        assertThat(access.relationshipsEnabled()).isFalse();
        assertThat(access.requireOwned("offers:write", "can_edit", OFFER, "org_a")).isEqualTo(current);
        assertThatThrownBy(() -> access.requireOwned("offers:write", "can_edit", OFFER, "org_b"))
                .isInstanceOf(ForbiddenActionException.class).hasFieldOrPropertyWithValue("code", "auth.wrong-org");
        assertThatThrownBy(() -> access.requireForOrg("payouts:read", "can_view_payouts", "org_a"))
                .hasFieldOrPropertyWithValue("code", "auth.missing-permission");
        assertThat(access.require("offers:read", "can_view", OFFER)).isEqualTo(current);
    }

    @Test
    void relationshipModeChecksPermissionThenRelationWithTheOwnerAsContext() {
        ResourceAccessChecker access = ResourceAccessCheckers.withRelationships(permissions, store,
                FgaSubjectResolver.standard(), true);
        store.answer = true;

        access.requireOwned("offers:write", "can_edit", OFFER, "org_a");

        assertThat(store.calls).containsExactly("user:anne can_edit offer:o-1 [organization:org_a org offer:o-1]");
    }

    @Test
    void aForgedOrgClaimDoesNotHelpWhenTheRelationshipIsMissing() {
        current = seller("anne", "org_b");
        ResourceAccessChecker access = ResourceAccessCheckers.withRelationships(permissions, store,
                FgaSubjectResolver.standard(), true);
        store.answer = false;

        assertThatThrownBy(() -> access.requireOwned("offers:write", "can_edit", OFFER, "org_b"))
                .isInstanceOf(ForbiddenActionException.class)
                .hasFieldOrPropertyWithValue("code", "auth.relationship-denied");
        assertThatThrownBy(() -> access.requireForOrg("offers:write", "can_manage_products", "org_b"))
                .hasFieldOrPropertyWithValue("code", "auth.relationship-denied");
    }

    @Test
    void theRbacPermissionIsStillRequiredAndCheckedFirst() {
        ResourceAccessChecker access = ResourceAccessCheckers.withRelationships(permissions, store,
                FgaSubjectResolver.standard(), true);
        store.answer = true;

        assertThatThrownBy(() -> access.requireForOrg("payouts:read", "can_view_payouts", "org_a"))
                .hasFieldOrPropertyWithValue("code", "auth.missing-permission");
        assertThat(store.calls).isEmpty();
    }

    @Test
    void shadowModeLogsDenialsAndStoreErrorsButAllows() {
        ResourceAccessChecker access = ResourceAccessCheckers.withRelationships(permissions, store,
                FgaSubjectResolver.standard(), false);
        store.answer = false;
        assertThat(access.requireOwned("offers:write", "can_edit", OFFER, "org_a")).isEqualTo(current);

        store.failure = new RelationshipCheckUnavailableException("down", null);
        assertThat(access.requireOwned("offers:write", "can_edit", OFFER, "org_a")).isEqualTo(current);
    }

    @Test
    void enforcingModeFailsClosedWhenTheStoreIsDown() {
        ResourceAccessChecker access = ResourceAccessCheckers.withRelationships(permissions, store,
                FgaSubjectResolver.standard(), true);
        store.failure = new RelationshipCheckUnavailableException("down", null);

        assertThatThrownBy(() -> access.requireOwned("offers:write", "can_edit", OFFER, "org_a"))
                .isInstanceOf(RelationshipCheckUnavailableException.class);
    }

    @Test
    void clientsAreMappedToTheClientType() {
        FgaSubjectResolver subjects = FgaSubjectResolver.standard();
        assertThat(subjects.subjectOf(new Actor("orders-service", PrincipalType.SERVICE, Optional.empty(), Set.of(),
                Set.of()))).isEqualTo("client:orders-service");
        assertThat(subjects.subjectOf(new Actor("u1", PrincipalType.STAFF, Optional.empty(), Set.of(), Set.of())))
                .isEqualTo("user:u1");
    }

    @Test
    void objectsAndTuplesAreValidated() {
        assertThat(FgaObject.organization("org_a").key()).isEqualTo("organization:org_a");
        assertThatThrownBy(() -> FgaObject.of("Offer", "1")).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> FgaObject.of("offer", "a#b")).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new RelationshipTuple("user:a", " ", "offer:1"))
                .isInstanceOf(IllegalArgumentException.class);
        assertThat(RelationshipTuple.ownedBy(OFFER, "org_a"))
                .isEqualTo(new RelationshipTuple("organization:org_a", "org", "offer:o-1"));
    }

    private static Actor seller(String sub, String org) {
        return new Actor(sub, PrincipalType.SELLER_MEMBER, Optional.of(org), Set.of("seller.catalog"),
                Set.of("offers:read", "offers:write"));
    }

    private static final class RecordingChecker implements RelationshipChecker {

        private final List<String> calls = new ArrayList<>();
        private boolean answer;
        private RuntimeException failure;

        @Override
        public boolean check(String user, String relation, String object, List<RelationshipTuple> context) {
            if (failure != null) {
                throw failure;
            }
            calls.add(user + " " + relation + " " + object + " " + context.stream()
                    .map(t -> t.user() + " " + t.relation() + " " + t.object()).toList());
            return answer;
        }
    }
}
