package app

import (
	"context"
	"errors"
	"fmt"
	"log/slog"
	"time"

	"github.com/billionairedev24/diaspora-commerce/go/services/search/internal/domain"
	"github.com/billionairedev24/diaspora-commerce/go/services/search/internal/port"
)

// ClickRecorder records result clicks reported by the BFF: search.result.clicked for every first click on a result,
// and ads.click.recorded for a sponsored result whose signed token is valid, unexpired and not seen before.
type ClickRecorder interface {
	Record(ctx context.Context, c domain.Click) (domain.ClickResult, error)
}

// ErrInvalidAdToken is returned when an ad token is forged or does not belong to the clicked result.
var ErrInvalidAdToken = errors.New("invalid ad token")

// Ad click outcomes.
const (
	AdBillable  = "BILLABLE"
	AdDuplicate = "DUPLICATE"
	AdExpired   = "EXPIRED"
)

type clickRecorderImpl struct {
	tokens    port.AdTokens
	events    port.EventPublisher
	analytics AnalyticsRecorder
	seen      *dedupeSet
	log       *slog.Logger
	now       func() time.Time
}

var _ ClickRecorder = (*clickRecorderImpl)(nil)

// NewClickRecorder returns the click use case. Clicks are de-duplicated per replica for dedupeWindow (same search and
// product, same ad token); every event also carries a deterministic event-id so consumers' inboxes drop the rest.
func NewClickRecorder(tokens port.AdTokens, events port.EventPublisher, analytics AnalyticsRecorder,
	dedupeWindow time.Duration, log *slog.Logger,
) ClickRecorder {
	return &clickRecorderImpl{tokens: tokens, events: events, analytics: analytics,
		seen: newDedupeSet(dedupeWindow, 200_000), log: log, now: time.Now}
}

func (r *clickRecorderImpl) Record(ctx context.Context, c domain.Click) (domain.ClickResult, error) {
	c, err := c.Normalize()
	if err != nil {
		return domain.ClickResult{}, fmt.Errorf("click: %w", err)
	}
	now := r.now().UTC()
	var (
		res domain.ClickResult
		tok domain.AdToken
	)
	if c.AdToken != "" {
		if r.tokens == nil {
			return domain.ClickResult{}, ErrInvalidAdToken
		}
		tok, err = r.tokens.Verify(c.AdToken)
		if err != nil || tok.ATIN != c.ATIN || tok.SearchID != c.SearchID {
			return domain.ClickResult{}, errors.Join(ErrInvalidAdToken, err)
		}
		// The signed token, not the request body, says which query and market the ad was shown for.
		c.Query, c.Market = tok.Query, tok.Market
		res.Sponsored = true
		res.AdStatus = r.adClick(ctx, c, tok, now)
	}
	if !r.seen.first("click\x1f"+c.SearchID+"\x1f"+c.ATIN, now) {
		res.Duplicate = true
		return res, nil
	}
	res.Recorded = true
	if r.analytics != nil {
		r.analytics.Clicked(c.Market, c.Query, now)
	}
	id := stableID(domain.EventResultClicked, c.SearchID, c.ATIN)
	e, err := envelope(id, domain.EventResultClicked, domain.AggregateSearch, c.SearchID, now, domain.ResultClicked{
		SearchID: c.SearchID, Market: c.Market, Query: c.Query, Normalized: domain.AnalyticsKey(c.Query), ATIN: c.ATIN,
		Position: c.Position, Sponsored: res.Sponsored, CampaignID: tok.CampaignID, CustomerID: c.CustomerID,
		OccurredAt: now,
	})
	if err != nil {
		return res, fmt.Errorf("click event: %w", err)
	}
	r.events.Publish(ctx, e)
	return res, nil
}

// adClick publishes ads.click.recorded once per token while it is valid.
func (r *clickRecorderImpl) adClick(ctx context.Context, c domain.Click, tok domain.AdToken, now time.Time) string {
	if now.Unix() > tok.ExpiresAt {
		return AdExpired
	}
	key := digest(c.AdToken)
	if !r.seen.first("ad\x1f"+key, now) {
		return AdDuplicate
	}
	id := stableID(domain.EventAdClick, key)
	e, err := envelope(id, domain.EventAdClick, domain.AggregateAds, tok.CampaignID, now, domain.AdClick{
		ClickID: id, CampaignID: tok.CampaignID, SellerOrgID: tok.SellerOrgID, ATIN: tok.ATIN,
		VariantATINs: nonNil(tok.VariantATINs), SearchID: tok.SearchID, Market: tok.Market, Query: tok.Query,
		Slot: tok.Slot, PriceMinor: tok.PriceMinor, Currency: tok.Currency, CustomerID: c.CustomerID,
		OccurredAt: now.Format(time.RFC3339Nano),
	})
	if err != nil {
		r.log.WarnContext(ctx, "ad click not recorded", "error", err)
		return AdDuplicate
	}
	r.events.Publish(ctx, e)
	return AdBillable
}

func nonNil(s []string) []string {
	if s == nil {
		return []string{}
	}
	return s
}
