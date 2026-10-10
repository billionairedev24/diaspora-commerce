// Package domain holds the cart aggregate and its rules.
package domain

import (
	"errors"
	"fmt"
	"slices"
	"sort"
	"time"
)

// MaxQuantityPerLine caps a single line even when stock is plentiful.
const MaxQuantityPerLine = 99

// MaxLines caps the number of distinct lines in a cart.
const MaxLines = 100

// Errors returned by cart rules.
var (
	ErrUnknownLine     = errors.New("line not in cart")
	ErrOutOfStock      = errors.New("out of stock")
	ErrQuantityTooHigh = errors.New("quantity exceeds available stock")
	ErrInvalidQuantity = errors.New("quantity must be at least 1")
	ErrCartFull        = errors.New("cart has too many lines")
	ErrConflict        = errors.New("cart changed concurrently")
)

// OwnerKind distinguishes signed-in customers from guests.
type OwnerKind string

// Owner kinds.
const (
	OwnerUser  OwnerKind = "user"
	OwnerGuest OwnerKind = "guest"
)

// Owner identifies a cart.
type Owner struct {
	Kind OwnerKind
	ID   string
}

// Key is the storage key, e.g. user:123 or guest:abc.
func (o Owner) Key() string { return string(o.Kind) + ":" + o.ID }

// Offer is what the cart knows about a purchasable variant at a point in time. OfferID is the offers-service offer
// the shopper chose (empty for lines added before offers, which follow the catalog's single seller).
type Offer struct {
	OfferID     string            `json:"offerId,omitempty"`
	VariantATIN string            `json:"variantAtin"`
	ProductATIN string            `json:"productAtin"`
	Title       string            `json:"title"`
	Options     map[string]string `json:"options,omitempty"`
	ImageURL    string            `json:"imageUrl,omitempty"`
	SellerOrgID string            `json:"sellerOrgId"`
	SellerName  string            `json:"sellerName"`
	SellerKind  string            `json:"sellerKind"` // PLATFORM | SELLER
	Fulfilment  string            `json:"fulfilment"` // AFROTRANSACT | SELLER | AFROTRANSACT_DELIVERY
	PriceMinor  int64             `json:"priceMinor"`
	Currency    string            `json:"currency"`
	// ShipFromLocationID is the seller's pickup location of an AFROTRANSACT_DELIVERY offer (seller-service id): an
	// AfroTransact driver collects each location's parcel separately, so it splits packages (S259).
	ShipFromLocationID string `json:"shipFromLocationId,omitempty"`
}

// FulfilmentAfroTransactDelivery is AfroTransact Delivery from the seller's store: one pickup per seller location.
const FulfilmentAfroTransactDelivery = "AFROTRANSACT_DELIVERY"

// Line is one cart row with the price captured when it was added.
type Line struct {
	Offer
	Quantity        int       `json:"quantity"`
	AddedPriceMinor int64     `json:"addedPriceMinor"`
	AddedAt         time.Time `json:"addedAt"`
}

// Cart is the aggregate. Saved holds "save for later" lines.
type Cart struct {
	Owner     Owner     `json:"-"`
	Lines     []Line    `json:"lines"`
	Saved     []Line    `json:"saved"`
	Version   int64     `json:"version"`
	UpdatedAt time.Time `json:"updatedAt"`
	// PreorderFulfilment is the door / pickup choice per pre-order window (S272): window id → DOOR | PICKUP.
	PreorderFulfilment map[string]string `json:"preorderFulfilment,omitempty"`
}

func (c *Cart) index(lines []Line, atin string) int {
	return slices.IndexFunc(lines, func(l Line) bool { return l.VariantATIN == atin })
}

// Add adds qty of an offer (merging with an existing line), capped by available stock.
func (c *Cart) Add(o Offer, qty, available int, now time.Time) error {
	if qty < 1 {
		return ErrInvalidQuantity
	}
	if available <= 0 {
		return ErrOutOfStock
	}
	limit := min(available, MaxQuantityPerLine)
	if i := c.index(c.Lines, o.VariantATIN); i >= 0 {
		next := c.Lines[i].Quantity + qty
		if next > limit {
			return fmt.Errorf("%w: only %d available", ErrQuantityTooHigh, limit)
		}
		c.Lines[i].Quantity = next
		if c.Lines[i].OfferID != o.OfferID {
			// Another seller's offer of the same variant: the line follows the shopper's latest choice.
			c.Lines[i].AddedPriceMinor = o.PriceMinor
		}
		c.Lines[i].Offer = o
		return nil
	}
	if qty > limit {
		return fmt.Errorf("%w: only %d available", ErrQuantityTooHigh, limit)
	}
	if len(c.Lines)+len(c.Saved) >= MaxLines {
		return ErrCartFull
	}
	if i := c.index(c.Saved, o.VariantATIN); i >= 0 {
		c.Saved = slices.Delete(c.Saved, i, i+1)
	}
	c.Lines = append(c.Lines, Line{Offer: o, Quantity: qty, AddedPriceMinor: o.PriceMinor, AddedAt: now})
	return nil
}

// SetQuantity sets an exact quantity (the − / + stepper), capped by stock; 0 removes the line.
func (c *Cart) SetQuantity(atin string, qty, available int) error {
	i := c.index(c.Lines, atin)
	if i < 0 {
		return ErrUnknownLine
	}
	if qty == 0 {
		c.Lines = slices.Delete(c.Lines, i, i+1)
		return nil
	}
	if qty < 0 {
		return ErrInvalidQuantity
	}
	limit := min(available, MaxQuantityPerLine)
	if qty > limit {
		return fmt.Errorf("%w: only %d available", ErrQuantityTooHigh, max(limit, 0))
	}
	c.Lines[i].Quantity = qty
	return nil
}

// Remove deletes a line from the cart or the saved list.
func (c *Cart) Remove(atin string) error {
	if i := c.index(c.Lines, atin); i >= 0 {
		c.Lines = slices.Delete(c.Lines, i, i+1)
		return nil
	}
	if i := c.index(c.Saved, atin); i >= 0 {
		c.Saved = slices.Delete(c.Saved, i, i+1)
		return nil
	}
	return ErrUnknownLine
}

// SaveForLater moves a line out of the active cart.
func (c *Cart) SaveForLater(atin string) error {
	i := c.index(c.Lines, atin)
	if i < 0 {
		return ErrUnknownLine
	}
	l := c.Lines[i]
	c.Lines = slices.Delete(c.Lines, i, i+1)
	c.Saved = append([]Line{l}, c.Saved...)
	return nil
}

// MoveToCart moves a saved line back, capped by stock.
func (c *Cart) MoveToCart(atin string, available int) error {
	i := c.index(c.Saved, atin)
	if i < 0 {
		return ErrUnknownLine
	}
	l := c.Saved[i]
	if available <= 0 {
		return ErrOutOfStock
	}
	l.Quantity = min(l.Quantity, available, MaxQuantityPerLine)
	c.Saved = slices.Delete(c.Saved, i, i+1)
	c.Lines = append(c.Lines, l)
	return nil
}

// Merge folds a guest cart into this one after sign-in; quantities add up, capped by stock.
func (c *Cart) Merge(guest Cart, available map[string]int) {
	for _, g := range guest.Lines {
		avail := available[g.VariantATIN]
		if i := c.index(c.Lines, g.VariantATIN); i >= 0 {
			c.Lines[i].Quantity = min(c.Lines[i].Quantity+g.Quantity, max(avail, c.Lines[i].Quantity), MaxQuantityPerLine)
			continue
		}
		if avail > 0 && len(c.Lines)+len(c.Saved) < MaxLines {
			g.Quantity = min(g.Quantity, avail, MaxQuantityPerLine)
			c.Lines = append(c.Lines, g)
		}
	}
	for _, g := range guest.Saved {
		if c.index(c.Saved, g.VariantATIN) < 0 && c.index(c.Lines, g.VariantATIN) < 0 {
			c.Saved = append(c.Saved, g)
		}
	}
}

// RemoveOrdered drops the lines that were checked out (cart cleared after a successful order).
func (c *Cart) RemoveOrdered(atins []string) {
	c.Lines = slices.DeleteFunc(c.Lines, func(l Line) bool { return slices.Contains(atins, l.VariantATIN) })
}

// LineView is a line as shown to the shopper with live price and stock.
type LineView struct {
	Line
	Available    int   `json:"available"`
	LowStock     bool  `json:"lowStock"`     // "Only N left" at ≤5
	Unavailable  bool  `json:"unavailable"`  // excluded from checkout
	PriceChanged bool  `json:"priceChanged"` // price differs from when it was added
	LineTotal    int64 `json:"lineTotalMinor"`
}

// Package is one shipment group: lines from the same seller and fulfilment type, and for AfroTransact Delivery
// from the same seller pickup location (one driver pickup per location, S259).
type Package struct {
	SellerOrgID      string     `json:"sellerOrgId"`
	SellerName       string     `json:"sellerName"`
	Fulfilment       string     `json:"fulfilment"`
	PickupLocationID string     `json:"pickupLocationId,omitempty"`
	Lines            []LineView `json:"lines"`
	SubtotalMinor    int64      `json:"subtotalMinor"`
	// PackageID is the checkout delivery-selection key: <sellerOrgId>:<fulfilment>, or preorder:<windowId>.
	PackageID string `json:"packageId"`
	// Preorder is set for a pre-order window's package (S272); lines of several sellers share it.
	Preorder *PackagePreorder `json:"preorder,omitempty"`
}

// packageKey groups lines into shipments: seller and fulfilment, plus the pickup location for AfroTransact Delivery.
func packageKey(lv LineView) (key, pickupLocationID string) {
	key = lv.SellerOrgID + "|" + lv.Fulfilment
	if lv.Fulfilment == FulfilmentAfroTransactDelivery {
		pickupLocationID = lv.ShipFromLocationID
		key += "|" + pickupLocationID
	}
	return key, pickupLocationID
}

// View is the cart page / mini-cart model.
type View struct {
	Packages      []Package  `json:"packages"`
	Saved         []LineView `json:"saved"`
	ItemCount     int        `json:"itemCount"`
	SubtotalMinor int64      `json:"subtotalMinor"`
	Currency      string     `json:"currency"`
	Checkoutable  bool       `json:"checkoutable"`
	Version       int64      `json:"version"`
	// PreorderFulfilment echoes the door / pickup choices per window (S272).
	PreorderFulfilment map[string]string `json:"preorderFulfilment,omitempty"`
}

// LowStockThreshold matches search and the product page.
const LowStockThreshold = 5

// Present builds the view with live offers and stock; lines without a live offer are unavailable.
func (c *Cart) Present(live map[string]Offer, available map[string]int) View {
	return c.PresentWithPreorders(live, available, nil)
}

// PresentWithPreorders also groups the lines delivery-runs sells as a pre-order at the shopper's location into one
// package per window (S272), with its stage, delivery date and door / pickup options. A window that is not open
// locks its package and makes the cart not checkoutable.
func (c *Cart) PresentWithPreorders(live map[string]Offer, available map[string]int, preorders map[string]PreorderLine) View {
	v := View{Packages: []Package{}, Saved: []LineView{}, Currency: "USD", Version: c.Version,
		PreorderFulfilment: c.PreorderFulfilment}
	pkgIndex := map[string]int{}
	anyAvailable := false
	locked := false
	for _, l := range c.Lines {
		lv := view(l, live, available)
		key, location := packageKey(lv)
		pre, isPre := preorders[lv.VariantATIN]
		isPre = isPre && pre.Preorder && pre.WindowID != ""
		if isPre {
			key = PreorderPackagePrefix + pre.WindowID
		}
		pi, ok := pkgIndex[key]
		if !ok {
			pkg := Package{SellerOrgID: lv.SellerOrgID, SellerName: lv.SellerName, Fulfilment: lv.Fulfilment,
				PickupLocationID: location, PackageID: lv.SellerOrgID + ":" + lv.Fulfilment}
			if isPre {
				pkg = Package{Fulfilment: FulfilmentPreorder, PackageID: key, SellerName: pre.WindowName,
					Preorder: preorderOf(pre, c.PreorderFulfilment[pre.WindowID])}
			}
			v.Packages = append(v.Packages, pkg)
			pi = len(v.Packages) - 1
			pkgIndex[key] = pi
		}
		v.Packages[pi].Lines = append(v.Packages[pi].Lines, lv)
		if !lv.Unavailable {
			anyAvailable = true
			v.Packages[pi].SubtotalMinor += lv.LineTotal
			v.SubtotalMinor += lv.LineTotal
			v.ItemCount += lv.Quantity
			v.Currency = lv.Currency
			if isPre && v.Packages[pi].Preorder.Locked {
				locked = true
			}
		}
	}
	sort.SliceStable(v.Packages, func(i, j int) bool {
		// Pre-order packages first (the design's "Pre-order · delivered …" group), then the ships-now packages.
		if (v.Packages[i].Preorder != nil) != (v.Packages[j].Preorder != nil) {
			return v.Packages[i].Preorder != nil
		}
		// AfroTransact-fulfilled packages first, then by seller name and pickup location, so the page is stable.
		if (v.Packages[i].Fulfilment == "AFROTRANSACT") != (v.Packages[j].Fulfilment == "AFROTRANSACT") {
			return v.Packages[i].Fulfilment == "AFROTRANSACT"
		}
		if v.Packages[i].SellerName != v.Packages[j].SellerName {
			return v.Packages[i].SellerName < v.Packages[j].SellerName
		}
		return v.Packages[i].PickupLocationID < v.Packages[j].PickupLocationID
	})
	for _, s := range c.Saved {
		v.Saved = append(v.Saved, view(s, live, available))
	}
	v.Checkoutable = anyAvailable && !locked
	return v
}

func view(l Line, live map[string]Offer, available map[string]int) LineView {
	lv := LineView{Line: l, Available: available[l.VariantATIN]}
	if o, ok := live[l.VariantATIN]; ok {
		lv.PriceChanged = o.PriceMinor != l.AddedPriceMinor
		lv.Offer = o
	} else {
		lv.Unavailable = true
	}
	if lv.Available <= 0 || lv.Quantity > lv.Available {
		lv.Unavailable = true
	}
	lv.LowStock = lv.Available > 0 && lv.Available <= LowStockThreshold
	lv.LineTotal = lv.PriceMinor * int64(lv.Quantity)
	return lv
}
