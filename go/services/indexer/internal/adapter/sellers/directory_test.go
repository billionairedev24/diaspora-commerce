package sellers_test

import (
	"context"
	"testing"

	"github.com/billionairedev24/diaspora-commerce/go/services/indexer/internal/adapter/sellers"
)

func TestDirectoryLearnsNames(t *testing.T) {
	ctx := context.Background()
	d := sellers.NewMemoryDirectory(map[string]string{"org_r": "AfroTransact Retail"}, "Marketplace seller")
	if n, known, _ := d.DisplayName(ctx, "org_r"); n != "AfroTransact Retail" || !known {
		t.Fatalf("configured name: %q %v", n, known)
	}
	if n, known, _ := d.DisplayName(ctx, "org_x"); n != "Marketplace seller" || known {
		t.Fatalf("fallback: %q %v", n, known)
	}
	d.Remember(ctx, "org_x", "Kemi's Pantry")
	d.Remember(ctx, "org_y", "")
	if n, known, _ := d.DisplayName(ctx, "org_x"); n != "Kemi's Pantry" || !known {
		t.Fatalf("learned name: %q %v", n, known)
	}
	if _, known, _ := d.DisplayName(ctx, "org_y"); known {
		t.Fatal("empty names are not remembered")
	}
}
