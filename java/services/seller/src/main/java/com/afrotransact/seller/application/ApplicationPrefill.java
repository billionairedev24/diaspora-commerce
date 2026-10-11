package com.afrotransact.seller.application;

import com.afrotransact.seller.domain.Address;

/**
 * What the applicant's account already knows beyond the token's claims (gateway-bff reads it from identity's profile
 * and address book): name and email (access tokens may not carry the profile claims), the phone (verified or not),
 * a store name (an organisation they already belong to) and the default address, used as the business address.
 * Applied only when the application is created, and only where the token's claims have nothing.
 */
public record ApplicationPrefill(String givenName, String familyName, String email, String phone, String storeName,
                                 Address address) {

    /** Nothing beyond the token's claims. */
    public static ApplicationPrefill none() {
        return new ApplicationPrefill(null, null, null, null, null, null);
    }
}
