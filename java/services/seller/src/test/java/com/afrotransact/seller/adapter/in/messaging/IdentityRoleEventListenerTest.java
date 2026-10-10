package com.afrotransact.seller.adapter.in.messaging;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import com.afrotransact.seller.application.OrgMemberSyncService;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.json.JsonMapper;

/** S236: identity role events are parsed and routed to the member sync (listener called directly). */
class IdentityRoleEventListenerTest {

    private final List<String> calls = new ArrayList<>();
    private final OrgMemberSyncService fake = new OrgMemberSyncService() {
        @Override
        public boolean granted(RoleChange change) {
            calls.add("granted " + change.orgId() + " " + change.userId() + " " + change.roleKey() + " "
                    + change.eventId());
            return true;
        }

        @Override
        public boolean revoked(RoleChange change) {
            calls.add("revoked " + change.orgId() + " " + change.userId() + " " + change.roleKey());
            return true;
        }
    };
    private final IdentityRoleEventListener listener = new IdentityRoleEventListener(fake,
            JsonMapper.builder().build());

    private static ConsumerRecord<String, String> rec(String type, String eventId, String payload) {
        ConsumerRecord<String, String> r = new ConsumerRecord<>("at.identity.events", 0, 0, "user-1", payload);
        if (type != null) {
            r.headers().add("event-type", type.getBytes(StandardCharsets.UTF_8));
        }
        if (eventId != null) {
            r.headers().add("event-id", eventId.getBytes(StandardCharsets.UTF_8));
        }
        return r;
    }

    private static String role(String roleKey, String orgId) {
        return "{\"userId\":\"user-1\",\"roleKey\":\"" + roleKey + "\",\"orgId\":" + (orgId == null ? "null"
                : "\"" + orgId + "\"") + ",\"actorId\":\"owner-1\",\"reason\":\"team\","
                + "\"at\":\"2026-10-01T10:00:00Z\",\"extra\":1}";
    }

    @Test
    void grantedAndRevokedOrganisationRolesAreForwarded() {
        UUID id = UUID.randomUUID();
        assertThat(listener.handle(rec("identity.role.granted", id.toString(), role("seller.catalog", "org_1"))))
                .isTrue();
        assertThat(listener.handle(rec("identity.role.revoked", UUID.randomUUID().toString(),
                role("seller.catalog", "org_1")))).isTrue();
        assertThat(calls).containsExactly("granted org_1 user-1 seller.catalog " + id,
                "revoked org_1 user-1 seller.catalog");
    }

    @Test
    void otherEventsMalformedOnesAndRolesWithoutOrgAreSkipped() {
        assertThat(listener.handle(rec("identity.user.registered", UUID.randomUUID().toString(), "{}"))).isFalse();
        assertThat(listener.handle(rec("identity.role.granted", null, role("seller.catalog", "org_1")))).isFalse();
        assertThat(listener.handle(rec("identity.role.granted", "not-a-uuid", role("seller.catalog", "org_1"))))
                .isFalse();
        assertThat(listener.handle(rec("identity.role.granted", UUID.randomUUID().toString(), "{not json")))
                .isFalse();
        assertThat(listener.handle(rec("identity.role.granted", UUID.randomUUID().toString(),
                role("account.customer", null)))).isFalse();
        assertThat(calls).isEmpty();
    }
}
