package domain

import (
	"slices"
	"testing"
)

func TestHandoverCodeIsFourDigits(t *testing.T) {
	for v, want := range map[string]bool{"0427": true, "1234": true, "": false, "123": false, "12345": false, "12a4": false, " 123": false} {
		if got := IsHandoverCode(v); got != want {
			t.Errorf("IsHandoverCode(%q) = %v, want %v", v, got, want)
		}
	}
}

func TestSellersMayHandAPickupOrderOverButOnlyWhenOrdersAllowsIt(t *testing.T) {
	if !IsSellerStatus("DELIVERED") {
		t.Fatal("a seller hands a pickup order over at the shop")
	}
	if got := SellerNextActions([]string{"delivered"}); !slices.Equal(got, []string{"DELIVERED"}) {
		t.Fatalf("next actions = %v", got)
	}
	if got := SellerNextActions([]string{"ready", "in_transit"}); !slices.Equal(got, []string{"READY"}) {
		t.Fatalf("a step that is not the seller's is dropped, got %v", got)
	}
}
