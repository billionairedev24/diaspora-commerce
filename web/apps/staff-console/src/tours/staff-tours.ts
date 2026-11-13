import { defineChapter, type TourJourney } from '@afrotransact/tour';

/*
 * Staff console tours, as short chapters (3 to 8 steps) grouped into journeys for the Tour hub. Targets are `data-tour` ids placed on the
 * real components (see docs/engineering/guided-tours.md); text is in src/i18n/*.json under `st.tour.<chapter>.<step>.title|body`. Plain
 * words, nothing that is not built today, no tax advice. Bump a chapter's `version` when its words change enough to deserve a
 * "What's new" mark in the hub.
 *
 * Who sees what: `permissions` are `<module>:<level>` (levels view, edit, approve, full; each includes the ones before it, and the
 * module is the one that owns the page), so a person only gets the chapters their permissions reach. A step's `when` names a guard
 * (`can-<module>`, `can-settings-<section>`) so a chapter never walks someone to a page their role cannot open. Only the first-time
 * chapters of the busiest pages start by themselves; every other chapter is started from the Tour hub.
 */

export const STAFF_JOURNEYS: readonly TourJourney[] = [
  { id: 'start', title: 'st.tour.journey.start.title', summary: 'st.tour.journey.start.summary' },
  { id: 'partners', title: 'st.tour.journey.partners.title', summary: 'st.tour.journey.partners.summary' },
  { id: 'catalogue', title: 'st.tour.journey.catalogue.title', summary: 'st.tour.journey.catalogue.summary' },
  { id: 'commerce', title: 'st.tour.journey.commerce.title', summary: 'st.tour.journey.commerce.summary' },
  { id: 'operations', title: 'st.tour.journey.operations.title', summary: 'st.tour.journey.operations.summary' },
  { id: 'platform', title: 'st.tour.journey.platform.title', summary: 'st.tour.journey.platform.summary' }
];

export const STAFF_TOURS = [
  // ---------------------------------------------------------------- Find your way
  defineChapter({
    id: 'admin-home',
    prefix: 'st.tour.adminHome',
    journey: 'start',
    autoStart: { routes: ['/'] },
    steps: [['hello'], ['menu', 'sidebar', { placement: 'end', optional: true }], ['home', 'page-dash', { route: '/' }], ['help', 'help-menu']]
  }),
  defineChapter({
    id: 'my-account',
    prefix: 'st.tour.myAccount',
    journey: 'start',
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

  // ---------------------------------------------------------------- Sellers and providers
  defineChapter({
    id: 'applications-review',
    prefix: 'st.tour.applications',
    journey: 'partners',
    route: '/applications',
    audience: { permissions: ['apps:view'] },
    autoStart: { routes: ['/applications'] },
    steps: [['page', 'page-apps'], ['table', 'table-staff.applications', { placement: 'top' }], ['two-step'], ['same-person'], ['sla']]
  }),
  defineChapter({
    id: 'selling-approvals',
    prefix: 'st.tour.sellingApprovals',
    journey: 'partners',
    route: '/applications?tab=selling-approvals',
    audience: { permissions: ['apps:view'] },
    steps: [
      ['cases', 'table-staff.selling-approvals', { placement: 'top' }],
      ['held', 'table-staff.seller-approvals', { route: '/applications?tab=seller-approvals', placement: 'top' }],
      ['wrap']
    ]
  }),
  defineChapter({
    id: 'organisations',
    prefix: 'st.tour.organisations',
    journey: 'partners',
    route: '/organisations',
    audience: { permissions: ['orgs:view'] },
    steps: [
      ['page', 'page-orgs'],
      ['table', 'table-staff.organisations', { placement: 'top' }],
      ['connect', 'table-staff.connect-accounts', { route: '/organisations?tab=connect-accounts', placement: 'top' }],
      ['wrap']
    ]
  }),
  defineChapter({
    id: 'department-requests',
    prefix: 'st.tour.departmentRequests',
    journey: 'partners',
    route: '/department-requests',
    audience: { permissions: ['deptRequests:view'] },
    steps: [
      ['page', 'page-deptrequests'],
      ['pending', 'table-staff.departments.pending', { placement: 'top' }],
      ['decided', 'table-staff.departments.decided', { route: '/department-requests?tab=decided', placement: 'top' }]
    ]
  }),
  defineChapter({
    id: 'delivery-quotes',
    prefix: 'st.tour.deliveryQuotes',
    journey: 'partners',
    route: '/delivery-quotes',
    audience: { permissions: ['deliveryQuotes:view'] },
    steps: [
      ['page', 'page-deliveryquotes'],
      ['quotes', 'table-staff.delivery-quotes', { placement: 'top' }],
      ['settings', 'table-staff.delivery-quote-settings', { route: '/delivery-quotes?tab=delivery-quote-settings', placement: 'top' }]
    ]
  }),

  // ---------------------------------------------------------------- Catalogue and rules
  defineChapter({
    id: 'moderation-queue',
    prefix: 'st.tour.moderation',
    journey: 'catalogue',
    route: '/moderation',
    audience: { permissions: ['catalog:view'] },
    autoStart: { routes: ['/moderation'] },
    steps: [
      ['page', 'page-catalog'],
      ['queue', 'table-staff.moderation', { placement: 'top' }],
      ['reviews', 'table-staff.product-reviews', { route: '/moderation?tab=product-reviews', placement: 'top' }],
      ['products', 'table-staff.products', { route: '/moderation?tab=products', placement: 'top' }],
      ['tabs']
    ]
  }),
  defineChapter({
    id: 'catalogue-rules',
    prefix: 'st.tour.catalogueRules',
    journey: 'catalogue',
    route: '/moderation',
    audience: { permissions: ['catalog:view'] },
    steps: [
      ['intro'],
      ['requests', 'table-staff.category-requests', { route: '/moderation?tab=category-requests', placement: 'top', optional: true }],
      ['brands', 'table-staff.brands', { route: '/moderation?tab=brands', placement: 'top', optional: true }],
      ['rules', 'table-staff.selling-rules', { route: '/moderation?tab=selling-rules', placement: 'top' }],
      ['wrap']
    ]
  }),

  // ---------------------------------------------------------------- Orders and money
  defineChapter({
    id: 'orders-shipments',
    prefix: 'st.tour.orders',
    journey: 'commerce',
    route: '/orders',
    audience: { permissions: ['orders:view'] },
    autoStart: { routes: ['/orders'] },
    steps: [['page', 'page-orders'], ['table', 'table-staff.orders', { placement: 'top' }], ['steps'], ['picked-up']]
  }),
  defineChapter({
    id: 'disputes-returns',
    prefix: 'st.tour.disputes',
    journey: 'commerce',
    route: '/disputes',
    audience: { permissions: ['disputes:view'] },
    steps: [
      ['page', 'page-disputes'],
      ['claims', 'table-staff.claims', { placement: 'top' }],
      ['returns', 'table-staff.returns', { route: '/disputes?tab=returns', placement: 'top' }],
      ['wrap']
    ]
  }),
  defineChapter({
    id: 'support-refunds',
    prefix: 'st.tour.support',
    journey: 'commerce',
    route: '/support',
    audience: { permissions: ['support:view'] },
    steps: [['page', 'page-support'], ['cases', 'table-staff.support-cases', { placement: 'top', optional: true }], ['lookup'], ['refunds']]
  }),
  defineChapter({
    id: 'payouts',
    prefix: 'st.tour.payouts',
    journey: 'commerce',
    route: '/payouts',
    audience: { permissions: ['payouts:view'] },
    steps: [
      ['page', 'page-payouts'],
      ['requests', 'table-staff.payouts', { placement: 'top' }],
      ['reconciliation', 'table-staff.reconciliation', { route: '/payouts?tab=reconciliation', placement: 'top' }],
      ['giftcards', 'table-staff.gift-cards', { route: '/payouts?tab=gift-cards', placement: 'top' }],
      ['members', 'table-staff.memberships', { route: '/payouts?tab=memberships', placement: 'top' }]
    ]
  }),
  defineChapter({
    id: 'tax-review',
    prefix: 'st.tour.tax',
    journey: 'commerce',
    route: '/settings?section=tax&tab=tax-review',
    audience: { permissions: ['fees:view'] },
    steps: [
      ['views', 'tax-review-views'],
      ['queue', 'table-staff.tax.review.queue', { placement: 'top' }],
      ['banner', 'tax-gate-banner', { optional: true }],
      ['sellers']
    ]
  }),
  defineChapter({
    id: 'tax-profiles',
    prefix: 'st.tour.taxProfiles',
    journey: 'commerce',
    route: '/settings?section=tax&tab=tax-method',
    audience: { permissions: ['fees:view'] },
    steps: [
      ['method', 'settings-section'],
      ['profiles', 'table-staff.tax.profiles', { route: '/settings?section=tax&tab=tax-profiles', placement: 'top' }],
      ['decisions', 'table-staff.tax.review.decisions', { route: '/settings?section=tax&tab=tax-review&review.view=decisions', placement: 'top' }],
      ['codes', 'settings-section', { route: '/settings?section=tax&tab=tax-codes' }],
      ['wrap']
    ]
  }),
  defineChapter({
    id: 'fees-plans',
    prefix: 'st.tour.feesPlans',
    journey: 'commerce',
    route: '/settings?section=fees',
    audience: { permissions: ['fees:view'] },
    steps: [
      ['schedules', 'table-staff.fee-schedule-versions', { placement: 'top' }],
      ['commission', 'settings-section', { route: '/settings?section=fees&tab=commission' }],
      ['negotiated', 'settings-section', { route: '/settings?section=fees&tab=negotiated' }],
      ['plans', 'table-staff.plans.catalogue', { route: '/plans', placement: 'top', when: 'can-plans' }],
      ['wrap']
    ]
  }),

  // ---------------------------------------------------------------- Operations
  defineChapter({
    id: 'inventory',
    prefix: 'st.tour.inventory',
    journey: 'operations',
    route: '/inventory',
    audience: { permissions: ['inventory:view'] },
    steps: [
      ['page', 'page-inventory'],
      ['flow'],
      ['inbound', 'table-staff.inventory.inbound', { route: '/inventory?tab=inbound', placement: 'top' }],
      ['stock', 'table-staff.inventory.lots', { route: '/inventory?tab=stock', placement: 'top' }],
      ['counts', 'table-staff.inventory.counts', { route: '/inventory?tab=counts', placement: 'top' }],
      ['expiry', 'table-staff.inventory.expiry', { route: '/inventory?tab=expiry', placement: 'top' }],
      ['movements', 'table-staff.inventory.movements', { route: '/inventory?tab=movements', placement: 'top' }]
    ]
  }),
  defineChapter({
    id: 'retail',
    prefix: 'st.tour.retail',
    journey: 'operations',
    route: '/retail',
    audience: { permissions: ['retail:view'] },
    steps: [
      ['page', 'page-retail'],
      ['orders', 'table-staff.retail.pos', { placement: 'top' }],
      ['items', 'table-staff.retail.items', { route: '/retail?tab=items', placement: 'top' }],
      ['receipts', 'table-staff.retail.receipts', { route: '/retail?tab=receipts', placement: 'top' }],
      ['invoices', 'table-staff.retail.invoices', { route: '/retail?tab=invoices', placement: 'top' }],
      ['suppliers', 'table-staff.retail.suppliers', { route: '/retail?tab=suppliers', placement: 'top' }]
    ]
  }),
  defineChapter({
    id: 'dispatch',
    prefix: 'st.tour.dispatch',
    journey: 'operations',
    route: '/dispatch',
    audience: { permissions: ['dispatch:view'] },
    steps: [
      ['page', 'page-dispatch'],
      ['routes', 'table-staff.routes', { placement: 'top' }],
      ['runs', 'table-staff.runs', { route: '/dispatch?tab=runs', placement: 'top' }],
      ['pickups', 'table-staff.pickups', { route: '/dispatch?tab=pickups', placement: 'top' }],
      ['wrap']
    ]
  }),
  defineChapter({
    id: 'preorders',
    prefix: 'st.tour.preorders',
    journey: 'operations',
    route: '/preorders',
    audience: { permissions: ['preorders:view'] },
    steps: [['page', 'page-preorders'], ['list', 'table-staff.preorders', { placement: 'top', optional: true }], ['windows'], ['fees']]
  }),
  defineChapter({
    id: 'exports',
    prefix: 'st.tour.exports',
    journey: 'operations',
    route: '/exports',
    audience: { permissions: ['exports:view'] },
    steps: [['page', 'page-exports'], ['table', 'table-staff.exports', { placement: 'top' }], ['wrap']]
  }),

  // ---------------------------------------------------------------- Platform settings and access
  defineChapter({
    id: 'roles-permissions',
    prefix: 'st.tour.roles',
    journey: 'platform',
    route: '/users',
    audience: { permissions: ['rbac:view'] },
    autoStart: { routes: ['/users'] },
    steps: [['page', 'page-rbac'], ['people', 'table-staff.users', { placement: 'top' }], ['levels'], ['tours']]
  }),
  defineChapter({
    id: 'settings',
    prefix: 'st.tour.settings',
    journey: 'platform',
    route: '/settings',
    audience: { anyPermissions: ['plans:view', 'preorders:view', 'markets:view', 'fees:view', 'siteCfg:view'] },
    steps: [
      ['hub', 'settings-hub'],
      ['membership', 'settings-section', { route: '/settings?section=membership', when: 'can-settings-membership' }],
      ['shipping', 'settings-section', { route: '/settings?section=shipping', when: 'can-settings-shipping' }],
      ['delivery-fees', 'settings-section', { route: '/settings?section=delivery-fees', when: 'can-settings-delivery-fees' }],
      ['markets', 'settings-section', { route: '/settings?section=markets', when: 'can-settings-markets' }]
    ]
  }),
  defineChapter({
    id: 'site-content',
    prefix: 'st.tour.siteContent',
    journey: 'platform',
    route: '/settings?section=published-pages',
    audience: { permissions: ['siteCfg:view'] },
    steps: [
      ['pages', 'table-staff.published-pages', { placement: 'top' }],
      ['general', 'settings-section', { route: '/settings?section=general' }],
      ['wrap']
    ]
  }),
  defineChapter({
    id: 'notifications',
    prefix: 'st.tour.notifications',
    journey: 'platform',
    route: '/settings?section=notifications',
    audience: { permissions: ['notifications:view'] },
    steps: [['page', 'settings-section'], ['templates', 'table-staff.notification-templates', { placement: 'top' }], ['wrap']]
  }),
  defineChapter({
    id: 'identity-access',
    prefix: 'st.tour.identity',
    journey: 'platform',
    route: '/settings?section=identity',
    audience: { permissions: ['identity:view'] },
    steps: [
      ['page', 'settings-section'],
      ['clients', 'table-staff.oauth-clients', { placement: 'top' }],
      ['sessions', 'table-staff.identity-sessions', { route: '/settings?section=identity&tab=identity-sessions', placement: 'top' }],
      ['wrap']
    ]
  }),
  defineChapter({
    id: 'marketing',
    prefix: 'st.tour.marketing',
    journey: 'platform',
    route: '/marketing',
    audience: { permissions: ['marketing:view'] },
    steps: [
      ['page', 'page-marketing'],
      ['announcements', 'table-staff.announcements', { placement: 'top' }],
      ['placements', 'table-staff.placements', { route: '/marketing?tab=placements', placement: 'top' }],
      ['home', 'home-settings', { route: '/marketing?tab=home', placement: 'top' }],
      ['preview', 'home-preview', { route: '/marketing?tab=preview', placement: 'top' }],
      ['coupons', 'table-staff.coupons', { route: '/marketing?tab=coupons', placement: 'top' }]
    ]
  }),
  defineChapter({
    id: 'audit-log',
    prefix: 'st.tour.audit',
    journey: 'platform',
    route: '/audit',
    audience: { permissions: ['audit:view'] },
    steps: [['page', 'page-audit'], ['table', 'table-staff.audit', { placement: 'top' }], ['wrap']]
  })
];
