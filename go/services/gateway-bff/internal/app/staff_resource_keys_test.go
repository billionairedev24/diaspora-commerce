package app

import (
	"slices"
	"testing"

	"github.com/billionairedev24/diaspora-commerce/go/services/gateway-bff/internal/domain"
)

// The HTTP layer answers 404 for a table that is not in domain.ResourceKeys, so a registered table missing from the list
// is a screen that cannot load (this once hid Organisations and Stripe Connect), and a listed key without a table is a 500.
func TestEveryRegisteredTableIsAnAllowedResourceKey(t *testing.T) {
	registered := staffResources()
	for key := range registered {
		if !slices.Contains(domain.ResourceKeys, key) {
			t.Errorf("table %q is registered but missing from domain.ResourceKeys", key)
		}
	}
	for _, key := range domain.ResourceKeys {
		if _, ok := registered[key]; !ok {
			t.Errorf("domain.ResourceKeys lists %q but no table is registered for it", key)
		}
	}
}
