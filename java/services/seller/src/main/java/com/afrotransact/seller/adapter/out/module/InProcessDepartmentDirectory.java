package com.afrotransact.seller.adapter.out.module;

import java.util.EnumSet;
import java.util.List;
import java.util.Set;

import com.afrotransact.catalog.api.CatalogLookupApi;
import com.afrotransact.module.CallerPrincipals;
import com.afrotransact.module.ModuleMode;
import com.afrotransact.platform.web.module.ConditionalOnModuleMode;
import com.afrotransact.seller.domain.Department.Requirement;
import com.afrotransact.seller.domain.Department;
import com.afrotransact.seller.port.DepartmentDirectory;
import org.springframework.beans.factory.ListableBeanFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.stereotype.Component;

/**
 * {@link DepartmentDirectory} through catalog's published API inside this JVM (mode {@code in-process}): the same departments as
 * the public tree ({@code GET /v1/categories}). Not cached: there is no round trip to save. A failure is
 * {@link DirectoryUnavailable}, as over HTTP when nothing is cached.
 */
@Component
@ConditionalOnModuleMode(caller = "seller", callee = "catalog", value = ModuleMode.IN_PROCESS)
class InProcessDepartmentDirectory implements DepartmentDirectory {

    private final SellerModuleCalls<CatalogLookupApi> catalog;

    InProcessDepartmentDirectory(ObjectProvider<CatalogLookupApi> api, ObjectProvider<CallerPrincipals> callers,
                                 ListableBeanFactory beans) {
        this.catalog = new SellerModuleCalls<>(CatalogLookupApi.class, "catalog", api, callers, beans);
    }

    @Override
    public List<Department> departments() {
        try {
            return catalog.raw(CatalogLookupApi::departments).stream()
                    .map(d -> new Department(d.code(), d.name(), requirements(d.compliance()))).toList();
        } catch (RuntimeException ex) {
            throw new DirectoryUnavailable("catalog departments unavailable: " + ex.getMessage(), ex);
        }
    }

    private static Set<Requirement> requirements(Set<String> compliance) {
        Set<Requirement> out = EnumSet.noneOf(Requirement.class);
        for (String c : compliance) {
            try {
                out.add(Requirement.valueOf(c));
            } catch (IllegalArgumentException ignored) {
                // a requirement this module does not know yet asks for nothing here
            }
        }
        return out;
    }
}
