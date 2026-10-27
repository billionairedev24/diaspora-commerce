package app_test

import (
	"strings"
	"testing"

	"github.com/billionairedev24/diaspora-commerce/go/services/notifications/internal/domain"
)

// payments.gift_card.deliver (S321) emails the recipient's address (they may have no account) with the amount, the
// buyer's words and the redeem link; it sends no SMS or push and asks identity nothing.
func TestGiftCardDeliverEmailsTheRecipientAddress(t *testing.T) {
	store := newFakeStore()
	ing := newIngest(store, &fakeResolver{}, "amount", "code", "senderName", "recipientName", "message", "redeemUrl")
	handle(t, ing, "payments.gift_card.deliver", map[string]any{"recipient": map[string]any{"email": "ama@example.com"}, "locale": "fr",
		"giftCardId": "gc-1", "recipientName": "Ama", "senderName": nil, "message": nil, "amountMinor": 5000, "currency": "USD", "code": "ABCD-EFGH-JKLM-2345"})
	if got := strings.Join(summary(store.enqueued), ","); got != "payment.gift_card.delivered|email|ama@example.com" {
		t.Fatalf("got %v", got)
	}
	body := emailBody(t, store.enqueued, "payment.gift_card.delivered")
	for _, part := range []string{"amount=$50.00", "code=ABCD-EFGH-JKLM-2345", "recipientName=Ama", "senderName=;", "message=;", "redeemUrl=https://shop.test/account/credit"} {
		if !strings.Contains(body, part) {
			t.Errorf("%q lacks %q", body, part)
		}
	}
	if store.enqueued[0].Channel != domain.ChannelEmail {
		t.Errorf("channel = %q", store.enqueued[0].Channel)
	}
}
