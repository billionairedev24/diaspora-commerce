package domain

// SponsorReason says why a sponsored product was shown (targeting, relevance, auction rank, eligibility; never bids).
type SponsorReason struct {
	Targeting    string   `json:"targeting"`
	Matched      string   `json:"matched"`
	QualityScore float64  `json:"qualityScore"`
	AuctionRank  int      `json:"auctionRank"`
	Competitors  int      `json:"competitors"`
	Eligibility  []string `json:"eligibility"`
	Summary      string   `json:"summary"`
}

// SearchClick is a shopper's click on a search result (organic or sponsored).
type SearchClick struct {
	SearchID string `json:"searchId"`
	Q        string `json:"q"`
	ATIN     string `json:"atin"`
	Position int    `json:"position"`
	AdToken  string `json:"adToken"`
}

// SearchClickResult is the outcome of a click report.
type SearchClickResult struct {
	Recorded  bool   `json:"recorded"`
	Duplicate bool   `json:"duplicate"`
	Sponsored bool   `json:"sponsored"`
	AdStatus  string `json:"adStatus,omitempty"`
}
