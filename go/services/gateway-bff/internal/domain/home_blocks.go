package domain

// Home block types. Paid modules are always Sponsored; the rest are organic and rule based.
const (
	BlockRail              = "rail"
	BlockBanner            = "banner"
	BlockBrandBanner       = "brand-banner"
	BlockSponsoredProducts = "sponsored-products"
	BlockAdRow             = "ad-row"
	BlockAdvertorial       = "advertorial"
	BlockDeals             = "deals"
	BlockBasedOnOrders     = "based-on-orders"
	BlockRecentlyViewed    = "recently-viewed"
	BlockPreorders         = "preorders"
	BlockServices          = "services-near-you"
	BlockCommunity         = "community-spotlight"
	BlockSeller            = "seller-spotlight"
	BlockMembership        = "membership-promo"
	BlockSignIn            = "signin-promo"
	BlockOccasions         = "occasions"
)

// HomeBlock is one module of the home page body. Type decides which fields are filled; a client ignores a Type it
// does not know. Paid modules carry Sponsored=true and the Advertiser, and are the only ones with ad counting (the
// placement ID is what impressions and clicks are reported against).
type HomeBlock struct {
	ID         string  `json:"id"`
	Type       string  `json:"type"`
	Sponsored  bool    `json:"sponsored"`
	Advertiser string  `json:"advertiser,omitempty"`
	Title      string  `json:"title,omitempty"`
	Sub        string  `json:"sub,omitempty"`
	Body       string  `json:"body,omitempty"`
	CTA        string  `json:"cta,omitempty"`
	Href       string  `json:"href,omitempty"`
	ImageURL   *string `json:"imageUrl,omitempty"`
	LogoURL    *string `json:"logoUrl,omitempty"`
	Tone       string  `json:"tone,omitempty"`
	// Rail holds the products of rail-like modules (rails, deals, sponsored products, spotlights).
	Rail *Rail `json:"rail,omitempty"`
	// Products is the optional strip of a brand banner.
	Products []ProductSummary `json:"products,omitempty"`
	Ads      []BlockAd        `json:"ads,omitempty"`
	Cells    []BlockCell      `json:"cells,omitempty"`
	// EndsAt (RFC 3339) is when a deals module ends; the storefront counts down to it.
	EndsAt string `json:"endsAt,omitempty"`
	// SearchID is the search the sponsored products came from; clicks on them are reported with it and the product's ad token.
	SearchID string `json:"searchId,omitempty"`
	// MaxPerSession caps how many times one browser session shows a paid module (0 = no cap).
	MaxPerSession int `json:"maxPerSession,omitempty"`
}

// BlockAd is one image ad of an ad row.
type BlockAd struct {
	ImageURL   string `json:"imageUrl"`
	Alt        string `json:"alt"`
	Href       string `json:"href"`
	Advertiser string `json:"advertiser"`
}

// BlockCell is a linked cell of a spotlight or occasion module.
type BlockCell struct {
	Label    string  `json:"label"`
	Href     string  `json:"href"`
	ImageURL *string `json:"imageUrl"`
	Sub      string  `json:"sub,omitempty"`
}
