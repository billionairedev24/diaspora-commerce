package testkit

import (
	"net/http"
	"strconv"
	"strings"
	"sync"
)

// Credit and gift card fixtures (S320, S321).
const (
	// CreditBalanceMinor is the fake shopper's credit; CreditEntriesTotal the number of ledger entries behind it.
	CreditBalanceMinor = 2500
	CreditEntriesTotal = 3
	// GiftCardCode redeems once into CreditGiftCardMinor; GiftCardRateLimitedCode answers 429.
	GiftCardCode            = "ABCD-EFGH-JKLM-2345"
	GiftCardRateLimitedCode = "ZZZZ-ZZZZ-ZZZZ-2222"
	CreditGiftCardMinor     = 5000
	// CreditPaidOrderID was paid partly with credit: choosing credit refunds works on it. CreditOnlyOrderID was paid
	// with credit only.
	CreditPaidOrderID = ShopperOrderID
	CreditOnlyOrderID = "0b0b0b0b-0000-4000-8000-000000000999" //nolint:gosec // a test fixture, not a credential
	// CreditOnlyOrderNumber is the order number of CreditOnlyOrderID.
	CreditOnlyOrderNumber = "AT-2026-CREDIT1"
)

type creditState struct {
	mu        sync.Mutex
	redeemed  bool
	purchases map[string]map[string]any // Idempotency-Key → card
	chosen    bool
}

// creditRoutes serves payments' credit, gift card and refund-choice routes for the shopper fakes.
func (u *Upstreams) creditRoutes(mux *http.ServeMux) {
	st := &creditState{purchases: map[string]map[string]any{}}
	entry := func(i int, kind, source string, amount, after int64) map[string]any {
		return map[string]any{"id": "0e0e0e0e-0000-4000-8000-00000000000" + strconv.Itoa(i), "kind": kind, "source": source, "amountMinor": amount,
			"currency": "USD", "reason": "Internal reason " + strconv.Itoa(i), "orderId": nil, "balanceAfterMinor": after,
			"createdAt": "2026-10-2" + strconv.Itoa(i) + "T10:00:00Z"}
	}
	// newest first: 2500 from a referral, 5000 from a gift card, 5000 spent on an order
	all := []map[string]any{
		entry(3, "SPEND", "ORDER", -5000, 2500),
		entry(2, "GRANT", "GIFT_CARD", 5000, 7500),
		entry(1, "GRANT", "REFERRAL", 2500, 2500),
	}

	mux.HandleFunc("GET /v1/credit/me", customerOnly("payments.customer-only", func(w http.ResponseWriter, _ *http.Request) {
		writeJSON(w, 200, map[string]any{"customerId": "cust", "balanceMinor": CreditBalanceMinor, "currency": "USD"})
	}))
	mux.HandleFunc("GET /v1/credit/me/entries", customerOnly("payments.customer-only", func(w http.ResponseWriter, r *http.Request) {
		limit, _ := strconv.Atoi(r.URL.Query().Get("limit"))
		if limit <= 0 || limit > len(all) {
			limit = len(all)
		}
		before := r.URL.Query().Get("before")
		out := []map[string]any{}
		for _, e := range all {
			if before != "" && e["createdAt"].(string) >= before {
				continue
			}
			if len(out) < limit {
				out = append(out, e)
			}
		}
		writeJSON(w, 200, out)
	}))
	mux.HandleFunc("GET /v1/gift-cards/config", customerOnly("payments.customer-only", func(w http.ResponseWriter, _ *http.Request) {
		writeJSON(w, 200, map[string]any{"minMinor": 1000, "maxMinor": 50000, "currency": "USD", "presetsMinor": []int64{2500, 5000, 10000, 20000},
			"terms": map[string]string{"en": "Gift cards never expire. Redeemed value is AfroTransact credit: it buys products and services and cannot be paid out."}})
	}))
	card := func(id, status string, amount int64, secret any) map[string]any {
		return map[string]any{"id": id, "status": status, "amountMinor": amount, "currency": "USD", "recipientEmail": "ama@example.com",
			"recipientName": "Ama", "senderName": "Kofi", "message": "Happy birthday!", "sendAt": "2026-11-01T09:00:00Z", "deliveryStatus": "SCHEDULED",
			"sentAt": nil, "redeemed": false, "createdAt": "2026-10-27T10:00:00Z", "clientSecret": secret}
	}
	mux.HandleFunc("GET /v1/gift-cards/mine", customerOnly("payments.customer-only", func(w http.ResponseWriter, _ *http.Request) {
		writeJSON(w, 200, []map[string]any{card("0f0f0f0f-0000-4000-8000-000000000001", "ACTIVE", 5000, nil)})
	}))
	mux.HandleFunc("POST /v1/gift-cards/purchases", customerOnly("payments.customer-only", func(w http.ResponseWriter, r *http.Request) {
		key := r.Header.Get("Idempotency-Key")
		body := readJSON(r)
		amount, _ := body["amountMinor"].(float64)
		if amount < 1000 || amount > 50000 {
			problem(w, 422, "payments.gift-card-invalid-request", "Invalid gift card", map[string]any{"fieldErrors": map[string]string{"amountMinor": "Between 10.00 and 500.00"}})
			return
		}
		st.mu.Lock()
		defer st.mu.Unlock()
		c, ok := st.purchases[key]
		if !ok {
			c = card("0f0f0f0f-0000-4000-8000-000000000002", "PENDING_PAYMENT", int64(amount), "pi_gift_secret_test")
			st.purchases[key] = c
		}
		writeJSON(w, 201, c)
	}))
	mux.HandleFunc("POST /v1/gift-cards/purchases/{id}/confirm", customerOnly("payments.customer-only", func(w http.ResponseWriter, r *http.Request) {
		if r.PathValue("id") != "0f0f0f0f-0000-4000-8000-000000000002" {
			problem(w, 404, "payments.gift-card-not-found", "No gift card", nil)
			return
		}
		writeJSON(w, 200, card(r.PathValue("id"), "ACTIVE", 5000, nil))
	}))
	mux.HandleFunc("POST /v1/gift-cards/redeem", customerOnly("payments.customer-only", func(w http.ResponseWriter, r *http.Request) {
		code, _ := readJSON(r)["code"].(string)
		st.mu.Lock()
		defer st.mu.Unlock()
		switch {
		case strings.EqualFold(code, GiftCardRateLimitedCode):
			w.Header().Set("Retry-After", "600")
			problem(w, 429, "payments.gift-card-rate-limited", "Too many wrong codes; try again later", map[string]any{"retryAfterSeconds": 600})
		case !strings.EqualFold(code, GiftCardCode):
			problem(w, 404, "payments.gift-card-invalid", "That gift card code is not valid", nil)
		case st.redeemed:
			problem(w, 409, "payments.gift-card-redeemed", "This gift card was already redeemed", nil)
		default:
			st.redeemed = true
			writeJSON(w, 200, map[string]any{"redeemedMinor": CreditGiftCardMinor, "balanceMinor": CreditBalanceMinor + CreditGiftCardMinor, "currency": "USD"})
		}
	}))
	mux.HandleFunc("POST /v1/customers/me/orders/{orderId}/credit-refunds", customerOnly("payments.customer-only", func(w http.ResponseWriter, r *http.Request) {
		switch r.PathValue("orderId") {
		case CreditPaidOrderID:
			st.mu.Lock()
			st.chosen = true
			st.mu.Unlock()
			writeJSON(w, 200, map[string]any{"orderId": CreditPaidOrderID, "cardRefundsAsCredit": true, "cardPartMinor": 1353, "currency": "USD",
				"chosenBy": "cust", "chosenAt": "2026-10-27T10:00:00Z"})
		case CreditOnlyOrderID:
			problem(w, 409, "payments.no-card-portion", "Order was paid with credit only", nil)
		default:
			problem(w, 404, "payments.payment-not-found", "No payment for order", nil)
		}
	}))
}
