package app_test

import (
	"strings"
	"testing"

	"github.com/billionairedev24/diaspora-commerce/go/services/notifications/internal/domain"
	"github.com/billionairedev24/diaspora-commerce/go/services/notifications/internal/port"
)

// payments.referral.rewarded (S322) tells the customer who earned the reward, resolved through identity, by email.
// The referrer hears which friend ordered; the friend gets a welcome. Credit shows as an amount, membership as days.
func TestReferralRewardEmailsTheCustomerWhoEarnedIt(t *testing.T) {
	store := newFakeStore()
	ing := newIngest(store, &fakeResolver{contacts: map[string]port.Contact{"cust-1": ada(), "cust-2": ada()}}, "amount", "days", "friendName", "isReferrer", "isCredit", "creditUrl", "referUrl")
	handle(t, ing, "payments.referral.rewarded", map[string]any{"referralId": "r-1", "customerId": "cust-1", "side": "REFERRER",
		"rewardType": "CREDIT", "creditMinor": 1000, "currency": "USD", "membershipDays": 0, "friendName": "Amara O."})
	if len(store.enqueued) != 1 || store.enqueued[0].Channel != domain.ChannelEmail {
		t.Fatalf("got %v", summary(store.enqueued))
	}
	body := emailBody(t, store.enqueued, "payment.referral.rewarded")
	for _, part := range []string{"amount=$10.00", "friendName=Amara O.", "isReferrer=true", "isCredit=true", "creditUrl=https://shop.test/account/credit"} {
		if !strings.Contains(body, part) {
			t.Errorf("%q lacks %q", body, part)
		}
	}
}

func TestReferralMembershipRewardShowsDaysAndLinksToReferrals(t *testing.T) {
	store := newFakeStore()
	ing := newIngest(store, &fakeResolver{contacts: map[string]port.Contact{"cust-1": ada(), "cust-2": ada()}}, "amount", "days", "friendName", "isReferrer", "isCredit", "creditUrl", "referUrl")
	handle(t, ing, "payments.referral.rewarded", map[string]any{"referralId": "r-1", "customerId": "cust-2", "side": "REFERRED",
		"rewardType": "MEMBERSHIP_DAYS", "creditMinor": 0, "currency": "USD", "membershipDays": 14, "friendName": nil})
	body := emailBody(t, store.enqueued, "payment.referral.rewarded")
	for _, part := range []string{"days=14", "isReferrer=false", "isCredit=false", "friendName=your friend", "referUrl=https://shop.test/account/refer"} {
		if !strings.Contains(body, part) {
			t.Errorf("%q lacks %q", body, part)
		}
	}
}
