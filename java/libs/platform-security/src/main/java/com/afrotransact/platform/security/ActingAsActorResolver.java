package com.afrotransact.platform.security;

import java.util.Locale;
import java.util.Optional;
import java.util.Set;
import java.util.stream.Collectors;

import org.springframework.web.context.request.RequestAttributes;
import org.springframework.web.context.request.RequestContextHolder;
import org.springframework.web.context.request.ServletRequestAttributes;

/**
 * Applies the context a person acts in for one request ({@value #HEADER}).
 *
 * <p>Identity issues one token per person. Once they belong to a seller or provider organisation it carries
 * {@code pt} SELLER_MEMBER / PROVIDER_MEMBER and the {@code org}, and gateway-bff relays that same token from every
 * app. The storefront and the customer app send {@code X-Acting-As: CUSTOMER} (gateway-bff on every storefront route,
 * orders when it relays the shopper's token to cart and identity at checkout), so the person shops as themselves:
 * their own cart, orders, addresses, payment methods and bookings, whatever organisations they belong to. Seller
 * Center and Provider Hub never send it, so the organisation context applies only there.
 *
 * <p>Acting as a customer keeps the subject, drops the organisation and every role but the {@code account.*} ones,
 * and keeps the token's permissions (the person's own; without an organisation the org-scoped ones grant nothing).
 * Only people (customers, organisation members, drivers) switch; staff, services, partner apps and AI agents keep
 * their type. The header can therefore only narrow a caller to a shopper, which every account already is
 * ({@code account.customer}), never widen what it may do. Mirrors go/pkg/auth ActingAs.
 */
public class ActingAsActorResolver implements ActorResolver {

    /** Request header naming the acting context. */
    public static final String HEADER = "X-Acting-As";
    /** The only acting context: the person as a shopper. */
    public static final String CUSTOMER = "CUSTOMER";

    private static final Set<PrincipalType> PEOPLE = Set.of(PrincipalType.CUSTOMER, PrincipalType.SELLER_MEMBER,
            PrincipalType.PROVIDER_MEMBER, PrincipalType.DRIVER);
    private static final String ACCOUNT_ROLE_PREFIX = "account.";

    private final ActorResolver delegate;

    public ActingAsActorResolver(ActorResolver delegate) {
        this.delegate = delegate;
    }

    @Override
    public Actor current() {
        Actor actor = delegate.current();
        RequestAttributes attrs = RequestContextHolder.getRequestAttributes();
        if (attrs instanceof ServletRequestAttributes servlet) {
            return actingAs(actor, servlet.getRequest().getHeader(HEADER));
        }
        return actor;
    }

    /** The actor for an acting context (null, blank or unknown: unchanged). */
    public static Actor actingAs(Actor actor, String context) {
        if (context == null || !CUSTOMER.equals(context.trim().toUpperCase(Locale.ROOT))
                || !PEOPLE.contains(actor.type()) || actor.subject() == null || actor.subject().isBlank()) {
            return actor;
        }
        Set<String> roles = actor.roles().stream()
                .filter(r -> r.startsWith(ACCOUNT_ROLE_PREFIX))
                .collect(Collectors.toSet());
        return new Actor(actor.subject(), PrincipalType.CUSTOMER, Optional.empty(), roles, actor.permissions());
    }
}
