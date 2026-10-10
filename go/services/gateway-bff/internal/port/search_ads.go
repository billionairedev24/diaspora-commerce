package port

import "context"

// SearchSponsored marks a sponsored search hit (search.yaml Sponsored, S240).
type SearchSponsored struct {
	Label      string         `json:"label"`
	Advertiser string         `json:"advertiser"`
	CampaignID string         `json:"campaignId"`
	Slot       int            `json:"slot"`
	Token      string         `json:"token"`
	Reason     SearchAdReason `json:"reason"`
}

// SearchAdReason says why an ad was shown (never bids or prices).
type SearchAdReason struct {
	Targeting    string   `json:"targeting"`
	Matched      string   `json:"matched"`
	QualityScore float64  `json:"qualityScore"`
	AuctionRank  int      `json:"auctionRank"`
	Competitors  int      `json:"competitors"`
	Eligibility  []string `json:"eligibility"`
	Summary      string   `json:"summary"`
}

// SearchClick is a result click reported to search (POST /v1/search/clicks).
type SearchClick struct {
	SearchID string `json:"searchId"`
	Q        string `json:"q,omitempty"`
	Market   string `json:"market,omitempty"`
	ATIN     string `json:"atin"`
	Position int    `json:"position"`
	AdToken  string `json:"adToken,omitempty"`
}

// SearchClickResult is search's answer to a click report.
type SearchClickResult struct {
	Recorded  bool   `json:"recorded"`
	Duplicate bool   `json:"duplicate"`
	Sponsored bool   `json:"sponsored"`
	AdStatus  string `json:"adStatus,omitempty"`
}

// SearchClicksClient reports result clicks (organic and sponsored) to the search service.
type SearchClicksClient interface {
	RecordClick(ctx context.Context, c SearchClick) (SearchClickResult, error)
}
