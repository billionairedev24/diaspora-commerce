/* Settings → Published pages (contracts/openapi/gateway-bff-staff.yaml, tag Published pages): every public page with its
   absolute live link, and for legal documents and help pages the editor behind it: the page as published, a draft that
   changes nothing public, any past version, publication with a reason (a second approver for legal documents) and
   rollback. */
import { queryOptions } from '@tanstack/react-query';
import { z } from 'zod';

import { type Requester } from './http';

export const CONTENT_LOCALES = ['en', 'fr', 'es', 'ar', 'yo'] as const;
export const ContentLocaleSchema = z.enum(CONTENT_LOCALES);
export type ContentLocale = z.infer<typeof ContentLocaleSchema>;

export const ContentKindSchema = z.enum(['legal', 'help']);
export type ContentKind = z.infer<typeof ContentKindSchema>;

export const ContentPageStatusSchema = z.enum(['PUBLISHED', 'DRAFT_FOR_COUNSEL', 'HIDDEN', 'DEVELOPER_ONLY']);
export type ContentPageStatus = z.infer<typeof ContentPageStatusSchema>;

export const ContentPageRowSchema = z.object({
  /** legal, help, or record for content edited in its own table. */
  kind: z.string(),
  key: z.string(),
  title: z.string(),
  group: z.string(),
  livePath: z.string().nullable(),
  /** Absolute storefront address; open it in a new tab. */
  liveUrl: z.string().nullable(),
  status: ContentPageStatusSchema,
  version: z.string(),
  effectiveDate: z.string().nullable(),
  updatedAt: z.string().nullable(),
  updatedByName: z.string().nullable(),
  hasDraft: z.boolean(),
  draftUpdatedByName: z.string().nullable(),
  editor: z.enum(['page', 'record', 'developer']),
  resource: z.string().nullable(),
  resourceId: z.string().nullable(),
  consoleHref: z.string().nullable(),
  canEdit: z.boolean(),
  locales: z.array(ContentLocaleSchema)
});
export type ContentPageRow = z.infer<typeof ContentPageRowSchema>;

export const ContentPageListSchema = z.object({ storefrontUrl: z.string().nullable(), items: z.array(ContentPageRowSchema) });
export type ContentPageList = z.infer<typeof ContentPageListSchema>;

export const ContentDocumentSchema = z.object({
  version: z.string(),
  effectiveDate: z.string().nullable(),
  draftBanner: z.boolean().nullable(),
  title: z.string(),
  summary: z.string().nullable(),
  topic: z.string().nullable(),
  sortOrder: z.number().int().nullable(),
  /** Markdown per locale (en always); help pages are the sections as one text, a `## Heading` line before each. */
  bodies: z.record(z.string(), z.string()),
  needsReview: z.array(z.string()),
  changeSummary: z.string().nullable(),
  rowVersion: z.number().int().nullable(),
  updatedAt: z.string().nullable(),
  updatedByName: z.string().nullable(),
  reason: z.string().nullable()
});
export type ContentDocument = z.infer<typeof ContentDocumentSchema>;

export const ContentVersionRefSchema = z.object({
  version: z.string(),
  status: z.enum(['CURRENT', 'ARCHIVED']),
  effectiveDate: z.string().nullable(),
  changeSummary: z.string().nullable(),
  reason: z.string().nullable(),
  publishedAt: z.string().nullable(),
  publishedByName: z.string().nullable(),
  draftBanner: z.boolean().nullable(),
  locales: z.array(z.string())
});
export type ContentVersionRef = z.infer<typeof ContentVersionRefSchema>;

export const ContentAccessSchema = z.object({
  canEdit: z.boolean(),
  canPublish: z.boolean(),
  /** Someone other than the draft's last editor must publish it. */
  secondApprover: z.boolean(),
  reason: z.string().nullable()
});
export type ContentAccess = z.infer<typeof ContentAccessSchema>;

export const ContentPageSchema = z.object({
  kind: ContentKindSchema,
  key: z.string(),
  title: z.string(),
  group: z.string(),
  livePath: z.string().nullable(),
  liveUrl: z.string().nullable(),
  locales: z.array(ContentLocaleSchema),
  published: ContentDocumentSchema,
  draft: ContentDocumentSchema.nullable(),
  versions: z.array(ContentVersionRefSchema),
  /** Values for the {phone}, {legal}, {address}… tokens of legal Markdown. */
  placeholders: z.record(z.string(), z.string()),
  topics: z.array(z.object({ value: z.string(), label: z.string() })),
  access: ContentAccessSchema
});
export type ContentPage = z.infer<typeof ContentPageSchema>;

export interface ContentDraftInput {
  /** Legal only; higher than the published version. */
  version?: string;
  effectiveDate?: string;
  draftBanner?: boolean;
  title?: string;
  summary?: string;
  topic?: string;
  sortOrder?: number;
  bodies: Record<string, string>;
  needsReview?: string[];
  changeSummary?: string;
  /** The draft version the editor loaded (omitted on the first save). */
  rowVersion?: number;
}

const enc = encodeURIComponent;
const base = (kind: ContentKind, key: string): string => `/v1/staff/content/pages/${enc(kind)}/${enc(key)}`;

/** Published pages routes of the staff BFF (mounted as `staffApi().content`). */
export function createStaffContentApi(request: Requester) {
  return {
    pages: (signal?: AbortSignal): Promise<ContentPageList> => request('/v1/staff/content/pages', ContentPageListSchema, { signal }),
    page: (kind: ContentKind, key: string, signal?: AbortSignal): Promise<ContentPage> => request(base(kind, key), ContentPageSchema, { signal }),
    version: (kind: ContentKind, key: string, version: string, signal?: AbortSignal): Promise<ContentDocument> =>
      request(`${base(kind, key)}/versions/${enc(version)}`, ContentDocumentSchema, { signal }),
    /** Saves the draft; nothing public changes. 409 when someone saved in between, 422 names the fields. */
    saveDraft: (kind: ContentKind, key: string, draft: ContentDraftInput): Promise<ContentDocument> =>
      request(`${base(kind, key)}/draft`, ContentDocumentSchema, { method: 'PUT', body: draft }),
    discardDraft: (kind: ContentKind, key: string): Promise<void> => request(`${base(kind, key)}/draft`, z.void(), { method: 'DELETE' }),
    /** Publishes the draft the approver reviewed (`rowVersion`) with a reason. */
    publish: (kind: ContentKind, key: string, input: { reason: string; rowVersion: number }): Promise<ContentPage> =>
      request(`${base(kind, key)}/publication`, ContentPageSchema, { method: 'POST', body: input }),
    /** Help: publishes the revision at once. Legal: starts a draft from it (a second person publishes). */
    rollback: (kind: ContentKind, key: string, input: { version: string; reason?: string }): Promise<ContentPage> =>
      request(`${base(kind, key)}/rollback`, ContentPageSchema, { method: 'POST', body: input })
  };
}

export type StaffContentApi = ReturnType<typeof createStaffContentApi>;

export const staffContentKeys = {
  all: ['staff', 'content'] as const,
  pages: () => [...staffContentKeys.all, 'pages'] as const,
  page: (kind: ContentKind, key: string) => [...staffContentKeys.all, 'page', kind, key] as const,
  version: (kind: ContentKind, key: string, version: string) => [...staffContentKeys.page(kind, key), 'version', version] as const
};

export const staffContentQueries = {
  pages: (api: StaffContentApi) => queryOptions({ queryKey: staffContentKeys.pages(), queryFn: ({ signal }) => api.pages(signal), staleTime: 10_000 }),
  page: (api: StaffContentApi, kind: ContentKind, key: string) =>
    queryOptions({ queryKey: staffContentKeys.page(kind, key), queryFn: ({ signal }) => api.page(kind, key, signal), staleTime: 5_000 }),
  version: (api: StaffContentApi, kind: ContentKind, key: string, version: string) =>
    queryOptions({ queryKey: staffContentKeys.version(kind, key, version), queryFn: ({ signal }) => api.version(kind, key, version, signal), staleTime: Infinity })
};

/** Where a published page can be opened for preview and editing in the console. */
export const contentPageHref = (kind: ContentKind, key: string): string => `/settings/pages/${enc(kind)}/${enc(key)}`;
