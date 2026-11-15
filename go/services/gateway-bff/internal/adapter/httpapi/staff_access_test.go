package httpapi_test

import (
	"bytes"
	"context"
	"io"
	"net/http"
	"net/http/httptest"
	"slices"
	"strings"
	"sync"
	"testing"

	"github.com/getkin/kin-openapi/openapi3"
	"github.com/getkin/kin-openapi/openapi3filter"
	"github.com/getkin/kin-openapi/routers/gorillamux"

	"github.com/billionairedev24/diaspora-commerce/go/services/gateway-bff/internal/adapter/httpapi"
	"github.com/billionairedev24/diaspora-commerce/go/services/gateway-bff/internal/app"
)

const (
	accessAda  = "0a0a0a0a-0000-4000-8000-000000000001"
	accessKofi = "0a0a0a0a-0000-4000-8000-000000000002"
)

// TestStaffAccessConformance drives Settings > Roles and permissions against a fake identity: the catalogue (a permission
// added later shows up untouched), roles per portal, who holds a role, effective permissions with direct grants, changes
// that need a reason and access:admin, the lock-out guard, and the change log with names. Every answer is checked
// against the staff contract.
func TestStaffAccessConformance(t *testing.T) {
	doc, err := openapi3.NewLoader().LoadFromFile(staffContractPath)
	if err != nil {
		t.Fatal(err)
	}
	doc.Servers = openapi3.Servers{{URL: "http://bff.internal"}}
	router, err := gorillamux.NewRouter(doc)
	if err != nil {
		t.Fatal(err)
	}
	var mu sync.Mutex
	perms := []map[string]any{
		{"key": "refunds:issue", "module": "payments", "description": "Refund a customer"},
		{"key": "access:read", "module": "access", "description": "View access control"},
		{"key": "delivery:read", "module": "delivery", "description": "View delivery runs"},
	}
	roles := map[string][]string{"platform.finance": {"refunds:issue"}, "platform.super_admin": {"access:read", "access:admin"}}
	direct := []string{}
	var reasons []string
	f := &fakePlatform{payoutSt: "REQUESTED"}
	f.override = func(w http.ResponseWriter, r *http.Request, _ map[string]any) bool {
		mu.Lock()
		defer mu.Unlock()
		p := r.URL.Path
		switch {
		case p == "/v1/admin/permissions":
			writeJ(w, 200, perms)
		case p == "/v1/admin/roles" && r.URL.Query().Get("portal") == "platform":
			out := []any{}
			for k, v := range roles {
				out = append(out, map[string]any{"key": k, "portal": "platform", "name": "From identity", "description": "d", "permissions": v})
			}
			writeJ(w, 200, out)
		case p == "/v1/admin/roles":
			writeJ(w, 200, []any{})
		case strings.HasPrefix(p, "/v1/admin/roles/") && strings.Contains(p, "/permissions/"):
			rest := strings.TrimPrefix(p, "/v1/admin/roles/")
			role, perm, _ := strings.Cut(rest, "/permissions/")
			reasons = append(reasons, r.URL.Query().Get("reason"))
			if r.Method == http.MethodPut {
				roles[role] = append(roles[role], perm)
			} else {
				roles[role] = slices.DeleteFunc(roles[role], func(x string) bool { return x == perm })
			}
			writeJ(w, 200, map[string]any{"key": role, "portal": "platform", "name": "x", "description": "d", "permissions": roles[role]})
		case p == "/v1/admin/users" && r.Method == http.MethodGet:
			writeJ(w, 200, map[string]any{"items": []any{
				map[string]any{"id": accessAda, "name": "Ada Obi", "email": "ada@afrotransact.com", "enabled": true, "principalType": "STAFF", "roles": []string{"platform.finance"},
					"directGrants": direct, "effectivePermissions": append([]string{"refunds:issue"}, direct...), "roleMarkets": map[string]any{"platform.finance": []string{"GB"}}},
				map[string]any{"id": accessKofi, "name": "Kofi Mensah", "email": "kofi@afrotransact.com", "enabled": true, "principalType": "STAFF", "roles": []string{"platform.auditor"}}}, "page": 0, "size": 100, "total": 2})
		case p == "/v1/admin/users/"+accessAda+"/permissions/delivery:read":
			reasons = append(reasons, r.URL.Query().Get("reason"))
			if r.Method == http.MethodPut {
				direct = []string{"delivery:read"}
			} else {
				direct = []string{}
			}
			writeJ(w, 200, map[string]any{"id": accessAda, "name": "Ada Obi", "email": "ada@afrotransact.com", "enabled": true, "principalType": "STAFF", "roles": []string{"platform.finance"},
				"directGrants": direct, "effectivePermissions": append([]string{"refunds:issue"}, direct...)})
		case p == "/v1/admin/users/"+accessAda:
			writeJ(w, 200, map[string]any{"id": accessAda, "name": "Ada Obi", "email": "ada@afrotransact.com", "enabled": true, "principalType": "STAFF", "roles": []string{"platform.finance"},
				"directGrants": direct, "effectivePermissions": []string{"refunds:issue"}})
		case p == "/v1/admin/access-log":
			writeJ(w, 200, map[string]any{"items": []any{
				map[string]any{"id": 2, "at": "2026-11-01T10:00:00Z", "actorId": accessKofi, "action": "ROLE_PERMISSION_ADDED", "targetRole": "platform.finance", "subject": "refunds:issue", "detail": "Quarter close"},
				map[string]any{"id": 1, "at": "2026-10-30T10:00:00Z", "actorId": accessKofi, "action": "ROLE_ASSIGNED", "targetUserId": accessAda, "targetRole": "platform.auditor", "subject": "platform.auditor", "detail": ""}}, "page": 0, "size": 50})
		default:
			return false
		}
		return true
	}
	raw := staffBFFWith(t, f, func(d app.StaffDeps, staff *httpapi.StaffServices) { staff.Access = app.NewStaffAccessService(d) })
	do := func(token, method, path string, body any, want int) map[string]any {
		t.Helper()
		rec := raw(token, method, path, body)
		if rec.Code != want {
			t.Fatalf("%s %s as %s = %d, want %d: %s", method, path, token, rec.Code, want, rec.Body.String())
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
		if rec.Body.Len() == 0 {
			return map[string]any{}
		}
		return jsonOf(t, rec)
	}
	const why = "Quarter close needs refunds"

	// Who may open it: Super Admin (access:read), not customer support, and the auditor only if they hold access:read.
	do("", "GET", "/v1/staff/access/permissions", nil, 401)
	do("support", "GET", "/v1/staff/access/permissions", nil, 403)
	do("support", "GET", "/v1/staff/access/roles", nil, 403)
	do("auditor", "GET", "/v1/staff/access/log", nil, 403) // the fake auditor lacks access:read, so identity's gate is mirrored

	// The catalogue comes from identity, grouped by module, with nothing hard-coded: a permission added later appears.
	cat := do("admin", "GET", "/v1/staff/access/permissions", nil, 200)["items"].([]any)
	keys := []string{}
	for _, p := range cat {
		keys = append(keys, p.(map[string]any)["key"].(string))
	}
	if strings.Join(keys, ",") != "access:read,delivery:read,refunds:issue" {
		t.Fatalf("catalogue = %v", keys)
	}
	mu.Lock()
	perms = append(perms, map[string]any{"key": "delivery:manage", "module": "delivery", "description": "Manage delivery"})
	mu.Unlock()
	if n := len(do("admin", "GET", "/v1/staff/access/permissions", nil, 200)["items"].([]any)); n != 4 {
		t.Fatalf("a new catalogue entry did not show up: %d", n)
	}

	// Roles: the platform roles keep the console's names; the count is computed; an unknown portal is a 422.
	rl := do("admin", "GET", "/v1/staff/access/roles?portal=platform", nil, 200)
	if rl["canAdmin"] != true || len(rl["items"].([]any)) != 2 {
		t.Fatalf("roles = %v", rl)
	}
	for _, r := range rl["items"].([]any) {
		m := r.(map[string]any)
		if m["key"] == "platform.finance" && (m["name"] != "Finance" || m["permissionCount"].(float64) != 1) {
			t.Fatalf("finance role = %v", m)
		}
	}
	do("admin", "GET", "/v1/staff/access/roles?portal=nonsense", nil, 422)
	do("admin", "GET", "/v1/staff/access/roles?portal=seller", nil, 200)

	// Members are named; the market a role is limited to comes along.
	mem := do("admin", "GET", "/v1/staff/access/roles/platform.finance/members", nil, 200)
	items := mem["items"].([]any)
	if len(items) != 1 || items[0].(map[string]any)["name"] != "Ada Obi" || items[0].(map[string]any)["markets"].([]any)[0] != "GB" || mem["partial"] != false {
		t.Fatalf("members = %v", mem)
	}
	do("admin", "GET", "/v1/staff/access/roles/NOT_A_ROLE/members", nil, 404)

	// Changing a role needs a reason, and reaches identity with it.
	path := "/v1/staff/access/roles/platform.finance/permissions/delivery:read"
	do("admin", "PUT", path, map[string]any{}, 422)
	do("admin", "PUT", path, map[string]any{"reason": "no"}, 422)
	do("support", "PUT", path, map[string]any{"reason": why}, 403)
	added := do("admin", "PUT", path, map[string]any{"reason": why}, 200)
	if added["permissionCount"].(float64) != 2 {
		t.Fatalf("after add = %v", added)
	}
	do("admin", "DELETE", path, map[string]any{"reason": why}, 200)
	if len(reasons) != 2 || reasons[0] != why {
		t.Fatalf("identity saw reasons %v", reasons)
	}
	do("admin", "PUT", "/v1/staff/access/roles/platform.finance/permissions/Bad%20Key", map[string]any{"reason": why}, 422)
	// Nobody locks Super Admin out.
	do("admin", "DELETE", "/v1/staff/access/roles/platform.super_admin/permissions/access:admin", map[string]any{"reason": why}, 409)

	// People: effective permissions, then a direct grant and its revoke.
	pp := do("admin", "GET", "/v1/staff/access/people?q=ada", nil, 200)
	if pp["total"].(float64) != 2 {
		t.Fatalf("people = %v", pp)
	}
	person := do("admin", "GET", "/v1/staff/access/people/"+accessAda, nil, 200)
	if person["name"] != "Ada Obi" || person["roles"].([]any)[0].(map[string]any)["name"] != "Finance" {
		t.Fatalf("person = %v", person)
	}
	do("admin", "GET", "/v1/staff/access/people/stf-1", nil, 404)
	grant := "/v1/staff/access/people/" + accessAda + "/permissions/delivery:read"
	do("admin", "PUT", grant, map[string]any{}, 422)
	got := do("admin", "PUT", grant, map[string]any{"reason": "Covers dispatch this week"}, 200)
	if g := got["directGrants"].([]any); len(g) != 1 || g[0] != "delivery:read" {
		t.Fatalf("after grant = %v", got)
	}
	if g := do("admin", "DELETE", grant, map[string]any{"reason": "Back from cover"}, 200)["directGrants"].([]any); len(g) != 0 {
		t.Fatalf("after revoke = %v", g)
	}

	// The change log names the actor and the person; a role filter keeps that role's entries.
	lg := do("admin", "GET", "/v1/staff/access/log", nil, 200)["items"].([]any)
	if len(lg) != 2 || lg[0].(map[string]any)["actorName"] == "" || lg[0].(map[string]any)["actorName"] == accessKofi {
		t.Fatalf("log = %v", lg)
	}
	byRole := do("admin", "GET", "/v1/staff/access/log?role=platform.finance", nil, 200)["items"].([]any)
	if len(byRole) != 1 || byRole[0].(map[string]any)["detail"] != "Quarter close" {
		t.Fatalf("log by role = %v", byRole)
	}
	do("admin", "GET", "/v1/staff/access/log?userId="+accessAda, nil, 200)
	do("admin", "GET", "/v1/staff/access/log?userId=not-a-uuid", nil, 422)
}
