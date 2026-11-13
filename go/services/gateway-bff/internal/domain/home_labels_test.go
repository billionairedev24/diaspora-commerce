package domain

import (
	"encoding/json"
	"os"
	"strings"
	"testing"
)

func TestDepartmentKeyAndShortLabels(t *testing.T) {
	cases := map[string][2]string{
		"Groceries & Food":       {"groceries-food", "Groceries"},
		"Beauty & Personal Care": {"beauty-personal-care", "Beauty"},
		"Fashion & Accessories":  {"fashion-accessories", "Fashion"},
		"Home & Household":       {"home-household", "Home"},
		"Baby & Kids":            {"baby-kids", "Baby & Kids"},
		"Electronics":            {"electronics", "Electronics"},
		"Pet Supplies":           {"pet-supplies", "Pet Supplies"},
	}
	for name, want := range cases {
		if got := DepartmentKey(name); got != want[0] {
			t.Errorf("key(%q)=%q want %q", name, got, want[0])
		}
		if got := DepartmentLabels(name, nil)["en"]; got != want[1] {
			t.Errorf("label(%q)=%q want %q", name, got, want[1])
		}
	}
}

func TestStaffLabelWinsAndNewDepartmentStillGetsALabel(t *testing.T) {
	staff := map[string]LocaleLabels{"groceries-food": {"en": "Food", "fr": "Épicerie"}}
	l := DepartmentLabels("Groceries & Food", staff)
	if l["en"] != "Food" || l["fr"] != "Épicerie" || l["es"] == "" {
		t.Fatalf("staff override per locale, i18n for the rest: %v", l)
	}
	if got := DepartmentLabels("Musical Instruments & Gear", nil)["en"]; got != "Musical Instruments" {
		t.Fatalf("a department added later is labelled from the live name: %q", got)
	}
}

func TestDepartmentLabelsMatchTheContractCopyFile(t *testing.T) {
	raw, err := os.ReadFile("../../../../../contracts/i18n/home-copy.json")
	if err != nil {
		t.Skip("contracts not available: ", err)
	}
	var f struct {
		Keys map[string]map[string]any `json:"keys"`
	}
	if err := json.Unmarshal(raw, &f); err != nil {
		t.Fatal(err)
	}
	n := 0
	for k, v := range f.Keys {
		if !strings.HasPrefix(k, "dept.") {
			continue
		}
		n++
		for _, l := range Locales {
			if s, _ := v[l].(string); deptLabels[strings.TrimPrefix(k, "dept.")][l] != s {
				t.Errorf("%s/%s drifted from contracts/i18n/home-copy.json", k, l)
			}
		}
	}
	if n != len(deptLabels) || n == 0 {
		t.Fatalf("contract has %d dept keys, code has %d", n, len(deptLabels))
	}
}

func TestCommunityResolverMergesCaseAndAliases(t *testing.T) {
	r := NewCommunityResolver([]CommunityEntry{{Key: "nigerian", Labels: LocaleLabels{"en": "Nigerian", "fr": "Nigérian"}, Aliases: []string{"Naija", "nigeria"}}})
	for _, raw := range []string{"nigerian", "Nigerian", " NIGERIAN ", "Naija", "nigeria"} {
		if k, e := r.Resolve(raw); k != "nigerian" || e == nil {
			t.Errorf("%q resolved to %q", raw, k)
		}
	}
	if k, e := r.Resolve("Sierra Leonean"); k != "sierra-leonean" || e != nil {
		t.Errorf("unregistered value uses itself lower-cased: %q", k)
	}
	if r.Labels("nigerian")["fr"] != "Nigérian" || r.Labels("sierra-leonean")["en"] != "Sierra Leonean" {
		t.Error("labels")
	}
	if k, _ := r.Resolve("  "); k != "" {
		t.Error("blank is no community")
	}
}

func TestSettingsDefaultsValidationAndMode(t *testing.T) {
	d := DefaultHomeSettings()
	c := d.Modules[ModCommunities]
	if c.MinUnits != 3 || c.UnitMinProduct != 8 || c.UnitMinSellers != 2 {
		t.Fatalf("communities default is 3 communities x 8 products x 2 sellers: %+v", c)
	}
	if bad := ValidateHomeSettings(d); len(bad) != 0 {
		t.Fatalf("defaults must validate: %v", bad)
	}
	neg := d.Merged()
	r := neg.Modules[ModDeals]
	r.MinProducts, r.Weight = -1, 101
	neg.Modules[ModDeals] = r
	neg.Modules["nonsense"] = ModuleRule{}
	bad := ValidateHomeSettings(neg)
	for _, f := range []string{"modules.deals.minProducts", "modules.deals.weight", "modules.nonsense"} {
		if bad[f] == "" {
			t.Errorf("missing error for %s: %v", f, bad)
		}
	}
	on, off := DynamicOn, DynamicOff
	for env, want := range map[string]string{"local": "on", "dev": "on", "uat": "on", "trial": "on", "prod": "off", "": "off"} {
		if got := EffectiveDynamic(nil, env); got != want {
			t.Errorf("env %q -> %s want %s", env, got, want)
		}
	}
	if EffectiveDynamic(&on, "prod") != "on" || EffectiveDynamic(&off, "dev") != "off" {
		t.Error("a stored value wins over the environment default")
	}
}

func TestEveryHomeCopyKeyExistsInFiveLocales(t *testing.T) {
	raw, err := os.ReadFile("../../../../../contracts/i18n/home-copy.json")
	if err != nil {
		t.Skip("contracts not available: ", err)
	}
	var f struct {
		Keys map[string]map[string]any `json:"keys"`
	}
	if err := json.Unmarshal(raw, &f); err != nil {
		t.Fatal(err)
	}
	for _, k := range HomeCopyKeys {
		entry, ok := f.Keys[k]
		if !ok {
			t.Errorf("%s is not in contracts/i18n/home-copy.json", k)
			continue
		}
		for _, l := range Locales {
			if s, _ := entry[l].(string); strings.TrimSpace(s) == "" {
				t.Errorf("%s has no %s text", k, l)
			}
		}
	}
}
