package com.afrotransact.identity.adapter.in.module;

import java.util.List;

import com.afrotransact.identity.api.AddressBookApi;
import com.afrotransact.identity.application.AddressBookService;
import com.afrotransact.identity.domain.PostalAddress;
import com.afrotransact.module.EndUserActor;
import com.afrotransact.platform.security.ActorResolver;
import com.afrotransact.platform.security.ModuleEntry;
import org.springframework.stereotype.Component;

/**
 * The in-process entry of {@link AddressBookApi}: the use case behind {@code GET /v1/me/addresses}, entered for the signed-in
 * person. {@link AddressBookService#list()} reads the owner from the actor in force, which inside {@code runAsUser} is the person's
 * token subject (never the calling module), so tenant isolation is the HTTP controller's: a customer's own addresses only.
 */
@Component
class AddressBookApiAdapter implements AddressBookApi {

    private final AddressBookService book;
    private final ModuleEntry entry;

    AddressBookApiAdapter(AddressBookService book, ActorResolver actors) {
        this.book = book;
        this.entry = new ModuleEntry(actors);
    }

    @Override
    public List<Address> addresses(EndUserActor user) {
        return entry.runAsUser(user, CALLER_SCOPE, () -> book.list().stream().map(AddressBookApiAdapter::address).toList());
    }

    private static Address address(PostalAddress a) {
        return new Address(a.id().toString(), a.label(), a.fullName(), a.phone(), a.line1(), a.line2(), a.city(), a.state(),
                a.postalCode(), a.country(), a.latitude(), a.longitude(), a.instructions(), a.verified(), a.isDefault());
    }
}
