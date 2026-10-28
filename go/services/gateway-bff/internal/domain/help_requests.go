package domain

// Help requests: the customer side of disputes. A delivered order has no self-service refund or return; the customer
// describes the problem with photos and staff resolve it in the claims tooling (trust-ops).

// Help request limits.
const (
	HelpEvidenceMaxBytes  = 10 << 20
	HelpEvidenceMaxPerReq = 5
	HelpDescriptionMin    = 10
	HelpDescriptionMax    = 4000
)

// HelpEvidenceContentTypes are the photo types a help request accepts.
var HelpEvidenceContentTypes = []string{"image/jpeg", "image/png", "image/webp", "image/heic"}

// HelpRequestTypes are what the customer can report (trust-ops claim types without the booking-only one).
var HelpRequestTypes = []string{"ITEM_NOT_RECEIVED", "DAMAGED_ITEM", "WRONG_ITEM", "NOT_AS_DESCRIBED", "OTHER"}

// Help request statuses in the customer's words.
const (
	HelpReceived  = "RECEIVED"
	HelpInReview  = "IN_REVIEW"
	HelpResolved  = "RESOLVED"
	HelpClosed    = "CLOSED"
	HelpWithdrawn = "WITHDRAWN"
)

// HelpEvidenceUpload is a signed direct upload for one evidence photo (PUT the file to Upload.URL with Upload.Headers).
type HelpEvidenceUpload struct {
	AssetID  string          `json:"assetId"`
	Upload   ResumeUploadURL `json:"upload"`
	MaxBytes int64           `json:"maxBytes"`
}

// HelpEvidenceAsset is a finalised evidence photo: pass AssetID in evidenceAssetIds.
type HelpEvidenceAsset struct {
	AssetID     string `json:"assetId"`
	ContentType string `json:"contentType"`
	Bytes       int64  `json:"bytes"`
	Status      string `json:"status"`
}

// CreateHelpRequest is POST /v1/me/help-requests.
type CreateHelpRequest struct {
	OrderNumber      string   `json:"orderNumber"`
	ShipmentID       string   `json:"shipmentId,omitempty"`
	Type             string   `json:"type"`
	Description      string   `json:"description"`
	EvidenceAssetIDs []string `json:"evidenceAssetIds"`
}

// HelpResolution is how staff settled a request. Refunds go back to the payers; the customer sees the amount only.
type HelpResolution struct {
	Outcome     string  `json:"outcome"` // REFUNDED or NOT_REFUNDED
	Note        string  `json:"note"`
	RefundMinor *int64  `json:"refundMinor"`
	DecidedAt   *string `json:"decidedAt"`
}

// HelpRequest is one request in the customer's words: no internal ids, no staff or seller identities.
type HelpRequest struct {
	ID            string          `json:"id"`
	Reference     string          `json:"reference"`
	OrderNumber   string          `json:"orderNumber"`
	Type          string          `json:"type"`
	Status        string          `json:"status"`
	StatusLabel   string          `json:"statusLabel"`
	Description   string          `json:"description"`
	EvidenceCount int             `json:"evidenceCount"`
	ClaimedMinor  int64           `json:"claimedMinor"`
	Currency      string          `json:"currency"`
	Resolution    *HelpResolution `json:"resolution"`
	CreatedAt     string          `json:"createdAt"`
	UpdatedAt     string          `json:"updatedAt"`
}
