/* The application wizard of the mock Seller Center BFF (S286, S289): every step, completion as the seller service
   decides it (a step is complete only with its required data; Stripe verification and Payouts from what "Stripe"
   reports), the step to resume at, the local Stripe stand-in and submission. Mirrors contracts/openapi/
   gateway-bff-seller.yaml; the rules mirror the seller service's DefaultApplicationRules. */
import {
  type OnboardingComplianceInput,
  type OnboardingDetailsInput,
  type OnboardingForm,
  type OnboardingOwnerInput,
  type OnboardingPlanInput,
  type OnboardingPlans,
  type OnboardingStatus,
  type OnboardingStep,
  type OnboardingStepKey,
  type OnboardingStepSlug,
  type OnboardingStripe,
  type OrgKind
} from '../../schemas/seller';
import { type MockOrg, type MockSellerDb, plansFor } from './db';

/** Mock state of one application beyond its answers. */
export interface MockApplication {
  form: OnboardingForm;
  stripe: OnboardingStripe;
  submittedAt: string | null;
  timeline: OnboardingStatus['timeline'];
}

export type FieldErrors = Record<string, string>;

const STEPS: { key: OnboardingStepKey; slug: OnboardingStepSlug; label: string; sub: string }[] = [
  { key: 'BUSINESS', slug: 'business', label: 'Your business', sub: 'What you offer and how you’re organised' },
  { key: 'BUSINESS_DETAILS', slug: 'business-details', label: 'Business details & TIN', sub: 'Legal name, EIN and registered address' },
  { key: 'OWNERS', slug: 'owners', label: 'Owners', sub: 'Everyone with 25%+ and a control person' },
  { key: 'STRIPE_VERIFICATION', slug: 'stripe-verification', label: 'Stripe verification', sub: 'Identity and tax ID checked by Stripe' },
  { key: 'PAYOUTS', slug: 'payouts', label: 'Payouts', sub: 'The bank account Stripe pays out to' },
  { key: 'PLAN', slug: 'plan', label: 'Plan', sub: 'Choose a subscription' },
  { key: 'CATALOG_COMPLIANCE', slug: 'catalog-compliance', label: 'Catalog, permits & licences', sub: 'Categories, permits and agreements' },
  { key: 'REVIEW', slug: 'review', label: 'Review & submit', sub: 'Check everything before our team reviews it' }
];

const US_STATES = new Set(
  'AL AK AZ AR CA CO CT DE DC FL GA HI ID IL IN IA KS KY LA ME MD MA MI MN MS MO MT NE NV NH NJ NM NY NC ND OH OK OR PA RI SC SD TN TX UT VT VA WA WV WI WY PR GU VI AS MP'.split(' ')
);
const FOOD = new Set(['groceries', 'perishables', 'prepared_food']);
const BEAUTY = new Set(['beauty', 'cosmetics']);
const ACCOUNT_ADDRESS = { line1: '4410 Bellaire Blvd', line2: null, city: 'Houston', state: 'TX', postalCode: '77025', country: 'US' };
/* Stripe collects the payout bank account in its embedded onboarding (seller accounts are Stripe-collected): the
   wizard's "Payouts" only mirrors what Stripe holds. */
const NO_BANK: OnboardingForm['payouts'] = { status: 'NOT_STARTED', bankName: null, accountLast4: null, bankStatus: null, linked: false };
/** The bank the local stand-in's verification "collects", as Stripe's test bank account. */
const LOCAL_BANK: OnboardingForm['payouts'] = { status: 'READY', bankName: 'STRIPE TEST BANK (local)', accountLast4: '6789', bankStatus: 'new', linked: true };
const NO_STRIPE: OnboardingStripe = { status: 'NOT_STARTED', detailsSubmitted: false, chargesEnabled: false, payoutsEnabled: false, identityStatus: null, taxIdStatus: null, currentlyDue: [] };

const blank = (v: string | null | undefined): boolean => !v || !v.trim();
const trimOrNull = (v: string | null | undefined): string | null => (v && v.trim() ? v.trim() : null);

/** A new application's answers: what the account already knows (name, email, phone, default address). */
export function accountForm(db: MockSellerDb, kind: OrgKind): OnboardingForm {
  return {
    business: { offering: kind === 'PROVIDER' ? 'SERVICES' : null, entityType: null, country: null, storeName: null, contactEmail: db.user.email, phone: '+17132490142', description: null, brandColor: null, logoUrl: null },
    details: {
      legalFirstName: db.user.firstName, legalLastName: db.user.lastName, legalName: null, dba: null, taxIdType: null, taxIdMasked: null, dateOfBirth: null,
      stateOfFormation: null, dateOfFormation: null, address: { ...ACCOUNT_ADDRESS }, formationDocumentAssetId: null, irsDeterminationLetterAssetId: null
    },
    owners: [],
    payouts: { ...NO_BANK },
    plan: { planCode: null, planName: null, cadence: null, paymentMethodKind: null, cardBrand: null, cardLast4: null, cardExpMonth: null, cardExpYear: null, firstCharge: null,
      planFeeCollection: null, planFeeCollectionAvailable: true, planFeeCollectionDefault: 'CARD' },
    compliance: { categories: [], foodPermit: null, beautyLabellingConfirmed: null, services: [], insuranceDocumentAssetId: null, agreementsAccepted: null, w9Certified: null, w9SignerName: null },
    documents: [],
    prefilled: ['business.contactEmail', 'business.phone', 'details.address', 'details.legalFirstName', 'details.legalLastName'],
    editable: true
  };
}

/** A submitted application (the seeded INFO_REQUESTED organisation): every step answered. */
function submittedForm(db: MockSellerDb, org: MockOrg): OnboardingForm {
  const f = accountForm(db, org.kind);
  return {
    ...f,
    business: { offering: 'PRODUCTS', entityType: 'SINGLE_MEMBER_LLC', country: 'US', storeName: org.name, contactEmail: 'hello@bonwire.example', phone: '+17132490142', description: null, brandColor: null, logoUrl: null },
    details: { ...f.details, legalName: `${org.name} LLC`, taxIdType: 'EIN', taxIdMasked: '••-•••6789', stateOfFormation: 'TX', dateOfFormation: '2021-03-01', formationDocumentAssetId: 'asset-articles' },
    owners: [{ firstName: 'Kwame', lastName: 'Asante', title: 'Owner', email: 'kwame@bonwire.example', ownershipPercent: 100, significantControl: true, dateOfBirth: '1984-05-02', address: { ...ACCOUNT_ADDRESS } }],
    payouts: { status: 'READY', bankName: 'Chase', accountLast4: '4417', bankStatus: 'verified', linked: true },
    plan: { ...f.plan, planCode: 'seller_essentials', planName: 'Seller Essentials', cadence: 'MONTHLY' },
    compliance: { ...f.compliance, categories: ['groceries'], foodPermit: { permitType: 'FOOD_ESTABLISHMENT', permitNumber: 'HC-FE-1', issuingAuthority: 'Harris County', expiresOn: '2025-12-31', documentAssetId: 'asset-permit' }, agreementsAccepted: true, w9Certified: true, w9SignerName: 'Kwame Asante' },
    prefilled: []
  };
}

export function applicationOf(db: MockSellerDb, org: MockOrg): MockApplication {
  let app = db.onboarding[org.id];
  if (!app) {
    const draft = org.status === 'DRAFT';
    app = {
      form: draft ? accountForm(db, org.kind) : submittedForm(db, org),
      stripe: draft ? { ...NO_STRIPE } : { status: 'VERIFIED', detailsSubmitted: true, chargesEnabled: true, payoutsEnabled: true, identityStatus: 'verified', taxIdStatus: 'verified', currentlyDue: [] },
      submittedAt: draft ? null : '2026-10-01T15:00:00Z',
      timeline: draft ? [] : [{ action: 'SUBMITTED', status: 'PENDING_REVIEW', note: null, occurredAt: '2026-10-01T15:00:00Z' }]
    };
    db.onboarding[org.id] = app;
  }
  return app;
}

const sole = (f: OnboardingForm) => f.business.entityType === 'SOLE_PROPRIETOR';

function addressErrors(prefix: string, a: OnboardingForm['details']['address'], complete: boolean): FieldErrors {
  const e: FieldErrors = {};
  if (!a) {
    if (complete) e[prefix] = 'Required';
    return e;
  }
  if (complete && blank(a.line1)) e[`${prefix}.line1`] = 'Required';
  if (complete && blank(a.city)) e[`${prefix}.city`] = 'Required';
  if (a.state && !US_STATES.has(a.state.toUpperCase())) e[`${prefix}.state`] = 'Use the two-letter state code';
  else if (complete && blank(a.state)) e[`${prefix}.state`] = 'Required';
  if (a.postalCode && !/^\d{5}(-\d{4})?$/.test(a.postalCode)) e[`${prefix}.postalCode`] = 'Enter a 5-digit ZIP code';
  else if (complete && blank(a.postalCode)) e[`${prefix}.postalCode`] = 'Required';
  return e;
}

/** The step's missing or invalid fields, as the seller service reports them (empty = complete). */
export function stepErrors(key: OnboardingStepKey, app: MockApplication, kind: OrgKind): FieldErrors {
  const f = app.form;
  const e: FieldErrors = {};
  const req = (cond: boolean, field: string) => {
    if (cond) e[field] = 'Required';
  };
  switch (key) {
    case 'BUSINESS':
      req(!f.business.offering, 'business.offering');
      req(!f.business.entityType, 'business.entityType');
      req(blank(f.business.storeName), 'business.storeName');
      req(blank(f.business.contactEmail), 'business.contactEmail');
      req(blank(f.business.phone), 'business.phone');
      break;
    case 'BUSINESS_DETAILS': {
      const d = f.details;
      req(!d.taxIdMasked, 'businessDetails.taxId');
      if (sole(f)) {
        req(blank(d.legalFirstName), 'businessDetails.legalFirstName');
        req(blank(d.legalLastName), 'businessDetails.legalLastName');
        req(!d.dateOfBirth, 'businessDetails.dateOfBirth');
      } else {
        req(blank(d.legalName), 'businessDetails.legalName');
        req(blank(d.stateOfFormation), 'businessDetails.stateOfFormation');
        req(!d.dateOfFormation, 'businessDetails.dateOfFormation');
        if (f.business.entityType === 'NONPROFIT') req(blank(d.irsDeterminationLetterAssetId), 'businessDetails.irsDeterminationLetterAssetId');
        else req(blank(d.formationDocumentAssetId), 'businessDetails.formationDocumentAssetId');
      }
      Object.assign(e, addressErrors('businessDetails.address', d.address, true));
      break;
    }
    case 'OWNERS': {
      if (!f.owners.length) e['owners.owners'] = 'Add everyone who owns 25% or more';
      let total = 0;
      f.owners.forEach((o, i) => {
        const p = `owners[${i}].`;
        req(blank(o.firstName), `${p}firstName`);
        req(blank(o.lastName), `${p}lastName`);
        req(blank(o.title), `${p}title`);
        req(blank(o.email), `${p}email`);
        req(!o.dateOfBirth, `${p}dateOfBirth`);
        Object.assign(e, addressErrors(`${p}address`, o.address, true));
        total += o.ownershipPercent ?? 0;
      });
      if (total > 100) e['owners.ownershipTotal'] = `Ownership can’t exceed 100% (currently ${total}%)`;
      if (f.owners.length && !f.owners.some((o) => o.significantControl)) e['owners.significantControl'] = 'At least one person must have significant control';
      if ((f.business.entityType === 'MULTI_MEMBER_LLC' || f.business.entityType === 'PARTNERSHIP') && f.owners.length < 2) e['owners.owners'] = 'Multi-member LLCs and partnerships need two or more people';
      break;
    }
    case 'STRIPE_VERIFICATION':
      if (app.stripe.status === 'NOT_STARTED') e['stripe.account'] = 'Start Stripe verification';
      else if (app.stripe.status === 'REJECTED') e['stripe.account'] = 'Stripe could not verify this business';
      else if (!app.stripe.detailsSubmitted) e['stripe.detailsSubmitted'] = 'Finish Stripe verification';
      else if (app.stripe.currentlyDue.length) e['stripe.currentlyDue'] = 'Stripe needs more information';
      break;
    case 'PAYOUTS':
      if (app.stripe.status === 'NOT_STARTED') e['payouts.externalAccount'] = 'Start Stripe verification: Stripe collects your payout bank account there';
      else if (!f.payouts.accountLast4) e['payouts.externalAccount'] = 'Add your payout bank account in Stripe verification';
      else if (!f.payouts.linked) e['payouts.externalAccount'] = 'Stripe couldn’t use this bank account; change it';
      break;
    case 'PLAN': {
      req(!f.plan.planCode, 'plan.planCode');
      req(!f.plan.cadence, 'plan.cadence');
      const plan = plansFor(kind, '').find((p) => p.code === f.plan.planCode);
      if (plan && plan.monthlyMinor > 0) req(!f.plan.cardLast4, 'plan.stripePaymentMethodId');
      break;
    }
    case 'CATALOG_COMPLIANCE': {
      const c = f.compliance;
      const offering = f.business.offering;
      if (offering !== 'SERVICES' && !c.categories.length) e['compliance.categories'] = 'Choose what you’ll sell';
      if (offering !== 'PRODUCTS' && !c.services.length) e['compliance.services'] = 'Add the services you’ll offer';
      if (c.categories.some((x) => FOOD.has(x))) {
        const p = c.foodPermit;
        if (!p) e['compliance.foodPermit'] = 'Food sellers need a food establishment or cottage food permit';
        else {
          req(blank(p.permitType), 'compliance.foodPermit.permitType');
          req(blank(p.permitNumber), 'compliance.foodPermit.permitNumber');
          req(!p.expiresOn, 'compliance.foodPermit.expiresOn');
          req(blank(p.documentAssetId), 'compliance.foodPermit.documentAssetId');
        }
      }
      if (c.categories.some((x) => BEAUTY.has(x)) && c.beautyLabellingConfirmed !== true) e['compliance.beautyLabellingConfirmed'] = 'Confirm your beauty products carry full ingredient labelling';
      c.services.forEach((s, i) => {
        if (!s.regulated) return;
        req(blank(s.licenceNumber), `compliance.services[${i}].licenceNumber`);
        req(blank(s.issuingState), `compliance.services[${i}].issuingState`);
        req(!s.expiresOn, `compliance.services[${i}].expiresOn`);
        req(blank(s.documentAssetId), `compliance.services[${i}].documentAssetId`);
      });
      if (c.services.some((s) => s.regulated)) req(blank(c.insuranceDocumentAssetId), 'compliance.insuranceDocumentAssetId');
      if (c.agreementsAccepted !== true) e['compliance.agreementsAccepted'] = 'Accept the Seller & Provider Terms';
      if (c.w9Certified !== true) e['compliance.w9Certified'] = 'Certify your taxpayer details (W-9)';
      break;
    }
    case 'REVIEW':
      break;
  }
  return e;
}

/** GET /v1/seller/onboarding: steps with their state, the step to resume at, Stripe status and every answer. */
export function onboardingStatus(db: MockSellerDb, org: MockOrg): OnboardingStatus {
  const app = applicationOf(db, org);
  const f = app.form;
  const status = org.status;
  const editable = status === 'DRAFT' || status === 'INFO_REQUESTED';
  const info = status === 'INFO_REQUESTED';
  const steps: OnboardingStep[] = [];
  let resume: string | null = null;
  for (const s of STEPS) {
    if (s.key === 'REVIEW' || (s.key === 'OWNERS' && sole(f))) continue;
    const done = status === 'APPROVED' || Object.keys(stepErrors(s.key, app, org.kind)).length === 0;
    let { label, sub } = s;
    if (s.key === 'BUSINESS_DETAILS' && sole(f)) [label, sub] = ['About you', 'Your legal name, tax ID and address'];
    if (s.key === 'CATALOG_COMPLIANCE' && (f.business.offering === 'SERVICES' || (!f.business.offering && org.kind === 'PROVIDER')))
      [label, sub] = ['Services & licences', 'What you provide and your licences'];
    const state = done ? 'DONE' : info && s.key === 'CATALOG_COMPLIANCE' ? 'ACTION' : 'TODO';
    if (state !== 'DONE' && !resume) resume = s.slug;
    steps.push({ key: s.key, slug: s.slug, label, sub, state });
  }
  const review = STEPS[STEPS.length - 1]!;
  steps.push({ key: review.key, slug: review.slug, label: review.label, sub: review.sub,
    state: status === 'APPROVED' ? 'DONE' : status === 'REJECTED' ? 'BLOCKED' : info ? 'ACTION' : status === 'DRAFT' ? 'TODO' : 'WAITING' });
  return {
    orgId: org.id, kind: org.kind, status, reference: org.id === 'org_new' ? 'APP-40001' : 'APP-30131', steps,
    requestedItems: info ? ['Harris County food establishment permit (current)'] : [], canResume: editable, resumeStep: resume ?? 'review',
    submittedAt: app.submittedAt, stripe: app.stripe, timeline: app.timeline, form: { ...f, payouts: { ...f.payouts, status: payoutsStatus(app, steps) }, editable }
  };
}

/** "Payouts" as the BFF reports it from what Stripe holds. */
function payoutsStatus(app: MockApplication, steps: OnboardingStep[]): OnboardingForm['payouts']['status'] {
  const p = app.form.payouts;
  if (steps.some((s) => s.key === 'PAYOUTS' && s.state === 'DONE')) return 'READY';
  if (app.stripe.status === 'NOT_STARTED') return 'NOT_STARTED';
  if (p.bankStatus === 'errored' || p.bankStatus === 'verification_failed') return 'FAILED';
  if (!p.accountLast4) return 'NEEDS_BANK';
  return 'VERIFYING';
}

/** Keeps a prefilled marker only while the answer is unchanged. */
function keepPrefilled(before: OnboardingForm, after: OnboardingForm): string[] {
  const value = (f: OnboardingForm, key: string): string => {
    const [section, field] = key.split('.') as ['business' | 'details', string];
    return JSON.stringify((f[section] as Record<string, unknown>)[field] ?? null);
  };
  return before.prefilled.filter((k) => value(before, k) === value(after, k));
}

function update(app: MockApplication, next: OnboardingForm) {
  app.form = { ...next, prefilled: keepPrefilled(app.form, next) };
}

function addressOf(a: OnboardingDetailsInput['address']): OnboardingForm['details']['address'] {
  if (!a) return null;
  return { line1: a.line1.trim(), line2: trimOrNull(a.line2), city: a.city.trim(), state: a.state.trim().toUpperCase(), postalCode: a.postalCode.trim(), country: a.country ?? 'US' };
}

function only(errors: FieldErrors): FieldErrors | null {
  return Object.keys(errors).length ? errors : null;
}

/** Validation of what was sent (formats only, like the seller service's step saves). */
function checkAddress(prefix: string, a: OnboardingDetailsInput['address']): FieldErrors {
  return addressErrors(prefix, addressOf(a), false);
}

export function saveDetails(app: MockApplication, d: OnboardingDetailsInput): FieldErrors | null {
  const errors = checkAddress('businessDetails.address', d.address);
  let masked = app.form.details.taxIdMasked;
  let type = app.form.details.taxIdType;
  if (d.taxId) {
    const digits = d.taxId.replace(/\D/g, '');
    if (digits.length !== 9) errors['businessDetails.taxId'] = sole(app.form) ? 'Enter a valid 9-digit SSN or ITIN, e.g. 123-45-6789' : 'Enter the 9-digit EIN, e.g. 12-3456789';
    else {
      type = sole(app.form) ? (digits.startsWith('9') ? 'ITIN' : 'SSN') : 'EIN';
      masked = type === 'EIN' ? `••-•••${digits.slice(5)}` : `•••-••-${digits.slice(5)}`;
    }
  }
  if (d.dateOfBirth && new Date(d.dateOfBirth) > new Date(Date.now() - 18 * 365.25 * 86_400_000)) errors['businessDetails.dateOfBirth'] = 'You must be 18 or older';
  if (only(errors)) return errors;
  update(app, {
    ...app.form,
    details: {
      legalFirstName: trimOrNull(d.legalFirstName), legalLastName: trimOrNull(d.legalLastName), legalName: trimOrNull(d.legalName), dba: trimOrNull(d.dba),
      taxIdType: type, taxIdMasked: masked, dateOfBirth: trimOrNull(d.dateOfBirth), stateOfFormation: trimOrNull(d.stateOfFormation)?.toUpperCase() ?? null,
      dateOfFormation: trimOrNull(d.dateOfFormation), address: addressOf(d.address), formationDocumentAssetId: trimOrNull(d.formationDocumentAssetId),
      irsDeterminationLetterAssetId: trimOrNull(d.irsDeterminationLetterAssetId)
    }
  });
  return null;
}

export function saveOwners(app: MockApplication, owners: OnboardingOwnerInput[]): FieldErrors | null {
  const errors: FieldErrors = {};
  let total = 0;
  owners.forEach((o, i) => {
    Object.assign(errors, checkAddress(`owners[${i}].address`, o.address));
    if (o.email && !/^[^\s@]+@[^\s@]+\.[^\s@]{2,}$/.test(o.email)) errors[`owners[${i}].email`] = 'Enter a valid email address';
    total += o.ownershipPercent ?? 0;
  });
  if (total > 100) errors['owners.ownershipTotal'] = `Ownership can’t exceed 100% (currently ${total}%)`;
  if (only(errors)) return errors;
  update(app, {
    ...app.form,
    owners: owners.map((o) => ({
      firstName: trimOrNull(o.firstName), lastName: trimOrNull(o.lastName), title: trimOrNull(o.title), email: trimOrNull(o.email),
      ownershipPercent: o.ownershipPercent ?? null, significantControl: o.significantControl === true, dateOfBirth: trimOrNull(o.dateOfBirth), address: addressOf(o.address)
    }))
  });
  return null;
}

/** Trial campaigns the mock knows (S295): a promo code lengthens the plan's trial for the plans it names. */
export const MOCK_TRIAL_CAMPAIGNS: Record<string, { trialDays: number; plans: string[] }> = {
  GROW60: { trialDays: 60, plans: ['seller_growth', 'seller_pro'] }
};

export function savePlan(app: MockApplication, kind: OrgKind, p: OnboardingPlanInput): FieldErrors | null {
  const plan = plansFor(kind, '').find((x) => x.code === p.planCode);
  if (!plan) return { 'plan.planCode': 'Unknown plan' };
  if (p.stripePaymentMethodId && !p.stripePaymentMethodId.startsWith('pm_')) return { 'plan.stripePaymentMethodId': 'Expected a Stripe payment method id (pm_…)' };
  const promo = p.promoCode?.trim().toUpperCase();
  const campaign = promo ? MOCK_TRIAL_CAMPAIGNS[promo] : undefined;
  if (promo && (!campaign || !campaign.plans.includes(plan.code))) return { 'plan.promoCode': 'This code isn’t valid for this plan' };
  const trialDays = Math.max(plan.trialDays, campaign?.trialDays ?? 0);
  const price = plan.prices.find((x) => x.cadence === p.cadence);
  const trialEnd = new Date(Date.now() + trialDays * 86_400_000);
  const day = trialEnd.toLocaleDateString('en-US', { month: 'short', day: 'numeric', year: 'numeric', timeZone: 'UTC' });
  const amount = price?.totalMinor ?? 0;
  const dollars = `$${(amount / 100).toFixed(2)}`;
  const sentence = amount === 0 ? 'No subscription fee.' : trialDays ? `Free for ${trialDays} days. First charge of ${dollars} on ${day}.` : `First charge of ${dollars} on approval.`;
  // The same plan saved without a card keeps the card (only how the plan is paid changed, S298).
  const prev = app.form.plan;
  const keep = !p.stripePaymentMethodId && prev.planCode === plan.code && prev.cadence === p.cadence && !!prev.cardLast4;
  update(app, {
    ...app.form,
    plan: {
      planCode: plan.code, planName: plan.name, cadence: p.cadence,
      paymentMethodKind: keep ? prev.paymentMethodKind : (p.paymentMethodKind ?? null), cardBrand: keep ? prev.cardBrand : (p.cardBrand ?? null),
      cardLast4: keep ? prev.cardLast4 : (p.cardLast4 ?? null), cardExpMonth: keep ? prev.cardExpMonth : (p.cardExpMonth ?? null),
      cardExpYear: keep ? prev.cardExpYear : (p.cardExpYear ?? null), firstCharge: { chargeOn: trialEnd.toISOString().slice(0, 10), amountCents: amount, sentence },
      planFeeCollection: p.planFeeCollection ?? null, planFeeCollectionAvailable: prev.planFeeCollectionAvailable, planFeeCollectionDefault: prev.planFeeCollectionDefault
    }
  });
  return null;
}

export function saveCompliance(app: MockApplication, c: OnboardingComplianceInput): FieldErrors | null {
  const errors: FieldErrors = {};
  const today = new Date().toISOString().slice(0, 10);
  if (c.foodPermit?.expiresOn && c.foodPermit.expiresOn < today) errors['compliance.foodPermit.expiresOn'] = 'This permit has expired';
  (c.services ?? []).forEach((s, i) => {
    if (s.expiresOn && s.expiresOn < today) errors[`compliance.services[${i}].expiresOn`] = 'This licence has expired';
  });
  if (only(errors)) return errors;
  const p = c.foodPermit;
  update(app, {
    ...app.form,
    compliance: {
      categories: [...new Set((c.categories ?? []).map((x) => x.trim().toLowerCase()).filter(Boolean))],
      foodPermit: p ? { permitType: p.permitType ?? null, permitNumber: trimOrNull(p.permitNumber), issuingAuthority: trimOrNull(p.issuingAuthority), expiresOn: trimOrNull(p.expiresOn), documentAssetId: trimOrNull(p.documentAssetId) } : null,
      beautyLabellingConfirmed: c.beautyLabellingConfirmed ?? null,
      services: (c.services ?? []).map((s) => ({ serviceName: s.serviceName, regulated: s.regulated === true, licenceNumber: trimOrNull(s.licenceNumber), issuingState: trimOrNull(s.issuingState)?.toUpperCase() ?? null, expiresOn: trimOrNull(s.expiresOn), documentAssetId: trimOrNull(s.documentAssetId) })),
      insuranceDocumentAssetId: trimOrNull(c.insuranceDocumentAssetId), agreementsAccepted: c.agreementsAccepted ?? null, w9Certified: c.w9Certified ?? null, w9SignerName: trimOrNull(c.w9SignerName)
    }
  });
  return null;
}

/**
 * POST …/stripe/refresh with the local stand-in: the applicant finished the labelled local verification, which (like
 * Stripe's embedded onboarding) also collected the payout bank account.
 */
export function verifyStripe(app: MockApplication) {
  app.stripe = { status: 'VERIFIED', detailsSubmitted: true, chargesEnabled: true, payoutsEnabled: true, identityStatus: 'verified', taxIdStatus: 'verified', currentlyDue: [] };
  if (!app.form.payouts.accountLast4 || !app.form.payouts.linked) app.form = { ...app.form, payouts: { ...LOCAL_BANK } };
}

/** POST …/submit: every step's errors, or the application goes to review. */
export function submit(db: MockSellerDb, org: MockOrg): FieldErrors | null {
  const app = applicationOf(db, org);
  const errors: FieldErrors = {};
  for (const s of STEPS) {
    if (s.key === 'OWNERS' && sole(app.form)) continue;
    Object.assign(errors, stepErrors(s.key, app, org.kind));
  }
  if (only(errors)) return errors;
  const now = new Date().toISOString();
  app.submittedAt = now;
  app.timeline = [...app.timeline, { action: org.status === 'INFO_REQUESTED' ? 'RESUBMITTED' : 'SUBMITTED', status: 'PENDING_REVIEW', note: null, occurredAt: now }];
  org.status = 'PENDING_REVIEW';
  return null;
}

export function onboardingPlans(kind: OrgKind): OnboardingPlans {
  return {
    // The mock BFF has no Stripe key: Seller Center shows its labelled test card form.
    publishableKey: '',
    plans: plansFor(kind, '').map((p) => ({
      code: p.code, name: p.name, tagline: p.tagline, monthlyCents: p.monthlyMinor, trialDays: p.trialDays, features: p.features,
      prices: p.prices.map((pr) => ({
        cadence: pr.cadence, months: pr.months, totalCents: pr.totalMinor, perMonthCents: pr.perMonthMinor, savingCents: pr.savingMinor,
        perMonthLabel: pr.perMonthLabel, billedLabel: pr.billedLabel, savingLabel: pr.savingLabel || null,
        firstCharge: pr.totalMinor === 0 ? null : { chargeOn: null, amountCents: pr.totalMinor, sentence: p.trialDays ? `Free for ${p.trialDays} days, then ${pr.billedLabel.replace(/^Billed /, '')}.` : pr.billedLabel }
      }))
    }))
  };
}
