package app

import (
	"context"
	"log/slog"
	"sort"
	"strings"
	"time"

	"github.com/billionairedev24/diaspora-commerce/go/services/search/internal/domain"
	"github.com/billionairedev24/diaspora-commerce/go/services/search/internal/port"
)

// AdServer blends sponsored products into a results page (S127).
type AdServer interface {
	// Inject places auction winners at the configured slots, labelled "Sponsored" with the reason they were shown,
	// and records an impression per ad. It fails open: any error leaves the organic page unchanged.
	Inject(ctx context.Context, q domain.Query, page domain.Page) domain.Page
}

// AdSettings are the published ad rules.
type AdSettings struct {
	// Slots are the 1-based result positions ads may take, e.g. [1, 5].
	Slots []int
	Rules domain.AuctionRules
	// TokenTTL bounds how long after the search a click is billable.
	TokenTTL time.Duration
	// MaxCandidates caps the advertised products scored per search.
	MaxCandidates int
}

type adServerImpl struct {
	campaigns port.CampaignSource
	index     port.Index
	tokens    port.AdTokens
	events    port.EventPublisher
	settings  AdSettings
	log       *slog.Logger
	now       func() time.Time
}

var _ AdServer = (*adServerImpl)(nil)

// NewAdServer returns the sponsored-products blender.
func NewAdServer(campaigns port.CampaignSource, index port.Index, tokens port.AdTokens, events port.EventPublisher,
	settings AdSettings, log *slog.Logger,
) AdServer {
	slots := append([]int(nil), settings.Slots...)
	sort.Ints(slots)
	settings.Slots = slots
	if settings.MaxCandidates <= 0 {
		settings.MaxCandidates = 100
	}
	if settings.TokenTTL <= 0 {
		settings.TokenTTL = time.Hour
	}
	return &adServerImpl{campaigns: campaigns, index: index, tokens: tokens, events: events, settings: settings,
		log: log, now: time.Now}
}

type advertised struct {
	campaign domain.Campaign
	match    domain.TargetMatch
}

func (a *adServerImpl) Inject(ctx context.Context, q domain.Query, page domain.Page) domain.Page {
	if len(page.Hits) == 0 || len(a.settings.Slots) == 0 {
		return page
	}
	camps, err := a.campaigns.Eligible(ctx, q.Market)
	if err != nil {
		a.log.WarnContext(ctx, "campaigns unavailable; serving organic results only", "error", err)
		return page
	}
	byATIN, atins := a.targeted(camps, q, page)
	if len(atins) == 0 {
		return page
	}
	hits, err := a.index.AdCandidates(ctx, q, atins)
	if err != nil {
		a.log.WarnContext(ctx, "ad candidates unavailable; serving organic results only", "error", err)
		return page
	}
	top := page.Hits[0].Score
	cands := make([]domain.AdCandidate, 0, len(hits))
	for _, h := range hits {
		quality := 1.0
		if top > 0 {
			quality = min(1, h.Score/top)
		}
		for _, ad := range byATIN[h.ATIN] {
			// Buy-box eligible: the advertiser must own the product's buy-box offer.
			if ad.campaign.SellerOrgID != h.SellerID {
				continue
			}
			cands = append(cands, domain.AdCandidate{Campaign: ad.campaign, Hit: h, Match: ad.match, Quality: quality})
		}
	}
	winners := domain.RunAuction(cands, len(a.settings.Slots), a.settings.Rules)
	if len(winners) == 0 {
		return page
	}
	return a.place(ctx, q, page, winners)
}

// targeted returns, per advertised ATIN, the campaigns whose keywords or categories match the search.
func (a *adServerImpl) targeted(camps []domain.Campaign, q domain.Query, page domain.Page) (map[string][]advertised, []string) {
	categories := make([]string, 0, 7)
	if q.Department != "" {
		categories = append(categories, q.Department)
	}
	for i := 0; i < len(page.Hits) && i < 3; i++ {
		categories = append(categories, page.Hits[i].CategoryPath, page.Hits[i].Department)
	}
	byATIN := map[string][]advertised{}
	atins := make([]string, 0, 16)
	for _, c := range camps {
		if c.BidMinor <= 0 || c.RemainingMinor <= 0 {
			continue
		}
		m, ok := domain.MatchCampaign(c, q.Text, categories)
		if !ok {
			continue
		}
		for _, atin := range c.ATINs {
			if _, seen := byATIN[atin]; !seen {
				if len(atins) >= a.settings.MaxCandidates {
					continue
				}
				atins = append(atins, atin)
			}
			byATIN[atin] = append(byATIN[atin], advertised{campaign: c, match: m})
		}
	}
	return byATIN, atins
}

func (a *adServerImpl) place(ctx context.Context, q domain.Query, page domain.Page, winners []domain.AuctionWinner) domain.Page {
	now := a.now().UTC()
	sponsoredATIN := map[string]bool{}
	for _, w := range winners {
		sponsoredATIN[w.Candidate.Hit.ATIN] = true
	}
	// The design never shows a product twice: an advertised product leaves its organic position.
	organic := make([]domain.Hit, 0, len(page.Hits))
	for _, h := range page.Hits {
		if !sponsoredATIN[h.ATIN] {
			organic = append(organic, h)
		}
	}
	out := organic
	for i, w := range winners {
		slot := a.settings.Slots[i]
		hit, ok := a.sponsoredHit(q, page.SearchID, w, slot, now)
		if !ok {
			continue
		}
		pos := min(slot-1, len(out))
		out = append(out[:pos], append([]domain.Hit{hit}, out[pos:]...)...)
		a.impression(ctx, q, page.SearchID, w, slot, now)
	}
	page.Hits = out
	return page
}

func (a *adServerImpl) sponsoredHit(q domain.Query, searchID string, w domain.AuctionWinner, slot int, now time.Time) (domain.Hit, bool) {
	c := w.Candidate
	variants := c.Hit.VariantATINs
	if len(variants) > 20 {
		variants = variants[:20]
	}
	token, err := a.tokens.Sign(domain.AdToken{Version: 1, SearchID: searchID, CampaignID: c.Campaign.ID,
		SellerOrgID: c.Campaign.SellerOrgID, ATIN: c.Hit.ATIN, VariantATINs: variants, PriceMinor: w.PriceMinor,
		Currency: strings.ToUpper(c.Campaign.Currency), Market: q.Market, Query: q.Text, Slot: slot,
		ExpiresAt: now.Add(a.settings.TokenTTL).Unix()})
	if err != nil {
		a.log.Warn("ad token signing failed; ad skipped", "campaign", c.Campaign.ID, "error", err)
		return domain.Hit{}, false
	}
	hit := c.Hit
	hit.ApplyStockSignal()
	hit.Sponsored = &domain.Sponsored{Label: domain.SponsoredLabel, Advertiser: c.Campaign.Advertiser,
		CampaignID: c.Campaign.ID, Slot: slot, Token: token, Reason: domain.Explain(w, q.Text)}
	return hit, true
}

func (a *adServerImpl) impression(ctx context.Context, q domain.Query, searchID string, w domain.AuctionWinner, slot int, now time.Time) {
	c := w.Candidate
	id := stableID("ads.impression", searchID, c.Campaign.ID, c.Hit.ATIN)
	e, err := envelope(id, domain.EventAdImpression, domain.AggregateAds, c.Campaign.ID, now, domain.AdImpression{
		ImpressionID: id, CampaignID: c.Campaign.ID, SellerOrgID: c.Campaign.SellerOrgID, ATIN: c.Hit.ATIN,
		SearchID: searchID, Market: q.Market, Query: q.Text, Slot: slot, AuctionRank: w.Rank,
		QualityScore: w.Candidate.Quality, OccurredAt: now.Format(time.RFC3339Nano),
	})
	if err != nil {
		a.log.Warn("impression not recorded", "error", err)
		return
	}
	a.events.Publish(ctx, e)
}
