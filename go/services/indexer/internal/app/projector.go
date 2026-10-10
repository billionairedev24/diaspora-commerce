// Package app holds the projection use case.
package app

import (
	"context"
	"encoding/json"
	"fmt"
	"log/slog"
	"strings"

	"github.com/billionairedev24/diaspora-commerce/go/pkg/events"
	"github.com/billionairedev24/diaspora-commerce/go/pkg/searchindex"
	"github.com/billionairedev24/diaspora-commerce/go/services/indexer/internal/domain"
	"github.com/billionairedev24/diaspora-commerce/go/services/indexer/internal/port"
)

// Projector turns platform events into search documents.
type Projector interface {
	events.Handler
}

type projectorImpl struct {
	index   port.ProductIndex
	catalog port.CatalogReader
	sellers port.SellerDirectory
	market  string
	log     *slog.Logger
}

var _ Projector = (*projectorImpl)(nil)

// NewProjector returns a Projector writing to the given market's index.
func NewProjector(index port.ProductIndex, catalog port.CatalogReader, sellers port.SellerDirectory,
	market string, log *slog.Logger,
) Projector {
	return &projectorImpl{index: index, catalog: catalog, sellers: sellers, market: market, log: log}
}

func (p *projectorImpl) Handle(ctx context.Context, e events.Envelope) error {
	version := domain.VersionOf(e.OccurredAt)
	switch e.Type {
	case domain.ProductCreated, domain.ProductApproved, domain.ProductRejected, domain.ProductPaused,
		domain.ProductResumed, domain.ProductHidden, domain.ProductRestored:
		var pe domain.ProductEvent
		if err := json.Unmarshal(e.Payload, &pe); err != nil {
			p.log.WarnContext(ctx, "skipping malformed product event", "event_id", e.ID, "error", err)
			return nil
		}
		return p.product(ctx, pe, version)
	case domain.AvailabilityChanged:
		var ae domain.AvailabilityEvent
		if err := json.Unmarshal(e.Payload, &ae); err != nil {
			p.log.WarnContext(ctx, "skipping malformed availability event", "event_id", e.ID, "error", err)
			return nil
		}
		if ae.Version > 0 {
			version = ae.Version
		}
		found, err := p.index.ApplyAvailability(ctx, p.market, ae.SKU, ae.Node, ae.Available, version)
		if err != nil {
			return fmt.Errorf("availability %s@%s: %w", ae.SKU, ae.Node, err)
		}
		if !found {
			p.log.InfoContext(ctx, "availability for unknown variant ignored", "sku", ae.SKU)
		}
		return nil
	case domain.SellerOrgApproved, domain.SellerOrgUpdated:
		var se domain.SellerOrgEvent
		if err := json.Unmarshal(e.Payload, &se); err != nil {
			p.log.WarnContext(ctx, "skipping malformed seller org event", "event_id", e.ID, "error", err)
			return nil
		}
		return p.sellerName(ctx, se)
	default:
		return nil
	}
}

func (p *projectorImpl) product(ctx context.Context, pe domain.ProductEvent, version int64) error {
	cat := domain.Category{}
	if pe.CategoryID != "" {
		c, err := p.catalog.Category(ctx, pe.CategoryID)
		if err != nil {
			return fmt.Errorf("category %s: %w", pe.CategoryID, err)
		}
		cat = c
	}
	seller, known, err := p.sellers.DisplayName(ctx, pe.OwnerOrgID)
	if err != nil {
		return fmt.Errorf("seller %s: %w", pe.OwnerOrgID, err)
	}
	status := pe.Status
	if pe.SellerHidden {
		status = domain.StatusSellerHidden
	}
	doc := searchindex.ProductDocument{
		ATIN:         pe.ATIN,
		Title:        pe.Title,
		Department:   cat.Department,
		CategoryPath: cat.Path,
		SellerID:     pe.OwnerOrgID,
		SellerName:   seller,
		SellerKind:   pe.OwnerKind,
		Fulfilment:   fulfilment(pe.OwnerKind),
		Currency:     "USD",
		OfferCount:   1,
		Status:       status,
		DeliveryDays: deliveryDays(pe.OwnerKind),
	}
	for i, v := range pe.Variants {
		doc.VariantATINs = append(doc.VariantATINs, v.ATIN)
		if i == 0 || (v.PriceMinor > 0 && v.PriceMinor < doc.PriceMinor) {
			doc.PriceMinor = v.PriceMinor
			if v.Currency != "" {
				doc.Currency = strings.ToUpper(v.Currency)
			}
		}
	}
	if err := p.index.UpsertProduct(ctx, p.market, doc, version,
		port.UpsertOptions{PreserveSellerName: !known}); err != nil {
		return fmt.Errorf("upsert %s: %w", pe.ATIN, err)
	}
	return nil
}

// sellerName applies seller.org.approved / updated: remember the name and rename the seller's indexed documents.
func (p *projectorImpl) sellerName(ctx context.Context, se domain.SellerOrgEvent) error {
	name := se.Name()
	if se.OrgID == "" || name == "" {
		return nil
	}
	p.sellers.Remember(ctx, se.OrgID, name)
	n, err := p.index.RenameSeller(ctx, p.market, se.OrgID, name)
	if err != nil {
		return fmt.Errorf("rename seller %s: %w", se.OrgID, err)
	}
	if n > 0 {
		p.log.InfoContext(ctx, "seller renamed in index", "org_id", se.OrgID, "documents", n)
	}
	return nil
}

func fulfilment(ownerKind string) string {
	if ownerKind == searchindex.SellerPlatform {
		return searchindex.FulfilledByAfroTransact
	}
	return searchindex.ShipsFromSeller
}

// deliveryDays is a coarse default until the promise service publishes per-offer promises.
func deliveryDays(ownerKind string) int {
	if ownerKind == searchindex.SellerPlatform {
		return 2
	}
	return 5
}
