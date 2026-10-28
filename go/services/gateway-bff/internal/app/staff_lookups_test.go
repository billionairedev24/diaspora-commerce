package app

import (
	"context"
	"testing"

	"github.com/billionairedev24/diaspora-commerce/go/services/gateway-bff/internal/domain"
)

// lookupFieldOf finds a field of a form and says which picker it is.
func lookupFieldOf(t *testing.T, f *domain.Form, key string) domain.FormField {
	t.Helper()
	for _, fld := range f.Fields {
		if fld.Key == key {
			return fld
		}
	}
	t.Fatalf("form %q has no field %q", f.Title, key)
	return domain.FormField{}
}

// Assign driver and Stripe lookup ask for the driver and the seller by name, not for a typed id.
func TestFormsUseSearchablePickers(t *testing.T) {
	for _, a := range routesResource().actions {
		if a.key != "assign" {
			continue
		}
		f := lookupFieldOf(t, a.form(context.Background(), staffCtx{}, jobj{}), "driverId")
		if f.Lookup != lookupDrivers || f.Label != "Driver" || !f.Required {
			t.Errorf("assign driver field = %+v", f)
		}
		if module, _ := LookupModule(f.Lookup); module != dispatchModule {
			t.Errorf("drivers are looked up in %q", module)
		}
		return
	}
	t.Fatal("routes have no assign action")
}

func TestStripeLookupAsksForTheSellerByName(t *testing.T) {
	form := stripeLookupResource().create.form(context.Background(), staffCtx{})
	f := lookupFieldOf(t, &form, "orgId")
	if f.Lookup != lookupSellers || f.Required || f.Label == "" || f.Label == "Seller's organisation id (for a payout)" {
		t.Errorf("stripe lookup seller field = %+v", f)
	}
	if module, ok := LookupModule(f.Lookup); !ok || module != "payouts" {
		t.Errorf("sellers are looked up in %q", module)
	}
	if _, ok := LookupModule("orders"); ok {
		t.Error("an unknown kind has a lookup")
	}
}
