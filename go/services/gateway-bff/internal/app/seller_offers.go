package app

import (
	"context"
	"net/http"
	"slices"
	"strings"

	"github.com/billionairedev24/diaspora-commerce/go/services/gateway-bff/internal/domain"
	"github.com/billionairedev24/diaspora-commerce/go/services/gateway-bff/internal/port"
)

// SellerOffersService is the fulfilment choice of a listing's offers (S255): "I ship it" (SELLER), "Fulfilled by
// AfroTransact (FBA)" (AFROTRANSACT) or "AfroTransact picks up from my store" (AFROTRANSACT_DELIVERY, with a pickup
// location). offers checks the location against seller-service on every change.
type SellerOffersService interface {
	List(ctx context.Context, c *domain.Caller, productID string) (domain.SellerOfferList, error)
	SetFulfilment(ctx context.Context, c *domain.Caller, offerID string, in domain.OfferFulfilmentRequest) (domain.SellerOffer, error)
	// SetTerms changes the price and/or quantity of one of the organisation's own offers (the Listings table edits offers
	// on other sellers' pages this way). Another organisation's offer is a 404.
	SetTerms(ctx context.Context, c *domain.Caller, offerID string, in domain.OfferTermsRequest) (domain.SellerOffer, error)
	// Pause takes one of the organisation's own offers off sale (an offer on another seller's page); the page is never touched.
	Pause(ctx context.Context, c *domain.Caller, offerID string, in domain.OfferStatusRequest) (domain.SellerOffer, error)
	// Resume puts a paused offer back on sale (it waits for tax review first when its tax basis is not verified yet).
	Resume(ctx context.Context, c *domain.Caller, offerID string, in domain.OfferStatusRequest) (domain.SellerOffer, error)
}

type sellerOffersImpl struct {
	r     *orgResolver
	cache *scanCache[domain.ListingRow]
}

var _ SellerOffersService = (*sellerOffersImpl)(nil)

// NewSellerOffersService returns the SellerOffersService.
func NewSellerOffersService(d SellerDeps) SellerOffersService {
	return &sellerOffersImpl{r: newResolver(d), cache: d.listingCache()}
}

// offerPages caps how many pages of 100 offers one listing lookup reads.
const offerPages = 5

var offerFulfilments = []string{domain.ShipsFromSeller, domain.FulfilledByAfroTransact, domain.AfroTransactDelivery}

func sellerOfferOf(o port.SellerOfferUp) domain.SellerOffer {
	out := domain.SellerOffer{OfferID: o.ID, ATIN: o.ATIN, ProductID: o.ProductID, SellerSKU: o.SellerSKU, Condition: firstNonEmpty(strings.ToUpper(o.Condition), "NEW"),
		PriceMinor: o.PriceMinor, Currency: firstNonEmpty(strings.ToUpper(o.Currency), domain.Currency), Fulfilment: strings.ToUpper(o.Fulfilment),
		Quantity: o.Quantity, AvailableQty: o.AvailableQty, ShipsFrom: strPtr(o.ShipsFrom), Status: firstNonEmpty(strings.ToUpper(o.Status), "ACTIVE"), Version: o.Version}
	if !slices.Contains(offerFulfilments, out.Fulfilment) {
		out.Fulfilment = domain.ShipsFromSeller
	}
	if o.ShipFrom != nil {
		out.ShipFromLocationID = strPtr(o.ShipFrom.LocationID)
		if out.ShipsFrom == nil && o.ShipFrom.City != "" {
			out.ShipsFrom = strPtr(strings.TrimSuffix(o.ShipFrom.City+", "+o.ShipFrom.Region, ", "))
		}
	}
	return out
}

func (s *sellerOffersImpl) List(ctx context.Context, c *domain.Caller, productID string) (domain.SellerOfferList, error) {
	productID = strings.TrimSpace(productID)
	if productID == "" || len(productID) > 64 {
		return domain.SellerOfferList{}, domain.ValidationProblem(map[string]string{"productId": "Name the listing"})
	}
	if _, err := s.r.require(ctx, c, domain.OrgKindSeller, "listings", domain.LevelView); err != nil {
		return domain.SellerOfferList{}, err
	}
	out := domain.SellerOfferList{Items: []domain.SellerOffer{}}
	for page := range offerPages {
		p, err := s.r.d.Offers.List(ctx, c.BearerToken, page, 100)
		if err != nil {
			return domain.SellerOfferList{}, mapErr("offers", err)
		}
		for _, o := range p.Items {
			if o.ProductID == productID && !strings.EqualFold(o.Status, "WITHDRAWN") {
				out.Items = append(out.Items, sellerOfferOf(o))
			}
		}
		if len(p.Items) < 100 || (p.Total > 0 && int64((page+1)*100) >= p.Total) {
			break
		}
	}
	slices.SortStableFunc(out.Items, func(a, b domain.SellerOffer) int { return strings.Compare(a.SellerSKU, b.SellerSKU) })
	return out, nil
}

func (s *sellerOffersImpl) SetFulfilment(ctx context.Context, c *domain.Caller, offerID string, in domain.OfferFulfilmentRequest) (domain.SellerOffer, error) {
	ff := strings.ToUpper(strings.TrimSpace(in.Fulfilment))
	loc := ""
	if in.ShipFromLocationID != nil {
		loc = strings.TrimSpace(*in.ShipFromLocationID)
	}
	errs := map[string]string{}
	switch {
	case !slices.Contains(offerFulfilments, ff):
		errs["fulfilment"] = "Choose who fulfils this offer"
	case ff == domain.AfroTransactDelivery && loc == "":
		errs["shipFromLocationId"] = "Choose the pickup location (a ship-from address with AfroTransact pickup on)"
	}
	if len(loc) > 64 {
		errs["shipFromLocationId"] = "Choose a pickup location"
	}
	if offerID == "" || len(offerID) > 64 {
		return domain.SellerOffer{}, domain.NewProblem(http.StatusNotFound, domain.CodeNotFound, "Not found", "No such offer.")
	}
	if len(errs) > 0 {
		return domain.SellerOffer{}, domain.ValidationProblem(errs)
	}
	sc, err := s.r.require(ctx, c, domain.OrgKindSeller, "listings", domain.LevelEdit)
	if err != nil {
		return domain.SellerOffer{}, err
	}
	body := map[string]any{"fulfilment": ff}
	if loc != "" {
		body["shipFrom"] = map[string]any{"locationId": loc}
	}
	if ff == domain.ShipsFromSeller && loc == "" {
		// "I ship it" from an offer that had no ship-from (it was FBA): ship from the seller's default address.
		ship, err := s.defaultShipFrom(ctx, c, sc.org.ID, offerID)
		if err != nil {
			return domain.SellerOffer{}, err
		}
		if ship != nil {
			body["shipFrom"] = ship
		}
	}
	o, err := s.r.d.Offers.Update(ctx, c.BearerToken, offerID, body)
	if err != nil {
		return domain.SellerOffer{}, offerErr(err)
	}
	if s.cache != nil {
		s.cache.drop("listings")
	}
	return sellerOfferOf(o), nil
}

func (s *sellerOffersImpl) SetTerms(ctx context.Context, c *domain.Caller, offerID string, in domain.OfferTermsRequest) (domain.SellerOffer, error) {
	notFound := domain.NewProblem(http.StatusNotFound, domain.CodeNotFound, "Not found", "No such offer.")
	if offerID = strings.TrimSpace(offerID); offerID == "" || len(offerID) > 64 {
		return domain.SellerOffer{}, notFound
	}
	errs := map[string]string{}
	if in.PriceMinor == nil && in.Quantity == nil {
		errs["priceMinor"] = "Change the price or the quantity"
	}
	if in.PriceMinor != nil && (*in.PriceMinor < 1 || *in.PriceMinor > maxOfferMinor) {
		errs["priceMinor"] = "Enter a price above zero"
	}
	if in.Quantity != nil && (*in.Quantity < 0 || *in.Quantity > maxOfferQuantity) {
		errs["quantity"] = "Enter a quantity from 0 to 100,000"
	}
	if len(errs) > 0 {
		return domain.SellerOffer{}, domain.ValidationProblem(errs)
	}
	if _, err := s.r.require(ctx, c, domain.OrgKindSeller, "listings", domain.LevelEdit); err != nil {
		return domain.SellerOffer{}, err
	}
	// The offer must be one of this organisation's own: offers answers the token's organisation only, and the BFF checks it too.
	if _, err := s.ownOffer(ctx, c, offerID); err != nil {
		return domain.SellerOffer{}, err
	}
	body := map[string]any{}
	if in.PriceMinor != nil {
		body["priceMinor"] = *in.PriceMinor
	}
	if in.Quantity != nil {
		body["quantity"] = *in.Quantity
	}
	if in.Version != nil {
		body["version"] = *in.Version
	}
	o, err := s.r.d.Offers.Update(ctx, c.BearerToken, offerID, body)
	if err != nil {
		return domain.SellerOffer{}, offerErr(err)
	}
	if s.cache != nil {
		s.cache.drop("listings")
	}
	return sellerOfferOf(o), nil
}

// ownOffer is the organisation's own offer with this id; any other id (another organisation's included) is a 404.
func (s *sellerOffersImpl) ownOffer(ctx context.Context, c *domain.Caller, offerID string) (port.SellerOfferUp, error) {
	for page := range offerListMaxPages {
		p, err := s.r.d.Offers.List(ctx, c.BearerToken, page, offerListPageSize)
		if err != nil {
			return port.SellerOfferUp{}, mapErr("offers", err)
		}
		for _, o := range p.Items {
			if o.ID == offerID && !strings.EqualFold(o.Status, "WITHDRAWN") {
				return o, nil
			}
		}
		if len(p.Items) == 0 || int64((page+1)*offerListPageSize) >= p.Total {
			break
		}
	}
	return port.SellerOfferUp{}, domain.NewProblem(http.StatusNotFound, domain.CodeNotFound, "Not found", "No such offer.")
}

func (s *sellerOffersImpl) Pause(ctx context.Context, c *domain.Caller, offerID string, in domain.OfferStatusRequest) (domain.SellerOffer, error) {
	return s.setStatus(ctx, c, offerID, "pause", "ACTIVE", "Only an item that is on sale can be paused.", in)
}

func (s *sellerOffersImpl) Resume(ctx context.Context, c *domain.Caller, offerID string, in domain.OfferStatusRequest) (domain.SellerOffer, error) {
	return s.setStatus(ctx, c, offerID, "resume", "PAUSED", "Only a paused item can be put back on sale.", in)
}

// setStatus pauses or resumes one of the organisation's own offers (Listings: EDIT). Only offers' own offer endpoint is called:
// the page the offer sits on, and its owner, are never written to.
func (s *sellerOffersImpl) setStatus(ctx context.Context, c *domain.Caller, offerID, action, from, wrongState string, in domain.OfferStatusRequest) (domain.SellerOffer, error) {
	if offerID = strings.TrimSpace(offerID); offerID == "" || len(offerID) > 64 {
		return domain.SellerOffer{}, domain.NewProblem(http.StatusNotFound, domain.CodeNotFound, "Not found", "No such offer.")
	}
	if _, err := s.r.require(ctx, c, domain.OrgKindSeller, "listings", domain.LevelEdit); err != nil {
		return domain.SellerOffer{}, err
	}
	own, err := s.ownOffer(ctx, c, offerID)
	if err != nil {
		return domain.SellerOffer{}, err
	}
	if !strings.EqualFold(own.Status, from) {
		return domain.SellerOffer{}, domain.NewProblem(http.StatusConflict, domain.CodeConflict, "Conflict", wrongState)
	}
	var version int64
	if in.Version != nil {
		version = *in.Version
	}
	o, err := s.r.d.Offers.SetStatus(ctx, c.BearerToken, offerID, action, version)
	if err != nil {
		return domain.SellerOffer{}, mapErr("offers", err)
	}
	if s.cache != nil {
		s.cache.drop("listings")
	}
	return sellerOfferOf(o), nil
}

// offerErr maps offers' rule violations to the form's fields (shipFrom.locationId → shipFromLocationId).
func offerErr(err error) error {
	p, ok := port.AsUpstreamProblem(err)
	if !ok || p.Status != http.StatusUnprocessableEntity {
		return mapErr("offers", err)
	}
	fields := map[string]string{}
	for k, v := range p.Fields {
		if strings.HasPrefix(k, "shipFrom") {
			k = "shipFromLocationId"
		}
		fields[k] = v
	}
	if len(fields) == 0 {
		fields["fulfilment"] = firstNonEmpty(p.Detail, "This fulfilment can't be used for this offer.")
	}
	return domain.ValidationProblem(fields).WithCause(err)
}

// defaultShipFrom is the ship-from to give an offer switching to "I ship it": nil when the offer already has one,
// else the seller's default address; a validation problem naming the field when the seller has no address at all.
func (s *sellerOffersImpl) defaultShipFrom(ctx context.Context, c *domain.Caller, orgID, offerID string) (*port.CatalogShipFrom, error) {
	for page := range offerPages {
		p, err := s.r.d.Offers.List(ctx, c.BearerToken, page, 100)
		if err != nil {
			return nil, mapErr("offers", err)
		}
		for _, o := range p.Items {
			if o.ID == offerID && o.ShipFrom != nil && (o.ShipFrom.City != "" || o.ShipFrom.LocationID != "") {
				return nil, nil
			}
		}
		if len(p.Items) < 100 || (p.Total > 0 && int64((page+1)*100) >= p.Total) {
			break
		}
	}
	list, err := s.r.d.Sellers.ShipFrom(ctx, c.BearerToken, orgID)
	if err != nil {
		return nil, mapErr("seller", err)
	}
	a, ok := defaultShipFrom(list)
	if !ok {
		return nil, domain.ValidationProblem(map[string]string{"form": "Add a ship-from address first (Orders → Ship-from addresses): that is where you ship this from."})
	}
	return catalogShipFrom(a), nil
}
