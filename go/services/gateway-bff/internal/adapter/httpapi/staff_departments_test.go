package httpapi_test

import (
	"bytes"
	"context"
	"encoding/json"
	"io"
	"log/slog"
	"net/http"
	"net/http/httptest"
	"slices"
	"strings"
	"sync"
	"testing"
	"time"

	"github.com/getkin/kin-openapi/openapi3"
	"github.com/getkin/kin-openapi/openapi3filter"
	"github.com/getkin/kin-openapi/routers/gorillamux"
	"github.com/prometheus/client_golang/prometheus"

	"github.com/billionairedev24/diaspora-commerce/go/services/gateway-bff/internal/adapter/httpapi"
	"github.com/billionairedev24/diaspora-commerce/go/services/gateway-bff/internal/adapter/httpclient"
	"github.com/billionairedev24/diaspora-commerce/go/services/gateway-bff/internal/adapter/upstreams"
	"github.com/billionairedev24/diaspora-commerce/go/services/gateway-bff/internal/app"
	"github.com/billionairedev24/diaspora-commerce/go/services/gateway-bff/internal/port"
)

// deptStaff: an approver (Operations Manager: applications:review and :approve), a reviewer who may only read and
// Customer Support, who has neither.
var deptStaff = map[string][]any{
	"approver": {[]string{"platform.operations_manager"}, []string{"applications:review", "applications:approve"}},
	"reviewer": {[]string{"platform.customer_support"}, []string{"applications:review"}},
	"nobody":   {[]string{"platform.marketing"}, []string{"support:respond"}},
}

// fakeDepartments serves identity (who is asking, people's names) and the seller service's department routes.
type fakeDepartments struct {
	mu       sync.Mutex
	calls    []string
	bodies   map[string]map[string]any
	requests map[string]map[string]any
	scope    map[string][]string
	tick     int
}

func newFakeDepartments() *fakeDepartments {
	req := func(id, org, dept, status, by, at string, extra map[string]any) map[string]any {
		m := map[string]any{"id": id, "orgId": org, "departmentCode": dept, "status": status, "requestedBy": by, "createdAt": at, "needs": []string{}}
		for k, v := range extra {
			m[k] = v
		}
		return m
	}
	return &fakeDepartments{
		bodies: map[string]map[string]any{},
		requests: map[string]map[string]any{
			"7d6b1d2a-0001-4a6e-9c11-000000000001": req("7d6b1d2a-0001-4a6e-9c11-000000000001", "org_ade", "fashion", "PENDING", "usr_ade", "2026-10-30T09:15:00Z",
				map[string]any{"note": "We also sell ankara tote bags and head wraps."}),
			"7d6b1d2a-0002-4a6e-9c11-000000000002": req("7d6b1d2a-0002-4a6e-9c11-000000000002", "org_efe", "beauty", "PENDING", "usr_efe", "2026-11-01T14:40:00Z",
				map[string]any{"note": "Shea butter and hair oils.", "needs": []string{"BEAUTY_LABELLING"}}),
			"7d6b1d2a-0003-4a6e-9c11-000000000003": req("7d6b1d2a-0003-4a6e-9c11-000000000003", "org_kofi", "groceries", "PENDING", "usr_kofi", "2026-10-28T08:00:00Z",
				map[string]any{"needs": []string{"FOOD_PERMIT"}}),
			"7d6b1d2a-0004-4a6e-9c11-000000000004": req("7d6b1d2a-0004-4a6e-9c11-000000000004", "org_kofi", "fashion", "APPROVED", "usr_kofi", "2026-10-20T11:00:00Z",
				map[string]any{"decidedBy": "stf-approver", "decidedAt": "2026-10-21T10:00:00Z", "decisionNote": "Welcome to Fashion."}),
			"7d6b1d2a-0005-4a6e-9c11-000000000005": req("7d6b1d2a-0005-4a6e-9c11-000000000005", "org_efe", "groceries", "REJECTED", "usr_efe", "2026-10-22T16:30:00Z",
				map[string]any{"decidedBy": "stf-approver", "decidedAt": "2026-10-23T09:00:00Z", "decisionNote": "Upload your food permit first."}),
		},
		scope: map[string][]string{"org_ade": {"groceries"}, "org_efe": {"beauty"}, "org_kofi": {"fashion", "home-art"}},
	}
}

var deptNames = map[string]string{"org_ade": "Ade's Kitchen", "org_efe": "Mama Efe Braids", "org_kofi": "Kofi Textiles", "org_afrotransact_retail": "AfroTransact Retail"}

func (f *fakeDepartments) problem(w http.ResponseWriter, status int, code, detail string) {
	w.Header().Set("Content-Type", "application/problem+json")
	w.WriteHeader(status)
	_ = json.NewEncoder(w).Encode(map[string]any{"type": "about:blank", "title": code, "status": status, "code": code, "detail": detail})
}

func (f *fakeDepartments) withNeeds(r map[string]any) map[string]any {
	out := map[string]any{}
	for k, v := range r {
		out[k] = v
	}
	if out["status"] == "REJECTED" {
		out["needs"] = []string{}
	}
	return out
}

func (f *fakeDepartments) queue(status string) []any {
	items := []map[string]any{}
	for _, r := range f.requests {
		if r["status"] == status {
			items = append(items, f.withNeeds(r))
		}
	}
	key := "createdAt"
	if status != "PENDING" {
		key = "decidedAt"
	}
	slices.SortFunc(items, func(a, b map[string]any) int {
		if status == "PENDING" {
			return strings.Compare(a[key].(string), b[key].(string))
		}
		return strings.Compare(b[key].(string), a[key].(string))
	})
	out := make([]any, 0, len(items))
	for _, it := range items {
		out = append(out, it)
	}
	return out
}

func (f *fakeDepartments) ServeHTTP(w http.ResponseWriter, r *http.Request) {
	f.mu.Lock()
	defer f.mu.Unlock()
	p := r.URL.Path
	token := strings.TrimPrefix(r.Header.Get("Authorization"), "Bearer ")
	f.calls = append(f.calls, r.Method+" "+p+"?"+r.URL.RawQuery)
	var body map[string]any
	if r.Body != nil && (r.Method == http.MethodPost || r.Method == http.MethodPut) {
		_ = json.NewDecoder(r.Body).Decode(&body)
		f.bodies[r.Method+" "+p] = body
	}
	switch {
	case p == "/v1/me":
		who, ok := deptStaff[token]
		if !ok {
			writeJ(w, 200, map[string]any{"id": "cus-1", "principalType": "CUSTOMER", "roles": []string{"account.customer"}})
			return
		}
		writeJ(w, 200, map[string]any{"id": "stf-" + token, "firstName": "Test", "lastName": token, "email": token + "@afrotransact.test",
			"principalType": "STAFF", "roles": who[0], "permissions": who[1]})
	case p == "/v1/admin/module-access":
		f.problem(w, 403, "identity.forbidden", "no")
	case strings.HasPrefix(p, "/v1/admin/users/"):
		id := strings.TrimPrefix(p, "/v1/admin/users/")
		names := map[string][2]string{"usr_ade": {"Adebayo", "Ogunleye"}, "usr_efe": {"Efe", "Okonkwo"}, "usr_kofi": {"Kofi", "Mensah"}, "stf-approver": {"Amara", "Diallo"}}
		n, ok := names[id]
		if !ok {
			f.problem(w, 404, "identity.user-not-found", "no")
			return
		}
		writeJ(w, 200, map[string]any{"id": id, "firstName": n[0], "lastName": n[1], "email": id + "@example.test"})
	case p == "/v1/orgs/directory":
		items := []any{}
		for id, name := range deptNames {
			items = append(items, map[string]any{"id": id, "displayName": name})
		}
		writeJ(w, 200, map[string]any{"items": items, "total": len(items), "page": 0, "size": 200})
	case p == "/v1/departments":
		writeJ(w, 200, []any{
			map[string]any{"code": "groceries", "name": "Groceries & Food", "requires": []string{"FOOD_PERMIT"}},
			map[string]any{"code": "beauty", "name": "Beauty & Personal Care", "requires": []string{"BEAUTY_LABELLING"}},
			map[string]any{"code": "fashion", "name": "Fashion", "requires": []string{}},
			map[string]any{"code": "home-art", "name": "Home & Art", "requires": []string{}},
		})
	case p == "/v1/department-requests" && r.Method == http.MethodGet:
		writeJ(w, 200, f.queue(r.URL.Query().Get("status")))
	case strings.HasPrefix(p, "/v1/department-requests/") && strings.HasSuffix(p, "/decision"):
		id := strings.TrimSuffix(strings.TrimPrefix(p, "/v1/department-requests/"), "/decision")
		req, ok := f.requests[id]
		if !ok {
			f.problem(w, 404, "seller.department-request-not-found", "Department request not found")
			return
		}
		if req["status"] != "PENDING" {
			f.problem(w, 409, "seller.department-request-decided", "This request was already "+strings.ToLower(req["status"].(string)))
			return
		}
		approve, _ := body["approve"].(bool)
		if !approve && strings.TrimSpace(body["note"].(string)) == "" {
			f.problem(w, 422, "seller.reason-required", "Say why the request is rejected: the seller reads it")
			return
		}
		req["status"] = map[bool]string{true: "APPROVED", false: "REJECTED"}[approve]
		req["decidedBy"], req["decidedAt"] = "stf-"+token, "2026-11-03T10:00:00Z"
		if note, ok := body["note"].(string); ok && note != "" {
			req["decisionNote"] = note
		}
		if approve {
			org := req["orgId"].(string)
			f.scope[org] = append(f.scope[org], req["departmentCode"].(string))
		}
		writeJ(w, 200, f.withNeeds(req))
	case strings.HasPrefix(p, "/v1/department-requests/"):
		req, ok := f.requests[strings.TrimPrefix(p, "/v1/department-requests/")]
		if !ok {
			f.problem(w, 404, "seller.department-request-not-found", "Department request not found")
			return
		}
		writeJ(w, 200, f.withNeeds(req))
	case strings.HasSuffix(p, "/catalog-scope/departments") && r.Method == http.MethodPut:
		org := strings.TrimSuffix(strings.TrimPrefix(p, "/v1/orgs/"), "/catalog-scope/departments")
		list := []string{}
		for _, c := range body["departments"].([]any) {
			list = append(list, c.(string))
		}
		f.scope[org] = list
		writeJ(w, 200, f.scopeOf(org))
	case strings.HasSuffix(p, "/catalog-scope"):
		org := strings.TrimSuffix(strings.TrimPrefix(p, "/v1/orgs/"), "/catalog-scope")
		if _, ok := deptNames[org]; !ok {
			f.problem(w, 404, "seller.org-not-found", "Organisation not found")
			return
		}
		writeJ(w, 200, f.scopeOf(org))
	default:
		f.problem(w, 404, "not-found", "no")
	}
}

func (f *fakeDepartments) scopeOf(org string) map[string]any {
	if org == "org_afrotransact_retail" {
		return map[string]any{"orgId": org, "exempt": true, "departments": []string{}, "requests": []any{}}
	}
	reqs := []any{}
	for _, r := range f.requests {
		if r["orgId"] == org {
			reqs = append(reqs, f.withNeeds(r))
		}
	}
	deps := slices.Clone(f.scope[org])
	slices.Sort(deps)
	return map[string]any{"orgId": org, "exempt": false, "departments": deps, "requests": reqs}
}

func (f *fakeDepartments) called(sub string) bool {
	f.mu.Lock()
	defer f.mu.Unlock()
	for _, c := range f.calls {
		if strings.Contains(c, sub) {
			return true
		}
	}
	return false
}

func (f *fakeDepartments) bodyOf(key string) map[string]any {
	f.mu.Lock()
	defer f.mu.Unlock()
	return f.bodies[key]
}

func departmentsBFF(t *testing.T, f *fakeDepartments) func(token, method, path string, body any) *httptest.ResponseRecorder {
	t.Helper()
	srv := httptest.NewServer(f)
	t.Cleanup(srv.Close)
	metrics := httpclient.NewMetrics(prometheus.NewRegistry())
	up := func(name string) port.StaffUpstream {
		return upstreams.NewStaffUpstream(httpclient.New(httpclient.Options{Name: name, BaseURL: srv.URL, Timeout: 3 * time.Second, Metrics: metrics, Transport: http.DefaultTransport}))
	}
	log := slog.New(slog.NewTextHandler(io.Discard, nil))
	d := app.StaffDeps{Up: port.StaffUpstreams{Identity: up("identity"), Seller: up("seller")},
		Matrix: upstreams.NewOrgAccess(httpclient.New(httpclient.Options{Name: "identity", BaseURL: srv.URL, Metrics: metrics, Transport: http.DefaultTransport})),
		Log:    log, Background: func(fn func()) { fn() }}
	staff := httpapi.StaffServices{Shell: app.NewStaffShellService(d), Records: app.NewStaffRecordsService(d), Dashboard: app.NewStaffDashboardService(d),
		Exports: app.NewStaffExportsService(d), Support: app.NewStaffSupportService(d), Departments: app.NewStaffDepartmentsService(d)}
	mux := http.NewServeMux()
	httpapi.New(httpapi.Services{Sessions: staffSessions{}, Staff: staff}, nil, httpapi.SecurityOptions{AllowedOrigins: []string{"http://localhost:3002"}}, log).Register(mux)
	return func(token, method, path string, body any) *httptest.ResponseRecorder {
		var rd io.Reader
		if body != nil {
			raw, _ := json.Marshal(body)
			rd = bytes.NewReader(raw)
		}
		req := httptest.NewRequestWithContext(context.Background(), method, "http://bff.internal"+path, rd)
		if body != nil {
			req.Header.Set("Content-Type", "application/json")
		}
		if token != "" {
			req.Header.Set("Authorization", "Bearer "+token)
		}
		rec := httptest.NewRecorder()
		mux.ServeHTTP(rec, req)
		return rec
	}
}

const (
	reqAde  = "7d6b1d2a-0001-4a6e-9c11-000000000001"
	reqEfe  = "7d6b1d2a-0002-4a6e-9c11-000000000002"
	reqKofi = "7d6b1d2a-0003-4a6e-9c11-000000000003"
	reqDone = "7d6b1d2a-0004-4a6e-9c11-000000000004"
	reqNo   = "7d6b1d2a-0005-4a6e-9c11-000000000005"
)

// TestStaffDepartmentRequests drives the queue, one request, both decisions and an organisation's departments through
// the BFF against a fake seller service, with every answer checked against gateway-bff-staff.yaml.
func TestStaffDepartmentRequests(t *testing.T) {
	doc, err := openapi3.NewLoader().LoadFromFile(staffContractPath)
	if err != nil {
		t.Fatalf("load contract: %v", err)
	}
	doc.Servers = openapi3.Servers{{URL: "http://bff.internal"}}
	router, err := gorillamux.NewRouter(doc)
	if err != nil {
		t.Fatal(err)
	}
	f := newFakeDepartments()
	raw := departmentsBFF(t, f)
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
		in := &openapi3filter.ResponseValidationInput{
			RequestValidationInput: &openapi3filter.RequestValidationInput{Request: req, PathParams: params, Route: route,
				Options: &openapi3filter.Options{AuthenticationFunc: openapi3filter.NoopAuthenticationFunc}},
			Status: rec.Code, Header: rec.Header(), Body: io.NopCloser(bytes.NewReader(rec.Body.Bytes())),
			Options: &openapi3filter.Options{IncludeResponseStatus: true, MultiError: true},
		}
		if err := openapi3filter.ValidateResponse(context.Background(), in); err != nil {
			t.Errorf("%s %s → %d does not match the contract: %v\nbody: %s", method, path, rec.Code, err, rec.Body.String())
		}
		if rec.Body.Len() == 0 {
			return nil
		}
		return jsonOf(t, rec)
	}
	orgs := func(m map[string]any) []string {
		out := []string{}
		for _, it := range m["items"].([]any) {
			row := it.(map[string]any)
			out = append(out, row["orgName"].(string)+" / "+row["departmentName"].(string))
		}
		return out
	}
	same := func(got, want []string) bool { return strings.Join(got, "|") == strings.Join(want, "|") }

	t.Run("the pending tab lists the oldest request first, with names and no ids", func(t *testing.T) {
		m := do("approver", "GET", "/v1/staff/department-requests", nil, 200)
		want := []string{"Kofi Textiles / Groceries & Food", "Ade's Kitchen / Fashion", "Mama Efe Braids / Beauty & Personal Care"}
		if !same(orgs(m), want) {
			t.Errorf("pending = %v", orgs(m))
		}
		if m["total"] != float64(3) || m["pending"] != float64(3) || m["canDecide"] != true || m["truncated"] != false {
			t.Errorf("page = %v", m)
		}
		first := m["items"].([]any)[1].(map[string]any)
		if first["requestedByName"] != "Adebayo Ogunleye" || first["status"] != "PENDING" || first["decidedByName"] != nil {
			t.Errorf("row = %v", first)
		}
	})

	t.Run("the decided tab joins approved and rejected, newest decision first", func(t *testing.T) {
		m := do("approver", "GET", "/v1/staff/department-requests?status=DECIDED", nil, 200)
		if !same(orgs(m), []string{"Mama Efe Braids / Groceries & Food", "Kofi Textiles / Fashion"}) {
			t.Errorf("decided = %v", orgs(m))
		}
		row := m["items"].([]any)[0].(map[string]any)
		if row["decidedByName"] != "Amara Diallo" || row["decidedAt"] == nil {
			t.Errorf("decided row = %v", row)
		}
		if m["pending"] != float64(3) {
			t.Errorf("the pending count follows the open queue, not the tab: %v", m["pending"])
		}
		rej := do("approver", "GET", "/v1/staff/department-requests?status=REJECTED", nil, 200)
		if !same(orgs(rej), []string{"Mama Efe Braids / Groceries & Food"}) {
			t.Errorf("rejected = %v", orgs(rej))
		}
	})

	t.Run("filters by department name, organisation name and the days the request was made", func(t *testing.T) {
		if m := do("approver", "GET", "/v1/staff/department-requests?department=beauty", nil, 200); !same(orgs(m), []string{"Mama Efe Braids / Beauty & Personal Care"}) {
			t.Errorf("department filter = %v", orgs(m))
		}
		if m := do("approver", "GET", "/v1/staff/department-requests?organisation=ADE", nil, 200); !same(orgs(m), []string{"Ade's Kitchen / Fashion"}) {
			t.Errorf("organisation filter = %v", orgs(m))
		}
		m := do("approver", "GET", "/v1/staff/department-requests?from=2026-10-29&to=2026-10-31", nil, 200)
		if !same(orgs(m), []string{"Ade's Kitchen / Fashion"}) || m["total"] != float64(1) || m["pending"] != float64(3) {
			t.Errorf("date range = %v", m)
		}
	})

	t.Run("sorts and pages on the server", func(t *testing.T) {
		m := do("approver", "GET", "/v1/staff/department-requests?sort=organisation,asc&size=2", nil, 200)
		if !same(orgs(m), []string{"Ade's Kitchen / Fashion", "Kofi Textiles / Groceries & Food"}) || m["total"] != float64(3) {
			t.Errorf("by organisation = %v", orgs(m))
		}
		p2 := do("approver", "GET", "/v1/staff/department-requests?sort=organisation,asc&size=2&page=2", nil, 200)
		if !same(orgs(p2), []string{"Mama Efe Braids / Beauty & Personal Care"}) || p2["page"] != float64(2) {
			t.Errorf("page 2 = %v", orgs(p2))
		}
		desc := do("approver", "GET", "/v1/staff/department-requests?sort=department,desc", nil, 200)
		if orgs(desc)[0] != "Kofi Textiles / Groceries & Food" {
			t.Errorf("by department desc = %v", orgs(desc))
		}
	})

	t.Run("bad filters are field errors", func(t *testing.T) {
		for _, q := range []string{"status=MAYBE", "from=31-10-2026", "from=2026-11-02&to=2026-11-01", "sort=requestedBy", "size=500"} {
			do("approver", "GET", "/v1/staff/department-requests?"+q, nil, 422)
		}
	})

	t.Run("a request shows the compliance prompts and whether the organisation's data satisfies them", func(t *testing.T) {
		m := do("approver", "GET", "/v1/staff/department-requests/"+reqKofi, nil, 200)
		prompts := m["prompts"].([]any)
		if len(prompts) != 1 || prompts[0].(map[string]any)["requirement"] != "FOOD_PERMIT" || prompts[0].(map[string]any)["satisfied"] != false {
			t.Errorf("food permit prompt = %v", prompts)
		}
		if m["orgName"] != "Kofi Textiles" || m["departmentName"] != "Groceries & Food" || m["note"] != nil {
			t.Errorf("request = %v", m)
		}
		cur := []string{}
		for _, d := range m["currentDepartments"].([]any) {
			cur = append(cur, d.(map[string]any)["name"].(string))
		}
		if !same(cur, []string{"Fashion", "Home & Art"}) {
			t.Errorf("current departments = %v", cur)
		}
		ade := do("approver", "GET", "/v1/staff/department-requests/"+reqAde, nil, 200)
		if len(ade["prompts"].([]any)) != 0 || ade["note"] != "We also sell ankara tote bags and head wraps." {
			t.Errorf("fashion asks for nothing: %v", ade)
		}
		// Satisfied once the seller's compliance data covers it.
		f.mu.Lock()
		f.requests[reqEfe]["needs"] = []string{}
		f.mu.Unlock()
		efe := do("approver", "GET", "/v1/staff/department-requests/"+reqEfe, nil, 200)
		if p := efe["prompts"].([]any)[0].(map[string]any); p["requirement"] != "BEAUTY_LABELLING" || p["satisfied"] != true {
			t.Errorf("beauty labelling prompt = %v", p)
		}
		no := do("approver", "GET", "/v1/staff/department-requests/"+reqNo, nil, 200)
		if p := no["prompts"].([]any)[0].(map[string]any); p["satisfied"] != nil || no["decisionNote"] != "Upload your food permit first." {
			t.Errorf("a rejected request is not checked: %v", no)
		}
		do("approver", "GET", "/v1/staff/department-requests/7d6b1d2a-9999-4a6e-9c11-000000000009", nil, 404)
	})

	t.Run("only people with the permission see and decide", func(t *testing.T) {
		do("nobody", "GET", "/v1/staff/department-requests", nil, 403)
		do("nobody", "GET", "/v1/staff/organisations/org_ade/departments", nil, 403)
		do("", "GET", "/v1/staff/department-requests", nil, 401)
		if m := do("reviewer", "GET", "/v1/staff/department-requests", nil, 200); m["canDecide"] != false {
			t.Errorf("a reviewer cannot decide: %v", m)
		}
		do("reviewer", "POST", "/v1/staff/department-requests/"+reqAde+"/approve", map[string]any{}, 403)
		do("reviewer", "POST", "/v1/staff/department-requests/"+reqAde+"/reject", map[string]any{"reason": "no"}, 403)
		do("reviewer", "PUT", "/v1/staff/organisations/org_ade/departments", map[string]any{"departments": []string{"fashion"}, "reason": "A long enough reason"}, 403)
		if f.called("/decision") {
			t.Error("a refused staff member must not reach the seller service")
		}
		if g := do("reviewer", "GET", "/v1/staff/organisations/org_ade/departments", nil, 200); g["canEdit"] != false {
			t.Errorf("a reviewer cannot edit: %v", g)
		}
	})

	t.Run("rejecting needs a reason the BFF checks before the seller service is asked", func(t *testing.T) {
		for _, body := range []map[string]any{{}, {"reason": "  "}, {"reason": "no"}} {
			m := do("approver", "POST", "/v1/staff/department-requests/"+reqAde+"/reject", body, 422)
			if m["errors"].(map[string]any)["reason"] == nil {
				t.Errorf("reason error missing: %v", m)
			}
		}
		if f.called("/decision") {
			t.Error("the seller service must not be asked without a reason")
		}
	})

	t.Run("rejecting sends the reason to the seller and the decision shows who decided", func(t *testing.T) {
		m := do("approver", "POST", "/v1/staff/department-requests/"+reqAde+"/reject", map[string]any{"reason": "  Please add your product photos first.  "}, 200)
		if m["status"] != "REJECTED" || m["decisionNote"] != "Please add your product photos first." || m["decidedByName"] != "Amara Diallo" {
			t.Errorf("rejected = %v", m)
		}
		body := f.bodyOf("POST /v1/department-requests/" + reqAde + "/decision")
		if body["approve"] != false || body["note"] != "Please add your product photos first." {
			t.Errorf("seller body = %v", body)
		}
	})

	t.Run("approving adds the department and the note is optional", func(t *testing.T) {
		m := do("approver", "POST", "/v1/staff/department-requests/"+reqKofi+"/approve", map[string]any{"note": "Permit seen by phone."}, 200)
		if m["status"] != "APPROVED" || m["decisionNote"] != "Permit seen by phone." {
			t.Errorf("approved = %v", m)
		}
		names := []string{}
		for _, d := range m["currentDepartments"].([]any) {
			names = append(names, d.(map[string]any)["name"].(string))
		}
		if !same(names, []string{"Fashion", "Groceries & Food", "Home & Art"}) {
			t.Errorf("current departments after approving = %v", names)
		}
		body := f.bodyOf("POST /v1/department-requests/" + reqKofi + "/decision")
		if body["approve"] != true || body["note"] != "Permit seen by phone." {
			t.Errorf("seller body = %v", body)
		}
		bare := do("approver", "POST", "/v1/staff/department-requests/"+reqEfe+"/approve", nil, 200)
		if bare["status"] != "APPROVED" || bare["decisionNote"] != nil {
			t.Errorf("approve without a note = %v", bare)
		}
		if b := f.bodyOf("POST /v1/department-requests/" + reqEfe + "/decision"); b["note"] != nil {
			t.Errorf("no note should be sent: %v", b)
		}
	})

	t.Run("a request somebody already decided is a calm 409 that says what happened", func(t *testing.T) {
		m := do("approver", "POST", "/v1/staff/department-requests/"+reqDone+"/approve", map[string]any{}, 409)
		if m["code"] != "ALREADY_DONE" || m["detail"] != "This request was already approved" {
			t.Errorf("409 = %v", m)
		}
		do("approver", "POST", "/v1/staff/department-requests/7d6b1d2a-9999-4a6e-9c11-000000000009/approve", map[string]any{}, 404)
	})

	t.Run("an organisation's departments are listed by name with the catalog to choose from", func(t *testing.T) {
		m := do("approver", "GET", "/v1/staff/organisations/org_ade/departments", nil, 200)
		if m["orgName"] != "Ade's Kitchen" || m["exempt"] != false || m["canEdit"] != true || len(m["catalog"].([]any)) != 4 {
			t.Errorf("org departments = %v", m)
		}
		if d := m["departments"].([]any); len(d) != 1 || d[0].(map[string]any)["name"] != "Groceries & Food" {
			t.Errorf("departments = %v", d)
		}
		if m["pendingRequests"] != float64(0) {
			t.Errorf("Ade's request was decided above: %v", m["pendingRequests"])
		}
		retail := do("approver", "GET", "/v1/staff/organisations/org_afrotransact_retail/departments", nil, 200)
		if retail["exempt"] != true || retail["canEdit"] != false || retail["orgName"] != "AfroTransact Retail" {
			t.Errorf("retail = %v", retail)
		}
		do("approver", "GET", "/v1/staff/organisations/org_missing/departments", nil, 404)
	})

	t.Run("an override needs a real change, a reason and departments from the catalog", func(t *testing.T) {
		put := func(body map[string]any, want int) map[string]any {
			return do("approver", "PUT", "/v1/staff/organisations/org_ade/departments", body, want)
		}
		if m := put(map[string]any{"departments": []string{"groceries"}, "reason": "Nothing changes here at all"}, 422); m["errors"].(map[string]any)["departments"] == nil {
			t.Errorf("unchanged should be refused on departments: %v", m)
		}
		if m := put(map[string]any{"departments": []string{"groceries", "fashion"}, "reason": "short"}, 422); m["errors"].(map[string]any)["reason"] == nil {
			t.Errorf("a short reason should be refused: %v", m)
		}
		if m := put(map[string]any{"departments": []string{"groceries", "hardware"}, "reason": "Seller asked by phone"}, 422); m["errors"].(map[string]any)["departments"] == nil {
			t.Errorf("an unknown department should be refused: %v", m)
		}
		put(map[string]any{"reason": "Seller asked by phone"}, 422)
		if f.called("PUT /v1/orgs/org_ade") {
			t.Error("a refused override must not reach the seller service")
		}
		m := put(map[string]any{"departments": []string{"fashion", "groceries", "fashion"}, "reason": "  Seller added tailoring, confirmed by phone  "}, 200)
		names := []string{}
		for _, d := range m["departments"].([]any) {
			names = append(names, d.(map[string]any)["name"].(string))
		}
		if !same(names, []string{"Fashion", "Groceries & Food"}) {
			t.Errorf("departments after the override = %v", names)
		}
		body := f.bodyOf("PUT /v1/orgs/org_ade/catalog-scope/departments")
		if body["reason"] != "Seller added tailoring, confirmed by phone" || len(body["departments"].([]any)) != 2 {
			t.Errorf("seller body = %v", body)
		}
		if m := do("approver", "PUT", "/v1/staff/organisations/org_afrotransact_retail/departments", map[string]any{"departments": []string{"fashion"}, "reason": "Retail should not change"}, 409); m["code"] != "CONFLICT" {
			t.Errorf("retail override = %v", m)
		}
	})
}

// TestStaffDepartmentRequestsNav: the entry is in the sidebar for people holding applications:review or :approve, with
// the number of requests waiting as its badge, and hidden for everyone else.
func TestStaffDepartmentRequestsNav(t *testing.T) {
	f := newFakeDepartments()
	raw := departmentsBFF(t, f)
	entry := func(token string) map[string]any {
		rec := raw(token, "GET", "/v1/staff/session", nil)
		if rec.Code == http.StatusForbidden {
			return nil
		}
		if rec.Code != 200 {
			t.Fatalf("session as %s = %d: %s", token, rec.Code, rec.Body.String())
		}
		for _, g := range jsonOf(t, rec)["navGroups"].([]any) {
			for _, m := range g.(map[string]any)["modules"].([]any) {
				if m.(map[string]any)["key"] == "deptRequests" {
					return m.(map[string]any)
				}
			}
		}
		return nil
	}
	if m := entry("approver"); m == nil || m["level"] != "EDIT" || m["badge"] != float64(3) || m["label"] != "Department requests" {
		t.Errorf("approver entry = %v", m)
	}
	if m := entry("reviewer"); m == nil || m["level"] != "VIEW" || m["badge"] != float64(3) {
		t.Errorf("reviewer entry = %v", m)
	}
	if m := entry("nobody"); m != nil {
		t.Errorf("nobody should not see the entry: %v", m)
	}
}
