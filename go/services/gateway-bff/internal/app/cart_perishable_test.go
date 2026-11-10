package app

import (
	"os"
	"regexp"
	"sort"
	"strings"
	"testing"

	"github.com/billionairedev24/diaspora-commerce/go/services/gateway-bff/internal/port"
)

// The BFF's perishable rule is orders': the same product types (orders' application.yml, at.orders.perishable-types).
func TestPerishableTypesMatchOrders(t *testing.T) {
	raw, err := os.ReadFile("../../../../../java/services/orders/src/main/resources/application.yml")
	if err != nil {
		t.Skipf("orders config not available: %v", err)
	}
	m := regexp.MustCompile(`(?m)^\s*perishable-types:\s*\[([^\]]*)\]`).FindSubmatch(raw)
	if m == nil {
		t.Fatal("orders application.yml has no perishable-types list")
	}
	var want []string
	for _, v := range strings.Split(string(m[1]), ",") {
		want = append(want, strings.TrimSpace(v))
	}
	var got []string
	for k := range perishableProductTypes {
		got = append(got, k)
	}
	sort.Strings(want)
	sort.Strings(got)
	if strings.Join(want, ",") != strings.Join(got, ",") {
		t.Fatalf("gateway-bff perishable types %v differ from orders' %v", got, want)
	}
}

func TestPerishableProduct(t *testing.T) {
	if !perishableProduct(port.CatalogProduct{ProductType: "frozen-meals"}) {
		t.Error("frozen meals are perishable")
	}
	if perishableProduct(port.CatalogProduct{ProductType: "rice-grains"}) {
		t.Error("rice is not perishable")
	}
	if !perishableProduct(port.CatalogProduct{ProductType: "rice-grains", Attributes: map[string]string{"perishable": "true"}}) {
		t.Error("the attribute still counts")
	}
}
