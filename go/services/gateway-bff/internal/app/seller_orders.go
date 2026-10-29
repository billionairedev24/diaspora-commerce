package app

import (
	"context"
	"math"
	"net/http"
	"slices"
	"strconv"
	"strings"
	"time"

	"github.com/billionairedev24/diaspora-commerce/go/services/gateway-bff/internal/domain"
	"github.com/billionairedev24/diaspora-commerce/go/services/gateway-bff/internal/port"
)

// SellerOrdersService serves the orders table and sheet: the shipment queue, order detail, seller status updates
// (confirm, pack, ready only) and shipping labels bought through carrier-gateway.
type SellerOrdersService interface {
	// List is the shipment queue: one search over every column, any sort, status (links), cursor and size (limit).
	List(ctx context.Context, c *domain.Caller, q SellerTableQuery) (domain.SellerOrderPage, error)
	Get(ctx context.Context, c *domain.Caller, orderNumber string) (domain.SellerOrderDetail, error)
	// UpdateStatus moves a seller's shipment one step; handoverCode is the buyer's 4-digit code for handing a pickup order over.
	UpdateStatus(ctx context.Context, c *domain.Caller, idempotencyKey, orderNumber, shipmentID, status, handoverCode string) (domain.SellerOrderDetail, error)
	Rates(ctx context.Context, c *domain.Caller, orderNumber, shipmentID string, parcel domain.Parcel, from domain.SellerAddress, carrier string) (domain.RateQuotes, error)
	BuyLabel(ctx context.Context, c *domain.Caller, idempotencyKey, orderNumber, shipmentID, carrier, service string, parcel domain.Parcel, from domain.SellerAddress) (domain.LabelPurchase, error)
	// Label downloads the shipping label bought for the shipment.
	Label(ctx context.Context, c *domain.Caller, orderNumber, shipmentID string) (domain.FileDownload, error)
}

type sellerOrdersImpl struct {
	r     *orgResolver
	cache *scanCache[domain.SellerOrderRow]
}

var _ SellerOrdersService = (*sellerOrdersImpl)(nil)

// NewSellerOrdersService returns the SellerOrdersService.
func NewSellerOrdersService(d SellerDeps) SellerOrdersService {
	r := newResolver(d)
	return &sellerOrdersImpl{r: r, cache: newScanCache[domain.SellerOrderRow](r.d.clock())}
}

func fulfilmentOf(t string) string {
	if strings.EqualFold(t, "SELLER_FULFILLED") || strings.EqualFold(t, domain.ShipsFromSeller) || t == "" {
		return domain.ShipsFromSeller
	}
	return domain.FulfilledByAfroTransact
}

// shipmentFulfilment: a seller-fulfilled shipment that AfroTransact's driver collects at the seller's store (a local
// delivery snapshot) is AfroTransact Delivery from the seller's store (S255).
func shipmentFulfilment(s port.OrderShipmentView) string {
	ff := fulfilmentOf(s.FulfilmentType)
	if ff == domain.ShipsFromSeller && s.LocalDelivery != nil && strings.EqualFold(s.DeliveryMethod, domain.AfroTransactDelivery) {
		return domain.AfroTransactDelivery
	}
	return ff
}

func shipmentOf(s port.OrderShipmentView) domain.SellerShipment {
	status := strings.ToUpper(s.Status)
	ff := shipmentFulfilment(s)
	out := domain.SellerShipment{ShipmentID: s.ID, Label: s.Label, Status: status, StatusLabel: domain.ShipmentStatusLabel(status),
		Fulfilment: ff, Perishable: s.Perishable, Lines: []domain.SellerShipmentLine{}, ItemsMinor: s.ItemsMinor,
		ShippingMinor: s.ShippingMinor, TotalMinor: s.TotalMinor, Carrier: strPtr(s.Carrier), TrackingNumber: strPtr(s.TrackingNumber),
		NextActions: domain.SellerNextActions(s.NextActions), CanBuyLabel: domain.CanBuyLabel(ff, s.DeliveryMethod, status, s.TrackingNumber),
		HasLabel: ff == domain.ShipsFromSeller && s.TrackingNumber != "" && s.Carrier != "" && !strings.EqualFold(s.DeliveryMethod, "PICKUP"),
		Timeline: []domain.TimelineEntry{}}
	if ff == domain.FulfilledByAfroTransact {
		out.NextActions = []string{} // AfroTransact owns every step of FBA and 1P shipments; sellers pack AfroTransact Delivery ones
	}
	if ld := s.LocalDelivery; ld != nil {
		fee := int64(0)
		if ld.SellerFeeMinor != nil {
			fee = *ld.SellerFeeMinor
		}
		out.LocalDelivery = &domain.SellerLocalDelivery{PickupLocationID: strPtr(firstNonEmpty(ld.ShipFromLocationID, s.PickupLocationID)),
			RoadMiles: ld.RoadMiles, DurationMinutes: ld.DurationMinutes, CustomerChargeMinor: ld.CustomerChargeMinor, SellerFeeMinor: fee}
	}
	for _, l := range s.Lines {
		out.Lines = append(out.Lines, domain.SellerShipmentLine{Title: l.Title, Quantity: l.Quantity, UnitPriceMinor: l.UnitPriceMinor,
			ItemsMinor: l.ItemsMinor, VariantATIN: l.VariantATIN, ImageURL: strPtr(l.ImageURL)})
	}
	entries := make([]proofEntry, 0, len(s.Timeline))
	for _, t := range s.Timeline {
		out.Timeline = append(out.Timeline, domain.TimelineEntry{Status: strings.ToUpper(t.To), At: t.At, Actor: strings.ToLower(t.ActorType)})
		entries = append(entries, proofEntry{to: t.To, at: t.At.UTC().Format(time.RFC3339), proof: t.Proof})
	}
	out.Proof = deliveredProof(entries)
	return out
}

func addressOf(a *port.ShipTo) *domain.SellerAddress {
	if a == nil || a.Line1 == "" {
		return nil
	}
	country := a.Country
	if country == "" {
		country = "US"
	}
	return &domain.SellerAddress{Name: a.FullName, Phone: strPtr(a.Phone), Line1: a.Line1, Line2: strPtr(a.Line2), City: a.City,
		State: a.State, PostalCode: a.PostalCode, Country: country}
}

func orderOf(o port.OrderView) domain.SellerOrderDetail {
	out := domain.SellerOrderDetail{OrderNumber: o.OrderNumber, Status: strings.ToUpper(o.Status), PlacedAt: o.PlacedAt, Currency: o.Currency,
		ShippingAddress: addressOf(o.ShippingAddress), Shipments: []domain.SellerShipment{}, TotalMinor: o.TotalMinor}
	if out.Currency == "" {
		out.Currency = domain.Currency
	}
	if o.ShippingAddress != nil {
		out.BuyerName = o.ShippingAddress.FullName
	}
	for _, s := range o.Shipments {
		out.Shipments = append(out.Shipments, shipmentOf(s))
	}
	return out
}

func (s *sellerOrdersImpl) List(ctx context.Context, c *domain.Caller, q SellerTableQuery) (domain.SellerOrderPage, error) {
	if q.Status != "" && !domain.IsShipmentStatus(q.Status) {
		return domain.SellerOrderPage{}, domain.ValidationProblem(map[string]string{"status": "Unknown status"})
	}
	if q.Size <= 0 {
		q.Size = 20
	}
	if q.Size > 100 {
		return domain.SellerOrderPage{}, domain.ValidationProblem(map[string]string{"limit": "At most 100"})
	}
	sort, err := q.sort(SellerOrderColumns)
	if err != nil {
		return domain.SellerOrderPage{}, err
	}
	sc, err := s.r.require(ctx, c, domain.OrgKindSeller, "orders", domain.LevelView)
	if err != nil {
		return domain.SellerOrderPage{}, err
	}
	if strings.TrimSpace(q.Q) == "" && len(sort) == 0 {
		// Upstream order (newest first) and cursors.
		page, err := s.r.d.Orders.Queue(ctx, c.BearerToken, strings.ToLower(q.Status), q.Cursor, q.Size)
		if err != nil {
			return domain.SellerOrderPage{}, mapErr("orders", err)
		}
		out := domain.SellerOrderPage{Items: []domain.SellerOrderRow{}, NextCursor: page.NextCursor, Total: page.Total, Sort: []string{}}
		for _, it := range page.Items {
			out.Items = append(out.Items, sellerOrderRowOf(it))
		}
		return out, nil
	}
	// Search over every column and any sort: the BFF reads the queue and pages it (cursor = offset).
	rows, partial, err := s.cache.load(sc.member.ID+"@"+sc.org.ID, "orders", q.Status, func() ([]domain.SellerOrderRow, bool, error) {
		return s.scan(ctx, c.BearerToken, q.Status, domain.TableScanMaxRows)
	})
	if err != nil {
		return domain.SellerOrderPage{}, err
	}
	picked := pickRows(rows, SellerOrderColumns, sellerOrderRowCells, q.Q, nil, sort)
	offset := cursorOffset(q.Cursor)
	pageItems, more := pageSlice(picked, offset, q.Size)
	out := domain.SellerOrderPage{Items: pageItems, Total: int64(len(picked)), Sort: domain.SortStrings(sort), Partial: partial}
	if more {
		next := strconv.Itoa(offset + q.Size)
		out.NextCursor = &next
	}
	return out, nil
}

// scan reads up to limit rows of the shipment queue.
func (s *sellerOrdersImpl) scan(ctx context.Context, bearer, status string, limit int) ([]domain.SellerOrderRow, bool, error) {
	out := []domain.SellerOrderRow{}
	cursor := ""
	for {
		page, err := s.r.d.Orders.Queue(ctx, bearer, strings.ToLower(status), cursor, exportPageSize)
		if err != nil {
			return nil, false, mapErr("orders", err)
		}
		for _, it := range page.Items {
			if len(out) == limit {
				return out, true, nil
			}
			out = append(out, sellerOrderRowOf(it))
		}
		if page.NextCursor == nil || *page.NextCursor == "" || len(page.Items) == 0 {
			return out, false, nil
		}
		cursor = *page.NextCursor
	}
}

func sellerOrderRowOf(it port.SellerShipmentRow) domain.SellerOrderRow {
	sh := shipmentOf(it.Shipment)
	count := 0
	for _, l := range sh.Lines {
		count += l.Quantity
	}
	row := domain.SellerOrderRow{OrderNumber: it.OrderNumber, ShipmentID: sh.ShipmentID, PlacedAt: it.PlacedAt, ItemsSummary: it.Shipment.Items,
		ItemCount: max(count, 1), TotalMinor: sh.TotalMinor, Currency: domain.Currency, Fulfilment: sh.Fulfilment, Status: sh.Status,
		StatusLabel: sh.StatusLabel, Carrier: sh.Carrier, TrackingNumber: sh.TrackingNumber, NextActions: sh.NextActions, CanBuyLabel: sh.CanBuyLabel}
	if it.ShipTo != nil {
		row.BuyerName = it.ShipTo.FullName
		row.ShipTo = strings.TrimSuffix(it.ShipTo.City+", "+it.ShipTo.State, ", ")
	}
	if row.ItemsSummary == "" && len(sh.Lines) > 0 {
		row.ItemsSummary = sh.Lines[0].Title
	}
	return row
}

func (s *sellerOrdersImpl) Get(ctx context.Context, c *domain.Caller, orderNumber string) (domain.SellerOrderDetail, error) {
	sc, err := s.r.require(ctx, c, domain.OrgKindSeller, "orders", domain.LevelView)
	if err != nil {
		return domain.SellerOrderDetail{}, err
	}
	o, err := s.r.d.Orders.Order(ctx, c.BearerToken, orderNumber)
	if err != nil {
		return domain.SellerOrderDetail{}, mapErr("orders", err)
	}
	out := orderOf(o)
	s.withProofLinks(ctx, &out)
	out.Earnings = s.earnings(ctx, c, sc.org.ID, out)
	s.suggestParcels(ctx, c, &out)
	return out, nil
}

// suggestParcels fills the package of every shipment that still needs a label from what the products were listed
// with: the weight of every unit, the footprint of the largest item and the heights stacked. Best effort: products
// without a listed package leave the form empty.
func (s *sellerOrdersImpl) suggestParcels(ctx context.Context, c *domain.Caller, o *domain.SellerOrderDetail) {
	if s.r.d.Listing == nil {
		return
	}
	for i := range o.Shipments {
		sh := &o.Shipments[i]
		if !sh.CanBuyLabel {
			continue
		}
		var p domain.SuggestedParcel
		known := true
		for _, l := range sh.Lines {
			prod, err := s.r.d.Listing.ProductByATIN(ctx, c.BearerToken, l.VariantATIN)
			if err != nil || prod.Listing == nil || prod.Listing.Packaging == nil || prod.Listing.Packaging.WeightLb <= 0 {
				known = false
				break
			}
			pk := prod.Listing.Packaging
			q := float64(max(1, l.Quantity))
			p.WeightLb += pk.WeightLb * q
			p.LengthIn = max(p.LengthIn, pk.LengthIn)
			p.WidthIn = max(p.WidthIn, pk.WidthIn)
			p.HeightIn += pk.HeightIn * q
		}
		if known && p.WeightLb > 0 {
			p.WeightLb = math.Round(p.WeightLb*100) / 100
			p.HeightIn = math.Min(108, math.Round(p.HeightIn*10)/10)
			sh.SuggestedParcel = &p
		}
	}
}

func (s *sellerOrdersImpl) Label(ctx context.Context, c *domain.Caller, orderNumber, shipmentID string) (domain.FileDownload, error) {
	if _, err := s.r.require(ctx, c, domain.OrgKindSeller, "orders", domain.LevelView); err != nil {
		return domain.FileDownload{}, err
	}
	o, err := s.r.d.Orders.Order(ctx, c.BearerToken, orderNumber)
	if err != nil {
		return domain.FileDownload{}, mapErr("orders", err)
	}
	sh, ok := orderOf(o).Shipment(shipmentID)
	if !ok {
		return domain.FileDownload{}, domain.NewProblem(http.StatusNotFound, domain.CodeNotFound, "Not found", "That shipment isn't part of this order.")
	}
	if !sh.HasLabel || sh.Carrier == nil || sh.TrackingNumber == nil {
		return domain.FileDownload{}, domain.NewProblem(http.StatusNotFound, domain.CodeNotFound, "No label", "No shipping label was bought for this shipment.")
	}
	f, err := s.r.d.Carrier.LabelDocument(ctx, c.BearerToken, strings.ToLower(*sh.Carrier), *sh.TrackingNumber)
	if err != nil {
		return domain.FileDownload{}, mapErr("carrier-gateway", err)
	}
	return f, nil
}

// earnings is the organisation's row of the order's payment split (best effort: nil before the payment is split or
// when it can't be read). orders' split does not relay payments' deliveryFeesMinor yet; until it does, the seller fees
// snapshotted on the AfroTransact Delivery shipments are used.
func (s *sellerOrdersImpl) earnings(ctx context.Context, c *domain.Caller, orgID string, o domain.SellerOrderDetail) *domain.OrderEarnings {
	split, err := s.r.d.Orders.Split(ctx, c.BearerToken, o.OrderNumber)
	if err != nil {
		s.r.d.Log.DebugContext(ctx, "payment split unavailable; no earnings breakdown", "error", err)
		return nil
	}
	var snapshot int64
	for _, sh := range o.Shipments {
		if sh.LocalDelivery != nil {
			snapshot += sh.LocalDelivery.SellerFeeMinor
		}
	}
	for _, r := range split.Recipients {
		if r.Recipient != orgID || strings.EqualFold(r.Kind, "PLATFORM") {
			continue
		}
		fees := snapshot
		if r.DeliveryFeesMinor != nil {
			fees = *r.DeliveryFeesMinor
		}
		return &domain.OrderEarnings{ItemsMinor: r.ItemsMinor, ShippingMinor: r.ShippingMinor, CommissionMinor: r.CommissionMinor, FbaFeesMinor: r.FbaFeesMinor,
			DeliveryFeesMinor: fees, StripeFeeMinor: r.StripeFeeMinor, NetMinor: r.NetMinor}
	}
	return nil
}

func (s *sellerOrdersImpl) UpdateStatus(ctx context.Context, c *domain.Caller, key, orderNumber, shipmentID, status, handoverCode string) (domain.SellerOrderDetail, error) {
	if !domain.IsSellerStatus(status) {
		return domain.SellerOrderDetail{}, domain.ValidationProblem(map[string]string{"status": "Sellers can confirm, pack, mark ready, mark shipped and hand a pickup order over"})
	}
	handoverCode = strings.TrimSpace(handoverCode)
	if status != "DELIVERED" {
		handoverCode = ""
	}
	if _, err := s.r.require(ctx, c, domain.OrgKindSeller, "orders", domain.LevelEdit); err != nil {
		return domain.SellerOrderDetail{}, err
	}
	if status == "DELIVERED" && !domain.IsHandoverCode(handoverCode) {
		// A pickup order is handed over against the buyer's code; a parcel the seller shipped is confirmed delivered
		// without one (orders refuses any other case).
		if handoverCode != "" || s.waitingForHandover(ctx, c, orderNumber, shipmentID) {
			return domain.SellerOrderDetail{}, domain.ValidationProblem(map[string]string{"handoverCode": "Ask the buyer for their 4-digit code"})
		}
	}
	o, err := s.r.d.Orders.Transition(ctx, c.BearerToken, key, shipmentID, strings.ToLower(status), handoverCode)
	if err != nil {
		return domain.SellerOrderDetail{}, mapErr("orders", err)
	}
	if o.OrderNumber == "" {
		if o, err = s.r.d.Orders.Order(ctx, c.BearerToken, orderNumber); err != nil {
			return domain.SellerOrderDetail{}, mapErr("orders", err)
		}
	}
	out := orderOf(o)
	s.withProofLinks(ctx, &out)
	return out, nil
}

// waitingForHandover: the shipment is ready at the seller's shop (a pickup order), which only the buyer's code releases.
// When it cannot be read, the code is asked for, the safe side.
func (s *sellerOrdersImpl) waitingForHandover(ctx context.Context, c *domain.Caller, orderNumber, shipmentID string) bool {
	o, err := s.r.d.Orders.Order(ctx, c.BearerToken, orderNumber)
	if err != nil {
		return true
	}
	sh, ok := orderOf(o).Shipment(shipmentID)
	return !ok || sh.Status == "READY"
}

// withProofLinks gives the shipments' delivery proofs their short-lived photo and signature links.
func (s *sellerOrdersImpl) withProofLinks(ctx context.Context, o *domain.SellerOrderDetail) {
	proofs := make([]*domain.DeliveryProof, 0, len(o.Shipments))
	for _, sh := range o.Shipments {
		proofs = append(proofs, sh.Proof)
	}
	attachProofLinks(ctx, s.r.d.ProofLinks, proofs)
}

func validateParcel(p domain.Parcel, from domain.SellerAddress) map[string]string {
	errs := map[string]string{}
	if p.WeightLb <= 0 || p.WeightLb > 150 {
		errs["parcel.weightLb"] = "Enter a weight between 0 and 150 lb"
	}
	if p.LengthIn < 0 || p.WidthIn < 0 || p.HeightIn < 0 {
		errs["parcel"] = "Dimensions can't be negative"
	}
	for k, v := range map[string]string{"shipFrom.name": from.Name, "shipFrom.line1": from.Line1, "shipFrom.city": from.City,
		"shipFrom.state": from.State, "shipFrom.postalCode": from.PostalCode} {
		if strings.TrimSpace(v) == "" {
			errs[k] = "Required"
		}
	}
	return errs
}

func carrierAddress(a domain.SellerAddress) port.CarrierAddress {
	country := a.Country
	if country == "" {
		country = "US"
	}
	out := port.CarrierAddress{Name: a.Name, Line1: a.Line1, City: a.City, State: a.State, PostalCode: a.PostalCode, Country: country}
	if a.Phone != nil {
		out.Phone = *a.Phone
	}
	if a.Line2 != nil {
		out.Line2 = *a.Line2
	}
	return out
}

// labelShipment loads the order and checks that the shipment can get a label; it returns the carrier shipment.
func (s *sellerOrdersImpl) labelShipment(ctx context.Context, c *domain.Caller, orgID, orderNumber, shipmentID string, parcel domain.Parcel, from domain.SellerAddress) (port.CarrierShipment, error) {
	o, err := s.r.d.Orders.Order(ctx, c.BearerToken, orderNumber)
	if err != nil {
		return port.CarrierShipment{}, mapErr("orders", err)
	}
	detail := orderOf(o)
	sh, ok := detail.Shipment(shipmentID)
	if !ok {
		return port.CarrierShipment{}, domain.NewProblem(http.StatusNotFound, domain.CodeNotFound, "Not found", "That shipment isn't part of this order.")
	}
	if !sh.CanBuyLabel || detail.ShippingAddress == nil {
		return port.CarrierShipment{}, domain.NewProblem(http.StatusConflict, domain.CodeConflict, "Label not possible",
			"Labels can be bought for confirmed seller-shipped orders without tracking.")
	}
	return port.CarrierShipment{Reference: shipmentID, OriginNode: orgID, From: carrierAddress(from), To: carrierAddress(*detail.ShippingAddress),
		Parcels: []domain.Parcel{parcel}}, nil
}

func (s *sellerOrdersImpl) Rates(ctx context.Context, c *domain.Caller, orderNumber, shipmentID string, parcel domain.Parcel, from domain.SellerAddress, carrier string) (domain.RateQuotes, error) {
	if errs := validateParcel(parcel, from); len(errs) > 0 {
		return domain.RateQuotes{}, domain.ValidationProblem(errs)
	}
	sc, err := s.r.require(ctx, c, domain.OrgKindSeller, "orders", domain.LevelEdit)
	if err != nil {
		return domain.RateQuotes{}, err
	}
	shipment, err := s.labelShipment(ctx, c, sc.org.ID, orderNumber, shipmentID, parcel, from)
	if err != nil {
		return domain.RateQuotes{}, err
	}
	res, err := s.r.d.Carrier.Rates(ctx, c.BearerToken, shipment, carrier)
	if err != nil {
		return domain.RateQuotes{}, mapErr("carrier-gateway", err)
	}
	out := domain.RateQuotes{Quotes: []domain.RateQuote{}, Partial: res.Partial}
	for _, q := range res.Quotes {
		cur := q.Currency
		if cur == "" {
			cur = domain.Currency
		}
		out.Quotes = append(out.Quotes, domain.RateQuote{Carrier: q.Carrier, Service: q.Service, ServiceName: firstNonEmpty(q.ServiceName, q.Service),
			AmountMinor: q.AmountMinor, Currency: cur, TransitDays: q.TransitDays, EstimatedDelivery: q.EstimatedDelivery})
	}
	slices.SortStableFunc(out.Quotes, func(a, b domain.RateQuote) int { return int(a.AmountMinor - b.AmountMinor) })
	return out, nil
}

var carriers = []string{"usps", "ups", "fedex", "dhl"}

func (s *sellerOrdersImpl) BuyLabel(ctx context.Context, c *domain.Caller, key, orderNumber, shipmentID, carrier, service string, parcel domain.Parcel, from domain.SellerAddress) (domain.LabelPurchase, error) {
	errs := validateParcel(parcel, from)
	if !slices.Contains(carriers, carrier) {
		errs["carrier"] = "Choose USPS, UPS, FedEx or DHL"
	}
	if strings.TrimSpace(service) == "" {
		errs["service"] = "Choose a rate"
	}
	if l := len(key); l < 8 || l > 128 {
		errs["Idempotency-Key"] = "Send an 8–128 character Idempotency-Key header"
	}
	if len(errs) > 0 {
		return domain.LabelPurchase{}, domain.ValidationProblem(errs)
	}
	sc, err := s.r.require(ctx, c, domain.OrgKindSeller, "orders", domain.LevelEdit)
	if err != nil {
		return domain.LabelPurchase{}, err
	}
	shipment, err := s.labelShipment(ctx, c, sc.org.ID, orderNumber, shipmentID, parcel, from)
	if err != nil {
		return domain.LabelPurchase{}, err
	}
	label, err := s.r.d.Carrier.BuyLabel(ctx, c.BearerToken, key, carrier, service, shipment)
	if err != nil {
		return domain.LabelPurchase{}, mapErr("carrier-gateway", err)
	}
	// Retrying with the same Idempotency-Key returns the same label, so a failed attach can simply be retried.
	o, err := s.r.d.Orders.AttachTracking(ctx, c.BearerToken, shipmentID, label.Carrier, label.TrackingNumber)
	if err != nil {
		return domain.LabelPurchase{}, mapErr("orders", err)
	}
	if o.OrderNumber == "" {
		if o, err = s.r.d.Orders.Order(ctx, c.BearerToken, orderNumber); err != nil {
			return domain.LabelPurchase{}, mapErr("orders", err)
		}
	}
	cur := label.Currency
	if cur == "" {
		cur = domain.Currency
	}
	return domain.LabelPurchase{Carrier: firstNonEmpty(label.Carrier, carrier), Service: firstNonEmpty(label.Service, service), TrackingNumber: label.TrackingNumber,
		CostMinor: label.CostMinor, Currency: cur, DocumentURL: strPtr(label.DocumentURL), Order: orderOf(o)}, nil
}
