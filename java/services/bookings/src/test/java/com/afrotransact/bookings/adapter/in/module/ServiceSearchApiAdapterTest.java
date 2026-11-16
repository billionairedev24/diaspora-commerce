package com.afrotransact.bookings.adapter.in.module;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.time.Instant;
import java.util.List;
import java.util.UUID;
import java.util.function.Supplier;

import com.afrotransact.bookings.api.ServiceSearchApi;
import com.afrotransact.bookings.application.PublicBookingsService;
import com.afrotransact.bookings.application.PublicBookingsService.Listing;
import com.afrotransact.bookings.application.PublicBookingsService.SearchQuery;
import com.afrotransact.bookings.application.PublicBookingsService.SearchResult;
import com.afrotransact.bookings.domain.BookingsRuleViolation;
import com.afrotransact.bookings.domain.Provider;
import com.afrotransact.bookings.domain.RatingSummary;
import com.afrotransact.bookings.domain.ServiceOffering;
import com.afrotransact.bookings.port.GeoClientException;
import com.afrotransact.module.InProcessCalls;
import com.afrotransact.module.ModuleApiException;
import com.afrotransact.platform.test.InProcessKit;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

/** The anonymous service search runs the same use case as {@code GET /v1/services/search} and fails with the statuses the advice gives. */
class ServiceSearchApiAdapterTest {

    private final PublicBookingsService catalog = mock(PublicBookingsService.class);
    private final ServiceSearchApi api = new ServiceSearchApiAdapter(catalog, InProcessKit.ACTORS);

    private <T> T asAiGateway(Supplier<T> call) {
        return InProcessCalls.as(InProcessKit.caller("ai-gateway"), "bookings", call);
    }

    @Test
    void searchesWithTheAliasDefaultsAndMapsEachListing() {
        ServiceOffering service = mock(ServiceOffering.class);
        Provider provider = mock(Provider.class);
        UUID id = UUID.randomUUID();
        when(service.id()).thenReturn(id);
        when(service.name()).thenReturn("Knotless braids");
        when(service.description()).thenReturn("Waist length");
        when(service.priceMinor()).thenReturn(15000L);
        when(service.depositMinor()).thenReturn(3000L);
        when(service.currency()).thenReturn("USD");
        when(provider.displayName()).thenReturn("Ada Braids");
        when(provider.location()).thenReturn("Houston, TX");
        Instant next = Instant.parse("2026-10-25T14:00:00Z");
        when(catalog.search(any())).thenReturn(new SearchResult(null, "LOCAL",
                List.of(new Listing(service, provider, next, new RatingSummary(4.5, 12), null)), List.of(), 0, true));
        List<ServiceSearchApi.ServiceListing> out = asAiGateway(() -> api.search("braids", "77002", 6));
        assertThat(out).containsExactly(new ServiceSearchApi.ServiceListing(id.toString(), "Knotless braids", "Ada Braids",
                "Waist length", 15000, "USD", 3000, 4.5, next.toString(), "Houston, TX"));
        ArgumentCaptor<SearchQuery> query = ArgumentCaptor.forClass(SearchQuery.class);
        verify(catalog).search(query.capture());
        assertThat(query.getValue()).isEqualTo(new SearchQuery("braids", null, "77002", null, 6, null));
    }

    @Test
    void anInvalidZipIsThe422AndAnUnreachableGeoServiceThe502() {
        when(catalog.search(any())).thenThrow(new BookingsRuleViolation("bookings.invalid-zip", "bad zip"));
        assertThatThrownBy(() -> asAiGateway(() -> api.search("x", "1", 6))).isInstanceOfSatisfying(ModuleApiException.class, e -> {
            assertThat(e.status()).isEqualTo(422);
            assertThat(e.code()).isEqualTo("bookings.invalid-zip");
        });
        doThrow(new GeoClientException("down", null)).when(catalog).search(any());
        assertThatThrownBy(() -> asAiGateway(() -> api.search("x", "77002", 6))).isInstanceOfSatisfying(ModuleApiException.class, e -> {
            assertThat(e.status()).isEqualTo(502);
            assertThat(e.code()).isEqualTo("bookings.geo-unavailable");
        });
    }

    @Test
    void enteredWithoutAnInProcessCallerItFailsClosed() {
        assertThatThrownBy(() -> api.search("x", null, 6)).isInstanceOf(IllegalStateException.class);
    }
}
