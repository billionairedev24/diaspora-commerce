import { z } from 'zod';

/** Documents of the Legal & privacy center (content-service keys; also the storefront slugs under /legal/). */
export const LEGAL_DOCUMENT_KEYS = [
  'conditions-of-use',
  'privacy',
  'cookies',
  'privacy-choices',
  'returns',
  'seller-terms',
  'prohibited-items',
  'accessibility',
  'sms-terms',
  'gift-card-terms',
  'store-credit-terms',
  'membership-terms',
  'referral-terms',
  'preorder-delivery-terms',
  'community-guidelines',
  'ip-policy',
  'provider-terms',
  'payments-terms',
  'subprocessors',
  'data-processing',
  'seller-fees'
] as const;
/** How the legal center index groups the documents (every document is in exactly one group, in reading order). */
export const LEGAL_GROUPS = [
  { id: 'customers', documents: ['conditions-of-use', 'returns', 'preorder-delivery-terms', 'membership-terms', 'referral-terms'] },
  { id: 'payments', documents: ['gift-card-terms', 'store-credit-terms', 'payments-terms', 'seller-fees'] },
  { id: 'sellers', documents: ['seller-terms', 'provider-terms', 'data-processing'] },
  { id: 'privacy', documents: ['privacy', 'cookies', 'privacy-choices', 'sms-terms', 'subprocessors'] },
  { id: 'policies', documents: ['community-guidelines', 'ip-policy', 'prohibited-items', 'accessibility'] }
] as const satisfies ReadonlyArray<{ id: string; documents: readonly (typeof LEGAL_DOCUMENT_KEYS)[number][] }>;
export type LegalGroupId = (typeof LEGAL_GROUPS)[number]['id'];

/** English section titles of the legal center (the storefront translates them; the staff console shows these). */
export const LEGAL_GROUP_TITLES: Readonly<Record<LegalGroupId, string>> = {
  customers: 'Customers',
  payments: 'Payments and money',
  sellers: 'Sellers and providers',
  privacy: 'Privacy and cookies',
  policies: 'Policies'
};

/** The legal center section of a document key (empty for an unknown key). */
export function legalGroupTitle(key: string): string {
  const group = LEGAL_GROUPS.find((g) => (g.documents as readonly string[]).includes(key));
  return group ? LEGAL_GROUP_TITLES[group.id] : '';
}

/** Documents a seller accepts (with the version shown) in onboarding; a provider accepts PROVIDER_AGREEMENTS instead. */
export const SELLER_AGREEMENTS = ['seller-terms', 'payments-terms', 'data-processing'] as const;
export const PROVIDER_AGREEMENTS = ['provider-terms', 'payments-terms', 'data-processing'] as const;

/** English titles (content-service LegalDocumentType): for apps without a catalogue, such as Seller Center and the staff console. */
export const LEGAL_DOCUMENT_TITLES = {
  'conditions-of-use': 'Conditions of Use',
  privacy: 'Privacy Notice',
  cookies: 'Cookie Notice',
  'privacy-choices': 'Your Privacy Choices',
  returns: 'Returns & Refunds',
  'seller-terms': 'Seller & Provider Terms',
  'prohibited-items': 'Prohibited & Restricted Items',
  accessibility: 'Accessibility Statement',
  'sms-terms': 'SMS & Notification Terms',
  'gift-card-terms': 'Gift Card Terms',
  'store-credit-terms': 'Store Credit Terms',
  'membership-terms': 'Membership Terms',
  'referral-terms': 'Referral Program Terms',
  'preorder-delivery-terms': 'Pre-order & Delivery Terms',
  'community-guidelines': 'Community Guidelines',
  'ip-policy': 'Intellectual Property Policy',
  'provider-terms': 'Service Provider Terms',
  'payments-terms': 'Payments Terms',
  subprocessors: 'Subprocessors',
  'data-processing': 'Data Processing Addendum',
  'seller-fees': 'Seller Fees & Charges'
} as const satisfies Record<(typeof LEGAL_DOCUMENT_KEYS)[number], string>;

export const LegalDocumentKeySchema = z.enum(LEGAL_DOCUMENT_KEYS);
export type LegalDocumentKey = z.infer<typeof LegalDocumentKeySchema>;

/** Short or legacy slugs that older links, apps and emails used; they redirect to the canonical key. */
export const LEGAL_DOCUMENT_ALIASES: Readonly<Record<string, LegalDocumentKey>> = {
  terms: 'conditions-of-use',
  'terms-of-use': 'conditions-of-use',
  conditions: 'conditions-of-use',
  choices: 'privacy-choices',
  'do-not-sell': 'privacy-choices',
  sellers: 'seller-terms',
  'seller-agreement': 'seller-terms',
  prohibited: 'prohibited-items',
  sms: 'sms-terms',
  'cookie-policy': 'cookies',
  'cookie-notice': 'cookies',
  'privacy-notice': 'privacy',
  'privacy-policy': 'privacy',
  'returns-policy': 'returns',
  refunds: 'returns',
  'accessibility-statement': 'accessibility',
  'sms-notification-terms': 'sms-terms',
  'gift-card': 'gift-card-terms',
  'gift-cards': 'gift-card-terms',
  'giftcard-terms': 'gift-card-terms',
  'store-credit': 'store-credit-terms',
  membership: 'membership-terms',
  'member-terms': 'membership-terms',
  referral: 'referral-terms',
  referrals: 'referral-terms',
  preorder: 'preorder-delivery-terms',
  'pre-order': 'preorder-delivery-terms',
  'preorder-terms': 'preorder-delivery-terms',
  'delivery-terms': 'preorder-delivery-terms',
  guidelines: 'community-guidelines',
  community: 'community-guidelines',
  ip: 'ip-policy',
  copyright: 'ip-policy',
  dmca: 'ip-policy',
  provider: 'provider-terms',
  providers: 'provider-terms',
  'provider-agreement': 'provider-terms',
  payments: 'payments-terms',
  'payment-terms': 'payments-terms',
  'sub-processors': 'subprocessors',
  dpa: 'data-processing',
  'data-processing-addendum': 'data-processing',
  fees: 'seller-fees',
  'fee-schedule': 'seller-fees',
  'seller-fee-schedule': 'seller-fees'
};

export const isLegalDocumentKey = (v: string): v is LegalDocumentKey => (LEGAL_DOCUMENT_KEYS as readonly string[]).includes(v);

/** Canonical key for a slug (key or alias), or null when unknown. */
export function legalDocumentKeyOf(slug: string): LegalDocumentKey | null {
  const s = slug.trim().toLowerCase();
  return isLegalDocumentKey(s) ? s : (LEGAL_DOCUMENT_ALIASES[s] ?? null);
}

/** Storefront path of a document (relative; prefix PUBLIC_WEB_URL for other apps). */
export const legalHref = (key: LegalDocumentKey): string => `/legal/${key}`;

export const LegalSummarySchema = z.object({
  key: LegalDocumentKeySchema,
  title: z.string(),
  version: z.string(),
  effectiveDate: z.string(),
  href: z.string(),
  draftBanner: z.boolean()
});
export type LegalSummary = z.infer<typeof LegalSummarySchema>;

/** The legal center index: the current version of every published document. */
export const LegalIndexSchema = z.object({ documents: z.array(LegalSummarySchema) });
export type LegalIndex = z.infer<typeof LegalIndexSchema>;

export const LegalVersionRefSchema = z.object({
  version: z.string(),
  effectiveDate: z.string(),
  status: z.enum(['CURRENT', 'ARCHIVED']),
  changeSummary: z.string().nullish()
});
export type LegalVersionRef = z.infer<typeof LegalVersionRefSchema>;

export const LegalPageSchema = z.object({
  key: z.string(),
  title: z.string(),
  version: z.string(),
  effectiveDate: z.string(),
  status: z.enum(['CURRENT', 'ARCHIVED']),
  draftBanner: z.boolean(),
  locale: z.string(),
  translationFallback: z.boolean(),
  /** Markdown: `# Title`, `## Section` headings and paragraphs. */
  body: z.string(),
  outline: z.array(z.object({ id: z.string(), title: z.string() })),
  versions: z.array(LegalVersionRefSchema),
  href: z.string()
});
export type LegalPage = z.infer<typeof LegalPageSchema>;

export const CookieCategorySchema = z.object({
  key: z.string(),
  name: z.string(),
  description: z.string(),
  required: z.boolean(),
  gpcOptOut: z.boolean()
});
export type CookieCategory = z.infer<typeof CookieCategorySchema>;

export const CookieDefinitionSchema = z.object({
  name: z.string(),
  provider: z.string(),
  purpose: z.string(),
  duration: z.string(),
  category: z.string()
});
export type CookieDefinition = z.infer<typeof CookieDefinitionSchema>;

export const CookiePolicySchema = z.object({
  noticeVersion: z.string(),
  effectiveDate: z.string(),
  noticeHref: z.string(),
  categories: z.array(CookieCategorySchema),
  cookies: z.array(CookieDefinitionSchema)
});
export type CookiePolicy = z.infer<typeof CookiePolicySchema>;

export const ConsentSourceSchema = z.enum(['banner', 'preferences', 'privacy-choices']);
export type ConsentSource = z.infer<typeof ConsentSourceSchema>;

export const ConsentRequestSchema = z.object({
  categories: z.record(z.string(), z.boolean()),
  saleOptOut: z.boolean().optional(),
  gpc: z.boolean().optional(),
  source: ConsentSourceSchema.optional()
});
export type ConsentRequest = z.infer<typeof ConsentRequestSchema>;

export const ConsentRecordSchema = z.object({
  id: z.string(),
  categories: z.record(z.string(), z.boolean()),
  gpc: z.boolean(),
  saleOptOut: z.boolean(),
  policyVersion: z.string(),
  recordedAt: z.string()
});
export type ConsentRecord = z.infer<typeof ConsentRecordSchema>;

export const PRIVACY_REQUEST_TYPES = ['access', 'delete', 'correct', 'optout'] as const;
export const PrivacyRequestTypeSchema = z.enum(PRIVACY_REQUEST_TYPES);
export type PrivacyRequestType = z.infer<typeof PrivacyRequestTypeSchema>;

/** Longest free text on a privacy request. */
export const PRIVACY_DETAILS_MAX = 2000;

export const PrivacyRequestSchema = z.object({
  type: PrivacyRequestTypeSchema,
  email: z.string(),
  state: z.string().regex(/^[A-Z]{2}$/),
  details: z.string().max(PRIVACY_DETAILS_MAX).optional(),
  locale: z.string().optional()
});
export type PrivacyRequestInput = z.infer<typeof PrivacyRequestSchema>;

export const PrivacyRequestReceiptSchema = z.object({
  reference: z.string(),
  type: PrivacyRequestTypeSchema,
  status: z.enum(['RECEIVED', 'COMPLETED', 'DENIED']),
  submittedAt: z.string(),
  dueAt: z.string()
});
export type PrivacyRequestReceipt = z.infer<typeof PrivacyRequestReceiptSchema>;
