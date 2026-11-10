package catalog

import "testing"

func TestDisplayPath(t *testing.T) {
	cases := map[string]string{
		"Groceries & Food › Rice & grains › Rice & grains":                    "Groceries & Food › Rice & grains",
		"Fashion & Accessories › Adult clothing & footwear › Women's dresses": "Fashion & Accessories › Adult clothing & footwear › Women's dresses",
		"Groceries & Food":        "Groceries & Food",
		"Beverages › Plain water": "Beverages › Plain water",
		"Fashion & Accessories › Children’s clothing › Kids' clothing": "Fashion & Accessories › Children’s clothing › Kids' clothing",
	}
	for in, want := range cases {
		if got := displayPath(in); got != want {
			t.Errorf("displayPath(%q) = %q, want %q", in, got, want)
		}
	}
}
