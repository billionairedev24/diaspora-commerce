package app

import (
	"encoding/json"
	"os"
	"slices"
	"testing"
)

// vectorsPath is shared with orders (ShippingAllocationTest.java): the estimate and the order must agree.
const vectorsPath = "../../../../../tools/checkout/shipping-allocation-vectors.json"

func TestShippingAllocationSharedVectors(t *testing.T) {
	raw, err := os.ReadFile(vectorsPath)
	if err != nil {
		t.Fatal(err)
	}
	var doc struct {
		Cases []struct {
			Name     string  `json:"name"`
			Method   string  `json:"method"`
			Total    int64   `json:"totalMinor"`
			Weights  []int64 `json:"weightsMinor"`
			Expected []int64 `json:"expectedMinor"`
		} `json:"cases"`
	}
	if err := json.Unmarshal(raw, &doc); err != nil {
		t.Fatal(err)
	}
	if len(doc.Cases) < 10 {
		t.Fatalf("expected the shared vectors, got %d cases", len(doc.Cases))
	}
	for _, c := range doc.Cases {
		m, err := ParseShippingAllocation(c.Method)
		if err != nil {
			t.Fatalf("%s: %v", c.Name, err)
		}
		if got := m.Allocate(c.Total, c.Weights); !slices.Equal(got, c.Expected) {
			t.Errorf("%s: got %v, want %v", c.Name, got, c.Expected)
		}
	}
}

func TestShippingAllocationSettingDefaultsToProportional(t *testing.T) {
	if m, err := ParseShippingAllocation(""); err != nil || m != AllocateProportional {
		t.Fatalf("default: %v %v", m, err)
	}
	if m, err := ParseShippingAllocation(" first_line "); err != nil || m != AllocateFirstLine {
		t.Fatalf("rollback: %v %v", m, err)
	}
	if _, err := ParseShippingAllocation("BY_WEIGHT"); err == nil {
		t.Fatal("an unknown method must be refused at start-up")
	}
}

func TestShippingAllocationAlwaysAddsUp(t *testing.T) {
	for _, total := range []int64{1, 7, 599, 1234, 99999} {
		var sum int64
		for _, v := range AllocateProportional.Allocate(total, []int64{333, 1, 999, 250, 0, 17}) {
			sum += v
		}
		if sum != total {
			t.Errorf("total %d allocated %d", total, sum)
		}
	}
}
