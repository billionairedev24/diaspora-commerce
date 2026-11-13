package tenanttest

import (
	"context"
	"fmt"
	"net/http"
	"slices"
	"strings"

	"github.com/billionairedev24/diaspora-commerce/go/pkg/auth"
)

// Principal is one caller of a service: a token the fake verifier maps to an Actor. The zero value is anonymous.
type Principal struct {
	Name  string
	Actor auth.Actor
}

const tokenPrefix = "tt."

// Token is the bearer token of the principal ("" for anonymous).
func (p Principal) Token() string {
	if p.Name == "" {
		return ""
	}
	return tokenPrefix + p.Name
}

// IsAnonymous reports whether the principal sends no token.
func (p Principal) IsAnonymous() bool { return p.Name == "" }

// Authorization is the Authorization header value of the principal ("" for anonymous).
func (p Principal) Authorization() string {
	if p.IsAnonymous() {
		return ""
	}
	return "Bearer " + p.Token()
}

// String names the principal in test output.
func (p Principal) String() string {
	if p.IsAnonymous() {
		return "anonymous"
	}
	return p.Name
}

// World is the fixed cast of a cross-tenant test: two organisations with a member each, two shoppers, staff, a
// service and an anonymous caller.
type World struct {
	OrgA, OrgB string
	SellerA    Principal
	SellerB    Principal
	CustomerA  Principal
	CustomerB  Principal
	Staff      Principal
	Service    Principal
	Anonymous  Principal

	extra []Principal
}

// Default identities.
const (
	OrgAID      = "org_tenant_a"
	OrgBID      = "org_tenant_b"
	CustomerAID = "cust_tenant_a"
	CustomerBID = "cust_tenant_b"
)

// Option adjusts the principals a World mints.
type Option func(*worldConfig)

type worldConfig struct {
	seller, customer, staff, service []string
	serviceSubject                   string
	memberType                       string
}

// SellerPerms sets the permissions of both organisation members (what seller.owner holds for the service).
func SellerPerms(perms ...string) Option {
	return func(c *worldConfig) { c.seller = perms }
}

// CustomerPerms sets the permissions of both shoppers.
func CustomerPerms(perms ...string) Option {
	return func(c *worldConfig) { c.customer = perms }
}

// StaffPerms sets the permissions of the staff member.
func StaffPerms(perms ...string) Option {
	return func(c *worldConfig) { c.staff = perms }
}

// ServicePerms sets the permissions of the service principal.
func ServicePerms(perms ...string) Option {
	return func(c *worldConfig) { c.service = perms }
}

// ServiceSubject sets the service principal's subject (the client id of its token).
func ServiceSubject(subject string) Option {
	return func(c *worldConfig) { c.serviceSubject = subject }
}

// OrgMemberType sets the principal type of the organisation members (default SELLER_MEMBER).
func OrgMemberType(t string) Option {
	return func(c *worldConfig) { c.memberType = t }
}

// NewWorld mints the principals.
func NewWorld(opts ...Option) World {
	cfg := worldConfig{serviceSubject: "svc-test", memberType: "SELLER_MEMBER"}
	for _, o := range opts {
		o(&cfg)
	}
	mk := func(name, subject, typ, org string, perms []string) Principal {
		return Principal{Name: name, Actor: auth.Actor{Subject: subject, Type: typ, OrgID: org, Permissions: slices.Clone(perms)}}
	}
	return World{
		OrgA: OrgAID, OrgB: OrgBID,
		SellerA:   mk("seller-a", "user_seller_a", cfg.memberType, OrgAID, cfg.seller),
		SellerB:   mk("seller-b", "user_seller_b", cfg.memberType, OrgBID, cfg.seller),
		CustomerA: mk("customer-a", CustomerAID, "CUSTOMER", "", cfg.customer),
		CustomerB: mk("customer-b", CustomerBID, "CUSTOMER", "", cfg.customer),
		Staff:     mk("staff", "user_staff", "STAFF", "", cfg.staff),
		Service:   mk("service", cfg.serviceSubject, "SERVICE", "", cfg.service),
	}
}

// With adds a principal with its own identity (a second member of an organisation, a driver...).
func (w World) With(name string, a auth.Actor) (World, Principal) {
	p := Principal{Name: name, Actor: a}
	w.extra = append(slices.Clone(w.extra), p)
	return w, p
}

// All lists every minted principal except the anonymous one.
func (w World) All() []Principal {
	return append([]Principal{w.SellerA, w.SellerB, w.CustomerA, w.CustomerB, w.Staff, w.Service}, w.extra...)
}

// Verifier maps the principals' tokens to their actors and rejects every other token.
func (w World) Verifier() auth.Verifier {
	byToken := map[string]auth.Actor{}
	for _, p := range w.All() {
		byToken[p.Token()] = p.Actor
	}
	return verifierFunc(func(_ context.Context, token string) (auth.Actor, error) {
		a, ok := byToken[token]
		if !ok {
			return auth.Actor{}, fmt.Errorf("tenanttest: unknown token %q: %w", strings.TrimPrefix(token, tokenPrefix), auth.ErrUnauthenticated)
		}
		return a, nil
	})
}

// Auth is the service's real auth.Middleware over the world's verifier.
func (w World) Auth(optional bool) func(http.Handler) http.Handler {
	return auth.Middleware(w.Verifier(), optional)
}

type verifierFunc func(ctx context.Context, token string) (auth.Actor, error)

func (f verifierFunc) Verify(ctx context.Context, token string) (auth.Actor, error) {
	return f(ctx, token)
}
