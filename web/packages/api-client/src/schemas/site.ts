import { z } from 'zod';

export const SocialNetworkSchema = z.enum(['tiktok', 'instagram', 'facebook', 'x', 'youtube', 'linkedin', 'whatsapp']);
export type SocialNetwork = z.infer<typeof SocialNetworkSchema>;

export const IdentityProviderIdSchema = z.enum(['google', 'apple', 'facebook']);
export type IdentityProviderId = z.infer<typeof IdentityProviderIdSchema>;

export const SiteConfigSchema = z.object({
  revision: z.number().int(),
  /** Only enabled, validated profile links are returned. */
  socials: z.array(z.object({ network: SocialNetworkSchema, url: z.url(), label: z.string() })),
  contacts: z.object({
    phone: z.string(),
    /** The support phone in E.164 (+18335550199); also the number of the WhatsApp chat with support. Empty when not set. */
    phoneE164: z.string().default(''),
    privacyEmail: z.string(),
    accessibilityEmail: z.string(),
    legalEmail: z.string(),
    pressEmail: z.string(),
    partnersEmail: z.string(),
    address: z.string()
  }),
  legal: z.array(z.object({ key: z.string(), title: z.string(), version: z.string(), effective: z.string(), href: z.string() })),
  identityProviders: z.array(z.object({ id: IdentityProviderIdSchema, name: z.string(), enabled: z.boolean() })),
  locales: z.array(z.object({ code: z.string(), name: z.string(), dir: z.enum(['ltr', 'rtl']) })),
  market: z.object({ code: z.string(), currency: z.string(), country: z.string() }),
  appLinks: z.object({ ios: z.string().nullable(), android: z.string().nullable() }),
  paymentMethods: z.array(z.string()),
  smsProgramName: z.string()
});
export type SiteConfig = z.infer<typeof SiteConfigSchema>;

export const NewsletterRequestSchema = z.object({ email: z.email('Enter a valid email address.') });
export type NewsletterRequest = z.infer<typeof NewsletterRequestSchema>;
export const NewsletterResponseSchema = z.object({ status: z.enum(['pending_confirmation', 'subscribed']) });
