package app

import (
	"context"
	"encoding/json"
	"errors"
	"fmt"
	"strings"
	"time"

	"github.com/google/uuid"

	"github.com/billionairedev24/diaspora-commerce/go/pkg/auth"
	"github.com/billionairedev24/diaspora-commerce/go/pkg/events"
	"github.com/billionairedev24/diaspora-commerce/go/services/carrier-gateway/internal/domain"
	"github.com/billionairedev24/diaspora-commerce/go/services/carrier-gateway/internal/port"
)

// Permissions checked by carrier-gateway.
const (
	PermRate  = "shipping:rate"
	PermLabel = "shipping:label"
	PermVoid  = "shipping:void"
	PermAdmin = "shipping:admin"
)

// ErrForbidden is returned when the actor may not act on a resource (e.g. another seller's pickup location).
var ErrForbidden = errors.New("forbidden")

// ReadyRequest tells a customer their order is ready at a pickup location.
type ReadyRequest struct {
	OrderRef    string `json:"orderRef"`
	CustomerRef string `json:"customerRef"`
	PickupCode  string `json:"pickupCode,omitempty"`
}

// PickupService schedules carrier pickups and manages customer pickup locations (platform counters, lockers and
// seller pickup with address and hours).
type PickupService interface {
	Schedule(ctx context.Context, req domain.PickupRequest) (domain.Pickup, error)
	SaveLocation(ctx context.Context, actor auth.Actor, loc domain.PickupLocation) (domain.PickupLocation, error)
	GetLocation(ctx context.Context, id string) (domain.PickupLocation, error)
	ListLocations(ctx context.Context, ownerOrg, postalPrefix string) ([]domain.PickupLocation, error)
	// ManagedLocations lists every location the actor manages, inactive ones too: staff with shipping:admin (all, or
	// one owner's), a seller its own org's SELLER locations.
	ManagedLocations(ctx context.Context, actor auth.Actor, ownerOrg string) ([]domain.PickupLocation, error)
	MarkReady(ctx context.Context, actor auth.Actor, locationID string, req ReadyRequest) error
}

type pickupServiceImpl struct {
	carriers Carriers
	accounts port.AccountResolver
	repo     port.PickupRepository
	outbox   port.Outbox
	now      func() time.Time
}

var _ PickupService = (*pickupServiceImpl)(nil)

// NewPickupService returns a PickupService.
func NewPickupService(carriers Carriers, accounts port.AccountResolver, repo port.PickupRepository, outbox port.Outbox, now func() time.Time) PickupService {
	return &pickupServiceImpl{carriers: carriers, accounts: accounts, repo: repo, outbox: outbox, now: now}
}

func (s *pickupServiceImpl) Schedule(ctx context.Context, req domain.PickupRequest) (domain.Pickup, error) {
	if err := req.Validate(); err != nil {
		return domain.Pickup{}, fmt.Errorf("pickup: %w", err)
	}
	c, err := s.carriers.get(req.Carrier)
	if err != nil {
		return domain.Pickup{}, err
	}
	acct, err := accountFor(ctx, s.accounts, req.OriginNode, req.Carrier)
	if err != nil {
		return domain.Pickup{}, err
	}
	req.Account = acct
	p, err := c.SchedulePickup(ctx, req)
	if err != nil {
		return domain.Pickup{}, fmt.Errorf("schedule pickup: %w", err)
	}
	p.ID, p.OriginNode, p.CreatedAt = uuid.NewString(), req.OriginNode, s.now().UTC()
	if err := s.repo.SavePickup(context.WithoutCancel(ctx), p, req); err != nil {
		return domain.Pickup{}, fmt.Errorf("save pickup: %w", err)
	}
	return p, nil
}

// canManage: staff with shipping:admin manage every location; sellers with shipping:label manage their own
// org's SELLER locations only.
func canManage(actor auth.Actor, loc domain.PickupLocation) bool {
	if actor.Can(PermAdmin) {
		return true
	}
	return loc.Kind == domain.PickupSeller && actor.OrgID != "" && actor.OrgID == loc.OwnerOrg && actor.Can(PermLabel)
}

func (s *pickupServiceImpl) SaveLocation(ctx context.Context, actor auth.Actor, loc domain.PickupLocation) (domain.PickupLocation, error) {
	if loc.Kind == domain.PickupSeller && loc.OwnerOrg == "" {
		loc.OwnerOrg = actor.OrgID
	}
	loc.Address.Country = strings.ToUpper(loc.Address.CountryOrUS())
	if err := loc.Validate(); err != nil {
		return domain.PickupLocation{}, fmt.Errorf("pickup location: %w", err)
	}
	if !canManage(actor, loc) {
		return domain.PickupLocation{}, fmt.Errorf("%w: cannot manage this pickup location", ErrForbidden)
	}
	if loc.ID == "" {
		loc.ID = uuid.NewString()
	} else {
		existing, err := s.repo.GetLocation(ctx, loc.ID)
		switch {
		case errors.Is(err, domain.ErrNotFound):
		case err != nil:
			return domain.PickupLocation{}, fmt.Errorf("load location: %w", err)
		case !canManage(actor, existing):
			return domain.PickupLocation{}, fmt.Errorf("%w: cannot manage this pickup location", ErrForbidden)
		}
	}
	loc.UpdatedAt = s.now().UTC()
	if err := s.repo.SaveLocation(ctx, loc); err != nil {
		return domain.PickupLocation{}, fmt.Errorf("save location: %w", err)
	}
	return loc, nil
}

func (s *pickupServiceImpl) GetLocation(ctx context.Context, id string) (domain.PickupLocation, error) {
	l, err := s.repo.GetLocation(ctx, id)
	if err != nil {
		return domain.PickupLocation{}, fmt.Errorf("get location: %w", err)
	}
	return l, nil
}

func (s *pickupServiceImpl) ListLocations(ctx context.Context, ownerOrg, postalPrefix string) ([]domain.PickupLocation, error) {
	ls, err := s.repo.ListLocations(ctx, ownerOrg, postalPrefix, true)
	if err != nil {
		return nil, fmt.Errorf("list locations: %w", err)
	}
	return ls, nil
}

func (s *pickupServiceImpl) ManagedLocations(ctx context.Context, actor auth.Actor, ownerOrg string) ([]domain.PickupLocation, error) {
	if !actor.Can(PermAdmin) {
		if actor.OrgID == "" || !actor.Can(PermLabel) || (ownerOrg != "" && ownerOrg != actor.OrgID) {
			return nil, fmt.Errorf("%w: cannot list these pickup locations", ErrForbidden)
		}
		ownerOrg = actor.OrgID
	}
	ls, err := s.repo.ListLocations(ctx, ownerOrg, "", false)
	if err != nil {
		return nil, fmt.Errorf("list locations: %w", err)
	}
	if actor.Can(PermAdmin) {
		return ls, nil
	}
	out := make([]domain.PickupLocation, 0, len(ls))
	for _, l := range ls {
		if l.Kind == domain.PickupSeller {
			out = append(out, l)
		}
	}
	return out, nil
}

func (s *pickupServiceImpl) MarkReady(ctx context.Context, actor auth.Actor, id string, req ReadyRequest) error {
	if req.OrderRef == "" || req.CustomerRef == "" {
		return fmt.Errorf("%w: orderRef and customerRef are required", domain.ErrInvalid)
	}
	loc, err := s.repo.GetLocation(ctx, id)
	if err != nil {
		return fmt.Errorf("get location: %w", err)
	}
	if !canManage(actor, loc) && (!actor.IsStaff() || !actor.Can(PermLabel)) {
		return fmt.Errorf("%w: cannot mark orders ready at this location", ErrForbidden)
	}
	payload, err := json.Marshal(domain.PickupReady{LocationID: loc.ID, Name: loc.Name, Kind: string(loc.Kind), Address: loc.Address,
		Hours: loc.Hours, OrderRef: req.OrderRef, CustomerRef: req.CustomerRef, PickupCode: req.PickupCode, ReadyAt: s.now().UTC()})
	if err != nil {
		return fmt.Errorf("encode event: %w", err)
	}
	env := events.Envelope{ID: uuid.NewString(), Type: domain.EventPickupReady, Aggregate: domain.EventAggregate,
		AggregateID: "pickup:" + req.OrderRef, OccurredAt: s.now().UTC(), Payload: payload}
	if err := s.outbox.Enqueue(ctx, env); err != nil {
		return fmt.Errorf("enqueue pickup ready: %w", err)
	}
	return nil
}

// AddressService validates addresses with a carrier's own validation API.
type AddressService interface {
	Validate(ctx context.Context, carrier domain.CarrierCode, a domain.Address) (domain.AddressValidation, error)
}

type addressServiceImpl struct{ carriers Carriers }

var _ AddressService = (*addressServiceImpl)(nil)

// NewAddressService returns an AddressService.
func NewAddressService(carriers Carriers) AddressService {
	return &addressServiceImpl{carriers: carriers}
}

func (s *addressServiceImpl) Validate(ctx context.Context, carrier domain.CarrierCode, a domain.Address) (domain.AddressValidation, error) {
	if a.PostalCode == "" {
		return domain.AddressValidation{}, fmt.Errorf("%w: postal code is required", domain.ErrInvalid)
	}
	if carrier == "" {
		carrier = domain.USPS
		if a.CountryOrUS() != "US" {
			carrier = domain.DHL
		}
	}
	c, err := s.carriers.get(carrier)
	if err != nil {
		return domain.AddressValidation{}, err
	}
	v, err := c.ValidateAddress(ctx, a)
	if err != nil {
		return domain.AddressValidation{}, fmt.Errorf("validate address: %w", err)
	}
	return v, nil
}
