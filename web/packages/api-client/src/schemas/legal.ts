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
  'sms-terms'
] as const;
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
  'cookie-policy': 'cookies'
};

export const isLegalDocumentKey = (v: string): v is LegalDocumentKey => (LEGAL_DOCUMENT_KEYS as readonly string[]).includes(v);

/** Canonical key for a slug (key or alias), or null when unknown. */
export function legalDocumentKeyOf(slug: string): LegalDocumentKey | null {
  const s = slug.trim().toLowerCase();
  return isLegalDocumentKey(s) ? s : (LEGAL_DOCUMENT_ALIASES[s] ?? null);
}

/** Storefront path of a document (relative; prefix PUBLIC_WEB_URL for other apps). */
export const legalHref = (key: LegalDocumentKey): string => `/legal/${key}`;

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
