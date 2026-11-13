package testkit

import (
	"bytes"
	"encoding/base64"
	"encoding/json"
	"io"
	"net/http"
	"net/http/httptest"
	"net/url"
	"slices"
	"strings"
	"sync"
	"testing"
	"time"

	"github.com/golang-jwt/jwt/v5"
)

// Cross-tenant test kit. A TenantWorld is the BFF wired against the fake upstreams, with two seller organisations
// (A and B), two service-provider organisations, two customers and a recorder in front of every upstream that notes
// what the BFF forwarded: the bearer (user token or service token, with its sub and org claim), the path, query,
// body and headers. Tests perform a request as tenant A that carries tenant B's ids and spoofed org fields, then ask
// CheckForwarded whether the upstreams only ever saw tenant A.

// Tenant is a signed-in actor of the world. Token is a signed access token as identity would issue it (sub, pt, org,
// roles); web-session tests sign in instead.
type Tenant struct {
	Name  string
	Sub   string
	Org   string // the org claim (and active organisation); empty for customers
	PT    string // CUSTOMER | SELLER_MEMBER | PROVIDER_MEMBER
	Role  string
	Token string
}

// Fixed identities of the world. Ids are UUIDs because several routes validate the shape before calling upstream.
const (
	TenantSellerOrgA   = "aaaaaaaa-0000-4000-8000-00000000000a"
	TenantSellerOrgB   = "bbbbbbbb-0000-4000-8000-00000000000b"
	TenantProviderOrgA = "aaaaaaaa-0000-4000-8000-0000000000a1"
	TenantProviderOrgB = "bbbbbbbb-0000-4000-8000-0000000000b1"
	// TenantResourceB is a resource id (order, product, address, asset, …) owned by tenant B that tenant A names in a path.
	TenantResourceB = "bbbbbbbb-1111-4111-8111-bbbbbbbbbbbb"
)

// Request is one upstream request the BFF made.
type Request struct {
	Method string
	Path   string
	Query  string
	Body   string
	Header http.Header
	Auth   Bearer
}

// Bearer describes the credential of an upstream request.
type Bearer struct {
	// Kind is "none", "user" (a user's access token), "service" (client-credentials token: the testkit identity names it
	// svc-<scope>) or "other".
	Kind  string
	Sub   string
	Org   string
	PT    string
	Scope string
}

// Everything returns the request as one searchable string (path, query, body and header values except Authorization).
func (r Request) Everything() string {
	var b strings.Builder
	b.WriteString(r.Path + "?" + r.Query + " " + r.Body)
	for k, vs := range r.Header {
		if strings.EqualFold(k, "Authorization") {
			continue
		}
		b.WriteString(" " + k + "=" + strings.Join(vs, ","))
	}
	return b.String()
}

// TenantWorld is the cross-tenant fixture.
type TenantWorld struct {
	U   *Upstreams
	BFF *BFF

	SellerA, SellerB     Tenant
	ProviderA, ProviderB Tenant
	CustomerA, CustomerB Tenant

	mu      sync.Mutex
	recs    []Request
	members map[string]*membership
}

type membership struct {
	orgs   []MemberOrg
	active string
}

// NewTenantWorld builds the world and cleans it up with t.
func NewTenantWorld(t testing.TB) *TenantWorld {
	t.Helper()
	u := NewUpstreams(Options{Content: true, Payments: true, Promise: true, Offers: true, Notifications: true, SellerPending: true})
	t.Cleanup(u.Close)
	u.AccessTTL = 24 * time.Hour
	w := &TenantWorld{U: u, members: map[string]*membership{}}
	w.SellerA = w.add("seller-a", "user-seller-a", TenantSellerOrgA, "SELLER", "seller.owner", "SELLER_MEMBER")
	w.SellerB = w.add("seller-b", "user-seller-b", TenantSellerOrgB, "SELLER", "seller.owner", "SELLER_MEMBER")
	w.ProviderA = w.add("provider-a", "user-provider-a", TenantProviderOrgA, "PROVIDER", "provider.owner", "PROVIDER_MEMBER")
	w.ProviderB = w.add("provider-b", "user-provider-b", TenantProviderOrgB, "PROVIDER", "provider.owner", "PROVIDER_MEMBER")
	w.CustomerA = w.add("customer-a", "user-customer-a", "", "", "account.customer", "CUSTOMER")
	w.CustomerB = w.add("customer-b", "user-customer-b", "", "", "account.customer", "CUSTOMER")
	// The shared fake knows every organisation by id (seller service, offers, …); who belongs to which is the world's own.
	u.MemberOrgs = []MemberOrg{
		{ID: TenantSellerOrgA, Kind: "SELLER", Name: "Org A", Status: "APPROVED", Role: "seller.owner"},
		{ID: TenantSellerOrgB, Kind: "SELLER", Name: "Org B", Status: "APPROVED", Role: "seller.owner"},
		{ID: TenantProviderOrgA, Kind: "PROVIDER", Name: "Provider A", Status: "APPROVED", Role: "provider.owner"},
		{ID: TenantProviderOrgB, Kind: "PROVIDER", Name: "Provider B", Status: "APPROVED", Role: "provider.owner"},
	}
	u.ActiveOrgID = TenantSellerOrgA
	u.AccessDeniedMatrix = true
	orig := u.Server.Config.Handler
	u.Server.Config.Handler = http.HandlerFunc(func(rw http.ResponseWriter, r *http.Request) {
		w.record(r)
		if w.identityOverride(rw, r) {
			return
		}
		orig.ServeHTTP(rw, r)
	})
	w.BFF = NewBFF(u, Config{})
	return w
}

func (w *TenantWorld) add(name, sub, org, kind, role, pt string) Tenant {
	claims := jwt.MapClaims{"sub": sub, "pt": pt, "roles": []string{"account.customer", role}}
	if org != "" {
		claims["org"] = org
	}
	tn := Tenant{Name: name, Sub: sub, Org: org, PT: pt, Role: role}
	tn.Token = w.U.Token(claims)
	m := &membership{}
	if org != "" {
		m.orgs = []MemberOrg{{ID: org, Kind: kind, Name: "Org of " + name, Status: "APPROVED", Role: role}}
		m.active = org
	}
	w.members[sub] = m
	return tn
}

// Join makes sub a member of org (kind SELLER | PROVIDER) without changing the active organisation.
func (w *TenantWorld) Join(sub string, o MemberOrg) {
	w.mu.Lock()
	defer w.mu.Unlock()
	m := w.members[sub]
	m.orgs = append(m.orgs, o)
}

// Leave removes sub's membership of org, like identity does when a member is removed from the team. The access token
// the person holds keeps its org claim (identity cannot recall it), which is the staleness window.
func (w *TenantWorld) Leave(sub, org string) {
	w.mu.Lock()
	defer w.mu.Unlock()
	m := w.members[sub]
	m.orgs = slices.DeleteFunc(m.orgs, func(o MemberOrg) bool { return o.ID == org })
	if m.active == org {
		m.active = ""
		if len(m.orgs) > 0 {
			m.active = m.orgs[0].ID
		}
	}
}

// ActiveOrgOf is identity's stored active organisation of sub.
func (w *TenantWorld) ActiveOrgOf(sub string) string {
	w.mu.Lock()
	defer w.mu.Unlock()
	return w.members[sub].active
}

func (w *TenantWorld) subjectOfRequest(r *http.Request) (string, bool) {
	b := parseBearer(r.Header.Get("Authorization"))
	if b.Kind != "user" {
		return "", false
	}
	_, ok := w.members[b.Sub]
	return b.Sub, ok
}

// identityOverride answers identity's per-person routes from the world's own membership (the shared fake keeps one
// global membership list): GET /v1/me and PUT /v1/me/active-org.
func (w *TenantWorld) identityOverride(rw http.ResponseWriter, r *http.Request) bool {
	if r.URL.Path != "/v1/me" && r.URL.Path != "/v1/me/active-org" {
		return false
	}
	w.mu.Lock()
	defer w.mu.Unlock()
	sub, ok := w.subjectOfRequest(r)
	if !ok {
		return false
	}
	m := w.members[sub]
	orgs := []map[string]any{}
	for _, o := range m.orgs {
		orgs = append(orgs, map[string]any{"id": o.ID, "kind": o.Kind, "name": o.Name, "status": o.Status, "role": o.Role})
	}
	pt, role := "CUSTOMER", "account.customer"
	for _, t := range []Tenant{w.SellerA, w.SellerB, w.ProviderA, w.ProviderB, w.CustomerA, w.CustomerB} {
		if t.Sub == sub {
			pt, role = t.PT, t.Role
		}
	}
	if r.Method == http.MethodPut {
		var b struct {
			OrgID string `json:"orgId"`
		}
		_ = json.NewDecoder(r.Body).Decode(&b)
		if !slices.ContainsFunc(m.orgs, func(o MemberOrg) bool { return o.ID == b.OrgID }) {
			writeJSON(rw, http.StatusForbidden, map[string]any{"type": "about:blank", "title": "Not a member", "status": 403, "code": "identity.not-a-member"})
			return true
		}
		m.active = b.OrgID
	}
	writeJSON(rw, http.StatusOK, map[string]any{"id": sub, "firstName": "Tess", "lastName": "Tenant", "email": sub + "@example.com",
		"roles": []string{"account.customer", role}, "landing": "shop", "principalType": pt, "orgId": m.active, "orgs": orgs,
		"phone": "+17135550142", "phoneVerified": true})
	return true
}

func parseBearer(h string) Bearer {
	tok, ok := strings.CutPrefix(h, "Bearer ")
	if !ok || strings.TrimSpace(tok) == "" {
		return Bearer{Kind: "none"}
	}
	if scope, ok := strings.CutPrefix(tok, "svc-"); ok {
		return Bearer{Kind: "service", Scope: scope}
	}
	parts := strings.Split(tok, ".")
	if len(parts) != 3 {
		return Bearer{Kind: "other"}
	}
	raw, err := base64.RawURLEncoding.DecodeString(parts[1])
	if err != nil {
		return Bearer{Kind: "other"}
	}
	var c struct {
		Sub string `json:"sub"`
		Org string `json:"org"`
		PT  string `json:"pt"`
	}
	if json.Unmarshal(raw, &c) != nil {
		return Bearer{Kind: "other"}
	}
	return Bearer{Kind: "user", Sub: c.Sub, Org: c.Org, PT: c.PT}
}

// ParseBearerOrg is the org claim of an access token ("" when absent), for tests that inspect a session's tokens.
func ParseBearerOrg(token string) string { return parseBearer("Bearer " + token).Org }

func (w *TenantWorld) record(r *http.Request) {
	if r.URL.Path == "/oauth2/jwks" {
		return
	}
	var body []byte
	if r.Body != nil {
		body, _ = io.ReadAll(r.Body)
		r.Body = io.NopCloser(bytes.NewReader(body))
	}
	w.mu.Lock()
	w.recs = append(w.recs, Request{Method: r.Method, Path: r.URL.Path, Query: r.URL.RawQuery, Body: string(body), Header: r.Header.Clone(),
		Auth: parseBearer(r.Header.Get("Authorization"))})
	w.mu.Unlock()
}

// Mark is a position in the recording; Since returns everything the upstreams received after it.
func (w *TenantWorld) Mark() int {
	w.mu.Lock()
	defer w.mu.Unlock()
	return len(w.recs)
}

// Since returns the upstream requests recorded after mark.
func (w *TenantWorld) Since(mark int) []Request {
	w.mu.Lock()
	defer w.mu.Unlock()
	return slices.Clone(w.recs[mark:])
}

// Call is one request of a tenant to the BFF.
type Call struct {
	Method  string
	Path    string
	Query   url.Values
	Body    any // string (raw), []byte, or anything JSON-encodable
	Headers map[string]string
}

// Do performs the call as tenant t (bearer-token caller, like the apps) and returns the status and body. Origin is
// set so the CSRF check passes the way a browser client would.
func (w *TenantWorld) Do(t Tenant, c Call) (int, []byte) {
	target := c.Path
	if len(c.Query) > 0 {
		sep := "?"
		if strings.Contains(target, "?") {
			sep = "&"
		}
		target += sep + c.Query.Encode()
	}
	var rd io.Reader
	switch b := c.Body.(type) {
	case nil:
	case string:
		rd = strings.NewReader(b)
	case []byte:
		rd = bytes.NewReader(b)
	default:
		raw, _ := json.Marshal(b)
		rd = bytes.NewReader(raw)
	}
	req := httptest.NewRequest(c.Method, "http://bff.internal"+target, rd)
	if rd != nil {
		req.Header.Set("Content-Type", "application/json")
	}
	req.Header.Set("Accept", "application/json")
	if t.Token != "" {
		req.Header.Set("Authorization", "Bearer "+t.Token)
	}
	for k, v := range c.Headers {
		req.Header.Set(k, v)
	}
	rec := httptest.NewRecorder()
	w.BFF.Handler.ServeHTTP(rec, req)
	return rec.Code, rec.Body.Bytes()
}

// ---------------------------------------------------------------- the oracle

// Foreign is what tenant A must not be able to make the BFF send upstream.
type Foreign struct {
	// Identity are tenant B's org and user ids, planted in query, body and headers. They may appear nowhere in what
	// the BFF forwards (path included, unless listed in PathOK).
	Identity []string
	// PathOK are values the client legitimately selects in a path (a resource id or, where the route names it, an org
	// or user id). They may only ride on a request that carries tenant A's own user token, so that the owning service
	// makes the decision.
	PathOK []string
}

// SpoofHeaders are the client headers a BFF must never turn into an org or user: they are sent on every probe.
var SpoofHeaders = []string{"X-Org-Id", "X-Org", "X-Owner-Org", "X-Seller-Org-Id", "X-Organization-Id", "X-User-Id", "X-Customer-Id",
	"X-Forwarded-Org", "X-Forwarded-User", "X-Forwarded-Sub", "X-Tenant-Id"}

// CheckForwarded returns the violations in what the upstreams received while tenant "as" made one probe request.
// It is the whole isolation oracle:
//
//   - a user token must be tenant as's own (same sub and org claim): the BFF never swaps or mints identities;
//   - tenant B's org/user ids must not appear in the query, body or headers of any forwarded request, nor in a path
//     that is not a client-selected resource;
//   - a service token must not carry anything the client chose (confused deputy): neither Identity nor PathOK values;
//   - none of the spoofed headers may be forwarded, and X-Acting-As is never what the client sent.
func CheckForwarded(as Tenant, f Foreign, reqs []Request) []string {
	var out []string
	for _, r := range reqs {
		where := r.Method + " " + r.Path
		switch r.Auth.Kind {
		case "user":
			if r.Auth.Sub != as.Sub || r.Auth.Org != as.Org {
				out = append(out, where+": forwarded another identity's token ("+r.Auth.Sub+"/"+r.Auth.Org+")")
			}
		case "service":
			for _, v := range append(slices.Clone(f.Identity), f.PathOK...) {
				if strings.Contains(r.Everything(), v) {
					out = append(out, where+": service token ("+r.Auth.Scope+") request carries client-chosen "+v)
				}
			}
		case "other":
			out = append(out, where+": unrecognised credential")
		}
		rest := r.Query + " " + r.Body
		for k, vs := range r.Header {
			if strings.EqualFold(k, "Authorization") {
				continue
			}
			rest += " " + k + "=" + strings.Join(vs, ",")
			if slices.ContainsFunc(SpoofHeaders, func(h string) bool { return strings.EqualFold(h, k) }) {
				out = append(out, where+": spoofed header "+k+" forwarded")
			}
			if strings.EqualFold(k, "X-Acting-As") && slices.Contains(vs, "STAFF") {
				out = append(out, where+": client's X-Acting-As forwarded")
			}
		}
		for _, v := range f.Identity {
			if strings.Contains(rest, v) {
				out = append(out, where+": tenant B id "+v+" forwarded in query/body/header")
			}
			if strings.Contains(r.Path, v) && !slices.Contains(f.PathOK, v) {
				out = append(out, where+": tenant B id "+v+" forwarded in path")
			}
		}
	}
	return out
}
