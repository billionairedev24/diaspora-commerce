package app

import (
	"context"
	"net/http"
	"net/url"
	"slices"
	"strings"

	"github.com/billionairedev24/diaspora-commerce/go/services/gateway-bff/internal/domain"
	"github.com/billionairedev24/diaspora-commerce/go/services/gateway-bff/internal/port"
)

// The seller's Listings table is built from the organisation's own product pages. An offer-only offer (S382: the seller sells
// an item whose page another seller owns) is not on any of those pages, so it never showed. These rows join the organisation's
// offers (offers GET /v1/offers, the token's organisation only) with the names of the pages they sit on (catalog's public
// page, the same name shoppers see) and add them to the table as rows of kind OFFER.

const (
	// offerListPageSize and offerListMaxPages bound the offers read for one table build (2,000 offers).
	offerListPageSize = 200
	offerListMaxPages = 10
	// maxOfferPages bounds the name lookups of one table build.
	maxOfferNameLookups = 200
	// pageNotOnSaleLabel is what an offer's status reads while its page is off sale.
	pageNotOnSaleLabel = "The product page is not on sale right now."
)

// publicPageUp is the part of catalog's public product page a row needs: the name shoppers see, the owner and the picture.
type publicPageUp struct {
	Title       string `json:"title"`
	SellerOrgID string `json:"sellerOrgId"`
	Images      []struct {
		URL string `json:"url"`
	} `json:"images"`
	Variants []struct {
		ATIN    string            `json:"atin"`
		Options map[string]string `json:"options"`
	} `json:"variants"`
}

// orgOffers reads the organisation's open offers (withdrawn ones are history). Offers being down is an error the caller decides on.
func (s *sellerCatalogImpl) orgOffers(ctx context.Context, bearer string) ([]port.SellerOfferUp, error) {
	if s.r.d.Offers == nil {
		return nil, nil
	}
	var out []port.SellerOfferUp
	for page := range offerListMaxPages {
		p, err := s.r.d.Offers.List(ctx, bearer, page, offerListPageSize)
		if err != nil {
			return nil, mapErr("offers", err)
		}
		for _, o := range p.Items {
			if !strings.EqualFold(o.Status, "WITHDRAWN") {
				out = append(out, o)
			}
		}
		if len(p.Items) == 0 || int64((page+1)*offerListPageSize) >= p.Total {
			break
		}
	}
	return out, nil
}

// offerOnlyRows are the rows of the offers whose page the organisation does not own: neither the page's id nor its ATIN is
// among own. A page whose name cannot be read (catalog down, or the page is off sale) still gets a row, named by the seller's SKU.
func (s *sellerCatalogImpl) offerOnlyRows(ctx context.Context, bearer, orgID string, offers []port.SellerOfferUp, own []domain.ListingRow) []domain.ListingRow {
	ownID, ownATIN := map[string]bool{}, map[string]bool{}
	for _, r := range own {
		if r.ProductID != "" {
			ownID[r.ProductID] = true
		}
		if r.ATIN != nil {
			ownATIN[strings.ToUpper(*r.ATIN)] = true
		}
	}
	var foreign []port.SellerOfferUp
	for _, o := range offers {
		if ownID[o.ProductID] || ownATIN[strings.ToUpper(o.ProductATIN)] {
			continue
		}
		foreign = append(foreign, o)
	}
	pages := s.pageNames(ctx, bearer, foreign)
	rows := make([]domain.ListingRow, 0, len(foreign))
	for _, o := range foreign {
		pg, known := pages[strings.ToUpper(o.ProductATIN)]
		if known && orgID != "" && pg.SellerOrgID == orgID {
			continue // the page is the organisation's own after all (its list was partial): not an offer-only row
		}
		rows = append(rows, offerRow(o, pg, known))
	}
	return rows
}

// pageNames reads each distinct page once (catalog's public page: the name, the first picture and the variants' options).
func (s *sellerCatalogImpl) pageNames(ctx context.Context, bearer string, offers []port.SellerOfferUp) map[string]publicPageUp {
	out := map[string]publicPageUp{}
	if s.r.d.ScopeCatalog == nil {
		return out
	}
	for _, o := range offers {
		atin := strings.ToUpper(strings.TrimSpace(o.ProductATIN))
		if _, done := out[atin]; done || atin == "" || len(out) >= maxOfferNameLookups {
			continue
		}
		var pg publicPageUp
		if err := s.r.d.ScopeCatalog.Call(ctx, bearer, port.StaffCall{Method: http.MethodGet, Path: "/v1/public/products/" + url.PathEscape(atin)}, &pg); err != nil {
			continue
		}
		out[atin] = pg
	}
	return out
}

// offerRow is one offer as a Listings row. Its name is the page's name (plus the variant's options when the page has several);
// the status is the offer's in the table's words, and an offer held for tax reads "We are checking the tax details for this item."
func offerRow(o port.SellerOfferUp, pg publicPageUp, named bool) domain.ListingRow {
	offer := sellerOfferOf(o)
	title := ""
	if named {
		title = strings.TrimSpace(pg.Title)
		if len(pg.Variants) > 1 {
			for _, v := range pg.Variants {
				if strings.EqualFold(v.ATIN, o.ATIN) && len(v.Options) > 0 {
					title += " (" + variantLabel(v.Options) + ")"
				}
			}
		}
	}
	title = firstNonEmpty(title, "Your offer "+firstNonEmpty(o.SellerSKU, o.ATIN))
	row := domain.ListingRow{ProductID: o.ProductID, ATIN: strPtr(o.ProductATIN), Title: title, SKU: o.SellerSKU, VariantCount: 1, PriceMinor: o.PriceMinor,
		Currency: offer.Currency, Stock: max(0, o.AvailableQty), Fulfilment: offer.Fulfilment, Kind: domain.ListingKindOffer, UpdatedAt: o.UpdatedAt}
	id, version, qty := o.ID, o.Version, o.Quantity
	row.OfferID, row.OfferVersion, row.Quantity = &id, &version, &qty
	if len(pg.Images) > 0 {
		row.ImageURL = strPtr(pg.Images[0].URL)
	}
	switch strings.ToUpper(o.Status) {
	case "TAX_HELD":
		row.Status, row.StatusLabel, row.ModerationNote = "IN_REVIEW", taxHeldMessage, strPtr(taxHeldMessage)
	case "PAUSED":
		row.Status = "PAUSED"
	case "ACTIVE":
		if o.ProductLive != nil && !*o.ProductLive {
			row.Status, row.StatusLabel, row.ModerationNote = "IN_REVIEW", pageNotOnSaleLabel, strPtr(pageNotOnSaleLabel)
		} else {
			row.Status = "LIVE"
		}
	default:
		row.Status = "IN_REVIEW"
	}
	if row.StatusLabel == "" {
		row.StatusLabel = domain.ListingStatusLabel(row.Status)
	}
	return row
}

func variantLabel(options map[string]string) string {
	keys := make([]string, 0, len(options))
	for k := range options {
		keys = append(keys, k)
	}
	slices.Sort(keys)
	parts := make([]string, 0, len(keys))
	for _, k := range keys {
		parts = append(parts, options[k])
	}
	return strings.Join(parts, " / ")
}
