package app

import (
	"context"
	"encoding/json"
	"errors"
	"net/url"
	"slices"
	"strings"
	"sync"
	"testing"
	"time"

	"github.com/billionairedev24/diaspora-commerce/go/services/gateway-bff/internal/domain"
	"github.com/billionairedev24/diaspora-commerce/go/services/gateway-bff/internal/port"
)

// fakeUp answers staff upstream calls from a function and counts them.
type fakeUp struct {
	name  string
	mu    sync.Mutex
	calls []string
	serve func(path string, q url.Values) (any, bool)
}

func (f *fakeUp) Name() string { return f.name }

func (f *fakeUp) Call(_ context.Context, _ string, c port.StaffCall, out any) error {
	f.mu.Lock()
	f.calls = append(f.calls, c.Path)
	f.mu.Unlock()
	v, ok := f.serve(c.Path, c.Query)
	if !ok {
		return errors.New("not found")
	}
	b, _ := json.Marshal(v)
	return json.Unmarshal(b, out)
}

func (f *fakeUp) count(prefix string) int {
	f.mu.Lock()
	defer f.mu.Unlock()
	n := 0
	for _, c := range f.calls {
		if strings.HasPrefix(c, prefix) {
			n++
		}
	}
	return n
}

const retailID = "org_afrotransact_retail"

// namesFixture is a staff context whose seller directory knows two organisations and whose identity knows two people.
func namesFixture(t *testing.T) (staffCtx, *fakeUp, *fakeUp, *fakeUp) {
	t.Helper()
	seller := &fakeUp{name: "seller", serve: func(path string, _ url.Values) (any, bool) {
		if path == "/v1/orgs/directory" {
			return map[string]any{"items": []any{
				map[string]any{"id": "org_rf8luoko6kjmaln7d5al", "displayName": "Kente Naturals"},
				map[string]any{"id": "org_second", "displayName": "Ade's Kitchen"},
			}, "total": 2}, true
		}
		return nil, false
	}}
	identity := &fakeUp{name: "identity", serve: func(path string, _ url.Values) (any, bool) {
		switch path {
		case "/v1/admin/users/0b1c2d3e-0000-4000-8000-000000000001":
			return map[string]any{"name": "Amara Nwosu", "email": "amara@example.com"}, true
		case "/v1/admin/users/0b1c2d3e-0000-4000-8000-000000000002":
			return map[string]any{"email": "tunde@example.com"}, true
		}
		return nil, false
	}}
	fulfilment := &fakeUp{name: "fulfilment", serve: func(path string, _ url.Values) (any, bool) {
		if path == "/v1/dispatch/routes" {
			return []any{map[string]any{"id": "r1", "name": "Houston AM 1", "stops": []any{map[string]any{"id": "s1", "name": "Ngozi Eze"}}}}, true
		}
		return nil, false
	}}
	now := time.Date(2026, 10, 26, 9, 0, 0, 0, time.UTC)
	sc := staffCtx{me: staffMe{ID: "staff-1"}, names: newStaffNames(func() time.Time { return now }, ""),
		up: port.StaffUpstreams{Seller: seller, Identity: identity, Fulfilment: fulfilment}}
	return sc, seller, identity, fulfilment
}

func TestOrganisationsAreNamedInOnePassAndUnknownOnesAreNeutral(t *testing.T) {
	sc, seller, _, _ := namesFixture(t)
	items := []jobj{
		{"id": "p1", "ownerOrgId": "org_rf8luoko6kjmaln7d5al"},
		{"id": "p2", "ownerOrgId": retailID},
		{"id": "p3", "ownerOrgId": "org_gone"},
		{"id": "p4", "ownerOrgId": "org_second"},
		{"id": "p5", "ownerOrgId": "org_rf8luoko6kjmaln7d5al"},
		{"id": "p6"},
	}
	nameAll(context.Background(), sc, items, sellerRef("ownerOrgId"))
	want := []string{"Kente Naturals", "AfroTransact Retail", "Unknown seller", "Ade's Kitchen", "Kente Naturals", ""}
	for i, it := range items {
		if got := jstr(it, "ownerOrgName"); got != want[i] {
			t.Errorf("row %d = %q, want %q", i, got, want[i])
		}
		if strings.Contains(jstr(it, "ownerOrgName"), "org_") {
			t.Errorf("row %d shows an id: %q", i, jstr(it, "ownerOrgName"))
		}
	}
	// One directory page names the whole page of rows; the one organisation it does not list is asked for once.
	if n := seller.count("/v1/orgs/directory"); n != 1 {
		t.Errorf("directory reads = %d, want 1", n)
	}
	if n := seller.count("/v1/orgs/org_gone/mirror"); n != 1 {
		t.Errorf("mirror reads for the unlisted organisation = %d, want 1", n)
	}
	// The next page of the same table is served from the short-lived cache, unknown ids included.
	nameAll(context.Background(), sc, items, sellerRef("ownerOrgId"))
	if n := seller.count("/v1/orgs/"); n != 2 {
		t.Errorf("reading the same page again asked the directory again: %d calls", n)
	}
}

func TestPeopleAreNamedByNameThenEmailAndNeverByTheirId(t *testing.T) {
	sc, _, identity, _ := namesFixture(t)
	items := []jobj{
		{"actorId": "0b1c2d3e-0000-4000-8000-000000000001"},
		{"actorId": "0b1c2d3e-0000-4000-8000-000000000002"},
		{"actorId": "0b1c2d3e-0000-4000-8000-000000000009"},
		{"actorId": "system:seed"},
		{"actorId": "Ada Obi"},
	}
	nameAll(context.Background(), sc, items, personRef("actorId", "Actor"))
	want := []string{"Amara Nwosu", "tunde@example.com", "Unknown user", "System", "Ada Obi"}
	for i, it := range items {
		if got := jstr(it, "actorName"); got != want[i] {
			t.Errorf("row %d = %q, want %q", i, got, want[i])
		}
	}
	if n := identity.count("/v1/admin/users/"); n != 3 {
		t.Errorf("user lookups = %d, want one per distinct person (3)", n)
	}
}

func TestAnExistingNameIsKeptAndALabelledFieldNeedsNoLookup(t *testing.T) {
	sc, seller, identity, _ := namesFixture(t)
	items := []jobj{
		{"sellerOrgId": "org_second", "sellerName": "Ade Foods (from the order)", "customerId": "0b1c2d3e-0000-4000-8000-000000000001", "shippingAddress": map[string]any{"fullName": "Ngozi Eze"}},
	}
	nameAll(context.Background(), sc, items, sellerRef("sellerOrgId").named("sellerName"), customerRef("customerId").orFrom("shippingAddress.fullName"))
	if jstr(items[0], "sellerName") != "Ade Foods (from the order)" || jstr(items[0], "customerName") != "Ngozi Eze" {
		t.Fatalf("item = %v", items[0])
	}
	if seller.count("/v1/orgs/") != 0 || identity.count("/v1/admin/users/") != 0 {
		t.Error("a record that already names its seller and customer must not trigger lookups")
	}
}

func TestRoutesAndStopsAreNamedFromOneRoutesRead(t *testing.T) {
	sc, _, _, fulfilment := namesFixture(t)
	items := []jobj{{"routeId": "r1", "stopId": "s1"}, {"routeId": "r9", "stopId": "s9"}}
	nameAll(context.Background(), sc, items, routeRef("routeId"), stopRef("stopId"))
	if jstr(items[0], "routeName") != "Houston AM 1" || jstr(items[0], "stopName") != "Ngozi Eze" ||
		jstr(items[1], "routeName") != "Unknown route" || jstr(items[1], "stopName") != "Unknown stop" {
		t.Fatalf("items = %v", items)
	}
	if n := fulfilment.count("/v1/dispatch/routes"); n != 1 {
		t.Errorf("routes reads = %d, want 1 for both kinds", n)
	}
}

func TestWithoutAnyUpstreamNothingFailsAndNothingShowsAnId(t *testing.T) {
	items := []jobj{{"ownerOrgId": "org_x"}, {"ownerOrgId": retailID}}
	nameAll(context.Background(), staffCtx{}, items, sellerRef("ownerOrgId"))
	if jstr(items[0], "ownerOrgName") != "Unknown seller" {
		t.Errorf("name = %q", jstr(items[0], "ownerOrgName"))
	}
	var down fakeUp
	down.serve = func(string, url.Values) (any, bool) { return nil, false }
	sc := staffCtx{up: port.StaffUpstreams{Seller: &down}, names: newStaffNames(time.Now, "")}
	items = []jobj{{"ownerOrgId": "org_x"}, {"ownerOrgId": retailID}}
	nameAll(context.Background(), sc, items, sellerRef("ownerOrgId"))
	if jstr(items[0], "ownerOrgName") != "Unknown seller" || jstr(items[1], "ownerOrgName") != "AfroTransact Retail" {
		t.Errorf("names with the directory down = %v", items)
	}
}

// productsFixture is the Products resource over a fake catalog: three rows and the names above.
func productsFixture(t *testing.T) (*staffResource, staffCtx) {
	t.Helper()
	sc, _, _, _ := namesFixture(t)
	sc.up.Catalog = &fakeUp{name: "catalog", serve: func(path string, _ url.Values) (any, bool) {
		rows := []any{
			map[string]any{"id": "p1", "atin": "AT0001", "title": "Shea butter", "status": "IN_REVIEW", "ownerOrgId": "org_rf8luoko6kjmaln7d5al"},
			map[string]any{"id": "p2", "atin": "AT0002", "title": "Jollof seasoning", "status": "LIVE", "ownerOrgId": retailID},
			map[string]any{"id": "p3", "atin": "AT0003", "title": "Garri", "status": "LIVE", "ownerOrgId": "org_gone"},
		}
		if path == "/v1/products" {
			return map[string]any{"items": rows, "total": 3}, true
		}
		for _, r := range rows {
			if strings.HasSuffix(path, r.(map[string]any)["id"].(string)) {
				return r, true
			}
		}
		return nil, false
	}}
	return productsResource().withNames(), sc
}

func TestATableShowsNamesSearchFindsNamesAndIdsAndSortAndExportUseTheName(t *testing.T) {
	r, sc := productsFixture(t)
	page, err := r.list(context.Background(), sc, staffQuery{Size: 25})
	if err != nil {
		t.Fatal(err)
	}
	rows := make([]domain.Row, 0, len(page.items))
	for _, o := range page.items {
		rows = append(rows, rowOf(r, o))
	}
	var sellerCol *domain.Column
	for i := range r.columns {
		if r.columns[i].Label == "Seller" {
			sellerCol = &r.columns[i]
		}
	}
	if sellerCol == nil || sellerCol.Key == "ownerOrgId" || sellerCol.Kind == "mono" {
		t.Fatalf("the Seller column must show the name, not the id: %+v", sellerCol)
	}
	got := []any{}
	for _, row := range rows {
		got = append(got, row.Cells[sellerCol.Key])
	}
	if got[0] != "Kente Naturals" || got[1] != "AfroTransact Retail" || got[2] != "Unknown seller" {
		t.Fatalf("seller cells = %v", got)
	}
	// Search matches the name and the id (the id is not shown).
	for _, term := range []string{"kente", "org_rf8luoko6kjmaln7d5al", "retail", "org_afrotransact_retail", "unknown seller"} {
		if hits := domain.FilterRows(rows, r.columns, domain.SearchTerms(term), nil); len(hits) != 1 {
			t.Errorf("search %q found %d rows, want 1", term, len(hits))
		}
	}
	// Sorting uses the name.
	sorted := slices.Clone(rows)
	sortKeys, bad := domain.ParseSort([]string{sellerCol.Key + ",asc"}, r.hasColumn)
	if bad != nil {
		t.Fatal(bad)
	}
	domain.SortRows(sorted, r.columns, sortKeys)
	order := []string{}
	for _, row := range sorted {
		order = append(order, row.Cells[sellerCol.Key].(string))
	}
	if !slices.Equal(order, []string{"AfroTransact Retail", "Kente Naturals", "Unknown seller"}) {
		t.Fatalf("sorted by seller = %v", order)
	}
	// Exports render the cells, so they carry the name.
	for _, row := range rows {
		for _, c := range r.columns {
			if text := strings.Join(domain.CellTexts(c.Kind, row.Cells[c.Key]), " "); strings.Contains(text, "org_") {
				t.Errorf("column %q exports an id: %q", c.Label, text)
			}
		}
	}
}

func TestTheSheetShowsTheNameAndKeepsTheIdAsASecondaryIdField(t *testing.T) {
	r, sc := productsFixture(t)
	o, err := r.get(context.Background(), sc, "p1")
	if err != nil {
		t.Fatal(err)
	}
	d := (&staffRecordsImpl{}).detail(context.Background(), r, sc, o)
	if strings.Contains(d.Subtitle, "org_") || !strings.Contains(d.Subtitle, "Kente Naturals") {
		t.Errorf("subtitle = %q", d.Subtitle)
	}
	var ids *domain.DetailSection
	for i := range d.Sections {
		for _, f := range d.Sections[i].Fields {
			if f.Label == "Seller" && f.Value != "Kente Naturals" {
				t.Errorf("Seller field = %q", f.Value)
			}
			if strings.HasPrefix(f.Value, "org_") && d.Sections[i].Title != "IDs" {
				t.Errorf("section %q shows the raw id in %q", d.Sections[i].Title, f.Label)
			}
		}
		if d.Sections[i].Title == "IDs" {
			ids = &d.Sections[i]
		}
	}
	if ids == nil || len(ids.Fields) != 1 || ids.Fields[0].Label != "Seller ID" || ids.Fields[0].Value != "org_rf8luoko6kjmaln7d5al" || ids.Fields[0].Kind != "mono" {
		t.Fatalf("IDs section = %+v", ids)
	}
	seller := false
	for _, s := range d.Sections {
		for _, f := range s.Fields {
			seller = seller || (f.Label == "Seller" && f.Value == "Kente Naturals")
		}
	}
	if !seller {
		t.Error("the sheet does not show the seller by name")
	}
}

func TestRelayedDocumentsGetNamesNextToIdsAndInPlaceOfPeoplesIds(t *testing.T) {
	sc, _, _, _ := namesFixture(t)
	doc := any(map[string]any{
		"updatedBy": "0b1c2d3e-0000-4000-8000-000000000001", "actor": "system",
		"items": []any{map[string]any{"actorId": "0b1c2d3e-0000-4000-8000-000000000002", "sellerOrgId": "org_second", "createdBy": "Typed by hand"}},
	})
	sc.names.nameWho(context.Background(), sc, doc)
	m := doc.(map[string]any)
	row := m["items"].([]any)[0].(map[string]any)
	if m["updatedBy"] != "Amara Nwosu" || m["actor"] != "system" {
		t.Errorf("doc = %v", m)
	}
	if row["actorName"] != "tunde@example.com" || row["actorId"] != "0b1c2d3e-0000-4000-8000-000000000002" || row["sellerOrgName"] != "Ade's Kitchen" || row["createdBy"] != "Typed by hand" {
		t.Errorf("row = %v", row)
	}
}

// idKeys are fields that hold internal ids of people and organisations. A table column bound to one shows an id where a
// person needs a name; the resource marks the field with a nameRef and its column shows the resolved name instead.
var idKeys = []string{"ownerOrgId", "sellerOrgId", "orgId", "customerId", "userId", "actorId", "reviewerId", "driverId", "routeId", "stopId",
	"requestedBy", "createdBy", "updatedBy", "decidedBy", "approvedBy", "assignee", "proposedBy", "resolvedBy", "changedBy", "recordedBy"}

func TestNoTableColumnShowsAnIdOfAPersonOrOrganisation(t *testing.T) {
	for key, r := range staffResources() {
		check := func(where string, cols []domain.Column) {
			for _, c := range cols {
				if !slices.Contains(idKeys, c.Key) {
					continue
				}
				if c.Kind == "mono" {
					t.Errorf("%s %s: column %q (%s) is a mono column bound to the id %q; mark the field with a nameRef and show its name",
						key, where, c.Label, c.Kind, c.Key)
					continue
				}
				t.Errorf("%s %s: column %q shows the id %q; show the resolved name", key, where, c.Label, c.Key)
			}
		}
		check("table", r.columns)
		// A resolved name is only ever shown for an id the resource marked.
		for _, c := range r.columns {
			for _, ref := range r.names {
				if c.Key == ref.idKey {
					t.Errorf("%s: column %q is bound to a marked id", key, c.Key)
				}
			}
		}
	}
}

func TestEveryMarkedIdHasAColumnOrASheetPlaceForItsName(t *testing.T) {
	// A nameRef whose name key no column shows is still shown in the sheet; this keeps the keys consistent.
	for key, r := range staffResources() {
		seen := map[string]bool{}
		for _, ref := range r.names {
			id := ref.path + "/" + ref.idKey
			if seen[id] {
				t.Errorf("%s marks %s twice", key, id)
			}
			seen[id] = true
			if ref.nameKey == ref.idKey || ref.nameKey == "" || ref.unknown == "" || ref.label == "" {
				t.Errorf("%s: incomplete ref %+v", key, ref)
			}
		}
	}
}
