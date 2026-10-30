/* The public content renderers shared by the storefront and the staff console. */
export { HelpPageContent, type HelpPageContentProps, type HelpSectionContent, LegalDocumentContent, type LegalDocumentContentProps, LegalDraftBanner, LegalNote } from './LegalDocument';
export {
  countLegalMarkers,
  headingId,
  LEGAL_MARKER,
  type LegalBlock,
  type LegalBlockClasses,
  LegalBlocks,
  type LegalSection,
  type MarkerStyle,
  type ParsedLegalDocument,
  parseLegalMarkdown,
  renderInline,
  safeHref
} from './markdown';
export { fillPlaceholders, type MarkdownSection, type OutlineEntry, outlineOf, parseSections, placeholdersUsed, sectionsMarkdown, slugOf } from './text';
export { type ContentIssue, errorsOf, type IssueCode, type IssueLevel, validateMarkdown, type ValidateOptions } from './validate';
