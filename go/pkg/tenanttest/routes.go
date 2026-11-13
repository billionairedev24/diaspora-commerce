package tenanttest

import (
	"fmt"
	"go/ast"
	"go/parser"
	"go/token"
	"os"
	"path/filepath"
	"regexp"
	"slices"
	"sort"
	"strconv"
	"strings"
	"testing"
)

// Class is the isolation class of a route: who may call it and what scopes the data it touches.
type Class string

// Isolation classes.
const (
	// Public routes serve data that is public by design (catalogue, geography); no tenant data may be in the answer.
	Public Class = "PUBLIC"
	// CustomerSelf routes act on the caller's own shopper data, keyed by the verified token subject.
	CustomerSelf Class = "CUSTOMER_SELF"
	// OrgOwned routes act on one resource that belongs to an organisation; the owner is checked against the verified
	// org claim (or the staff permission) before the resource is read or changed.
	OrgOwned Class = "ORG_OWNED"
	// OrgScopedList routes list resources; the organisation filter comes from the verified claim, never the client.
	OrgScopedList Class = "ORG_SCOPED_LIST"
	// StaffOnly routes need a staff permission no organisation member or shopper holds.
	StaffOnly Class = "STAFF_ONLY"
	// ServiceOnly routes need a service permission (client credentials) no person holds.
	ServiceOnly Class = "SERVICE_ONLY"
	// TokenLink routes are authorised by an unguessable signed token in the URL.
	TokenLink Class = "TOKEN_LINK"
	// Webhook routes are authorised by a provider signature or credential over the raw body.
	Webhook Class = "WEBHOOK"
	// Unverified routes have no isolation proof yet; each must say why in its enforcement column.
	Unverified Class = "UNVERIFIED"
)

var classes = []Class{Public, CustomerSelf, OrgOwned, OrgScopedList, StaffOnly, ServiceOnly, TokenLink, Webhook, Unverified}

// nonRouteMethods mark matrix rows that document an entry point that is not an HTTP route (a Kafka consumer, a job).
var nonRouteMethods = []string{"EVENT", "JOB", "GRPC"}

// Row is one line of tenant_isolation_matrix.txt.
type Row struct {
	Method, Pattern string
	Class           Class
	Enforcement     string
	Test            string
}

// Route is the mux pattern of the row ("METHOD pattern").
func (r Row) Route() string { return r.Method + " " + r.Pattern }

// IsRoute reports whether the row documents an HTTP route.
func (r Row) IsRoute() bool { return !slices.Contains(nonRouteMethods, r.Method) }

// ParseMatrix parses the matrix: one row per line, "METHOD pattern | CLASS | enforcement | test"; blank lines and lines
// starting with # are ignored.
func ParseMatrix(text string) ([]Row, error) {
	var rows []Row
	for i, line := range strings.Split(text, "\n") {
		line = strings.TrimSpace(line)
		if line == "" || strings.HasPrefix(line, "#") {
			continue
		}
		parts := strings.Split(line, "|")
		if len(parts) != 4 {
			return nil, fmt.Errorf("matrix line %d: want 'METHOD pattern | CLASS | enforcement | test', got %q", i+1, line)
		}
		method, pattern, ok := strings.Cut(strings.TrimSpace(parts[0]), " ")
		if !ok {
			return nil, fmt.Errorf("matrix line %d: %q is not 'METHOD pattern'", i+1, parts[0])
		}
		row := Row{Method: strings.TrimSpace(method), Pattern: strings.TrimSpace(pattern), Class: Class(strings.TrimSpace(parts[1])),
			Enforcement: strings.TrimSpace(parts[2]), Test: strings.TrimSpace(parts[3])}
		if !slices.Contains(classes, row.Class) {
			return nil, fmt.Errorf("matrix line %d: unknown class %q", i+1, row.Class)
		}
		if row.Enforcement == "" {
			return nil, fmt.Errorf("matrix line %d: %s has no enforcement", i+1, row.Route())
		}
		rows = append(rows, row)
	}
	return rows, nil
}

var routeLiteral = regexp.MustCompile(`^(GET|POST|PUT|PATCH|DELETE|HEAD|OPTIONS) /\S*$`)

// PatternsFromSource lists the mux patterns a service registers, read from the string literals "METHOD /path" in the
// non-test Go files under dirs (the service's httpapi packages). Go's ServeMux cannot list its patterns, and the
// literals are exactly what the routes are registered with.
func PatternsFromSource(t testing.TB, dirs ...string) []string {
	t.Helper()
	seen := map[string]bool{}
	for _, dir := range dirs {
		err := filepath.WalkDir(dir, func(path string, d os.DirEntry, err error) error {
			if err != nil {
				return fmt.Errorf("walk %s: %w", path, err)
			}
			if d.IsDir() || !strings.HasSuffix(path, ".go") || strings.HasSuffix(path, "_test.go") {
				return nil
			}
			f, perr := parser.ParseFile(token.NewFileSet(), path, nil, 0)
			if perr != nil {
				return fmt.Errorf("parse %s: %w", path, perr)
			}
			ast.Inspect(f, func(n ast.Node) bool {
				lit, ok := n.(*ast.BasicLit)
				if !ok || lit.Kind != token.STRING {
					return true
				}
				if s, uerr := strconv.Unquote(lit.Value); uerr == nil && routeLiteral.MatchString(s) {
					seen[s] = true
				}
				return true
			})
			return nil
		})
		if err != nil {
			t.Fatalf("tenanttest: scanning %s for routes: %v", dir, err)
		}
	}
	out := make([]string, 0, len(seen))
	for p := range seen {
		out = append(out, p)
	}
	sort.Strings(out)
	return out
}

// CheckRoutes is the route-drift guard. It fails when
//   - a registered pattern has no row in the matrix (a new route needs an isolation decision),
//   - a route row names no registered pattern (the matrix is stale),
//   - a row's test column names a Test function that does not exist in testDirs ("n/a" is allowed for PUBLIC, WEBHOOK,
//     TOKEN_LINK and non-route rows only), or
//   - a route is UNVERIFIED without a reason.
func CheckRoutes(t testing.TB, patterns []string, matrix string, testDirs ...string) {
	t.Helper()
	rows, err := ParseMatrix(matrix)
	if err != nil {
		t.Fatalf("tenanttest: %v", err)
	}
	registered := map[string]bool{}
	for _, p := range patterns {
		registered[strings.Join(strings.Fields(p), " ")] = true
	}
	documented := map[string]bool{}
	tests := testFuncs(t, testDirs)
	for _, r := range rows {
		if r.IsRoute() {
			documented[r.Route()] = true
			if !registered[r.Route()] {
				t.Errorf("matrix row %q names a route the service does not register: remove or fix it", r.Route())
			}
		}
		name, _, _ := strings.Cut(r.Test, "/")
		switch {
		case r.Test == "":
			t.Errorf("matrix row %q has an empty test column (use n/a where none applies)", r.Route())
		case r.Test == "n/a":
			if r.IsRoute() && !slices.Contains([]Class{Public, Webhook, TokenLink}, r.Class) {
				t.Errorf("matrix row %q is %s and must name its cross-tenant test", r.Route(), r.Class)
			}
		case len(testDirs) > 0 && !tests[name]:
			t.Errorf("matrix row %q names test %q, which does not exist in %v", r.Route(), name, testDirs)
		}
	}
	for p := range registered {
		if !documented[p] {
			t.Errorf("route %q is registered but missing from tenant_isolation_matrix.txt: classify it (PUBLIC, CUSTOMER_SELF, ORG_OWNED, ORG_SCOPED_LIST, STAFF_ONLY, SERVICE_ONLY, TOKEN_LINK, WEBHOOK) and name its test", p)
		}
	}
}

var testFunc = regexp.MustCompile(`(?m)^func (Test\w+)\(`)

func testFuncs(t testing.TB, dirs []string) map[string]bool {
	t.Helper()
	out := map[string]bool{}
	for _, dir := range dirs {
		err := filepath.WalkDir(dir, func(path string, d os.DirEntry, err error) error {
			if err != nil {
				return fmt.Errorf("walk %s: %w", path, err)
			}
			if d.IsDir() || !strings.HasSuffix(path, "_test.go") {
				return nil
			}
			b, rerr := os.ReadFile(path) // #nosec G304 G122 -- test sources of the service under test, read by a test helper
			if rerr != nil {
				return fmt.Errorf("read %s: %w", path, rerr)
			}
			for _, m := range testFunc.FindAllStringSubmatch(string(b), -1) {
				out[m[1]] = true
			}
			return nil
		})
		if err != nil {
			t.Fatalf("tenanttest: scanning tests in %s: %v", dir, err)
		}
	}
	return out
}
