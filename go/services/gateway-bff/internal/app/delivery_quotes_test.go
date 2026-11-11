package app

import (
	"context"
	"net/http"
	"strings"
	"testing"

	"github.com/billionairedev24/diaspora-commerce/go/services/gateway-bff/internal/domain"
	"github.com/billionairedev24/diaspora-commerce/go/services/gateway-bff/internal/port"
)

type fakeQuotes struct {
	asked []port.UpQuoteRequest
	err   error
}

func (f *fakeQuotes) Request(_ context.Context, _ string, req port.UpQuoteRequest) (domain.QuoteRequest, error) {
	f.asked = append(f.asked, req)
	return domain.QuoteRequest{ID: "q1", Status: "REQUESTED", StatusLabel: "Requested"}, f.err
}
func (f *fakeQuotes) List(context.Context, string) ([]domain.QuoteRequest, error) { return nil, f.err }
func (f *fakeQuotes) Get(context.Context, string, string) (domain.QuoteRequest, error) {
	return domain.QuoteRequest{}, f.err
}

func shopperCaller() *domain.Caller {
	return &domain.Caller{BearerToken: "tok", Subject: "cust-1"}
}

func TestDeliveryQuoteRequestNeedsSignInAndValidInput(t *testing.T) {
	f := &fakeQuotes{}
	s := NewDeliveryQuotesService(f)
	if _, err := s.Request(context.Background(), &domain.Caller{}, domain.NewQuoteRequest{AddressID: "a"}); err == nil {
		t.Fatal("a signed-out shopper must sign in first")
	}
	for name, req := range map[string]domain.NewQuoteRequest{
		"no address":  {},
		"bad contact": {AddressID: "a", Contact: "pigeon"},
		"long note":   {AddressID: "a", Note: strings.Repeat("é", 501)},
		"bad line":    {AddressID: "a", Lines: []domain.NewQuoteRequestLine{{VariantAtin: "A", Quantity: 0}}},
	} {
		_, err := s.Request(context.Background(), shopperCaller(), req)
		p, ok := domain.AsProblem(err)
		if !ok || p.Status != http.StatusUnprocessableEntity {
			t.Errorf("%s: %v", name, err)
		}
	}
	if len(f.asked) != 0 {
		t.Fatalf("invalid requests reached orders: %v", f.asked)
	}
	if _, err := s.Request(context.Background(), shopperCaller(), domain.NewQuoteRequest{AddressID: " a ", Contact: "sms", Note: " gate "}); err != nil {
		t.Fatal(err)
	}
	if got := f.asked[0]; got.AddressID != "a" || got.Contact != "SMS" || got.Note != "gate" {
		t.Fatalf("relayed %+v", got)
	}
}

func TestOrdersQuoteRefusalsAreOneProblemWithAPlainMessage(t *testing.T) {
	for code, reason := range map[string]string{"orders.quote-changed": "changed", "orders.quote-expired": "expired", "orders.quote-used": "used",
		"orders.quote-not-ready": "not_ready", "orders.quote-not-found": "not_found"} {
		pr := quoteProblem(&port.UpstreamProblem{Status: 422, Code: code, Detail: "Your cart or delivery address changed since this quote was agreed."})
		if pr == nil || pr.Code != domain.CodeDeliveryQuoteVoid || pr.Status != 422 || pr.Extra["reason"] != reason || !strings.Contains(pr.Detail, "changed") {
			t.Errorf("%s -> %+v", code, pr)
		}
	}
	if quoteProblem(&port.UpstreamProblem{Status: 422, Code: "orders.cart-empty"}) != nil {
		t.Fatal("other problems are not quote problems")
	}
	// orderErr (place order) keeps them.
	pr, ok := domain.AsProblem(orderErr(&port.UpstreamProblem{Status: 422, Code: "orders.quote-expired", Detail: "This delivery quote has expired. Ask for a new one."}))
	if !ok || pr.Code != domain.CodeDeliveryQuoteVoid {
		t.Fatalf("place order: %+v", pr)
	}
}

func TestDeliverySelectionsCarryAQuoteButNotForPickup(t *testing.T) {
	sels, errs := selections([]domain.PlaceOrderRequestSelection{{PackageID: "p1", Option: "carrier", QuoteID: " q-1 "}})
	if len(errs) != 0 || sels[0].QuoteID != "q-1" {
		t.Fatalf("%v %v", sels, errs)
	}
	_, errs = selections([]domain.PlaceOrderRequestSelection{{PackageID: "p1", Option: "pickup", PickupPointID: "pp", QuoteID: "q-1"}})
	if errs["deliverySelections.0.quoteId"] == "" {
		t.Fatalf("a quote on pickup must be refused: %v", errs)
	}
}
