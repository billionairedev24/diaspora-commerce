import { type HelpArticle } from '../../schemas/help';

/* Help center pages as content-service seeds them (V10__help_center.sql; copy from the Shop design's info pages). */
const UPDATED = '2026-10-11T00:00:00Z';

export const HELP_ARTICLES: HelpArticle[] = [
  {
    slug: 'help-center', topic: 'ORDERS_DELIVERY', topicTitle: 'Orders & delivery', title: 'Help center', summary: 'Track a package, return an item or reach us.', updatedAt: UPDATED,
    sections: [
      { heading: 'Track a package', body: '[Your orders](/orders) shows every shipment and its live status from carriers and AfroTransact Delivery.' },
      { heading: 'Returns', body: 'Most items can be returned within 30 days. Food is refundable if damaged or wrong. [Start a return](/account/returns).' },
      { heading: 'Contact us', body: 'Chat with Lola any time, or reach a person 8 am–10 pm ET at help@afrotransact.com.' }
    ]
  },
  {
    slug: 'shipping', topic: 'ORDERS_DELIVERY', topicTitle: 'Orders & delivery', title: 'Shipping & delivery', summary: 'Carrier shipping, AfroTransact Delivery and pickup.', updatedAt: UPDATED,
    sections: [
      { heading: 'Carrier shipping', body: 'Sellers and our warehouses ship with USPS, UPS and FedEx. Tracking updates arrive automatically.' },
      { heading: 'AfroTransact Delivery', body: 'Our own drivers deliver same-day or next-day in Houston, Edison/Iselin and Atlanta, with a photo and a one-time code.' },
      { heading: 'Pickup', body: 'Collect from an AfroTransact will-call counter, a locker, or a seller’s own shop where the seller offers pickup.' }
    ]
  },
  {
    slug: 'returns', topic: 'RETURNS_REFUNDS', topicTitle: 'Returns & refunds', title: 'Returns & refunds', summary: 'Return items within 30 days and follow your refund.', updatedAt: UPDATED,
    sections: [
      { heading: 'Returns', body: 'Most items can be returned within 30 days. Food is refundable if damaged or wrong.' },
      { heading: 'Start a return', body: 'Go to [Returns & refunds](/account/returns), choose the order and the items, and tell us why. We email a label or arrange a pickup.' },
      { heading: 'Refunds', body: 'Refunds go back to the original payment method once the return is received or the claim is approved. Read the full [Returns & Refunds policy](/legal/returns).' }
    ]
  },
  {
    slug: 'fulfilled-by-afrotransact', topic: 'SELLING', topicTitle: 'Selling & advertising', title: 'Fulfilled by AfroTransact', summary: 'Store your stock with us and we pick, pack and deliver.', updatedAt: UPDATED,
    sections: [
      { heading: 'We ship for you', body: 'Send your stock to an AfroTransact fulfilment centre and we pick, pack and ship every order with USPS, UPS and FedEx.' },
      { heading: 'Faster delivery', body: 'Fulfilled by AfroTransact items qualify for same-day or next-day AfroTransact Delivery in Houston, Edison/Iselin and Atlanta.' },
      { heading: 'Get started', body: '[Sell on AfroTransact](/sell), then send an inbound shipment from Seller Center → Inventory & FBA.' }
    ]
  },
  {
    slug: 'advertise', topic: 'SELLING', topicTitle: 'Selling & advertising', title: 'Advertise on AfroTransact', summary: 'Sponsored products and banners for sellers and brands.', updatedAt: UPDATED,
    sections: [
      { heading: 'Sponsored products & banners', body: 'Sellers and brands buy sponsored products and banner placements with cost-per-click bids and a daily budget, and pay only for clicks. Contact ads@afrotransact.com to start a campaign.' }
    ]
  },
  {
    slug: 'press', topic: 'COMPANY', topicTitle: 'About AfroTransact', title: 'Press', summary: 'Media enquiries.', updatedAt: UPDATED,
    sections: [{ heading: 'Media enquiries', body: 'press@afrotransact.com' }]
  }
];
