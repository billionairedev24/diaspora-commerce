package app

import (
	"reflect"
	"testing"

	"github.com/billionairedev24/diaspora-commerce/go/services/gateway-bff/internal/domain"
)

func TestTileCellsFromTheEditor(t *testing.T) {
	cells, errs := tileCells(jobj{
		"cell1_rule": "top-rated", "cell1_department": "Fashion",
		"cell2_rule": "new", "cell2_department": "Fashion",
		"cell3_rule": "search", "cell3_query": "shea butter", "cell3_department": "Beauty",
		"cell4_label": "Nigerian", "cell4_href": "/communities/nigerian", "cell4_imageUrl": "https://cdn.example/ng.jpg",
	})
	if len(errs) != 0 {
		t.Fatalf("errors = %v", errs)
	}
	want := []any{
		map[string]any{"source": map[string]any{"kind": "search", "department": "Fashion", "sort": "rating"}},
		map[string]any{"source": map[string]any{"kind": "search", "department": "Fashion", "sort": "newest"}},
		map[string]any{"source": map[string]any{"kind": "search", "query": "shea butter", "department": "Beauty"}},
		map[string]any{"label": "Nigerian", "href": "/communities/nigerian", "imageUrl": "https://cdn.example/ng.jpg"},
	}
	if !reflect.DeepEqual(cells, want) {
		t.Errorf("cells = %#v", cells)
	}
}

func TestTileCellProblemsNameTheField(t *testing.T) {
	_, errs := tileCells(jobj{"cell1_rule": "top-rated", "cell2_rule": "search", "cell3_label": "A", "cell4_href": "/x"})
	for _, k := range []string{"cell1_department", "cell2_query", "cell3_href", "cell4_label"} {
		if errs[k] == "" {
			t.Errorf("no problem reported for %s: %v", k, errs)
		}
	}
	if _, errs := tileCells(jobj{}); errs["cell1_rule"] == "" {
		t.Errorf("an empty tile must be refused: %v", errs)
	}
}

func TestTileFieldsRoundTrip(t *testing.T) {
	stored := jobj{"payload": map[string]any{"cells": []any{
		map[string]any{"source": map[string]any{"kind": "department-bestsellers", "department": "Beauty"}},
		map[string]any{"source": map[string]any{"kind": "search", "department": "Fashion", "sort": "newest"}},
		map[string]any{"label": "Ghanaian", "href": "/communities/ghanaian", "imageUrl": "https://cdn.example/gh.jpg"}}}}
	values := jobj{}
	for _, f := range tileFields(stored, false) {
		if s, ok := f.Value.(string); ok && s != "" {
			values[f.Key] = s
		}
	}
	cells, errs := tileCells(values)
	if len(errs) != 0 || !reflect.DeepEqual(cells, stored["payload"].(map[string]any)["cells"]) {
		t.Errorf("an unchanged save must reproduce the stored cells: %#v %v", cells, errs)
	}
}

func TestHeroWindowAndSlotFields(t *testing.T) {
	p := jobj{"title": "Sale", "endsAt": "2026-01-01T00:00:00Z"}
	if errs := applySlotFields(slotHeroCarousel, jobj{"slideStartsAt": "2026-11-10T09:00:00Z", "slideEndsAt": "2026-11-20T09:00:00Z"}, p); len(errs) != 0 {
		t.Fatal(errs)
	}
	if p["startsAt"] != "2026-11-10T09:00:00Z" || p["endsAt"] != "2026-11-20T09:00:00Z" {
		t.Errorf("payload = %v", p)
	}
	if errs := applySlotFields(slotHeroCarousel, jobj{}, p); len(errs) != 0 || p["startsAt"] != nil || p["endsAt"] != nil {
		t.Errorf("clearing the fields must remove the window: %v %v", errs, p)
	}
	if errs := applySlotFields(slotHeroCarousel, jobj{"slideStartsAt": "2026-11-20T09:00:00Z", "slideEndsAt": "2026-11-10T09:00:00Z"}, jobj{}); errs["slideEndsAt"] == "" {
		t.Errorf("an end before the start must be refused: %v", errs)
	}
}

func TestPlacementFormShowsTileAndHeroFieldsOnlyForTheirSlot(t *testing.T) {
	form := domain.Form{Fields: append(heroFields(nil, true), tileFields(nil, true)...)}
	form.Fields = append([]domain.FormField{ff("slot", "Slot", "select", true)}, form.Fields...)
	if errs := domain.ValidateForm(form, map[string]any{"slot": "display_banner", "cell1_rule": "bogus", "slideEndsAt": 5}); len(errs) != 0 {
		t.Errorf("hidden fields must not be validated: %v", errs)
	}
	if errs := domain.ValidateForm(form, map[string]any{"slot": "home_tiles", "cell1_rule": "bogus"}); errs["cell1_rule"] == "" {
		t.Errorf("a tile's rule must be one of the options: %v", errs)
	}
}
