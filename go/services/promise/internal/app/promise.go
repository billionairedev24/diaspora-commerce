// Package app holds the promise use cases: batch promises for many offers to one destination ZIP (search cards,
// PDP, cart, checkout) and promise snapshots by id.
package app

import (
	"context"
	"crypto/sha256"
	"encoding/base32"
	"errors"
	"fmt"
	"log/slog"
	"strings"
	"time"

	"github.com/billionairedev24/diaspora-commerce/go/services/promise/internal/domain"
	"github.com/billionairedev24/diaspora-commerce/go/services/promise/internal/port"
)

// OriginInput identifies where an offer ships from. FCs are looked up by id; sellers send their own rules.
type OriginInput struct {
	Type          domain.OriginKind `json:"type"`
	ID            string            `json:"id"`
	ShipFromZIP   string            `json:"shipFromZip,omitempty"`
	HandlingDays  int               `json:"handlingDays,omitempty"`
	Cutoff        string            `json:"cutoff,omitempty"`   // HH:MM local, default from config
	Timezone      string            `json:"timezone,omitempty"` // default: the ship-from ZIP's zone
	WorksSaturday bool              `json:"worksSaturday,omitempty"`
}

// PickupInput describes the pickup location (from carrier-gateway pickup locations) when not an FC counter.
type PickupInput struct {
	LocationID string                `json:"locationId,omitempty"`
	ReadyHours *int                  `json:"readyHours,omitempty"`
	Timezone   string                `json:"timezone,omitempty"`
	Hours      []domain.OpeningHours `json:"hours,omitempty"`
}

// MethodInput is the fulfilment method of an offer.
type MethodInput struct {
	Type         domain.MethodType   `json:"type"`
	ServiceLevel domain.ServiceLevel `json:"serviceLevel,omitempty"`
	Pickup       *PickupInput        `json:"pickup,omitempty"`
}

// OfferInput is one offer to promise.
type OfferInput struct {
	OfferID string      `json:"offerId"`
	Origin  OriginInput `json:"origin"`
	Method  MethodInput `json:"method"`
}

// BatchRequest promises many offers to one destination.
type BatchRequest struct {
	DestinationZIP string       `json:"destinationZip"`
	Offers         []OfferInput `json:"offers"`
}

// BatchResponse holds one promise per offer, in request order.
type BatchResponse struct {
	DestinationZIP string           `json:"destinationZip"`
	GeneratedAt    time.Time        `json:"generatedAt"`
	Promises       []domain.Promise `json:"promises"`
}

// Settings are the configurable promise rules.
type Settings struct {
	ServiceLevels     map[domain.ServiceLevel]domain.ServiceRule
	SellerCutoff      domain.Clock
	SellerPickupReady int
	SellerPickupHours []domain.OpeningHours
	SnapshotTTL       time.Duration
	MaxOffers         int
	MaxHandlingDays   int
}

// PromiseService computes and serves delivery promises.
type PromiseService interface {
	Promise(ctx context.Context, req BatchRequest) (BatchResponse, error)
	Get(ctx context.Context, id string) (domain.Promise, error)
}

type promiseServiceImpl struct {
	planner domain.Planner
	zips    port.ZipDirectory
	transit port.TransitTable
	nodes   port.NodeDirectory
	store   port.PromiseStore
	cfg     Settings
	log     *slog.Logger
	now     func() time.Time
}

var _ PromiseService = (*promiseServiceImpl)(nil)

// NewPromiseService returns a PromiseService.
func NewPromiseService(planner domain.Planner, zips port.ZipDirectory, transit port.TransitTable, nodes port.NodeDirectory,
	store port.PromiseStore, cfg Settings, log *slog.Logger, now func() time.Time) PromiseService {
	if cfg.SnapshotTTL <= 0 {
		cfg.SnapshotTTL = 24 * time.Hour
	}
	if cfg.MaxOffers <= 0 {
		cfg.MaxOffers = 100
	}
	if cfg.MaxHandlingDays <= 0 {
		cfg.MaxHandlingDays = 30
	}
	return &promiseServiceImpl{planner: planner, zips: zips, transit: transit, nodes: nodes, store: store, cfg: cfg, log: log, now: now}
}

func (s *promiseServiceImpl) Promise(ctx context.Context, req BatchRequest) (BatchResponse, error) {
	dest, err := s.zips.Lookup(req.DestinationZIP)
	if err != nil {
		return BatchResponse{}, fmt.Errorf("destination: %w", err)
	}
	if len(req.Offers) == 0 || len(req.Offers) > s.cfg.MaxOffers {
		return BatchResponse{}, fmt.Errorf("%w: send between 1 and %d offers", domain.ErrInvalid, s.cfg.MaxOffers)
	}
	now := s.now()
	out := BatchResponse{DestinationZIP: dest.ZIP, GeneratedAt: now.UTC(), Promises: make([]domain.Promise, 0, len(req.Offers))}
	snapshots := make([]domain.Promise, 0, len(req.Offers))
	for _, o := range req.Offers {
		p := s.one(ctx, o, dest, now)
		p.OfferID, p.DestinationZIP = o.OfferID, dest.ZIP
		p.Origin = domain.OriginRef{Kind: o.Origin.Type, ID: o.Origin.ID}
		p.Method = o.Method.Type
		if o.Method.Type == domain.MethodCarrier {
			p.ServiceLevel = o.Method.ServiceLevel
		}
		domain.Describe(&p, now, dest.Location)
		p.ComputedAt, p.ExpiresAt = now.UTC(), now.Add(s.cfg.SnapshotTTL).UTC()
		if p.Available {
			p.ID = promiseID(o, p)
			snapshots = append(snapshots, p)
		}
		out.Promises = append(out.Promises, p)
	}
	if err := s.store.SaveSnapshots(ctx, snapshots, s.cfg.SnapshotTTL); err != nil {
		return BatchResponse{}, fmt.Errorf("save promise snapshots: %w", err)
	}
	return out, nil
}

func (s *promiseServiceImpl) Get(ctx context.Context, id string) (domain.Promise, error) {
	p, err := s.store.Snapshot(ctx, id)
	if err != nil {
		return domain.Promise{}, fmt.Errorf("snapshot: %w", err)
	}
	return p, nil
}

// one plans an offer, reusing a cached computation for the same origin, method, ZIP and origin-local hour.
func (s *promiseServiceImpl) one(ctx context.Context, o OfferInput, dest domain.ZipInfo, now time.Time) domain.Promise {
	in, key, err := s.input(ctx, o, dest, now)
	if err != nil {
		var unavailable unavailableError
		if errors.As(err, &unavailable) {
			return domain.Unavailable(string(unavailable))
		}
		return domain.Unavailable(strings.Replace(err.Error(), domain.ErrInvalid.Error()+": ", "", 1))
	}
	if cached, ok, err := s.store.GetComputed(ctx, key); err != nil {
		s.log.WarnContext(ctx, "promise cache read failed", "error", err)
	} else if ok && now.Before(cached.ValidUntil) {
		return cached
	}
	p := s.planner.Plan(in)
	if ttl := p.ValidUntil.Sub(now); ttl > 0 {
		if err := s.store.PutComputed(ctx, key, p, ttl); err != nil {
			s.log.WarnContext(ctx, "promise cache write failed", "error", err)
		}
	}
	return p
}

type unavailableError string

func (e unavailableError) Error() string { return string(e) }

func (s *promiseServiceImpl) origin(o OriginInput) (domain.Origin, *domain.FulfilmentCentre, error) {
	switch o.Type {
	case domain.OriginFC:
		fc, ok := s.nodes.FC(o.ID)
		if !ok {
			return domain.Origin{}, nil, unavailableError("Unknown fulfilment centre " + o.ID)
		}
		return fc.Origin(), &fc, nil
	case domain.OriginSeller:
		info, err := s.zips.Lookup(o.ShipFromZIP)
		if err != nil {
			return domain.Origin{}, nil, fmt.Errorf("seller ship-from: %w", err)
		}
		if o.HandlingDays < 0 || o.HandlingDays > s.cfg.MaxHandlingDays {
			return domain.Origin{}, nil, fmt.Errorf("%w: handlingDays must be 0-%d", domain.ErrInvalid, s.cfg.MaxHandlingDays)
		}
		loc := info.Location
		if o.Timezone != "" {
			if loc, err = time.LoadLocation(o.Timezone); err != nil {
				return domain.Origin{}, nil, fmt.Errorf("%w: unknown timezone %q", domain.ErrInvalid, o.Timezone)
			}
		}
		cutoff := s.cfg.SellerCutoff
		if o.Cutoff != "" {
			if cutoff, err = domain.ParseClock(o.Cutoff); err != nil {
				return domain.Origin{}, nil, fmt.Errorf("seller cutoff: %w", err)
			}
		}
		return domain.Origin{Kind: domain.OriginSeller, ID: o.ID, ZIP: info.ZIP, Location: loc, Cutoff: cutoff,
			HandlingDays: o.HandlingDays, WorksSaturday: o.WorksSaturday}, nil, nil
	default:
		return domain.Origin{}, nil, fmt.Errorf("%w: origin type must be FC or SELLER", domain.ErrInvalid)
	}
}

func (s *promiseServiceImpl) input(ctx context.Context, o OfferInput, dest domain.ZipInfo, now time.Time) (domain.PlanInput, string, error) {
	origin, fc, err := s.origin(o.Origin)
	if err != nil {
		return domain.PlanInput{}, "", err
	}
	in := domain.PlanInput{Now: now, Origin: origin, Method: o.Method.Type, Level: o.Method.ServiceLevel, Customer: dest.Location}
	methodKey := string(o.Method.Type)
	switch o.Method.Type {
	case domain.MethodCarrier:
		rule, ok := s.cfg.ServiceLevels[o.Method.ServiceLevel]
		if !ok {
			return domain.PlanInput{}, "", fmt.Errorf("%w: unknown service level %q", domain.ErrInvalid, o.Method.ServiceLevel)
		}
		t, err := s.transit.Transit(ctx, origin.ZIP, dest.ZIP)
		if err != nil {
			return domain.PlanInput{}, "", fmt.Errorf("transit: %w", err)
		}
		in.Service, in.Transit = rule, t
		methodKey += ":" + string(o.Method.ServiceLevel)
	case domain.MethodAfroDelivery:
		hub := fc
		if hub == nil {
			hub = s.hubFor(origin.ZIP)
		}
		if hub == nil || !hub.Serves(dest.ZIP3) {
			return domain.PlanInput{}, "", unavailableError("Outside the AfroTransact Delivery area for this item")
		}
		in.Zone = hub.Zone(dest.ZIP3)
		if fc == nil {
			in.Zone.SameDay, in.Zone.NextDay = false, true // seller parcels are collected by a driver, then delivered next day
		}
		methodKey += ":" + hub.ID
	case domain.MethodPickup:
		w, key, err := s.pickupWindow(fc, o.Method.Pickup, origin)
		if err != nil {
			return domain.PlanInput{}, "", err
		}
		in.Pickup = w
		methodKey += ":" + key
	default:
		return domain.PlanInput{}, "", fmt.Errorf("%w: method must be CARRIER, AFROTRANSACT_DELIVERY or PICKUP", domain.ErrInvalid)
	}
	originKey := string(origin.Kind) + ":" + origin.ID
	if origin.Kind == domain.OriginSeller {
		originKey += fmt.Sprintf(":%s:%d:%02d%02d:%s:%t", origin.ZIP, origin.HandlingDays, origin.Cutoff.Hour, origin.Cutoff.Minute,
			origin.Location.String(), origin.WorksSaturday)
	}
	return in, originKey + "|" + methodKey + "|" + dest.ZIP + "|" + now.In(origin.Location).Format("2006010215"), nil
}

// hubFor finds the FC whose driver zone covers a seller's ship-from ZIP.
func (s *promiseServiceImpl) hubFor(zip string) *domain.FulfilmentCentre {
	for _, fc := range s.nodes.All() {
		if fc.Serves(zip[:3]) {
			return &fc
		}
	}
	return nil
}

func (s *promiseServiceImpl) pickupWindow(fc *domain.FulfilmentCentre, in *PickupInput, origin domain.Origin) (domain.PickupWindow, string, error) {
	w := domain.PickupWindow{Location: origin.Location, ReadyHours: s.cfg.SellerPickupReady, Hours: s.cfg.SellerPickupHours}
	key := "default"
	if fc != nil {
		w.ReadyHours, w.Hours, key = fc.PickupReadyHours, fc.PickupHours, "fc"
	}
	if in != nil {
		if in.ReadyHours != nil {
			if *in.ReadyHours < 0 || *in.ReadyHours > 24*14 {
				return domain.PickupWindow{}, "", fmt.Errorf("%w: readyHours out of range", domain.ErrInvalid)
			}
			w.ReadyHours = *in.ReadyHours
		}
		if in.Timezone != "" {
			loc, err := time.LoadLocation(in.Timezone)
			if err != nil {
				return domain.PickupWindow{}, "", fmt.Errorf("%w: unknown timezone %q", domain.ErrInvalid, in.Timezone)
			}
			w.Location = loc
		}
		if len(in.Hours) > 0 {
			w.Hours = in.Hours
		}
		key = fmt.Sprintf("%s:%d:%s:%v", in.LocationID, w.ReadyHours, w.Location, w.Hours)
	}
	return w, key, nil
}

var idEncoding = base32.StdEncoding.WithPadding(base32.NoPadding)

// promiseID is deterministic for the same offer and dates, so repeated views of one promise share a snapshot.
func promiseID(o OfferInput, p domain.Promise) string {
	var b strings.Builder
	fmt.Fprintf(&b, "%s|%s|%s|%s|%s|%s|%s", o.OfferID, o.Origin.Type, o.Origin.ID, p.Method, p.ServiceLevel, p.DestinationZIP, p.EarliestDate)
	fmt.Fprintf(&b, "|%s|%v|%v|%v", p.LatestDate, p.ShipDate, p.CutoffAt, p.ReadyAt)
	sum := sha256.Sum256([]byte(b.String()))
	return "prm_" + strings.ToLower(idEncoding.EncodeToString(sum[:15]))
}
