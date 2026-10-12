package app_test

import (
	"strings"
	"testing"
)

// S300: a password reset code goes out on the payload's channel with the reset template (never the generic
// verification one); accounts without a password and completed resets get their own security emails, all to the
// payload's recipient without an identity lookup.
func TestPasswordResetNotifications(t *testing.T) {
	code := func(channel string, recipient map[string]any) map[string]any {
		return map[string]any{"recipient": recipient, "channel": channel, "code": "482913", "expiresInMinutes": 10,
			"expiresAt": "2026-10-10T10:10:00Z", "purpose": "PASSWORD_RESET", "userId": "u-9", "firstName": nil,
			"transactional": true}
	}
	store := newFakeStore()
	ing := newIngest(store, owners(), "code", "greetingName", "expiresInMinutes")
	handle(t, ing, "identity.verification.requested", code("email", map[string]any{"id": "u-9", "email": "ngozi@example.com"}))
	handle(t, ing, "identity.verification.requested", code("sms", map[string]any{"id": "u-9", "phone": "+15550009999"}))
	if got := strings.Join(summary(store.enqueued), ","); got !=
		"identity.password_reset|email|ngozi@example.com,identity.password_reset|sms|+15550009999" {
		t.Fatalf("reset code = %v", got)
	}
	if body := emailBody(t, store.enqueued, "identity.password_reset"); !strings.Contains(body, "code=482913") ||
		!strings.Contains(body, "greetingName=there") {
		t.Fatalf("reset body = %q", body)
	}

	store = newFakeStore()
	ing = newIngest(store, owners(), "providerList", "signInUrl", "changedOn", "helpUrl")
	handle(t, ing, "identity.password-reset.federated", map[string]any{"recipient": map[string]any{"id": "u-9",
		"email": "ngozi@example.com"}, "userId": "u-9", "firstName": "Ngozi", "providers": []string{"Apple", "Google"},
		"transactional": true})
	handle(t, ing, "identity.password.changed", map[string]any{"recipient": map[string]any{"id": "u-9",
		"email": "ngozi@example.com"}, "userId": "u-9", "firstName": "Ngozi", "method": "RESET",
		"changedAt": "2026-10-12T14:05:00Z", "sessionsRevoked": 2, "transactional": true})
	if got := strings.Join(summary(store.enqueued), ","); got !=
		"identity.password_changed|email|ngozi@example.com,identity.password_reset.federated|email|ngozi@example.com" {
		t.Fatalf("security emails = %v", got)
	}
	if body := emailBody(t, store.enqueued, "identity.password_reset.federated"); !strings.Contains(body, "providerList=Apple and Google") {
		t.Fatalf("federated body = %q", body)
	}
	if body := emailBody(t, store.enqueued, "identity.password_changed"); !strings.Contains(body, "changedOn=Mon, Oct 12") ||
		!strings.Contains(body, "signInUrl=https://") {
		t.Fatalf("changed body = %q", body)
	}
}
