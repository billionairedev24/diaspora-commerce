package com.afrotransact.seller.adapter.out.module;

import java.util.List;

import com.afrotransact.identity.api.StaffDirectoryApi;
import com.afrotransact.module.CallerPrincipals;
import com.afrotransact.seller.port.GatewayException;
import com.afrotransact.seller.port.StaffDirectory;
import org.springframework.beans.factory.ListableBeanFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.autoconfigure.condition.ConditionalOnExpression;
import org.springframework.stereotype.Component;

/**
 * {@link StaffDirectory} through identity's published API inside this JVM (mode {@code in-process}): the same roster as
 * {@code GET /v1/internal/staff}, entered as seller's caller principal (scope {@code users:read}). Any failure is the
 * {@code seller.staff-directory-unavailable} {@link GatewayException}, as over HTTP.
 */
@Component
@ConditionalOnExpression("'${at.seller.identity.mode:${at.seller.mode:http}}' == 'in-process'")
class InProcessStaffDirectory implements StaffDirectory {

    private final SellerModuleCalls<StaffDirectoryApi> identity;

    InProcessStaffDirectory(ObjectProvider<StaffDirectoryApi> api, ObjectProvider<CallerPrincipals> callers,
                            ListableBeanFactory beans) {
        this.identity = new SellerModuleCalls<>(StaffDirectoryApi.class, "identity", api, callers, beans);
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
        try {
            return identity.raw(api -> api.staff(permission, role, ids)).stream()
                    .map(m -> new StaffPerson(m.userId(), m.name() == null ? "" : m.name())).toList();
        } catch (RuntimeException e) {
            throw new GatewayException("seller.staff-directory-unavailable",
                    "The staff directory is unavailable: " + e.getMessage(), false, e);
        }
    }
}
