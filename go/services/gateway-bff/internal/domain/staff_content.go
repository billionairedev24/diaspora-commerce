package domain

// Published pages (Settings → Published pages; contracts/openapi/gateway-bff-staff.yaml, tag Published pages): every
// public page staff can see, preview and edit without a developer.

// Content page kinds that have the in-console editor.
const (
	ContentKindLegal = "legal"
	ContentKindHelp  = "help"
)

// ContentLocales are the storefront languages, English first (English is the binding text and the fallback).
var ContentLocales = []string{"en", "fr", "es", "ar", "yo"}

// ContentPageRow is one line of the Published pages index.
type ContentPageRow struct {
	Kind  string `json:"kind"`
	Key   string `json:"key"`
	Title string `json:"title"`
	Group string `json:"group"`
	// LivePath is the storefront path; LiveURL the absolute address (PUBLIC_WEB_URL), nil when the storefront origin is unknown.
	LivePath *string `json:"livePath"`
	LiveURL  *string `json:"liveUrl"`
	Status   string  `json:"status"`
	Version  string  `json:"version"`
	// EffectiveDate is a legal document's effective date.
	EffectiveDate      *string `json:"effectiveDate"`
	UpdatedAt          *string `json:"updatedAt"`
	UpdatedByName      *string `json:"updatedByName"`
	HasDraft           bool    `json:"hasDraft"`
	DraftUpdatedByName *string `json:"draftUpdatedByName"`
	// Editor is "page" for the in-console page editor (kind legal / help), "record" for content edited in its own table
	// (Resource and ResourceID say which record; ConsoleHref is a console path when there is no table) and "developer"
	// for pages whose text still ships with the code.
	Editor     string  `json:"editor"`
	Resource   *string `json:"resource"`
	ResourceID *string `json:"resourceId"`
	// ConsoleHref is a console path for content that has its own screen (notification templates).
	ConsoleHref *string  `json:"consoleHref"`
	CanEdit     bool     `json:"canEdit"`
	Locales     []string `json:"locales"`
}

// ContentPageList is the Published pages index.
type ContentPageList struct {
	StorefrontURL *string          `json:"storefrontUrl"`
	Items         []ContentPageRow `json:"items"`
}

// ContentDocument is the content of one version of a page (published, draft or past).
type ContentDocument struct {
	Version       string  `json:"version"`
	EffectiveDate *string `json:"effectiveDate"`
	DraftBanner   *bool   `json:"draftBanner"`
	Title         string  `json:"title"`
	Summary       *string `json:"summary"`
	Topic         *string `json:"topic"`
	SortOrder     *int    `json:"sortOrder"`
	// Bodies is the Markdown per locale; legal documents may hold all five, help pages English only.
	Bodies        map[string]string `json:"bodies"`
	NeedsReview   []string          `json:"needsReview"`
	ChangeSummary *string           `json:"changeSummary"`
	// RowVersion is the draft's optimistic-locking counter (nil for published versions).
	RowVersion    *int64  `json:"rowVersion"`
	UpdatedAt     *string `json:"updatedAt"`
	UpdatedByName *string `json:"updatedByName"`
	Reason        *string `json:"reason"`
}

// ContentVersionRef is one line of a page's history.
type ContentVersionRef struct {
	Version         string   `json:"version"`
	Status          string   `json:"status"`
	EffectiveDate   *string  `json:"effectiveDate"`
	ChangeSummary   *string  `json:"changeSummary"`
	Reason          *string  `json:"reason"`
	PublishedAt     *string  `json:"publishedAt"`
	PublishedByName *string  `json:"publishedByName"`
	DraftBanner     *bool    `json:"draftBanner"`
	Locales         []string `json:"locales"`
}

// ContentAccess says what this staff member can do with the page.
type ContentAccess struct {
	CanEdit    bool `json:"canEdit"`
	CanPublish bool `json:"canPublish"`
	// SecondApprover: someone other than the draft's last editor must publish it (legal documents).
	SecondApprover bool `json:"secondApprover"`
	// Reason explains why publishing is not possible for this person now.
	Reason *string `json:"reason"`
}

// ContentPage is everything the editor and the preview need for one page.
type ContentPage struct {
	Kind         string              `json:"kind"`
	Key          string              `json:"key"`
	Title        string              `json:"title"`
	Group        string              `json:"group"`
	LivePath     *string             `json:"livePath"`
	LiveURL      *string             `json:"liveUrl"`
	Locales      []string            `json:"locales"`
	Published    ContentDocument     `json:"published"`
	Draft        *ContentDocument    `json:"draft"`
	Versions     []ContentVersionRef `json:"versions"`
	Placeholders map[string]string   `json:"placeholders"`
	Topics       []FieldOption       `json:"topics"`
	Access       ContentAccess       `json:"access"`
}

// ContentDraftInput is the editor's whole draft (a save replaces the draft).
type ContentDraftInput struct {
	Version       string            `json:"version"`
	EffectiveDate string            `json:"effectiveDate"`
	DraftBanner   *bool             `json:"draftBanner"`
	Title         string            `json:"title"`
	Summary       string            `json:"summary"`
	Topic         string            `json:"topic"`
	SortOrder     *int              `json:"sortOrder"`
	Bodies        map[string]string `json:"bodies"`
	NeedsReview   []string          `json:"needsReview"`
	ChangeSummary string            `json:"changeSummary"`
	RowVersion    *int64            `json:"rowVersion"`
}

// ContentPublishInput publishes the draft the approver reviewed.
type ContentPublishInput struct {
	Reason     string `json:"reason"`
	RowVersion int64  `json:"rowVersion"`
}

// ContentRollbackInput republishes (help) or restarts a draft from (legal) an earlier version.
type ContentRollbackInput struct {
	Version string `json:"version"`
	Reason  string `json:"reason"`
}
