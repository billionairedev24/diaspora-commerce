package port

import "context"

// UpDeliveryProof is orders' DeliveryProof of a timeline entry: what the driver app recorded at the door. The photo and
// the signature are media asset ids; the driver's GPS position is only in staff's view and is not read here.
type UpDeliveryProof struct {
	HandoverMethod   string `json:"handoverMethod"`
	RecipientName    string `json:"recipientName"`
	Relationship     string `json:"relationship"`
	SafePlace        string `json:"safePlace"`
	FailureReason    string `json:"failureReason"`
	PhotoAssetID     string `json:"photoAssetId"`
	SignatureAssetID string `json:"signatureAssetId"`
}

// ProofLinks turns proof-of-delivery asset ids into short-lived download links (media-service, with the BFF's own
// client-credentials token: proof photos are private and the people who may see them are not the uploader). Callers
// pass only ids that orders-service returned for an order the viewer was already allowed to read.
type ProofLinks interface {
	// URLs returns a link per id; an id media cannot sign (gone, not readable, upstream down) is left out, so the page
	// shows the proof without its picture rather than failing.
	URLs(ctx context.Context, assetIDs ...string) map[string]string
}
