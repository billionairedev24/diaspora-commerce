package com.afrotransact.identity.api;

import java.util.List;

import com.afrotransact.module.EndUserActor;

/**
 * The signed-in customer's own address book, read in-process for a call made on their behalf (checkout and delivery quotes in orders):
 * the in-process equivalent of {@code GET /v1/me/addresses} with the customer's token and {@code X-Acting-As: CUSTOMER}.
 *
 * <p>The method takes the person explicitly ({@link EndUserActor}, built from the request's authenticated security context, never
 * from a parameter). The provider runs identity's address-book use case with that person as the actor in force, so the owner is the
 * token's subject and nothing else: there is no way to name another customer. The calling module needs the delegation scope
 * {@value #CALLER_SCOPE} in its caller scopes; without it the call is refused with {@code ModuleApiException} 403
 * {@code auth.missing-permission}. A service token or a missing authentication never reaches this far
 * ({@code EndUserActor.fromAuthentication} refuses them with 403 / 401).
 */
public interface AddressBookApi {

    /** The scope a module needs to read address books on behalf of its signed-in users. */
    String CALLER_SCOPE = "identity:addresses.read";

    /** The person's saved addresses, default first. */
    List<Address> addresses(EndUserActor user);

    /** One saved address, as {@code GET /v1/me/addresses} lists it. */
    record Address(String id, String label, String fullName, String phone, String line1, String line2, String city,
                   String state, String postalCode, String country, Double latitude, Double longitude,
                   String instructions, boolean verified, boolean isDefault) {
    }
}
