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
	var reasons, identityQueries []string
	matrix := map[string]string{"payouts|platform.finance": "VIEW", "rbac|platform.super_admin": "FULL"}
	var moduleCalls []map[string]any
	f := &fakePlatform{payoutSt: "REQUESTED"}
	f.override = func(w http.ResponseWriter, r *http.Request, body map[string]any) bool {
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
			identityQueries = append(identityQueries, r.URL.RawQuery)
			if role := r.URL.Query().Get("role"); role != "" {
				holders := []any{}
				if role == "platform.finance" {
					holders = append(holders, map[string]any{"id": accessAda, "name": "Ada Obi", "email": "ada@afrotransact.com", "enabled": true, "principalType": "STAFF", "roles": []string{"platform.finance"},
						"directGrants": []string{}, "effectivePermissions": []string{"refunds:issue"}, "roleMarkets": map[string]any{"platform.finance": []string{"GB"}}})
				}
				writeJ(w, 200, map[string]any{"items": holders, "page": 0, "size": 50, "total": len(holders)})
				return true
			}
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
		case p == "/v1/admin/module-access" && r.Method == http.MethodGet:
			lv := func(m string) map[string]any {
				out := map[string]any{}
				for _, role := range []string{"platform.finance", "platform.super_admin"} {
					if v, ok := matrix[m+"|"+role]; ok {
						out[role] = v
					}
				}
				return out
			}
			writeJ(w, 200, map[string]any{"portal": "platform", "modules": []any{
				map[string]any{"key": "payouts", "label": "Payouts & ledger", "levels": lv("payouts")},
				map[string]any{"key": "rbac", "label": "Users & roles", "levels": lv("rbac")}},
				"roles": []any{map[string]any{"key": "platform.finance", "portal": "platform", "name": "From identity", "description": "d", "permissions": []string{}},
					map[string]any{"key": "platform.super_admin", "portal": "platform", "name": "From identity", "description": "d", "permissions": []string{}}}})
		case strings.HasPrefix(p, "/v1/admin/module-access/") && r.Method == http.MethodPut:
			parts := strings.Split(strings.TrimPrefix(p, "/v1/admin/module-access/"), "/")
			moduleCalls = append(moduleCalls, body)
			matrix[parts[1]+"|"+parts[2]] = strings.ToUpper(body["level"].(string))
			writeJ(w, 200, map[string]any{"portal": parts[0], "module": parts[1], "role": parts[2], "level": body["level"]})
		case p == "/v1/admin/access-log":
			identityQueries = append(identityQueries, r.URL.RawQuery)
			if r.URL.Query().Get("role") == "platform.finance" {
				writeJ(w, 200, map[string]any{"items": []any{
					map[string]any{"id": 2, "at": "2026-11-01T10:00:00Z", "actorId": accessKofi, "action": "ROLE_PERMISSION_ADDED", "targetRole": "platform.finance", "subject": "refunds:issue", "detail": "Quarter close"}}, "page": 0, "size": 50, "total": 1})
				return true
			}
			writeJ(w, 200, map[string]any{"items": []any{
				map[string]any{"id": 2, "at": "2026-11-01T10:00:00Z", "actorId": accessKofi, "action": "ROLE_PERMISSION_ADDED", "targetRole": "platform.finance", "subject": "refunds:issue", "detail": "Quarter close"},
				map[string]any{"id": 1, "at": "2026-10-30T10:00:00Z", "actorId": accessKofi, "action": "ROLE_ASSIGNED", "targetUserId": accessAda, "targetRole": "platform.auditor", "subject": "platform.auditor", "detail": ""}}, "page": 0, "size": 50, "total": 2})
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
	if len(items) != 1 || items[0].(map[string]any)["name"] != "Ada Obi" || items[0].(map[string]any)["markets"].([]any)[0] != "GB" || mem["total"].(float64) != 1 {
		t.Fatalf("members = %v", mem)
	}
	do("admin", "GET", "/v1/staff/access/roles/NOT_A_ROLE/members", nil, 404)
	// The role filter is identity's own (exact, paged): no directory scan, no "partial" in the answer.
	if _, has := mem["partial"]; has {
		t.Fatalf("members still carry partial: %v", mem)
	}
	if empty := do("admin", "GET", "/v1/staff/access/roles/platform.auditor/members?page=0&size=10", nil, 200); empty["total"].(float64) != 0 || len(empty["items"].([]any)) != 0 {
		t.Fatalf("auditor members = %v", empty)
	}
	if last := identityQueries[len(identityQueries)-1]; last != "page=0&role=platform.auditor&size=10" {
		t.Fatalf("identity saw %q", last)
	}

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

	// Module access: a matrix by label and role name, changes with a reason, and the lock-out guard.
	mx := do("admin", "GET", "/v1/staff/access/modules", nil, 200)
	if mx["canAdmin"] != true || len(mx["modules"].([]any)) != 2 || len(mx["roles"].([]any)) != 2 || len(mx["locked"].([]any)) != 2 {
		t.Fatalf("matrix = %v", mx)
	}
	row := mx["modules"].([]any)[0].(map[string]any)
	if row["label"] != "Payouts & ledger" || row["levels"].(map[string]any)["platform.finance"] != "view" {
		t.Fatalf("row = %v", row)
	}
	for _, r := range mx["roles"].([]any) {
		if r.(map[string]any)["key"] == "platform.finance" && r.(map[string]any)["name"] != "Finance" {
			t.Fatalf("role not named by the console: %v", r)
		}
	}
	do("admin", "GET", "/v1/staff/access/modules?portal=nonsense", nil, 422)
	do("support", "GET", "/v1/staff/access/modules", nil, 403)
	cell := "/v1/staff/access/modules/platform/payouts/platform.finance"
	do("admin", "PUT", cell, map[string]any{"level": "edit"}, 422)                  // no reason
	do("admin", "PUT", cell, map[string]any{"level": "edit", "reason": "no"}, 422)  // too short
	do("admin", "PUT", cell, map[string]any{"level": "god", "reason": why}, 422)    // not a level
	do("support", "PUT", cell, map[string]any{"level": "edit", "reason": why}, 403) // no access:admin
	do("admin", "PUT", "/v1/staff/access/modules/platform/pay-outs/platform.finance", map[string]any{"level": "edit", "reason": why}, 404)
	changed := do("admin", "PUT", cell, map[string]any{"level": "edit", "reason": why}, 200)
	if changed["level"] != "edit" || len(moduleCalls) != 1 || moduleCalls[0]["reason"] != why {
		t.Fatalf("changed = %v, identity saw %v", changed, moduleCalls)
	}
	if after := do("admin", "GET", "/v1/staff/access/modules", nil, 200)["modules"].([]any)[0].(map[string]any); after["levels"].(map[string]any)["platform.finance"] != "edit" {
		t.Fatalf("matrix after = %v", after)
	}
	// Nobody locks Super Admin out: Users & roles stays at Edit or above; raising it, or lowering other roles, is fine.
	lock := "/v1/staff/access/modules/platform/rbac/platform.super_admin"
	do("admin", "PUT", lock, map[string]any{"level": "view", "reason": why}, 409)
	do("admin", "PUT", lock, map[string]any{"level": "none", "reason": why}, 409)
	do("admin", "PUT", "/v1/staff/access/modules/platform/access/platform.super_admin", map[string]any{"level": "view", "reason": why}, 409)
	if len(moduleCalls) != 1 {
		t.Fatalf("a refused lock-out reached identity: %v", moduleCalls)
	}
	do("admin", "PUT", lock, map[string]any{"level": "edit", "reason": why}, 200)
	do("admin", "PUT", "/v1/staff/access/modules/platform/rbac/platform.finance", map[string]any{"level": "none", "reason": why}, 200)

	// The change log names the actor and the person; a role filter keeps that role's entries.
	lg := do("admin", "GET", "/v1/staff/access/log", nil, 200)["items"].([]any)
	if len(lg) != 2 || lg[0].(map[string]any)["actorName"] == "" || lg[0].(map[string]any)["actorName"] == accessKofi {
		t.Fatalf("log = %v", lg)
	}
	byRole := do("admin", "GET", "/v1/staff/access/log?role=platform.finance", nil, 200)["items"].([]any)
	if len(byRole) != 1 || byRole[0].(map[string]any)["detail"] != "Quarter close" {
		t.Fatalf("log by role = %v", byRole)
	}
	if _, has := do("admin", "GET", "/v1/staff/access/log?role=platform.finance", nil, 200)["partial"]; has {
		t.Fatal("log still carries partial")
	}
	if last := identityQueries[len(identityQueries)-1]; last != "page=0&role=platform.finance&size=50" {
		t.Fatalf("identity saw %q for the log role filter", last)
	}
	do("admin", "GET", "/v1/staff/access/log?permission=refunds:issue&role=platform.finance&userId="+accessAda, nil, 200)
	if last := identityQueries[len(identityQueries)-1]; last != "page=0&permission=refunds%3Aissue&role=platform.finance&size=50&userId="+accessAda {
		t.Fatalf("identity saw %q for the combined filter", last)
	}
	do("admin", "GET", "/v1/staff/access/log?role=BAD", nil, 422)
	do("admin", "GET", "/v1/staff/access/log?userId="+accessAda, nil, 200)
	do("admin", "GET", "/v1/staff/access/log?userId=not-a-uuid", nil, 422)
}
