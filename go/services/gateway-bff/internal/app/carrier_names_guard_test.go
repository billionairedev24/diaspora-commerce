package app

import (
	"encoding/json"
	"regexp"
	"testing"
	"time"

	"github.com/billionairedev24/diaspora-commerce/go/services/gateway-bff/internal/domain"
)

// carrierName matches any carrier or carrier service we must never show a shopper while we are not shipping by carrier
// (listing rules section 3). Mirrors the web and mobile guards (carrier-names.test.ts).
var carrierName = regexp.MustCompile(`(?i)\b(usps|ups|fedex|dhl)\b|ground advantage|next day air|priority mail|2nd day|overnight`)

// shopperText returns every string in v (any JSON shape): the labels, prefixes, ETAs and notes a shopper reads.
func shopperText(t *testing.T, v any) []string {
	t.Helper()
	raw, err := json.Marshal(v)
	if err != nil {
		t.Fatal(err)
	}
	var tree any
	if err := json.Unmarshal(raw, &tree); err != nil {
		t.Fatal(err)
	}
	var out []string
	var walk func(any)
	walk = func(n any) {
		switch x := n.(type) {
		case string:
			out = append(out, x)
		case []any:
			for _, e := range x {
				walk(e)
			}
		case map[string]any:
			for _, e := range x {
				walk(e)
			}
		}
	}
	walk(tree)
	return out
}

// TestNoShopperFacingTextNamesACarrierWhileTheSellerCarrierSwitchIsOff is the guard: every option, promise and message
// the BFF can show a shopper is free of carrier names, for every package kind, fee setting and cart value, with the staff
// switch off. (With it on the labels stay generic too: the carrier is never named.)
func TestNoShopperFacingTextNamesACarrierWhileTheSellerCarrierSwitchIsOff(t *testing.T) {
	now := time.Date(2026, 11, 11, 12, 0, 0, 0, time.UTC)
	five := int64(5000)
	for _, on := range []bool{false, true} {
		for _, free := range []*int64{nil, &five} {
			e := domain.NewEstimator(domain.DeliveryPolicy{SellerShippingMinor: 599, FreeShippingThresholdMinor: 3500})
			set := e.(domain.ShippingSettingsSetter)
			set.SetShippingSettings(&domain.ShippingSettings{Currency: "USD", Version: 1, FlatFeeMinor: 599, FreeAboveMinor: free, SellerCarrierShippingEnabled: on})
			e.(domain.CarrierRuleSetter).SetCarrierRule(&domain.CarrierRule{RuleID: "r1", Version: 2, FallbackMinor: 1350})
			for _, kind := range []string{domain.FulfilledByAfroTransact, domain.AfroTransactDelivery, domain.ShipsFromSeller} {
				for _, items := range []int64{0, 1000, 4999, 5000, 99999} {
					var all []any
					all = append(all, e.Options(kind, 0, items, now), e.Promise(kind, 0, now), e.Nudge(kind, items))
					pkg := domain.CheckoutPackage{CartPackage: domain.CartPackage{PackageID: "p", Fulfilment: kind, DeliveryOptions: e.Options(kind, 0, items, now)}}
					addPickupOption(&pkg, []domain.PickupPoint{{ID: "pp", Ready: "Ready the same day"}})
					all = append(all, pkg.DeliveryOptions, quoteRequiredOptions(pkg))
					for _, text := range shopperText(t, all) {
						if carrierName.MatchString(text) {
							t.Errorf("carrier named in shopper text %q (kind %s, items %d, switch %v)", text, kind, items, on)
						}
					}
				}
			}
		}
	}
	for name, text := range map[string]string{
		"delivery":        domain.LabelDeliveryByAfroTransact,
		"store delivery":  domain.LabelStoreDelivery,
		"pickup":          domain.LabelPickupPoint,
		"store pickup":    domain.LabelStorePickup,
		"seller shipping": domain.LabelSellerShipping,
		"quote required":  domain.LabelQuoteRequired,
		"fee unknown":     domain.LabelFeeUnknown,
		"quote message":   quoteRequiredMessage,
		"fee message":     feeUnknownMessage,
		"heading":         domain.LocalDeliveryHeading("Houston, TX"),
	} {
		if carrierName.MatchString(text) {
			t.Errorf("%s names a carrier: %q", name, text)
		}
	}
	if !carrierName.MatchString("UPS Ground Advantage") || !carrierName.MatchString("Next Day Air") || carrierName.MatchString("Delivery by AfroTransact") {
		t.Fatal("the guard's pattern must catch carriers and spare our own words")
	}
}
