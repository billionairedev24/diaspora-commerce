package domain

import "time"

// Announcement is the bar above the header.
type Announcement struct {
	ID   string `json:"id"`
	Tone string `json:"tone"`
	Icon string `json:"icon"`
	Text string `json:"text"`
	CTA  string `json:"cta"`
	Href string `json:"href"`
}

// HeroSlide is one hero carousel slide.
type HeroSlide struct {
	ID       string  `json:"id"`
	Theme    string  `json:"theme"`
	Kicker   string  `json:"kicker"`
	Title    string  `json:"title"`
	Sub      string  `json:"sub"`
	CTA      string  `json:"cta"`
	Href     string  `json:"href"`
	Label    string  `json:"label"`
	ImageURL *string `json:"imageUrl"`
	// StartsAt and EndsAt (RFC 3339, both optional) bound when the slide is shown; the BFF drops slides outside the window.
	StartsAt *string `json:"startsAt,omitempty"`
	EndsAt   *string `json:"endsAt,omitempty"`
}

// TileCell is one cell of a home tile: a fixed image and link, or a rule (Source) the BFF resolves to a product, which
// supplies the image, title and link. Source never reaches the storefront; ATIN says which product a rule picked.
type TileCell struct {
	Label    string      `json:"label"`
	Href     string      `json:"href"`
	ImageURL *string     `json:"imageUrl"`
	Source   *RailSource `json:"source,omitempty"`
	ATIN     string      `json:"atin,omitempty"`
}

// Tile is a home grid tile with up to four cells.
type Tile struct {
	ID    string     `json:"id"`
	Title string     `json:"title"`
	CTA   string     `json:"cta"`
	Href  string     `json:"href"`
	Cells []TileCell `json:"cells"`
}

// Banner is a sponsored banner.
type Banner struct {
	ID         string  `json:"id"`
	Slot       string  `json:"slot"`
	Advertiser string  `json:"advertiser"`
	Title      string  `json:"title"`
	Sub        string  `json:"sub"`
	CTA        string  `json:"cta"`
	Href       string  `json:"href"`
	ImageURL   *string `json:"imageUrl"`
}

// Rail is a product row on the home page.
type Rail struct {
	ID    string           `json:"id"`
	Title string           `json:"title"`
	Href  string           `json:"href"`
	Items []ProductSummary `json:"items"`
}

// Home is the home page.
type Home struct {
	Announcements []Announcement `json:"announcements"`
	Hero          []HeroSlide    `json:"hero"`
	Tiles         []Tile         `json:"tiles"`
	// Blocks is the composed page body under the tiles, in the order staff set: product rails, sponsored modules and
	// the rule-based modules. The storefront renders by Type and ignores types it does not know.
	Blocks []HomeBlock `json:"blocks"`
	// Banners and Rails are the same blocks seen as the older two lists; kept for tests and internal callers only.
	Banners   []Banner         `json:"-"`
	Rails     []Rail           `json:"-"`
	DeliverTo DeliveryLocation `json:"deliverTo"`
	// Dynamic home (version 2); absent from the legacy composition.
	Version     int        `json:"version,omitempty"`
	GeneratedAt string     `json:"generatedAt,omitempty"`
	Locale      string     `json:"locale,omitempty"`
	Variant     string     `json:"variant,omitempty"`
	Visit       *HomeVisit `json:"visit,omitempty"`
	Debug       *HomeDebug `json:"debug,omitempty"`
}

// HomeVisit describes the visit the page was composed for.
type HomeVisit struct {
	Window        string   `json:"window"`
	Seen          []string `json:"seen"`
	Deterministic bool     `json:"deterministic,omitempty"`
}

// HomeDebug is what staff see in debug mode: the seed and why each module is shown or hidden. Never sent to shoppers.
type HomeDebug struct {
	Seed          string           `json:"seed"`
	Visitor       string           `json:"visitor,omitempty"`
	VisitWindow   string           `json:"visitWindow,omitempty"`
	NoveltyMemory int              `json:"noveltyMemory"`
	Dynamic       string           `json:"dynamic"`
	Decisions     []ModuleDecision `json:"decisions"`
}

// RailSource says how a placement rail (or a rule-based tile cell) is filled: a search (query/department/fulfilment,
// sorted), or explicit ATINs.
type RailSource struct {
	// Kind is search | department-bestsellers | fulfilled-fastest | atins, or one that reads the shopper's deliver-to
	// location: near-you (ships from the shopper's city, state or region), deliverable-fast (promised within MaxDays
	// to the shopper's ZIP) and community (a community's products).
	Kind       string   `json:"kind"`
	Query      string   `json:"query,omitempty"`
	Department string   `json:"department,omitempty"`
	Sort       string   `json:"sort,omitempty"`
	ATINs      []string `json:"atins,omitempty"`
	Limit      int      `json:"limit,omitempty"`
	// MaxDays is the longest delivery promise a deliverable-fast source accepts (default 2).
	MaxDays int `json:"maxDays,omitempty"`
	// Community is the community key a community source filters by (nigerian, ghanaian, ...).
	Community string `json:"community,omitempty"`
}

// RailPlacement is a rail as content publishes it, before products are resolved.
type RailPlacement struct {
	ID     string     `json:"id"`
	Title  string     `json:"title"` // may contain {city}
	Href   string     `json:"href"`
	Source RailSource `json:"source"`
	// MinItems hides the rail when fewer products qualify (default MinRailItems).
	MinItems int `json:"-"`
}

// Placements is what content resolves for a market/ZIP.
type Placements struct {
	Announcements     []Announcement  `json:"announcements"`
	Hero              []HeroSlide     `json:"hero"`
	Tiles             []Tile          `json:"tiles"`
	Banners           []Banner        `json:"banners"`
	Rails             []RailPlacement `json:"rails"`
	CheckoutCrossSell []RailPlacement `json:"-"`
	// Body lists every body placement (rails, banners, sponsored modules, blocks) with its priority, in content's
	// order. When empty the page body is built from Banners and Rails as before.
	Body []BodyPlacement `json:"-"`
}

// BodyPlacement is one placement of the page body as content serves it. Payload is the slot's JSON.
type BodyPlacement struct {
	ID         string
	Slot       string
	Priority   int
	Advertiser string
	Payload    []byte
}

// SiteConfig is published site configuration, passed through as content serves it (contract schema SiteConfig).
type SiteConfig struct {
	Body         []byte
	ETag         string
	CacheControl string
}

// Job is an open role.
type Job struct {
	ID               string   `json:"id"`
	Title            string   `json:"title"`
	Team             string   `json:"team"`
	Location         string   `json:"location"`
	Type             string   `json:"type"`
	Pay              string   `json:"pay"`
	Summary          string   `json:"summary"`
	Responsibilities []string `json:"responsibilities"`
	Requirements     []string `json:"requirements"`
}

// JobApplication is a careers application (résumé uploaded through media beforehand).
type JobApplication struct {
	Name              string `json:"name"`
	Email             string `json:"email"`
	LinkedIn          string `json:"linkedin"`
	WorkAuthorization string `json:"workAuthorization"`
	// ResumeAssetID is the media-service asset id of the uploaded résumé (content requires it).
	ResumeAssetID string `json:"resumeAssetId,omitempty"`
	// ResumeFileName is the original file name, for display.
	ResumeFileName string `json:"resumeFileName,omitempty"`
	CoverNote      string `json:"coverNote,omitempty"`
	Consent        bool   `json:"consent"`
}

// ApplicationReceipt acknowledges an application.
type ApplicationReceipt struct {
	Reference string `json:"reference"`
	JobID     string `json:"jobId"`
}

// MarketEligibility says whether AfroTransact delivers to a place (content: GET /v1/markets/eligibility).
type MarketEligibility struct {
	DeliveryAllowed bool   `json:"deliveryAllowed"`
	CountryStatus   string `json:"countryStatus"`
	Reason          string `json:"reason"`
	Message         string `json:"message"`
}

// Résumé upload limits (careers form): PDF only, at most 5 MB.
const (
	ResumeContentType = "application/pdf"
	ResumeMaxBytes    = 5 << 20
)

// ResumeUpload is a signed direct upload for a résumé (PUT the file to Upload.URL with Upload.Headers).
type ResumeUpload struct {
	AssetID  string          `json:"assetId"`
	Upload   ResumeUploadURL `json:"upload"`
	MaxBytes int64           `json:"maxBytes"`
}

// ResumeUploadURL is where and how to send the file.
type ResumeUploadURL struct {
	Method    string            `json:"method"`
	URL       string            `json:"url"`
	Headers   map[string]string `json:"headers"`
	ExpiresAt *time.Time        `json:"expiresAt"`
}

// ResumeAsset is a finalised résumé: pass AssetID as resumeAssetId when applying.
type ResumeAsset struct {
	AssetID     string `json:"assetId"`
	ContentType string `json:"contentType"`
	Bytes       int64  `json:"bytes"`
	Status      string `json:"status"`
}
