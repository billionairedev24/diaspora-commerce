package app

import (
	"net/url"
	"strconv"
	"strings"
	"time"

	"github.com/billionairedev24/diaspora-commerce/go/services/notifications/internal/domain"
	"github.com/billionairedev24/diaspora-commerce/go/services/notifications/internal/port"
)

// Pre-orders (S270–S275; docs/spikes/preorder-design-implementation.md §3–4). delivery-runs' scheduler publishes
// preorder.comms.due {windowId, kind} at opening (ANNOUNCE), closes − 48 h (REMIND_48H), closes − 6 h (LAST_CALL_6H),
// closing (CLOSED) and on delivery day (OUT_FOR_DELIVERY, from the driver events). Announcement, reminder and last
// call go to the window's audience (area subscribers with consent on the channel plus past buyers; the waitlist gets
// its own "pre-order is open" email); closed and out-for-delivery go to the window's buyers (orders[]). Fees are
// the window's effective fees (doorFeeMinor / pickupFeeMinor from the payment settings or the window's overrides),
// never literal amounts. Every link carries ?src=<channel>&po=<CODE>&cr=<slot>; SMS stay in one GSM-7 segment.

// Pre-order comms kinds (delivery-runs CommKind) and the cr slot their links carry.
const (
	PreorderAnnounce       = "ANNOUNCE"
	PreorderRemind48h      = "REMIND_48H"
	PreorderLastCall6h     = "LAST_CALL_6H"
	PreorderClosed         = "CLOSED"
	PreorderOutForDelivery = "OUT_FOR_DELIVERY"
)

var chSMSPush = []domain.Channel{domain.ChannelSMS, domain.ChannelPush}

// preorderCommsRules map preorder.comms.due onto the templates of each kind.
var preorderCommsRules = []EventRule{
	{Topic: TopicDeliveryRuns, Template: "preorder.announce", Channels: chEmailSMSPush, Recipient: RecipientAudience,
		IDField: "windowId", Segment: port.AudienceSubscribers, When: fieldIs("kind", PreorderAnnounce), Enrich: preorderComms("announce")},
	{Topic: TopicDeliveryRuns, Template: "preorder.waitlist.opened", Channels: chEmail, Recipient: RecipientAudience,
		IDField: "windowId", Segment: port.AudienceWaitlist, When: fieldIs("kind", PreorderAnnounce), Enrich: preorderComms("waitlist")},
	{Topic: TopicDeliveryRuns, Template: "preorder.reminder", Channels: chEmailPush, Recipient: RecipientAudience,
		IDField: "windowId", Segment: port.AudienceSubscribers, When: fieldIs("kind", PreorderRemind48h), Enrich: preorderComms("remind")},
	{Topic: TopicDeliveryRuns, Template: "preorder.last_call", Channels: chSMSPush, Recipient: RecipientAudience,
		IDField: "windowId", Segment: port.AudienceSubscribers, When: fieldIs("kind", PreorderLastCall6h), Enrich: preorderComms("last")},
	{Topic: TopicDeliveryRuns, Template: "preorder.closed", Channels: chEmail, Recipient: RecipientCustomers,
		IDField: "orders", When: fieldIs("kind", PreorderClosed), Enrich: preorderComms("closed")},
	{Topic: TopicDeliveryRuns, Template: "preorder.out_for_delivery", Channels: chSMSPush, Recipient: RecipientCustomers,
		IDField: "orders", When: fieldIs("kind", PreorderOutForDelivery), Enrich: preorderComms("ofd")},
}

// preorderLatePaymentRule texts the pay-now link of a late add (payment PAYMENT_LINK) to the customer: AfroTransact's own
// pay page (/orders/<number>/pay?token=…), never a Stripe-hosted page; a late add
// charged to the saved card is confirmed by orders.order.placed like any pre-order.
var preorderLatePaymentRule = EventRule{Topic: TopicDeliveryRuns, Template: "preorder.late_order.payment_link", Channels: chSMS,
	Recipient: RecipientPayload, IDField: "customerId", Enrich: enrichPreorderLatePayment,
	When: func(d map[string]any) bool { return str(d, "payment") == "PAYMENT_LINK" && str(d, "paymentUrl") != "" }}

// smsSafeDisplay keeps display strings in the GSM-7 alphabet: "Sat, Oct 17 · 10:00 AM" → "Sat, Oct 17 at 10:00 AM",
// dashes and curly quotes → ASCII.
func smsSafeDisplay(s string) string {
	return strings.NewReplacer(" · ", " at ", "·", "-", "–", "-", "—", "-", "‘", "'", "’", "'", "“", `"`, "”", `"`, "…", "...").Replace(s)
}

// friendlyDateTime renders an RFC 3339 timestamp as "Wed, Oct 21 11:59 PM" (UTC; producers send local display strings).
func friendlyDateTime(s string) string {
	t, err := time.Parse(time.RFC3339, s)
	if err != nil {
		return friendlyDate(s)
	}
	return t.UTC().Format("Mon, Jan 2 3:04 PM")
}

// withAttribution appends ?src=&po=&cr= (or &…) to a link.
func withAttribution(link, src, code, cr string) string {
	if link == "" {
		return ""
	}
	sep := "?"
	if strings.Contains(link, "?") {
		sep = "&"
	}
	return link + sep + "src=" + url.QueryEscape(src) + "&po=" + url.QueryEscape(code) + "&cr=" + url.QueryEscape(cr)
}

// preorderLinks sets emailUrl, smsUrl and pushUrl: the storefront's pre-order page for the window code, per channel.
func preorderLinks(d map[string]any, l Links, cr string) {
	base := join(l.Storefront, "/pre-orders")
	code := str(d, "code")
	for _, ch := range []string{"email", "sms", "push"} {
		d[ch+"Url"] = withAttribution(base, ch, code, cr)
	}
}

func boolOr(d map[string]any, k string, def bool) bool {
	if b, ok := d[k].(bool); ok {
		return b
	}
	return def
}

// preorderFees formats the window's effective fees: doorFee ("$7.99", "" without door delivery), pickupFee ("free",
// "$2.00", "" without pickup) and fulfilmentText for the copy.
func preorderFees(d map[string]any) {
	nullable(d, "pickupWindow")
	d["pickupWindow"] = smsSafeDisplay(str(d, "pickupWindow"))
	d["doorFee"], d["pickupFee"] = "", ""
	_, hasDoor := integer(d, "doorFeeMinor")
	_, hasPickup := integer(d, "pickupFeeMinor")
	if boolOr(d, "door", hasDoor) && hasDoor {
		d["doorFee"] = moneyField(d, "doorFeeMinor")
	}
	if boolOr(d, "pickup", hasPickup) && hasPickup {
		if n, _ := integer(d, "pickupFeeMinor"); n == 0 {
			d["pickupFee"] = "free"
		} else {
			d["pickupFee"] = moneyField(d, "pickupFeeMinor")
		}
	}
	door, pickup := str(d, "doorFee"), str(d, "pickupFee")
	pickupText := ""
	switch pickup {
	case "":
	case "free":
		pickupText = "free pickup"
	default:
		pickupText = "pickup for " + pickup
	}
	switch {
	case door != "" && pickupText != "":
		d["fulfilmentText"] = "door delivery for " + door + " or " + pickupText
	case door != "":
		d["fulfilmentText"] = "door delivery for " + door
	default:
		d["fulfilmentText"] = pickupText
	}
}

// preorderWindow normalises a window's display fields.
func preorderWindow(d map[string]any) {
	nullable(d, "name", "code", "where", "ribbon", "headline", "smsCopy", "opensDisplay", "closesDisplay", "deliversDisplay",
		"deliveryWindow", "unsubscribeUrl", "handoverCode")
	setDefault(d, "name", "AfroTransact pre-order")
	setDefault(d, "where", "your area")
	setDefault(d, "opensDisplay", friendlyDateTime(str(d, "opensAt")))
	setDefault(d, "closesDisplay", friendlyDateTime(str(d, "closesAt")))
	setDefault(d, "deliversDisplay", friendlyDate(str(d, "deliversOn")))
	for _, k := range []string{"name", "where", "opensDisplay", "closesDisplay", "deliversDisplay", "deliveryWindow"} {
		d[k] = smsSafeDisplay(str(d, k))
	}
	preorderFees(d)
}

// preorderComms enriches preorder.comms.due for one kind (cr = the kind's slot in links).
func preorderComms(cr string) func(map[string]any, Links) {
	return func(d map[string]any, l Links) {
		preorderWindow(d)
		preorderLinks(d, l, cr)
		// The window's SMS copy (≤ 160, GSM-7, set in the wizard); {link} becomes the tracked SMS link.
		sms := strings.TrimSpace(str(d, "smsCopy"))
		if sms == "" {
			sms = "AfroTransact: " + str(d, "name") + " is open until " + str(d, "closesDisplay") + ". {link} Reply STOP to opt out"
		}
		if !strings.Contains(strings.ToUpper(sms), "STOP") {
			sms = strings.TrimRight(sms, ". ") + ". Reply STOP to opt out"
		}
		if !strings.HasPrefix(sms, "AfroTransact") {
			sms = "AfroTransact: " + sms
		}
		d["smsCopy"] = smsSafeDisplay(strings.ReplaceAll(sms, "{link}", str(d, "smsUrl")))
	}
}

func isPreorderShipment(s map[string]any) bool {
	_, block := s["preorder"].(map[string]any)
	return block || str(s, "fulfilmentType") == "PREORDER"
}

// hasPreorder: the placed order has a pre-order package.
func hasPreorder(d map[string]any) bool {
	for _, s := range objects(d, "shipments") {
		if isPreorderShipment(s) {
			return true
		}
	}
	return false
}

// hasShipNow: the placed order has a package that ships now (or no shipment detail at all).
func hasShipNow(d map[string]any) bool {
	ships := objects(d, "shipments")
	if len(ships) == 0 {
		return true
	}
	for _, s := range ships {
		if !isPreorderShipment(s) {
			return true
		}
	}
	return false
}

// enrichPreorderConfirmed is "You're in": the pre-order package's window, delivery date, what was charged (one
// payment for the whole cart) and until when it can be cancelled for a full refund.
func enrichPreorderConfirmed(d map[string]any, l Links) {
	enrichOrderPlaced(d, l)
	var pre map[string]any
	nowItems := int64(0)
	for _, s := range objects(d, "shipments") {
		if !isPreorderShipment(s) {
			for _, line := range objects(s, "lines") {
				q, _ := integer(line, "quantity")
				nowItems += q
			}
			continue
		}
		if pre == nil {
			pre, _ = s["preorder"].(map[string]any)
			if pre == nil {
				pre = map[string]any{}
			}
			if str(pre, "deliversOn") == "" {
				pre["deliversOn"] = str(s, "promisedBy")
			}
		}
	}
	if pre == nil {
		pre = map[string]any{}
	}
	d["name"] = smsSafeDisplay(firstNonEmptyStr(str(pre, "name"), "your pre-order"))
	d["code"] = str(pre, "code")
	d["preorderNumber"] = firstNonEmptyStr(str(pre, "number"), str(d, "orderNumber"))
	d["deliversDisplay"] = smsSafeDisplay(firstNonEmptyStr(str(pre, "deliversDisplay"), friendlyDate(str(pre, "deliversOn"))))
	d["cancelUntil"] = smsSafeDisplay(firstNonEmptyStr(str(pre, "closesDisplay"), friendlyDateTime(str(pre, "closesAt"))))
	setDefault(d, "deliversDisplay", "the delivery date")
	setDefault(d, "cancelUntil", "the pre-order closes")
	// The fee charged for the chosen fulfilment (the window's effective door or pickup fee, from the cart).
	d["doorFee"], d["pickupFee"], d["pickupWindow"] = "", "", smsSafeDisplay(str(pre, "pickupWindow"))
	cur := firstNonEmptyStr(str(pre, "currency"), str(d, "currency"), "USD")
	fee, hasFee := integer(pre, "feeMinor")
	switch str(pre, "fulfilment") {
	case "PICKUP":
		d["fulfilmentText"] = "Pickup"
		d["pickupFee"] = "free"
		if hasFee && fee > 0 {
			d["pickupFee"] = money(fee, cur)
		}
	default:
		d["fulfilmentText"] = "Door delivery"
		if hasFee {
			d["doorFee"] = money(fee, cur)
		}
	}
	d["nowNote"] = ""
	switch {
	case nowItems == 1:
		d["nowNote"] = "1 other item ships now with tracking."
	case nowItems > 1:
		d["nowNote"] = strconv.FormatInt(nowItems, 10) + " other items ship now with tracking."
	}
	preorderLinks(d, l, "confirmed")
}

// enrichPreorderMissed offers the choices the payment settings allow (missedDelivery.offerRefund / offerNewDate):
// a full refund, or the new delivery date.
func enrichPreorderMissed(d map[string]any, l Links) {
	nullable(d, "name", "code", "orderNumber", "refundUrl", "newDateUrl", "newDate", "newDateDisplay")
	setDefault(d, "name", "your pre-order")
	d["name"] = smsSafeDisplay(str(d, "name"))
	d["missedDisplay"] = firstNonEmptyStr(friendlyDate(str(d, "deliversOn")), "the planned date")
	setDefault(d, "newDateDisplay", friendlyDate(str(d, "newDate")))
	d["total"] = moneyField(d, "totalMinor")
	code, order := str(d, "code"), str(d, "orderNumber")
	choose := join(l.Storefront, "/orders/"+url.PathEscape(order)+"/pre-order")
	refund, newDate := str(d, "refundUrl"), str(d, "newDateUrl")
	if refund == "" && boolOr(d, "offerRefund", true) {
		refund = choose + "?choice=refund"
	}
	if newDate == "" && boolOr(d, "offerNewDate", true) && str(d, "newDateDisplay") != "" {
		newDate = choose + "?choice=new-date"
	}
	if !boolOr(d, "offerRefund", true) {
		refund = ""
	}
	if !boolOr(d, "offerNewDate", true) {
		newDate = ""
	}
	d["refundUrl"] = withAttribution(refund, "email", code, "missed")
	d["newDateUrl"] = withAttribution(newDate, "email", code, "missed")
	d["orderUrl"] = withAttribution(join(l.Storefront, "/orders"), "email", code, "missed")
	d["helpUrl"] = join(l.Storefront, "/help")
	items := []any{}
	for _, line := range objects(d, "items") {
		items = append(items, viewItem(line, currencyOf(d), l))
	}
	d["packages"] = []any{}
	if len(items) > 0 {
		d["packages"] = []any{map[string]any{"label": "Pre-order · " + str(d, "name"), "kind": "preorder",
			"promise": "Was due " + str(d, "missedDisplay"), "note": "", "items": items}}
	}
}

// enrichPreorderLatePayment formats the late add's payment-link SMS.
func enrichPreorderLatePayment(d map[string]any, _ Links) {
	nullable(d, "number", "code", "name", "deliversDisplay")
	d["total"] = moneyField(d, "totalMinor")
	setDefault(d, "number", str(d, "orderNumber"))
	setDefault(d, "deliversDisplay", friendlyDate(str(d, "deliversOn")))
	d["deliversDisplay"] = smsSafeDisplay(str(d, "deliversDisplay"))
	// No attribution parameters: the signed pay-now link must stay short enough for a single SMS segment.
	d["paymentUrl"] = str(d, "paymentUrl")
}

func firstNonEmptyStr(vals ...string) string {
	for _, v := range vals {
		if v != "" {
			return v
		}
	}
	return ""
}
