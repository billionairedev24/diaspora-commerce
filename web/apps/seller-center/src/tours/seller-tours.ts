import { defineTour, tourText } from '@afrotransact/tour';

/*
 * Seller Center and Provider Hub tours. Targets are `data-tour` ids placed on the real components (see docs/engineering/guided-tours.md);
 * text is in src/i18n/*.json under `sc.tour.<tour>.<step>.title|body`. Rules for the words: plain, no jargon, names not ids, no
 * mention of carriers, nothing that is not built today. Bump a tour's `version` when it changes enough to show it again.
 *
 * Who sees what: `permissions` are `<module>:<level>` (levels view, edit, approve, full; each includes the ones before it),
 * `flags` are `seller`, `provider`, `approved`, `offers-services`.
 */

const welcome = (s: string) => tourText('sc.tour.welcome', s);
const product = (s: string) => tourText('sc.tour.firstProduct', s);
const approvals = (s: string) => tourText('sc.tour.approvals', s);
const stock = (s: string) => tourText('sc.tour.inventory', s);
const orders = (s: string) => tourText('sc.tour.orders', s);
const payouts = (s: string) => tourText('sc.tour.payouts', s);
const services = (s: string) => tourText('sc.tour.services', s);

export const SELLER_TOURS = [
  defineTour({
    id: 'welcome',
    version: 1,
    title: 'sc.tour.welcome.title',
    summary: 'sc.tour.welcome.summary',
    autoStart: { routes: ['/', '/onboarding'] },
    steps: [
      { id: 'hello', placement: 'center', ...welcome('hello') },
      { id: 'menu', target: 'sidebar', placement: 'end', optional: true, ...welcome('menu') },
      { id: 'business', target: 'org-switcher', placement: 'end', optional: true, ...welcome('business') },
      { id: 'application', target: 'onboarding-status', when: 'applying', optional: true, ...welcome('application') },
      { id: 'steps', target: 'onboarding-stepper', when: 'applying', optional: true, ...welcome('steps') },
      { id: 'home', target: 'page-home', route: '/', when: 'approved', ...welcome('home') },
      { id: 'help', target: 'help-menu', placement: 'bottom', ...welcome('help') }
    ]
  }),
  defineTour({
    id: 'first-product',
    version: 1,
    title: 'sc.tour.firstProduct.title',
    summary: 'sc.tour.firstProduct.summary',
    audience: { permissions: ['listings:edit'] },
    route: '/listings',
    autoStart: { routes: ['/listings'] },
    steps: [
      { id: 'add', target: 'listings-add', placement: 'bottom', ...product('add') },
      { id: 'category', target: 'product-form-basics', route: '/listings/new', placement: 'top', ...product('category') },
      { id: 'photos', target: 'product-form-images', placement: 'top', ...product('photos') },
      { id: 'nav', target: 'product-form-nav', placement: 'bottom', ...product('nav') },
      { id: 'missing', target: 'product-form-readiness', placement: 'start', optional: true, ...product('missing') },
      { id: 'submit', target: 'product-form-actions', placement: 'top', ...product('submit') }
    ]
  }),
  defineTour({
    id: 'approvals',
    version: 1,
    title: 'sc.tour.approvals.title',
    summary: 'sc.tour.approvals.summary',
    audience: { permissions: ['listings:edit'] },
    route: '/listings',
    autoStart: { routes: ['/listings/approvals'] },
    steps: [
      { id: 'button', target: 'listings-approvals', placement: 'bottom', ...approvals('button') },
      { id: 'find', target: 'approvals-find', route: '/listings/approvals', placement: 'bottom', ...approvals('find') },
      { id: 'status', target: 'approvals-first', placement: 'top', optional: true, ...approvals('status') },
      { id: 'expiry', placement: 'center', ...approvals('expiry') }
    ]
  }),
  defineTour({
    id: 'inventory',
    version: 1,
    title: 'sc.tour.inventory.title',
    summary: 'sc.tour.inventory.summary',
    audience: { permissions: ['inventory:view'] },
    route: '/inventory',
    autoStart: { routes: ['/inventory'] },
    steps: [
      { id: 'page', target: 'page-inventory', placement: 'bottom', ...stock('page') },
      { id: 'numbers', target: 'inventory-stats', placement: 'bottom', optional: true, ...stock('numbers') },
      { id: 'table', target: 'table-seller.inventory', placement: 'top', ...stock('table') },
      { id: 'count', target: 'inventory-adjust', placement: 'bottom', optional: true, ...stock('count') }
    ]
  }),
  defineTour({
    id: 'orders',
    version: 1,
    title: 'sc.tour.orders.title',
    summary: 'sc.tour.orders.summary',
    audience: { permissions: ['orders:view'], flags: ['seller'] },
    route: '/orders',
    autoStart: { routes: ['/orders'] },
    steps: [
      { id: 'page', target: 'page-orders', placement: 'bottom', ...orders('page') },
      { id: 'table', target: 'table-seller.orders', placement: 'top', ...orders('table') },
      { id: 'next', target: 'orders-next-step', placement: 'start', optional: true, ...orders('next') },
      { id: 'pickup', placement: 'center', ...orders('pickup') },
      { id: 'tools', target: 'page-orders-actions', placement: 'bottom', ...orders('tools') }
    ]
  }),
  defineTour({
    id: 'payouts',
    version: 1,
    title: 'sc.tour.payouts.title',
    summary: 'sc.tour.payouts.summary',
    audience: { permissions: ['payouts:view'], flags: ['approved'] },
    route: '/payouts',
    autoStart: { routes: ['/payouts'] },
    steps: [
      { id: 'balance', target: 'payouts-balance', placement: 'bottom', ...payouts('balance') },
      { id: 'request', target: 'page-payouts-actions', placement: 'bottom', optional: true, ...payouts('request') },
      { id: 'tax', target: 'payouts-tax', placement: 'top', ...payouts('tax') },
      { id: 'status', target: 'account-health-status', route: '/account-health', placement: 'bottom', ...payouts('status') },
      { id: 'stripe', target: 'account-health-stripe', placement: 'top', ...payouts('stripe') }
    ]
  }),
  defineTour({
    id: 'services',
    version: 1,
    title: 'sc.tour.services.title',
    summary: 'sc.tour.services.summary',
    audience: { permissions: ['services:view'], flags: ['offers-services'] },
    route: '/services',
    autoStart: { routes: ['/services'] },
    steps: [
      { id: 'add', target: 'page-services-actions', placement: 'bottom', optional: true, ...services('add') },
      { id: 'area', target: 'service-area', placement: 'bottom', ...services('area') },
      { id: 'hours', target: 'page-hours', route: '/hours', when: 'can-hours', placement: 'bottom', ...services('hours') },
      { id: 'requests', target: 'page-requests', route: '/requests', when: 'can-requests', placement: 'bottom', ...services('requests') },
      { id: 'calendar', target: 'page-calendar', route: '/calendar', when: 'can-calendar', placement: 'bottom', ...services('calendar') }
    ]
  })
];
