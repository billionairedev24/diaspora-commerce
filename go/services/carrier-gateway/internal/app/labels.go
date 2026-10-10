package app

import (
	"context"
	"crypto/sha256"
	"encoding/hex"
	"encoding/json"
	"errors"
	"fmt"
	"log/slog"
	"strings"
	"time"

	"github.com/google/uuid"

	"github.com/billionairedev24/diaspora-commerce/go/services/carrier-gateway/internal/domain"
	"github.com/billionairedev24/diaspora-commerce/go/services/carrier-gateway/internal/port"
)

// BuyRequest buys a label for one rated service.
type BuyRequest struct {
	IdempotencyKey string             `json:"-"`
	Caller         string             `json:"-"` // scopes idempotency keys per caller
	Shipment       domain.Shipment    `json:"shipment"`
	Carrier        domain.CarrierCode `json:"carrier"`
	Service        domain.ServiceCode `json:"service"`
}

// LabelView is a label record with a short-lived document URL when the store can sign one.
type LabelView struct {
	domain.LabelRecord
	DocumentURL string `json:"documentUrl,omitempty"`
}

// LabelService buys, reads and voids labels.
type LabelService interface {
	Buy(ctx context.Context, req BuyRequest) (LabelView, error)
	Get(ctx context.Context, id string) (LabelView, error)
	Document(ctx context.Context, id string) ([]byte, string, error)
	Void(ctx context.Context, id string) (LabelView, error)
}

// ErrIdempotencyMismatch is returned when an Idempotency-Key is reused with a different request.
var ErrIdempotencyMismatch = errors.New("idempotency key was used for a different request")

type labelServiceImpl struct {
	carriers Carriers
	accounts port.AccountResolver
	labels   port.LabelRepository
	objects  port.ObjectStore
	log      *slog.Logger
	now      func() time.Time
	urlTTL   time.Duration
}

var _ LabelService = (*labelServiceImpl)(nil)

// NewLabelService returns a LabelService.
func NewLabelService(carriers Carriers, accounts port.AccountResolver, labels port.LabelRepository, objects port.ObjectStore,
	log *slog.Logger, now func() time.Time) LabelService {
	return &labelServiceImpl{carriers: carriers, accounts: accounts, labels: labels, objects: objects, log: log, now: now, urlTTL: 10 * time.Minute}
}

func requestHash(req BuyRequest) string {
	b, _ := json.Marshal(struct {
		S domain.Shipment
		C domain.CarrierCode
		V domain.ServiceCode
	}{req.Shipment, req.Carrier, req.Service})
	sum := sha256.Sum256(b)
	return hex.EncodeToString(sum[:])
}

func accountFor(ctx context.Context, r port.AccountResolver, origin string, carrier domain.CarrierCode) (domain.Account, error) {
	accts, err := r.Accounts(ctx, origin)
	if err != nil {
		return domain.Account{}, fmt.Errorf("carrier accounts: %w", err)
	}
	for _, a := range accts {
		if a.Carrier == carrier {
			return a, nil
		}
	}
	return domain.Account{}, fmt.Errorf("%w: no %s account for origin %q", domain.ErrCarrierDisabled, carrier, origin)
}

func (s *labelServiceImpl) Buy(ctx context.Context, req BuyRequest) (LabelView, error) {
	if strings.TrimSpace(req.IdempotencyKey) == "" {
		return LabelView{}, fmt.Errorf("%w: Idempotency-Key header is required", domain.ErrInvalid)
	}
	if req.Service == "" {
		return LabelView{}, fmt.Errorf("%w: service is required", domain.ErrInvalid)
	}
	if err := req.Shipment.Validate(); err != nil {
		return LabelView{}, fmt.Errorf("shipment: %w", err)
	}
	carrier, err := s.carriers.get(req.Carrier)
	if err != nil {
		return LabelView{}, err
	}
	acct, err := accountFor(ctx, s.accounts, req.Shipment.OriginNode, req.Carrier)
	if err != nil {
		return LabelView{}, err
	}
	rec := domain.LabelRecord{
		ID: uuid.NewString(), IdempotencyKey: req.Caller + ":" + req.IdempotencyKey, RequestHash: requestHash(req),
		Reference: req.Shipment.Reference, OriginNode: req.Shipment.OriginNode, Carrier: req.Carrier, Service: req.Service,
		Status: domain.LabelPurchasing, CreatedAt: s.now().UTC(),
	}
	stored, created, err := s.labels.Reserve(ctx, rec)
	if err != nil {
		return LabelView{}, fmt.Errorf("reserve label: %w", err)
	}
	if !created {
		switch {
		case stored.RequestHash != rec.RequestHash:
			return LabelView{}, fmt.Errorf("%w: %w", domain.ErrConflict, ErrIdempotencyMismatch)
		case stored.Status == domain.LabelPurchasing:
			return LabelView{}, fmt.Errorf("%w: a purchase with this Idempotency-Key is in progress", domain.ErrConflict)
		default:
			return s.view(ctx, stored), nil
		}
	}
	shipment := req.Shipment
	shipment.Account = acct
	label, err := carrier.BuyLabel(ctx, shipment, req.Service)
	if err != nil {
		s.release(ctx, rec.ID)
		return LabelView{}, fmt.Errorf("buy label: %w", err)
	}
	rec.TrackingNumber, rec.CarrierShipmentID = label.TrackingNumber, label.CarrierShipmentID
	rec.CostMinor, rec.Currency, rec.Format = label.CostMinor, label.Currency, label.Format
	rec.LabelKey = rec.ObjectKey()
	if err := s.store(ctx, rec.LabelKey, label); err != nil {
		// Never keep a paid label we cannot print: void it at the carrier and free the key for a retry.
		if verr := carrier.Void(context.WithoutCancel(ctx), label.TrackingNumber); verr != nil {
			s.log.ErrorContext(ctx, "label stored nowhere and void failed", "carrier", rec.Carrier, "tracking", label.TrackingNumber, "error", verr)
		}
		s.release(ctx, rec.ID)
		return LabelView{}, fmt.Errorf("store label document: %w", err)
	}
	rec.Status, rec.TrackingState = domain.LabelPurchased, domain.StateLabelCreated
	if err := s.labels.Complete(context.WithoutCancel(ctx), rec); err != nil {
		return LabelView{}, fmt.Errorf("complete label: %w", err)
	}
	if sub, ok := carrier.(port.TrackingSubscriber); ok {
		if err := sub.Subscribe(ctx, []string{rec.TrackingNumber}); err != nil {
			s.log.WarnContext(ctx, "tracking subscription failed; status will rely on manual refresh", "carrier", rec.Carrier, "error", err)
		}
	}
	return s.view(ctx, rec), nil
}

func (s *labelServiceImpl) store(ctx context.Context, key string, l domain.Label) error {
	var err error
	for attempt := range 3 {
		if err = s.objects.Put(ctx, key, l.Format.ContentType(), l.Data); err == nil {
			return nil
		}
		if ctx.Err() != nil {
			break
		}
		time.Sleep(time.Duration(attempt+1) * 100 * time.Millisecond)
	}
	return fmt.Errorf("object store: %w", err)
}

func (s *labelServiceImpl) release(ctx context.Context, id string) {
	if err := s.labels.Release(context.WithoutCancel(ctx), id); err != nil {
		s.log.ErrorContext(ctx, "release idempotency slot", "label", id, "error", err)
	}
}

func (s *labelServiceImpl) view(ctx context.Context, rec domain.LabelRecord) LabelView {
	v := LabelView{LabelRecord: rec}
	if rec.LabelKey != "" && rec.Status == domain.LabelPurchased {
		if u, err := s.objects.SignedURL(ctx, rec.LabelKey, s.urlTTL); err == nil {
			v.DocumentURL = u
		}
	}
	return v
}

func (s *labelServiceImpl) Get(ctx context.Context, id string) (LabelView, error) {
	rec, err := s.labels.Get(ctx, id)
	if err != nil {
		return LabelView{}, fmt.Errorf("get label: %w", err)
	}
	return s.view(ctx, rec), nil
}

func (s *labelServiceImpl) Document(ctx context.Context, id string) ([]byte, string, error) {
	rec, err := s.labels.Get(ctx, id)
	if err != nil {
		return nil, "", fmt.Errorf("get label: %w", err)
	}
	if rec.LabelKey == "" || rec.Status != domain.LabelPurchased {
		return nil, "", fmt.Errorf("%w: label has no printable document", domain.ErrNotFound)
	}
	data, err := s.objects.Get(ctx, rec.LabelKey)
	if err != nil {
		return nil, "", fmt.Errorf("read label document: %w", err)
	}
	return data, rec.Format.ContentType(), nil
}

func (s *labelServiceImpl) Void(ctx context.Context, id string) (LabelView, error) {
	rec, err := s.labels.Get(ctx, id)
	if err != nil {
		return LabelView{}, fmt.Errorf("get label: %w", err)
	}
	if err := rec.CanVoid(); err != nil {
		return LabelView{}, fmt.Errorf("void: %w", err)
	}
	carrier, err := s.carriers.get(rec.Carrier)
	if err != nil {
		return LabelView{}, err
	}
	if err := carrier.Void(ctx, rec.TrackingNumber); err != nil {
		return LabelView{}, fmt.Errorf("void at carrier: %w", err)
	}
	at := s.now().UTC()
	if err := s.labels.MarkVoided(context.WithoutCancel(ctx), id, at); err != nil {
		return LabelView{}, fmt.Errorf("mark voided: %w", err)
	}
	rec.Status, rec.VoidedAt = domain.LabelVoided, &at
	return LabelView{LabelRecord: rec}, nil
}
