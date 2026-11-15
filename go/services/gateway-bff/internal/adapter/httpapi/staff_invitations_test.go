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
)

// TestStaffInvitationsConformance drives Settings > Staff accounts' invitations against a fake identity: who may invite,
// validation, the open list with names, resend and revoke with a reason, and the invitee's two session-less routes, where the
// token and the password reach identity in a body and never in a URL, a log or an answer. Every answer is checked against
// the staff contract.
func TestStaffInvitationsConformance(t *testing.T) {
	doc, err := openapi3.NewLoader().LoadFromFile(staffContractPath)
	if err != nil {
		t.Fatal(err)
	}
	doc.Servers = openapi3.Servers{{URL: "http://bff.internal"}}
	router, err := gorillamux.NewRouter(doc)
	if err != nil {
		t.Fatal(err)
	}
	const secretToken, secretPassword = "tok-9f8e7d6c5b4a39281706f5e4d3c2b1a0", "Plantain-Jollof-2026"
	var mu sync.Mutex
	var seen []string // every identity call: method, path with query, and whether a bearer was sent
	var bodies []map[string]any
	invitation := func(status string) map[string]any {
		return map[string]any{"id": accessAda, "email": "ada.obi@afrotransact.com", "firstName": "Ada", "lastName": "Obi", "roleKey": "platform.customer_support",
			"roleName": "Customer Support", "status": status, "reason": "Joins support in Lagos", "invitedByName": "Ifeoma Okafor", "createdAt": "2026-11-15T10:00:00Z",
			"expiresAt": "2026-11-18T10:00:00Z", "lastSentAt": "2026-11-15T10:00:00Z", "sendCount": 1}
	}
	f := &fakePlatform{payoutSt: "REQUESTED"}
	f.override = func(w http.ResponseWriter, r *http.Request, body map[string]any) bool {
		mu.Lock()
		defer mu.Unlock()
		p := r.URL.Path
		if strings.HasPrefix(p, "/v1/admin/staff-invitations") || strings.HasPrefix(p, "/v1/staff-invitations/") {
			seen = append(seen, r.Method+" "+r.URL.RequestURI()+" bearer="+strings.TrimSpace(strings.TrimPrefix(r.Header.Get("Authorization"), "Bearer ")))
			bodies = append(bodies, body)
		}
		switch {
		case p == "/v1/admin/staff-invitations" && r.Method == http.MethodGet:
			writeJ(w, 200, map[string]any{"items": []any{invitation("PENDING"), func() map[string]any { e := invitation("EXPIRED"); e["email"] = "old@afrotransact.com"; return e }()}, "page": 0, "size": 25, "total": 2})
		case p == "/v1/admin/staff-invitations" && r.Method == http.MethodPost:
			writeJ(w, 201, invitation("PENDING"))
		case strings.HasSuffix(p, "/resend"):
			writeJ(w, 200, invitation("PENDING"))
		case strings.HasSuffix(p, "/revoke"):
			w.WriteHeader(204)
		case p == "/v1/staff-invitations/preview":
			if body["token"] != secretToken {
				writeJ(w, 404, map[string]any{"type": "about:blank", "title": "Not Found", "status": 404, "code": "identity.staff-invitation-not-found", "detail": "This invitation link isn't valid"})
				return true
			}
			writeJ(w, 200, map[string]any{"firstName": "Ada", "lastName": "Obi", "email": "ada.obi@afrotransact.com", "roleName": "Customer Support", "status": "PENDING", "expiresAt": "2026-11-18T10:00:00Z"})
		case p == "/v1/staff-invitations/accept":
			if body["token"] != secretToken {
				writeJ(w, 404, map[string]any{"type": "about:blank", "title": "Not Found", "status": 404, "code": "identity.staff-invitation-not-found", "detail": "This invitation link isn't valid"})
				return true
			}
			if body["password"] == "short" {
				writeJ(w, 422, map[string]any{"type": "about:blank", "title": "Unprocessable", "status": 422, "code": "identity.invalid-user", "detail": "Check the highlighted fields", "errors": map[string]any{"password": "Use at least 12 characters"}})
				return true
			}
			writeJ(w, 201, map[string]any{"email": "ada.obi@afrotransact.com", "roleName": "Customer Support"})
		default:
			return false
		}
		return true
	}
	raw := staffBFFWith(t, f, func(d app.StaffDeps, staff *httpapi.StaffServices) {
		staff.Access = app.NewStaffAccessService(d)
		staff.Invitations = app.NewStaffInvitationsService(d)
	})
	do := func(token, method, path string, body any, want int) (map[string]any, *httptest.ResponseRecorder) {
		t.Helper()
		rec := raw(token, method, path, body)
		if rec.Code != want {
			t.Fatalf("%s %s as %q = %d, want %d: %s", method, path, token, rec.Code, want, rec.Body.String())
		}
		req := httptest.NewRequestWithContext(context.Background(), method, "http://bff.internal"+path, nil)
		route, params, err := router.FindRoute(req)
		if err != nil {
			t.Fatalf("%s %s is not in the contract: %v", method, path, err)
		}
		if route.Operation.Responses.Status(rec.Code) == nil {
			t.Errorf("%s %s answered %d, which the contract does not document", method, path, rec.Code)
		}
		if strings.Contains(rec.Header().Get("Content-Type"), "json") && rec.Code < 300 {
			in := &openapi3filter.ResponseValidationInput{
				RequestValidationInput: &openapi3filter.RequestValidationInput{Request: req, PathParams: params, Route: route, Options: &openapi3filter.Options{AuthenticationFunc: openapi3filter.NoopAuthenticationFunc}},
				Status:                 rec.Code, Header: rec.Header(), Body: io.NopCloser(bytes.NewReader(rec.Body.Bytes())), Options: &openapi3filter.Options{IncludeResponseStatus: true, MultiError: true},
			}
			if err := openapi3filter.ValidateResponse(context.Background(), in); err != nil {
				t.Errorf("%s %s → %d does not match the contract: %v\nbody: %s", method, path, rec.Code, err, rec.Body.String())
			}
		}
		if rec.Body.Len() == 0 {
			return map[string]any{}, rec
		}
		var out map[string]any
		_ = json.Unmarshal(rec.Body.Bytes(), &out)
		return out, rec
	}
	invite := map[string]any{"firstName": "Ada", "lastName": "Obi", "email": "ada.obi@afrotransact.com", "roleKey": "platform.customer_support", "reason": "Joins support in Lagos"}

	// Who may: Super Admin yes; customer support and the auditor (no access:read) no; nobody without a session.
	do("", "GET", "/v1/staff/access/invitations", nil, 401)
	do("support", "GET", "/v1/staff/access/invitations", nil, 403)
	do("support", "POST", "/v1/staff/access/invitations", invite, 403)
	do("auditor", "GET", "/v1/staff/access/invitations", nil, 403)

	// The open list shows names and emails, the status, who invited, and never a token.
	list, rec := do("admin", "GET", "/v1/staff/access/invitations?size=10", nil, 200)
	rows := list["items"].([]any)
	first := rows[0].(map[string]any)
	if len(rows) != 2 || first["name"] != "Ada Obi" || first["email"] != "ada.obi@afrotransact.com" || first["invitedByName"] != "Ifeoma Okafor" || list["total"].(float64) != 2 || list["canAdmin"] != true {
		t.Fatalf("list = %v", list)
	}
	if strings.Contains(strings.ToLower(rec.Body.String()), "token") {
		t.Fatalf("the list mentions a token: %s", rec.Body.String())
	}

	// Inviting validates here and at identity; the reason is required; only staff roles.
	bad := func(m map[string]any, field string) {
		t.Helper()
		in := map[string]any{}
		for k, v := range invite {
			in[k] = v
		}
		for k, v := range m {
			in[k] = v
		}
		res, _ := do("admin", "POST", "/v1/staff/access/invitations", in, 422)
		if errs, _ := res["errors"].(map[string]any); errs[field] == nil {
			t.Fatalf("%v: no error on %s: %v", m, field, res)
		}
	}
	bad(map[string]any{"reason": "no"}, "reason")
	bad(map[string]any{"email": "not-an-email"}, "email")
	bad(map[string]any{"roleKey": "seller.owner"}, "roleKey")
	bad(map[string]any{"firstName": ""}, "firstName")
	created, _ := do("admin", "POST", "/v1/staff/access/invitations", invite, 201)
	if created["name"] != "Ada Obi" || created["roleName"] != "Customer Support" {
		t.Fatalf("invited = %v", created)
	}
	// The call reached identity with the staff member's own token and the reason.
	last := bodies[len(bodies)-1]
	if last["reason"] != "Joins support in Lagos" || last["roleKey"] != "platform.customer_support" {
		t.Fatalf("identity saw %v", last)
	}

	// Resend and revoke: ids are checked, revoke needs a reason.
	do("admin", "POST", "/v1/staff/access/invitations/"+accessAda+"/resend", map[string]any{}, 200)
	do("admin", "POST", "/v1/staff/access/invitations/not-an-id/resend", map[string]any{}, 404)
	do("support", "POST", "/v1/staff/access/invitations/"+accessAda+"/resend", map[string]any{}, 403)
	do("admin", "POST", "/v1/staff/access/invitations/"+accessAda+"/revoke", map[string]any{"reason": "x"}, 422)
	do("support", "POST", "/v1/staff/access/invitations/"+accessAda+"/revoke", map[string]any{"reason": "Offer withdrawn"}, 403)
	do("admin", "POST", "/v1/staff/access/invitations/"+accessAda+"/revoke", map[string]any{"reason": "Offer withdrawn"}, 204)

	// The invitee: no session. The token and the password travel in a body, never in a URL, and are never answered back.
	mark := len(seen)
	pv, prec := do("", "POST", "/v1/staff/invitations/preview", map[string]any{"token": secretToken}, 200)
	if pv["name"] != "Ada Obi" || pv["status"] != "PENDING" || pv["roleName"] != "Customer Support" || strings.Contains(prec.Body.String(), secretToken) {
		t.Fatalf("preview = %v", pv)
	}
	if cc := prec.Header().Get("Cache-Control"); !strings.Contains(cc, "no-store") {
		t.Fatalf("preview Cache-Control = %q", cc)
	}
	do("", "POST", "/v1/staff/invitations/preview", map[string]any{"token": "wrong"}, 404)
	do("", "POST", "/v1/staff/invitations/preview", map[string]any{"token": strings.Repeat("a", 500)}, 404)
	weak, _ := do("", "POST", "/v1/staff/invitations/accept", map[string]any{"token": secretToken, "password": "short", "confirmPassword": "short"}, 422)
	if errs, _ := weak["errors"].(map[string]any); errs["password"] == nil {
		t.Fatalf("weak = %v", weak)
	}
	do("", "POST", "/v1/staff/invitations/accept", map[string]any{"token": secretToken, "password": secretPassword, "confirmPassword": secretPassword + "x"}, 422)
	done, arec := do("", "POST", "/v1/staff/invitations/accept", map[string]any{"token": secretToken, "password": secretPassword, "confirmPassword": secretPassword}, 201)
	if done["email"] != "ada.obi@afrotransact.com" || strings.Contains(arec.Body.String(), secretPassword) || strings.Contains(arec.Body.String(), secretToken) {
		t.Fatalf("accepted = %v", done)
	}
	do("", "POST", "/v1/staff/invitations/accept", map[string]any{"token": "wrong", "password": secretPassword, "confirmPassword": secretPassword}, 404)
	for _, call := range seen[mark:] {
		if strings.Contains(call, secretToken) || strings.Contains(call, secretPassword) {
			t.Errorf("a secret reached identity in a URL: %s", call)
		}
		if !strings.HasSuffix(call, "bearer=") {
			t.Errorf("the invitee has no session, but identity saw %s", call)
		}
	}
}
