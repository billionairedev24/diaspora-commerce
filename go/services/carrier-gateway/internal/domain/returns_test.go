package domain_test

import (
	"errors"
	"testing"

	"github.com/billionairedev24/diaspora-commerce/go/services/carrier-gateway/internal/domain"
)

func TestChooseQuoteByRateChoice(t *testing.T) {
	quotes := []domain.Quote{
		{Carrier: domain.UPS, Service: "03", AmountMinor: 1100, TransitDays: 3},
		{Carrier: domain.USPS, Service: "GROUND", AmountMinor: 900, TransitDays: 5},
		{Carrier: domain.FedEx, Service: "2DAY", AmountMinor: 2100, TransitDays: 2},
		{Carrier: domain.DHL, Service: "N", AmountMinor: 900},
	}
	cheapest, err := domain.ChooseQuote(quotes, domain.RateCheapest)
	if err != nil || cheapest.Carrier != domain.USPS {
		t.Fatalf("cheapest (ties: known transit first) = %+v %v", cheapest, err)
	}
	fastest, err := domain.ChooseQuote(quotes, domain.RateFastest)
	if err != nil || fastest.Carrier != domain.FedEx {
		t.Fatalf("fastest = %+v %v", fastest, err)
	}
	if quotes[0].Carrier != domain.UPS {
		t.Fatal("ChooseQuote must not reorder its input")
	}
	if _, err := domain.ChooseQuote(nil, domain.RateCheapest); !errors.Is(err, domain.ErrCarrierRejected) {
		t.Fatalf("no quotes: %v", err)
	}
	if _, err := domain.ChooseQuote(quotes, domain.RateService); !errors.Is(err, domain.ErrInvalid) {
		t.Fatalf("SERVICE is not chosen from quotes: %v", err)
	}
}

func TestParseRateChoiceAndReturnMode(t *testing.T) {
	if c, err := domain.ParseRateChoice(""); err != nil || c != domain.RateCheapest {
		t.Fatalf("default choice %q %v", c, err)
	}
	if c, err := domain.ParseRateChoice("fastest"); err != nil || c != domain.RateFastest {
		t.Fatalf("case-insensitive %q %v", c, err)
	}
	if _, err := domain.ParseRateChoice("random"); !errors.Is(err, domain.ErrInvalid) {
		t.Fatalf("unknown choice %v", err)
	}
	if m, err := domain.ParseReturnMode("box_free_qr"); err != nil || m != domain.ReturnBoxFreeQR {
		t.Fatalf("mode %q %v", m, err)
	}
	if m, err := domain.ParseReturnMode(""); err != nil || m != domain.ReturnPrintedLabel {
		t.Fatalf("default mode %q %v", m, err)
	}
	if _, err := domain.ParseReturnMode("drone"); !errors.Is(err, domain.ErrInvalid) {
		t.Fatalf("unknown mode %v", err)
	}
	if domain.FormatPNG.ContentType() != "image/png" {
		t.Fatal("QR images are PNG")
	}
}
