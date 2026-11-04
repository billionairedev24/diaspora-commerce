package auth

import (
	"slices"
	"strings"
)

// ActingAsHeader names the context a person acts in for one request. Identity issues one token per person: once they
// belong to a seller or provider organisation it carries pt SELLER_MEMBER / PROVIDER_MEMBER and the org, and the BFF
// relays that same token from every app. The storefront and the customer app send "X-Acting-As: CUSTOMER" so the
// person shops as themselves (their own cart, orders, addresses, payment methods and bookings) whatever organisations
// they belong to; Seller Center and Provider Hub never send it, so the organisation context applies only there.
// Mirrors com.afrotransact.platform.security.ActingAsActorResolver.
const ActingAsHeader = "X-Acting-As"

// ActingAsCustomer is the only acting context: the person as a shopper.
const ActingAsCustomer = "CUSTOMER"

// actsAsCustomer are the principal types that are people who may shop. Staff, services, partner apps and AI agents
// keep their own type: a header never turns them into a shopper.
var actsAsCustomer = []string{"CUSTOMER", "SELLER_MEMBER", "PROVIDER_MEMBER", "DRIVER"}

// ActingAs returns the actor a request acts as. With actingAs "CUSTOMER" a person becomes a CUSTOMER of their own
// subject, without an organisation and without organisation or platform roles (account.* roles stay). The token's
// permissions stay: they are the person's own, and without an organisation the org-scoped ones grant nothing.
// The header can only narrow a caller to a shopper, which every account already is (account.customer), so no
// caller gains access to anything but their own shopper data. Any other value, or another principal type, returns
// the actor unchanged.
func ActingAs(a Actor, actingAs string) Actor {
	if !strings.EqualFold(strings.TrimSpace(actingAs), ActingAsCustomer) || a.Subject == "" ||
		!slices.Contains(actsAsCustomer, a.Type) {
		return a
	}
	roles := make([]string, 0, len(a.Roles))
	for _, r := range a.Roles {
		if strings.HasPrefix(r, "account.") {
			roles = append(roles, r)
		}
	}
	return Actor{Subject: a.Subject, Type: ActingAsCustomer, Roles: roles, Permissions: slices.Clone(a.Permissions)}
}
