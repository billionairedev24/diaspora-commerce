package app_test

import (
	"strings"
	"testing"

	"github.com/billionairedev24/diaspora-commerce/go/services/notifications/internal/port"
)

// payments.membership.changed (S323) emails the member, in their language, when a membership renews or a trial converts,
// when a renewal payment fails (and when it lapses) and a few days before a free trial ends. Other steps stay quiet.
func TestMembershipEmailsTheMemberOnRenewalFailureAndEndingTrial(t *testing.T) {
	fields := []string{"amount", "date", "isYearly", "isLapsed", "isTrialConverted", "manageUrl", "actionUrl", "tierName"}
	cases := []struct {
		name, template string
		payload        map[string]any
		want           []string
	}{
		{"renewed", "payment.membership.renewed", map[string]any{"event": "RENEWED", "status": "ACTIVE", "interval": "MONTHLY", "amountMinor": 999, "periodEnd": "2026-11-28T10:00:00Z"},
			[]string{"amount=$9.99", "isYearly=false", "isTrialConverted=false", "manageUrl=https://shop.test/account/membership"}},
		{"trial converted", "payment.membership.renewed", map[string]any{"event": "TRIAL_CONVERTED", "status": "ACTIVE", "interval": "YEARLY", "amountMinor": 9900, "periodEnd": "2027-11-28T10:00:00Z"},
			[]string{"amount=$99.00", "isYearly=true", "isTrialConverted=true"}},
		{"payment failed, retrying", "payment.membership.payment_failed", map[string]any{"event": "RENEWAL_FAILED", "status": "PAST_DUE", "interval": "MONTHLY", "amountMinor": 999, "periodEnd": "2026-10-28T10:00:00Z"},
			[]string{"isLapsed=false", "actionUrl=https://shop.test/account/payments"}},
		{"payment failed for good", "payment.membership.payment_failed", map[string]any{"event": "RENEWAL_FAILED", "status": "LAPSED", "interval": "MONTHLY", "amountMinor": 999, "periodEnd": "2026-10-28T10:00:00Z"},
			[]string{"isLapsed=true", "actionUrl=https://shop.test/membership"}},
		{"trial ending", "payment.membership.trial_ending", map[string]any{"event": "TRIAL_ENDING", "status": "TRIALING", "interval": "MONTHLY", "amountMinor": 999, "periodEnd": "2026-11-02T10:00:00Z"},
			[]string{"amount=$9.99", "manageUrl=https://shop.test/account/membership"}},
	}
	for _, c := range cases {
		t.Run(c.name, func(t *testing.T) {
			store := newFakeStore()
			ing := newIngest(store, &fakeResolver{contacts: map[string]port.Contact{"cust-1": ada()}}, fields...)
			payload := map[string]any{"membershipId": "m-1", "customerId": "cust-1", "tierCode": "plus", "tierName": "Plus", "currency": "USD", "detail": ""}
			for k, v := range c.payload {
				payload[k] = v
			}
			handle(t, ing, "payments.membership.changed", payload)
			if len(store.enqueued) != 1 {
				t.Fatalf("got %v", summary(store.enqueued))
			}
			body := emailBody(t, store.enqueued, c.template)
			for _, part := range append(c.want, "tierName=Plus") {
				if !strings.Contains(body, part) {
					t.Errorf("%q lacks %q", body, part)
				}
			}
		})
	}
}

func TestMembershipStaysQuietForStepsThatNeedNoEmail(t *testing.T) {
	for _, event := range []string{"JOINED", "TRIAL_STARTED", "PAST_DUE", "LAPSED", "CANCEL_SCHEDULED", "UPGRADED", "ENDED", "FREE_DAYS_GRANTED"} {
		store := newFakeStore()
		ing := newIngest(store, &fakeResolver{contacts: map[string]port.Contact{"cust-1": ada()}}, "amount")
		handle(t, ing, "payments.membership.changed", map[string]any{"membershipId": "m-1", "customerId": "cust-1", "event": event, "tierCode": "plus", "tierName": "Plus",
			"status": "ACTIVE", "interval": "MONTHLY", "amountMinor": 0, "currency": "USD"})
		if len(store.enqueued) != 0 {
			t.Errorf("%s sent %v", event, summary(store.enqueued))
		}
	}
}
