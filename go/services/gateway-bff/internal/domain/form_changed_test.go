package domain_test

import (
	"testing"

	"github.com/billionairedev24/diaspora-commerce/go/services/gateway-bff/internal/domain"
)

func TestFormChangedOnlyLooksAtTheNamedFields(t *testing.T) {
	form := domain.Form{ChangeKeys: []string{"method"}, Fields: []domain.FormField{
		{Key: "method", Value: "RULES"}, {Key: "reason"}}}
	if domain.FormChanged(form, map[string]any{"method": "RULES", "reason": "Reviewing the method"}) {
		t.Fatal("the same method with a reason is not a change")
	}
	if !domain.FormChanged(form, map[string]any{"method": "STRIPE_TAX", "reason": "Registrations done"}) {
		t.Fatal("a different method is a change")
	}
	if !domain.FormChanged(domain.Form{}, map[string]any{}) {
		t.Fatal("a form without change fields is always worth saving")
	}
}
