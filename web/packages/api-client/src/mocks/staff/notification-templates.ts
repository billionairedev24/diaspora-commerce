/* Mock of notifications' templates behind the staff console's Notifications → Templates (gateway-bff-staff.yaml, tag
   Notifications; S268). Rows and sample data are copied from the real seeds (go/services/notifications migrations
   0002–0008, every version) and from notifications' GET /v1/templates/{key}/sample (the producer fixtures run through the
   real ingest), for a representative set of keys, with links on the local hosts (storefront :3000, Seller Center :3001).
   Regenerate by hand when those seeds change. */

export interface MockTemplateRow {
  key: string;
  locale: string;
  channel: 'email' | 'sms' | 'push' | 'chat';
  version: number;
  category: 'security' | 'transactional' | 'reminder' | 'marketing';
  subject: string;
  bodyHtml: string;
  bodyText: string;
  createdBy: string;
  createdAt: string;
}

export interface MockTemplateSample {
  source: 'event' | 'request' | 'sample' | 'none';
  area: 'account' | 'orders' | 'seller' | 'payouts' | 'bookings' | 'trust' | 'retail' | 'marketing' | 'security' | 'other';
  eventTypes: string[];
  data: Record<string, unknown>;
}

export const seedTemplateRows = (): MockTemplateRow[] => [
  {
    "key": "identity.verification",
    "locale": "en",
    "channel": "email",
    "version": 1,
    "category": "security",
    "subject": "Your AfroTransact verification code: {{.code}}",
    "bodyHtml": "<!doctype html><html><body style=\"font-family:Figtree,Arial,sans-serif;background:#ffffff;color:#111\">\n<h1 style=\"font-size:20px\">Confirm your email</h1>\n<p>Enter this code to finish setting up your AfroTransact account:</p>\n<p style=\"font-size:32px;letter-spacing:6px;font-weight:700\">{{.code}}</p>\n<p>The code expires in {{.expiresInMinutes}} minutes. If you didn't ask for it, you can ignore this email.</p>\n</body></html>",
    "bodyText": "Your AfroTransact verification code is {{.code}}. It expires in {{.expiresInMinutes}} minutes. If you didn't ask for it, ignore this email.",
    "createdBy": "system",
    "createdAt": "2026-06-01T09:00:00Z"
  },
  {
    "key": "identity.verification",
    "locale": "en",
    "channel": "sms",
    "version": 1,
    "category": "security",
    "subject": "",
    "bodyHtml": "",
    "bodyText": "AfroTransact: your verification code is {{.code}}. It expires in {{.expiresInMinutes}} min. Never share this code.",
    "createdBy": "system",
    "createdAt": "2026-06-01T09:00:00Z"
  },
  {
    "key": "order.confirmed",
    "locale": "en",
    "channel": "email",
    "version": 1,
    "category": "transactional",
    "subject": "Order {{.orderNumber}} confirmed",
    "bodyHtml": "<!doctype html><html><body style=\"font-family:Figtree,Arial,sans-serif;background:#ffffff;color:#111\">\n<h1 style=\"font-size:20px\">Thanks for your order, {{.firstName}}!</h1>\n<p>Order <strong>{{.orderNumber}}</strong> is confirmed. Total: <strong>{{.total}}</strong>.</p>\n<p>Estimated delivery: {{.estimatedDelivery}}.</p>\n<p><a href=\"{{.orderUrl}}\">View your order</a></p>\n</body></html>",
    "bodyText": "Thanks for your order, {{.firstName}}! Order {{.orderNumber}} is confirmed. Total: {{.total}}. Estimated delivery: {{.estimatedDelivery}}. View it at {{.orderUrl}}",
    "createdBy": "system",
    "createdAt": "2026-06-01T09:00:00Z"
  },
  {
    "key": "order.confirmed",
    "locale": "en",
    "channel": "sms",
    "version": 1,
    "category": "transactional",
    "subject": "",
    "bodyHtml": "",
    "bodyText": "AfroTransact: order {{.orderNumber}} confirmed ({{.total}}). Arriving {{.estimatedDelivery}}. Reply STOP to opt out.",
    "createdBy": "system",
    "createdAt": "2026-06-01T09:00:00Z"
  },
  {
    "key": "order.confirmed",
    "locale": "en",
    "channel": "push",
    "version": 1,
    "category": "transactional",
    "subject": "Order confirmed",
    "bodyHtml": "",
    "bodyText": "Order {{.orderNumber}} is confirmed. Arriving {{.estimatedDelivery}}.",
    "createdBy": "system",
    "createdAt": "2026-06-01T09:00:00Z"
  },
  {
    "key": "booking.confirmed",
    "locale": "en",
    "channel": "email",
    "version": 1,
    "category": "transactional",
    "subject": "Booking confirmed: {{.serviceName}} on {{.startsAt}}",
    "bodyHtml": "<!doctype html><html><body style=\"font-family:Figtree,Arial,sans-serif;background:#ffffff;color:#111\">\n<h1 style=\"font-size:20px\">You're booked, {{.firstName}}!</h1>\n<p><strong>{{.serviceName}}</strong> with {{.providerName}}</p>\n<p>{{.startsAt}} at {{.location}}</p>\n<p>Booking reference: {{.bookingReference}}</p>\n<p><a href=\"{{.manageUrl}}\">Manage or cancel your booking</a></p>\n</body></html>",
    "bodyText": "You're booked, {{.firstName}}! {{.serviceName}} with {{.providerName}}, {{.startsAt}} at {{.location}}. Reference {{.bookingReference}}. Manage: {{.manageUrl}}",
    "createdBy": "system",
    "createdAt": "2026-06-01T09:00:00Z"
  },
  {
    "key": "booking.confirmed",
    "locale": "en",
    "channel": "sms",
    "version": 1,
    "category": "transactional",
    "subject": "",
    "bodyHtml": "",
    "bodyText": "AfroTransact: {{.serviceName}} with {{.providerName}} is confirmed for {{.startsAt}}. Ref {{.bookingReference}}. Reply STOP to opt out.",
    "createdBy": "system",
    "createdAt": "2026-06-01T09:00:00Z"
  },
  {
    "key": "booking.confirmed",
    "locale": "en",
    "channel": "push",
    "version": 1,
    "category": "transactional",
    "subject": "Booking confirmed",
    "bodyHtml": "",
    "bodyText": "{{.serviceName}} with {{.providerName}}, {{.startsAt}}.",
    "createdBy": "system",
    "createdAt": "2026-06-01T09:00:00Z"
  },
  {
    "key": "order.confirmed",
    "locale": "en",
    "channel": "email",
    "version": 2,
    "category": "transactional",
    "subject": "Order {{.orderNumber}} is confirmed",
    "bodyHtml": "<!doctype html><html><body style=\"font-family:Figtree,Arial,sans-serif;background:#ffffff;color:#111\">\n<h1 style=\"font-size:20px\">Thanks for your order, {{.firstName}}!</h1>\n<p>Order <strong>{{.orderNumber}}</strong> is confirmed: {{.itemCount}} item(s), <strong>{{.total}}</strong>.</p>\n<p>Arriving {{.estimatedDelivery}}. We'll let you know as soon as it ships.</p>\n<p><a href=\"{{.orderUrl}}\" style=\"color:#111;font-weight:700\">View your order</a></p>\n</body></html>",
    "bodyText": "Thanks for your order, {{.firstName}}! Order {{.orderNumber}} is confirmed ({{.itemCount}} item(s), {{.total}}). Arriving {{.estimatedDelivery}}. View it: {{.orderUrl}}",
    "createdBy": "system",
    "createdAt": "2026-08-12T09:00:00Z"
  },
  {
    "key": "order.confirmed",
    "locale": "en",
    "channel": "sms",
    "version": 2,
    "category": "transactional",
    "subject": "",
    "bodyHtml": "",
    "bodyText": "AfroTransact: order {{.orderNumber}} confirmed ({{.total}}). Arriving {{.estimatedDelivery}}. Reply STOP to opt out.",
    "createdBy": "system",
    "createdAt": "2026-08-12T09:00:00Z"
  },
  {
    "key": "order.confirmed",
    "locale": "en",
    "channel": "push",
    "version": 2,
    "category": "transactional",
    "subject": "Order confirmed",
    "bodyHtml": "",
    "bodyText": "Order {{.orderNumber}} ({{.total}}) is confirmed. Arriving {{.estimatedDelivery}}.",
    "createdBy": "system",
    "createdAt": "2026-08-12T09:00:00Z"
  },
  {
    "key": "stock.back_in_stock",
    "locale": "en",
    "channel": "email",
    "version": 1,
    "category": "transactional",
    "subject": "It's back: {{.productTitle}}",
    "bodyHtml": "<!doctype html><html><body style=\"font-family:Figtree,Arial,sans-serif;background:#ffffff;color:#111\">\n<h1 style=\"font-size:20px\">Good news, {{.firstName}}, it's back!</h1>\n<p><strong>{{.productTitle}}</strong> is back in stock. Popular items go fast, so grab yours while you can.</p>\n<p><a href=\"{{.productUrl}}\" style=\"color:#111;font-weight:700\">Shop now</a></p>\n</body></html>",
    "bodyText": "Good news, {{.firstName}}! {{.productTitle}} is back in stock. Shop now: {{.productUrl}}",
    "createdBy": "system",
    "createdAt": "2026-08-12T09:00:00Z"
  },
  {
    "key": "stock.back_in_stock",
    "locale": "en",
    "channel": "push",
    "version": 1,
    "category": "transactional",
    "subject": "Back in stock",
    "bodyHtml": "",
    "bodyText": "{{.productTitle}} is back. Grab yours before it's gone.",
    "createdBy": "system",
    "createdAt": "2026-08-12T09:00:00Z"
  },
  {
    "key": "seller.suspended",
    "locale": "en",
    "channel": "email",
    "version": 1,
    "category": "transactional",
    "subject": "Your AfroTransact seller account is suspended",
    "bodyHtml": "<!doctype html><html><body style=\"font-family:Figtree,Arial,sans-serif;background:#ffffff;color:#111\">\n<h1 style=\"font-size:20px\">Your account is suspended</h1>\n<p>Hi {{.firstName}}, our Trust and Safety team has suspended your seller account (reason: {{.reasonText}}).</p>\n<p>While it's suspended, your listings are hidden and payouts are on hold. Your earnings are kept safe.</p>\n{{if .note}}<p>Note from our Trust and Safety team: {{.note}}</p>{{end}}\n<p><a href=\"{{.actionUrl}}\" style=\"color:#111;font-weight:700\">View account health</a></p>\n</body></html>",
    "bodyText": "Hi {{.firstName}}, your seller account is suspended (reason: {{.reasonText}}). Listings are hidden and payouts are on hold.{{if .note}} Note: {{.note}}{{end}} {{.actionUrl}}",
    "createdBy": "system",
    "createdAt": "2026-09-02T09:00:00Z"
  },
  {
    "key": "seller.suspended",
    "locale": "en",
    "channel": "sms",
    "version": 1,
    "category": "transactional",
    "subject": "",
    "bodyHtml": "",
    "bodyText": "AfroTransact: your seller account is suspended ({{.reasonText}}). Listings are hidden and payouts held. {{.actionUrl}} Reply STOP to opt out.",
    "createdBy": "system",
    "createdAt": "2026-09-02T09:00:00Z"
  },
  {
    "key": "retail.purchase_order.sent",
    "locale": "en",
    "channel": "email",
    "version": 1,
    "category": "transactional",
    "subject": "Purchase order {{.poNumber}} from AfroTransact Retail",
    "bodyHtml": "<!doctype html><html><body style=\"font-family:Figtree,Arial,sans-serif;background:#ffffff;color:#111\">\n<h1 style=\"font-size:20px\">Purchase order {{.poNumber}}</h1>\n<p>Hello {{.contactName}},</p>\n<p>AfroTransact Retail has issued purchase order <strong>{{.poNumber}}</strong> to {{.supplierName}}.</p>\n<table style=\"border-collapse:collapse;font-size:14px\">\n<tr><td style=\"padding:4px 16px 4px 0;color:#555\">Order date</td><td style=\"padding:4px 0\">{{.orderDate}}</td></tr>\n{{if .deliverBy}}<tr><td style=\"padding:4px 16px 4px 0;color:#555\">Deliver by</td><td style=\"padding:4px 0\">{{.deliverBy}}</td></tr>{{end}}\n{{if .incoterm}}<tr><td style=\"padding:4px 16px 4px 0;color:#555\">Incoterm</td><td style=\"padding:4px 0\">{{.incoterm}}</td></tr>{{end}}\n{{if .paymentTerms}}<tr><td style=\"padding:4px 16px 4px 0;color:#555\">Payment terms</td><td style=\"padding:4px 0\">{{.paymentTerms}}</td></tr>{{end}}\n<tr><td style=\"padding:4px 16px 4px 0;color:#555\">Total</td><td style=\"padding:4px 0\"><strong>{{.total}}</strong></td></tr>\n</table>\n<p>The purchase order PDF is attached to this email. If you can't see the attachment, download it here:</p>\n<p><a href=\"{{.documentUrl}}\" style=\"color:#111;font-weight:700\">Download purchase order {{.poNumber}} (PDF)</a>{{if .documentExpiresOn}}<br><span style=\"color:#555;font-size:13px\">This link works until {{.documentExpiresOn}}.</span>{{end}}</p>\n<p>Please confirm the order and your expected ship date with your AfroTransact Retail buyer.</p>\n</body></html>",
    "bodyText": "Hello {{.contactName}},\n\nAfroTransact Retail has issued purchase order {{.poNumber}} to {{.supplierName}}.\n\nOrder date: {{.orderDate}}\n{{if .deliverBy}}Deliver by: {{.deliverBy}}\n{{end}}{{if .incoterm}}Incoterm: {{.incoterm}}\n{{end}}{{if .paymentTerms}}Payment terms: {{.paymentTerms}}\n{{end}}Total: {{.total}}\n\nThe purchase order PDF is attached. If you can't see it, download it here{{if .documentExpiresOn}} (link valid until {{.documentExpiresOn}}){{end}}:\n{{.documentUrl}}\n\nPlease confirm the order and your expected ship date with your AfroTransact Retail buyer.",
    "createdBy": "system",
    "createdAt": "2026-10-01T09:00:00Z"
  }
];

export const seedTemplateSamples = (): Record<string, MockTemplateSample> => ({
  "booking.confirmed": {
    "source": "event",
    "area": "bookings",
    "eventTypes": [
      "booking.confirmed"
    ],
    "data": {
      "bookingId": "a306b1f4-cfd2-4017-c8d9-eafb0c1d2e11",
      "bookingReference": "BK-24018",
      "currency": "USD",
      "depositMinor": 3000,
      "endAt": "2026-10-17T18:00:00Z",
      "firstName": "Ada",
      "location": "Houston, TX",
      "manageUrl": "http://localhost:3000/bookings/BK-24018",
      "memberId": "b417c205-d0e3-4128-d9ea-fb0c1d2e3f12",
      "orgId": "org_2",
      "policy": "MODERATE",
      "priceMinor": 18000,
      "providerName": "Kemi's Studio",
      "refundedMinor": 3000,
      "serviceId": "c528d316-e1f4-4239-eafb-0c1d2e3f4a13",
      "serviceName": "Knotless braids",
      "startAt": "2026-10-17T15:00:00Z",
      "startsAt": "Sat, Oct 17 · 10:00 AM",
      "status": "CONFIRMED"
    }
  },
  "identity.verification": {
    "source": "event",
    "area": "security",
    "eventTypes": [
      "identity.verification.requested"
    ],
    "data": {
      "channel": "email",
      "code": "123456",
      "expiresAt": "2026-10-10T10:10:00Z",
      "expiresInMinutes": 10,
      "firstName": "Ada",
      "purpose": "EMAIL_VERIFICATION",
      "transactional": true,
      "userId": "u-1"
    }
  },
  "order.confirmed": {
    "source": "event",
    "area": "orders",
    "eventTypes": [
      "orders.order.placed"
    ],
    "data": {
      "currency": "USD",
      "customerId": "cust-1",
      "discountMinor": 0,
      "estimatedDelivery": "by Wed, Oct 14",
      "firstName": "Ada",
      "itemCount": 2,
      "itemsMinor": 4000,
      "orderId": "0f6c1d5a-2b3e-4c7d-8e9f-a0b1c2d3e401",
      "orderNumber": "AT-2026-7KQ3MD",
      "orderUrl": "http://localhost:3000/orders",
      "placedAt": "2026-10-10T10:00:00Z",
      "shipments": [
        {
          "deliveryMethod": "CARRIER",
          "fulfilmentType": "RETAIL_1P",
          "lines": [
            {
              "quantity": 2,
              "variantAtin": "ATGARI0001"
            }
          ],
          "promisedBy": "2026-10-14",
          "sellerOrgId": "org_1",
          "shipmentId": "1a7d2e6b-3c4f-4d8e-9fa0-b1c2d3e4f502",
          "totalMinor": 4800
        }
      ],
      "shippingMinor": 500,
      "taxMinor": 300,
      "total": "$48.00",
      "totalMinor": 4800
    }
  },
  "retail.purchase_order.sent": {
    "source": "request",
    "area": "retail",
    "eventTypes": [
      "notification.requested"
    ],
    "data": {
      "attachments": [
        {
          "assetId": "a1",
          "contentType": "application/pdf",
          "expiresAt": "2026-10-24T10:00:00Z",
          "fileName": "PO-1P-323.pdf",
          "url": "https://files.example/po/PO-1P-323.pdf?sig=abc"
        }
      ],
      "contactName": "Efua Mensah",
      "currency": "GHS",
      "deliverBy": "24 Oct 2026",
      "documentAssetId": "a1",
      "documentExpiresAt": "2026-10-24T10:00:00Z",
      "documentExpiresOn": "Oct 24, 2026",
      "documentUrl": "https://files.example/po/PO-1P-323.pdf?sig=abc",
      "incoterm": "FOB",
      "lineCount": 1,
      "orderDate": "10 Oct 2026",
      "paymentTerms": "",
      "poId": "5eb16caf-7a8d-4bc2-d3e4-f5a6b7c8d906",
      "poNumber": "PO-1P-323",
      "shipTo": "HOU-FC",
      "supplierCode": "SUP-ACC",
      "supplierName": "Accra Pack Ltd",
      "total": "GHS 16,656.00",
      "totalMinor": 1665600,
      "units": 480
    }
  },
  "seller.suspended": {
    "source": "event",
    "area": "trust",
    "eventTypes": [
      "trustops.seller.suspended"
    ],
    "data": {
      "actionUrl": "http://localhost:3001/account-health",
      "actorId": "staff-1",
      "buyBoxRestricted": true,
      "changedAt": "2026-10-10T10:00:00Z",
      "firstName": "Ada",
      "listingsHidden": true,
      "note": "",
      "orgId": "org_1",
      "payoutsHeld": true,
      "reason": "POLICY_VIOLATION",
      "reasonText": "policy violation",
      "status": "SUSPENDED"
    }
  },
  "stock.back_in_stock": {
    "source": "event",
    "area": "marketing",
    "eventTypes": [
      "inventory.availability.changed"
    ],
    "data": {
      "atin": "ATOGBO0500",
      "firstName": "Ada",
      "productTitle": "Ogbono seeds 500g",
      "productUrl": "http://localhost:3000/p/ATOGBO0500"
    }
  }
});
