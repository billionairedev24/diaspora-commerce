package app

import (
	"net/url"
	"strconv"
	"strings"

	"github.com/billionairedev24/diaspora-commerce/go/services/notifications/internal/domain"
)

// Order email view data (S277). The orders events carry the lines (title, image, quantity, unit price), the
// shipments (fulfilment type, seller, promise, pre-order block) and the ship-to address; these helpers turn them
// into what the layout's components render:
//
//	packages: [{label, kind (afrotransact | seller | preorder), promise, note, items: [{title, variant, quantity,
//	          unitPrice, lineTotal, imageUrl, url}]}]   → {{template "packages" .packages}}
//	totals:   {rows: [{label, value, credit}], total}   → {{template "totals" .totals}}
//	shipTo:   {label, name, lines: [...]}               → {{template "address" .shipTo}}
//
// Older events without lines or address render without those blocks (empty lists, empty name).

// orderLink is the order's page in the storefront (/orders/{orderNumber}).
func orderLink(d map[string]any, l Links) string {
	if n := str(d, "orderNumber"); n != "" {
		return join(l.Storefront, "/orders/"+url.PathEscape(n))
	}
	return join(l.Storefront, "/orders")
}

func currencyOf(d map[string]any) string {
	if c := str(d, "currency"); c != "" {
		return c
	}
	return "USD"
}

// viewItem is one order line as the packages component shows it.
func viewItem(line map[string]any, currency string, l Links) map[string]any {
	qty, ok := integer(line, "quantity")
	if !ok || qty <= 0 {
		qty = 1
	}
	title := firstNonEmptyStr(str(line, "title"), str(line, "productTitle"), "Item "+str(line, "variantAtin"))
	item := map[string]any{"title": title, "quantity": qty, "variant": firstNonEmptyStr(str(line, "variant"), str(line, "variantLabel")),
		"unitPrice": "", "lineTotal": "", "imageUrl": "", "url": l.Storefront}
	if unit, ok := integer(line, "unitPriceMinor"); ok {
		item["unitPrice"] = money(unit, currency)
		item["lineTotal"] = money(unit*qty, currency)
	}
	if qty == 1 {
		item["unitPrice"] = ""
	}
	if img := str(line, "imageUrl"); domain.SafeURL(img) {
		item["imageUrl"] = img
	}
	if atin := firstNonEmptyStr(str(line, "productAtin"), str(line, "variantAtin")); atin != "" {
		item["url"] = join(l.Storefront, "/p/"+url.PathEscape(atin))
	}
	return item
}

// packageLabel names a shipment the way the storefront does: Fulfilled by AfroTransact, Sold and shipped by a
// seller, or the pre-order delivery run.
func packageLabel(s map[string]any) (label, kind string) {
	switch str(s, "fulfilmentType") {
	case "RETAIL_1P", "FBA_AFROTRANSACT":
		return "Fulfilled by AfroTransact", "afrotransact"
	case "PREORDER":
		pre, _ := s["preorder"].(map[string]any)
		name := smsSafeDisplay(firstNonEmptyStr(str(pre, "name"), "community"))
		return "Pre-order · " + name + " delivery run", "preorder"
	}
	if isPreorderShipment(s) {
		return "Pre-order delivery run", "preorder"
	}
	if seller := str(s, "sellerName"); seller != "" {
		return "Sold and shipped by " + seller, "seller"
	}
	return "Shipped by the seller", "seller"
}

// packagePromise is the delivery promise line of a package.
func packagePromise(s map[string]any) string {
	if isPreorderShipment(s) {
		pre, _ := s["preorder"].(map[string]any)
		when := firstNonEmptyStr(str(pre, "deliversDisplay"), friendlyDate(str(pre, "deliversOn")), friendlyDate(str(s, "promisedBy")))
		how := "delivered to your door"
		if str(pre, "fulfilment") == "PICKUP" || str(s, "fulfilment") == "PICKUP" {
			how = "ready at the pickup point"
			if w := str(pre, "pickupWindow"); w != "" {
				how += " (" + smsSafeDisplay(w) + ")"
			}
		}
		if when == "" {
			return "Delivered on the pre-order date, " + how
		}
		return smsSafeDisplay(when) + ", " + how
	}
	when := friendlyDate(str(s, "promisedBy"))
	switch {
	case when == "":
		return ""
	case str(s, "deliveryMethod") == "PICKUP":
		return "Ready for pickup by " + when
	default:
		return "Arrives by " + when
	}
}

// orderPackages groups the order's lines by shipment: AfroTransact-fulfilled first, then sellers, then pre-orders.
func orderPackages(d map[string]any, l Links) []any {
	cur := currencyOf(d)
	rank := map[string]int{"afrotransact": 0, "seller": 1, "preorder": 2}
	var buckets [3][]any
	for _, s := range objects(d, "shipments") {
		label, kind := packageLabel(s)
		items := []any{}
		for _, line := range objects(s, "lines") {
			items = append(items, viewItem(line, cur, l))
		}
		if len(items) == 0 {
			continue
		}
		p := map[string]any{"label": label, "kind": kind, "promise": packagePromise(s), "note": "", "items": items}
		if kind == "preorder" {
			if pre, _ := s["preorder"].(map[string]any); pre != nil {
				if c := firstNonEmptyStr(str(pre, "closesDisplay"), friendlyDateTime(str(pre, "closesAt"))); c != "" {
					p["note"] = "Change your mind? Cancel for a full refund until " + smsSafeDisplay(c) + "."
				}
			}
		}
		buckets[rank[kind]] = append(buckets[rank[kind]], p)
	}
	out := []any{}
	for _, b := range buckets {
		out = append(out, b...)
	}
	return out
}

// orderTotals is the subtotal, delivery, tax and discount rows and the total.
func orderTotals(d map[string]any) map[string]any {
	cur := currencyOf(d)
	rows := []any{}
	add := func(label, key string, credit, always bool) {
		n, ok := integer(d, key)
		if !ok || (n == 0 && !always) {
			return
		}
		v := money(n, cur)
		switch {
		case credit:
			v = "−" + money(n, cur)
		case n == 0:
			v = "Free"
		}
		rows = append(rows, map[string]any{"label": label, "value": v, "credit": credit})
	}
	add("Subtotal", "itemsMinor", false, true)
	add("Delivery", "shippingMinor", false, true)
	add("Discounts", "discountMinor", true, false)
	add("Tax", "taxMinor", false, true)
	return map[string]any{"rows": rows, "total": moneyField(d, "totalMinor")}
}

// orderShipTo is the delivery address block (empty name = no block).
func orderShipTo(d map[string]any) map[string]any {
	lines := []any{}
	for _, k := range []string{"shipToLine1", "shipToLine2"} {
		if v := strings.TrimSpace(str(d, k)); v != "" {
			lines = append(lines, v)
		}
	}
	city := strings.TrimSpace(str(d, "shipToCity"))
	if st := str(d, "shipToState"); st != "" {
		city = strings.TrimSpace(strings.TrimSuffix(city+", "+st, ", "))
	}
	if pc := str(d, "shipToPostalCode"); pc != "" {
		city = strings.TrimSpace(city + " " + pc)
	}
	if city = strings.Trim(city, ", "); city != "" {
		lines = append(lines, city)
	}
	name := str(d, "shipToName")
	if name == "" && len(lines) > 0 {
		name = "Your address"
	}
	return map[string]any{"label": "Delivering to", "name": name, "lines": lines}
}

// orderIDs are the product and variant ATINs of every line (excluded from "Complete your pantry").
func orderIDs(d map[string]any) []string {
	var out []string
	lines := objects(d, "items")
	for _, s := range objects(d, "shipments") {
		lines = append(lines, objects(s, "lines")...)
	}
	for _, line := range lines {
		for _, k := range []string{"productAtin", "variantAtin"} {
			if v := str(line, k); v != "" {
				out = append(out, v)
			}
		}
	}
	return out
}

// enrichOrderView adds packages, totals, shipTo, orderUrl, helpUrl and itemCount for order emails.
func enrichOrderView(d map[string]any, l Links) {
	d["orderUrl"] = orderLink(d, l)
	d["ordersUrl"] = join(l.Storefront, "/orders")
	d["helpUrl"] = join(l.Storefront, "/help")
	d["packages"] = orderPackages(d, l)
	d["totals"] = orderTotals(d)
	d["shipTo"] = orderShipTo(d)
	d["placedDate"] = friendlyDate(str(d, "placedAt"))
	d["smsOrderUrl"] = smsLink(str(d, "orderUrl"))
}

// smsLink tags a link with ?src=sms for attribution.
func smsLink(u string) string {
	if u == "" {
		return ""
	}
	if strings.Contains(u, "?") {
		return u + "&src=sms"
	}
	return u + "?src=sms"
}

// enrichShipmentView adds the shipment's package (items carried by the shipment events), the tracking link and
// help links.
func enrichShipmentView(d map[string]any, l Links) {
	d["orderUrl"] = orderLink(d, l)
	d["ordersUrl"] = join(l.Storefront, "/orders")
	d["helpUrl"] = join(l.Storefront, "/help")
	cur := currencyOf(d)
	items := []any{}
	for _, line := range objects(d, "items") {
		items = append(items, viewItem(line, cur, l))
	}
	label, kind := packageLabel(d)
	d["packages"] = []any{}
	if len(items) > 0 {
		d["packages"] = []any{map[string]any{"label": label, "kind": kind, "promise": "", "note": "", "items": items}}
	}
	d["smsOrderUrl"] = smsLink(str(d, "orderUrl"))
	d["trackingUrl"] = trackingLink(str(d, "carrier"), str(d, "trackingNumber"))
	if d["trackingUrl"] == "" {
		d["trackingUrl"] = d["orderUrl"]
	}
	d["itemCount"] = int64(0)
	for _, it := range items {
		q, _ := integer(it.(map[string]any), "quantity")
		d["itemCount"] = d["itemCount"].(int64) + q
	}
}

// trackingLink is the carrier's public tracking page for a tracking number ("" for unknown carriers).
func trackingLink(carrier, number string) string {
	if number == "" {
		return ""
	}
	n := url.QueryEscape(number)
	switch strings.ToLower(carrier) {
	case "ups":
		return "https://www.ups.com/track?tracknum=" + n
	case "usps":
		return "https://tools.usps.com/go/TrackConfirmAction?tLabels=" + n
	case "fedex":
		return "https://www.fedex.com/fedextrack/?trknbr=" + n
	case "dhl":
		return "https://www.dhl.com/us-en/home/tracking/tracking-express.html?tracking-id=" + n
	default:
		return ""
	}
}

// itemsCountText is "1 item" / "3 items".
func itemsCountText(n int64) string {
	if n == 1 {
		return "1 item"
	}
	return strconv.FormatInt(n, 10) + " items"
}
