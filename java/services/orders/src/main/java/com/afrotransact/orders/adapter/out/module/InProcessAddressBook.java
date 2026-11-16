package com.afrotransact.orders.adapter.out.module;

import java.util.Optional;

import com.afrotransact.identity.api.AddressBookApi;
import com.afrotransact.module.InProcessCalls;
import com.afrotransact.module.ModuleMode;
import com.afrotransact.orders.domain.ShipTo;
import com.afrotransact.orders.port.AddressBook;
import com.afrotransact.platform.security.EndUsers;
import com.afrotransact.platform.web.module.ConditionalOnModuleMode;
import org.springframework.beans.factory.ListableBeanFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.stereotype.Component;

/**
 * {@link AddressBook} through identity's published {@link AddressBookApi} inside this JVM (mode {@code in-process}): the first edge
 * made for the signed-in person rather than for the service. The person is the one of the request's authenticated security context
 * acting as a shopper ({@code X-Acting-As: CUSTOMER}, as the HTTP adapter sends), captured on the request thread; the bearer string
 * the port still carries is not read, so nothing but the validated authentication decides whose addresses are read. Identity applies
 * its own rule (the owner is the token's subject), the calling module needs {@code identity:addresses.read}. An unknown address id is
 * "not found" (empty), as over HTTP; no signed-in person is the HTTP 401.
 */
@Component
@ConditionalOnModuleMode(caller = "orders", callee = "identity", value = ModuleMode.IN_PROCESS)
class InProcessAddressBook implements AddressBook {

    private static final String SERVICE = "identity";

    private final ObjectProvider<AddressBookApi> api;
    private final ModuleUpstream upstream;
    private final EndUsers endUsers;

    InProcessAddressBook(ObjectProvider<AddressBookApi> api, ModuleUpstream upstream, EndUsers endUsers,
                         ListableBeanFactory beans) {
        InProcessCalls.requireRunning(beans.getBeanNamesForType(AddressBookApi.class, true, false).length > 0, "orders",
                "identity", "at.orders.identity.mode");
        this.api = api;
        this.upstream = upstream;
        this.endUsers = endUsers;
    }

    @Override
    public Optional<ShipTo> find(String customerBearer, String addressId) {
        return upstream.forUser(SERVICE, endUsers::currentAsCustomer, user -> api.getObject().addresses(user)).stream()
                .filter(a -> addressId.equals(a.id())).findFirst()
                .map(a -> new ShipTo(addressId, a.fullName(), a.phone(), a.line1(), a.line2(), a.city(), a.state(),
                        a.postalCode(), a.country() == null ? "US" : a.country(), a.instructions(), a.latitude(),
                        a.longitude()));
    }
}
