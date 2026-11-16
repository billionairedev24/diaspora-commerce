package com.afrotransact.mcp.adapter.out.module;

import com.afrotransact.bookings.api.UserServiceSearchApi;
import com.afrotransact.mcp.port.BookingsApi;
import com.afrotransact.mcp.port.CallerCredentials;
import com.afrotransact.mcp.port.Downstream;
import com.afrotransact.module.InProcessCalls;
import com.afrotransact.module.ModuleMode;
import com.afrotransact.platform.web.module.ConditionalOnModuleMode;
import org.springframework.beans.factory.ListableBeanFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.stereotype.Component;

/**
 * {@link BookingsApi} through bookings' published {@link UserServiceSearchApi} inside this JVM (mode {@code in-process}), run as the
 * signed-in person captured at request entry. The bearer token the port still carries is not read.
 */
@Component
@ConditionalOnModuleMode(caller = "mcp-server", callee = "bookings", value = ModuleMode.IN_PROCESS)
class InProcessBookingsApi implements BookingsApi {

    private static final String SERVICE = "bookings";

    private final ObjectProvider<UserServiceSearchApi> api;
    private final ModuleUpstream upstream;

    InProcessBookingsApi(ObjectProvider<UserServiceSearchApi> api, ModuleUpstream upstream, ListableBeanFactory beans) {
        InProcessCalls.requireRunning(beans.getBeanNamesForType(UserServiceSearchApi.class, true, false).length > 0, "mcp-server",
                "bookings", "at.mcpserver.bookings.mode");
        this.api = api;
        this.upstream = upstream;
    }

    @Override
    public Downstream findServices(String query, String zip, CallerCredentials credentials) {
        return upstream.call(SERVICE, credentials, user -> api.getObject().findServices(user, query, zip));
    }
}
