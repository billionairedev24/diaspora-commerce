package app

import (
	"reflect"
	"testing"

	"github.com/billionairedev24/diaspora-commerce/go/services/gateway-bff/internal/domain"
)

func TestSplitSlotCarriesTheHomeModuleType(t *testing.T) {
	if s, b := splitSlot("home_block:deals"); s != "home_block" || b != "deals" {
		t.Errorf("split = %s %s", s, b)
	}
	if s, b := splitSlot("home_ad_row"); s != "home_ad_row" || b != "" {
		t.Errorf("split = %s %s", s, b)
	}
}

func TestLayoutOrderIsPriorityDescendingAndHidesEndedAndOtherSlots(t *testing.T) {
	items := []jobj{
		{"id": "a", "slot": "home_product_row", "priority": float64(10), "status": "LIVE"},
		{"id": "b", "slot": "home_brand_banner", "priority": float64(40), "status": "LIVE"},
		{"id": "hero", "slot": "hero_carousel", "priority": float64(99), "status": "LIVE"},
		{"id": "ended", "slot": "home_ad_row", "priority": float64(90), "status": "ENDED"},
		{"id": "c", "slot": "home_block", "priority": float64(10), "status": "SCHEDULED"},
	}
	var ids []string
	for _, o := range layoutOrder(items) {
		ids = append(ids, jstr(o, "id"))
	}
	if want := []string{"b", "a", "c"}; !reflect.DeepEqual(ids, want) {
		t.Errorf("order = %v, want %v (ties keep the list's order)", ids, want)
	}
}

func TestMovingAModuleSwapsItWithItsNeighbourAndClamps(t *testing.T) {
	ids := []string{"a", "b", "c"}
	if got := reorder(ids, "b", -1); !reflect.DeepEqual(got, []string{"b", "a", "c"}) {
		t.Errorf("up = %v", got)
	}
	if got := reorder(ids, "b", 1); !reflect.DeepEqual(got, []string{"a", "c", "b"}) {
		t.Errorf("down = %v", got)
	}
	if got := reorder(ids, "a", -1); !reflect.DeepEqual(got, ids) {
		t.Errorf("the first module cannot move up: %v", got)
	}
	if got := reorder(ids, "zzz", 1); !reflect.DeepEqual(got, ids) {
		t.Errorf("unknown module: %v", got)
	}
	// Positions become steps of ten, highest first, so ties are always broken.
	if priorityFor(0, 3) != 30 || priorityFor(2, 3) != 10 {
		t.Errorf("priorities = %d %d", priorityFor(0, 3), priorityFor(2, 3))
	}
}

func TestPaidModuleFieldsNeedTheAdvertiserAndValidPieces(t *testing.T) {
	p := jobj{"title": "Jollof week", "href": "/s?q=jollof"}
	errs := applyAdFields(slotBrandBanner, "", jobj{"cta": "Shop", "skus": "at1, at2", "imageUrl": "https://m.example/a.webp", "states": "tx, ga", "maxPerSession": float64(2)}, p, "Acme")
	if len(errs) != 0 {
		t.Fatalf("errors = %v", errs)
	}
	if p["headline"] != "Jollof week" || p["title"] != nil || p["advertiser"] != "Acme" || p["maxPerSession"] != 2 {
		t.Errorf("payload = %v", p)
	}
	if !reflect.DeepEqual(p["skus"], []string{"AT1", "AT2"}) || !reflect.DeepEqual(p["states"], []string{"TX", "GA"}) {
		t.Errorf("lists = %v %v", p["skus"], p["states"])
	}
	if errs := applyAdFields(slotBrandBanner, "", jobj{"cta": "Shop"}, jobj{}, ""); errs["advertiser"] == "" {
		t.Errorf("a paid module without an advertiser must be refused: %v", errs)
	}
	if errs := applyAdFields(slotBrandBanner, "", jobj{"cta": "Shop", "skus": "a,b,c,d"}, jobj{}, "Acme"); errs["skus"] == "" {
		t.Errorf("at most three products in the strip: %v", errs)
	}
	if errs := applyAdFields(slotHomeBlock, domain.BlockDeals, jobj{"states": "Texas"}, jobj{}, ""); errs["states"] == "" {
		t.Errorf("states are two letters: %v", errs)
	}
}

func TestAdRowNeedsTwoCompleteAdsAndNoHeadline(t *testing.T) {
	ad := func(n string) jobj {
		return jobj{"ad" + n + "_imageUrl": "https://m.example/" + n + ".webp", "ad" + n + "_alt": "Ad " + n, "ad" + n + "_href": "/s?q=" + n}
	}
	merge := func(ms ...jobj) jobj {
		out := jobj{}
		for _, m := range ms {
			for k, v := range m {
				out[k] = v
			}
		}
		return out
	}
	p := jobj{"title": "x", "href": "/y"}
	if errs := applyAdFields(slotAdRow, "", merge(ad("1"), ad("2")), p, "Acme"); len(errs) != 0 || len(p["ads"].([]any)) != 2 || p["title"] != nil || p["href"] != nil {
		t.Errorf("errs=%v payload=%v", errs, p)
	}
	if errs := applyAdFields(slotAdRow, "", ad("1"), jobj{}, "Acme"); len(errs) == 0 {
		t.Errorf("one ad is not a row")
	}
	if errs := applyAdFields(slotAdRow, "", merge(ad("1"), jobj{"ad2_imageUrl": "https://m.example/2.webp"}), jobj{}, "Acme"); errs["ad2_alt"] == "" {
		t.Errorf("alt text is required: %v", errs)
	}
}

func TestHomeModuleAndOccasionPayloads(t *testing.T) {
	p := jobj{}
	errs := applyAdFields(slotHomeBlock, domain.BlockOccasions, jobj{"audience": "signed-in",
		"cell1_label": "Party", "cell1_href": "/s?q=party", "cell1_imageUrl": "https://m.example/p.webp",
		"cell2_rule": "search", "cell2_query": "wedding", "cell2_label": "Wedding"}, p, "")
	if errs["cell2_href"] == "" {
		t.Errorf("every occasion cell needs its link: %v", errs)
	}
	p = jobj{}
	errs = applyAdFields(slotHomeBlock, domain.BlockOccasions, jobj{"cell1_label": "Party", "cell1_href": "/s?q=party", "cell2_rule": "search", "cell2_query": "wedding", "cell2_label": "Wedding", "cell2_href": "/s?q=wedding",
		"cell3_label": "Graduation", "cell3_href": "/s?q=graduation", "cell3_imageUrl": "https://m.example/g.webp"}, p, "")
	if len(errs) != 0 || p["type"] != domain.BlockOccasions || len(p["cells"].([]any)) != 3 {
		t.Errorf("errs=%v payload=%v", errs, p)
	}
	if errs := applyAdFields(slotHomeBlock, domain.BlockOccasions, jobj{"cell1_label": "A", "cell1_href": "/a"}, jobj{}, ""); errs["cell3_label"] == "" {
		t.Errorf("occasions need three cells: %v", errs)
	}
	if errs := applyAdFields(slotHomeBlock, domain.BlockMembership, jobj{}, jobj{}, ""); errs["href"] == "" {
		t.Errorf("a band needs a link: %v", errs)
	}
}

func TestTargetingKeepsMarketsAndSetsZipPrefixes(t *testing.T) {
	got, errs := targetingFrom(jobj{"zipPrefixes": "77, 750"}, map[string]any{"markets": []any{"US"}, "audience": "ALL"})
	if len(errs) != 0 || !reflect.DeepEqual(got["zipPrefixes"], []string{"77", "750"}) || got["audience"] != "ALL" {
		t.Errorf("targeting = %v %v", got, errs)
	}
	if _, errs := targetingFrom(jobj{"zipPrefixes": "7x"}, nil); errs["zipPrefixes"] == "" {
		t.Errorf("bad prefix accepted")
	}
	if got, _ := targetingFrom(jobj{}, nil); got["markets"] == nil {
		t.Errorf("a new placement defaults to the US market")
	}
	if priorityFrom(jobj{"priority": float64(55)}, 10) != 55 || priorityFrom(jobj{}, 10) != 10 || priorityFrom(jobj{"priority": float64(5000)}, 10) != 1000 {
		t.Errorf("priority")
	}
}

func TestEditorFieldsFollowTheSlot(t *testing.T) {
	keys := func(fs []domain.FormField) map[string]bool {
		m := map[string]bool{}
		for _, f := range fs {
			m[f.Key] = true
		}
		return m
	}
	edit := keys(adFields(jobj{"slot": "home_advertorial", "payload": map[string]any{"text": "t"}}, false))
	if !edit["body"] || edit["skus"] || edit["ad1_imageUrl"] || !edit["states"] || !edit["zipPrefixes"] {
		t.Errorf("advertorial edit fields = %v", edit)
	}
	create := adFields(jobj{}, true)
	for _, f := range create {
		if f.Key == "skus" && (f.ShowWhen == nil || len(f.ShowWhen.In) != 1 || f.ShowWhen.In[0] != slotBrandBanner) {
			t.Errorf("skus must show only for the brand banner: %+v", f.ShowWhen)
		}
	}
	seen := map[string]bool{}
	for _, f := range append(ctaField(jobj{}, true), create...) {
		if seen[f.Key] {
			t.Errorf("duplicate field %s", f.Key)
		}
		seen[f.Key] = true
	}
}

func TestPreviewDescribesDisclosureAndTargeting(t *testing.T) {
	o := jobj{"slot": "home_brand_banner", "paid": true, "advertiser": "Acme", "payload": map[string]any{"headline": "Jollof week", "skus": []any{"A", "B"}, "states": []any{"TX"}},
		"targeting": map[string]any{"zipPrefixes": []any{"77"}}}
	if d := disclosure(o); d != "Labelled Sponsored · Acme" {
		t.Errorf("disclosure = %s", d)
	}
	if s := targetingSummary(o); s != "TX · ZIP 77" {
		t.Errorf("targeting = %s", s)
	}
	if s := whatItShows(o); s != `Full-width banner "Jollof week" with a strip of 2 products` {
		t.Errorf("shows = %s", s)
	}
	organic := jobj{"slot": "home_block", "payload": map[string]any{"type": "deals"}}
	if disclosure(organic) != "Organic (no label)" || layoutKind(organic) != "Deals of the day" || targetingSummary(organic) != "Everywhere" {
		t.Errorf("organic = %s %s %s", disclosure(organic), layoutKind(organic), targetingSummary(organic))
	}
}
