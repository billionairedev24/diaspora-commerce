package com.afrotransact.identity.domain;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.Set;

import org.junit.jupiter.api.Test;

class ClientReconcilePlanTest {

    private static ClientReconcilePlan plan() {
        return ClientReconcilePlan.between(Set.of("openid", "orders:read"), Set.of("openid", "legacy"),
                Set.of("https://a/cb", "https://b/cb"), Set.of("https://a/cb", "https://old/cb"), Set.of(), Set.of());
    }

    @Test
    void findsAdditionsAndRemovals() {
        ClientReconcilePlan p = plan();
        assertThat(p.addedScopes()).containsExactly("orders:read");
        assertThat(p.removedScopes()).containsExactly("legacy");
        assertThat(p.addedRedirectUris()).containsExactly("https://b/cb");
        assertThat(p.removedRedirectUris()).containsExactly("https://old/cb");
        assertThat(p.inSync()).isFalse();
    }

    @Test
    void reportNeverChangesAnything() {
        ClientReconcilePlan p = plan();
        assertThat(p.changes(ClientReconcileMode.REPORT)).isFalse();
        assertThat(p.resultingScopes(ClientReconcileMode.REPORT, Set.of("openid", "legacy")))
                .containsExactlyInAnyOrder("openid", "legacy");
    }

    @Test
    void additiveAddsButNeverRemoves() {
        ClientReconcilePlan p = plan();
        assertThat(p.changes(ClientReconcileMode.ADDITIVE)).isTrue();
        assertThat(p.resultingScopes(ClientReconcileMode.ADDITIVE, Set.of("openid", "legacy")))
                .containsExactlyInAnyOrder("openid", "legacy", "orders:read");
        assertThat(p.resultingRedirectUris(ClientReconcileMode.ADDITIVE, Set.of("https://a/cb", "https://old/cb")))
                .containsExactlyInAnyOrder("https://a/cb", "https://old/cb", "https://b/cb");
    }

    @Test
    void additiveHasNothingToDoWhenOnlyRemovalsDiffer() {
        ClientReconcilePlan p = ClientReconcilePlan.between(Set.of("openid"), Set.of("openid", "mcp:staff"), Set.of(),
                Set.of(), Set.of(), Set.of());
        assertThat(p.changes(ClientReconcileMode.ADDITIVE)).isFalse();
        assertThat(p.changes(ClientReconcileMode.SYNC)).isTrue();
    }

    @Test
    void syncMakesTheStoredSetsEqualToTheConfiguredOnes() {
        ClientReconcilePlan p = plan();
        assertThat(p.resultingScopes(ClientReconcileMode.SYNC, Set.of("openid", "legacy")))
                .containsExactlyInAnyOrder("openid", "orders:read");
        assertThat(p.resultingRedirectUris(ClientReconcileMode.SYNC, Set.of("https://a/cb", "https://old/cb")))
                .containsExactlyInAnyOrder("https://a/cb", "https://b/cb");
    }

    @Test
    void inSyncSetsNeedNoChange() {
        ClientReconcilePlan p = ClientReconcilePlan.between(Set.of("a"), Set.of("a"), Set.of("u"), Set.of("u"),
                Set.of(), Set.of());
        assertThat(p.inSync()).isTrue();
        for (ClientReconcileMode m : ClientReconcileMode.values()) {
            assertThat(p.changes(m)).isFalse();
        }
    }

    @Test
    void modeParsing() {
        assertThat(ClientReconcileMode.parse(null)).isEqualTo(ClientReconcileMode.REPORT);
        assertThat(ClientReconcileMode.parse(" ")).isEqualTo(ClientReconcileMode.REPORT);
        assertThat(ClientReconcileMode.parse("Additive")).isEqualTo(ClientReconcileMode.ADDITIVE);
        assertThat(ClientReconcileMode.parse("sync")).isEqualTo(ClientReconcileMode.SYNC);
        assertThatThrownBy(() -> ClientReconcileMode.parse("delete-all")).isInstanceOf(IllegalStateException.class);
    }
}
