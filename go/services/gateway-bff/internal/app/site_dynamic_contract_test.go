package app

import (
	"context"
	"encoding/json"
	"testing"

	"github.com/getkin/kin-openapi/openapi3"

	gatewaybff "github.com/billionairedev24/diaspora-commerce/go/services/gateway-bff"
	"github.com/billionairedev24/diaspora-commerce/go/services/gateway-bff/internal/domain"
)

// Contract tests: what the dynamic home and the navigation sources produce is validated against the schemas of the
// embedded contracts (identical copies of contracts/openapi/gateway-bff*.yaml).

func loadSchemas(t *testing.T, file string) map[string]*openapi3.SchemaRef {
	t.Helper()
	raw, err := gatewaybff.Files.ReadFile(file)
	if err != nil {
		t.Fatal(err)
	}
	doc, err := openapi3.NewLoader().LoadFromData(raw)
	if err != nil {
		t.Fatalf("load %s: %v", file, err)
	}
	return doc.Components.Schemas
}

func validate(t *testing.T, schemas map[string]*openapi3.SchemaRef, name string, v any) {
	t.Helper()
	ref := schemas[name]
	if ref == nil {
		t.Fatalf("schema %s not in the contract", name)
	}
	raw, err := json.Marshal(v)
	if err != nil {
		t.Fatal(err)
	}
	var generic any
	if err := json.Unmarshal(raw, &generic); err != nil {
		t.Fatal(err)
	}
	if err := ref.Value.VisitJSON(generic); err != nil {
		t.Errorf("%s does not match the contract: %v\n%s", name, err, raw)
	}
}

func TestDynamicHomeAndNavigationMatchTheContract(t *testing.T) {
	schemas := loadSchemas(t, "api/openapi.yaml")
	for _, n := range []int{0, 10, 100, 400} {
		r := newHomeRig(t, n, max(1, n/10), "dev")
		r.svcs.all = nil
		req := guest("visitor-1", "w1")
		req.Debug = true
		home := r.compose(t, req)
		validate(t, schemas, "Home", home)
		nav, err := r.svc.Navigation(context.Background(), "fr")
		if err != nil {
			t.Fatal(err)
		}
		validate(t, schemas, "SiteNavigation", nav)
		depts, at, _ := r.svc.Departments(context.Background(), "en", true)
		for _, d := range depts {
			validate(t, schemas, "SiteDepartment", d)
		}
		validate(t, schemas, "Home", home)
		comms, _, _ := r.svc.Communities(context.Background(), "en")
		for _, c := range comms {
			validate(t, schemas, "SiteCommunity", c)
		}
		sm, _ := r.svc.SitemapSources(context.Background())
		for _, e := range append(sm.Departments, sm.Communities...) {
			validate(t, schemas, "SitemapEntry", e)
		}
		_ = at
	}
}

func TestStaffPreviewAndSettingsMatchTheStaffContract(t *testing.T) {
	schemas := loadSchemas(t, "api/openapi-staff.yaml")
	r := newHomeRig(t, 100, 10, "prod")
	h, decisions, err := r.svc.PreviewHome(context.Background(), HomeRequest{Catalog: "100", Locale: "en", Debug: true})
	if err != nil {
		t.Fatal(err)
	}
	preview := StaffHomePreview{AsOf: homeNow.Format("2006-01-02T15:04:05Z"), Locale: "en", Catalog: "100", Seed: h.Debug.Seed, Blocks: h.Blocks, Hero: h.Hero, Tiles: h.Tiles, Decisions: decisions}
	validate(t, schemas, "StaffHomePreview", preview)
	for _, d := range decisions {
		validate(t, schemas, "StaffHomeDecision", d)
	}
	validate(t, schemas, "StaffHomeSettings", (&staffHomeImpl{env: "prod"}).wrap(domain.DefaultHomeSettings()))
	save := StaffHomeSave{ExpectedVersion: 0, Modules: domain.DefaultHomeRules(), Caps: domain.DefaultHomeCaps(), DepartmentLabels: map[string]domain.LocaleLabels{}, Reason: "launch"}
	validate(t, schemas, "StaffHomeSettingsSave", save)
}

func TestDefaultSettingsEqualTheDocumentedThresholds(t *testing.T) {
	d := domain.DefaultHomeSettings()
	c := d.Modules[domain.ModCommunities]
	if c.MinUnits != 3 || c.UnitMinProduct != 8 || c.UnitMinSellers != 2 {
		t.Errorf("communities default 3 / 8 / 2: %+v", c)
	}
	caps := d.Caps
	if caps.SellerShare != 2 || caps.SponsoredPerOrg != 3 || caps.PoolMultiplier != 3 || caps.VisitWindowMinutes != 30 || caps.MaxModules != 12 || caps.NavBarSize != 7 {
		t.Errorf("caps: %+v", caps)
	}
}
