package app

import "testing"

func TestMaskPIIHidesEmailsAndPhonesButNotIds(t *testing.T) {
	cases := map[string]string{
		"ada.okafor@example.com wrote":                "a***@example.com wrote",
		"call +1 (713) 249-0142 today":                "call ***0142 today",
		"order AT-2026-000123 is late":                "order AT-2026-000123 is late",
		"payout 7f3e2a1b-0c4d-4e5f-8a9b-1c2d3e4f5a6b": "payout 7f3e2a1b-0c4d-4e5f-8a9b-1c2d3e4f5a6b",
		"zip 77002 and 12 units":                      "zip 77002 and 12 units",
	}
	for in, want := range cases {
		if got := maskString(in); got != want {
			t.Errorf("maskString(%q) = %q, want %q", in, got, want)
		}
	}
	m := maskPII(map[string]any{"who": []string{"bob@x.io"}, "n": 3, "nested": map[string]any{"phone": "07123456789"}}).(map[string]any)
	if m["who"].([]any)[0] != "b***@x.io" || m["n"] != 3 || m["nested"].(map[string]any)["phone"] != "***6789" {
		t.Errorf("maskPII = %v", m)
	}
}
