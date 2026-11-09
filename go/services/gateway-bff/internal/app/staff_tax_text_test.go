package app

import "testing"

func TestDescribeCondition(t *testing.T) {
	facts := map[string]taxFact{
		"net_weight_oz": {id: "net_weight_oz", label: "Net weight (oz)", kind: "DECIMAL", unit: "oz", choices: map[string]string{}},
		"servings":      {id: "servings", label: "Servings per package", kind: "INTEGER", choices: map[string]string{}},
		"juice":         {id: "juice", label: "Juice content", kind: "PERCENT", unit: "%", choices: map[string]string{}},
		"sweetened":     {id: "sweetened", label: "Sweetened", kind: "BOOLEAN", choices: map[string]string{}},
		"label":         {id: "label", label: "Label on the package", kind: "CHOICE", choices: map[string]string{"DRUG_FACTS": "Drug Facts", "NONE": "No such label"}},
	}
	leaf := func(op, fact string, v any) map[string]any { return map[string]any{"op": op, "fact": fact, "value": v} }
	cases := []struct {
		name string
		expr any
		want string
	}{
		{"nothing", nil, ""},
		{"and", map[string]any{"all": []any{leaf("gte", "net_weight_oz", 2.5), leaf("gt", "servings", 1.0)}},
			"net weight is at least 2.5 oz and servings per package is more than 1"},
		{"or inside and keeps its brackets", map[string]any{"all": []any{leaf("isFalse", "sweetened", nil), map[string]any{"any": []any{leaf("lte", "juice", 50.0), leaf("lt", "net_weight_oz", 1.0)}}}},
			`"Sweetened" is no and (juice content is at most 50% or net weight is less than 1 oz)`},
		{"nested all in all is flat", map[string]any{"all": []any{map[string]any{"all": []any{leaf("isTrue", "sweetened", nil), leaf("eq", "label", "NONE")}}, leaf("gt", "servings", 2.0)}},
			`"Sweetened" is yes and "Label on the package" is No such label and servings per package is more than 2`},
		{"not of a leaf reads as its opposite", map[string]any{"not": leaf("gte", "net_weight_oz", 2.5)}, "net weight is less than 2.5 oz"},
		{"not of a group", map[string]any{"not": map[string]any{"all": []any{leaf("isTrue", "sweetened", nil), leaf("gt", "servings", 1.0)}}},
			`not ("Sweetened" is yes and servings per package is more than 1)`},
		{"in", map[string]any{"op": "in", "fact": "label", "values": []any{"DRUG_FACTS", "NONE"}}, `"Label on the package" is one of Drug Facts, No such label`},
		{"unknown fact falls back to its id", leaf("isTrue", "mystery_flag", nil), "mystery flag is yes"},
	}
	for _, c := range cases {
		if got := describeCondition(c.expr, facts); got != c.want {
			t.Errorf("%s: got %q, want %q", c.name, got, c.want)
		}
	}
	row := jobj{"when": map[string]any{"all": []any{leaf("gte", "net_weight_oz", 2.5)}}, "outcome": "EXEMPT"}
	if got := describeRow(row, facts); got != "If net weight is at least 2.5 oz, then exempt" {
		t.Errorf("row = %q", got)
	}
	if got := describeRow(jobj{"outcome": "TAXABLE"}, facts); got != "Always, then taxable" {
		t.Errorf("unconditional row = %q", got)
	}
}

func TestCodeStateAndCompleteness(t *testing.T) {
	rows := []jobj{
		{"outcome": "EXEMPT", "providerCode": "txcd_40040016", "verified": true},
		{"outcome": "TAXABLE", "providerCode": "txcd_40040016", "verified": false},
		{"outcome": "TAXABLE", "providerCode": "", "verified": false},
		{"outcome": "BLOCKED", "providerCode": "", "verified": false},
	}
	for _, r := range rows {
		r["state"] = codeState(r)
	}
	c := completeness(rows)
	if c["needed"] != 3 || c["verified"] != 1 || c["unmapped"] != 1 || c["unverified"] != 1 || c["percent"] != 33 || c["complete"] != false {
		t.Errorf("completeness = %v", c)
	}
	for _, bad := range []struct{ provider, code string }{{"STRIPE", "40040016"}, {"STRIPE", "txcd_1"}, {"AVALARA", "has space"}, {"PAYPAL", "x"}} {
		if validProviderCode(bad.provider, bad.code) == "" {
			t.Errorf("%s %q passed", bad.provider, bad.code)
		}
	}
	if validProviderCode("STRIPE", "txcd_40040016") != "" || validProviderCode("AVALARA", "PF050099") != "" {
		t.Error("a valid code was refused")
	}
}
