package app

import (
	"testing"

	"github.com/billionairedev24/diaspora-commerce/go/services/gateway-bff/internal/domain"
)

// A package with only pickup must say why it can't be delivered, so the page never shows pickup as if it were a default.

func pkgWith(fulfilment string, opts ...domain.DeliveryOption) domain.CheckoutPackage {
	return domain.CheckoutPackage{CartPackage: domain.CartPackage{PackageID: "org:" + fulfilment, Fulfilment: fulfilment, DeliveryOptions: opts}}
}

func TestDeliveryUnavailableReason(t *testing.T) {
	pickup := domain.DeliveryOption{Key: domain.OptionPickup, Free: true}
	delivery := domain.DeliveryOption{Key: domain.OptionDelivery}
	quote := domain.DeliveryOption{Key: domain.OptionQuoteRequired}

	if got := deliveryUnavailableReason(pkgWith(domain.ShipsFromSeller, pickup)); got != domain.DeliveryWhySellerPickupOnly {
		t.Fatalf("a Pick up offer with a pickup point: %q", got)
	}
	if got := deliveryUnavailableReason(pkgWith(domain.ShipsFromSeller)); got != domain.DeliveryWhySellerPickupOnly {
		t.Fatalf("a Pick up offer with nothing: %q", got)
	}
	if got := deliveryUnavailableReason(pkgWith(domain.FulfilledByAfroTransact, delivery, pickup)); got != "" {
		t.Fatalf("delivery and pickup: %q", got)
	}
	if got := deliveryUnavailableReason(pkgWith(domain.FulfilledByAfroTransact, quote, pickup)); got != domain.DeliveryWhyAddress {
		t.Fatalf("outside every zone: %q", got)
	}
	far := pkgWith(domain.AfroTransactDelivery, delivery, pickup)
	far.LocalDelivery = &domain.CheckoutLocalDelivery{Available: false}
	if got := deliveryUnavailableReason(far); got != domain.DeliveryWhyAddress {
		t.Fatalf("beyond the store's range: %q", got)
	}
	near := pkgWith(domain.AfroTransactDelivery, delivery)
	near.LocalDelivery = &domain.CheckoutLocalDelivery{Available: true}
	if got := deliveryUnavailableReason(near); got != "" {
		t.Fatalf("store delivery in range: %q", got)
	}
}
