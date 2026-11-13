package httpapi_test

import (
	_ "embed"
	"go/ast"
	"go/parser"
	"go/token"
	"net/http"
	"net/http/httptest"
	"net/url"
	"os"
	"path/filepath"
	"regexp"
	"slices"
	"sort"
	"strconv"
	"strings"
	"testing"

	"github.com/billionairedev24/diaspora-commerce/go/services/gateway-bff/internal/app"
	"github.com/billionairedev24/diaspora-commerce/go/services/gateway-bff/internal/testkit"
)

// Tenant isolation: OpenFGA is not deployed in production, so what keeps tenant A out of tenant B's data is the BFF's
// use of the session's own org/subject plus the owning services' checks on the user's token. These tests prove it
// per route: tenant_isolation_matrix.txt classifies every registered route, the drift guard fails on an unlisted
// route (or a row without a route), and the kit replays every matrix row as tenant A carrying tenant B's ids and
// spoofed org fields while a recorder in front of the upstreams checks what the BFF actually forwarded.

//go:embed tenant_isolation_matrix.txt
var tenantMatrixText string

type matrixRow struct {
	Method, Pattern, Class, Enforcement, Test string
}

func (r matrixRow) key() string { return r.Method + " " + r.Pattern }

var matrixClasses = []string{"PUBLIC", "CUSTOMER_SELF", "ORG_OWNED", "ORG_SCOPED_LIST", "STAFF_ONLY", "SERVICE_ONLY", "TOKEN_LINK", "WEBHOOK", "UNVERIFIED"}

var kitCases = []string{"kit:seller-matrix", "kit:customer-matrix", "kit:staff-gate", "kit:active-org"}

func parseMatrix(t *testing.T) []matrixRow {
	t.Helper()
	var rows []matrixRow
	for i, line := range strings.Split(tenantMatrixText, "\n") {
		line = strings.TrimSpace(line)
		if line == "" || strings.HasPrefix(line, "#") {
			continue
		}
		parts := strings.Split(line, " | ")
		if len(parts) != 4 {
			t.Fatalf("matrix line %d: want 4 columns `METHOD pattern | CLASS | enforcement | test`: %q", i+1, line)
		}
		mp := strings.SplitN(parts[0], " ", 2)
		if len(mp) != 2 {
			t.Fatalf("matrix line %d: first column must be `METHOD pattern`: %q", i+1, parts[0])
		}
		rows = append(rows, matrixRow{mp[0], mp[1], parts[1], parts[2], parts[3]})
	}
	return rows
}

// registeredRoutes lists every pattern the package registers: string-literal route(...)/mux.Handle(...) calls found
// in the non-test sources (constants resolved), plus the three registration loops (named below) expanded from the
// tables they range over, plus the two spec routes cmd/gateway-bff adds. A registration call with an argument this
// scan cannot resolve, outside the named loops, fails the test: it would escape the matrix.
func registeredRoutes(t *testing.T) []string {
	t.Helper()
	fset := token.NewFileSet()
	pkgs, err := parser.ParseDir(fset, ".", func(fi os.FileInfo) bool { return !strings.HasSuffix(fi.Name(), "_test.go") }, 0)
	if err != nil {
		t.Fatal(err)
	}
	consts := map[string]string{}
	for _, pkg := range pkgs {
		for _, f := range pkg.Files {
			for _, d := range f.Decls {
				gd, ok := d.(*ast.GenDecl)
				if !ok || gd.Tok != token.CONST {
					continue
				}
				for _, s := range gd.Specs {
					vs := s.(*ast.ValueSpec)
					for i, n := range vs.Names {
						if i < len(vs.Values) {
							if bl, ok := vs.Values[i].(*ast.BasicLit); ok && bl.Kind == token.STRING {
								v, _ := strconv.Unquote(bl.Value)
								consts[n.Name] = v
							}
						}
					}
				}
			}
		}
	}
	var eval func(e ast.Expr) (string, bool)
	eval = func(e ast.Expr) (string, bool) {
		switch x := e.(type) {
		case *ast.BasicLit:
			v, err := strconv.Unquote(x.Value)
			return v, err == nil
		case *ast.Ident:
			v, ok := consts[x.Name]
			return v, ok
		case *ast.BinaryExpr:
			l, ok1 := eval(x.X)
			r, ok2 := eval(x.Y)
			return l + r, ok1 && ok2 && x.Op == token.ADD
		}
		return "", false
	}
	// The registration loops, by file: what they range over.
	dynamic := map[string][]string{"handlers_billing.go": nil, "handlers_seller.go": nil, "handlers_staff_inventory.go": nil}
	for _, op := range app.StaffPlanOps {
		dynamic["handlers_billing.go"] = append(dynamic["handlers_billing.go"], op.Route)
	}
	for _, m := range []string{"GET", "POST", "PUT"} {
		dynamic["handlers_seller.go"] = append(dynamic["handlers_seller.go"], m+" /v1/seller/fba/{rest...}")
		for _, a := range []string{"inventory", "retail"} {
			dynamic["handlers_staff_inventory.go"] = append(dynamic["handlers_staff_inventory.go"], m+" /v1/staff/"+a+"/{rest...}")
		}
	}
	seen := map[string]bool{}
	usedDynamic := map[string]bool{}
	for _, pkg := range pkgs {
		for name, f := range pkg.Files {
			base := filepath.Base(name)
			ast.Inspect(f, func(n ast.Node) bool {
				c, ok := n.(*ast.CallExpr)
				if !ok || len(c.Args) == 0 {
					return true
				}
				isRoute := false
				switch fn := c.Fun.(type) {
				case *ast.Ident:
					isRoute = fn.Name == "route"
				case *ast.SelectorExpr:
					isRoute = fn.Sel.Name == "Handle" && len(c.Args) == 2
					if id, ok := fn.X.(*ast.Ident); ok && id.Name != "mux" {
						isRoute = false
					}
				}
				if !isRoute {
					return true
				}
				if v, ok := eval(c.Args[0]); ok {
					seen[v] = true
					return true
				}
				if id, ok := c.Args[0].(*ast.Ident); ok && id.Name == "pattern" {
					return true // the route(...) helper itself forwarding its parameter to mux.Handle
				}
				// A route table loop or a wrapper whose first argument is not a literal.
				if exp, ok := dynamic[base]; ok {
					usedDynamic[base] = true
					for _, r := range exp {
						seen[r] = true
					}
					return true
				}
				pos := fset.Position(c.Pos())
				t.Errorf("%s: route registration with an argument the drift guard cannot resolve; add it to the matrix test's loop table", pos)
				return true
			})
		}
	}
	for base := range dynamic {
		if !usedDynamic[base] {
			t.Errorf("registration loop in %s no longer exists; update the drift guard", base)
		}
	}
	seen["GET /openapi-seller.yaml"], seen["GET /openapi-staff.yaml"] = true, true // cmd/gateway-bff
	out := make([]string, 0, len(seen))
	for r := range seen {
		out = append(out, r)
	}
	sort.Strings(out)
	return out
}

// TestRouteDriftGuard fails when a registered route is not classified in tenant_isolation_matrix.txt, when a matrix
// row has no route, and when a row is malformed or points at a test that does not exist.
func TestRouteDriftGuard(t *testing.T) {
	rows := parseMatrix(t)
	inMatrix := map[string]matrixRow{}
	for _, r := range rows {
		if _, dup := inMatrix[r.key()]; dup {
			t.Errorf("matrix row listed twice: %s", r.key())
		}
		inMatrix[r.key()] = r
		if !slices.Contains(matrixClasses, r.Class) {
			t.Errorf("%s: unknown class %q", r.key(), r.Class)
		}
		if strings.TrimSpace(r.Enforcement) == "" {
			t.Errorf("%s: enforcement is empty", r.key())
		}
		if r.Class == "UNVERIFIED" {
			continue
		}
	}
	registered := registeredRoutes(t)
	reg := map[string]bool{}
	for _, p := range registered {
		reg[p] = true
		if _, ok := inMatrix[p]; !ok {
			t.Errorf("route %q is registered but missing from tenant_isolation_matrix.txt: classify it (PUBLIC, CUSTOMER_SELF, ORG_OWNED, ORG_SCOPED_LIST, STAFF_ONLY, SERVICE_ONLY, TOKEN_LINK, WEBHOOK) and name its test", p)
		}
	}
	for k := range inMatrix {
		if !reg[k] {
			t.Errorf("matrix row %q has no registered route", k)
		}
	}
	unitTests := packageTestNames(t)
	for _, r := range rows {
		switch {
		case strings.HasPrefix(r.Test, "kit:"):
			if !slices.Contains(kitCases, r.Test) {
				t.Errorf("%s: unknown kit case %q", r.key(), r.Test)
			}
		case strings.HasPrefix(r.Test, "unit:"):
			if !unitTests[strings.TrimPrefix(r.Test, "unit:")] {
				t.Errorf("%s: test %q does not exist in package httpapi_test", r.key(), r.Test)
			}
		default:
			t.Errorf("%s: test column must be kit:<case> or unit:<TestName>, got %q", r.key(), r.Test)
		}
	}
}

func packageTestNames(t *testing.T) map[string]bool {
	t.Helper()
	fset := token.NewFileSet()
	pkgs, err := parser.ParseDir(fset, ".", func(fi os.FileInfo) bool { return strings.HasSuffix(fi.Name(), "_test.go") }, 0)
	if err != nil {
		t.Fatal(err)
	}
	out := map[string]bool{}
	for _, p := range pkgs {
		for _, f := range p.Files {
			for _, d := range f.Decls {
				if fd, ok := d.(*ast.FuncDecl); ok && strings.HasPrefix(fd.Name.Name, "Test") {
					out[fd.Name.Name] = true
				}
			}
		}
	}
	return out
}

// TestMatrixRowsAreServedByTheirPattern proves matrix rows are real routes, not strings: on the fully wired test BFF
// (everything except the staff console, which has its own wiring in TestStaffGate) the mux resolves each row's
// concrete path to exactly that pattern.
func TestMatrixRowsAreServedByTheirPattern(t *testing.T) {
	w := testkit.NewTenantWorld(t)
	mux := w.BFF.Handler.(*http.ServeMux)
	matched := 0
	for _, r := range parseMatrix(t) {
		if strings.HasPrefix(r.Pattern, "/v1/staff/") || strings.HasPrefix(r.Pattern, "/openapi-") {
			continue
		}
		req := httptest.NewRequest(r.Method, "http://bff.internal"+fillPath(r.Pattern, w.SellerA, nil), nil)
		_, pat := mux.Handler(req)
		if pat == "" {
			continue // service not wired in the test BFF (optional services); the AST guard still lists it
		}
		matched++
		if pat != r.key() {
			t.Errorf("%s resolves to pattern %q", r.key(), pat)
		}
	}
	if matched < 300 {
		t.Fatalf("only %d matrix rows resolve on the test BFF; wiring regressed", matched)
	}
}

// ---------------------------------------------------------------- the kit runs

var pathParam = regexp.MustCompile(`\{([A-Za-z]+)(\.\.\.)?\}`)

// foreignFor are tenant B's identity values as seen from tenant a.
func foreignFor(w *testkit.TenantWorld) []string {
	return []string{testkit.TenantSellerOrgB, testkit.TenantProviderOrgB, w.SellerB.Sub, w.ProviderB.Sub, w.CustomerB.Sub}
}

// fillPath turns a pattern into a request path in which every path parameter names tenant B's resource (an org or
// user parameter names B's org or user).
func fillPath(pattern string, as testkit.Tenant, pathOK *[]string) string {
	return pathParam.ReplaceAllStringFunc(pattern, func(m string) string {
		name := pathParam.FindStringSubmatch(m)[1]
		v := testkit.TenantResourceB
		switch name {
		case "orgId":
			v = testkit.TenantSellerOrgB
			if as.PT == "PROVIDER_MEMBER" {
				v = testkit.TenantProviderOrgB
			}
		case "userId", "customerId":
			v = "user-seller-b"
		case "rest":
			v = "inbound/" + testkit.TenantResourceB
		}
		if pathOK != nil {
			*pathOK = append(*pathOK, v)
		}
		return v
	})
}

// spoofCall is the probe: tenant B's ids in the path, in every query parameter an org or user could be named by, in
// the body (top level and nested) and in headers.
func spoofCall(r matrixRow, as testkit.Tenant, w *testkit.TenantWorld) (testkit.Call, testkit.Foreign) {
	var pathOK []string
	path := fillPath(r.Pattern, as, &pathOK)
	orgB, userB := testkit.TenantSellerOrgB, "user-seller-b"
	if as.PT == "PROVIDER_MEMBER" {
		orgB = testkit.TenantProviderOrgB
	}
	if as.PT == "CUSTOMER" {
		userB = w.CustomerB.Sub
	}
	fields := map[string]any{}
	q := url.Values{}
	for _, k := range []string{"orgId", "ownerOrg", "sellerOrgId", "organizationId", "providerId", "org", "ownerOrgId"} {
		fields[k] = orgB
		q.Set(k, orgB)
	}
	for _, k := range []string{"customerId", "userId", "ownerId", "sub", "subject", "createdBy"} {
		fields[k] = userB
		q.Set(k, userB)
	}
	fields["owner"] = map[string]any{"orgId": orgB, "ownerOrg": orgB, "userId": userB}
	fields["items"] = []any{map[string]any{"sellerOrgId": orgB, "id": testkit.TenantResourceB}}
	h := map[string]string{"X-Acting-As": "STAFF", "X-Forwarded-Host": "evil.example"}
	for _, n := range testkit.SpoofHeaders {
		h[n] = orgB
	}
	c := testkit.Call{Method: r.Method, Path: path, Query: q, Headers: h}
	if r.Method != http.MethodGet && r.Method != http.MethodHead {
		c.Body = fields
	}
	f := testkit.Foreign{Identity: foreignFor(w), PathOK: pathOK}
	if r.Class == "TOKEN_LINK" {
		f.PathOK = nil // a link names its own order/location in the path; the token, not the session, authorises it
	}
	return c, f
}

type kitRun struct{ rows, reached, rejected int }

func runMatrix(t *testing.T, w *testkit.TenantWorld, as testkit.Tenant, rows []matrixRow) kitRun {
	t.Helper()
	var run kitRun
	for _, r := range rows {
		call, foreign := spoofCall(r, as, w)
		mark := w.Mark()
		status, body := w.Do(as, call)
		reqs := w.Since(mark)
		run.rows++
		if slices.ContainsFunc(reqs, func(q testkit.Request) bool { return q.Auth.Kind == "user" && q.Path != "/v1/me" }) {
			run.reached++
		}
		if status >= 400 && status < 500 {
			run.rejected++
		}
		if status >= 500 && status != 502 && status != 503 && status != 504 {
			t.Errorf("%s as %s: status %d %s", r.key(), as.Name, status, snippet(body))
		}
		for _, v := range testkit.CheckForwarded(as, foreign, reqs) {
			t.Errorf("%s as %s (HTTP %d): %s", r.key(), as.Name, status, v)
		}
	}
	return run
}

func snippet(b []byte) string {
	s := string(b)
	if len(s) > 160 {
		s = s[:160]
	}
	return s
}

func rowsWhere(t *testing.T, keep func(matrixRow) bool) []matrixRow {
	var out []matrixRow
	for _, r := range parseMatrix(t) {
		if keep(r) {
			out = append(out, r)
		}
	}
	return out
}

// TestSellerMatrix replays every /v1/seller/* matrix row as seller A and as provider A.
func TestSellerMatrix(t *testing.T) {
	w := testkit.NewTenantWorld(t)
	rows := rowsWhere(t, func(r matrixRow) bool {
		return r.Test == "kit:seller-matrix" && r.Pattern != "/v1/seller/session/active-org"
	})
	if len(rows) < 150 {
		t.Fatalf("only %d seller rows", len(rows))
	}
	for _, as := range []testkit.Tenant{w.SellerA, w.ProviderA} {
		run := runMatrix(t, w, as, rows)
		t.Logf("%s: %d rows, %d reached an upstream with the user's token, %d answered 4xx", as.Name, run.rows, run.reached, run.rejected)
		if run.reached < 40 {
			t.Errorf("%s: only %d seller routes reached an upstream, so the isolation check would be vacuous", as.Name, run.reached)
		}
	}
}

// TestCustomerMatrix replays customer, public and token-link rows (carts, addresses, payment methods, orders,
// notification preferences, media uploads, bookings, pay links, ...) as customer A and as seller A shopping.
func TestCustomerMatrix(t *testing.T) {
	w := testkit.NewTenantWorld(t)
	rows := rowsWhere(t, func(r matrixRow) bool { return r.Test == "kit:customer-matrix" })
	if len(rows) < 150 {
		t.Fatalf("only %d customer rows", len(rows))
	}
	for _, as := range []testkit.Tenant{w.CustomerA, w.SellerA} {
		run := runMatrix(t, w, as, rows)
		t.Logf("%s: %d rows, %d reached an upstream with the user's token, %d answered 4xx", as.Name, run.rows, run.reached, run.rejected)
		if run.reached < 40 {
			t.Errorf("%s: only %d customer routes reached an upstream, so the isolation check would be vacuous", as.Name, run.reached)
		}
	}
}

// TestOracleDetectsASpoofThatIsForwarded proves the kit is not vacuous: a probe whose upstream really received tenant
// B's org (a stand-in for a handler that passed ?orgId through) and a service-token call carrying a client id are
// both reported.
func TestOracleDetectsASpoofThatIsForwarded(t *testing.T) {
	w := testkit.NewTenantWorld(t)
	foreign := testkit.Foreign{Identity: []string{testkit.TenantSellerOrgB}, PathOK: []string{testkit.TenantResourceB}}
	leaky := []testkit.Request{{Method: "GET", Path: "/v1/offers", Query: "orgId=" + testkit.TenantSellerOrgB,
		Auth: testkit.Bearer{Kind: "user", Sub: w.SellerA.Sub, Org: w.SellerA.Org}}}
	if got := testkit.CheckForwarded(w.SellerA, foreign, leaky); len(got) != 1 {
		t.Fatalf("query spoof: %v", got)
	}
	deputy := []testkit.Request{{Method: "GET", Path: "/v1/assets/" + testkit.TenantResourceB, Auth: testkit.Bearer{Kind: "service", Scope: "media:read"}}}
	if got := testkit.CheckForwarded(w.SellerA, foreign, deputy); len(got) != 1 {
		t.Fatalf("confused deputy: %v", got)
	}
	swapped := []testkit.Request{{Method: "GET", Path: "/v1/me", Auth: testkit.Bearer{Kind: "user", Sub: w.SellerB.Sub, Org: w.SellerB.Org}}}
	if got := testkit.CheckForwarded(w.SellerA, foreign, swapped); len(got) != 1 {
		t.Fatalf("token swap: %v", got)
	}
	hdr := []testkit.Request{{Method: "GET", Path: "/v1/x", Header: http.Header{"X-Org-Id": {"a"}}, Auth: testkit.Bearer{Kind: "user", Sub: w.SellerA.Sub, Org: w.SellerA.Org}}}
	if got := testkit.CheckForwarded(w.SellerA, foreign, hdr); len(got) != 1 {
		t.Fatalf("header spoof: %v", got)
	}
	clean := []testkit.Request{{Method: "GET", Path: "/v1/orders/" + testkit.TenantResourceB, Auth: testkit.Bearer{Kind: "user", Sub: w.SellerA.Sub, Org: w.SellerA.Org}}}
	if got := testkit.CheckForwarded(w.SellerA, foreign, clean); len(got) != 0 {
		t.Fatalf("a resource id in the path with A's own token is the owning service's decision: %v", got)
	}
}

// TestSellerSpoofsAreOverwrittenWhereTheBFFRelays pins the three places the BFF forwards a client-influenced org:
// FBA (ownerOrg on reads is the session org; ownerOrg in a body is dropped) and run pick lists (orgId = session org).
func TestSellerSpoofsAreOverwrittenWhereTheBFFRelays(t *testing.T) {
	w := testkit.NewTenantWorld(t)
	b := testkit.TenantSellerOrgB
	cases := []struct {
		name string
		call testkit.Call
		want func(r testkit.Request) bool
	}{
		{"fba inbound list", testkit.Call{Method: "GET", Path: "/v1/seller/fba/inbound", Query: url.Values{"ownerOrg": {b}}},
			func(r testkit.Request) bool {
				return r.Path == "/v1/inbound/shipments" && strings.Contains(r.Query, "ownerOrg="+testkit.TenantSellerOrgA)
			}},
		{"fba lots list", testkit.Call{Method: "GET", Path: "/v1/seller/fba/lots", Query: url.Values{"ownerOrg": {b}}},
			func(r testkit.Request) bool {
				return r.Path == "/v1/lots" && strings.Contains(r.Query, "ownerOrg="+testkit.TenantSellerOrgA)
			}},
		{"fba create", testkit.Call{Method: "POST", Path: "/v1/seller/fba/inbound", Body: map[string]any{"ownerOrg": b, "OwnerOrg": b, "OWNERORG": b, "nodeId": "n1"}},
			func(r testkit.Request) bool { return r.Path == "/v1/inbound/shipments" && r.Method == "POST" }},
		{"run pickups", testkit.Call{Method: "GET", Path: "/v1/seller/run-pickups", Query: url.Values{"orgId": {b}}},
			func(r testkit.Request) bool {
				return r.Path == "/v1/seller/run-pickups" && strings.Contains(r.Query, "orgId="+testkit.TenantSellerOrgA)
			}},
	}
	for _, c := range cases {
		mark := w.Mark()
		status, body := w.Do(w.SellerA, c.call)
		reqs := w.Since(mark)
		for _, v := range testkit.CheckForwarded(w.SellerA, testkit.Foreign{Identity: []string{b}}, reqs) {
			t.Errorf("%s (HTTP %d %s): %s", c.name, status, snippet(body), v)
		}
		if !slices.ContainsFunc(reqs, c.want) {
			t.Errorf("%s: expected an upstream call with the session org; got %d requests (HTTP %d %s)", c.name, len(reqs), status, snippet(body))
		}
	}
}
