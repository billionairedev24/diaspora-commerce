package app

import (
	"strings"
	"testing"
)

func TestAuditSummaryReadsLikeASentence(t *testing.T) {
	for name, tc := range map[string]struct {
		entry jobj
		want  string
	}{
		"rename": {jobj{"action": "NODE_RENAMED", "subjectLabel": "Fashion › Ladies", "details": map[string]any{"from": "Women", "to": "Ladies"}}, "Renamed “Women” to “Ladies”"},
		"move": {jobj{"action": "NODE_MOVED", "subjectLabel": "Fashion › Dresses", "details": map[string]any{"fromPath": "Fashion › Women › Dresses", "toPath": "Fashion › Dresses"}},
			"Moved from Fashion › Women › Dresses to Fashion › Dresses"},
		"retire": {jobj{"action": "NODE_RETIRED", "subjectLabel": "Toys"}, "Retired Toys"},
		"type": {jobj{"action": "TYPE_UPDATED", "subjectLabel": "Dresses", "details": map[string]any{"identifierRule": map[string]any{},
			"attributes": map[string]any{"added": []any{"brand"}, "removed": []any{"fit"}}}}, "Changed Dresses: Identifier rule; added brand; removed fit"},
		"approved": {jobj{"action": "REQUEST_APPROVED", "details": map[string]any{"createdPath": "Fashion › Women › Bridal sets"}}, "Approved and created Fashion › Women › Bridal sets"},
		"unknown":  {jobj{"action": "SOMETHING_NEW", "subjectLabel": "Thing"}, "Thing"},
	} {
		if got := auditSummary(tc.entry); got != tc.want {
			t.Errorf("%s: %q, want %q", name, got, tc.want)
		}
	}
	if auditActionLabel("NODES_REORDERED") != "Reordered" || auditActionLabel("NODE_X_Y") == "" {
		t.Error("action labels")
	}
}

func TestTaxonomyValidationNamesTheField(t *testing.T) {
	errs := map[string]string{}
	if got := taxReasonOf(jobj{"reason": "  ab "}, errs); got != "ab" || errs["reason"] == "" {
		t.Errorf("short reason: %q %v", got, errs)
	}
	errs = map[string]string{}
	taxReasonOf(jobj{"reason": strings.Repeat("x", 1001)}, errs)
	if errs["reason"] == "" {
		t.Error("long reason")
	}
	errs = map[string]string{}
	if n := taxNameOf(jobj{"name": "  Hair   care "}, "name", errs); n != "Hair care" || len(errs) != 0 {
		t.Errorf("name %q %v", n, errs)
	}
	taxNameOf(jobj{"name": "A › B"}, "name", errs)
	if errs["name"] == "" {
		t.Error("the path separator is refused")
	}
}

func TestNewProductTypeBody(t *testing.T) {
	body, errs := newTypeBody(jobj{"label": " Bridal  sets ", "identifierRule": "gtin", "traits": map[string]any{"food": true},
		"attributes": []any{map[string]any{"key": " pieces ", "label": "Pieces", "kind": "number", "units": []any{"pcs"}, "required": true}}})
	if len(errs) != 0 {
		t.Fatalf("errors = %v", errs)
	}
	attrs := body["attributes"].([]jobj)
	if body["label"] != "Bridal sets" || body["identifierRule"] != "GTIN" || attrs[0]["key"] != "pieces" || attrs[0]["kind"] != "NUMBER" || attrs[0]["applies"] != true ||
		attrs[0]["section"] != "DETAILS" || body["traits"].(jobj)["food"] != true || body["traits"].(jobj)["perishable"] != false {
		t.Fatalf("body = %+v", body)
	}
	_, errs = newTypeBody(jobj{"identifierRule": "EAN", "code": "Bad Code", "attributes": []any{map[string]any{"key": "", "label": "", "kind": "blob", "section": "elsewhere"}}})
	for _, k := range []string{"identifierRule", "code", "attributes[0].key", "attributes[0].label", "attributes[0].kind", "attributes[0].section"} {
		if errs[k] == "" {
			t.Errorf("no error for %s: %v", k, errs)
		}
	}
	if _, errs = newTypeBody(jobj{"identifierRule": "GTIN", "attributes": "nope"}); errs["attributes"] == "" {
		t.Error("attributes must be a list")
	}
}

func TestAttributeApplyingDefaultsToTrueAndCanBeSwitchedOff(t *testing.T) {
	if a := attributeBody(map[string]any{"key": "brand", "label": "Brand", "kind": "TEXT"}); a["applies"] != true {
		t.Errorf("applies = %v", a["applies"])
	}
	if a := attributeBody(map[string]any{"key": "brand", "label": "Brand", "kind": "TEXT", "applies": false}); a["applies"] != false {
		t.Errorf("applies = %v", a["applies"])
	}
}

func TestPlanLines(t *testing.T) {
	lines := planLines(jobj{"nodes": []any{
		map[string]any{"level": "CATEGORY", "path": "Fashion › Classic games", "created": true},
		map[string]any{"level": "PRODUCT_TYPE", "path": "Fashion › Classic games › Chess", "created": true}},
		"productType":    map[string]any{"label": "Chess", "created": false, "attributeCount": float64(4)},
		"productsMoving": float64(2)})
	want := []string{"Category|Fashion › Classic games — will be created", "Product type|Fashion › Classic games › Chess — will be created",
		"Details sellers fill in|Chess (existing schema, 4 attributes)", "Products waiting|2 move into listing review"}
	if len(lines) != len(want) {
		t.Fatalf("lines = %+v", lines)
	}
	for i, l := range lines {
		if l.Label+"|"+l.Value != want[i] {
			t.Errorf("line %d = %q, want %q", i, l.Label+"|"+l.Value, want[i])
		}
	}
}

func TestCategoryRequestsAreATable(t *testing.T) {
	r, ok := staffResources()["category-requests"]
	if !ok || r.module != "catalog" || len(r.actions) != 2 {
		t.Fatalf("resource = %+v", r)
	}
	for _, a := range r.actions {
		if a.permission != permTaxonomy || a.level != "APPROVE" {
			t.Errorf("action %s needs %s at %s", a.key, a.permission, a.level)
		}
	}
}
