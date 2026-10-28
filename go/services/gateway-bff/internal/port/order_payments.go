package port

import "context"

// UpPaymentLine is one payments line as charged, with what was refunded so far (payments PaymentLineResponse).
type UpPaymentLine struct {
	LineID           string `json:"lineId"`
	ItemsMinor       int64  `json:"itemsMinor"`
	ShippingMinor    int64  `json:"shippingMinor"`
	TaxMinor         int64  `json:"taxMinor"`
	ShippingTaxMinor int64  `json:"shippingTaxMinor"`
	RefundedMinor    int64  `json:"refundedMinor"`
	RefundableMinor  int64  `json:"refundableMinor"`
}

// UpOrderPayment is an order's payment (payments PaymentResponse).
type UpOrderPayment struct {
	Status        string `json:"status"`
	AmountMinor   int64  `json:"amountMinor"`
	CapturedMinor int64  `json:"capturedMinor"`
	RefundedMinor int64  `json:"refundedMinor"`
	Currency      string `json:"currency"`
	// CreditMinor is the part of the order paid with AfroTransact credit (S320); the card paid the rest.
	CreditMinor int64 `json:"creditMinor"`
	// CardRefundsAsCredit: the shopper chose credit for the card part of this order's refunds (one way).
	CardRefundsAsCredit bool            `json:"cardRefundsAsCredit"`
	Lines               []UpPaymentLine `json:"lines"`
}

// OrderPaymentsClient reads an order's payment (GET /v1/payments?orderId=). Callers must have checked that the
// order belongs to the shopper first: payments does not check ownership on this read.
type OrderPaymentsClient interface {
	ByOrder(ctx context.Context, bearer, orderID string) (UpOrderPayment, error)
}
