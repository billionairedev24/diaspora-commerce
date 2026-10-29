package port

import (
	"context"
	"time"
)

// ---------------------------------------------------------------- payments: credit and gift cards (S320, S321)

// UpCreditEntry is payments' CreditEntry.
type UpCreditEntry struct {
	ID                string    `json:"id"`
	Kind              string    `json:"kind"`
	AmountMinor       int64     `json:"amountMinor"`
	Currency          string    `json:"currency"`
	Source            string    `json:"source"`
	Reason            string    `json:"reason"`
	OrderID           string    `json:"orderId"`
	BalanceAfterMinor int64     `json:"balanceAfterMinor"`
	CreatedAt         time.Time `json:"createdAt"`
}

// UpCreditBalance is payments' CreditBalance.
type UpCreditBalance struct {
	CustomerID   string `json:"customerId"`
	BalanceMinor int64  `json:"balanceMinor"`
	Currency     string `json:"currency"`
}

// UpGiftCardConfig is payments' GiftCardConfig.
type UpGiftCardConfig struct {
	MinMinor     int64             `json:"minMinor"`
	MaxMinor     int64             `json:"maxMinor"`
	Currency     string            `json:"currency"`
	PresetsMinor []int64           `json:"presetsMinor"`
	Terms        map[string]string `json:"terms"`
}

// UpGiftCard is payments' GiftCardPurchase (the buyer's view of a card).
type UpGiftCard struct {
	ID             string     `json:"id"`
	Status         string     `json:"status"`
	AmountMinor    int64      `json:"amountMinor"`
	Currency       string     `json:"currency"`
	RecipientEmail string     `json:"recipientEmail"`
	RecipientName  *string    `json:"recipientName"`
	SenderName     *string    `json:"senderName"`
	Message        *string    `json:"message"`
	SendAt         time.Time  `json:"sendAt"`
	DeliveryStatus string     `json:"deliveryStatus"`
	SentAt         *time.Time `json:"sentAt"`
	Redeemed       bool       `json:"redeemed"`
	CreatedAt      time.Time  `json:"createdAt"`
	ClientSecret   *string    `json:"clientSecret"`
}

// UpGiftCardPurchaseRequest is payments' GiftCardPurchaseRequest.
type UpGiftCardPurchaseRequest struct {
	AmountMinor    int64  `json:"amountMinor"`
	RecipientEmail string `json:"recipientEmail"`
	RecipientName  string `json:"recipientName,omitempty"`
	SenderName     string `json:"senderName,omitempty"`
	Message        string `json:"message,omitempty"`
	SendAt         string `json:"sendAt,omitempty"`
	Locale         string `json:"locale,omitempty"`
}

// UpGiftCardRedemption is payments' GiftCardRedemption.
type UpGiftCardRedemption struct {
	RedeemedMinor int64  `json:"redeemedMinor"`
	BalanceMinor  int64  `json:"balanceMinor"`
	Currency      string `json:"currency"`
}

// CreditClient calls payments with the shopper's token: the credit balance and history, gift cards. There is deliberately no call that moves credit out.
type CreditClient interface {
	Balance(ctx context.Context, bearer string) (UpCreditBalance, error)
	Entries(ctx context.Context, bearer string, before time.Time, limit int) ([]UpCreditEntry, error)
	GiftCardConfig(ctx context.Context, bearer string) (UpGiftCardConfig, error)
	MyGiftCards(ctx context.Context, bearer string) ([]UpGiftCard, error)
	// StartGiftCard buys a card (POST /v1/gift-cards/purchases, Idempotency-Key).
	StartGiftCard(ctx context.Context, bearer, key string, req UpGiftCardPurchaseRequest) (UpGiftCard, error)
	ConfirmGiftCard(ctx context.Context, bearer, id string) (UpGiftCard, error)
	// Redeem adds a card's value to the shopper's credit; clientAddress is the caller's network address (rate limits).
	Redeem(ctx context.Context, bearer, code, clientAddress string) (UpGiftCardRedemption, error)
}
