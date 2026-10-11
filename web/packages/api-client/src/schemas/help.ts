import { z } from 'zod';

/** Help center topics (content-service HelpTopic), in display order. */
export const HELP_TOPICS = ['ORDERS_DELIVERY', 'RETURNS_REFUNDS', 'ACCOUNT_PAYMENTS', 'SELLING', 'COMPANY'] as const;
export const HelpTopicKeySchema = z.enum(HELP_TOPICS);
export type HelpTopicKey = z.infer<typeof HelpTopicKeySchema>;

export const isHelpTopicKey = (v: string): v is HelpTopicKey => (HELP_TOPICS as readonly string[]).includes(v);

/** The help center hub's slug; it renders at /help. */
export const HELP_HUB = 'help-center';

/** Storefront path of a help page (the hub is /help). */
export const helpHref = (slug: string): string => (slug === HELP_HUB ? '/help' : `/help/${encodeURIComponent(slug)}`);

export const HelpSectionSchema = z.object({ heading: z.string(), body: z.string() });
export type HelpSection = z.infer<typeof HelpSectionSchema>;

export const HelpArticleSchema = z.object({
  slug: z.string(),
  topic: HelpTopicKeySchema,
  topicTitle: z.string(),
  title: z.string(),
  summary: z.string(),
  sections: z.array(HelpSectionSchema),
  updatedAt: z.string()
});
export type HelpArticle = z.infer<typeof HelpArticleSchema>;

export const HelpArticleSummarySchema = HelpArticleSchema.omit({ sections: true });
export type HelpArticleSummary = z.infer<typeof HelpArticleSummarySchema>;

export const HelpTopicCountSchema = z.object({ key: HelpTopicKeySchema, title: z.string(), count: z.number().int().nonnegative() });
export type HelpTopicCount = z.infer<typeof HelpTopicCountSchema>;

export const HelpArticlePageSchema = z.object({
  items: z.array(HelpArticleSummarySchema),
  topics: z.array(HelpTopicCountSchema),
  nextCursor: z.string().nullable()
});
export type HelpArticlePage = z.infer<typeof HelpArticlePageSchema>;

/** Filters of the help article list (cursor paging: the next page is loaded as the shopper scrolls). */
export interface HelpQuery {
  topic?: HelpTopicKey;
  q?: string;
  limit?: number;
}
