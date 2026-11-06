package app

import (
	"reflect"
	"testing"
)

func TestTileCellsFromTheEditorLocationRules(t *testing.T) {
	cells, errs := tileCells(jobj{"cell1_rule": "near-you", "cell1_department": "Groceries", "cell2_rule": "deliverable-fast"})
	if len(errs) != 0 {
		t.Fatalf("errors = %v", errs)
	}
	want := []any{
		map[string]any{"source": map[string]any{"kind": "near-you", "department": "Groceries"}},
		map[string]any{"source": map[string]any{"kind": "deliverable-fast", "maxDays": 2}},
	}
	if !reflect.DeepEqual(cells, want) {
		t.Errorf("cells = %#v", cells)
	}
	if got := cellDefaults(jobj{"source": map[string]any{"kind": "near-you", "department": "Groceries"}}); got["rule"] != ruleNear || got["department"] != "Groceries" {
		t.Errorf("a stored near-you cell reads back as %v", got)
	}
}
