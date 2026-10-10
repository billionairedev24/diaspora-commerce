package app

import (
	"fmt"
	"slices"
	"sort"
	"strings"

	"github.com/billionairedev24/diaspora-commerce/go/services/notifications/internal/domain"
)

// ConsumerGroup is the Kafka consumer group and inbox consumer name.
const ConsumerGroup = "notifications"

// Logical topic names (config kafka.topics.<name>). Rules name these, so an environment can rename a topic without a
// code change.
const (
	TopicIdentity           = "identity"
	TopicOrders             = "orders"
	TopicSellerApplications = "sellerApplications"
	TopicSellerCompliance   = "sellerCompliance"
	TopicPayouts            = "payouts"
	TopicBookings           = "bookings"
	TopicContent            = "content"
	TopicInventory          = "inventory"
)

// DefaultTopics are the AsyncAPI channel addresses (contracts/asyncapi/events.yaml) for each logical topic.
var DefaultTopics = map[string]string{
	TopicIdentity:           "at.identity.events",
	TopicOrders:             "at.order.events",
	TopicSellerApplications: "at.seller-application.events",
	TopicSellerCompliance:   "at.seller-compliance.events",
	TopicPayouts:            "at.payout.events",
	TopicBookings:           "at.bookings.events",
	TopicContent:            "at.content.events",
	TopicInventory:          "at.inventory.events",
}

// ResolveTopics overlays configured addresses on DefaultTopics. Unknown names and empty addresses are errors, so a
// typo in config/<env>.yaml fails at startup instead of silently dropping a topic.
func ResolveTopics(overrides map[string]string) (map[string]string, error) {
	out := make(map[string]string, len(DefaultTopics))
	for k, v := range DefaultTopics {
		out[k] = v
	}
	for k, v := range overrides {
		if _, ok := DefaultTopics[k]; !ok {
			return nil, fmt.Errorf("kafka.topics: unknown topic %q", k)
		}
		if strings.TrimSpace(v) == "" {
			return nil, fmt.Errorf("kafka.topics.%s: empty address", k)
		}
		out[k] = v
	}
	return out, nil
}

// TopicAddresses returns the distinct addresses to subscribe to, sorted.
func TopicAddresses(topics map[string]string) []string {
	out := make([]string, 0, len(topics))
	for _, v := range topics {
		if !slices.Contains(out, v) {
			out = append(out, v)
		}
	}
	sort.Strings(out)
	return out
}

// RecipientSource says where an event's recipients come from.
type RecipientSource string

// Recipient sources.
const (
	// RecipientPayload uses the payload's recipient block (or top-level email/phone); IDField, when set, names the
	// user id. Missing addresses are filled from identity when a user id is known.
	RecipientPayload RecipientSource = "payload"
	// RecipientUser resolves the identity user id in IDField.
	RecipientUser RecipientSource = "user"
	// RecipientOrgOwners resolves every owner of the seller/provider organisation id in IDField.
	RecipientOrgOwners RecipientSource = "orgOwners"
)

// EventRule maps one event type to one template. An event type may have several rules (e.g. one per shipment
// status); every rule whose When matches produces notifications.
type EventRule struct {
	Topic     string // logical topic (DefaultTopics key) the event type is published on
	Template  string
	Channels  []domain.Channel
	Recipient RecipientSource
	IDField   string
	// Security messages (one-time codes) skip identity lookups and the account's channel switches.
	Security bool
	// PayloadChannels honours the payload's "channels" list (seller compliance: email, sms, in_app → push).
	PayloadChannels bool
	// When filters events (nil = always).
	When func(d map[string]any) bool
	// Enrich adds formatted template fields (amounts, dates, links) to a copy of the payload.
	Enrich func(d map[string]any, l Links)
}

// GenericEventType lets any producer request a templated notification explicitly, on any consumed topic.
const GenericEventType = "notification.requested"

// AvailabilityChangedType is inventory's availability event; it drives back-in-stock alerts.
const AvailabilityChangedType = "inventory.availability.changed"

var (
	chEmail          = []domain.Channel{domain.ChannelEmail}
	chEmailPush      = []domain.Channel{domain.ChannelEmail, domain.ChannelPush}
	chEmailSMS       = []domain.Channel{domain.ChannelEmail, domain.ChannelSMS}
	chEmailSMSPush   = []domain.Channel{domain.ChannelEmail, domain.ChannelSMS, domain.ChannelPush}
	chSMSPushEmail   = []domain.Channel{domain.ChannelSMS, domain.ChannelPush, domain.ChannelEmail}
	shipmentStatusTo = func(statuses ...string) func(map[string]any) bool {
		return func(d map[string]any) bool { return slices.Contains(statuses, str(d, "to")) }
	}
)

// DefaultEventRules are the platform's event → notification mappings, keyed by event type. Every type and topic is
// checked against contracts/asyncapi/events.yaml by TestRulesMatchAsyncAPIContract.
var DefaultEventRules = map[string][]EventRule{
	// ---- identity (at.identity.events) ----
	"identity.verification.requested": {{Topic: TopicIdentity, Template: "identity.verification", Channels: chEmail,
		Recipient: RecipientPayload, IDField: "userId", Security: true}},
	"identity.user.registered": {{Topic: TopicIdentity, Template: "account.welcome", Channels: chEmail,
		Recipient: RecipientPayload, IDField: "userId",
		When:   func(d map[string]any) bool { return str(d, "intent") != "STAFF" },
		Enrich: enrichWelcome}},

	// ---- orders (at.order.events) ----
	"orders.order.placed": {{Topic: TopicOrders, Template: "order.confirmed", Channels: chEmailSMSPush,
		Recipient: RecipientUser, IDField: "customerId", Enrich: enrichOrderPlaced}},
	"orders.order.cancelled": {{Topic: TopicOrders, Template: "order.cancelled", Channels: chEmailPush,
		Recipient: RecipientUser, IDField: "customerId",
		When: func(d map[string]any) bool { return boolean(d, "wasPlaced") }, Enrich: enrichOrder}},
	"orders.shipment.status_changed": {
		{Topic: TopicOrders, Template: "shipment.shipped", Channels: chEmailPush, Recipient: RecipientUser,
			IDField: "customerId", When: shipped, Enrich: enrichShipment},
		{Topic: TopicOrders, Template: "shipment.out_for_delivery", Channels: chSMSPushEmail, Recipient: RecipientUser,
			IDField: "customerId", When: shipmentStatusTo("out_for_delivery"), Enrich: enrichShipment},
		{Topic: TopicOrders, Template: "shipment.ready_for_pickup", Channels: chEmailSMSPush, Recipient: RecipientUser,
			IDField: "customerId", Enrich: enrichShipment,
			When: func(d map[string]any) bool { return str(d, "to") == "ready" && str(d, "deliveryMethod") == "PICKUP" }},
		{Topic: TopicOrders, Template: "shipment.delivery_failed", Channels: chEmailSMSPush, Recipient: RecipientUser,
			IDField: "customerId", When: shipmentStatusTo("delivery_failed"), Enrich: enrichShipment},
	},
	"orders.shipment.delivered": {{Topic: TopicOrders, Template: "shipment.delivered", Channels: chEmailPush,
		Recipient: RecipientUser, IDField: "customerId", Enrich: enrichOrder}},
	"orders.shipment.cancelled": {{Topic: TopicOrders, Template: "shipment.cancelled", Channels: chEmailPush,
		Recipient: RecipientUser, IDField: "customerId", Enrich: enrichShipmentCancelled}},

	// ---- seller onboarding (at.seller-application.events) ----
	"seller.application.submitted": {{Topic: TopicSellerApplications, Template: "seller.application.submitted",
		Channels: chEmail, Recipient: RecipientUser, IDField: "applicantUserId", Enrich: enrichApplication}},
	"seller.application.info_requested": {{Topic: TopicSellerApplications, Template: "seller.application.info_requested",
		Channels: chEmailSMS, Recipient: RecipientUser, IDField: "applicantUserId", Enrich: enrichApplication}},
	"seller.application.approved": {{Topic: TopicSellerApplications, Template: "seller.application.approved",
		Channels: chEmailSMSPush, Recipient: RecipientUser, IDField: "applicantUserId", Enrich: enrichApplication}},
	"seller.application.rejected": {{Topic: TopicSellerApplications, Template: "seller.application.rejected",
		Channels: chEmail, Recipient: RecipientUser, IDField: "applicantUserId", Enrich: enrichApplication}},

	// ---- seller compliance (at.seller-compliance.events), sent to every org owner ----
	"seller.compliance.reminder": {{Topic: TopicSellerCompliance, Template: "seller.compliance.reminder",
		Channels: chEmailSMS, Recipient: RecipientOrgOwners, IDField: "orgId", PayloadChannels: true,
		Enrich: enrichComplianceReminder}},
	"seller.compliance.changed": {{Topic: TopicSellerCompliance, Template: "seller.compliance.action_needed",
		Channels: chEmailSMS, Recipient: RecipientOrgOwners, IDField: "orgId", PayloadChannels: true,
		When: complianceNeedsAction, Enrich: enrichComplianceChanged}},
	"seller.payouts.paused": {{Topic: TopicSellerCompliance, Template: "seller.payouts.paused", Channels: chEmailSMS,
		Recipient: RecipientOrgOwners, IDField: "orgId", Enrich: enrichEnforcement}},
	"seller.payouts.resumed": {{Topic: TopicSellerCompliance, Template: "seller.payouts.resumed", Channels: chEmail,
		Recipient: RecipientOrgOwners, IDField: "orgId", Enrich: enrichEnforcement}},
	"seller.listings.hidden": {{Topic: TopicSellerCompliance, Template: "seller.listings.hidden", Channels: chEmailSMS,
		Recipient: RecipientOrgOwners, IDField: "orgId", Enrich: enrichEnforcement}},
	"seller.listings.restored": {{Topic: TopicSellerCompliance, Template: "seller.listings.restored", Channels: chEmail,
		Recipient: RecipientOrgOwners, IDField: "orgId", Enrich: enrichEnforcement}},

	// ---- payouts (at.payout.events) ----
	"payments.payout.paid": {{Topic: TopicPayouts, Template: "payout.paid", Channels: chEmailPush,
		Recipient: RecipientOrgOwners, IDField: "orgId", Enrich: enrichPayout}},
	"payments.payout.failed": {{Topic: TopicPayouts, Template: "payout.failed", Channels: chEmailSMS,
		Recipient: RecipientOrgOwners, IDField: "orgId", Enrich: enrichPayout}},

	// ---- bookings (at.bookings.events; payloads carry the customer's recipient block + template fields) ----
	"booking.confirmed": {{Topic: TopicBookings, Template: "booking.confirmed", Channels: chEmailSMSPush,
		Recipient: RecipientPayload}},
	"booking.cancelled": {{Topic: TopicBookings, Template: "booking.cancelled", Channels: chEmailSMSPush,
		Recipient: RecipientPayload, Enrich: enrichBookingCancelled}},
	"booking.declined": {{Topic: TopicBookings, Template: "booking.declined", Channels: chEmailPush,
		Recipient: RecipientPayload}},
	"booking.rescheduled": {{Topic: TopicBookings, Template: "booking.rescheduled", Channels: chEmailSMSPush,
		Recipient: RecipientPayload}},

	// ---- content (at.content.events) ----
	"content.career-application.received": {{Topic: TopicContent, Template: "careers.application_received",
		Channels: chEmail, Recipient: RecipientPayload,
		Enrich: func(d map[string]any, _ Links) { setDefault(d, "firstName", firstWord(str(d, "name"))) }}},
	"content.newsletter.confirmation_requested": {{Topic: TopicContent, Template: "newsletter.confirm",
		Channels: chEmail, Recipient: RecipientPayload}},
}

func setDefault(d map[string]any, k, v string) {
	if str(d, k) == "" && v != "" {
		d[k] = v
	}
}

func enrichWelcome(d map[string]any, l Links) {
	d["startUrl"] = l.Storefront
	switch str(d, "intent") {
	case "SELL":
		d["startUrl"] = l.SellerCenter
	case "PROVIDE":
		d["startUrl"] = l.ProviderHub
	}
}

func enrichOrder(d map[string]any, l Links) {
	d["orderUrl"] = join(l.Storefront, "/orders")
}

func enrichOrderPlaced(d map[string]any, l Links) {
	enrichOrder(d, l)
	d["total"] = moneyField(d, "totalMinor")
	items, earliest := int64(0), ""
	for _, s := range objects(d, "shipments") {
		for _, line := range objects(s, "lines") {
			q, _ := integer(line, "quantity")
			items += q
		}
		if p := str(s, "promisedBy"); p != "" && (earliest == "" || p < earliest) {
			earliest = p
		}
	}
	d["itemCount"] = items
	d["estimatedDelivery"] = "soon"
	if f := friendlyDate(earliest); f != "" {
		d["estimatedDelivery"] = "by " + f
	}
}

// shipped fires once per shipment when it leaves the seller: picked_up, or in_transit without a pickup scan first.
func shipped(d map[string]any) bool {
	if str(d, "deliveryMethod") == "PICKUP" {
		return false
	}
	switch str(d, "to") {
	case "picked_up":
		return true
	case "in_transit":
		from := str(d, "from")
		return from != "picked_up" && from != "in_transit"
	default:
		return false
	}
}

func enrichShipment(d map[string]any, l Links) {
	enrichOrder(d, l)
	d["trackingLine"] = ""
	if c, n := str(d, "carrier"), str(d, "trackingNumber"); n != "" {
		d["trackingLine"] = strings.TrimSpace(strings.ToUpper(c) + " tracking: " + n)
	}
	d["eta"] = "today"
	if f := friendlyDate(str(d, "promisedBy")); f != "" && str(d, "to") != "out_for_delivery" {
		d["eta"] = f
	}
}

func enrichShipmentCancelled(d map[string]any, l Links) {
	enrichOrder(d, l)
	if boolean(d, "captured") {
		d["refundNote"] = "We're refunding " + moneyField(d, "totalMinor") + " to your original payment method. It can take 5–10 business days to show."
	} else {
		d["refundNote"] = "You haven't been charged for these items."
	}
}

var programNames = map[string]string{"SELLER": "Seller Center", "PROVIDER": "Provider Hub"}

func enrichApplication(d map[string]any, l Links) {
	kind := str(d, "kind")
	d["programName"] = programNames["SELLER"]
	d["portalUrl"] = l.SellerCenter
	if kind == "PROVIDER" {
		d["programName"] = programNames["PROVIDER"]
		d["portalUrl"] = l.ProviderHub
	}
	setDefault(d, "displayName", "your business")
	d["requestedItemsText"] = humanList(stringList(d, "requestedItems"))
	if d["requestedItemsText"] == "" {
		d["requestedItemsText"] = "a few more details"
	}
	d["note"] = str(d, "note")
}

func deadline(d map[string]any) string {
	if f := friendlyDate(str(d, "currentDeadline")); f != "" {
		return f
	}
	return "as soon as you can"
}

func enrichComplianceReminder(d map[string]any, l Links) {
	d["itemsText"] = humanList(stringList(d, "openItems"))
	setDefault(d, "itemsText", "a few account details")
	d["deadline"] = deadline(d)
	d["actionUrl"] = join(l.SellerCenter, "/payouts")
}

func complianceNeedsAction(d map[string]any) bool {
	if len(stringList(d, "newItems")) > 0 {
		return true
	}
	st := str(d, "status")
	return (st == "RESTRICTED_SOON" || st == "RESTRICTED") && st != str(d, "previousStatus")
}

func enrichComplianceChanged(d map[string]any, l Links) {
	items := stringList(d, "newItems")
	if len(items) == 0 {
		items = append(stringList(d, "pastDue"), stringList(d, "currentlyDue")...)
	}
	d["itemsText"] = humanList(items)
	setDefault(d, "itemsText", "a few account details")
	d["deadline"] = deadline(d)
	d["actionUrl"] = join(l.SellerCenter, "/payouts")
}

func enrichEnforcement(d map[string]any, l Links) {
	d["itemsText"] = humanList(stringList(d, "pastDue"))
	setDefault(d, "itemsText", "your Stripe account details")
	d["actionUrl"] = join(l.SellerCenter, "/payouts")
}

func enrichPayout(d map[string]any, l Links) {
	d["amount"] = moneyField(d, "amountMinor")
	d["actionUrl"] = join(l.SellerCenter, "/payouts")
}

func enrichBookingCancelled(d map[string]any, _ Links) {
	d["refundNote"] = ""
	if n, ok := integer(d, "refundedMinor"); ok && n > 0 {
		d["refundNote"] = "We're refunding " + moneyField(d, "refundedMinor") + " to your original payment method."
	}
}
