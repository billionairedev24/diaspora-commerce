package httpapi_test

import (
	"strings"
	"testing"

	"github.com/billionairedev24/diaspora-commerce/go/services/gateway-bff/internal/testkit"
)

// S222: the buy box and "Other sellers" come from the offers service; search cards carry offer counts and the lowest
// price; add-to-cart validates offer ids (buyable) through offers and the cart line keeps the offer.
func TestProductPageSearchAndCartUseOffers(t *testing.T) {
	bff, b := setup(t, testkit.Options{Offers: true})
	p := b.do("GET", "/v1/products/ATSHEAV001", nil).json(t)
	bb := p["buyBox"].(map[string]any)
	if bb["offerId"] != testkit.OfferKenteSheaID || bb["seller"].(map[string]any)["city"] != "Atlanta" || num(bb, "priceMinor") != 1250 {
		t.Fatalf("buy box = %v", bb)
	}
	others := p["otherOffers"].([]any)
	if len(others) != 1 || others[0].(map[string]any)["offerId"] != testkit.OfferAccraSheaID || p["soldBy"] != "Kente Naturals" {
		t.Fatalf("other offers = %v (soldBy %v)", others, p["soldBy"])
	}
	if g := b.do("GET", "/v1/products/ATGARI0001", nil).json(t); g["buyBox"] != nil || len(g["otherOffers"].([]any)) != 0 {
		t.Fatalf("no offers ⇒ currently unavailable: %v", g)
	}

	hits := b.do("GET", "/v1/search?q=shea", nil).json(t)["hits"].([]any)
	card := hits[0].(map[string]any)
	if num(card, "offerCount") != 2 || num(card, "minOfferPriceMinor") != 1250 || card["offerId"] != testkit.OfferKenteSheaID {
		t.Fatalf("search card = %v", card)
	}
	if bff.Upstreams.CallCount("GET /v1/public/buy-boxes") != 1 {
		t.Fatal("search cards must use one batch buy-box call")
	}

	cart := b.do("POST", "/v1/cart/items", map[string]any{"offerId": testkit.OfferAccraSheaID, "quantity": 2})
	mustStatus(t, cart, 200)
	line := lines(t, cart.json(t))[0]
	if line["offerId"] != testkit.OfferAccraSheaID || line["lineId"] != "ATSHEAV001" {
		t.Fatalf("cart line = %v", line)
	}
	r := b.do("POST", "/v1/cart/items", map[string]any{"offerId": testkit.OfferPausedID, "quantity": 1})
	mustStatus(t, r, 404)
	if c := problemCode(t, r); c != "OFFER_NOT_FOUND" {
		t.Fatalf("paused offer code = %s", c)
	}
	mustStatus(t, b.do("POST", "/v1/cart/items", map[string]any{"offerId": "0a0a0a0a-0000-4000-8000-0000000000aa", "quantity": 1}), 404)
	// ATIN offer ids keep working (catalog's single seller).
	mustStatus(t, b.do("POST", "/v1/cart/items", map[string]any{"offerId": "ATJOLLOFV1", "quantity": 1}), 200)
}

// Without the offers service the page falls back to the catalog's single seller.
func TestProductPageWithoutOffersUsesCatalogSeller(t *testing.T) {
	_, b := setup(t, testkit.Options{})
	p := b.do("GET", "/v1/products/ATSHEAV001", nil).json(t)
	if p["buyBox"].(map[string]any)["offerId"] != "ATSHEAV001" {
		t.Fatalf("fallback buy box = %v", p["buyBox"])
	}
}

func TestStockAlertsWithNotifications(t *testing.T) {
	bff, b := setup(t, testkit.Options{Notifications: true})
	mustStatus(t, b.do("POST", "/v1/products/ATGARI0001/stock-alerts", map[string]any{"email": "a@example.com"}), 401)
	mustStatus(t, signIn(t, bff, b, "/"), 302)
	r := b.do("POST", "/v1/products/ATGARI0001/stock-alerts", map[string]any{})
	mustStatus(t, r, 201)
	mustStatus(t, b.do("POST", "/v1/products/ATGARI0001/stock-alerts", map[string]any{}), 201) // 200 upstream: still subscribed
	items := b.do("GET", "/v1/account/stock-alerts", nil).json(t)["items"].([]any)
	if len(items) != 1 || items[0].(map[string]any)["atin"] != "ATGARIV001" || items[0].(map[string]any)["productTitle"] != "Ijebu Garri" ||
		items[0].(map[string]any)["status"] != "active" {
		t.Fatalf("alerts = %v", items)
	}
	mustStatus(t, b.do("DELETE", "/v1/account/stock-alerts/ATGARIV001", nil), 204)
	mustStatus(t, b.do("DELETE", "/v1/account/stock-alerts/ATGARIV001", nil), 404)
}

func TestCareersResumeUpload(t *testing.T) {
	bff, b := setup(t, testkit.Options{Content: true})
	r := b.do("POST", "/v1/careers/uploads", map[string]any{"fileName": "Ada Obi CV.pdf", "contentType": "application/pdf", "size": 120000})
	mustStatus(t, r, 201)
	up := r.json(t)
	if up["assetId"] == "" || up["upload"].(map[string]any)["method"] != "PUT" || num(up, "maxBytes") != 5<<20 {
		t.Fatalf("upload = %v", up)
	}
	if got := bff.Upstreams.ServiceTokenRequests; len(got) == 0 || got[len(got)-1] != "" {
		t.Fatalf("guest uploads must use the BFF's client-credentials token: %v", got)
	}
	for _, bad := range []map[string]any{
		{"fileName": "cv.docx", "contentType": "application/vnd.openxmlformats-officedocument.wordprocessingml.document", "size": 1000},
		{"fileName": "cv.pdf", "contentType": "application/pdf", "size": 6 << 20},
	} {
		mustStatus(t, b.do("POST", "/v1/careers/uploads", bad), 422)
	}
	done := b.do("POST", "/v1/careers/uploads/"+up["assetId"].(string)+"/complete", nil)
	mustStatus(t, done, 200)
	if done.json(t)["status"] != "READY" {
		t.Fatalf("complete = %s", done.body)
	}
	app := b.do("POST", "/v1/careers/jobs/eng-1/applications", map[string]any{"name": "Ada Obi", "email": "ada@example.com", "linkedin": "",
		"workAuthorization": "Yes", "resumeAssetId": up["assetId"], "resumeFileName": "Ada Obi CV.pdf", "consent": true})
	mustStatus(t, app, 201)
	if ref, _ := app.json(t)["reference"].(string); !strings.HasPrefix(ref, "CAR-") {
		t.Fatalf("application = %s", app.body)
	}
}
