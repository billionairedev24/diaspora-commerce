import { defineTour, tourText } from '@afrotransact/tour';

/*
 * Staff console tours. Targets are `data-tour` ids placed on the real components (see docs/engineering/guided-tours.md); text is in
 * src/i18n/*.json under `st.tour.<tour>.<step>.title|body`. Plain words, nothing that is not built today.
 *
 * Who sees what: `permissions` are `<module>:<level>` (levels view, edit, approve, full; each includes the ones before it, and the
 * module is the one that owns the page), so a person only gets the tours their permissions reach.
 */

const home = (s: string) => tourText('st.tour.adminHome', s);
const apps = (s: string) => tourText('st.tour.applications', s);
const moderation = (s: string) => tourText('st.tour.moderation', s);
const tax = (s: string) => tourText('st.tour.tax', s);
const orders = (s: string) => tourText('st.tour.orders', s);
const settings = (s: string) => tourText('st.tour.settings', s);
const roles = (s: string) => tourText('st.tour.roles', s);

export const STAFF_TOURS = [
  defineTour({
    id: 'admin-home',
    version: 1,
    title: 'st.tour.adminHome.title',
    summary: 'st.tour.adminHome.summary',
    autoStart: { routes: ['/'] },
    steps: [
      { id: 'hello', placement: 'center', ...home('hello') },
      { id: 'menu', target: 'sidebar', placement: 'end', optional: true, ...home('menu') },
      { id: 'home', target: 'page-dash', route: '/', placement: 'bottom', ...home('home') },
      { id: 'help', target: 'help-menu', placement: 'bottom', ...home('help') }
    ]
  }),
  defineTour({
    id: 'applications-review',
    version: 1,
    title: 'st.tour.applications.title',
    summary: 'st.tour.applications.summary',
    audience: { permissions: ['apps:view'] },
    route: '/applications',
    autoStart: { routes: ['/applications'] },
    steps: [
      { id: 'page', target: 'page-apps', placement: 'bottom', ...apps('page') },
      { id: 'table', target: 'table-staff.applications', placement: 'top', ...apps('table') },
      { id: 'two-step', placement: 'center', ...apps('twoStep') },
      { id: 'same-person', placement: 'center', ...apps('samePerson') }
    ]
  }),
  defineTour({
    id: 'moderation-queue',
    version: 1,
    title: 'st.tour.moderation.title',
    summary: 'st.tour.moderation.summary',
    audience: { permissions: ['catalog:view'] },
    route: '/moderation',
    autoStart: { routes: ['/moderation'] },
    steps: [
      { id: 'page', target: 'page-catalog', placement: 'bottom', ...moderation('page') },
      { id: 'queue', target: 'table-staff.moderation', placement: 'top', ...moderation('queue') },
      { id: 'tabs', placement: 'center', ...moderation('tabs') }
    ]
  }),
  defineTour({
    id: 'tax-review',
    version: 1,
    title: 'st.tour.tax.title',
    summary: 'st.tour.tax.summary',
    audience: { permissions: ['fees:view'] },
    route: '/settings?section=tax&tab=tax-review',
    steps: [
      { id: 'views', target: 'tax-review-views', placement: 'bottom', ...tax('views') },
      { id: 'queue', target: 'table-staff.tax.review.queue', placement: 'top', ...tax('queue') },
      { id: 'banner', target: 'tax-gate-banner', placement: 'bottom', optional: true, ...tax('banner') },
      { id: 'sellers', placement: 'center', ...tax('sellers') }
    ]
  }),
  defineTour({
    id: 'orders-shipments',
    version: 1,
    title: 'st.tour.orders.title',
    summary: 'st.tour.orders.summary',
    audience: { permissions: ['orders:view'] },
    route: '/orders',
    autoStart: { routes: ['/orders'] },
    steps: [
      { id: 'page', target: 'page-orders', placement: 'bottom', ...orders('page') },
      { id: 'table', target: 'table-staff.orders', placement: 'top', ...orders('table') },
      { id: 'steps', placement: 'center', ...orders('steps') },
      { id: 'picked-up', placement: 'center', ...orders('pickedUp') }
    ]
  }),
  defineTour({
    id: 'settings',
    version: 1,
    title: 'st.tour.settings.title',
    summary: 'st.tour.settings.summary',
    audience: { anyPermissions: ['plans:view', 'preorders:view', 'markets:view', 'fees:view', 'siteCfg:view'] },
    route: '/settings',
    steps: [
      { id: 'hub', target: 'settings-hub', placement: 'bottom', ...settings('hub') },
      { id: 'membership', target: 'settings-section', route: '/settings?section=membership', when: 'can-settings-membership', placement: 'bottom', ...settings('membership') },
      { id: 'shipping', target: 'settings-section', route: '/settings?section=shipping', when: 'can-settings-shipping', placement: 'bottom', ...settings('shipping') },
      { id: 'delivery-fees', target: 'settings-section', route: '/settings?section=delivery-fees', when: 'can-settings-delivery-fees', placement: 'bottom', ...settings('deliveryFees') },
      { id: 'markets', target: 'settings-section', route: '/settings?section=markets', when: 'can-settings-markets', placement: 'bottom', ...settings('markets') }
    ]
  }),
  defineTour({
    id: 'roles-permissions',
    version: 1,
    title: 'st.tour.roles.title',
    summary: 'st.tour.roles.summary',
    audience: { permissions: ['rbac:view'] },
    route: '/users',
    autoStart: { routes: ['/users'] },
    steps: [
      { id: 'page', target: 'page-rbac', placement: 'bottom', ...roles('page') },
      { id: 'people', target: 'table-staff.users', placement: 'top', ...roles('people') },
      { id: 'levels', placement: 'center', ...roles('levels') },
      { id: 'tours', placement: 'center', ...roles('tours') }
    ]
  })
];
