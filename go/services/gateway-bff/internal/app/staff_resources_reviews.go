package app

import (
	"context"
	"net/url"
	"strconv"
	"strings"

	"github.com/billionairedev24/diaspora-commerce/go/services/gateway-bff/internal/domain"
)

// Product reviews in the staff console (S324): the moderation queue shows reviews that failed the automatic checks or
// were reported (trust-ops, type PRODUCT_REVIEW), and the Product reviews table lists every review of catalog with
// Remove for published ones. Staff see people by name: the author is resolved from the account, never shown as an id.

var reviewReasonCodes = domain.StaffOptions("ABUSIVE", "PERSONAL_INFORMATION", "SPAM", "OFF_TOPIC", "CONFLICT_OF_INTEREST", "OTHER")

func starsOf(n float64) string {
	r := int(n)
	if r < 0 || r > 5 {
		return ""
	}
	return strings.Repeat("★", r) + strings.Repeat("☆", 5-r) + " (" + strconv.Itoa(r) + " of 5)"
}

// liftReviewAuthor copies a queue item's review author to the record so the console can name them (customerId →
// customerName); items that are not reviews pass through.
func liftReviewAuthor(o jobj) jobj {
	if o == nil {
		return o
	}
	if id := jstr(o, "review.customerId"); id != "" {
		o["customerId"] = id
	}
	return o
}

func liftedList(next func(context.Context, staffCtx, staffQuery) (staffList, error)) func(context.Context, staffCtx, staffQuery) (staffList, error) {
	return func(ctx context.Context, sc staffCtx, q staffQuery) (staffList, error) {
		page, err := next(ctx, sc, q)
		for _, it := range page.items {
			liftReviewAuthor(it)
		}
		return page, err
	}
}

func liftedGet(next func(context.Context, staffCtx, string) (jobj, error)) func(context.Context, staffCtx, string) (jobj, error) {
	return func(ctx context.Context, sc staffCtx, id string) (jobj, error) {
		o, err := next(ctx, sc, id)
		return liftReviewAuthor(o), err
	}
}

// reviewFields lays out what a moderator reads: the stars, the words, the photos and why the review is here.
func reviewFields(o jobj, prefix string, extra ...domain.DetailField) []domain.DetailField {
	key := func(k string) string {
		if prefix == "" {
			return k
		}
		return prefix + "." + k
	}
	fields := []domain.DetailField{{Label: "Rating", Value: starsOf(num(o, key("rating"))), Kind: "text"}}
	if t := jstr(o, key("title")); t != "" {
		fields = append(fields, domain.DetailField{Label: "Title", Value: t, Kind: "text"})
	}
	if t := jstr(o, key("text")); t != "" {
		fields = append(fields, domain.DetailField{Label: "Review", Value: t, Kind: "text"})
	}
	fields = append(fields, domain.DetailField{Label: "Shown as", Value: jstr(o, key("reviewerName")), Kind: "text"})
	if flags, ok := at(o, key("flags")).([]any); ok && len(flags) > 0 {
		names := make([]string, 0, len(flags))
		for _, f := range flags {
			if s, ok := f.(string); ok {
				names = append(names, reviewFlagLabels[s])
			}
		}
		fields = append(fields, domain.DetailField{Label: "Automatic checks found", Value: strings.Join(names, ", "), Kind: "text"})
	}
	return append(fields, extra...)
}

// reviewPhotos lists the review's photos in a section of their own: the console shows a section made only of images as a
// gallery (https URLs only). Nil when there are none.
func reviewPhotos(o jobj, prefix string) *domain.DetailSection {
	key := "photos"
	urlKey := "photoUrls"
	if prefix != "" {
		key, urlKey = prefix+".photos", prefix+".photoUrls"
	}
	var fields []domain.DetailField
	add := func(u string) {
		if strings.HasPrefix(u, "https://") {
			fields = append(fields, domain.DetailField{Label: "Photo " + strconv.Itoa(len(fields)+1), Value: u, Kind: "image"})
		}
	}
	if urls, ok := at(o, urlKey).([]any); ok {
		for _, p := range urls {
			if u, ok := p.(string); ok {
				add(u)
			}
		}
	}
	for _, p := range objs(o, key) {
		add(jstr(p, "url"))
	}
	if len(fields) == 0 {
		return nil
	}
	return &domain.DetailSection{Title: "Photos", Fields: fields}
}

var reviewFlagLabels = map[string]string{"PROFANITY": "profanity", "LINK": "a link", "EMAIL": "an e-mail address", "PHONE": "a phone number"}

// reviewItemSections is the sheet of a PRODUCT_REVIEW or SERVICE_REVIEW queue item.
func reviewItemSections(o jobj) []domain.DetailSection {
	what := "Booking"
	if jstr(o, "type") == "PRODUCT_REVIEW" {
		what = "Product"
	}
	var extra []domain.DetailField
	if n := num(o, "review.reports"); n > 0 {
		extra = append(extra, domain.DetailField{Label: "Reports", Value: strconv.Itoa(int(n)), Kind: "text"},
			domain.DetailField{Label: "First reported for", Value: domain.Humanize(jstr(o, "review.reportReason")), Kind: "text"})
	}
	review := domain.DetailSection{Title: "Review", Fields: reviewFields(o, "review", extra...)}
	if serviceName := jstr(o, "review.serviceName"); serviceName != "" {
		review.Fields = append([]domain.DetailField{{Label: what, Value: serviceName, Kind: "text"}}, review.Fields...)
	}
	secs := []domain.DetailSection{review}
	if photos := reviewPhotos(o, "review"); photos != nil {
		secs = append(secs, *photos)
	}
	return append(secs, autoSections(o, "status", "review", "report", "title", "atin", "productId", "ownerKind", "productTypeCode")...)
}

// productReviewsResource is the Product reviews table (catalog-service): every review in any state but deleted.
func productReviewsResource() *staffResource {
	act := func(path string, body func(v jobj) jobj) func(context.Context, staffCtx, jobj, jobj) error {
		return func(ctx context.Context, sc staffCtx, o jobj, v jobj) error {
			return sc.post(ctx, sc.up.Catalog, "/v1/product-reviews/"+url.PathEscape(jstr(o, "id"))+path, body(v), nil)
		}
	}
	decisionForm := func(title, submit string) func(context.Context, staffCtx, jobj) *domain.Form {
		return func(context.Context, staffCtx, jobj) *domain.Form {
			return &domain.Form{Title: title, SubmitLabel: submit, Fields: []domain.FormField{
				withOptions(ff("reasonCode", "Reason (the customer sees it)", "select", true), reviewReasonCodes), withMax(ff("note", "Note", "textarea", false), 500)}}
		}
	}
	decision := func(v jobj) jobj { return omitEmpty(jobj{"reasonCode": vs(v, "reasonCode"), "note": vs(v, "note")}) }
	return &staffResource{
		key: "product-reviews", module: "catalog", title: "Product reviews", statusKey: "status", exportable: true, searchPlaceholder: "Product ATIN",
		columns: []domain.Column{col("productTitle", "Product", "strong", 26), col("stars", "Rating", "text", 12), col("reviewerName", "Shown as", "text", 12),
			col("sellerOrgName", "Seller", "text", 16), col("reportCount", "Reports", "number", 8), col("createdAt", "Written", "datetime", 14), col("status", "Status", "status", 10)},
		facets: domain.StaffFacets("PENDING", "PUBLISHED", "REJECTED", "REMOVED"),
		cells: func(o jobj) map[string]any {
			return map[string]any{"stars": starsOf(num(o, "rating"))}
		},
		list: listPaged("/v1/product-reviews", upCatalog, func(q staffQuery, v url.Values) {
			if q.Q != "" {
				v.Set("atin", strings.ToUpper(q.Q))
			}
		}),
		get:        getByPath("/v1/product-reviews/", upCatalog),
		names:      []nameRef{sellerRef("sellerOrgId"), customerRef("customerId")},
		titleOf:    func(o jobj) string { return starsOf(num(o, "rating")) + " · " + jstr(o, "productTitle") },
		subtitleOf: func(o jobj) string { return jstr(o, "atin") + " · shown as " + jstr(o, "reviewerName") },
		version:    func(o jobj) string { return jstr(o, "version") },
		sections: func(o jobj) []domain.DetailSection {
			secs := []domain.DetailSection{{Title: "Review", Fields: reviewFields(o, "", domain.DetailField{Label: "Order", Value: jstr(o, "orderNumber"), Kind: "mono"})}}
			if photos := reviewPhotos(o, ""); photos != nil {
				secs = append(secs, *photos)
			}
			if reply := jstr(o, "reply.text"); reply != "" {
				secs = append(secs, domain.DetailSection{Title: "Seller reply", Fields: []domain.DetailField{{Label: "Reply", Value: reply, Kind: "text"}}})
			}
			if by := jstr(o, "moderation.by"); by != "" {
				secs = append(secs, domain.DetailSection{Title: "Decision", Fields: []domain.DetailField{{Label: "Decided by", Value: by, Kind: "text"},
					{Label: "Reason", Value: domain.Humanize(jstr(o, "moderation.reasonCode")), Kind: "text"}, {Label: "Note", Value: jstr(o, "moderation.note"), Kind: "text"}}})
			}
			return append(secs, autoSections(o, "status", "rating", "title", "text", "photos", "flags", "reply", "moderation", "orderNumber", "productTitle", "reviewerName", "version")...)
		},
		actions: []staffAction{
			{key: "approve", label: "Publish", tone: "primary", permission: "catalog:moderate", level: domain.LevelApprove,
				form: noteForm("Publish the review", "Publish", false), when: inState("PENDING", "REJECTED", "REMOVED"),
				run: act("/approve", func(v jobj) jobj { return omitEmpty(jobj{"note": vs(v, "note")}) })},
			{key: "reject", label: "Reject", tone: "danger", permission: "catalog:moderate", level: domain.LevelApprove,
				form: decisionForm("Reject the review", "Reject"), when: inState("PENDING"), run: act("/reject", decision)},
			{key: "remove", label: "Remove", tone: "danger", permission: "catalog:moderate", level: domain.LevelApprove,
				form: decisionForm("Take the review down", "Remove"), when: inState("PENDING", "PUBLISHED"), run: act("/remove", decision)},
		},
	}
}
