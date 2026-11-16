package com.afrotransact.identity.adapter.in.module;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.lang.reflect.RecordComponent;
import java.time.Instant;
import java.util.Arrays;
import java.util.List;
import java.util.UUID;

import com.afrotransact.identity.api.AddressBookApi;
import com.afrotransact.identity.application.AddressBookService;
import com.afrotransact.identity.domain.PostalAddress;
import com.afrotransact.module.InProcessCalls;
import com.afrotransact.module.ModuleApiException;
import com.afrotransact.platform.test.InProcessKit;
import org.junit.jupiter.api.Test;

/** The published address-book API answers what {@code GET /v1/me/addresses} answers, to a module that holds the delegation scope. */
class AddressBookApiAdapterTest {

    private final AddressBookService book = mock(AddressBookService.class);
    private final AddressBookApi api = new AddressBookApiAdapter(book, InProcessKit.ACTORS);
    private final UUID id = UUID.randomUUID();

    @Test
    void returnsTheListInTheServicesOrderFieldForField() {
        Instant now = Instant.now();
        when(book.list()).thenReturn(List.of(new PostalAddress(id, UUID.randomUUID(), "Home", "Ada Obi", "+15125550100",
                "1 Main St", "Apt 2", "Houston", "TX", "77002", "US", 29.7, -95.3, "Ring", true, true, now, now)));
        var person = InProcessKit.user(UUID.randomUUID().toString(), "pt", "CUSTOMER");
        List<AddressBookApi.Address> out = InProcessCalls.as(InProcessKit.caller("orders", AddressBookApi.CALLER_SCOPE),
                "identity", () -> api.addresses(person));
        assertThat(out).containsExactly(new AddressBookApi.Address(id.toString(), "Home", "Ada Obi", "+15125550100",
                "1 Main St", "Apt 2", "Houston", "TX", "77002", "US", 29.7, -95.3, "Ring", true, true));
    }

    @Test
    void theRecordHasTheFieldsOfTheHttpResponse() throws ClassNotFoundException {
        Class<?> http = Class.forName("com.afrotransact.identity.adapter.in.web.AddressController$AddressResponse");
        List<String> httpFields = Arrays.stream(http.getRecordComponents()).map(RecordComponent::getName).toList();
        List<String> apiFields = Arrays.stream(AddressBookApi.Address.class.getRecordComponents())
                .map(RecordComponent::getName).toList();
        assertThat(apiFields).isEqualTo(httpFields);
    }

    @Test
    void aModuleWithoutTheDelegationScopeIsRefused() {
        var person = InProcessKit.user(UUID.randomUUID().toString(), "pt", "CUSTOMER");
        assertThatThrownBy(() -> InProcessCalls.as(InProcessKit.caller("orders", "users:read"), "identity",
                () -> api.addresses(person))).isInstanceOfSatisfying(ModuleApiException.class, e -> {
                    assertThat(e.status()).isEqualTo(403);
                    assertThat(e.code()).isEqualTo("auth.missing-permission");
                });
    }

    @Test
    void enteredWithoutAnInProcessCallerItFailsClosed() {
        var person = InProcessKit.user(UUID.randomUUID().toString(), "pt", "CUSTOMER");
        assertThatThrownBy(() -> api.addresses(person)).isInstanceOf(IllegalStateException.class);
    }
}
