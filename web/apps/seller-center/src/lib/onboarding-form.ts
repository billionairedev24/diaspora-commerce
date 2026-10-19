import {
  type OnboardingAddress,
  type OnboardingAddressInput,
  type OnboardingBusinessInput,
  type OnboardingComplianceInput,
  type OnboardingDetailsInput,
  type OnboardingEntityType,
  type OnboardingFoodPermitType,
  type OnboardingForm,
  type OnboardingOffering,
  type OnboardingOwnersInput,
  type OrgKind,
  US_STATES
} from '@afrotransact/api-client';
import { type AddressSuggestionItem } from '@afrotransact/ui';

/* The application wizard's answers as editable text (S286, S289), their validation (inline errors and the error
   summary), the step bodies sent to the BFF and the mapping of the seller service's field errors onto the fields.
   Field keys are paths ('address.city', 'owners.1.email'); fieldId() turns them into element ids. */

export type Errors = Record<string, string>;

/** Element id of a field key, e.g. 'owners.0.email' → 'ob-owners-0-email' (the error summary links to it). */
export const fieldId = (key: string): string => `ob-${key.replace(/[.[\]]+/g, '-').replace(/-$/, '')}`;

const text = (v: string | null | undefined): string => v ?? '';
const opt = (v: string): string | undefined => (v.trim() ? v.trim() : undefined);
const digitsOf = (v: string): string => v.replace(/\D/g, '');

const EMAIL = /^[^\s@]+@[^\s@]+\.[^\s@]{2,}$/;
const ZIP = /^\d{5}(-\d{4})?$/;
const STATE_CODES = new Set(US_STATES.map(([code]) => code));

/* ── Addresses ─────────────────────────────────────────── */

export interface AddressValues {
  line1: string;
  line2: string;
  city: string;
  state: string;
  postalCode: string;
}

export const EMPTY_ADDRESS: AddressValues = {
  line1: '',
  line2: '',
  city: '',
  state: '',
  postalCode: ''
};

export function addressValues(a: OnboardingAddress | null | undefined): AddressValues {
  return {
    line1: text(a?.line1),
    line2: text(a?.line2),
    city: text(a?.city),
    state: text(a?.state).toUpperCase(),
    postalCode: text(a?.postalCode)
  };
}

/** Picking a suggestion fills street, city, state and ZIP (the apartment line is kept). */
export function fromSuggestion(current: AddressValues, s: AddressSuggestionItem): AddressValues {
  const a = s.address;
  return {
    line1: a.line1 || s.label,
    line2: a.line2 || current.line2,
    city: a.city,
    state: a.state.toUpperCase().slice(0, 2),
    postalCode: a.zip.slice(0, 10)
  };
}

const blankAddress = (a: AddressValues) => !a.line1.trim() && !a.city.trim() && !a.state.trim() && !a.postalCode.trim();

/** A US address: street, city, a state code and a 5-digit ZIP (ZIP+4 accepted). */
export function validateAddress(a: AddressValues, prefix: string, required = true): Errors {
  const e: Errors = {};
  if (!required && blankAddress(a)) return e;
  if (!a.line1.trim()) e[`${prefix}.line1`] = 'Enter the street address';
  if (!a.city.trim()) e[`${prefix}.city`] = 'Enter the city';
  if (!a.state.trim()) e[`${prefix}.state`] = 'Choose the state';
  else if (!STATE_CODES.has(a.state.trim().toUpperCase())) e[`${prefix}.state`] = 'Choose a US state';
  if (!a.postalCode.trim()) e[`${prefix}.postalCode`] = 'Enter the ZIP code';
  else if (!ZIP.test(a.postalCode.trim())) e[`${prefix}.postalCode`] = 'Enter a 5-digit ZIP code';
  return e;
}

export function toAddressInput(a: AddressValues): OnboardingAddressInput | undefined {
  if (blankAddress(a)) return undefined;
  return {
    line1: a.line1.trim(),
    line2: opt(a.line2),
    city: a.city.trim(),
    state: a.state.trim().toUpperCase(),
    postalCode: a.postalCode.trim(),
    country: 'US'
  };
}

/* ── Step 1: Your business ──────────────────────────────── */

export interface BusinessValues {
  offering: OnboardingOffering | '';
  entityType: OnboardingEntityType | '';
  storeName: string;
  contactEmail: string;
  /** National number as displayed, e.g. "(713) 249-0142" (US). */
  phone: string;
  /** What the business sells or does: shown on its public page and given to Stripe. */
  description: string;
  /** #rrggbb, or empty for the default. */
  brandColor: string;
  /** https URL of the uploaded logo, or empty. */
  logoUrl: string;
}

/** National display of a US number (+17132490142 → (713) 249-0142); other numbers are shown as stored. */
export function displayPhone(e164: string | null | undefined): string {
  const m = /^\+1(\d{3})(\d{3})(\d{4})$/.exec(e164 ?? '');
  return m ? `(${m[1]}) ${m[2]}-${m[3]}` : text(e164);
}

export function businessValues(form: OnboardingForm, kind: OrgKind): BusinessValues {
  const b = form.business;
  return {
    offering: b.offering ?? (kind === 'PROVIDER' ? 'SERVICES' : 'PRODUCTS'),
    entityType: b.entityType ?? '',
    storeName: text(b.storeName),
    contactEmail: text(b.contactEmail),
    phone: displayPhone(b.phone),
    description: text(b.description),
    brandColor: text(b.brandColor),
    logoUrl: text(b.logoUrl)
  };
}

export function validateBusiness(v: BusinessValues, kind: OrgKind): Errors {
  const e: Errors = {};
  if (!v.offering) e.offering = 'Choose what you’ll offer';
  else if (kind === 'SELLER' && v.offering === 'SERVICES') e.offering = 'Seller accounts sell products (choose products or both)';
  else if (kind === 'PROVIDER' && v.offering === 'PRODUCTS') e.offering = 'Provider accounts offer services (choose services or both)';
  if (!v.entityType) e.entityType = 'Choose your business type';
  const name = v.storeName.trim();
  if (!name) e.storeName = kind === 'PROVIDER' ? 'Enter your business name' : 'Enter your business name';
  else if (name.length < 2 || name.length > 80) e.storeName = '2 to 80 characters';
  if (!EMAIL.test(v.contactEmail.trim())) e.contactEmail = 'Enter a valid email address';
  if (digitsOf(v.phone).length !== 10) e.phone = 'Enter a 10-digit US mobile number';
  if (v.description.length > 500) e.description = 'At most 500 characters';
  if (v.brandColor.trim() && !/^#[0-9a-fA-F]{6}$/.test(v.brandColor.trim())) e.brandColor = 'Use a colour like #rrggbb';
  return e;
}

export function toBusinessInput(v: BusinessValues): OnboardingBusinessInput {
  return {
    offering: v.offering || undefined,
    entityType: v.entityType || undefined,
    country: 'US',
    storeName: opt(v.storeName),
    contactEmail: opt(v.contactEmail),
    phone: opt(v.phone),
    description: opt(v.description),
    brandColor: opt(v.brandColor),
    logoUrl: opt(v.logoUrl)
  };
}

/* ── Step 2: About you / Business details & TIN ─────────── */

export interface DetailsValues {
  legalFirstName: string;
  legalLastName: string;
  legalName: string;
  dba: string;
  /** What the applicant types (digits only, formatted as they type); empty keeps the stored TIN. */
  taxId: string;
  dateOfBirth: string;
  stateOfFormation: string;
  dateOfFormation: string;
  address: AddressValues;
  formationDocumentAssetId: string;
  irsDeterminationLetterAssetId: string;
}

export const isSole = (t: OnboardingEntityType | '' | null | undefined): boolean => t === 'SOLE_PROPRIETOR';

export function detailsValues(form: OnboardingForm): DetailsValues {
  const d = form.details;
  return {
    legalFirstName: text(d.legalFirstName),
    legalLastName: text(d.legalLastName),
    legalName: text(d.legalName),
    dba: text(d.dba),
    taxId: '',
    dateOfBirth: text(d.dateOfBirth),
    stateOfFormation: text(d.stateOfFormation),
    dateOfFormation: text(d.dateOfFormation),
    address: addressValues(d.address),
    formationDocumentAssetId: text(d.formationDocumentAssetId),
    irsDeterminationLetterAssetId: text(d.irsDeterminationLetterAssetId)
  };
}

/** Formats a TIN as it is typed: SSN/ITIN 123-45-6789, EIN 12-3456789 (9 digits at most). */
export function formatTaxId(raw: string, kind: 'SSN' | 'EIN'): string {
  const d = digitsOf(raw).slice(0, 9);
  if (kind === 'EIN') return d.length > 2 ? `${d.slice(0, 2)}-${d.slice(2)}` : d;
  if (d.length > 5) return `${d.slice(0, 3)}-${d.slice(3, 5)}-${d.slice(5)}`;
  if (d.length > 3) return `${d.slice(0, 3)}-${d.slice(3)}`;
  return d;
}

/** Last-4 display of a TIN being typed (never the whole number): •••-••-6789 or ••-•••6789. */
export function maskTaxId(raw: string, kind: 'SSN' | 'EIN'): string {
  const last4 = digitsOf(raw).slice(-4);
  if (!last4) return '';
  return kind === 'EIN' ? `••-•••${last4}` : `•••-••-${last4}`;
}

/** SSN/ITIN: 9 digits, no 000 / 666 / 9xx area (ITIN starts with 9 and is allowed), no 00 group, no 0000 serial. */
export function validTaxId(raw: string, kind: 'SSN' | 'EIN'): boolean {
  const n = digitsOf(raw);
  if (n.length !== 9) return false;
  if (kind === 'EIN') return !['00', '07', '08', '09', '17', '18', '19', '28', '29', '49', '69', '70', '78', '79', '89', '96', '97'].includes(n.slice(0, 2));
  if (n.startsWith('9')) return true;
  return !/^(000|666)/.test(n) && n.slice(3, 5) !== '00' && n.slice(5) !== '0000';
}

const ageOn = (dob: string, today: Date): number => {
  const d = new Date(`${dob}T12:00:00Z`);
  let age = today.getUTCFullYear() - d.getUTCFullYear();
  const m = today.getUTCMonth() - d.getUTCMonth();
  if (m < 0 || (m === 0 && today.getUTCDate() < d.getUTCDate())) age--;
  return age;
};

/** "About you" (sole proprietor) or "Business details & TIN" (everyone else), as designed. */
export function validateDetails(v: DetailsValues, entityType: OnboardingEntityType | '', hasStoredTaxId: boolean, today = new Date()): Errors {
  const e: Errors = {};
  const kind = isSole(entityType) ? 'SSN' : 'EIN';
  if (isSole(entityType)) {
    if (!v.legalFirstName.trim()) e.legalFirstName = 'Enter your legal first name';
    if (!v.legalLastName.trim()) e.legalLastName = 'Enter your legal last name';
    if (!v.dateOfBirth) e.dateOfBirth = 'Enter your date of birth';
    else if (ageOn(v.dateOfBirth, today) < 18) e.dateOfBirth = 'You must be 18 or older';
    else if (ageOn(v.dateOfBirth, today) > 120) e.dateOfBirth = 'Enter a real date of birth';
  } else {
    if (!v.legalName.trim()) e.legalName = 'Enter the legal business name';
    if (!v.stateOfFormation) e.stateOfFormation = 'Choose the state';
    if (!v.dateOfFormation) e.dateOfFormation = 'Enter the formation date';
    else if (new Date(`${v.dateOfFormation}T00:00:00Z`) > today) e.dateOfFormation = 'Can’t be in the future';
    if (entityType === 'NONPROFIT' && !v.irsDeterminationLetterAssetId) e.irsDeterminationLetterAssetId = 'Upload your determination letter';
    if (entityType !== 'NONPROFIT' && !v.formationDocumentAssetId) e.formationDocumentAssetId = 'Upload your formation document';
  }
  if (v.taxId) {
    if (!validTaxId(v.taxId, kind)) e.taxId = kind === 'EIN' ? 'Enter a valid EIN (12-3456789)' : 'Enter a valid 9-digit SSN or ITIN';
  } else if (!hasStoredTaxId) e.taxId = kind === 'EIN' ? 'Enter your EIN' : 'Enter your SSN or ITIN';
  Object.assign(e, validateAddress(v.address, 'address'));
  return e;
}

/** The whole step (the seller service replaces the section); the TIN only when one was typed. */
export function toDetailsInput(v: DetailsValues, entityType: OnboardingEntityType | ''): OnboardingDetailsInput {
  const sole = isSole(entityType);
  return {
    // The applicant's legal name is kept for every business type (it prefills the first owner).
    legalFirstName: opt(v.legalFirstName),
    legalLastName: opt(v.legalLastName),
    legalName: sole ? undefined : opt(v.legalName),
    dba: opt(v.dba),
    taxId: v.taxId ? formatTaxId(v.taxId, sole ? 'SSN' : 'EIN') : undefined,
    dateOfBirth: sole ? opt(v.dateOfBirth) : undefined,
    stateOfFormation: sole ? undefined : opt(v.stateOfFormation),
    dateOfFormation: sole ? undefined : opt(v.dateOfFormation),
    address: toAddressInput(v.address),
    formationDocumentAssetId: sole ? undefined : opt(v.formationDocumentAssetId),
    irsDeterminationLetterAssetId: sole ? undefined : opt(v.irsDeterminationLetterAssetId)
  };
}

/* ── Step 3: Owners ─────────────────────────────────────── */

export interface OwnerValues {
  firstName: string;
  lastName: string;
  title: string;
  email: string;
  ownershipPercent: string;
  significantControl: boolean;
  dateOfBirth: string;
  /** "Same as the business address": the address fields are filled from step 2. */
  sameAsBusiness: boolean;
  address: AddressValues;
}

export const emptyOwner = (): OwnerValues => ({
  firstName: '',
  lastName: '',
  title: '',
  email: '',
  ownershipPercent: '',
  significantControl: false,
  dateOfBirth: '',
  sameAsBusiness: false,
  address: { ...EMPTY_ADDRESS }
});

const sameAddress = (a: AddressValues, b: AddressValues) =>
  a.line1.trim() === b.line1.trim() &&
  a.city.trim() === b.city.trim() &&
  a.state === b.state &&
  a.postalCode.trim() === b.postalCode.trim() &&
  a.line1.trim() !== '';

/** Saved owners, or (none yet) the applicant as the first person: name and email from the account. */
export function ownerValues(form: OnboardingForm): OwnerValues[] {
  const business = addressValues(form.details.address);
  if (form.owners.length) {
    return form.owners.map((o) => {
      const address = addressValues(o.address);
      return {
        firstName: text(o.firstName),
        lastName: text(o.lastName),
        title: text(o.title),
        email: text(o.email),
        ownershipPercent: o.ownershipPercent === null ? '' : String(o.ownershipPercent),
        significantControl: o.significantControl,
        dateOfBirth: text(o.dateOfBirth),
        sameAsBusiness: sameAddress(address, business),
        address
      };
    });
  }
  return [
    {
      ...emptyOwner(),
      firstName: text(form.details.legalFirstName),
      lastName: text(form.details.legalLastName),
      email: text(form.business.contactEmail),
      significantControl: true
    }
  ];
}

/** Sum of the ownership listed (blank and invalid entries count as 0). */
export function ownershipTotal(owners: OwnerValues[]): number {
  return owners.reduce((sum, o) => {
    const n = Number(o.ownershipPercent);
    return sum + (Number.isFinite(n) ? n : 0);
  }, 0);
}

export function validateOwners(owners: OwnerValues[], entityType: OnboardingEntityType | '', today = new Date()): Errors {
  const e: Errors = {};
  owners.forEach((o, i) => {
    const p = `owners.${i}`;
    if (!o.firstName.trim()) e[`${p}.firstName`] = 'Enter the first name';
    if (!o.lastName.trim()) e[`${p}.lastName`] = 'Enter the last name';
    if (!o.title.trim()) e[`${p}.title`] = 'Enter the title (e.g. CEO, managing member)';
    if (!EMAIL.test(o.email.trim())) e[`${p}.email`] = 'Enter a valid email address';
    const pct = Number(o.ownershipPercent);
    if (o.ownershipPercent.trim() === '' || !Number.isFinite(pct) || pct < 0 || pct > 100) e[`${p}.ownershipPercent`] = '0 to 100';
    else if (pct < 25 && !o.significantControl) e[`${p}.ownershipPercent`] = 'List people under 25% only if they have significant control';
    if (!o.dateOfBirth) e[`${p}.dateOfBirth`] = 'Enter the date of birth';
    else if (ageOn(o.dateOfBirth, today) < 18) e[`${p}.dateOfBirth`] = 'Owners must be 18 or older';
    Object.assign(e, validateAddress(o.address, `${p}.address`));
  });
  const total = ownershipTotal(owners);
  if (!owners.length) e.owners = 'Add everyone who owns 25% or more';
  else if (total > 100) e.owners = `Ownership adds up to more than 100% (${total}%)`;
  else if (!owners.some((o) => o.significantControl)) e.owners = 'Mark at least one person with significant control';
  else if ((entityType === 'MULTI_MEMBER_LLC' || entityType === 'PARTNERSHIP') && owners.length < 2)
    e.owners = `A ${entityType === 'PARTNERSHIP' ? 'partnership' : 'multi-member LLC'} needs at least two people`;
  return e;
}

export function toOwnersInput(owners: OwnerValues[]): OnboardingOwnersInput {
  return {
    owners: owners.map((o) => ({
      firstName: opt(o.firstName),
      lastName: opt(o.lastName),
      title: opt(o.title),
      email: opt(o.email),
      ownershipPercent: o.ownershipPercent.trim() === '' ? undefined : Number(o.ownershipPercent),
      significantControl: o.significantControl,
      dateOfBirth: opt(o.dateOfBirth),
      address: toAddressInput(o.address)
    }))
  };
}

/* ── Step 5: Payouts ─────────────────────────────────────
   Nothing to type: seller accounts are Stripe-collected, so Stripe's embedded onboarding collects the payout bank
   account and its account management changes it. The step shows what Stripe holds (form.payouts, read-only). */

/** The payout account as one line: "Chase ••6789". */
export function payoutAccountLabel(p: OnboardingForm['payouts']): string {
  return p.accountLast4 ? `${p.bankName ?? 'Bank account'} ••${p.accountLast4}` : 'Not added yet';
}

/* ── Step 7: Catalog, permits & licences / Services & licences ── */

/** Product categories as designed (BO.PRODUCT_CATS); keys are what the seller service checks (food, beauty). */
export const PRODUCT_CATEGORIES: { key: string; label: string }[] = [
  { key: 'groceries', label: 'Groceries' },
  { key: 'prepared_food', label: 'Prepared food' },
  { key: 'fashion', label: 'Fashion' },
  { key: 'beauty', label: 'Beauty' },
  { key: 'home_art', label: 'Home & Art' },
  { key: 'books', label: 'Books' },
  { key: 'health', label: 'Health' },
  { key: 'party', label: 'Party' }
];
export const FOOD_CATEGORIES = new Set(['groceries', 'prepared_food', 'perishables']);
export const BEAUTY_CATEGORIES = new Set(['beauty', 'cosmetics']);

export const FOOD_PERMITS: {
  value: OnboardingFoodPermitType;
  label: string;
}[] = [
  { value: 'FOOD_ESTABLISHMENT', label: 'County food establishment permit' },
  { value: 'COTTAGE_FOOD', label: 'State cottage-food permit' },
  { value: 'FDA_FACILITY', label: 'FDA food facility registration' },
  { value: 'FOOD_HANDLER', label: 'Food handler certificate' }
];

/** Services and the licence each needs (BO.SERVICE_LICENCES). */
export const SERVICES: { name: string; licence: string }[] = [
  {
    name: 'Hair & beauty',
    licence: 'State cosmetology / natural-hair-care licence'
  },
  { name: 'Catering', licence: 'Food handler permit + county caterer permit' },
  {
    name: 'Shipping to Africa',
    licence: 'FMC Ocean Transportation Intermediary licence'
  },
  {
    name: 'Immigration, tax & legal',
    licence: 'Bar number (attorney) or IRS PTIN / EA'
  },
  {
    name: 'Translation',
    licence: 'ATA certification (for certified translations)'
  },
  {
    name: 'Home services',
    licence: 'State contractor licence where required + insurance'
  },
  {
    name: 'Events & DJ',
    licence: 'Business registration + general liability insurance'
  },
  { name: 'Tailoring', licence: 'Business registration' }
];

export interface ServiceValues {
  serviceName: string;
  licenceNumber: string;
  issuingState: string;
  expiresOn: string;
  documentAssetId: string;
}

export interface ComplianceValues {
  categories: string[];
  permitType: OnboardingFoodPermitType | '';
  permitNumber: string;
  permitExpiresOn: string;
  permitDocumentAssetId: string;
  beautyLabellingConfirmed: boolean;
  services: ServiceValues[];
  insuranceDocumentAssetId: string;
  /** Seller & Provider Agreement and fee schedule. */
  agreeTerms: boolean;
  /** Prohibited Items and Services Policy. */
  agreePolicy: boolean;
  w9Certified: boolean;
}

export function complianceValues(form: OnboardingForm): ComplianceValues {
  const c = form.compliance;
  const p = c.foodPermit;
  const accepted = c.agreementsAccepted === true;
  const type = p?.permitType;
  return {
    categories: [...c.categories],
    permitType: FOOD_PERMITS.some((x) => x.value === type) ? (type as OnboardingFoodPermitType) : '',
    permitNumber: text(p?.permitNumber),
    permitExpiresOn: text(p?.expiresOn),
    permitDocumentAssetId: text(p?.documentAssetId),
    beautyLabellingConfirmed: c.beautyLabellingConfirmed === true,
    services: c.services.map((s) => ({
      serviceName: s.serviceName,
      licenceNumber: text(s.licenceNumber),
      issuingState: text(s.issuingState),
      expiresOn: text(s.expiresOn),
      documentAssetId: text(s.documentAssetId)
    })),
    insuranceDocumentAssetId: text(c.insuranceDocumentAssetId),
    agreeTerms: accepted,
    agreePolicy: accepted,
    w9Certified: c.w9Certified === true
  };
}

export const sellsProducts = (o: OnboardingOffering | '' | null): boolean => o !== 'SERVICES';
export const offersServices = (o: OnboardingOffering | '' | null): boolean => o === 'SERVICES' || o === 'BOTH';

export function validateCompliance(v: ComplianceValues, offering: OnboardingOffering | '' | null, today = new Date()): Errors {
  const e: Errors = {};
  const iso = today.toISOString().slice(0, 10);
  if (sellsProducts(offering)) {
    if (!v.categories.length) e.categories = 'Pick at least one category';
    if (v.categories.some((c) => FOOD_CATEGORIES.has(c))) {
      if (!v.permitType) e.permitType = 'Choose the permit type';
      if (!v.permitNumber.trim()) e.permitNumber = 'Enter the permit number';
      if (!v.permitExpiresOn) e.permitExpiresOn = 'Enter the expiry date';
      else if (v.permitExpiresOn < iso) e.permitExpiresOn = 'This permit has expired';
      if (!v.permitDocumentAssetId) e.permitDocumentAssetId = 'Upload your permit';
    }
    if (v.categories.some((c) => BEAUTY_CATEGORIES.has(c)) && !v.beautyLabellingConfirmed) e.beautyLabellingConfirmed = 'Required for beauty sellers';
  }
  if (offersServices(offering)) {
    if (!v.services.length) e.services = 'Pick at least one service';
    v.services.forEach((s, i) => {
      const p = `services.${i}`;
      if (!s.licenceNumber.trim()) e[`${p}.licenceNumber`] = 'Enter the licence or registration number';
      if (!s.issuingState) e[`${p}.issuingState`] = 'Choose the state';
      if (!s.expiresOn) e[`${p}.expiresOn`] = 'Enter the expiry date';
      else if (s.expiresOn < iso) e[`${p}.expiresOn`] = 'Expired licence';
      if (!s.documentAssetId) e[`${p}.documentAssetId`] = 'Upload the licence';
    });
    if (v.services.length && !v.insuranceDocumentAssetId) e.insuranceDocumentAssetId = 'Upload your insurance certificate';
  }
  if (!v.agreeTerms) e.agreeTerms = 'Required';
  if (!v.agreePolicy) e.agreePolicy = 'Required';
  if (!v.w9Certified) e.w9Certified = 'Required';
  return e;
}

export function toComplianceInput(v: ComplianceValues, offering: OnboardingOffering | '' | null, signer: string): OnboardingComplianceInput {
  const products = sellsProducts(offering);
  const food = products && v.categories.some((c) => FOOD_CATEGORIES.has(c));
  return {
    categories: products ? v.categories : [],
    foodPermit: food
      ? {
          permitType: v.permitType || undefined,
          permitNumber: opt(v.permitNumber),
          expiresOn: opt(v.permitExpiresOn),
          documentAssetId: opt(v.permitDocumentAssetId)
        }
      : undefined,
    beautyLabellingConfirmed: products && v.categories.some((c) => BEAUTY_CATEGORIES.has(c)) ? v.beautyLabellingConfirmed : undefined,
    services: offersServices(offering)
      ? v.services.map((s) => ({
          serviceName: s.serviceName,
          regulated: true,
          licenceNumber: opt(s.licenceNumber),
          issuingState: opt(s.issuingState),
          expiresOn: opt(s.expiresOn),
          documentAssetId: opt(s.documentAssetId)
        }))
      : [],
    insuranceDocumentAssetId: offersServices(offering) ? opt(v.insuranceDocumentAssetId) : undefined,
    agreementsAccepted: v.agreeTerms && v.agreePolicy,
    w9Certified: v.w9Certified,
    w9SignerName: opt(signer)
  };
}

/* ── Server field errors ───────────────────────────────── */

/** Seller service field names that differ from the wizard's. */
const RENAMES: Record<string, string> = {
  'foodPermit.permitType': 'permitType',
  'foodPermit.permitNumber': 'permitNumber',
  'foodPermit.expiresOn': 'permitExpiresOn',
  'foodPermit.documentAssetId': 'permitDocumentAssetId',
  foodPermit: 'permitType',
  agreementsAccepted: 'agreeTerms',
  ownershipTotal: 'owners',
  significantControl: 'owners',
  address: 'address.line1'
};

/**
 * The seller service's field errors ('business.storeName', 'businessDetails.address.postalCode', 'owners[1].email',
 * 'compliance.foodPermit.permitNumber') on the wizard's field keys ('storeName', 'address.postalCode', 'owners.1.email',
 * 'permitNumber'). Errors of other steps are dropped; unknown ones are kept under their own name.
 */
export function mapServerErrors(fieldErrors: Record<string, string>): Errors {
  const out: Errors = {};
  for (const [raw, message] of Object.entries(fieldErrors)) {
    let k = raw.replace(/\[(\d+)\]/g, '.$1');
    k = k.replace(/^(business|businessDetails|payouts|plan|compliance|stripe)\./, '');
    if (k.startsWith('owners.') && !/^owners\.\d/.test(k)) k = k.slice('owners.'.length);
    out[RENAMES[k] ?? k] = message;
  }
  return out;
}

/* ── Prefill ───────────────────────────────────────────── */

/** Wizard field → the `form.prefilled` name it shows the "From your account" tag for. */
const PREFILL_OF: Record<string, string> = {
  storeName: 'business.storeName',
  contactEmail: 'business.contactEmail',
  phone: 'business.phone',
  legalFirstName: 'details.legalFirstName',
  legalLastName: 'details.legalLastName',
  address: 'details.address'
};

/** Whether the field still holds the value taken from the account. */
export const isPrefilled = (form: OnboardingForm, field: string): boolean => {
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

export const ENTITY_TYPES: {
  value: OnboardingEntityType;
  label: string;
  desc: string;
}[] = [
  {
    value: 'SOLE_PROPRIETOR',
    label: 'Sole proprietor',
    desc: 'You sell under your own name or a DBA. We collect your SSN or ITIN.'
  },
  {
    value: 'SINGLE_MEMBER_LLC',
    label: 'Single-member LLC',
    desc: 'One owner. Uses an EIN (or the owner’s SSN if disregarded).'
  },
  {
    value: 'MULTI_MEMBER_LLC',
    label: 'Multi-member LLC',
    desc: 'Two or more members. EIN plus every owner with 25%+.'
  },
  {
    value: 'CORPORATION',
    label: 'Corporation (C or S)',
    desc: 'EIN, state of incorporation, officers and 25%+ owners.'
  },
  {
    value: 'PARTNERSHIP',
    label: 'Partnership',
    desc: 'EIN and every general partner.'
  },
  {
    value: 'NONPROFIT',
    label: 'Nonprofit',
    desc: 'EIN and IRS determination letter (501(c)(3)).'
  }
];

export const OFFERINGS: {
  value: OnboardingOffering;
  label: string;
  desc: string;
}[] = [
  {
    value: 'PRODUCTS',
    label: 'Products',
    desc: 'Groceries, fashion, beauty, home and more, shipped or picked up.'
  },
  {
    value: 'SERVICES',
    label: 'Services',
    desc: 'Bookable services: hair, catering, shipping, tax and legal help.'
  },
  {
    value: 'BOTH',
    label: 'Products and services',
    desc: 'A shop and bookable services under one business.'
  }
];
