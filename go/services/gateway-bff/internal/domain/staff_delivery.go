package domain

// The staff console Delivery module (docs/engineering/delivery-fulfilment.md): AfroTransact staff run AfroTransact-
// delivered orders end to end, without the driver app. Everything staff read names people and places, never ids (the
// shipment id is only the handle the console sends back with an action).

// Delivery modes the module lists. Seller-shipped orders and pickups are never in it.
const (
	DeliveryModeSellerStore = "AFROTRANSACT_DELIVERY"
	DeliveryModeFulfilled   = "FULFILLED_BY_AFROTRANSACT"
)

// DeliveryModule is the console module key; delivery:read views it, delivery:manage runs it.
const (
	DeliveryModule     = "delivery"
	PermDeliveryRead   = "delivery:read"
	PermDeliveryManage = "delivery:manage"
)

// DeliveryRow is one line of the queue.
type DeliveryRow struct {
	// ShipmentID is the handle for actions; the console never shows it.
	ShipmentID    string `json:"shipmentId"`
	OrderNumber   string `json:"orderNumber"`
	ShipmentLabel string `json:"shipmentLabel"`
	// Mode is AFROTRANSACT_DELIVERY (the seller opted in) or FULFILLED_BY_AFROTRANSACT (FBA or Retail).
	Mode string `json:"mode"`
	// Status is the shipment status, lower case: ready, picked_up, in_transit, out_for_delivery, delivery_failed, delivered.
	Status     string `json:"status"`
	SellerName string `json:"sellerName"`
	// OriginCity is where it is collected: the seller's city, or the fulfilment centre's. Empty when it cannot be read.
	OriginCity         string  `json:"originCity"`
	CustomerName       string  `json:"customerName"`
	CustomerCity       string  `json:"customerCity"`
	CustomerState      string  `json:"customerState"`
	CustomerPostalCode string  `json:"customerPostalCode"`
	Units              int     `json:"units"`
	Perishable         bool    `json:"perishable"`
	PromisedBy         *string `json:"promisedBy"`
	AssigneeName       *string `json:"assigneeName"`
	// AssigneeMe is true when the signed-in staff member has the job.
	AssigneeMe    bool    `json:"assigneeMe"`
	Attempts      int     `json:"attempts"`
	Version       int64   `json:"version"`
	ProofAttached bool    `json:"proofAttached"`
	UpdatedAt     *string `json:"updatedAt"`
	DeliveredAt   *string `json:"deliveredAt"`
}

// DeliveryList is the queue: a page of rows, the total, and how many shipments each status holds.
type DeliveryList struct {
	Items []DeliveryRow `json:"items"`
	Page  int           `json:"page"`
	Size  int           `json:"size"`
	Total int64         `json:"total"`
	// Counts is the number of shipments per status over the whole queue (the tab badges).
	Counts    map[string]int64 `json:"counts"`
	CanManage bool             `json:"canManage"`
}

// DeliveryCourier is a person a delivery can be assigned to.
type DeliveryCourier struct {
	ID   string `json:"id"`
	Name string `json:"name"`
	Me   bool   `json:"me"`
}

// DeliveryDestination is where it goes; the street, phone and instructions only come with delivery:manage.
type DeliveryDestination struct {
	Name           string  `json:"name"`
	Line1          *string `json:"line1"`
	Line2          *string `json:"line2"`
	City           string  `json:"city"`
	State          string  `json:"state"`
	PostalCode     *string `json:"postalCode"`
	Phone          *string `json:"phone"`
	Instructions   *string `json:"instructions"`
	HandToCustomer bool    `json:"handToCustomer"`
}

// DeliveryItem is one thing to deliver.
type DeliveryItem struct {
	Title    string `json:"title"`
	Quantity int    `json:"quantity"`
}

// DeliveryJob says who has it.
type DeliveryJob struct {
	AssigneeName *string `json:"assigneeName"`
	AssignedAt   *string `json:"assignedAt"`
	AssignedBy   *string `json:"assignedBy"`
	Attempts     int     `json:"attempts"`
	LastFailure  *string `json:"lastFailure"`
	// Version is sent back with the next action (409 when someone else changed the job meanwhile).
	Version int64 `json:"version"`
}

// DeliveryProofView is the saved proof as staff see it: photos with short-lived links, who recorded it and when.
type DeliveryProofView struct {
	// PhotoAssetIDs are the media assets (the correction form sends them back); PhotoURLs are signed links (about
	// 5 minutes) in the same order, empty when one could not be signed.
	PhotoAssetIDs   []string `json:"photoAssetIds"`
	PhotoURLs       []string `json:"photoUrls"`
	RecipientName   *string  `json:"recipientName"`
	Note            *string  `json:"note"`
	Latitude        *float64 `json:"latitude"`
	Longitude       *float64 `json:"longitude"`
	DeliveredAt     string   `json:"deliveredAt"`
	RecordedByName  string   `json:"recordedByName"`
	CorrectedAt     *string  `json:"correctedAt"`
	CorrectedByName *string  `json:"correctedByName"`
	Corrections     int      `json:"corrections"`
	Version         int64    `json:"version"`
}

// DeliveryAuditLine is one entry of the trail: who did what, when.
type DeliveryAuditLine struct {
	Action     string `json:"action"`
	FromStatus string `json:"fromStatus"`
	ToStatus   string `json:"toStatus"`
	ActorName  string `json:"actorName"`
	// Note is the reason or note given (a failed delivery's reason, a correction's reason); empty when none.
	Note string `json:"note"`
	At   string `json:"at"`
}

// DeliveryDetail is one delivery in full: the route, the job, the proof and the audit trail.
type DeliveryDetail struct {
	Row          DeliveryRow         `json:"row"`
	Destination  DeliveryDestination `json:"destination"`
	Items        []DeliveryItem      `json:"items"`
	Job          DeliveryJob         `json:"job"`
	Proof        *DeliveryProofView  `json:"proof"`
	Audit        []DeliveryAuditLine `json:"audit"`
	NextStatuses []string            `json:"nextStatuses"`
	CanManage    bool                `json:"canManage"`
}

// DeliveryActionRequest is the body of take, picked-up and out-for-delivery.
type DeliveryActionRequest struct {
	// Version is the job version the console saw (optional: 409 when it is stale).
	Version *int64 `json:"version"`
	Note    string `json:"note"`
}

// DeliveryAssignRequest assigns the delivery to a named person.
type DeliveryAssignRequest struct {
	AssigneeID string `json:"assigneeId"`
	Version    *int64 `json:"version"`
	Note       string `json:"note"`
}

// DeliveryFailedRequest records a failed attempt.
type DeliveryFailedRequest struct {
	Reason  string `json:"reason"`
	Version *int64 `json:"version"`
}

// DeliveryProofRequest is the proof of a delivery (delivered) or its correction (proof, with a reason).
type DeliveryProofRequest struct {
	PhotoAssetIDs []string `json:"photoAssetIds"`
	RecipientName string   `json:"recipientName"`
	Note          string   `json:"note"`
	// Latitude and Longitude are only sent when the device had a position; never required.
	Latitude  *float64 `json:"latitude"`
	Longitude *float64 `json:"longitude"`
	// Reason is required to correct a saved proof.
	Reason  string `json:"reason"`
	Version *int64 `json:"version"`
}

// DeliveryUploadRequest asks for a direct upload of a proof photo.
type DeliveryUploadRequest struct {
	FileName    string `json:"fileName"`
	ContentType string `json:"contentType"`
	Size        int64  `json:"size"`
}

// DeliveryUploadTicket is the media asset to attach and the direct upload to PUT the photo to.
type DeliveryUploadTicket struct {
	AssetID string             `json:"assetId"`
	Upload  DeliveryUploadSpec `json:"upload"`
}

// DeliveryUploadSpec is how the console sends the file: the method, URL and headers of the direct upload.
type DeliveryUploadSpec struct {
	Method  string            `json:"method"`
	URL     string            `json:"url"`
	Headers map[string]string `json:"headers"`
}
