package domain

// SellerReturnLine is one item in a return.
type SellerReturnLine struct {
	Title    string `json:"title"`
	Quantity int    `json:"quantity"`
	Reason   string `json:"reason"`
	Outcome  string `json:"outcome,omitempty"`
}

// SellerReturn is a return a customer started on one of the organisation's orders (tax-returns).
type SellerReturn struct {
	Number      string             `json:"number"`
	OrderNumber string             `json:"orderNumber"`
	Title       string             `json:"title"`
	Status      string             `json:"status"`
	StatusLabel string             `json:"statusLabel"`
	Method      string             `json:"method"`
	RefundMinor int64              `json:"refundMinor"`
	Currency    string             `json:"currency"`
	RefundState string             `json:"refundState"`
	Items       []SellerReturnLine `json:"items"`
	Carrier     *string            `json:"carrier"`
	Tracking    *string            `json:"trackingNumber"`
	DropOffBy   *string            `json:"dropOffBy"`
	CreatedAt   *string            `json:"createdAt"`
}

// SellerDispute is a dispute or A-to-z claim against the organisation (trust-ops). The buyer is never identified.
type SellerDispute struct {
	Reference      string  `json:"reference"`
	Type           string  `json:"type"`
	TypeLabel      string  `json:"typeLabel"`
	Status         string  `json:"status"`
	StatusLabel    string  `json:"statusLabel"`
	OrderNumber    string  `json:"orderNumber"`
	AmountMinor    int64   `json:"amountMinor"`
	Currency       string  `json:"currency"`
	Description    string  `json:"description"`
	Responded      bool    `json:"responded"`
	SellerResponse *string `json:"sellerResponse"`
	RespondBy      *string `json:"respondBy"`
	DeadlineState  *string `json:"deadlineState"`
	Escalated      bool    `json:"escalated"`
	Outcome        *string `json:"outcome"`
	RefundMinor    int64   `json:"refundMinor"`
	OpenedAt       *string `json:"openedAt"`
}
