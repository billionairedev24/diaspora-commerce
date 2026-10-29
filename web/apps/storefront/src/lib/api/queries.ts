/* Re-exports used by app code so components import query helpers from one place. */
export { accountQueries, careersQueries, cartQueries, helpQueries, membershipQueries, productQueries, searchHref, searchQueries, shopperQueries, siteQueries } from '@afrotransact/api-client';

/** Department bar order (matches the design and the catalogue's top-level departments). */
export const DEPARTMENTS_LIST = ['Groceries', 'Fashion', 'Beauty', 'Home & Art', 'Books', 'Health', 'Party'] as const;
