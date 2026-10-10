// Package app holds carrier-gateway's use cases: rate shopping, labels, tracking ingestion and polling, the
// outbox dispatcher, carrier pickups, pickup locations and carrier-side address validation.
package app

import (
	"context"
	"errors"
	"fmt"
	"log/slog"
	"slices"
	"sort"
	"time"

	"github.com/billionairedev24/diaspora-commerce/go/services/carrier-gateway/internal/domain"
	"github.com/billionairedev24/diaspora-commerce/go/services/carrier-gateway/internal/port"
)

// Carriers is the registry of enabled carrier adapters.
type Carriers map[domain.CarrierCode]port.Carrier

func (c Carriers) get(code domain.CarrierCode) (port.Carrier, error) {
	carrier, ok := c[code]
	if !ok {
		return nil, fmt.Errorf("%w: %s", domain.ErrCarrierDisabled, code)
	}
	return carrier, nil
}

// RateRequest asks for quotes from every enabled carrier with an account for the origin.
type RateRequest struct {
	CartID   string               `json:"cartId,omitempty"`
	Shipment domain.Shipment      `json:"shipment"`
	Carriers []domain.CarrierCode `json:"carriers,omitempty"` // optional filter
}

// CarrierOutcome reports how one carrier answered.
type CarrierOutcome struct {
	Carrier domain.CarrierCode `json:"carrier"`
	Status  string             `json:"status"` // ok | timeout | error
	Error   string             `json:"error,omitempty"`
	Quotes  int                `json:"quotes"`
}

// Outcome statuses.
const (
	OutcomeOK      = "ok"
	OutcomeTimeout = "timeout"
	OutcomeError   = "error"
)

// RateResult is the rate-shopping answer. Partial is true when a carrier timed out or failed; partial results
// are not cached so the next request can complete them.
type RateResult struct {
	Fingerprint string           `json:"fingerprint"`
	Quotes      []domain.Quote   `json:"quotes"`
	Partial     bool             `json:"partial"`
	Cached      bool             `json:"cached"`
	Carriers    []CarrierOutcome `json:"carriers"`
}

// RateService fans out rate requests.
type RateService interface {
	Shop(ctx context.Context, req RateRequest) (RateResult, error)
}

type rateServiceImpl struct {
	carriers Carriers
	accounts port.AccountResolver
	cache    port.QuoteCache
	budget   time.Duration
	ttl      time.Duration
	log      *slog.Logger
}

var _ RateService = (*rateServiceImpl)(nil)

// NewRateService returns a RateService with an overall budget (1.5 s) and cache TTL (10 min).
func NewRateService(carriers Carriers, accounts port.AccountResolver, cache port.QuoteCache, budget, ttl time.Duration, log *slog.Logger) RateService {
	return &rateServiceImpl{carriers: carriers, accounts: accounts, cache: cache, budget: budget, ttl: ttl, log: log}
}

type rateAnswer struct {
	carrier domain.CarrierCode
	quotes  []domain.Quote
	err     error
}

func (s *rateServiceImpl) Shop(ctx context.Context, req RateRequest) (RateResult, error) {
	if err := req.Shipment.Validate(); err != nil {
		return RateResult{}, fmt.Errorf("shipment: %w", err)
	}
	accounts, err := s.accounts.Accounts(ctx, req.Shipment.OriginNode)
	if err != nil {
		return RateResult{}, fmt.Errorf("carrier accounts: %w", err)
	}
	accounts = slices.DeleteFunc(accounts, func(a domain.Account) bool {
		_, enabled := s.carriers[a.Carrier]
		return !enabled || (len(req.Carriers) > 0 && !slices.Contains(req.Carriers, a.Carrier))
	})
	if len(accounts) == 0 {
		return RateResult{}, fmt.Errorf("%w: no carrier account for origin %q", domain.ErrCarrierDisabled, req.Shipment.OriginNode)
	}
	key := cacheKey(req, accounts)
	if quotes, ok, err := s.cache.Get(ctx, key); err != nil {
		s.log.WarnContext(ctx, "quote cache read failed", "error", err)
	} else if ok {
		return RateResult{Fingerprint: req.Shipment.Fingerprint(req.CartID), Quotes: quotes, Cached: true, Carriers: []CarrierOutcome{}}, nil
	}

	budgetCtx, cancel := context.WithTimeout(ctx, s.budget)
	defer cancel()
	answers := make(chan rateAnswer, len(accounts))
	for _, acct := range accounts {
		carrier := s.carriers[acct.Carrier]
		shipment := req.Shipment
		shipment.Account = acct
		go func() {
			quotes, err := carrier.Rate(budgetCtx, shipment)
			for i := range quotes {
				quotes[i] = quotes[i].Adjust(acct.AdjustmentPct)
			}
			answers <- rateAnswer{carrier: acct.Carrier, quotes: quotes, err: err}
		}()
	}

	res := RateResult{Fingerprint: req.Shipment.Fingerprint(req.CartID), Quotes: []domain.Quote{}}
	pending := map[domain.CarrierCode]bool{}
	for _, a := range accounts {
		pending[a.Carrier] = true
	}
collect:
	for len(pending) > 0 {
		select {
		case a := <-answers:
			delete(pending, a.carrier)
			out := CarrierOutcome{Carrier: a.carrier, Status: OutcomeOK, Quotes: len(a.quotes)}
			if a.err != nil {
				out.Status, out.Error = OutcomeError, a.err.Error()
				if errors.Is(a.err, context.DeadlineExceeded) || budgetCtx.Err() != nil {
					out.Status = OutcomeTimeout
				}
				res.Partial = true
				s.log.WarnContext(ctx, "carrier rate failed", "carrier", a.carrier, "error", a.err)
			}
			res.Quotes = append(res.Quotes, a.quotes...)
			res.Carriers = append(res.Carriers, out)
		case <-budgetCtx.Done():
			break collect
		}
	}
	for _, a := range accounts {
		if pending[a.Carrier] {
			res.Partial = true
			res.Carriers = append(res.Carriers, CarrierOutcome{Carrier: a.Carrier, Status: OutcomeTimeout})
		}
	}
	sort.SliceStable(res.Quotes, func(i, j int) bool { return res.Quotes[i].AmountMinor < res.Quotes[j].AmountMinor })
	sort.SliceStable(res.Carriers, func(i, j int) bool { return res.Carriers[i].Carrier < res.Carriers[j].Carrier })
	if !res.Partial && len(res.Quotes) > 0 {
		putCtx, cancelPut := context.WithTimeout(context.WithoutCancel(ctx), 250*time.Millisecond)
		defer cancelPut()
		if err := s.cache.Put(putCtx, key, res.Quotes, s.ttl); err != nil {
			s.log.WarnContext(ctx, "quote cache write failed", "error", err)
		}
	}
	return res, nil
}

func cacheKey(req RateRequest, accounts []domain.Account) string {
	carriers := make([]string, 0, len(accounts))
	for _, a := range accounts {
		carriers = append(carriers, fmt.Sprintf("%s:%s:%t:%g", a.Carrier, a.Number, a.Negotiated, a.AdjustmentPct))
	}
	sort.Strings(carriers)
	return "rates:" + req.Shipment.Fingerprint(req.CartID) + ":" + domain.EventKey(carriers...)
}
