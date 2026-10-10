package app

import (
	"fmt"
	"slices"
	"sort"
	"strings"
	"time"

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
	TopicPayments           = "payments"
	TopicTrustOps           = "trustOps"
	TopicRetail             = "retail"
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
	TopicPayments:           "at.payment.events",
	TopicTrustOps:           "at.trustops.events",
	TopicRetail:             "at.retail.events",
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

// ContactChangedTypes are identity events after which a user's cached contact details are stale (phone now
// verified, channel switches or locale changed). They send nothing; the ingest drops the user's cache entry.
var ContactChangedTypes = []string{"identity.user.phone-verified", "identity.notification-preferences.updated"}

// SupersededGenericTemplates are notification.requested template keys that typed rules already cover. trust-ops
// publishes notification.requested for claim.opened / claim.decided / claim.withdrawn next to its typed
// trustops.claim.* events (with the seller org id as recipient.id, which identity cannot resolve as a user), so
// those requests are ignored instead of notifying the customer twice.
var SupersededGenericTemplates = map[string]bool{"claim.opened": true, "claim.decided": true, "claim.withdrawn": true}

// RequestedTemplate is a template that a producer asks for with notification.requested instead of a typed event
// rule (the producer chooses template, channels and recipient).
type RequestedTemplate struct {
	Topic    string           // logical topic the producer publishes the request on
	Channels []domain.Channel // channels the producer requests (a seeded template is checked for each)
	// PayloadOnly: the recipient is an external address from the payload (a supplier contact) and is never
	// looked up in identity, even when the payload carries an id.
	PayloadOnly bool
	// Enrich normalises and formats the producer's raw template fields on a copy of data.
	Enrich func(d map[string]any, l Links)
}

// RequestedTemplates are the notification.requested template keys producers are known to send. Other keys are
// still rendered as-is when a template exists.
var RequestedTemplates = map[string]RequestedTemplate{
	// Retail (S225/S243): the PO email to the supplier's purchasing contact, with a signed link to the PO PDF
	// (data.documentUrl) and the PDF as a media attachment reference (data.attachments).
	"retail.purchase_order.sent": {Topic: TopicRetail, Channels: chEmail, PayloadOnly: true, Enrich: enrichPurchaseOrder},
}

var (
	chEmail          = []domain.Channel{domain.ChannelEmail}
	chSMS            = []domain.Channel{domain.ChannelSMS}
	chEmailPush      = []domain.Channel{domain.ChannelEmail, domain.ChannelPush}
	chEmailSMS       = []domain.Channel{domain.ChannelEmail, domain.ChannelSMS}
	chEmailSMSPush   = []domain.Channel{domain.ChannelEmail, domain.ChannelSMS, domain.ChannelPush}
	chSMSPushEmail   = []domain.Channel{domain.ChannelSMS, domain.ChannelPush, domain.ChannelEmail}
	shipmentStatusTo = func(statuses ...string) func(map[string]any) bool {
		return func(d map[string]any) bool { return slices.Contains(statuses, str(d, "to")) }
	}
	// fieldIs / fieldNot select rule variants on a payload field (purpose, reason, status).
	fieldIs = func(k string, vals ...string) func(map[string]any) bool {
		return func(d map[string]any) bool { return slices.Contains(vals, str(d, k)) }
	}
	fieldNot = func(k string, vals ...string) func(map[string]any) bool {
		return func(d map[string]any) bool { return !slices.Contains(vals, str(d, k)) }
	}
)

// DefaultEventRules are the platform's event → notification mappings, keyed by event type. Every type and topic is
// checked against contracts/asyncapi/events.yaml by TestRulesMatchAsyncAPIContract.
var DefaultEventRules = map[string][]EventRule{
	// ---- identity (at.identity.events) ----
	"identity.verification.requested": {
		{Topic: TopicIdentity, Template: "identity.verification", Channels: chEmail, Recipient: RecipientPayload,
			IDField: "userId", Security: true, When: fieldNot("purpose", "PHONE_VERIFICATION")},
		// Phone verification (S234): sms to the number being verified, "Your AfroTransact code is …".
		{Topic: TopicIdentity, Template: "identity.verification.phone", Channels: chSMS, Recipient: RecipientPayload,
			IDField: "userId", Security: true, When: fieldIs("purpose", "PHONE_VERIFICATION")},
	},
	// The invitee usually has no account: recipient.email from the payload only, never looked up.
	"identity.invitation.created": {{Topic: TopicIdentity, Template: "org.invitation", Channels: chEmail,
		Recipient: RecipientPayload, Security: true, Enrich: enrichInvitation}},
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
	// Reason SUSPENDED / REINSTATED comes from a Trust and Safety suspension, not Stripe: own wording, email only
	// (trustops.seller.suspended already sends the email + sms announcement).
	"seller.payouts.paused": {
		{Topic: TopicSellerCompliance, Template: "seller.payouts.paused", Channels: chEmailSMS,
			Recipient: RecipientOrgOwners, IDField: "orgId", When: fieldNot("reason", "SUSPENDED"), Enrich: enrichEnforcement},
		{Topic: TopicSellerCompliance, Template: "seller.payouts.paused.suspended", Channels: chEmail,
			Recipient: RecipientOrgOwners, IDField: "orgId", When: fieldIs("reason", "SUSPENDED"), Enrich: enrichSuspensionHold},
	},
	"seller.payouts.resumed": {
		{Topic: TopicSellerCompliance, Template: "seller.payouts.resumed", Channels: chEmail,
			Recipient: RecipientOrgOwners, IDField: "orgId", When: fieldNot("reason", "REINSTATED"), Enrich: enrichEnforcement},
		{Topic: TopicSellerCompliance, Template: "seller.payouts.resumed.reinstated", Channels: chEmail,
			Recipient: RecipientOrgOwners, IDField: "orgId", When: fieldIs("reason", "REINSTATED"), Enrich: enrichSuspensionHold},
	},
	"seller.listings.hidden": {
		{Topic: TopicSellerCompliance, Template: "seller.listings.hidden", Channels: chEmailSMS,
			Recipient: RecipientOrgOwners, IDField: "orgId", When: fieldNot("reason", "SUSPENDED"), Enrich: enrichEnforcement},
		{Topic: TopicSellerCompliance, Template: "seller.listings.hidden.suspended", Channels: chEmail,
			Recipient: RecipientOrgOwners, IDField: "orgId", When: fieldIs("reason", "SUSPENDED"), Enrich: enrichSuspensionHold},
	},
	"seller.listings.restored": {
		{Topic: TopicSellerCompliance, Template: "seller.listings.restored", Channels: chEmail,
			Recipient: RecipientOrgOwners, IDField: "orgId", When: fieldNot("reason", "REINSTATED"), Enrich: enrichEnforcement},
		{Topic: TopicSellerCompliance, Template: "seller.listings.restored.reinstated", Channels: chEmail,
			Recipient: RecipientOrgOwners, IDField: "orgId", When: fieldIs("reason", "REINSTATED"), Enrich: enrichSuspensionHold},
	},

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
	"booking.balance_unpaid": {{Topic: TopicBookings, Template: "booking.balance_unpaid", Channels: chEmailPush,
		Recipient: RecipientPayload, Enrich: enrichBalanceUnpaid}},

	// ---- payments (at.payment.events): off-session charge needs the customer to authenticate (SCA) ----
	"payments.intent.action_required": {{Topic: TopicPayments, Template: "payment.authentication_required",
		Channels: chEmailPush, Recipient: RecipientUser, IDField: "customerId", Enrich: enrichActionRequired}},

	// ---- trust & safety (at.trustops.events), S230 ----
	"trustops.claim.opened": {
		{Topic: TopicTrustOps, Template: "claim.opened", Channels: chEmailPush, Recipient: RecipientUser,
			IDField: "customerId", Enrich: enrichClaim},
		// The seller's response window opens with the claim (dueAt = response deadline); the reminder before the
		// deadline comes from trustops.claim.response_due_soon.
		{Topic: TopicTrustOps, Template: "claim.seller_response_due", Channels: chEmailSMS, Recipient: RecipientOrgOwners,
			IDField: "sellerOrgId", When: fieldIs("status", "AWAITING_SELLER"), Enrich: enrichClaim},
	},
	"trustops.claim.decided": {
		{Topic: TopicTrustOps, Template: "claim.decided", Channels: chEmailPush, Recipient: RecipientUser,
			IDField: "customerId", Enrich: enrichClaim},
		{Topic: TopicTrustOps, Template: "claim.decided.seller", Channels: chEmail, Recipient: RecipientOrgOwners,
			IDField: "sellerOrgId", Enrich: enrichClaim},
	},
	// S237: reminder 24h before the seller's response deadline, and notice when the window lapsed.
	"trustops.claim.response_due_soon": {{Topic: TopicTrustOps, Template: "claim.seller_response_reminder",
		Channels: chEmailSMS, Recipient: RecipientOrgOwners, IDField: "sellerOrgId", Enrich: enrichClaim}},
	"trustops.claim.response_lapsed": {{Topic: TopicTrustOps, Template: "claim.seller_response_lapsed",
		Channels: chEmail, Recipient: RecipientOrgOwners, IDField: "sellerOrgId", Enrich: enrichClaim}},
	"trustops.claim.withdrawn": {{Topic: TopicTrustOps, Template: "claim.withdrawn", Channels: chEmail,
		Recipient: RecipientOrgOwners, IDField: "sellerOrgId", Enrich: enrichClaim}},
	"trustops.moderation.decided": {
		{Topic: TopicTrustOps, Template: "listing.rejected", Channels: chEmail, Recipient: RecipientOrgOwners,
			IDField: "sellerOrgId", When: moderationRejected("NEW_LISTING"), Enrich: enrichModeration},
		{Topic: TopicTrustOps, Template: "listing.taken_down", Channels: chEmailSMS, Recipient: RecipientOrgOwners,
			IDField: "sellerOrgId", When: moderationRejected("LIVE_LISTING"), Enrich: enrichModeration},
	},
	"trustops.seller.suspended": {{Topic: TopicTrustOps, Template: "seller.suspended", Channels: chEmailSMS,
		Recipient: RecipientOrgOwners, IDField: "orgId", Enrich: enrichSellerEnforcement}},
	"trustops.seller.reinstated": {{Topic: TopicTrustOps, Template: "seller.reinstated", Channels: chEmail,
		Recipient: RecipientOrgOwners, IDField: "orgId", Enrich: enrichSellerEnforcement}},

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

// portal sets programName and portalUrl from an organisation kind (S215: BOTH uses Seller Center).
func portal(d map[string]any, l Links, kind domain.OrgKind) {
	d["programName"] = kind.ProgramName()
	d["portalUrl"] = l.SellerCenter
	if kind.UsesProviderHub() {
		d["portalUrl"] = l.ProviderHub
	}
}

func enrichApplication(d map[string]any, l Links) {
	portal(d, l, domain.ParseOrgKind(str(d, "kind")))
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

// humanCode makes an enum readable: "ITEM_NOT_RECEIVED" → "item not received".
func humanCode(s string) string {
	return strings.TrimSpace(strings.ToLower(strings.NewReplacer("_", " ", ".", " ").Replace(s)))
}

// nullable normalises optional payload fields to "" so templates never render "<no value>" for a JSON null.
func nullable(d map[string]any, keys ...string) {
	for _, k := range keys {
		d[k] = str(d, k)
	}
}

func enrichInvitation(d map[string]any, l Links) {
	nullable(d, "inviterName")
	portal(d, l, domain.ParseOrgKind(str(d, "orgKind")))
	setDefault(d, "inviterName", "A teammate")
	setDefault(d, "orgName", "your team")
	if _, ok := integer(d, "expiresInDays"); !ok {
		d["expiresInDays"] = 7
	}
}

// suspensionNote splits seller-service's "[REASON_CODE] note" suspension note into a readable reason and the note.
func suspensionNote(note string) (reason, rest string) {
	note = strings.TrimSpace(note)
	if strings.HasPrefix(note, "[") {
		if end := strings.Index(note, "]"); end > 0 {
			return humanCode(note[1:end]), strings.TrimSpace(note[end+1:])
		}
	}
	return "", note
}

func enrichSuspensionHold(d map[string]any, l Links) {
	reason, note := suspensionNote(str(d, "note"))
	if reason == "" {
		reason = "a Trust and Safety review"
	}
	d["reasonText"], d["note"] = reason, note
	d["actionUrl"] = join(l.SellerCenter, "/account-health")
}

func enrichSellerEnforcement(d map[string]any, l Links) {
	nullable(d, "note")
	d["reasonText"] = humanCode(str(d, "reason"))
	setDefault(d, "reasonText", "a Trust and Safety review")
	d["actionUrl"] = join(l.SellerCenter, "/account-health")
}

func enrichBalanceUnpaid(d map[string]any, _ Links) {
	d["balance"] = moneyField(d, "balanceMinor")
	if d["balance"] == "" {
		price, _ := integer(d, "priceMinor")
		deposit, _ := integer(d, "depositMinor")
		cur := str(d, "currency")
		if cur == "" {
			cur = "USD"
		}
		d["balance"] = money(price-deposit, cur)
	}
}

func enrichActionRequired(d map[string]any, l Links) {
	d["amount"] = moneyField(d, "amountMinor")
	ref := str(d, "orderId")
	d["reference"] = ref
	d["actionUrl"] = join(l.Storefront, "/orders")
	if strings.HasPrefix(ref, "BK-") {
		d["actionUrl"] = join(l.Storefront, "/bookings/"+ref)
	}
}

func enrichClaim(d map[string]any, l Links) {
	d["claimType"] = humanCode(str(d, "type"))
	nullable(d, "orderNumber")
	setDefault(d, "orderNumber", str(d, "orderId"))
	d["amount"] = moneyField(d, "amountMinor")
	d["dueDate"] = friendlyDate(str(d, "dueAt"))
	setDefault(d, "dueDate", "within 3 days")
	d["claimsUrl"] = join(l.Storefront, "/orders")
	d["actionUrl"] = join(l.SellerCenter, "/orders")
	refund := moneyField(d, "refundMinor")
	if refund == "" {
		refund = moneyField(d, "amountMinor")
	}
	switch {
	case str(d, "outcome") == "REFUND" || str(d, "status") == "RESOLVED_REFUND":
		d["outcomeText"] = "Good news: we're refunding " + refund + " to your original payment method. It can take 5–10 business days to show."
		d["sellerOutcomeText"] = "We refunded the customer " + refund + " for this order."
	default:
		d["outcomeText"] = "After reviewing the details and evidence, we couldn't approve this claim."
		d["sellerOutcomeText"] = "The claim was closed without a refund."
	}
}

// moderationRejected matches a trust-ops rejection of a new submission (NEW_LISTING) or a take-down of a live
// listing (LIVE_LISTING). Older events without itemType count as new listings.
func moderationRejected(itemType string) func(map[string]any) bool {
	return func(d map[string]any) bool {
		if str(d, "status") != "REJECTED" {
			return false
		}
		t := str(d, "itemType")
		if t == "" {
			t = "NEW_LISTING"
		}
		return t == itemType
	}
}

func enrichModeration(d map[string]any, l Links) {
	nullable(d, "note")
	setDefault(d, "title", "your listing")
	d["reasonText"] = humanCode(str(d, "reasonCode"))
	setDefault(d, "reasonText", "our listing policies")
	d["actionUrl"] = join(l.SellerCenter, "/listings")
}

// enrichPurchaseOrder fills retail's supplier PO email: JSON nulls in optional fields become "" (the template skips
// empty rows), the total falls back to totalMinor + currency, and the link expiry gets a readable date.
func enrichPurchaseOrder(d map[string]any, _ Links) {
	nullable(d, "contactName", "supplierName", "orderDate", "deliverBy", "incoterm", "paymentTerms", "total",
		"documentExpiresAt")
	setDefault(d, "contactName", "there")
	setDefault(d, "supplierName", "your company")
	setDefault(d, "total", moneyField(d, "totalMinor"))
	d["documentExpiresOn"] = ""
	if t, err := time.Parse(time.RFC3339, str(d, "documentExpiresAt")); err == nil {
		d["documentExpiresOn"] = t.UTC().Format("Jan 2, 2006")
	}
}
