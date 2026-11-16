package com.afrotransact.bookings.adapter.in.module;

import java.util.List;
import java.util.Optional;

import com.afrotransact.bookings.adapter.in.web.BookingsEndpointViews;
import com.afrotransact.bookings.api.UserServiceSearchApi;
import com.afrotransact.bookings.domain.BookingsRuleViolation;
import com.afrotransact.bookings.domain.BookingsStateConflict;
import com.afrotransact.bookings.port.GeoClientException;
import com.afrotransact.module.EndUserActor;
import com.afrotransact.module.ModuleApiException;
import com.afrotransact.platform.security.ActorResolver;
import com.afrotransact.platform.security.ModuleEntry;
import org.springframework.stereotype.Component;

/**
 * The in-process entry of {@link UserServiceSearchApi}: {@code GET /v1/services/search} behind the MCP shopper tool, entered AS the
 * signed-in person ({@code ModuleEntry.runAsUser}). The search is public over HTTP and applies no permission here either.
 */
@Component
class UserUserServiceSearchApiAdapter implements UserServiceSearchApi {

    private final BookingsEndpointViews views;
    private final ModuleEntry entry;

    UserUserServiceSearchApiAdapter(BookingsEndpointViews views, ActorResolver actors) {
        this.views = views;
        this.entry = new ModuleEntry(actors);
    }

    @Override
    public Object findServices(EndUserActor user, String query, String zip) {
        return entry.runAsUser(user, READ_SCOPE, () -> views.findServices(query, zip), UserUserServiceSearchApiAdapter::map);
    }

    /** The mapping {@code BookingsErrorAdvice} applies to the errors a search raises. */
    private static Optional<ModuleApiException> map(RuntimeException ex) {
        if (ex instanceof BookingsRuleViolation rule) {
            return Optional.of(new ModuleApiException(422, rule.code(), ex.getMessage(), List.of(), ex));
        }
        if (ex instanceof BookingsStateConflict state) {
            return Optional.of(new ModuleApiException(409, state.code(), ex.getMessage(), List.of(), ex));
        }
        if (ex instanceof GeoClientException) {
            return Optional.of(new ModuleApiException(502, "bookings.geo-unavailable", ex.getMessage(), List.of(), ex));
        }
        if (ex instanceof IllegalArgumentException) {
            return Optional.of(new ModuleApiException(400, "bookings.invalid-input", ex.getMessage(), List.of(), ex));
        }
        return Optional.empty();
    }
}
