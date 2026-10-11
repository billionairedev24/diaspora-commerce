package app

import (
	"context"
	"encoding/json"
	"reflect"
	"testing"

	"github.com/billionairedev24/diaspora-commerce/go/services/gateway-bff/internal/domain"
	"github.com/billionairedev24/diaspora-commerce/go/services/gateway-bff/internal/port"
)

// deliveryRunsResolve is delivery-runs' GET /v1/public/preorders/resolve as the service answers it (recorded from the
// local stack after make seed): fees, payment methods, count and countdownTarget live at the top level.
const deliveryRunsResolve = `{"place":{"key":"US/TX/Lubbock","country":"US","region":"TX","city":"Lubbock"},"on":true,
 "stage":"OPEN","metro":false,"fallback":"SHIP","placements":{"ribbon":true,"hero":true,"badge":true,"pdp":true,"cart":true,
 "family":true},"window":{"id":"dfc0a099","code":"LUB-1","name":"Lubbock Pre-order #7","where":"Lubbock, TX",
 "ribbon":"Lubbock pre-order is open","headline":"Fresh egusi, garri and palm oil.","opensAt":"2026-10-08T09:00:00Z",
 "closesAt":"2026-10-20T23:59:00Z","deliversOn":"2026-10-23","timeZone":"America/Chicago",
 "categories":["Pantry","Spices & seasonings","Unknown aisle"],"door":true,"pickup":true,
 "pickupAddress":"Lubbock: South Plains Mall","creatives":{"hero":{"url":"http://localhost:9000/media/h.jpg","width":1600,
 "height":640}}},"countdownTarget":"2026-10-20T23:59:00Z","count":0,"fees":{"doorFeeMinor":799,"pickupFeeMinor":0,
 "currency":"USD","pickupWindow":"10am–2pm"},"paymentMethods":["CARD","WALLET","ACH"],"cancelUntil":"CLOSE"}`

// deliveryRunsOpen is GET /v1/public/preorders/open: a bare array with flat country / region / city.
const deliveryRunsOpen = `[{"id":"dfc0a099","code":"LUB-1","name":"Lubbock Pre-order #7","where":"Lubbock, TX","stage":"OPEN",
 "open":true,"city":"Lubbock","region":"TX","country":"US","heroUrl":"http://localhost:9000/media/h.jpg",
 "opensAt":"2026-10-08T09:00:00Z","closesAt":"2026-10-20T23:59:00Z","deliversOn":"2026-10-23","doorFeeMinor":799,
 "pickupFeeMinor":0,"currency":"USD"}]`

type stubPreorders struct{ resolve, open string }

func (s stubPreorders) Resolve(context.Context, port.PreorderArea) (json.RawMessage, error) {
	return json.RawMessage(s.resolve), nil
}
func (s stubPreorders) Open(context.Context) (json.RawMessage, error) {
	return json.RawMessage(s.open), nil
}
func (s stubPreorders) Waitlist(context.Context, port.PreorderWaitlist) (json.RawMessage, error) {
	return json.RawMessage(`{}`), nil
}
func (s stubPreorders) LeaveWaitlist(context.Context, string) error { return nil }

type stubCategories struct{ port.CatalogClient }

func (stubCategories) Categories(context.Context) ([]port.Category, error) {
	return []port.Category{{ID: "1", Name: "Groceries", Path: "Groceries"}, {ID: "2", Name: "Pantry", Path: "Groceries › Pantry"},
		{ID: "3", Name: "Spices & seasonings", Path: "Groceries › Pantry › Spices & seasonings"},
		{ID: "4", Name: "Butters & oils", Path: "Beauty › Skin care › Butters & oils"}}, nil
}

type stubLocation struct{ LocationService }

func (stubLocation) Current(context.Context, *domain.Caller) domain.DeliveryLocation {
	return domain.DeliveryLocation{ZIP: "79401", City: "Lubbock", State: "TX"}
}

func TestResolveMapsDeliveryRunsOntoTheStorefrontWindow(t *testing.T) {
	svc := NewPreorderService(stubPreorders{resolve: deliveryRunsResolve}, stubLocation{}, "US", WithPreorderCatalog(stubCategories{}))
	out, err := svc.Resolve(context.Background(), nil, "US", "TX", "Lubbock")
	if err != nil {
		t.Fatal(err)
	}
	if out["countdownTo"] != "2026-10-20T23:59:00Z" || out["on"] != true || out["covered"] != true {
		t.Fatalf("resolution %v", out)
	}
	w := out["window"].(map[string]any)
	want := map[string]any{"doorFeeMinor": float64(799), "pickupFeeMinor": float64(0), "currency": "USD",
		"pickupWindow": "10am–2pm", "count": float64(0), "showCount": true, "timeZone": "America/Chicago"}
	for k, v := range want {
		if !reflect.DeepEqual(w[k], v) {
			t.Errorf("window[%s] = %#v, want %#v", k, w[k], v)
		}
	}
	if !reflect.DeepEqual(w["allowedMethods"], []any{"CARD", "WALLET", "ACH"}) {
		t.Errorf("allowedMethods %v", w["allowedMethods"])
	}
	if !reflect.DeepEqual(w["departments"], []string{"Groceries"}) {
		t.Errorf("departments %v", w["departments"])
	}
	creatives := w["creatives"].(map[string]any)
	if creatives["hero"] == nil || creatives["pdp"] != nil {
		t.Errorf("creatives %v", creatives)
	}
	if _, ok := creatives["pdp"]; !ok {
		t.Error("pdp slot must be present (null)")
	}
}

func TestResolveKeepsTheStorefrontShapeAndToleratesNoWindow(t *testing.T) {
	storefront := `{"on":true,"covered":true,"stage":"OPEN","countdownTo":"X","window":{"doorFeeMinor":500,"currency":"CAD",
	 "allowedMethods":["CARD"],"count":null,"showCount":false,"departments":["Beauty"],"categories":[],"pickupWindow":"",
	 "timeZone":"America/Toronto","creatives":{"hero":null,"pdp":null}},"fallback":"SHIP"}`
	svc := NewPreorderService(stubPreorders{resolve: storefront}, stubLocation{}, "US")
	out, err := svc.Resolve(context.Background(), nil, "US", "TX", "Lubbock")
	if err != nil {
		t.Fatal(err)
	}
	w := out["window"].(map[string]any)
	if w["doorFeeMinor"] != float64(500) || w["currency"] != "CAD" || w["showCount"] != false || w["count"] != nil ||
		!reflect.DeepEqual(w["departments"], []any{"Beauty"}) || out["countdownTo"] != "X" {
		t.Fatalf("storefront shape changed: %v", out)
	}
	off, err := NewPreorderService(stubPreorders{resolve: `{"on":false,"window":null}`}, stubLocation{}, "US").
		Resolve(context.Background(), nil, "US", "TX", "Houston")
	if err != nil || off["window"] != nil || off["countdownTo"] != nil {
		t.Fatalf("off: %v %v", off, err)
	}
}

func TestOpenGivesEachWindowAPlace(t *testing.T) {
	out, err := NewPreorderService(stubPreorders{open: deliveryRunsOpen}, stubLocation{}, "US").Open(context.Background())
	if err != nil {
		t.Fatal(err)
	}
	windows := out["windows"].([]any)
	w := windows[0].(map[string]any)
	if !reflect.DeepEqual(w["place"], map[string]any{"country": "US", "region": "TX", "city": "Lubbock"}) ||
		w["timeZone"] != "America/Chicago" || w["zip"] != nil {
		t.Fatalf("list item %v", w)
	}
	if _, ok := w["zip"]; !ok {
		t.Fatal("zip must be present (null)")
	}
}
