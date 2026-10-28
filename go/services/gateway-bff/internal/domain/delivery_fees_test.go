package domain_test

import (
	"errors"
	"reflect"
	"testing"

	"github.com/billionairedev24/diaspora-commerce/go/services/gateway-bff/internal/domain"
)

func band(from, to, fee any) map[string]any {
	return map[string]any{"fromMiles": from, "toMiles": to, "feeMinor": fee}
}

func fieldsOf(t *testing.T, err error) map[string]string {
	t.Helper()
	var p *domain.Problem
	if !errors.As(err, &p) {
		t.Fatalf("want a problem, got %v", err)
	}
	return p.Errors
}

func TestFeeMethodAndCurrencyAreChecked(t *testing.T) {
	for in, want := range map[string]string{"afrotransact_delivery": "AFROTRANSACT_DELIVERY", " carrier ": "CARRIER", "Pickup": "PICKUP"} {
		if got, ok := domain.ValidFeeMethod(in); !ok || got != want {
			t.Errorf("method %q = %q %v", in, got, ok)
		}
	}
	for _, in := range []string{"", "drone", "AFROTRANSACT", "PICKUP;"} {
		if _, ok := domain.ValidFeeMethod(in); ok {
			t.Errorf("method %q should be refused", in)
		}
	}
	if c, ok := domain.ValidFeeCurrency(" usd "); !ok || c != "USD" {
		t.Errorf("currency = %q %v", c, ok)
	}
	for _, in := range []string{"", "US", "USDX", "12A", "US$"} {
		if _, ok := domain.ValidFeeCurrency(in); ok {
			t.Errorf("currency %q should be refused", in)
		}
	}
	if p := domain.FeeNotFound(); p.Status != 404 {
		t.Errorf("not found = %d", p.Status)
	}
}

func TestAfroTransactBandsAreSortedAndMustTouchFromZero(t *testing.T) {
	got, err := domain.DeliveryFeeRuleBody("AFROTRANSACT_DELIVERY", map[string]any{
		"reason": "ignored here", "feeMinor": float64(5),
		"bands": []any{band(10.0, 40.0, 799.0), band(0.0, 10.0, 599.0), band(40.0, 80.5, 1299.0)}})
	if err != nil {
		t.Fatal(err)
	}
	want := map[string]any{"bands": []map[string]any{
		{"fromMiles": 0.0, "toMiles": 10.0, "feeMinor": int64(599)},
		{"fromMiles": 10.0, "toMiles": 40.0, "feeMinor": int64(799)},
		{"fromMiles": 40.0, "toMiles": 80.5, "feeMinor": int64(1299)}}}
	if !reflect.DeepEqual(got, want) {
		t.Fatalf("relayed %#v, want %#v", got, want)
	}
}

func TestAfroTransactBandProblemsNameTheField(t *testing.T) {
	cases := map[string]struct {
		bands any
		field string
	}{
		"none":           {nil, "bands"},
		"empty":          {[]any{}, "bands"},
		"too many":       {manyBands(13), "bands"},
		"not from zero":  {[]any{band(5.0, 10.0, 599.0)}, "bands[0].fromMiles"},
		"gap":            {[]any{band(0.0, 10.0, 599.0), band(12.0, 20.0, 799.0)}, "bands[1].fromMiles"},
		"overlap":        {[]any{band(0.0, 10.0, 599.0), band(8.0, 20.0, 799.0)}, "bands[1].fromMiles"},
		"ends before":    {[]any{band(0.0, 0.0, 599.0)}, "bands[0].toMiles"},
		"too far":        {[]any{band(0.0, 501.0, 599.0)}, "bands[0].toMiles"},
		"miles missing":  {[]any{map[string]any{"fromMiles": 0.0, "feeMinor": 1.0}}, "bands[0].toMiles"},
		"three decimals": {[]any{band(0.0, 10.125, 599.0)}, "bands[0].toMiles"},
		"fee too high":   {[]any{band(0.0, 10.0, 10001.0)}, "bands[0].feeMinor"},
		"fee fraction":   {[]any{band(0.0, 10.0, 5.99)}, "bands[0].feeMinor"},
		"fee missing":    {[]any{map[string]any{"fromMiles": 0.0, "toMiles": 10.0}}, "bands[0].feeMinor"},
		"negative fee":   {[]any{band(0.0, 10.0, -1.0)}, "bands[0].feeMinor"},
		"not an object":  {[]any{"0-10"}, "bands[0].fromMiles"},
	}
	for name, c := range cases {
		_, err := domain.DeliveryFeeRuleBody("AFROTRANSACT_DELIVERY", map[string]any{"bands": c.bands})
		if fields := fieldsOf(t, err); fields[c.field] == "" {
			t.Errorf("%s: no problem under %s: %v", name, c.field, fields)
		}
	}
}

func manyBands(n int) []any {
	out := make([]any, n)
	for i := range out {
		out[i] = band(float64(i), float64(i+1), 100.0)
	}
	return out
}

func TestCarrierRuleKeepsHandlingFallbackAndAnOptionalThreshold(t *testing.T) {
	got, err := domain.DeliveryFeeRuleBody("CARRIER", map[string]any{"handlingMinor": 200.0, "fallbackMinor": 799.0, "freeAboveMinor": 7500.0})
	if err != nil {
		t.Fatal(err)
	}
	if !reflect.DeepEqual(got, map[string]any{"handlingMinor": int64(200), "fallbackMinor": int64(799), "freeAboveMinor": int64(7500)}) {
		t.Fatalf("relayed %#v", got)
	}
	got, err = domain.DeliveryFeeRuleBody("CARRIER", map[string]any{"handlingMinor": 0.0, "fallbackMinor": 599.0, "freeAboveMinor": nil, "bands": []any{band(0.0, 5.0, 1.0)}})
	if err != nil {
		t.Fatal(err)
	}
	if _, has := got["freeAboveMinor"]; has || got["bands"] != nil {
		t.Fatalf("no threshold, no bands for the carrier: %#v", got)
	}
	_, err = domain.DeliveryFeeRuleBody("CARRIER", map[string]any{"handlingMinor": 20000.0, "fallbackMinor": "five", "freeAboveMinor": -1.0})
	fields := fieldsOf(t, err)
	for _, k := range []string{"handlingMinor", "fallbackMinor", "freeAboveMinor"} {
		if fields[k] == "" {
			t.Errorf("no problem under %s: %v", k, fields)
		}
	}
}

func TestPickupFeeIsRequiredAndCanBeZero(t *testing.T) {
	got, err := domain.DeliveryFeeRuleBody("PICKUP", map[string]any{"feeMinor": 0.0})
	if err != nil || !reflect.DeepEqual(got, map[string]any{"feeMinor": int64(0)}) {
		t.Fatalf("free pickup: %#v %v", got, err)
	}
	for _, body := range []map[string]any{{}, {"feeMinor": nil}, {"feeMinor": 10001.0}, {"feeMinor": 1.5}, {"feeMinor": "free"}} {
		if _, err := domain.DeliveryFeeRuleBody("PICKUP", body); fieldsOf(t, err)["feeMinor"] == "" {
			t.Errorf("%v should be refused", body)
		}
	}
	if _, err := domain.DeliveryFeeRuleBody("DRONE", map[string]any{}); err == nil {
		t.Error("an unknown method is not a rule")
	}
}
