package com.afrotransact.orders.adapter.out.module;

import java.util.List;

import com.afrotransact.identity.api.StaffDirectoryApi;
import com.afrotransact.module.InProcessCalls;
import com.afrotransact.module.ModuleMode;
import com.afrotransact.orders.port.StaffDirectory;
import com.afrotransact.platform.web.module.ConditionalOnModuleMode;
import org.springframework.beans.factory.ListableBeanFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.stereotype.Component;

/**
 * {@link StaffDirectory} through identity's published {@link StaffDirectoryApi} inside this JVM (mode {@code in-process}), entered as
 * orders' caller principal (scope {@code users:read}, as {@code GET /v1/internal/staff} needs). Same list as over HTTP: enabled staff
 * only, names for ids; a refusal or a failure is the {@code UpstreamFailure} the HTTP adapter raises.
 */
@Component
@ConditionalOnModuleMode(caller = "orders", callee = "identity", value = ModuleMode.IN_PROCESS)
class InProcessStaffDirectory implements StaffDirectory {

    private static final String SERVICE = "identity";

    private final ObjectProvider<StaffDirectoryApi> api;
    private final ModuleUpstream upstream;

    InProcessStaffDirectory(ObjectProvider<StaffDirectoryApi> api, ModuleUpstream upstream, ListableBeanFactory beans) {
        InProcessCalls.requireRunning(beans.getBeanNamesForType(StaffDirectoryApi.class, true, false).length > 0, "orders",
                "identity", "at.orders.identity.mode");
        this.api = api;
        this.upstream = upstream;
    }

    @Override
    public List<StaffPerson> withPermission(String permission) {
        return fetch(permission, null, null);
    }

    @Override
    public List<StaffPerson> withRole(String roleKey) {
        return fetch(null, roleKey, null);
    }

    @Override
    public List<StaffPerson> people(List<String> userIds) {
        if (userIds == null || userIds.isEmpty()) {
            return List.of();
        }
        return fetch(null, null, userIds);
    }

    private List<StaffPerson> fetch(String permission, String role, List<String> ids) {
        return upstream.call(SERVICE, () -> api.getObject().staff(permission, role, ids)).stream()
                .map(m -> new StaffPerson(m.userId(), m.name() == null ? "" : m.name())).toList();
    }
}
