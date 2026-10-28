package app

import (
	"net/url"
	"slices"
	"strconv"
	"strings"

	"github.com/billionairedev24/diaspora-commerce/go/services/notifications/internal/domain"
)

// Delivery runs (S262, ADR 0005). delivery-runs publishes run events on at.delivery-runs.events:
//
//   - delivery-runs.run.announced / .opened / .cutoff_reminder go to the location's subscribers on every channel they
//     consented to (RecipientLocation: delivery-runs returns only members with consent on that channel; email-only
//     subscribers get email only). Their links are the run link with UTM per channel (links.<channel>, utm_campaign
//     delivery-run-<slug>-<yyyymmdd>, utm_content = the event), so every announcement is attributed in analytics.
//   - .out_for_delivery (dispatch) and .confirmed go to the customers on the run (orders[]), with their own handover
//     code at a pickup point. A run handed over from a pre-order window (reason "PREORDER <code>") is already covered
//     by the window's own emails, so its confirmation is not sent twice.
//   - .cancelled tells the customers on a run staff scheduled that every order is refunded in full, delivery fee
//     included; a run cancelled with its window ("Pre-order <code> cancelled: …") is covered by the cancelled
//     pre-order email orders-service sends for each shipment, so it is skipped here.
//   - subscription.created / .confirmed send the double opt-in link and the welcome (with the WhatsApp community).
//
// WhatsApp: a message may only go out as an approved template to a member who opted in on WhatsApp. Approval happens
// in Meta Business Manager, outside this repository, so the channel is off until deliveryRuns.whatsapp.enabled is
// set (RunRulesWithWhatsApp). Template names and parameters are the chat rows seeded for run.opened and
// run.cutoff_reminder (subject = approved template name, one parameter per body line).

// runEventRules are the rules with WhatsApp off, the platform default.
var runEventRules = RunRulesWithWhatsApp(false)

// RunRulesWithWhatsApp returns the delivery-runs event rules. With whatsApp true the open and cutoff-reminder
// announcements also go to the location's WhatsApp-consented members as approved templates.
func RunRulesWithWhatsApp(whatsApp bool) map[string][]EventRule {
	open, remind := chEmailSMSPush, chEmailSMSPush
	if whatsApp {
		open = append(slices.Clone(open), domain.ChannelChat)
		remind = append(slices.Clone(remind), domain.ChannelChat)
	}
	return map[string][]EventRule{
		"delivery-runs.run.announced": {{Topic: TopicDeliveryRuns, Template: "run.announced", Channels: chEmailPush,
			Recipient: RecipientLocation, IDField: "locationId", Enrich: enrichRun}},
		"delivery-runs.run.opened": {{Topic: TopicDeliveryRuns, Template: "run.opened", Channels: open,
			Recipient: RecipientLocation, IDField: "locationId", Enrich: enrichRun}},
		"delivery-runs.run.cutoff_reminder": {{Topic: TopicDeliveryRuns, Template: "run.cutoff_reminder", Channels: remind,
			Recipient: RecipientLocation, IDField: "locationId", Enrich: enrichRun}},
		"delivery-runs.run.out_for_delivery": {{Topic: TopicDeliveryRuns, Template: "run.out_for_delivery",
			Channels: chSMSPushEmail, Recipient: RecipientCustomers, IDField: "orders", Enrich: enrichRun}},
		"delivery-runs.run.confirmed": {{Topic: TopicDeliveryRuns, Template: "run.confirmed", Channels: chEmailPush,
			Recipient: RecipientCustomers, IDField: "orders", When: runNotFromWindow, Enrich: enrichRun}},
		"delivery-runs.run.cancelled": {{Topic: TopicDeliveryRuns, Template: "run.cancelled", Channels: chEmailSMSPush,
			Recipient: RecipientCustomers, IDField: "orders", When: runCancelledOnItsOwn, Enrich: enrichRunCancelled}},
		"delivery-runs.subscription.created": {
			{Topic: TopicDeliveryRuns, Template: "run.subscription.confirm", Channels: chEmail, Recipient: RecipientPayload,
				Security: true, When: subscriptionNeedsConfirming, Enrich: enrichRunSubscription},
			{Topic: TopicDeliveryRuns, Template: "run.subscription.welcome", Channels: chEmail, Recipient: RecipientUser,
				IDField: "userId", When: subscriptionActiveForUser, Enrich: enrichRunSubscription},
		},
		"delivery-runs.subscription.confirmed": {{Topic: TopicDeliveryRuns, Template: "run.subscription.welcome",
			Channels: chEmail, Recipient: RecipientPayload, Security: true, Enrich: enrichRunSubscription}},
	}
}

// runNotFromWindow: the run was scheduled by staff, not handed over from a pre-order window.
func runNotFromWindow(d map[string]any) bool {
	return !strings.HasPrefix(str(d, "reason"), "PREORDER ")
}

// runCancelledOnItsOwn: a run cancelled by itself (staff, or its minimum), not along with its pre-order window.
func runCancelledOnItsOwn(d map[string]any) bool {
	return !strings.HasPrefix(str(d, "reason"), "Pre-order ")
}

func subscriptionNeedsConfirming(d map[string]any) bool {
	return str(d, "status") == "PENDING_CONFIRMATION" && str(d, "confirmUrl") != "" && str(d, "email") != ""
}

func subscriptionActiveForUser(d map[string]any) bool {
	return str(d, "status") == "ACTIVE" && str(d, "userId") != ""
}

// mapField is d[k] as an object ("" fields for anything else).
func mapField(d map[string]any, k string) map[string]any {
	m, _ := d[k].(map[string]any)
	if m == nil {
		return map[string]any{}
	}
	return m
}

// enrichRun adds the display fields every run message uses and the link of each channel. The links come from the
// event (run link + UTM per channel); the SMS link drops the run id so the text stays short, keeping its UTM.
func enrichRun(d map[string]any, _ Links) {
	nullable(d, "town", "state", "deliveryDay", "windowDisplay", "cutoffDisplay", "feeDisplay", "reason", "runUrl",
		"locationUrl", "whatsappInviteUrl", "handoverCode")
	setDefault(d, "town", "your town")
	d["cutoffText"] = smsSafeDisplay(str(d, "cutoffDisplay"))
	d["windowText"] = smsSafeDisplay(str(d, "windowDisplay"))
	links := mapField(d, "links")
	d["emailUrl"] = firstNonEmptyStr(str(links, "email"), str(d, "runUrl"))
	d["pushUrl"] = firstNonEmptyStr(str(links, "push"), str(d, "runUrl"))
	d["whatsappUrl"] = firstNonEmptyStr(str(links, "whatsapp"), str(d, "runUrl"))
	d["smsUrl"] = shortRunLink(firstNonEmptyStr(str(links, "sms"), str(d, "runUrl")), str(d, "locationUrl"))
	d["dropText"] = "Delivered to your door"
	if pp := mapField(d, "pickupPoint"); str(pp, "name") != "" {
		d["dropText"] = "Pickup at " + str(pp, "name") + ", " + str(pp, "line1") + ", " + str(pp, "city") +
			" (" + smsSafeDisplay(str(pp, "hours")) + ")"
	}
	d["isPickupPoint"] = str(d, "dropMode") == "PICKUP_POINT"
	d["minimumText"] = ""
	if need, ok := integer(d, "minOrders"); ok && need > 1 {
		got, _ := integer(d, "reservedOrders")
		d["minimumText"] = strconv.FormatInt(got, 10) + " of " + strconv.FormatInt(need, 10) + " orders so far"
	}
}

// enrichRunCancelled adds the refund wording (ADR 0005: refunded in full, delivery fee included) to the run fields.
func enrichRunCancelled(d map[string]any, l Links) {
	enrichRun(d, l)
	d["reasonText"] = ""
	if str(d, "reason") == "MINIMUM_NOT_MET" {
		d["reasonText"] = "There weren't enough orders to make the trip."
	}
	d["refundNote"] = "Every order on this run is refunded in full, delivery fee included, to your original payment " +
		"method. If your card was only on hold, the hold is released and nothing is charged."
}

// shortRunLink is the location page with the UTM of the run link: the run id is dropped (the page lists its runs)
// so a text message stays short. Anything unparsable falls back to the full link.
func shortRunLink(full, location string) string {
	u, err := url.Parse(full)
	if err != nil || location == "" {
		return full
	}
	q := u.Query()
	q.Del("run")
	base, err := url.Parse(location)
	if err != nil {
		return full
	}
	base.RawQuery = q.Encode()
	return base.String()
}

// enrichRunSubscription names what was subscribed to for the double opt-in and welcome emails.
func enrichRunSubscription(d map[string]any, _ Links) {
	nullable(d, "town", "confirmUrl", "unsubscribeUrl", "locationUrl", "whatsappInviteUrl")
	setDefault(d, "town", "your town")
	names := map[string]string{"EMAIL": "email", "SMS": "text messages", "PUSH": "app notifications", "WHATSAPP": "WhatsApp"}
	var words []string
	for _, c := range stringList(d, "channels") {
		if n, ok := names[c]; ok {
			words = append(words, n)
		}
	}
	d["channelList"] = humanList(words)
	d["hasWhatsappCommunity"] = str(d, "whatsappInviteUrl") != ""
}
