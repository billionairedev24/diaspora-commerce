package app_test

import (
	"testing"

	"github.com/billionairedev24/diaspora-commerce/go/pkg/tenanttest"
	authzsync "github.com/billionairedev24/diaspora-commerce/go/services/authz-sync"
)

// TestRouteMatrix: authz-sync exposes no tenant-facing HTTP route. A route added to its code must be classified.
func TestRouteMatrix(t *testing.T) {
	m, err := authzsync.Files.ReadFile("tenant_isolation_matrix.txt")
	if err != nil {
		t.Fatal(err)
	}
	tenanttest.CheckRoutes(t, tenanttest.PatternsFromSource(t, "../../cmd", "../adapter", "."), string(m), ".")
}
