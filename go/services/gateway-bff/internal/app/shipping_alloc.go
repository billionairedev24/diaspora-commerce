package app

import (
	"fmt"
	"strings"
)

// ShippingAllocation is how a package's delivery charge is spread over its lines for the tax estimate (rules TX-05 to
// TX-07): the delivery follows the sale, so a delivery tied only to exempt lines is taxed at 0% and a mixed package is
// split. It must give exactly the amounts orders' ShippingAllocation gives when the order is placed (the shared
// vectors in tools/checkout/shipping-allocation-vectors.json are tested on both sides).
//
// OPEN LEGAL DECISION (docs/engineering/category-tax-design.md, open decision 5): the mixed-basket attribution method
// must be approved by the tax adviser before production. PROPORTIONAL is the default build; FIRST_LINE is the previous
// behaviour, kept as the rollback (checkout.shippingAllocation / CHECKOUT_SHIPPING_ALLOCATION, and orders'
// at.orders.tax.shipping-allocation).
type ShippingAllocation string

const (
	// AllocateProportional splits by each line's net item value (largest remainder; ties go to the earliest line).
	AllocateProportional ShippingAllocation = "PROPORTIONAL"
	// AllocateFirstLine puts the whole charge on the package's first line.
	AllocateFirstLine ShippingAllocation = "FIRST_LINE"
)

// ParseShippingAllocation reads the setting; blank means the default.
func ParseShippingAllocation(v string) (ShippingAllocation, error) {
	switch strings.ToUpper(strings.TrimSpace(v)) {
	case "", string(AllocateProportional):
		return AllocateProportional, nil
	case string(AllocateFirstLine):
		return AllocateFirstLine, nil
	}
	return "", fmt.Errorf("checkout shipping allocation must be PROPORTIONAL or FIRST_LINE, not %q", v)
}

// Allocate returns the shipping on each line; the amounts add up to total (when it is positive and there is a line).
// weights are the lines' net item values (items minus discount), in line order.
func (m ShippingAllocation) Allocate(total int64, weights []int64) []int64 {
	out := make([]int64, len(weights))
	if total <= 0 || len(weights) == 0 {
		return out
	}
	var sum int64
	for _, w := range weights {
		sum += max(0, w)
	}
	if m == AllocateFirstLine || sum == 0 {
		out[0] = total
		return out
	}
	rem := make([]int64, len(weights))
	var given int64
	for i, w := range weights {
		p := total * max(0, w)
		out[i], rem[i] = p/sum, p%sum
		given += out[i]
	}
	for left := total - given; left > 0; left-- {
		best := 0
		for i := 1; i < len(rem); i++ {
			if rem[i] > rem[best] {
				best = i
			}
		}
		out[best]++
		rem[best] = -1
	}
	return out
}
