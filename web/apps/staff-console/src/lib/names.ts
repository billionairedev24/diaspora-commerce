import { type MessageKey, type Translator } from '@/i18n/t';

/*
 * Names, never ids. The BFF names every person and organisation it shows ("Unknown seller" when it cannot); these
 * helpers are the fallback for a response that carries no name, so an internal id (org_…, a user UUID) is never what a
 * person reads.
 */

type T = Translator<MessageKey>;

const named = (name: string | null | undefined): string | null => (name && name.trim() ? name : null);

/** A seller or organisation. */
export const sellerName = (t: T, name: string | null | undefined): string => named(name) ?? t('st.names.unknownSeller');

/** A customer, staff member, reviewer or whoever did something. */
export const personName = (t: T, name: string | null | undefined): string => named(name) ?? t('st.names.unknownUser');

/** A driver. */
export const driverName = (t: T, name: string | null | undefined): string => named(name) ?? t('st.names.unknownDriver');

/** A route. */
export const routeName = (t: T, name: string | null | undefined): string => named(name) ?? t('st.names.unknownRoute');
