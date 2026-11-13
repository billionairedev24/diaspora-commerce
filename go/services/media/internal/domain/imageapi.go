package domain

// ImageOptions is what a client says about an image when it uploads or imports it.
type ImageOptions struct {
	// Slot is the slot the image is for; the zero value is OTHER (the least strict).
	Slot ImageSlot
	// Hint is the title, brand and product type the AI hook may compare the picture with.
	Hint ModerationHint
}

// VerifyItem names an image and the slot it is to be judged for: by its public URL, or by its asset id (a held
// image has no public URL yet; the id is how a listing refers to it). With an asset id, OwnerOrg (when given) must
// be the organisation that uploaded it, so a seller cannot attach another seller's image.
type VerifyItem struct {
	URL             string
	AssetID         string
	OwnerOrg        string
	Slot            ImageSlot
	ProductTypeCode string
}

// Asset states a verify answer reports. HELD is an image kept private until staff review it (it will become READY or
// REJECTED); UNKNOWN is an image media cannot vouch for (no such asset, someone else's, or not a product image).
const (
	AssetReady    = "READY"
	AssetHeld     = "HELD"
	AssetRejected = "REJECTED"
	AssetUnknown  = "UNKNOWN"
)

// VerifyResult is the verdict of one image for one slot, from what media stored about it. Found is false for a URL
// media does not know as a published image; such an image is a REJECT with NOT_VERIFIED, so a caller that only reads
// the verdict is safe by default.
type VerifyResult struct {
	URL string `json:"url"`
	// AssetID and Status are set when the image was asked for by asset id: Status is READY (URL is its public URL),
	// HELD (no URL yet, Verdict HOLD), REJECTED or UNKNOWN.
	AssetID     string       `json:"assetId,omitempty"`
	Status      string       `json:"status,omitempty"`
	Found       bool         `json:"found"`
	Slot        ImageSlot    `json:"slot"`
	Verdict     ImageVerdict `json:"verdict"`
	Checks      []Check      `json:"checks"`
	Width       int          `json:"width,omitempty"`
	Height      int          `json:"height,omitempty"`
	Bytes       int64        `json:"bytes,omitempty"`
	ContentType string       `json:"contentType,omitempty"`
	// Measured is true when the pixels were analysed (false for images stored before the checks existed, which are
	// judged on their size only until they are rescored).
	Measured bool `json:"measured"`
	// Shadow is true when the image was published although its quality checks rejected it (shadow mode).
	Shadow bool `json:"shadow,omitempty"`
}

// ImageRulesView is the rules in force and what staff overrode.
type ImageRulesView struct {
	Effective ImageRules       `json:"effective"`
	Override  StoredImageRules `json:"override"`
}

// Codes added by the verify and review paths.
const (
	// CodeMainProps rejects, as a main image, a picture that was accepted as an additional one and shows props or a
	// person.
	CodeMainProps = "MAIN_PROPS"
	// CodeReviewRejected is the finding on an image staff did not approve.
	CodeReviewRejected = "REVIEW_REJECTED"
)
