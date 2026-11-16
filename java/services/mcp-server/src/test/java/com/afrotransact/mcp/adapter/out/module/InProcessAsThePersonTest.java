package com.afrotransact.mcp.adapter.out.module;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.lang.reflect.Proxy;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;

import com.afrotransact.catalog.api.CatalogUserApi;
import com.afrotransact.mcp.port.CallerCredentials;
import com.afrotransact.mcp.port.CatalogApi;
import com.afrotransact.mcp.port.Downstream;
import com.afrotransact.module.CallerPrincipal;
import com.afrotransact.module.CallerPrincipals;
import com.afrotransact.module.EndUserActor;
import com.afrotransact.module.InProcessCalls;
import com.afrotransact.module.ModuleApiException;
import com.afrotransact.platform.test.InProcessKit;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.support.DefaultListableBeanFactory;

/**
 * Every tool call of the in-process edges is made AS THE SIGNED-IN PERSON captured at request entry: the callee gets exactly that
 * person, under mcp-server's own caller principal (whose scopes are delegation scopes, never the person's), and a request without a
 * valid person (no authentication, a service token) never reaches the callee.
 */
class InProcessAsThePersonTest {

    private final List<String> calls = new ArrayList<>();
    private final AtomicReference<EndUserActor> seenPerson = new AtomicReference<>();
    private final AtomicReference<CallerPrincipal> seenCaller = new AtomicReference<>();

    private final CatalogUserApi stub = (CatalogUserApi) Proxy.newProxyInstance(
            getClass().getClassLoader(), new Class<?>[] {CatalogUserApi.class}, (proxy, method, args) -> {
                calls.add(method.getName());
                seenPerson.set((EndUserActor) args[0]);
                seenCaller.set(InProcessCalls.require());
                return Map.of("ok", true);
            });

    private final CatalogApi catalog = InProcessWiring.catalog(stub);

    @Test
    void theCalleeGetsThePersonCapturedAtRequestEntryUnderMcpServersOwnPrincipal() {
        EndUserActor seller = InProcessKit.user("seller-1", "pt", "SELLER_MEMBER", "org", "org-1", "perms", List.of("mcp:seller"));
        Downstream d = catalog.product("AT1AAAAAAA", new CallerCredentials("ignored-token", () -> seller));

        assertThat(d).isEqualTo(Downstream.ok(Map.of("ok", true)));
        assertThat(seenPerson.get()).isSameAs(seller);
        assertThat(seenCaller.get().module()).isEqualTo("mcp-server");
        assertThat(seenCaller.get().scopes()).doesNotContain("mcp:seller");
        assertThat(seenCaller.get().scopes()).containsExactlyInAnyOrderElementsOf(InProcessWiring.MCP_SERVER.scopes());
    }

    @Test
    void thePersonIsTakenFromTheCredentialsNotFromAnythingElseOnTheThread() {
        EndUserActor first = InProcessKit.user("u-1", "pt", "CUSTOMER");
        EndUserActor second = InProcessKit.user("u-2", "pt", "CUSTOMER");
        catalog.publicProduct("AT1", new CallerCredentials(null, () -> first));
        assertThat(seenPerson.get().userId()).isEqualTo("u-1");
        catalog.publicProduct("AT1", new CallerCredentials(null, () -> second));
        assertThat(seenPerson.get().userId()).isEqualTo("u-2");
    }

    @Test
    void aRequestWithoutAValidPersonNeverReachesTheCallee() {
        CallerCredentials noPerson = new CallerCredentials("tok", () -> {
            throw new ModuleApiException(401, "auth.unauthenticated", "No authenticated user");
        });
        CallerCredentials serviceToken = new CallerCredentials("tok", () -> {
            throw new ModuleApiException(403, "auth.service-token", "A service token cannot act as an end user");
        });
        assertThat(catalog.product("AT1", noPerson)).isEqualTo(Downstream.of(Downstream.Status.FORBIDDEN));
        assertThat(catalog.requestCategory(Map.of(), serviceToken)).isEqualTo(Downstream.of(Downstream.Status.FORBIDDEN));
        assertThat(calls).isEmpty();
    }

    @Test
    void theStartCheckNamesBothModulesWhenCatalogIsNotRunning() {
        DefaultListableBeanFactory beans = new DefaultListableBeanFactory();
        assertThatThrownBy(() -> new InProcessCatalogApi(beans.getBeanProvider(CatalogUserApi.class),
                new ModuleUpstream(beans.getBeanProvider(CallerPrincipals.class)), beans))
                .hasMessageContaining("mcp-server").hasMessageContaining("catalog").hasMessageContaining("at.mcpserver.catalog.mode");
    }
}
