package app

import (
	"slices"
	"strconv"
	"strings"
	"time"

	"github.com/billionairedev24/diaspora-commerce/go/services/gateway-bff/internal/domain"
)

// Seller Center's server-driven tables (orders, listings) share the staff tables' query model: one search over every
// column the table shows, any sort (`<column>,asc|desc`), and the columns below are what the search, the sort and the
// exports see.

// SellerTableQuery is a seller table request.
type SellerTableQuery struct {
	Q, Status, Cursor string
	Sort              []string
	Page, Size        int
}

func (q SellerTableQuery) sort(cols []domain.Column) ([]domain.SortKey, error) {
	if len(q.Q) > domain.MaxSearchLength {
		return nil, domain.ValidationProblem(map[string]string{"q": "The search text is too long"})
	}
	known := func(k string) bool {
		return slices.ContainsFunc(cols, func(c domain.Column) bool { return c.Key == k })
	}
	keys, bad := domain.ParseSort(q.Sort, known)
	if bad != nil {
		return nil, bad
	}
	return keys, nil
}

// SellerOrderColumns are the orders table's columns.
var SellerOrderColumns = []domain.Column{
	{Key: "orderNumber", Label: "Order", Kind: "mono", Sortable: true},
	{Key: "placedAt", Label: "Placed", Kind: "datetime", Sortable: true},
	{Key: "buyerName", Label: "Buyer", Kind: "strong", Sortable: true},
	{Key: "shipTo", Label: "Ship to", Kind: "text", Sortable: true},
	{Key: "itemsSummary", Label: "Items", Kind: "text", Sortable: true},
	{Key: "itemCount", Label: "Units", Kind: "number", Sortable: true},
	{Key: "totalMinor", Label: "Total", Kind: "money", Sortable: true},
	{Key: "fulfilment", Label: "Fulfilment", Kind: "text", Sortable: true},
	{Key: "status", Label: "Status", Kind: "status", Sortable: true},
	{Key: "trackingNumber", Label: "Tracking", Kind: "mono", Sortable: true},
}

// SellerListingColumns are the listings table's columns.
var SellerListingColumns = []domain.Column{
	{Key: "title", Label: "Product", Kind: "strong", Sortable: true},
	{Key: "atin", Label: "ATIN", Kind: "mono", Sortable: true},
	{Key: "sku", Label: "SKU", Kind: "mono", Sortable: true},
	{Key: "variantCount", Label: "Variants", Kind: "number", Sortable: true},
	{Key: "priceMinor", Label: "Price", Kind: "money", Sortable: true},
	{Key: "stock", Label: "Stock", Kind: "number", Sortable: true},
	{Key: "fulfilment", Label: "Fulfilment", Kind: "text", Sortable: true},
	{Key: "status", Label: "Status", Kind: "status", Sortable: true},
	{Key: "updatedAt", Label: "Updated", Kind: "datetime", Sortable: true},
}

// sellerFulfilmentLabel is the fulfilment as Seller Center shows it.
func sellerFulfilmentLabel(ff string) string {
	switch ff {
	case domain.ShipsFromSeller:
		return "You ship"
	case domain.FulfilledByAfroTransact:
		return "AfroTransact"
	case domain.AfroTransactDelivery:
		return "AfroTransact pickup"
	default:
		return domain.Humanize(ff)
	}
}

func iso(t time.Time) any {
	if t.IsZero() {
		return nil
	}
	return t.UTC().Format(time.RFC3339)
}

func optional(s *string) any {
	if s == nil || *s == "" {
		return nil
	}
	return *s
}

func sellerOrderRowCells(r domain.SellerOrderRow) domain.Row {
	return domain.Row{ID: r.ShipmentID, Cells: map[string]any{
		"orderNumber": r.OrderNumber, "placedAt": iso(r.PlacedAt), "buyerName": r.BuyerName, "shipTo": r.ShipTo,
		"itemsSummary": r.ItemsSummary, "itemCount": float64(r.ItemCount), "totalMinor": float64(r.TotalMinor),
		"fulfilment": sellerFulfilmentLabel(r.Fulfilment), "status": firstNonEmpty(r.StatusLabel, r.Status), "trackingNumber": optional(r.TrackingNumber),
	}}
}

func sellerListingRowCells(r domain.ListingRow) domain.Row {
	return domain.Row{ID: r.ProductID, Cells: map[string]any{
		"title": r.Title, "atin": optional(r.ATIN), "sku": r.SKU, "variantCount": float64(r.VariantCount), "priceMinor": float64(r.PriceMinor),
		"stock": float64(r.Stock), "fulfilment": sellerFulfilmentLabel(r.Fulfilment), "status": firstNonEmpty(r.StatusLabel, r.Status), "updatedAt": iso(r.UpdatedAt),
	}}
}

// pickRows keeps the typed rows whose cells match the search (and ids, when given) in the sort's order.
func pickRows[T any](items []T, cols []domain.Column, cells func(T) domain.Row, q string, ids []string, sort []domain.SortKey) []T {
	rows := make([]domain.Row, len(items))
	byID := make(map[string]T, len(items))
	for i, it := range items {
		rows[i] = cells(it)
		byID[rows[i].ID] = it
	}
	rows = domain.FilterRows(rows, cols, domain.SearchTerms(q), ids)
	domain.SortRows(rows, cols, sort)
	out := make([]T, len(rows))
	for i, r := range rows {
		out[i] = byID[r.ID]
	}
	return out
}

func cursorOffset(c string) int {
	n, err := strconv.Atoi(strings.TrimSpace(c))
	if err != nil || n < 0 {
		return 0
	}
	return n
}

func pageSlice[T any](items []T, offset, size int) ([]T, bool) {
	from := min(offset, len(items))
	to := min(from+size, len(items))
	out := make([]T, to-from)
	copy(out, items[from:to])
	return out, to < len(items)
}

// tableCells turns typed rows into export cells in the column order (money in currency units).
func tableCells(row domain.Row, cols []domain.Column) []any {
	out := make([]any, len(cols))
	for i, c := range cols {
		out[i] = exportCell(c.Kind, row.Cells[c.Key])
	}
	return out
}
