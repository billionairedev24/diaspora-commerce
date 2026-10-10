package domain

import "testing"

func TestOrgKindPortals(t *testing.T) {
	cases := map[string]struct {
		kind    OrgKind
		program string
		hub     bool
	}{
		"SELLER":   {OrgKindSeller, "Seller Center", false},
		"provider": {OrgKindProvider, "Provider Hub", true},
		"Both":     {OrgKindBoth, "Seller Center", false},
		"":         {OrgKindSeller, "Seller Center", false},
		"unknown":  {OrgKindSeller, "Seller Center", false},
	}
	for in, want := range cases {
		k := ParseOrgKind(in)
		if k != want.kind || k.ProgramName() != want.program || k.UsesProviderHub() != want.hub {
			t.Errorf("%q: got %s %q %v", in, k, k.ProgramName(), k.UsesProviderHub())
		}
	}
}
