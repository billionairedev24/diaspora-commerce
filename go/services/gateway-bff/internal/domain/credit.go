package domain

// AfroTransact credit and gift cards as the storefront and the apps see them (S320, S321, ADR 0006). Credit only buys
// products and services: nothing here pays it out, refunds it to a card or moves it to someone else.

// Problem codes of gift cards.
const (
	// CodeGiftCardInvalid (404): the code is not a gift card's. Wrong guesses are counted.
	CodeGiftCardInvalid = "GIFT_CARD_INVALID"
	// CodeGiftCardRedeemed (409): the card was redeemed already.
	CodeGiftCardRedeemed = "GIFT_CARD_REDEEMED"
	// CodeGiftCardInvalidRequest (422): the amount, email or date is not allowed.
	CodeGiftCardInvalidRequest = "GIFT_CARD_INVALID_REQUEST"
	// CodeNoCardPortion (409): the order was paid with credit only; its refunds already come back as credit.
	CodeNoCardPortion = "NO_CARD_PORTION"
)

// CreditEntry is one line of the shopper's credit history. The storefront words it from kind and source in the
// shopper's language: staff reasons and order ids stay inside AfroTransact.
type CreditEntry struct {
	ID string `json:"id"`
	// Kind is GRANT, SPEND, REFUND or ADJUSTMENT.
	Kind string `json:"kind"`
	// Source is REFERRAL, GIFT_CARD, MEMBERSHIP, REFUND, STAFF, PROMO or ORDER.
	Source string `json:"source"`
	// AmountMinor is signed: credit added is positive, credit spent negative.
	AmountMinor       int64  `json:"amountMinor"`
	Currency          string `json:"currency"`
	BalanceAfterMinor int64  `json:"balanceAfterMinor"`
	CreatedAt         string `json:"createdAt"`
}

// CreditAccount is the shopper's balance and a page of history, newest first.
type CreditAccount struct {
	BalanceMinor int64         `json:"balanceMinor"`
	Currency     string        `json:"currency"`
	Entries      []CreditEntry `json:"entries"`
	// NextBefore pages backwards: pass it as ?before= for older entries; null on the last page.
	NextBefore *string `json:"nextBefore"`
}

// CheckoutCredit is what the checkout page needs to offer "use my credit".
type CheckoutCredit struct {
	AvailableMinor int64 `json:"availableMinor"`
	// AppliedMinor is what credit would pay of this order if the shopper uses it: the balance up to the total, leaving
	// at least 0.50 for the card or paying everything (payments decides when the order is placed).
	AppliedMinor int64 `json:"appliedMinor"`
	// CardDueMinor is what the card pays then; 0 means no card is needed.
	CardDueMinor int64 `json:"cardDueMinor"`
}

// GiftCardConfig is what the buy page needs: limits, preset amounts and staff-written terms by language.
type GiftCardConfig struct {
	MinMinor int64  `json:"minMinor"`
	MaxMinor int64  `json:"maxMinor"`
	Currency string `json:"currency"`
	// PublishableKey is the Stripe key the Payment Element on the buy page is created with.
	PublishableKey string            `json:"publishableKey"`
	PresetsMinor   []int64           `json:"presetsMinor"`
	Terms          map[string]string `json:"terms"`
}

// GiftCard is a card as its buyer sees it: never the code, never who redeemed it.
type GiftCard struct {
	ID             string  `json:"id"`
	Status         string  `json:"status"`
	AmountMinor    int64   `json:"amountMinor"`
	Currency       string  `json:"currency"`
	RecipientEmail string  `json:"recipientEmail"`
	RecipientName  *string `json:"recipientName"`
	SenderName     *string `json:"senderName"`
	Message        *string `json:"message"`
	SendAt         string  `json:"sendAt"`
	DeliveryStatus string  `json:"deliveryStatus"`
	SentAt         *string `json:"sentAt"`
	Redeemed       bool    `json:"redeemed"`
	CreatedAt      string  `json:"createdAt"`
}

// GiftCardPurchase is a card being bought: confirm the payment with Stripe Elements using the client secret.
type GiftCardPurchase struct {
	GiftCard
	ClientSecret   *string `json:"clientSecret"`
	PublishableKey string  `json:"publishableKey"`
}

// GiftCardPurchaseRequest is the buy form.
type GiftCardPurchaseRequest struct {
	AmountMinor    int64  `json:"amountMinor"`
	RecipientEmail string `json:"recipientEmail"`
	RecipientName  string `json:"recipientName,omitempty"`
	SenderName     string `json:"senderName,omitempty"`
	Message        string `json:"message,omitempty"`
	// SendAt is when the card is emailed; empty sends it now.
	SendAt string `json:"sendAt,omitempty"`
	Locale string `json:"locale,omitempty"`
}

// GiftCardRedeemRequest carries the code from the card's email.
type GiftCardRedeemRequest struct {
	Code string `json:"code"`
}

// GiftCardRedemption is what redeeming added.
type GiftCardRedemption struct {
	RedeemedMinor int64  `json:"redeemedMinor"`
	BalanceMinor  int64  `json:"balanceMinor"`
	Currency      string `json:"currency"`
}

// CreditRefundChoice is the shopper's one-way choice to take the card part of an order's refunds as credit.
type CreditRefundChoice struct {
	OrderNumber         string `json:"orderNumber"`
	CardRefundsAsCredit bool   `json:"cardRefundsAsCredit"`
	CardPartMinor       int64  `json:"cardPartMinor"`
	Currency            string `json:"currency"`
}
