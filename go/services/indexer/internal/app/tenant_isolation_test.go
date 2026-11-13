package app_test

import (
	"testing"

	"github.com/billionairedev24/diaspora-commerce/go/pkg/tenanttest"
	"github.com/billionairedev24/diaspora-commerce/go/services/indexer"
)

// TestRouteMatrix: the indexer exposes no tenant-facing HTTP route. A route added to its code (cmd or adapters) must be classified.
func TestRouteMatrix(t *testing.T) {
	m, err := indexer.Files.ReadFile("tenant_isolation_matrix.txt")
	if err != nil {
		t.Fatal(err)
	}
	tenanttest.CheckRoutes(t, tenanttest.PatternsFromSource(t, "../../cmd", "../adapter", "."), string(m), ".")
}
