package app

import "net/url"

// Delivery quotes (listing rules D20). The event carries ids, a reference, an amount and a city; the shopper's name and
// address come from identity, never from the event.

// enrichDeliveryQuoteReady adds the agreed charge, when it ends and the pay link: the shopper returns to checkout, where the
// quote is applied to their cart and address (there is no separate payment page).
func enrichDeliveryQuoteReady(d map[string]any, l Links) {
	nullable(d, "reference", "quoteId", "expiresAt")
	d["amount"] = moneyField(d, "amountMinor")
	d["expiresDisplay"] = firstNonEmptyStr(friendlyDateTime(str(d, "expiresAt")), "the date shown at checkout")
	d["payUrl"] = join(l.Storefront, "/checkout?quote="+url.QueryEscape(str(d, "quoteId")))+"&src=email"
	d["quotesUrl"] = join(l.Storefront, "/account/delivery-quotes")
}

// enrichDeliveryQuoteDeclined says plainly that we can't deliver there right now and where to go instead; the staff reason is
// never sent.
func enrichDeliveryQuoteDeclined(d map[string]any, l Links) {
	nullable(d, "reference", "quoteId", "place")
	d["pickupUrl"] = join(l.Storefront, "/checkout")
	d["quotesUrl"] = join(l.Storefront, "/account/delivery-quotes")
	d["helpUrl"] = join(l.Storefront, "/help")
}
