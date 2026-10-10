package app

import (
	"context"
	"crypto/sha256"
	"encoding/hex"
	"encoding/json"
	"fmt"
	"slices"
	"strings"

	"github.com/billionairedev24/diaspora-commerce/go/services/carrier-gateway/internal/domain"
	"github.com/billionairedev24/diaspora-commerce/go/services/carrier-gateway/internal/port"
)

// ReturnLabelRequest buys a customer return label (tax-returns-service, reference = RMA number).
type ReturnLabelRequest struct {
	IdempotencyKey string `json:"-"`
	Caller         string `json:"-"`
	// Shipment goes from the customer to the seller's or the AfroTransact return centre's address.
	Shipment domain.Shipment `json:"shipment"`
	// RateChoice is CHEAPEST (default), FASTEST, or SERVICE with Carrier + Service (a choice made from /v1/rates).
	RateChoice domain.RateChoice  `json:"rateChoice"`
	Carrier    domain.CarrierCode `json:"carrier,omitempty"`
	Service    domain.ServiceCode `json:"service,omitempty"`
	// Carriers limits rate shopping to these carriers (optional).
	Carriers []domain.CarrierCode `json:"carriers,omitempty"`
	// Mode is LABEL (printed label, default) or BOX_FREE_QR (label-free QR drop-off).
	Mode domain.ReturnMode `json:"mode"`
	// FallbackToLabel buys a printed label when no carrier offers box-free QR drop-off, instead of failing.
	FallbackToLabel bool `json:"fallbackToLabel"`
}

// UnsupportedCarrier says why one carrier cannot serve a request.
type UnsupportedCarrier struct {
	Carrier domain.CarrierCode `json:"carrier"`
	Reason  string             `json:"reason"`
}

// UnsupportedError is returned when no carrier offers a capability; it lists every carrier with its reason and
// unwraps to domain.ErrUnsupported (HTTP 422 carrier-gateway.unsupported).
type UnsupportedError struct {
	Capability string
	Carriers   []UnsupportedCarrier
}

func (e *UnsupportedError) Error() string {
	parts := make([]string, 0, len(e.Carriers))
	for _, c := range e.Carriers {
		parts = append(parts, string(c.Carrier)+": "+c.Reason)
	}
	return fmt.Sprintf("no carrier offers %s (%s)", e.Capability, strings.Join(parts, "; "))
}

// Unwrap exposes the sentinel kind.
func (e *UnsupportedError) Unwrap() error { return domain.ErrUnsupported }

// ReturnLabelView is the bought return label with how it was chosen.
type ReturnLabelView struct {
	LabelView
	Mode       domain.ReturnMode `json:"mode"`
	RateChoice domain.RateChoice `json:"rateChoice"`
	// Quote is the rated service the choice picked (absent for SERVICE choices and replays).
	Quote *domain.Quote `json:"quote,omitempty"`
	// BoxFreeUnsupported lists why each carrier could not do a requested box-free return (fallback to a label).
	BoxFreeUnsupported []UnsupportedCarrier `json:"boxFreeUnsupported,omitempty"`
}

// ReturnLabelService buys return labels with a rate choice, printed or as box-free QR drop-off.
type ReturnLabelService interface {
	Buy(ctx context.Context, req ReturnLabelRequest) (ReturnLabelView, error)
}

type returnLabelServiceImpl struct {
	carriers Carriers
	rates    RateService
	labels   LabelService
}

var _ ReturnLabelService = (*returnLabelServiceImpl)(nil)

// NewReturnLabelService returns a ReturnLabelService.
func NewReturnLabelService(carriers Carriers, rates RateService, labels LabelService) ReturnLabelService {
	return &returnLabelServiceImpl{carriers: carriers, rates: rates, labels: labels}
}

func (s *returnLabelServiceImpl) Buy(ctx context.Context, req ReturnLabelRequest) (ReturnLabelView, error) {
	if strings.TrimSpace(req.IdempotencyKey) == "" {
		return ReturnLabelView{}, fmt.Errorf("%w: Idempotency-Key header is required", domain.ErrInvalid)
	}
	choice, err := domain.ParseRateChoice(string(req.RateChoice))
	if err != nil {
		return ReturnLabelView{}, fmt.Errorf("return label: %w", err)
	}
	mode, err := domain.ParseReturnMode(string(req.Mode))
	if err != nil {
		return ReturnLabelView{}, fmt.Errorf("return label: %w", err)
	}
	if err := req.Shipment.Validate(); err != nil {
		return ReturnLabelView{}, fmt.Errorf("shipment: %w", err)
	}
	if choice == domain.RateService && (req.Carrier == "" || req.Service == "") {
		return ReturnLabelView{}, fmt.Errorf("%w: rateChoice SERVICE needs carrier and service", domain.ErrInvalid)
	}
	req.RateChoice, req.Mode = choice, mode
	hash := returnHash(req)

	candidates := s.candidates(req)
	view := ReturnLabelView{Mode: mode, RateChoice: choice}
	if mode == domain.ReturnBoxFreeQR {
		supported, unsupported := boxFreeSupport(s.carriers, candidates)
		if len(supported) == 0 {
			if !req.FallbackToLabel {
				return ReturnLabelView{}, &UnsupportedError{Capability: "box-free QR drop-off", Carriers: unsupported}
			}
			view.Mode, view.BoxFreeUnsupported = domain.ReturnPrintedLabel, unsupported
		} else {
			candidates = supported
		}
	}
	carrier, service := req.Carrier, req.Service
	if choice != domain.RateService {
		if len(candidates) == 0 {
			return ReturnLabelView{}, fmt.Errorf("%w: no enabled carrier for this return", domain.ErrCarrierDisabled)
		}
		res, err := s.rates.Shop(ctx, RateRequest{Shipment: req.Shipment, Carriers: candidates})
		if err != nil {
			return ReturnLabelView{}, fmt.Errorf("rate return: %w", err)
		}
		q, err := domain.ChooseQuote(res.Quotes, choice)
		if err != nil {
			return ReturnLabelView{}, fmt.Errorf("choose return rate: %w", err)
		}
		carrier, service, view.Quote = q.Carrier, q.Service, &q
	} else if !slices.Contains(candidates, carrier) {
		return ReturnLabelView{}, fmt.Errorf("%w: %s cannot serve this return", domain.ErrUnsupported, carrier)
	}
	label, err := s.labels.Buy(ctx, BuyRequest{IdempotencyKey: req.IdempotencyKey, Caller: req.Caller, Shipment: req.Shipment,
		Carrier: carrier, Service: service, Kind: domain.KindReturn, RateChoice: choice,
		BoxFree: view.Mode == domain.ReturnBoxFreeQR, RequestHash: hash})
	if err != nil {
		return ReturnLabelView{}, fmt.Errorf("buy return label: %w", err)
	}
	view.LabelView = label
	if label.Carrier != carrier || label.Service != service {
		view.Quote = nil // replay of an earlier purchase
	}
	return view, nil
}

// candidates are the enabled carriers the request allows, in a stable order.
func (s *returnLabelServiceImpl) candidates(req ReturnLabelRequest) []domain.CarrierCode {
	var out []domain.CarrierCode
	for _, c := range domain.AllCarriers {
		if _, ok := s.carriers[c]; !ok {
			continue
		}
		if req.RateChoice == domain.RateService && c != req.Carrier {
			continue
		}
		if len(req.Carriers) > 0 && !slices.Contains(req.Carriers, c) {
			continue
		}
		out = append(out, c)
	}
	return out
}

func boxFreeSupport(carriers Carriers, candidates []domain.CarrierCode) ([]domain.CarrierCode, []UnsupportedCarrier) {
	var supported []domain.CarrierCode
	var unsupported []UnsupportedCarrier
	for _, c := range candidates {
		if _, ok := carriers[c].(port.BoxFreeReturner); ok {
			supported = append(supported, c)
			continue
		}
		unsupported = append(unsupported, UnsupportedCarrier{Carrier: c,
			Reason: "box-free QR drop-off is not available through the " + string(c) + " integration"})
	}
	return supported, unsupported
}

func returnHash(req ReturnLabelRequest) string {
	b, _ := json.Marshal(struct {
		S domain.Shipment
		R domain.RateChoice
		C domain.CarrierCode
		V domain.ServiceCode
		L []domain.CarrierCode
		M domain.ReturnMode
		F bool
	}{req.Shipment, req.RateChoice, req.Carrier, req.Service, req.Carriers, req.Mode, req.FallbackToLabel})
	sum := sha256.Sum256(b)
	return "return:" + hex.EncodeToString(sum[:])
}
