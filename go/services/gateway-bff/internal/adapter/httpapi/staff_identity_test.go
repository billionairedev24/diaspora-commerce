package httpapi_test

import (
	"bytes"
	"context"
	"encoding/json"
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
	// seen collects every request body, to check that tokens and secrets travel only where they must.
	seen []string
}

const (
	createdSecret = "created-secret-SHOWN-ONCE-0001"
	rotatedSecret = "rotated-secret-SHOWN-ONCE-0002"
	inspectedJWT  = "eyJhbGciOiJSUzI1NiIsImtpZCI6ImsxIn0.eyJzdWIiOiJ1c2VyLTEiLCJpc3MiOiJodHRwczovL2lkLnRlc3QifQ.c2lnbmF0dXJl"
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
			"clientName": "Customer app", "ipAddress": "203.0.113.9", "signedInAt": "2026-10-24T09:00:00Z", "lastUsedAt": "2026-10-24T09:30:00Z",
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
	needsReason := r.Method != http.MethodGet && !strings.HasSuffix(p, "/token-inspector")
	if reason, _ := body["reason"].(string); needsReason && len(strings.TrimSpace(reason)) < 10 {
		invalidReason(w)
		return
	}
	rest := strings.TrimPrefix(p, "/v1/admin/identity/")
	parts := strings.Split(rest, "/")
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
		for _, r := range []string{"oauth-clients", "identity-sessions", "signing-keys", "auth-settings"} {
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
	off := do("admin", "POST", res("oauth-clients")+"/partner-api/actions/disable", map[string]any{"values": map[string]any{"reason": why}}, 200)
	if off["status"] != "DISABLED" {
		t.Fatalf("disabled = %v", off["status"])
	}
	on := do("admin", "POST", res("oauth-clients")+"/partner-api/actions/enable", map[string]any{"values": map[string]any{"reason": why}}, 200)
	if on["status"] != "ENABLED" {
		t.Fatalf("enabled = %v", on["status"])
	}
	do("admin", "POST", res("oauth-clients")+"/partner-api/actions/revoke-sessions", map[string]any{"values": map[string]any{"reason": why}}, 200)

	// Sessions and tokens: list, sheet, revoke one, sign a person out everywhere.
	sessions := do("admin", "GET", res("identity-sessions"), nil, 200)
	if rows := sessions["rows"].([]any); len(rows) != 2 || rows[1].(map[string]any)["cells"].(map[string]any)["userName"] != "Service account billing-svc" {
		t.Fatalf("sessions = %v", sessions["rows"])
	}
	person := do("admin", "GET", res("identity-sessions")+"/auth-1", nil, 200)
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
	if gone := do("admin", "POST", res("identity-sessions")+"/auth-1/actions/revoke-user", map[string]any{"values": map[string]any{"reason": why}}, 200); gone["removed"] != true {
		t.Fatalf("signed out everywhere = %v", gone)
	}
	do("admin", "GET", res("identity-sessions")+"/nope", nil, 404)

	// Signing keys: the table and rotation (a create with a reason).
	keys := do("admin", "GET", res("signing-keys"), nil, 200)
	if keys["canCreate"] != true || keys["createForm"].(map[string]any)["title"] != "Rotate the signing key" || len(keys["rows"].([]any)) != 2 {
		t.Fatalf("signing keys = %v", keys)
	}
	do("admin", "POST", res("signing-keys"), map[string]any{"values": map[string]any{}}, 422)
	newKey := do("admin", "POST", res("signing-keys"), map[string]any{"values": map[string]any{"reason": why}}, 201)
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
		"sessionIdleTimeoutMinutes": 30, "sessionAbsoluteTimeoutMinutes": 600, "accessTokenTtlCapMinutes": 10, "reason": why}
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

	// Secrets and tokens reach identity only in the requests that need them and are never in a sheet or table.
	for _, r := range []string{"oauth-clients", "identity-sessions", "signing-keys", "auth-settings"} {
		page := asJSON(do("admin", "GET", res(r), nil, 200))
		if strings.Contains(page, createdSecret) || strings.Contains(page, rotatedSecret) || strings.Contains(page, inspectedJWT) {
			t.Fatalf("%s leaks a secret: %s", r, page)
		}
	}

	for _, op := range []string{"inspectIdentityToken", "getIdentityDocuments", "listStaffResource", "getStaffRecord", "createStaffRecord", "updateStaffRecord", "runStaffAction", "getStaffSession"} {
		if validated[op] == 0 {
			t.Errorf("operation %s was not exercised", op)
		}
	}
	// Resource keys of the module are known to the console contract.
	for _, k := range []string{"oauth-clients", "identity-sessions", "signing-keys", "auth-settings"} {
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
