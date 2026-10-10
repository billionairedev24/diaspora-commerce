package domain

import (
	"fmt"
	"net/http"
)

// MaxLineQuantity caps a cart line even when stock is plentiful.
const MaxLineQuantity = 99

// LowStockThreshold is the low-stock boundary: 1–5 units left is LOW_STOCK ("Only N left — order soon").
const LowStockThreshold = 5

// StockStatus values.
const (
	InStock    = "IN_STOCK"
	LowStock   = "LOW_STOCK"
	OutOfStock = "OUT_OF_STOCK"
)

// StockStatusFor classifies an available quantity.
func StockStatusFor(available int) string {
	switch {
	case available <= 0:
		return OutOfStock
	case available <= LowStockThreshold:
		return LowStock
	default:
		return InStock
	}
}

// MaxQuantity is the highest quantity a shopper may set: min(available, 99), never negative.
func MaxQuantity(available int) int {
	return max(0, min(available, MaxLineQuantity))
}

// StockProblem is the 409 STOCK_LIMIT (or OUT_OF_STOCK at zero) problem with available/inCart/requested/maxQuantity.
func StockProblem(title string, available, inCart, requested int) *Problem {
	available = max(available, 0)
	if title == "" {
		title = "this item"
	}
	if available == 0 {
		p := NewProblem(http.StatusConflict, CodeOutOfStock, "Currently unavailable", title+" is currently unavailable.")
		p.Extra = map[string]any{"available": 0, "inCart": inCart, "requested": requested}
		return p
	}
	detail := fmt.Sprintf("Only %d available.", available)
	if inCart > 0 {
		detail = fmt.Sprintf("Only %d available — %d already in your cart.", available, inCart)
	}
	p := NewProblem(http.StatusConflict, CodeStockLimit, "Not enough stock", detail)
	p.Extra = map[string]any{"available": available, "inCart": inCart, "requested": requested, "maxQuantity": MaxQuantity(available)}
	return p
}
