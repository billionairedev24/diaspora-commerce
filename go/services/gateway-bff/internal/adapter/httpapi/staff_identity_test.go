package httpapi_test

import (
	"bytes"
	"context"
	"encoding/json"
	"fmt"
	"io"
	"net/http"
	"net/http/httptest"
	"strings"
	"sync"
	"testing"

	"github.com/getkin/kin-openapi/openapi3"
	"github.com/getkin/kin-openapi/openapi3filter"
	"github.com/getkin/kin-openapi/routers/gorillamux"

	"github.com/billionairedev24/diaspora-commerce/go/services/gateway-bff/internal/adapter/httpapi"
	"github.com/billionairedev24/diaspora-commerce/go/services/gateway-bff/internal/app"
	"github.com/billionairedev24/diaspora-commerce/go/services/gateway-bff/internal/domain"
)

// fakeIdentityAdmin is identity's /v1/admin/identity/** (S291): clients with one-time secrets, sessions, signing keys, the
// token inspector, the OIDC documents and the authentication settings. It answers like identity: 422 with field errors
// for a missing reason, and it never returns a secret except from the create and rotate calls.
type fakeIdentityAdmin struct {
	mu       sync.Mutex
	clients  []map[string]any
	sessions []map[string]any
	keys     []map[string]any
	settings map[string]any
	// policyOverrides are the stored sign-out overrides by audience.
	policyOverrides map[string]map[string]any
	// seen collects every request body, to check that tokens and secrets travel only where they must.
	seen []string
	// stepUps are the X-Step-Up values the dangerous calls arrived with ("" when the header was missing).
	stepUps []string
}

const (
	createdSecret = "created-secret-SHOWN-ONCE-0001"
	rotatedSecret = "rotated-secret-SHOWN-ONCE-0002"
	inspectedJWT  = "eyJhbGciOiJSUzI1NiIsImtpZCI6ImsxIn0.eyJzdWIiOiJ1c2VyLTEiLCJpc3MiOiJodHRwczovL2lkLnRlc3QifQ.c2lnbmF0dXJl"
	// The staff member's own password and the step-up proof identity issues for it (S291).
	staffPassword = "Correct-Horse-Battery-1"
	stepUpProof   = "step-up-proof-SHOWN-ONCE-0003"
)

func (f *fakeIdentityAdmin) init() {
	if f.clients != nil {
		return
	}
	f.clients = []map[string]any{
		{"clientId": "gateway-bff", "name": "Gateway BFF", "type": "confidential", "grantTypes": []string{"authorization_code", "client_credentials", "refresh_token"},
			"scopes": []string{"openid", "profile"}, "redirectUris": []string{"https://bff.example.com/callback"}, "postLogoutRedirectUris": []string{},
			"requirePkce": true, "requireConsent": false, "publicRefreshTokens": false, "accessTokenTtlMinutes": 5, "refreshTokenTtlMinutes": 43200,
			"enabled": true, "platformClient": true, "issuedAt": "2026-01-01T00:00:00Z"},
		{"clientId": "customer-app", "name": "Customer app", "type": "public", "grantTypes": []string{"authorization_code", "refresh_token"},
			"scopes": []string{"openid"}, "redirectUris": []string{"afrotransact://auth"}, "postLogoutRedirectUris": []string{},
			"requirePkce": true, "requireConsent": false, "publicRefreshTokens": true, "accessTokenTtlMinutes": 5, "refreshTokenTtlMinutes": 43200,
			"enabled": true, "platformClient": false, "issuedAt": "2026-01-01T00:00:00Z"},
	}
	f.sessions = []map[string]any{
		{"id": "auth-1", "userId": "11111111-1111-1111-1111-111111111111", "userName": "Ada Obi", "userEmail": "ada@example.com", "clientId": "customer-app",
			"clientName": "Customer app", "ipAddress": "203.0.113.9",
			"userAgent": "Mozilla/5.0 (Macintosh; Intel Mac OS X 10_15_7) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/124.0.0.0 Safari/537.36", "signedInAt": "2026-10-24T09:00:00Z", "lastUsedAt": "2026-10-24T09:30:00Z",
			"expiresAt": "2026-11-23T09:00:00Z", "refreshable": true},
		{"id": "auth-2", "userId": "svc-billing", "clientId": "billing-svc", "clientName": "Billing service", "signedInAt": "2026-10-24T08:00:00Z",
			"lastUsedAt": "2026-10-24T08:00:00Z", "expiresAt": "2026-10-24T08:05:00Z", "refreshable": false},
	}
	f.keys = []map[string]any{
		{"kid": "k20261001T000000-ab12", "algorithm": "RS256", "status": "ACTIVE", "createdAt": "2026-10-01T00:00:00Z"},
		{"kid": "k20260701T000000-cd34", "algorithm": "RS256", "status": "RETIRING", "createdAt": "2026-07-01T00:00:00Z", "rotatedAt": "2026-10-01T00:00:00Z",
			"retireAfter": "2026-10-01T00:07:00Z"},
	}
	f.settings = map[string]any{"overrides": map[string]any{}}
	f.policyOverrides = map[string]map[string]any{}
}

var policyDefaults = map[string]map[string]any{
	"customer-web": {"idleTimeoutMinutes": 43200, "absoluteTimeoutMinutes": 129600, "warnSeconds": 0},
	"customer-app": {"idleTimeoutMinutes": 43200, "absoluteTimeoutMinutes": 129600, "warnSeconds": 0},
	"seller":       {"idleTimeoutMinutes": 30, "absoluteTimeoutMinutes": 720, "warnSeconds": 60},
	"staff":        {"idleTimeoutMinutes": 15, "absoluteTimeoutMinutes": 480, "warnSeconds": 60},
	"driver":       {"idleTimeoutMinutes": 840, "absoluteTimeoutMinutes": 840, "warnSeconds": 0},
}

var policyLabels = map[string]string{"customer-web": "Customers on the web", "customer-app": "Customer mobile app",
	"seller": "Seller Center (sellers and providers)", "staff": "Staff console", "driver": "Driver app"}

// policyEntry is identity's SessionPolicyEntry for one audience with the stored overrides applied.
func (f *fakeIdentityAdmin) policyEntry(audience string) map[string]any {
	effective := map[string]any{}
	for k, v := range policyDefaults[audience] {
		effective[k] = v
	}
	overrides := map[string]any{"idleTimeoutMinutes": nil, "absoluteTimeoutMinutes": nil, "warnSeconds": nil}
	for k, v := range f.policyOverrides[audience] {
		overrides[k], effective[k] = v, v
	}
	return map[string]any{"audience": audience, "label": policyLabels[audience], "effective": effective, "overrides": overrides, "defaults": policyDefaults[audience]}
}

func defaultsOf() map[string]any {
	return map[string]any{"passwordMinLength": 12, "mfaRequiredAccount": false, "mfaRequiredSeller": false, "mfaRequiredProvider": false,
		"sessionIdleTimeoutMinutes": 30, "sessionAbsoluteTimeoutMinutes": 1440, "accessTokenTtlCapMinutes": nil, "refreshTokenTtlCapMinutes": nil}
}

func (f *fakeIdentityAdmin) settingsView() map[string]any {
	effective := defaultsOf()
	for k, v := range f.settings["overrides"].(map[string]any) {
		if v != nil {
			effective[k] = v
		}
	}
	return map[string]any{"effective": effective, "overrides": f.settings["overrides"], "defaults": defaultsOf()}
}

func invalidReason(w http.ResponseWriter) {
	w.Header().Set("Content-Type", "application/problem+json")
	w.WriteHeader(422)
	_ = json.NewEncoder(w).Encode(map[string]any{"type": "about:blank", "title": "Identity rule violated", "status": 422, "code": "identity.reason-required",
		"fieldErrors": map[string]string{"reason": "Use 10 to 500 characters"}})
}

func (f *fakeIdentityAdmin) find(list []map[string]any, key, id string) map[string]any {
	for _, it := range list {
		if it[key] == id {
			return it
		}
	}
	return nil
}

func (f *fakeIdentityAdmin) serve(w http.ResponseWriter, r *http.Request, p string, body map[string]any) {
	f.mu.Lock()
	defer f.mu.Unlock()
	f.init()
	raw, _ := json.Marshal(body)
	f.seen = append(f.seen, r.Method+" "+p+" "+string(raw))
	needsReason := r.Method != http.MethodGet && !strings.HasSuffix(p, "/token-inspector") && !strings.HasSuffix(p, "/step-up")
	if reason, _ := body["reason"].(string); needsReason && len(strings.TrimSpace(reason)) < 10 {
		invalidReason(w)
		return
	}
	rest := strings.TrimPrefix(p, "/v1/admin/identity/")
	parts := strings.Split(rest, "/")
	if rest == "step-up" {
		if body["password"] != staffPassword {
			w.Header().Set("Content-Type", "application/problem+json")
			w.WriteHeader(422)
			_ = json.NewEncoder(w).Encode(map[string]any{"status": 422, "code": "identity.step-up-failed", "detail": "That password isn't right",
				"fieldErrors": map[string]string{"password": "That password isn't right"}})
			return
		}
		writeJ(w, 200, map[string]any{"proof": stepUpProof, "expiresAt": "2026-10-24T10:05:00Z"})
		return
	}
	// The dangerous actions need the proof (or a sign-in from the last five minutes, which this fake never has).
	if dangerous := strings.HasSuffix(rest, "/disable") || strings.HasPrefix(rest, "session-policy/") || strings.HasSuffix(rest, "/sessions/revoke") || rest == "signing-keys/rotate" ||
		(rest == "settings" && r.Method == http.MethodPut); dangerous {
		f.stepUps = append(f.stepUps, r.Header.Get("X-Step-Up"))
		if r.Header.Get("X-Step-Up") != stepUpProof {
			w.Header().Set("Content-Type", "application/problem+json")
			w.WriteHeader(403)
			_ = json.NewEncoder(w).Encode(map[string]any{"status": 403, "code": "identity.step-up-required", "detail": "Confirm your password to continue"})
			return
		}
	}
	switch {
	case rest == "clients" && r.Method == http.MethodGet:
		out := make([]any, 0, len(f.clients))
		for _, c := range f.clients {
			out = append(out, c)
		}
		writeJ(w, 200, out)
	case rest == "clients" && r.Method == http.MethodPost:
		c := map[string]any{"clientId": body["clientId"], "name": body["name"], "type": body["type"], "grantTypes": body["grantTypes"], "scopes": body["scopes"],
			"redirectUris": body["redirectUris"], "postLogoutRedirectUris": body["postLogoutRedirectUris"], "requirePkce": body["type"] == "public",
			"requireConsent": body["requireConsent"], "publicRefreshTokens": false, "accessTokenTtlMinutes": body["accessTokenTtlMinutes"],
			"refreshTokenTtlMinutes": body["refreshTokenTtlMinutes"], "enabled": true, "platformClient": false, "issuedAt": "2026-10-24T10:00:00Z"}
		f.clients = append(f.clients, c)
		resp := map[string]any{"client": c}
		if body["type"] == "confidential" {
			resp["secret"] = createdSecret
		}
		writeJ(w, 201, resp)
	case len(parts) == 2 && parts[0] == "clients" && r.Method == http.MethodGet:
		if c := f.find(f.clients, "clientId", parts[1]); c != nil {
			writeJ(w, 200, c)
			return
		}
		problemJ(w, 404, "identity.client-not-found")
	case len(parts) == 2 && parts[0] == "clients" && r.Method == http.MethodPut:
		c := f.find(f.clients, "clientId", parts[1])
		for _, k := range []string{"name", "grantTypes", "scopes", "redirectUris", "postLogoutRedirectUris", "requireConsent", "accessTokenTtlMinutes", "refreshTokenTtlMinutes"} {
			c[k] = body[k]
		}
		writeJ(w, 200, c)
	case len(parts) == 3 && parts[0] == "clients" && parts[2] == "disable":
		c := f.find(f.clients, "clientId", parts[1])
		if c["platformClient"] == true {
			w.Header().Set("Content-Type", "application/problem+json")
			w.WriteHeader(422)
			_ = json.NewEncoder(w).Encode(map[string]any{"status": 422, "code": "identity.client-protected", "detail": "locked out"})
			return
		}
		c["enabled"] = false
		writeJ(w, 200, c)
	case len(parts) == 3 && parts[0] == "clients" && parts[2] == "enable":
		c := f.find(f.clients, "clientId", parts[1])
		c["enabled"] = true
		writeJ(w, 200, c)
	case len(parts) == 3 && parts[0] == "clients" && parts[2] == "rotate-secret":
		c := f.find(f.clients, "clientId", parts[1])
		c["secretRotatedAt"], c["previousSecretExpiresAt"] = "2026-10-24T10:00:00Z", "2026-10-25T10:00:00Z"
		writeJ(w, 200, map[string]any{"client": c, "secret": rotatedSecret})
	case len(parts) == 4 && parts[0] == "clients" && parts[2] == "sessions":
		writeJ(w, 200, map[string]any{"sessions": 2, "browserSessions": 0})
	case rest == "sessions" && r.Method == http.MethodGet:
		items := make([]any, 0, len(f.sessions))
		for _, s := range f.sessions {
			items = append(items, s)
		}
		writeJ(w, 200, map[string]any{"items": items, "page": 0, "size": 25, "total": len(items)})
	case len(parts) == 2 && parts[0] == "sessions" && r.Method == http.MethodGet:
		if s := f.find(f.sessions, "id", parts[1]); s != nil {
			writeJ(w, 200, s)
			return
		}
		problemJ(w, 404, "identity.session-not-found")
	case len(parts) == 3 && parts[0] == "sessions" && parts[2] == "revoke":
		w.WriteHeader(http.StatusNoContent)
	case len(parts) == 4 && parts[0] == "users" && parts[2] == "sessions":
		writeJ(w, 200, map[string]any{"sessions": 1, "browserSessions": 1})
	case rest == "signing-keys" && r.Method == http.MethodGet:
		out := make([]any, 0, len(f.keys))
		for _, k := range f.keys {
			out = append(out, k)
		}
		writeJ(w, 200, out)
	case rest == "signing-keys/rotate":
		f.keys[0]["status"], f.keys[0]["rotatedAt"], f.keys[0]["retireAfter"] = "RETIRING", "2026-10-24T10:00:00Z", "2026-10-24T10:07:00Z"
		k := map[string]any{"kid": "k20261024T100000-ef56", "algorithm": "RS256", "status": "ACTIVE", "createdAt": "2026-10-24T10:00:00Z"}
		f.keys = append([]map[string]any{k}, f.keys...)
		writeJ(w, 201, k)
	case rest == "token-inspector":
		tok, _ := body["token"].(string)
		if tok == inspectedJWT {
			writeJ(w, 200, map[string]any{"wellFormed": true, "header": map[string]any{"alg": "RS256", "kid": "k1"}, "claims": map[string]any{"sub": "user-1", "iss": "https://id.test"},
				"scopes": []string{"openid"}, "keyId": "k1", "issuedAt": nil, "expiresAt": "2026-10-24T11:00:00Z", "expired": false, "signatureValid": true,
				"issuerValid": true, "valid": true, "problems": []string{}})
			return
		}
		writeJ(w, 200, map[string]any{"wellFormed": false, "signatureValid": false, "issuerValid": false, "valid": false,
			"problems": []string{"This is not a signed JWT"}, "scopes": []string{}, "header": map[string]any{}, "claims": map[string]any{}})
	case rest == "discovery":
		writeJ(w, 200, map[string]any{"discovery": map[string]any{"issuer": "https://id.test", "jwks_uri": "https://id.test/oauth2/jwks"},
			"jwks": map[string]any{"keys": []any{map[string]any{"kty": "RSA", "kid": "k1", "n": "abc", "e": "AQAB"}}}})
	case rest == "session-policy" && r.Method == http.MethodGet:
		out := make([]any, 0, len(policyDefaults))
		for _, a := range []string{"customer-web", "customer-app", "seller", "staff", "driver"} {
			out = append(out, f.policyEntry(a))
		}
		writeJ(w, 200, out)
	case len(parts) == 2 && parts[0] == "session-policy" && r.Method == http.MethodPut:
		if _, known := policyDefaults[parts[1]]; !known {
			problemJ(w, 404, "identity.unknown-audience")
			return
		}
		overrides, _ := body["overrides"].(map[string]any)
		idle, absolute := policyDefaults[parts[1]]["idleTimeoutMinutes"], policyDefaults[parts[1]]["absoluteTimeoutMinutes"]
		if v, ok := overrides["idleTimeoutMinutes"]; ok {
			idle = v
		}
		if v, ok := overrides["absoluteTimeoutMinutes"]; ok {
			absolute = v
		}
		if idle.(float64) > absolute.(float64) {
			w.Header().Set("Content-Type", "application/problem+json")
			w.WriteHeader(422)
			_ = json.NewEncoder(w).Encode(map[string]any{"status": 422, "code": "identity.invalid-session-policy",
				"fieldErrors": map[string]string{"idleTimeoutMinutes": "The idle timeout cannot be longer than the session length"}})
			return
		}
		stored := map[string]any{}
		for k, v := range overrides {
			if v != nil && fmt.Sprint(v) != fmt.Sprint(policyDefaults[parts[1]][k]) {
				stored[k] = v
			}
		}
		f.policyOverrides[parts[1]] = stored
		writeJ(w, 200, f.policyEntry(parts[1]))
	case rest == "settings" && r.Method == http.MethodGet:
		writeJ(w, 200, f.settingsView())
	case rest == "settings" && r.Method == http.MethodPut:
		overrides, _ := body["overrides"].(map[string]any)
		if n, ok := overrides["passwordMinLength"].(float64); ok && n < 8 {
			w.Header().Set("Content-Type", "application/problem+json")
			w.WriteHeader(422)
			_ = json.NewEncoder(w).Encode(map[string]any{"status": 422, "code": "identity.invalid-auth-settings", "fieldErrors": map[string]string{"passwordMinLength": "Between 8 and 64 characters"}})
			return
		}
		f.settings["overrides"] = overrides
		writeJ(w, 200, f.settingsView())
	default:
		http.NotFound(w, r)
	}
}

func (f *fakeIdentityAdmin) bodies() string {
	f.mu.Lock()
	defer f.mu.Unlock()
	return strings.Join(f.seen, "\n")
}

// TestStaffIdentityAdminConformance drives every new operation of Identity & access (S291) through the real handlers
// against the fake identity and validates each answer against gateway-bff-staff.yaml.
func TestStaffIdentityAdminConformance(t *testing.T) {
	doc, err := openapi3.NewLoader().LoadFromFile(staffContractPath)
	if err != nil {
		t.Fatal(err)
	}
	doc.Servers = openapi3.Servers{{URL: "http://bff.internal"}}
	router, err := gorillamux.NewRouter(doc)
	if err != nil {
		t.Fatal(err)
	}
	f := &fakePlatform{payoutSt: "REQUESTED"}
	raw := staffBFFWith(t, f, func(d app.StaffDeps, staff *httpapi.StaffServices) { staff.Identity = app.NewStaffIdentityService(d) })
	validated := map[string]int{}
	do := func(token, method, path string, body any, want int) map[string]any {
		t.Helper()
		rec := raw(token, method, path, body)
		if rec.Code != want {
			t.Fatalf("%s %s = %d, want %d: %s", method, path, rec.Code, want, rec.Body.String())
		}
		req := httptest.NewRequestWithContext(context.Background(), method, "http://bff.internal"+path, nil)
		route, params, err := router.FindRoute(req)
		if err != nil {
			t.Fatalf("%s %s is not in the contract: %v", method, path, err)
		}
		if route.Operation.Responses.Status(rec.Code) == nil {
			t.Errorf("%s %s answered %d, which the contract does not document", method, path, rec.Code)
		}
		if strings.Contains(rec.Header().Get("Content-Type"), "json") {
			in := &openapi3filter.ResponseValidationInput{
				RequestValidationInput: &openapi3filter.RequestValidationInput{Request: req, PathParams: params, Route: route,
					Options: &openapi3filter.Options{AuthenticationFunc: openapi3filter.NoopAuthenticationFunc}},
				Status: rec.Code, Header: rec.Header(), Body: io.NopCloser(bytes.NewReader(rec.Body.Bytes())),
				Options: &openapi3filter.Options{IncludeResponseStatus: true, MultiError: true},
			}
			if err := openapi3filter.ValidateResponse(context.Background(), in); err != nil {
				t.Errorf("%s %s → %d does not match the contract: %v\nbody: %s", method, path, rec.Code, err, rec.Body.String())
			}
		}
		validated[route.Operation.OperationID]++
		if rec.Body.Len() == 0 {
			return map[string]any{}
		}
		return jsonOf(t, rec)
	}
	const why = "Rotating after the quarterly review"
	res := func(r string) string { return "/v1/staff/resources/" + r }

	// The module is for identity:admin only: it is in the sidebar for Super Admin and absent for everyone else.
	nav := func(token string) string {
		keys := []string{}
		for _, g := range do(token, "GET", "/v1/staff/session", nil, 200)["navGroups"].([]any) {
			for _, m := range g.(map[string]any)["modules"].([]any) {
				keys = append(keys, m.(map[string]any)["key"].(string))
			}
		}
		return strings.Join(keys, ",")
	}
	if !strings.Contains(nav("admin"), "identity") || strings.Contains(nav("support"), "identity") || strings.Contains(nav("auditor"), "identity") {
		t.Fatalf("identity module visibility: admin=%s support=%s auditor=%s", nav("admin"), nav("support"), nav("auditor"))
	}
	for _, token := range []string{"support", "auditor"} {
		for _, r := range []string{"oauth-clients", "identity-sessions", "signing-keys", "auth-settings", "session-policy"} {
			do(token, "GET", res(r), nil, 403)
		}
		do(token, "POST", "/v1/staff/identity/token-inspector", map[string]any{"token": inspectedJWT}, 403)
		do(token, "GET", "/v1/staff/identity/documents", nil, 403)
	}
	do("", "GET", res("oauth-clients"), nil, 401)
	do("", "POST", "/v1/staff/identity/token-inspector", map[string]any{"token": inspectedJWT}, 401)
	do("", "GET", "/v1/staff/identity/documents", nil, 401)

	// Clients: list, sheet, register (secret shown once), edit, rotate, disable / enable, revoke sign-ins.
	list := do("admin", "GET", res("oauth-clients"), nil, 200)
	if len(list["rows"].([]any)) != 2 || list["canCreate"] != true {
		t.Fatalf("clients = %v", list)
	}
	form := list["createForm"].(map[string]any)
	if form["title"] != "Register an OAuth client" {
		t.Fatalf("create form = %v", form)
	}
	do("admin", "POST", res("oauth-clients"), map[string]any{"values": map[string]any{"clientId": "Bad Id", "type": "confidential", "name": "X"}}, 422)
	created := do("admin", "POST", res("oauth-clients"), map[string]any{"values": map[string]any{"clientId": "partner-api", "type": "confidential", "name": "Partner API",
		"grantTypes": []any{"client_credentials"}, "scopes": "partner.read\npartner.write", "accessTokenTtlMinutes": 5, "refreshTokenTtlMinutes": 60, "reason": why}}, 201)
	reveal := created["reveal"].(map[string]any)
	if reveal["value"] != createdSecret || reveal["label"] == "" {
		t.Fatalf("the secret is shown once with the new client: %v", created)
	}
	if b := f.idAdmin.bodies(); !strings.Contains(b, `"scopes":["partner.read","partner.write"]`) || !strings.Contains(b, `"reason":"`+why+`"`) {
		t.Fatalf("create reaches identity with split scopes and the reason: %s", b)
	}
	again := do("admin", "GET", res("oauth-clients")+"/partner-api", nil, 200)
	if _, shown := again["reveal"]; shown || strings.Contains(asJSON(again), createdSecret) {
		t.Fatalf("the secret must not be readable again: %v", again)
	}
	do("admin", "POST", res("oauth-clients"), map[string]any{"values": map[string]any{"clientId": "partner-ui", "type": "public", "name": "Partner UI",
		"grantTypes": []any{"authorization_code"}, "redirectUris": "https://p.example.com/cb", "accessTokenTtlMinutes": 5, "refreshTokenTtlMinutes": 60, "reason": why}}, 201)

	sheet := do("admin", "GET", res("oauth-clients")+"/gateway-bff", nil, 200)
	actions := map[string]map[string]any{}
	for _, a := range sheet["actions"].([]any) {
		actions[a.(map[string]any)["key"].(string)] = a.(map[string]any)
	}
	if actions["disable"]["allowed"] != false || !strings.Contains(actions["disable"]["reason"].(string), "lock every staff member out") || actions["enable"]["allowed"] != false {
		t.Fatalf("the platform's own client cannot be disabled: %v", actions["disable"])
	}
	do("admin", "POST", res("oauth-clients")+"/gateway-bff/actions/disable", map[string]any{"values": map[string]any{"reason": why}}, 409)

	edited := do("admin", "PATCH", res("oauth-clients")+"/partner-api", map[string]any{"values": map[string]any{"name": "Partner API v2", "grantTypes": []any{"client_credentials"},
		"scopes": "partner.read", "accessTokenTtlMinutes": 3, "refreshTokenTtlMinutes": 60, "reason": why}}, 200)
	if edited["title"] != "Partner API v2" {
		t.Fatalf("edited sheet = %v", edited["title"])
	}
	do("admin", "PATCH", res("oauth-clients")+"/partner-api", map[string]any{"values": map[string]any{"name": "No reason", "grantTypes": []any{"client_credentials"},
		"accessTokenTtlMinutes": 3, "refreshTokenTtlMinutes": 60}}, 422)

	do("admin", "POST", res("oauth-clients")+"/partner-api/actions/rotate-secret", map[string]any{"values": map[string]any{}}, 422)
	rotated := do("admin", "POST", res("oauth-clients")+"/partner-api/actions/rotate-secret", map[string]any{"values": map[string]any{"reason": why}}, 200)
	if rotated["reveal"].(map[string]any)["value"] != rotatedSecret {
		t.Fatalf("rotated = %v", rotated["reveal"])
	}
	do("admin", "POST", res("oauth-clients")+"/partner-ui/actions/rotate-secret", map[string]any{"values": map[string]any{"reason": why}}, 409)
	off := do("admin", "POST", res("oauth-clients")+"/partner-api/actions/disable", map[string]any{"values": map[string]any{"reason": why, "stepUpPassword": staffPassword}}, 200)
	if off["status"] != "DISABLED" {
		t.Fatalf("disabled = %v", off["status"])
	}
	on := do("admin", "POST", res("oauth-clients")+"/partner-api/actions/enable", map[string]any{"values": map[string]any{"reason": why}}, 200)
	if on["status"] != "ENABLED" {
		t.Fatalf("enabled = %v", on["status"])
	}
	do("admin", "POST", res("oauth-clients")+"/partner-api/actions/revoke-sessions", map[string]any{"values": map[string]any{"reason": why, "stepUpPassword": staffPassword}}, 200)

	// Step-up (S291): the dangerous actions end with a password field, identity checks it and the call carries its proof.
	for _, k := range []string{"disable", "revoke-sessions"} {
		var field map[string]any
		for _, a := range do("admin", "GET", res("oauth-clients")+"/partner-api", nil, 200)["actions"].([]any) {
			if a.(map[string]any)["key"] != k {
				continue
			}
			fields := a.(map[string]any)["form"].(map[string]any)["fields"].([]any)
			field = fields[len(fields)-1].(map[string]any)
		}
		if field == nil || field["key"] != "stepUpPassword" || field["type"] != "password" || field["required"] != false {
			t.Fatalf("%s must end with the optional password field: %v", k, field)
		}
	}
	before := len(f.idAdmin.stepUps)
	// No password and no recent sign-in: identity refuses, the form shows it on the password field and nothing happened.
	blank := do("admin", "POST", res("oauth-clients")+"/partner-api/actions/disable", map[string]any{"values": map[string]any{"reason": why}}, 422)
	if !strings.Contains(asJSON(blank), "stepUpPassword") {
		t.Fatalf("a missing confirmation is an error on the password field: %v", blank)
	}
	// A wrong password is refused at the step-up call: the dangerous call is never made.
	wrong := do("admin", "POST", res("oauth-clients")+"/partner-api/actions/disable", map[string]any{"values": map[string]any{"reason": why, "stepUpPassword": "not-the-password"}}, 422)
	if !strings.Contains(asJSON(wrong), "stepUpPassword") || !strings.Contains(asJSON(wrong), "isn't right") {
		t.Fatalf("a wrong password is an error on the password field: %v", wrong)
	}
	if got := f.idAdmin.stepUps[before:]; len(got) != 1 || got[0] != "" {
		t.Fatalf("only the blank attempt reached the dangerous endpoint, without a proof: %v", got)
	}
	do("admin", "POST", res("signing-keys"), map[string]any{"values": map[string]any{"reason": why, "stepUpPassword": "not-the-password"}}, 422)
	do("admin", "PATCH", res("auth-settings")+"/settings", map[string]any{"values": map[string]any{"passwordMinLength": 14, "mfaRequiredAccount": false, "mfaRequiredSeller": false,
		"mfaRequiredProvider": false, "sessionIdleTimeoutMinutes": 30, "sessionAbsoluteTimeoutMinutes": 1440, "reason": why}}, 422)

	// Sessions and tokens: list, sheet, revoke one, sign a person out everywhere.
	sessions := do("admin", "GET", res("identity-sessions"), nil, 200)
	if rows := sessions["rows"].([]any); len(rows) != 2 || rows[1].(map[string]any)["cells"].(map[string]any)["userName"] != "Service account billing-svc" {
		t.Fatalf("sessions = %v", sessions["rows"])
	}
	// S291: the Device column is the parsed User-Agent; a sign-in without one shows no device; the sheet has the raw agent.
	if first := sessions["rows"].([]any)[0].(map[string]any)["cells"].(map[string]any); first["device"] != "Chrome on macOS" {
		t.Fatalf("device cell = %v", first["device"])
	}
	if second := sessions["rows"].([]any)[1].(map[string]any)["cells"].(map[string]any); second["device"] != nil {
		t.Fatalf("service account device cell = %v", second["device"])
	}
	person := do("admin", "GET", res("identity-sessions")+"/auth-1", nil, 200)
	if !strings.Contains(fmt.Sprint(person["sections"]), "Chrome on macOS") || !strings.Contains(fmt.Sprint(person["sections"]), "Chrome/124.0.0.0") {
		t.Fatalf("session sheet lacks the device: %v", person["sections"])
	}
	service := do("admin", "GET", res("identity-sessions")+"/auth-2", nil, 200)
	for _, a := range service["actions"].([]any) {
		if a.(map[string]any)["key"] == "revoke-user" && a.(map[string]any)["allowed"] != false {
			t.Fatalf("a service account has no person to sign out: %v", a)
		}
	}
	if len(person["actions"].([]any)) != 2 {
		t.Fatalf("person actions = %v", person["actions"])
	}
	do("admin", "POST", res("identity-sessions")+"/auth-1/actions/revoke", map[string]any{"values": map[string]any{"reason": "no"}}, 422)
	if gone := do("admin", "POST", res("identity-sessions")+"/auth-1/actions/revoke", map[string]any{"values": map[string]any{"reason": why}}, 200); gone["removed"] != true {
		t.Fatalf("revoked = %v", gone)
	}
	if gone := do("admin", "POST", res("identity-sessions")+"/auth-1/actions/revoke-user", map[string]any{"values": map[string]any{"reason": why, "stepUpPassword": staffPassword}}, 200); gone["removed"] != true {
		t.Fatalf("signed out everywhere = %v", gone)
	}
	do("admin", "GET", res("identity-sessions")+"/nope", nil, 404)

	// Signing keys: the table and rotation (a create with a reason).
	keys := do("admin", "GET", res("signing-keys"), nil, 200)
	if keys["canCreate"] != true || keys["createForm"].(map[string]any)["title"] != "Rotate the signing key" || len(keys["rows"].([]any)) != 2 {
		t.Fatalf("signing keys = %v", keys)
	}
	do("admin", "POST", res("signing-keys"), map[string]any{"values": map[string]any{}}, 422)
	newKey := do("admin", "POST", res("signing-keys"), map[string]any{"values": map[string]any{"reason": why, "stepUpPassword": staffPassword}}, 201)
	if newKey["id"] != "k20261024T100000-ef56" || newKey["status"] != "ACTIVE" {
		t.Fatalf("rotated key = %v", newKey)
	}
	do("admin", "GET", res("signing-keys")+"/k20260701T000000-cd34", nil, 200)

	// Settings: only values that differ from the configured defaults are stored.
	st := do("admin", "GET", res("auth-settings")+"/settings", nil, 200)
	if st["editForm"] == nil {
		t.Fatalf("settings sheet = %v", st)
	}
	values := map[string]any{"passwordMinLength": 16, "mfaRequiredAccount": false, "mfaRequiredSeller": true, "mfaRequiredProvider": false,
		"sessionIdleTimeoutMinutes": 30, "sessionAbsoluteTimeoutMinutes": 600, "accessTokenTtlCapMinutes": 10, "reason": why, "stepUpPassword": staffPassword}
	do("admin", "GET", res("auth-settings"), nil, 200)
	do("admin", "PATCH", res("auth-settings")+"/settings", map[string]any{"values": map[string]any{"passwordMinLength": 4, "reason": why, "sessionIdleTimeoutMinutes": 30, "sessionAbsoluteTimeoutMinutes": 600}}, 422)
	saved := do("admin", "PATCH", res("auth-settings")+"/settings", map[string]any{"values": values}, 200)
	body := f.bodies["PUT /v1/admin/identity/settings"]["overrides"].(map[string]any)
	if len(body) != 4 || body["passwordMinLength"] != float64(16) || body["mfaRequiredSeller"] != true || body["sessionAbsoluteTimeoutMinutes"] != float64(600) || body["accessTokenTtlCapMinutes"] != float64(10) {
		t.Fatalf("only what differs from the defaults is stored, got %v", body)
	}
	if !strings.Contains(asJSON(saved["sections"]), "set here; default 12") {
		t.Fatalf("saved settings = %v", saved["sections"])
	}

	// Sign-out policy: one row per audience with names, not ids; a change is validated by identity, needs the
	// step-up and stores only what differs from the standard setting.
	policy := do("admin", "GET", res("session-policy"), nil, 200)
	rows := policy["rows"].([]any)
	if len(rows) != 5 || rows[3].(map[string]any)["id"] != "staff" {
		t.Fatalf("session policy rows = %v", rows)
	}
	if cells := rows[3].(map[string]any)["cells"].(map[string]any); cells["name"] != "Staff console" || cells["idle"] != "15 minutes" ||
		cells["absolute"] != "8 hours" || cells["warn"] != "60 seconds" {
		t.Fatalf("staff console row = %v", cells)
	}
	if cells := rows[0].(map[string]any)["cells"].(map[string]any); cells["idle"] != "30 days" || cells["absolute"] != "90 days" || cells["warn"] != "None" {
		t.Fatalf("customer row = %v", cells)
	}
	row := do("admin", "GET", res("session-policy")+"/staff", nil, 200)
	editForm, _ := row["editForm"].(map[string]any)
	if editForm == nil || len(editForm["changeKeys"].([]any)) != 3 || !strings.Contains(asJSON(editForm["fields"]), "stepUpPassword") {
		t.Fatalf("sign-out sheet = %v", row)
	}
	do("admin", "GET", res("session-policy")+"/nobody", nil, 404)
	do("admin", "PATCH", res("session-policy")+"/staff", map[string]any{"values": map[string]any{"idleTimeoutMinutes": 600, "absoluteTimeoutMinutes": 480, "warnSeconds": 60, "reason": why}}, 422)
	invalid := do("admin", "PATCH", res("session-policy")+"/staff", map[string]any{"values": map[string]any{"idleTimeoutMinutes": 600, "absoluteTimeoutMinutes": 480, "warnSeconds": 60, "reason": why, "stepUpPassword": staffPassword}}, 422)
	if !strings.Contains(asJSON(invalid), "cannot be longer than the session length") {
		t.Fatalf("identity's message must reach the field: %v", invalid)
	}
	changed := do("admin", "PATCH", res("session-policy")+"/staff", map[string]any{"values": map[string]any{"idleTimeoutMinutes": 10, "absoluteTimeoutMinutes": 480, "warnSeconds": 60, "reason": why, "stepUpPassword": staffPassword}}, 200)
	if !strings.Contains(asJSON(changed["sections"]), "10 (set here; default 15)") || !strings.Contains(asJSON(changed["sections"]), "480 (default)") {
		t.Fatalf("saved sign-out policy = %v", changed["sections"])
	}
	sent := f.bodies["PUT /v1/admin/identity/session-policy/staff"]["overrides"].(map[string]any)
	if sent["idleTimeoutMinutes"] != float64(10) || sent["absoluteTimeoutMinutes"] != float64(480) {
		t.Fatalf("identity receives the form's numbers: %v", sent)
	}
	do("support", "PATCH", res("session-policy")+"/staff", map[string]any{"values": map[string]any{"idleTimeoutMinutes": 10, "reason": why}}, 403)

	// Token inspector and documents.
	good := do("admin", "POST", "/v1/staff/identity/token-inspector", map[string]any{"token": inspectedJWT}, 200)
	if good["valid"] != true || good["signatureValid"] != true || good["keyId"] != "k1" {
		t.Fatalf("inspection = %v", good)
	}
	if strings.Contains(asJSON(good), inspectedJWT) {
		t.Fatal("the inspected token must not be echoed")
	}
	bad := do("admin", "POST", "/v1/staff/identity/token-inspector", map[string]any{"token": "opaque"}, 200)
	if bad["valid"] != false || bad["wellFormed"] != false {
		t.Fatalf("a bad token is reported, not an error: %v", bad)
	}
	do("admin", "POST", "/v1/staff/identity/token-inspector", map[string]any{"token": ""}, 422)
	do("admin", "POST", "/v1/staff/identity/token-inspector", map[string]any{"token": strings.Repeat("a", 16385)}, 422)
	docs := do("admin", "GET", "/v1/staff/identity/documents", nil, 200)
	if docs["jwks"].(map[string]any)["keys"] == nil || docs["discovery"].(map[string]any)["issuer"] != "https://id.test" {
		t.Fatalf("documents = %v", docs)
	}

	// The password travels only in the step-up request; the proof only as the header of the dangerous calls.
	for _, line := range strings.Split(f.idAdmin.bodies(), "\n") {
		if strings.Contains(line, staffPassword) && !strings.HasPrefix(line, "POST /v1/admin/identity/step-up ") {
			t.Fatalf("the password reached identity outside the step-up call: %s", line)
		}
		if strings.Contains(line, stepUpProof) {
			t.Fatalf("the proof belongs in a header, not a body: %s", line)
		}
	}
	for _, got := range f.idAdmin.stepUps {
		if got != "" && got != stepUpProof {
			t.Fatalf("unexpected X-Step-Up value %q", got)
		}
	}

	// Secrets and tokens reach identity only in the requests that need them and are never in a sheet or table.
	for _, r := range []string{"oauth-clients", "identity-sessions", "signing-keys", "auth-settings", "session-policy"} {
		page := asJSON(do("admin", "GET", res(r), nil, 200))
		if strings.Contains(page, createdSecret) || strings.Contains(page, rotatedSecret) || strings.Contains(page, inspectedJWT) || strings.Contains(page, staffPassword) || strings.Contains(page, stepUpProof) {
			t.Fatalf("%s leaks a secret: %s", r, page)
		}
	}

	for _, op := range []string{"inspectIdentityToken", "getIdentityDocuments", "listStaffResource", "getStaffRecord", "createStaffRecord", "updateStaffRecord", "runStaffAction", "getStaffSession"} {
		if validated[op] == 0 {
			t.Errorf("operation %s was not exercised", op)
		}
	}
	// Resource keys of the module are known to the console contract.
	for _, k := range []string{"oauth-clients", "identity-sessions", "signing-keys", "auth-settings", "session-policy"} {
		found := false
		for _, rk := range domain.ResourceKeys {
			found = found || rk == k
		}
		if !found {
			t.Errorf("resource key %s missing from domain.ResourceKeys", k)
		}
	}
}

func asJSON(v any) string {
	b, _ := json.Marshal(v)
	return string(b)
}
