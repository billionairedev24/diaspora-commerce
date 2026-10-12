package app

import "fmt"

// Sample payloads shaped like contracts/asyncapi/events.yaml: one per mapped event type, and one per rule for
// multi-rule types (shipment statuses, suspension variants, both parties of a claim). They drive the template gallery,
// GET /v1/templates/{key}/sample and the Postgres integration test that renders every mapped event. Templates use
// missingkey=error, so a field the ingest forgets to derive fails there. Ids are fixed so samples are stable.

// SampleEvents returns fresh copies of the producer event fixtures, keyed by event type. Links the producers put in
// their payloads (invitation, booking and newsletter links) use the environment's base URLs.
func SampleEvents(l Links) map[string][]map[string]any {
	return map[string][]map[string]any{
		"identity.verification.requested": {
			{"recipient": map[string]any{"id": "u-1", "email": "otp@example.com"}, "channel": "email", "code": "123456",
				"expiresInMinutes": 10, "expiresAt": "2026-10-10T10:10:00Z", "purpose": "EMAIL_VERIFICATION", "userId": "u-1",
				"firstName": "Ada", "transactional": true},
			{"recipient": map[string]any{"id": "u-1", "phone": "+15550002222"}, "channel": "sms", "code": "654321",
				"expiresInMinutes": 10, "expiresAt": "2026-10-10T10:10:00Z", "purpose": "PHONE_VERIFICATION", "userId": "u-1",
				"transactional": true},
			{"recipient": map[string]any{"id": "u-1", "email": "ada@example.com"}, "channel": "email", "code": "482913",
				"expiresInMinutes": 10, "expiresAt": "2026-10-10T10:10:00Z", "purpose": "PASSWORD_RESET", "userId": "u-1",
				"firstName": "Ada", "transactional": true},
		},
		"identity.password-reset.federated": {{"recipient": map[string]any{"id": "u-2", "email": "kofi@example.com"},
			"userId": "u-2", "firstName": "Kofi", "providers": []string{"Google"}, "transactional": true}},
		"identity.password.changed": {{"recipient": map[string]any{"id": "u-1", "email": "ada@example.com"},
			"userId": "u-1", "firstName": "Ada", "method": "RESET", "changedAt": "2026-10-12T14:05:00Z",
			"sessionsRevoked": 3, "transactional": true}},
		"identity.invitation.created": {{"invitationId": "5b0c7d2e-8a41-4f0e-b3c2-9d6e1a2f4b01", "orgId": "org_1",
			"orgName": "Mama Put", "orgKind": "SELLER", "email": "invitee@example.com", "roleKey": "seller.catalog",
			"roleName": "Catalog manager", "token": "tok", "acceptUrl": join(l.SellerCenter, "/invitations/accept?token=tok"),
			"expiresAt": "2026-10-17T10:00:00Z", "expiresInDays": 7, "invitedBy": "owner-1", "inviterName": nil,
			"recipient": map[string]any{"id": nil, "email": "invitee@example.com", "phone": nil}, "transactional": true}},
		"identity.user.registered": {{"userId": "u-1", "email": "ada@example.com", "firstName": "Ada", "lastName": "Obi",
			"intent": "SELL", "roles": []string{"account.seller_applicant"}, "method": "PASSWORD", "emailVerified": false,
			"smsConsent": false, "registeredAt": "2026-10-10T10:00:00Z"}},
		"orders.order.placed": {sampleOrderPlaced(), samplePreorderOrder()},
		"orders.preorder.delivery_missed": {{"orderId": "5c2d8e1f-3a4b-4c5d-8e6f-7a8b9c0d1e21", "orderNumber": "AT-2026-PRE7LB",
			"customerId": "cust-1", "windowId": samplePreorderWindowID, "code": "LBK-7", "name": "Lubbock Pre-order #7",
			"deliversOn": "2026-10-25", "newDate": "2026-10-28", "offerRefund": true, "offerNewDate": true, "refundUrl": nil,
			"newDateUrl": nil, "totalMinor": 5196, "currency": "USD", "reason": "VEHICLE_BREAKDOWN",
			"items": []any{sampleItem(0, 2)}}},
		"preorder.comms.due": {
			samplePreorderComms(PreorderAnnounce, "AfroTransact: Lubbock pre-order is open until Wed 11:59PM. {link} Reply STOP to opt out"),
			samplePreorderComms(PreorderAnnounce, ""),
			samplePreorderComms(PreorderRemind48h, ""), samplePreorderComms(PreorderLastCall6h, ""),
			samplePreorderComms(PreorderClosed, ""), samplePreorderComms(PreorderOutForDelivery, ""),
		},
		"preorder.late_order.added": {{"windowId": samplePreorderWindowID, "code": "LBK-7", "name": "Lubbock Pre-order #7",
			"orderId": "6d3e9f20-4b5c-4d6e-9f70-8b9c0d1e2f32", "number": "LBK-L90", "customerId": "cust-1", "phone": "+15550001111",
			"payment": "PAYMENT_LINK", "paymentUrl": "http://localhost:3000/orders/AT-2026-PAY001/pay?token=1.vbkms0.Fh2nQ8l5XyZr0aB3cD4eFg", "totalMinor": 4297, "currency": "USD",
			"deliversOn": "2026-10-25", "expiresAt": "2026-10-23T05:00:00Z", "reason": "Customer called", "actor": "staff-4"}},
		"orders.order.cancelled": {{"orderId": "0f6c1d5a-2b3e-4c7d-8e9f-a0b1c2d3e401", "orderNumber": "AT-2026-7KQ3MD",
			"customerId": "cust-1", "code": "orders.all-shipments-cancelled", "wasPlaced": true, "cancelledAt": "2026-10-10T10:00:00Z"}},
		"orders.shipment.status_changed": {
			sampleShipment("packed", "picked_up", "CARRIER"), sampleShipment("picked_up", "out_for_delivery", "AFROTRANSACT_DELIVERY"),
			sampleShipment("packed", "ready", "PICKUP"), sampleShipment("out_for_delivery", "delivery_failed", "AFROTRANSACT_DELIVERY"),
		},
		"orders.shipment.delivered": {{"orderId": "0f6c1d5a-2b3e-4c7d-8e9f-a0b1c2d3e401", "orderNumber": "AT-2026-7KQ3MD",
			"customerId": "cust-1", "shipmentId": "1a7d2e6b-3c4f-4d8e-9fa0-b1c2d3e4f502", "sellerOrgId": "org_afrotransact_retail",
			"deliveredAt": "2026-10-13T16:20:00Z", "totalMinor": 2500, "lineIds": []string{}, "source": "driver",
			"fulfilmentType": "RETAIL_1P", "sellerName": "AfroTransact Retail", "currency": "USD",
			"items": []any{sampleItem(0, 2), sampleItem(1, 1)}}},
		"orders.shipment.cancelled": {{"orderId": "0f6c1d5a-2b3e-4c7d-8e9f-a0b1c2d3e401", "orderNumber": "AT-2026-7KQ3MD",
			"customerId": "cust-1", "shipmentId": "1a7d2e6b-3c4f-4d8e-9fa0-b1c2d3e4f502", "sellerOrgId": "org_1",
			"actorType": "CUSTOMER", "captured": true, "totalMinor": 1500, "currency": "USD", "cancelledAt": "2026-10-10T10:00:00Z",
			"fulfilmentType": "SELLER_FULFILLED", "sellerName": "Mama Put Foods", "items": []any{sampleItem(2, 1)}}},
		"seller.application.submitted":      {sampleApplication("SUBMITTED", nil)},
		"seller.application.info_requested": {sampleApplication("REQUEST_INFO", []string{"business_permit", "tax_id"})},
		"seller.application.approved":       {sampleApplication("FINAL_APPROVE", nil)},
		"seller.application.rejected":       {sampleApplication("REJECT", nil)},
		"seller.compliance.reminder": {{"orgId": "org_1", "accountId": "acct_1", "thresholdDays": 3, "manual": false,
			"openItems": []string{"external_account"}, "currentDeadline": "2026-10-13T00:00:00Z",
			"channels": []string{"email", "sms", "in_app"}, "occurredAt": "2026-10-10T10:00:00Z"}},
		"seller.compliance.changed": {{"orgId": "org_1", "accountId": "acct_1", "status": "ACTION_NEEDED",
			"previousStatus": "COMPLIANT", "newItems": []string{"individual.id_number"}, "currentDeadline": nil,
			"chargesEnabled": true, "payoutsEnabled": true, "payoutsPaused": false, "trigger": "account.updated",
			"channels": []string{"email"}, "occurredAt": "2026-10-10T10:00:00Z"}},
		"seller.payouts.paused":    {sampleEnforcement("AUTO_PAST_DUE"), sampleEnforcement("SUSPENDED")},
		"seller.payouts.resumed":   {sampleEnforcement("CLEARED"), sampleEnforcement("REINSTATED")},
		"seller.listings.hidden":   {sampleEnforcement("CHARGES_DISABLED"), sampleEnforcement("SUSPENDED")},
		"seller.listings.restored": {sampleEnforcement("CLEARED"), sampleEnforcement("REINSTATED")},
		"seller.billing.grant_applied": {
			{"orgId": "org_1", "grantId": "7a1b2c3d-4e5f-4a6b-8c7d-9e0f1a2b3c4d", "source": "RULE", "type": "FREE_DAYS",
				"summary": "1 month free", "reason": "$1,000 in sales in your first 60 days", "creditCents": nil,
				"freeDays": 30, "percentOff": nil, "durationInvoices": nil, "trialEndsAt": "2026-12-09T10:00:00Z",
				"occurredAt": "2026-10-10T10:00:00Z"},
			{"orgId": "org_1", "grantId": "8b2c3d4e-5f6a-4b7c-9d8e-0f1a2b3c4d5e", "source": "MANUAL", "type": "CREDIT",
				"summary": "$25 credit", "reason": nil, "creditCents": 2500, "freeDays": nil, "percentOff": nil,
				"durationInvoices": nil, "trialEndsAt": nil, "occurredAt": "2026-10-10T10:00:00Z"},
		},
		"payments.payout.paid":   {samplePayout("PAID")},
		"payments.payout.failed": {samplePayout("FAILED")},
		"booking.confirmed":      {sampleBooking(l, "CONFIRMED")},
		"booking.cancelled":      {sampleBooking(l, "CANCELLED_PROVIDER")},
		"booking.declined":       {sampleBooking(l, "DECLINED")},
		"booking.rescheduled":    {sampleBooking(l, "CONFIRMED")},
		"booking.balance_unpaid": {sampleBalanceUnpaid(l)},
		"payments.intent.action_required": {{"paymentId": "2b8e3f7c-4d5a-4e9f-a0b1-c2d3e4f5a603", "orderId": "BK-24018",
			"parentPaymentId": "3c9f4a8d-5e6b-4fa0-b1c2-d3e4f5a6b704", "customerId": "cust-1", "stripePaymentIntentId": nil,
			"amountMinor": 15000, "currency": "USD", "reason": "authentication_required", "lineIds": []string{}}},
		"trustops.claim.opened":            {sampleClaim("AWAITING_SELLER", nil), sampleClaim("AWAITING_SELLER", nil)},
		"trustops.claim.decided":           {sampleClaim("RESOLVED_REFUND", "REFUND"), sampleClaim("RESOLVED_DENIED", "DENY")},
		"trustops.claim.withdrawn":         {sampleClaim("WITHDRAWN", nil)},
		"trustops.claim.response_due_soon": {sampleClaim("AWAITING_SELLER", nil)},
		"trustops.claim.response_lapsed":   {sampleClaim("UNDER_REVIEW", nil)},
		"trustops.moderation.decided":      {sampleModeration("NEW_LISTING"), sampleModeration("LIVE_LISTING")},
		"trustops.seller.suspended": {{"orgId": "org_1", "status": "SUSPENDED", "reason": "POLICY_VIOLATION", "note": nil,
			"actorId": "staff-1", "payoutsHeld": true, "buyBoxRestricted": true, "listingsHidden": true,
			"changedAt": "2026-10-10T10:00:00Z"}},
		"trustops.seller.reinstated": {{"orgId": "org_1", "status": "ACTIVE", "reason": nil, "note": nil, "actorId": "staff-1",
			"payoutsHeld": false, "buyBoxRestricted": false, "listingsHidden": false, "changedAt": "2026-10-10T10:00:00Z"}},
		"content.career-application.received": {{"reference": "CAR-000123", "jobId": "j1", "jobTitle": "Backend Engineer",
			"team": "Platform", "location": "Remote", "name": "Kofi Mensah", "email": "kofi@example.com", "resumeAssetId": "a1",
			"resumeFileName": "cv.pdf", "submittedAt": "2026-10-10T10:00:00Z"}},
		"content.newsletter.confirmation_requested": {{"subscriptionId": "4da05b9e-6f7c-4ab1-c2d3-e4f5a6b7c805",
			"email": "news@example.com", "locale": "fr", "confirmUrl": join(l.Storefront, "/newsletter/confirm?t=x"),
			"unsubscribeUrl": join(l.Storefront, "/u"), "expiresAt": "2026-10-11T10:00:00Z"}},
	}
}

// SampleRequests returns the notification.requested payloads producers send for RequestedTemplates, keyed by
// template key. The PO document link is a stand-in for media's signed link.
func SampleRequests() map[string]map[string]any {
	docURL := "https://files.example/po/PO-1P-323.pdf?sig=abc"
	return map[string]map[string]any{
		"retail.purchase_order.sent": {"templateKey": "retail.purchase_order.sent", "channels": []string{"email"},
			"recipient": map[string]any{"email": "efua@accrapack.example", "name": "Efua Mensah"}, "locale": "en",
			"data": map[string]any{"poId": "5eb16caf-7a8d-4bc2-d3e4-f5a6b7c8d906", "poNumber": "PO-1P-323",
				"supplierName": "Accra Pack Ltd", "supplierCode": "SUP-ACC", "contactName": "Efua Mensah",
				"orderDate": "10 Oct 2026", "deliverBy": "24 Oct 2026", "incoterm": "FOB", "paymentTerms": nil, "shipTo": "HOU-FC",
				"lineCount": 1, "units": 480, "totalMinor": 1665600, "currency": "GHS", "total": "GHS 16,656.00",
				"documentUrl": docURL, "documentExpiresAt": "2026-10-24T10:00:00Z", "documentAssetId": "a1",
				"attachments": []any{map[string]any{"assetId": "a1", "fileName": "PO-1P-323.pdf", "contentType": "application/pdf",
					"url": docURL, "expiresAt": "2026-10-24T10:00:00Z"}}}},
	}
}

// SampleData is template data for keys that no event or producer request maps to yet (they are sent with
// notification.requested or POST /v1/notifications).
func SampleData(l Links) map[string]map[string]any {
	return map[string]map[string]any{
		// bookings has no reminder event yet; it would request the template with the booking's display fields.
		"booking.reminder": {"firstName": "Ada", "serviceName": "Knotless braids", "providerName": "Kemi's Studio",
			"startsAt": "Sat, Oct 17 at 10:00 AM", "location": "Houston, TX", "bookingReference": "BK-24018",
			"manageUrl": join(l.Storefront, "/bookings/BK-24018")},
		// The original v1 Stripe requirement reminder (superseded by seller.compliance.reminder).
		"payout.requirement_due": {"businessName": "Mama Put Foods", "requirement": "Upload a bank statement",
			"dueDate": "Tue, Oct 13", "actionUrl": join(l.SellerCenter, "/payouts")},
	}
}

const samplePreorderWindowID = "7b1e2c3d-0000-4000-8000-000000000007"

// samplePreorderComms is delivery-runs' preorder.comms.due for the Lubbock window (fees from the payment settings;
// display strings in the market's time zone). CLOSED and OUT_FOR_DELIVERY carry the window's buyers.
func samplePreorderComms(kind, smsCopy string) map[string]any {
	//nolint:gosec // sample event, no credentials
	e := map[string]any{"windowId": samplePreorderWindowID, "code": "LBK-7", "name": "Lubbock Pre-order #7", "kind": kind,
		"where": "Lubbock, TX", "targets": []string{"US/TX/Lubbock"}, "opensAt": "2026-10-06T14:00:00Z", "closesAt": "2026-10-22T04:59:00Z",
		"deliversOn": "2026-10-25", "opensDisplay": "Tue, Oct 6 9:00 AM", "closesDisplay": "Wed, Oct 21 11:59 PM",
		"deliversDisplay": "Sun, Oct 25", "ribbon": "Lubbock pre-order is open", "headline": "African groceries, delivered to Lubbock",
		"smsCopy": smsCopy, "categories": []string{"Pantry", "Spices"}, "door": true, "pickup": true, "doorFeeMinor": 799,
		"pickupFeeMinor": 0, "currency": "USD", "pickupWindow": "10am–2pm", "deliveryWindow": nil, "timeZone": "America/Chicago"}
	if smsCopy == "" {
		e["smsCopy"] = nil
	}
	if kind == PreorderClosed || kind == PreorderOutForDelivery {
		e["orders"] = []any{map[string]any{"orderId": "0f6c1d5a-2b3e-4c7d-8e9f-a0b1c2d3e401", "customerId": "cust-1", "fulfilment": "DOOR"}}
	}
	if kind == PreorderOutForDelivery {
		e["deliveryWindow"] = "10am-2pm"
	}
	return e
}

// samplePreorderOrder is orders.order.placed for a cart with a pre-order package (door delivery, fee from the
// window) and two items that ship now.
func samplePreorderOrder() map[string]any {
	return map[string]any{"orderId": "5c2d8e1f-3a4b-4c5d-8e6f-7a8b9c0d1e21", "orderNumber": "AT-2026-PRE7LB", "customerId": "cust-1",
		"currency": "USD", "itemsMinor": 4198, "shippingMinor": 799, "taxMinor": 199, "discountMinor": 0, "totalMinor": 5196,
		"placedAt": "2026-10-10T10:00:00Z", "attribution": map[string]any{"src": "ig", "cr": "story", "po": "LBK-7"},
		"shipToName": "Ada Obi", "shipToLine1": "4410 19th St", "shipToCity": "Lubbock", "shipToState": "TX", "shipToPostalCode": "79416",
		"shipments": []any{
			map[string]any{"shipmentId": "2b8e3f7c-0000-4e9f-a0b1-c2d3e4f5a611", "sellerOrgId": "org_afrotransact_retail",
				"fulfilmentType": "PREORDER", "deliveryMethod": "AFROTRANSACT_DELIVERY", "promisedBy": "2026-10-25", "totalMinor": 3398,
				"preorder": map[string]any{"windowId": samplePreorderWindowID, "code": "LBK-7", "name": "Lubbock Pre-order #7",
					"number": "LBK-1042", "deliversOn": "2026-10-25", "deliversDisplay": "Sun, Oct 25", "closesAt": "2026-10-22T04:59:00Z",
					"closesDisplay": "Wed, Oct 21 11:59 PM", "fulfilment": "DOOR", "feeMinor": 799, "pickupWindow": nil},
				"lines": []any{sampleItem(0, 2), sampleItem(1, 1)}},
			map[string]any{"shipmentId": "3c9f4a8d-0000-4fa0-b1c2-d3e4f5a6b722", "sellerOrgId": "org_1", "sellerName": "Mama Put Foods",
				"fulfilmentType": "SELLER_FULFILLED", "deliveryMethod": "CARRIER", "promisedBy": "2026-10-15", "totalMinor": 1798,
				"lines": []any{sampleItem(2, 1)}},
		}}
}

func sampleShipment(from, to, method string) map[string]any {
	return map[string]any{"orderId": "0f6c1d5a-2b3e-4c7d-8e9f-a0b1c2d3e401", "orderNumber": "AT-2026-7KQ3MD",
		"customerId": "cust-1", "shipmentId": "1a7d2e6b-3c4f-4d8e-9fa0-b1c2d3e4f502", "sellerOrgId": "org_1",
		"fulfilmentType": "SELLER_FULFILLED", "deliveryMethod": method, "from": from, "to": to, "actorType": "CARRIER",
		"actorId": "ups", "source": "carrier", "occurredAt": "2026-10-10T10:00:00Z", "carrier": "ups",
		"trackingNumber": "1Z999AA10123456784", "promisedBy": "2026-10-14", "sellerName": "Mama Put Foods", "currency": "USD",
		"items": []any{sampleItem(2, 1)}}
}

// SampleProduct is a product the order samples use (title, photo, price). Defaults use the hosted placeholder
// photo; the running service swaps in real catalog products (search) so previews show real photos.
type SampleProduct struct {
	Atin       string
	Title      string
	ImageURL   string
	PriceMinor int64
}

// sampleCatalog are the products in the sample orders, in line order (2 × garri and 1 × rice ship from
// AfroTransact, the shea butter from a seller; the pre-order cart reuses the first two).
var sampleCatalog = []SampleProduct{
	{Atin: "ATGARI0001", Title: "Yellow Garri, 1.5 kg", PriceMinor: 650},
	{Atin: "ATOFAD0001", Title: "Ofada Rice, Destoned 2 lb", PriceMinor: 1200},
	{Atin: "ATSHEA0001", Title: "Unrefined Shea Butter, Grade A 16 oz", PriceMinor: 1500},
}

// sampleItem is order line i of the sample catalog with a quantity (event line / item shape).
func sampleItem(i int, qty int64) map[string]any {
	p := sampleCatalog[i]
	return map[string]any{"lineId": fmt.Sprintf("9a1b2c3d-0000-4000-8000-00000000000%d", i+1), "variantAtin": p.Atin,
		"productAtin": p.Atin, "title": p.Title, "imageUrl": p.ImageURL, "quantity": qty, "unitPriceMinor": p.PriceMinor}
}

// sampleOrderPlaced is orders.order.placed for a cart with an AfroTransact package and a seller package.
func sampleOrderPlaced() map[string]any {
	return map[string]any{"orderId": "0f6c1d5a-2b3e-4c7d-8e9f-a0b1c2d3e401", "orderNumber": "AT-2026-7KQ3MD",
		"customerId": "cust-1", "currency": "USD", "itemsMinor": 4000, "shippingMinor": 500, "taxMinor": 300,
		"discountMinor": 0, "totalMinor": 4800, "placedAt": "2026-10-10T10:00:00Z", "shipToName": "Ada Obi",
		"shipToLine1": "2207 Bagby St", "shipToLine2": "Apt 4B", "shipToCity": "Houston", "shipToState": "TX",
		"shipToPostalCode": "77006", "shipToCountry": "US",
		"shipments": []any{
			map[string]any{"shipmentId": "1a7d2e6b-3c4f-4d8e-9fa0-b1c2d3e4f502", "sellerOrgId": "org_afrotransact_retail",
				"sellerName": "AfroTransact Retail", "fulfilmentType": "RETAIL_1P", "deliveryMethod": "AFROTRANSACT_DELIVERY",
				"promisedBy": "2026-10-14", "totalMinor": 2500, "lines": []any{sampleItem(0, 2), sampleItem(1, 1)}},
			map[string]any{"shipmentId": "4d0a5b9e-0000-4c1f-a2b3-c4d5e6f7a833", "sellerOrgId": "org_1", "sellerName": "Mama Put Foods",
				"fulfilmentType": "SELLER_FULFILLED", "deliveryMethod": "CARRIER", "promisedBy": "2026-10-16", "totalMinor": 1500,
				"lines": []any{sampleItem(2, 1)}},
		}}
}

func sampleApplication(action string, items []string) map[string]any {
	return map[string]any{"applicationId": "app_1", "reference": "APP-30201", "orgId": "org_1", "kind": "SELLER",
		"applicantUserId": "applicant-1", "status": "PENDING_REVIEW", "action": action, "actorId": "staff-1",
		"requestedItems": items, "displayName": "Mama Put Foods", "note": nil, "submissionCount": 1,
		"occurredAt": "2026-10-10T10:00:00Z"}
}

func sampleEnforcement(reason string) map[string]any {
	e := map[string]any{"orgId": "org_1", "accountId": "acct_1", "reason": reason, "pastDue": []string{"external_account"},
		"actorId": nil, "note": nil, "occurredAt": "2026-10-10T10:00:00Z"}
	if reason == "SUSPENDED" {
		e["accountId"], e["actorId"], e["note"], e["pastDue"] = nil, "trust-ops-service",
			"[PROHIBITED_ITEMS] Repeated prohibited listings", []string{}
	}
	return e
}

func sampleClaim(status string, outcome any) map[string]any {
	return map[string]any{"claimId": "6fc27db0-8b9e-4cd3-e4f5-a6b7c8d9ea07", "reference": "AZ-10001",
		"type": "ITEM_NOT_RECEIVED", "status": status, "orderId": "0f6c1d5a-2b3e-4c7d-8e9f-a0b1c2d3e401",
		"orderNumber": "AT-2026-7KQ3MD", "shipmentId": nil, "sellerOrgId": "org_1", "customerId": "cust-1",
		"amountMinor": 4800, "currency": "USD", "outcome": outcome, "refundMinor": nil, "refundId": nil,
		"dueAt": "2026-10-13T10:00:00Z", "escalated": false}
}

func sampleModeration(itemType string) map[string]any {
	return map[string]any{"itemId": "70d38ec1-9caf-4de4-f5a6-b7c8d9eafb08", "productId": "81e49fd2-adb0-4ef5-a6b7-c8d9eafb0c09",
		"atin": nil, "sellerOrgId": "org_1", "title": "Zobo leaves 200g", "status": "REJECTED", "priority": "NORMAL",
		"reasonCode": "UNAPPROVED_HEALTH_CLAIM", "note": nil, "escalationReason": nil, "aiRiskLevel": nil,
		"itemType": itemType, "reportSource": nil, "reportReason": nil, "reports": 0, "decidedBy": "staff-1",
		"dueAt": "2026-10-11T10:00:00Z"}
}

func sampleBalanceUnpaid(l Links) map[string]any {
	b := sampleBooking(l, "CONFIRMED")
	b["balanceMinor"], b["balanceState"], b["balanceFailure"] = 15000, "FAILED", "card_declined"
	return b
}

func samplePayout(status string) map[string]any {
	return map[string]any{"payoutRequestId": "92f5a0e3-bec1-4f06-b7c8-d9eafb0c1d10", "orgId": "org_1",
		"amountMinor": 250000, "currency": "USD", "status": status, "requestedAt": "2026-10-10T10:00:00Z"}
}

func sampleBooking(l Links, status string) map[string]any {
	return map[string]any{"recipient": map[string]any{"id": "cust-1", "email": nil, "phone": nil, "timeZone": "America/Chicago"},
		"firstName": "Ada", "serviceName": "Knotless braids", "providerName": "Kemi's Studio",
		"startsAt": "Sat, Oct 17 at 10:00 AM", "location": "Houston, TX", "bookingReference": "BK-24018",
		"manageUrl": join(l.Storefront, "/bookings/BK-24018"), "bookingId": "a306b1f4-cfd2-4017-c8d9-eafb0c1d2e11",
		"orgId": "org_2", "memberId": "b417c205-d0e3-4128-d9ea-fb0c1d2e3f12", "serviceId": "c528d316-e1f4-4239-eafb-0c1d2e3f4a13",
		"status": status, "startAt": "2026-10-17T15:00:00Z", "endAt": "2026-10-17T18:00:00Z", "priceMinor": 18000,
		"depositMinor": 3000, "currency": "USD", "policy": "MODERATE", "refundedMinor": 3000}
}
