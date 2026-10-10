package com.afrotransact.identity.domain;

import static org.assertj.core.api.Assertions.assertThat;

import com.afrotransact.identity.domain.AddressBookPolicy.AddressDraft;
import org.junit.jupiter.api.Test;

class DefaultAddressBookPolicyTest {

    private final AddressBookPolicy policy = new DefaultAddressBookPolicy();

    @Test
    void acceptsAUsAddress() {
        assertThat(policy.check(new AddressDraft("Home", "Adaeze Okafor", "+17132490142", "US", "123 Main St",
                "Apt 4B", "Houston", "tx", "77002", "US", "Gate code 1234"))).isEmpty();
    }

    @Test
    void reportsEveryProblem() {
        assertThat(policy.check(new AddressDraft(null, "", null, null, "1", null, "", "ZZ", "7700", "US",
                "x".repeat(301)))).containsKeys("fullName", "line1", "city", "state", "postalCode", "instructions");
    }

    @Test
    void otherCountriesNeedAPostalCode() {
        assertThat(policy.check(new AddressDraft(null, "Kwame Mensah", null, null, "12 Ring Road", null, "Accra",
                null, "", "GH", null))).containsOnlyKeys("postalCode");
    }
}
