package domain

// DeliveryProof is how a driver handed a parcel over, as the customer and the seller see it on the order: the way (to the
// customer with their code, to someone else with a signature, or left in a safe place), who or where, and the pictures.
// The driver's GPS position is for staff only and is not part of it.
type DeliveryProof struct {
	// Method is CUSTOMER | SOMEONE_ELSE | SAFE_PLACE.
	Method        string  `json:"method"`
	RecipientName *string `json:"recipientName"`
	Relationship  *string `json:"relationship"`
	SafePlace     *string `json:"safePlace"`
	// PhotoURL and SignatureURL are short-lived links (about 5 minutes); null when there is none or it cannot be shown.
	PhotoURL     *string `json:"photoUrl"`
	SignatureURL *string `json:"signatureUrl"`
	// At is when it was delivered.
	At string `json:"at"`

	// The media asset ids the links are made from.
	PhotoAssetID     string `json:"-"`
	SignatureAssetID string `json:"-"`
}
