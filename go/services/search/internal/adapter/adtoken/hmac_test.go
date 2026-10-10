package adtoken_test

import (
	"errors"
	"strings"
	"testing"

	"github.com/billionairedev24/diaspora-commerce/go/services/search/internal/adapter/adtoken"
	"github.com/billionairedev24/diaspora-commerce/go/services/search/internal/domain"
)

const key = "test-ads-token-key-0123456789abcdef"

func TestSignVerifyRoundTrip(t *testing.T) {
	tokens, err := adtoken.New(key)
	if err != nil {
		t.Fatal(err)
	}
	in := domain.AdToken{Version: 1, SearchID: "s1", CampaignID: "cmp_1", SellerOrgID: "org_1", ATIN: "AT00000001",
		VariantATINs: []string{"AT00000002"}, PriceMinor: 35, Currency: "USD", Market: "us", Query: "egusi", Slot: 1,
		ExpiresAt: 1_900_000_000}
	tok, err := tokens.Sign(in)
	if err != nil {
		t.Fatal(err)
	}
	if strings.ContainsAny(tok, "+/= ") {
		t.Fatalf("token is not URL-safe: %s", tok)
	}
	out, err := tokens.Verify(tok)
	if err != nil || out.PriceMinor != 35 || out.CampaignID != "cmp_1" || out.VariantATINs[0] != "AT00000002" {
		t.Fatalf("verify: %+v %v", out, err)
	}
}

func TestTamperedTokensAreRejected(t *testing.T) {
	tokens, _ := adtoken.New(key)
	tok, _ := tokens.Sign(domain.AdToken{Version: 1, CampaignID: "cmp_1", PriceMinor: 35})
	payload, sig, _ := strings.Cut(tok, ".")
	other, _ := adtoken.New(key + "-other")
	forged, _ := other.Sign(domain.AdToken{Version: 1, CampaignID: "cmp_1", PriceMinor: 1})
	for _, bad := range []string{"", "abc", payload + ".", payload[:len(payload)-2] + "AA." + sig, forged} {
		if _, err := tokens.Verify(bad); !errors.Is(err, adtoken.ErrInvalid) {
			t.Fatalf("%q accepted: %v", bad, err)
		}
	}
	if _, err := adtoken.New("short"); err == nil {
		t.Fatal("short keys are refused")
	}
}
