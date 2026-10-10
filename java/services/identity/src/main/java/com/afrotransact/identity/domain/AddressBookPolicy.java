package com.afrotransact.identity.domain;

import java.util.Map;

/** Validates address book entries. */
public interface AddressBookPolicy {

    int MAX_ADDRESSES = 20;

    /** Returns field errors (empty when valid); the phone is normalised by the caller with PhoneNumberPolicy. */
    Map<String, String> check(AddressDraft draft);

    /**
     * Raw address input.
     *
     * @param phoneRegion ISO region of the phone number when it is not typed in E.164
     */
    record AddressDraft(String label, String fullName, String phone, String phoneRegion, String line1, String line2,
                        String city, String state, String postalCode, String country, String instructions) {
    }
}
