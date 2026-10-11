import {
  type OnboardingBusinessInput,
  type OnboardingDetailsInput,
  type OnboardingEntityType,
  type OnboardingForm,
  type OnboardingOffering,
  type OrgKind
} from '@afrotransact/api-client';

/* The application wizard's first two steps ("Your business", "About you") as editable text, prefilled from what the
   account already has (S286): name, email and phone from the profile, the default address-book address as the
   business address, and a store name when the person already runs an organisation. */

export interface OnboardingValues {
  offering: OnboardingOffering;
  entityType: OnboardingEntityType | '';
  storeName: string;
  contactEmail: string;
  phone: string;
  website: string;
  legalFirstName: string;
  legalLastName: string;
  legalName: string;
  line1: string;
  line2: string;
  city: string;
  state: string;
  postalCode: string;
}

/** Wizard field → the `form.prefilled` name it shows the "From your account" tag for. */
export const PREFILL_OF: Partial<Record<keyof OnboardingValues, string>> = {
  storeName: 'business.storeName',
  contactEmail: 'business.contactEmail',
  phone: 'business.phone',
  legalFirstName: 'details.legalFirstName',
  legalLastName: 'details.legalLastName',
  line1: 'details.address',
  line2: 'details.address',
  city: 'details.address',
  state: 'details.address',
  postalCode: 'details.address'
};

const text = (v: string | null | undefined): string => v ?? '';

/** National display of a US number (+17132490142 → (713) 249-0142); other numbers are shown as stored. */
export function displayPhone(e164: string | null | undefined): string {
  const m = /^\+1(\d{3})(\d{3})(\d{4})$/.exec(e164 ?? '');
  return m ? `(${m[1]}) ${m[2]}-${m[3]}` : text(e164);
}

/** Editable values of the form; a new application offers products (Seller Center) or services (Provider Hub). */
export function formValues(form: OnboardingForm, kind: OrgKind): OnboardingValues {
  const b = form.business;
  const d = form.details;
  const a = d.address;
  return {
    offering: b.offering ?? (kind === 'PROVIDER' ? 'SERVICES' : 'PRODUCTS'),
    entityType: b.entityType ?? '',
    storeName: text(b.storeName),
    contactEmail: text(b.contactEmail),
    phone: displayPhone(b.phone),
    website: text(b.website),
    legalFirstName: text(d.legalFirstName),
    legalLastName: text(d.legalLastName),
    legalName: text(d.legalName),
    line1: text(a?.line1),
    line2: text(a?.line2),
    city: text(a?.city),
    state: text(a?.state),
    postalCode: text(a?.postalCode)
  };
}

/** Whether the field still holds the value taken from the account. */
export const isPrefilled = (form: OnboardingForm, field: keyof OnboardingValues): boolean => {
  const name = PREFILL_OF[field];
  return name !== undefined && form.prefilled.includes(name);
};

/**
 * The design's prefill banner ("Using your existing AfroTransact account."): what was filled in, with the address;
 * null when nothing was.
 */
export function prefillNote(form: OnboardingForm): string | null {
  const has = (n: string) => form.prefilled.includes(n);
  const parts: string[] = [];
  if (has('details.legalFirstName') || has('details.legalLastName')) parts.push('name');
  if (has('business.contactEmail')) parts.push('email');
  if (has('business.phone')) parts.push('phone');
  if (has('details.address')) parts.push('address');
  if (has('business.storeName')) parts.push('business name');
  if (!parts.length) return null;
  const list = parts.length === 1 ? parts[0] : `${parts.slice(0, -1).join(', ')} and ${parts[parts.length - 1]}`;
  const a = form.details.address;
  const where = has('details.address') && a?.line1 ? ` (${[a.line1, a.city].filter(Boolean).join(', ')})` : '';
  return `We filled in your ${list} from your AfroTransact account${where}. Check them — your legal and business details may differ.`;
}

const opt = (v: string): string | undefined => (v.trim() ? v.trim() : undefined);

/** PUT /v1/seller/onboarding/business body (the whole step; blanks are left out). */
export function toBusinessInput(v: OnboardingValues): OnboardingBusinessInput {
  return {
    offering: v.offering,
    entityType: v.entityType || undefined,
    country: 'US',
    storeName: opt(v.storeName),
    contactEmail: opt(v.contactEmail),
    phone: opt(v.phone),
    website: opt(v.website)
  };
}

/** PUT /v1/seller/onboarding/business-details body; the address is sent once its required parts are filled in. */
export function toDetailsInput(v: OnboardingValues): OnboardingDetailsInput {
  const address =
    v.line1.trim() || v.city.trim() || v.state.trim() || v.postalCode.trim()
      ? { line1: v.line1.trim(), line2: opt(v.line2), city: v.city.trim(), state: v.state.trim().toUpperCase(), postalCode: v.postalCode.trim(), country: 'US' }
      : undefined;
  return { legalFirstName: opt(v.legalFirstName), legalLastName: opt(v.legalLastName), legalName: opt(v.legalName), address };
}

/** Business types that register a business name (everything but a sole proprietor). */
export const needsLegalName = (t: OnboardingEntityType | ''): boolean => t !== '' && t !== 'SOLE_PROPRIETOR';
