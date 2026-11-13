import { defineChapter, type TourJourney } from '@afrotransact/tour';

/*
 * Seller Center and Provider Hub tours, as short chapters (3 to 8 steps) grouped into journeys for the Tour hub.
 * Targets are `data-tour` ids placed on the real components (see docs/engineering/guided-tours.md); text is in src/i18n/*.json under
 * `sc.tour.<chapter>.<step>.title|body`. Rules for the words: plain, no jargon, names not ids, no mention of carriers, nothing that is
 * not built today, no tax advice. Bump a chapter's `version` when its words change enough to deserve a "What's new" mark in the hub.
 *
 * Who sees what: `permissions` are `<module>:<level>` (levels view, edit, approve, full; each includes the ones before it), `flags` are
 * `seller`, `provider`, `approved`, `applying`, `offers-services` and `cap-<plan capability>`. A step's `when` names a guard (`can-<module>`)
 * so a chapter never walks someone to a page their role cannot open. Only the first-time chapters of the busiest pages start by
 * themselves; every other chapter is started from the Tour hub.
 */

export const SELLER_JOURNEYS: readonly TourJourney[] = [
  { id: 'setup', title: 'sc.tour.journey.setup.title', summary: 'sc.tour.journey.setup.summary' },
  { id: 'catalogue', title: 'sc.tour.journey.catalogue.title', summary: 'sc.tour.journey.catalogue.summary' },
  { id: 'fulfilment', title: 'sc.tour.journey.fulfilment.title', summary: 'sc.tour.journey.fulfilment.summary' },
  { id: 'money', title: 'sc.tour.journey.money.title', summary: 'sc.tour.journey.money.summary' },
  { id: 'services', title: 'sc.tour.journey.services.title', summary: 'sc.tour.journey.services.summary' }
];

export const SELLER_TOURS = [
  // ---------------------------------------------------------------- Get set up
  defineChapter({
    id: 'welcome',
    prefix: 'sc.tour.welcome',
    journey: 'setup',
    autoStart: { routes: ['/', '/onboarding'] },
    steps: [
      ['hello'],
      ['menu', 'sidebar', { placement: 'end', optional: true }],
      ['business', 'org-switcher', { placement: 'end', optional: true }],
      ['application', 'onboarding-status', { when: 'applying', optional: true }],
      ['steps', 'onboarding-stepper', { when: 'applying', optional: true }],
      ['home', 'page-home', { route: '/', when: 'approved' }],
      ['help', 'help-menu']
    ]
  }),
  defineChapter({
    id: 'application',
    prefix: 'sc.tour.application',
    journey: 'setup',
    route: '/onboarding',
    audience: { flags: ['applying'] },
    steps: [['hello'], ['status', 'onboarding-status'], ['steps', 'onboarding-stepper', { optional: true }], ['after']]
  }),
  defineChapter({
    id: 'account-health',
    prefix: 'sc.tour.accountHealth',
    journey: 'setup',
    route: '/account-health',
    audience: { permissions: ['account-health:view'], flags: ['approved'] },
    steps: [
      ['page', 'page-account-health'],
      ['status', 'account-health-status'],
      ['items', 'account-health-items', { optional: true }],
      ['stripe', 'account-health-stripe', { placement: 'top' }]
    ]
  }),
  defineChapter({
    id: 'team',
    prefix: 'sc.tour.team',
    journey: 'setup',
    route: '/team',
    audience: { permissions: ['team:view'] },
    steps: [
      ['page', 'page-team'],
      ['invite', 'page-team-actions', { optional: true }],
      ['people', 'table-seller.team', { placement: 'top' }],
      ['roles'],
      ['business', 'page-business-page', { route: '/team/business-page' }]
    ]
  }),
  defineChapter({
    id: 'account-settings',
    prefix: 'sc.tour.accountSettings',
    journey: 'setup',
    route: '/account',
    steps: [
      ['head', 'account-head'],
      ['nav', 'account-nav', { placement: 'end' }],
      ['profile', 'account-section', { placement: 'top' }],
      ['security', 'account-section', { route: '/account/security', placement: 'top' }],
      ['alerts', 'account-section', { route: '/account/notifications', placement: 'top' }],
      ['display', 'account-section', { route: '/account/preferences', placement: 'top' }]
    ]
  }),
  defineChapter({
    id: 'help-support',
    prefix: 'sc.tour.helpSupport',
    journey: 'setup',
    route: '/',
    steps: [['hello'], ['help', 'help-menu'], ['assistant', 'lola-launcher', { placement: 'start', optional: true }], ['wrap']]
  }),

  // ---------------------------------------------------------------- Products and stock
  defineChapter({
    id: 'listings-overview',
    prefix: 'sc.tour.listings',
    journey: 'catalogue',
    route: '/listings',
    audience: { permissions: ['listings:view'] },
    steps: [
      ['page', 'page-listings'],
      ['actions', 'page-listings-actions', { optional: true }],
      ['table', 'table-seller.listings', { placement: 'top' }],
      ['status']
    ]
  }),
  defineChapter({
    id: 'first-product',
    prefix: 'sc.tour.firstProduct',
    journey: 'catalogue',
    // 2: Add a product opens on the barcode step (S382). A page that already exists is sold with a short offer form; the full product form
    // (category, photos ...) comes after it, once the person carries on, so its steps are optional and appear only then.
    version: 2,
    route: '/listings',
    audience: { permissions: ['listings:edit'] },
    autoStart: { routes: ['/listings'] },
    steps: [
      ['add', 'listings-add', { doIt: true, success: { target: 'listings-add', when: 'click' } }],
      ['barcode', 'barcode-input', { route: '/listings/new', placement: 'bottom' }],
      ['offer-only'],
      ['carry-on', 'barcode-step', { placement: 'top', success: { target: 'product-form-basics', when: 'appear' } }],
      ['category', 'product-form-basics', { placement: 'top', optional: true }],
      ['photos', 'product-form-images', { placement: 'top', optional: true }],
      ['missing', 'product-form-readiness', { placement: 'start', optional: true }],
      ['submit', 'product-form-actions', { placement: 'top', optional: true }]
    ]
  }),
  defineChapter({
    id: 'product-sections',
    prefix: 'sc.tour.productSections',
    journey: 'catalogue',
    version: 2,
    route: '/listings/new',
    audience: { permissions: ['listings:edit'] },
    steps: [
      // The barcode step comes first; the sections below belong to the full form, which opens once the person carries on.
      ['carry-on', 'barcode-step', { placement: 'top', success: { target: 'product-form-basics', when: 'appear' } }],
      ['basics', 'product-form-basics', { placement: 'top', optional: true }],
      ['nav', 'product-form-nav', { optional: true }],
      ['offer', 'product-form-offer', { placement: 'top', optional: true }],
      ['facts', 'product-form-facts', { placement: 'top', optional: true }],
      ['variants', 'product-form-variants', { placement: 'top', optional: true }],
      ['sizes', 'product-form-sizeguide', { placement: 'top', optional: true }],
      ['fulfilment', 'product-form-fulfilment', { placement: 'top', optional: true }]
    ]
  }),
  defineChapter({
    id: 'images',
    prefix: 'sc.tour.images',
    journey: 'catalogue',
    route: '/listings/images',
    audience: { permissions: ['listings:edit'], flags: ['cap-imageLibrary'] },
    steps: [
      ['page', 'page-images'],
      ['actions', 'page-images-actions'],
      ['library', 'table-seller.image-library', { placement: 'top', optional: true }],
      ['names']
    ]
  }),
  defineChapter({
    id: 'bulk-upload',
    prefix: 'sc.tour.bulkUpload',
    journey: 'catalogue',
    route: '/listings/bulk',
    audience: { permissions: ['listings:edit'], flags: ['cap-bulkUpload'] },
    steps: [
      ['page', 'page-bulk'],
      ['types', 'bulk-upload-types'],
      ['template'],
      ['links', 'page-bulk-actions'],
      ['history', 'page-bulk-history', { route: '/listings/bulk/history' }],
      ['reports', 'table-seller.imports', { placement: 'top' }]
    ]
  }),
  defineChapter({
    id: 'approvals',
    prefix: 'sc.tour.approvals',
    journey: 'catalogue',
    route: '/listings',
    audience: { permissions: ['listings:edit'] },
    autoStart: { routes: ['/listings/approvals'] },
    steps: [
      ['button', 'listings-approvals'],
      ['find', 'approvals-find', { route: '/listings/approvals' }],
      ['status', 'approvals-first', { placement: 'top', optional: true }],
      ['expiry']
    ]
  }),
  defineChapter({
    id: 'inventory',
    prefix: 'sc.tour.inventory',
    journey: 'catalogue',
    route: '/inventory',
    audience: { permissions: ['inventory:view'] },
    autoStart: { routes: ['/inventory'] },
    steps: [
      ['page', 'page-inventory'],
      ['numbers', 'inventory-stats', { optional: true }],
      ['table', 'table-seller.inventory', { placement: 'top' }],
      ['count', 'inventory-adjust', { optional: true }]
    ]
  }),

  // ---------------------------------------------------------------- Orders and delivery
  defineChapter({
    id: 'orders',
    prefix: 'sc.tour.orders',
    journey: 'fulfilment',
    route: '/orders',
    audience: { permissions: ['orders:view'], flags: ['seller'] },
    autoStart: { routes: ['/orders'] },
    steps: [
      ['page', 'page-orders'],
      ['table', 'table-seller.orders', { placement: 'top' }],
      ['next', 'orders-next-step', { placement: 'start', optional: true }],
      ['pickup'],
      ['tools', 'page-orders-actions']
    ]
  }),
  defineChapter({
    id: 'shipping-setup',
    prefix: 'sc.tour.shippingSetup',
    journey: 'fulfilment',
    route: '/orders/ship-from',
    audience: { permissions: ['orders:view'], flags: ['seller'] },
    steps: [
      ['page', 'page-ship-from'],
      ['actions', 'page-ship-from-actions'],
      ['address', 'ship-from-address', { placement: 'top', optional: true }],
      ['pickup', 'ship-from-pickup', { placement: 'start', optional: true }]
    ]
  }),
  defineChapter({
    id: 'run-pickups',
    prefix: 'sc.tour.runPickups',
    journey: 'fulfilment',
    route: '/orders/run-pickups',
    audience: { permissions: ['orders:view'], flags: ['seller'] },
    steps: [['page', 'page-run-pickups'], ['about'], ['back', 'page-run-pickups-actions']]
  }),
  defineChapter({
    id: 'returns-disputes',
    prefix: 'sc.tour.returnsDisputes',
    journey: 'fulfilment',
    route: '/returns',
    audience: { permissions: ['returns:view'], flags: ['seller'] },
    steps: [
      ['page', 'page-returns'],
      ['filter', 'page-returns-actions'],
      ['table', 'table-seller.returns', { placement: 'top' }],
      ['disputes', 'page-disputes', { route: '/disputes', when: 'can-disputes' }],
      ['claims', 'table-seller.disputes', { placement: 'top', when: 'can-disputes' }]
    ]
  }),

  // ---------------------------------------------------------------- Money and growth
  defineChapter({
    id: 'dashboard',
    prefix: 'sc.tour.dashboard',
    journey: 'money',
    route: '/',
    audience: { flags: ['approved'] },
    steps: [
      ['page', 'page-home'],
      ['period', 'page-home-actions'],
      ['reports', 'home-reports', { placement: 'top', optional: true }],
      ['tables']
    ]
  }),
  defineChapter({
    id: 'payouts',
    prefix: 'sc.tour.payouts',
    journey: 'money',
    route: '/payouts',
    audience: { permissions: ['payouts:view'], flags: ['approved'] },
    autoStart: { routes: ['/payouts'] },
    steps: [
      ['balance', 'payouts-balance'],
      ['request', 'page-payouts-actions', { optional: true }],
      ['history', 'table-seller.payouts', { placement: 'top', optional: true }],
      ['tax', 'payouts-tax', { placement: 'top' }],
      ['statement', 'page-tax', { route: '/payouts/tax' }]
    ]
  }),
  defineChapter({
    id: 'fees-billing',
    prefix: 'sc.tour.feesBilling',
    journey: 'money',
    route: '/fees',
    audience: { permissions: ['fees:view'], flags: ['approved'] },
    steps: [
      ['page', 'page-fees'],
      ['rates'],
      ['table', 'table-seller.fees', { placement: 'top', optional: true }],
      ['plan', 'page-billing', { route: '/billing', when: 'can-billing' }],
      ['invoices', 'table-seller.invoices', { placement: 'top', optional: true, when: 'can-billing' }]
    ]
  }),
  defineChapter({
    id: 'coupons',
    prefix: 'sc.tour.coupons',
    journey: 'money',
    route: '/coupons',
    audience: { permissions: ['coupons:view'], flags: ['seller'] },
    steps: [
      ['page', 'page-coupons'],
      ['create', 'page-coupons-actions', { optional: true }],
      ['table', 'table-seller.coupons', { placement: 'top' }],
      ['scope']
    ]
  }),
  defineChapter({
    id: 'reviews',
    prefix: 'sc.tour.reviews',
    journey: 'money',
    route: '/reviews',
    audience: { permissions: ['reviews:view'], flags: ['seller'] },
    steps: [['page', 'page-reviews'], ['filter', 'page-reviews-actions'], ['replies']]
  }),

  // ---------------------------------------------------------------- Your services (providers)
  defineChapter({
    id: 'services',
    prefix: 'sc.tour.services',
    journey: 'services',
    route: '/services',
    audience: { permissions: ['services:view'], flags: ['offers-services'] },
    autoStart: { routes: ['/services'] },
    steps: [
      ['add', 'page-services-actions', { optional: true }],
      ['list', 'table-seller.services', { placement: 'top' }],
      ['area', 'service-area']
    ]
  }),
  defineChapter({
    id: 'availability',
    prefix: 'sc.tour.availability',
    journey: 'services',
    route: '/hours',
    audience: { anyPermissions: ['hours:view', 'policy:view'], flags: ['offers-services'] },
    steps: [
      ['hours', 'page-hours', { when: 'can-hours' }],
      ['policy', 'page-policy', { route: '/policy', when: 'can-policy' }],
      ['wrap']
    ]
  }),
  defineChapter({
    id: 'bookings',
    prefix: 'sc.tour.bookings',
    journey: 'services',
    route: '/requests',
    audience: { anyPermissions: ['requests:view', 'calendar:view'], flags: ['offers-services'] },
    steps: [
      ['requests', 'page-requests', { when: 'can-requests' }],
      ['calendar', 'page-calendar', { route: '/calendar', when: 'can-calendar' }],
      ['wrap']
    ]
  }),
  defineChapter({
    id: 'provider-earnings',
    prefix: 'sc.tour.providerEarnings',
    journey: 'services',
    route: '/earnings',
    audience: { permissions: ['earnings:view'], flags: ['offers-services'] },
    steps: [
      ['page', 'page-earnings'],
      ['table', 'table-seller.earnings', { placement: 'top' }],
      ['payouts', 'page-payouts', { route: '/payouts', when: 'can-payouts' }]
    ]
  })
];
