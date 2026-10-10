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
}

// TileCell is one cell of a home tile.
type TileCell struct {
	Label    string  `json:"label"`
	Href     string  `json:"href"`
	ImageURL *string `json:"imageUrl"`
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
	Announcements []Announcement   `json:"announcements"`
	Hero          []HeroSlide      `json:"hero"`
	Tiles         []Tile           `json:"tiles"`
	Banners       []Banner         `json:"banners"`
	Rails         []Rail           `json:"rails"`
	DeliverTo     DeliveryLocation `json:"deliverTo"`
}

// RailSource says how a placement rail is filled: a search (query/department/fulfilment, sorted), or explicit ATINs.
type RailSource struct {
	Kind       string   `json:"kind"` // search | department-bestsellers | fulfilled-fastest | atins
	Query      string   `json:"query,omitempty"`
	Department string   `json:"department,omitempty"`
	Sort       string   `json:"sort,omitempty"`
	ATINs      []string `json:"atins,omitempty"`
	Limit      int      `json:"limit,omitempty"`
}

// RailPlacement is a rail as content publishes it, before products are resolved.
type RailPlacement struct {
	ID     string     `json:"id"`
	Title  string     `json:"title"` // may contain {city}
	Href   string     `json:"href"`
	Source RailSource `json:"source"`
}

// Placements is what content resolves for a market/ZIP.
type Placements struct {
	Announcements []Announcement  `json:"announcements"`
	Hero          []HeroSlide     `json:"hero"`
	Tiles         []Tile          `json:"tiles"`
	Banners       []Banner        `json:"banners"`
	Rails         []RailPlacement `json:"rails"`
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
