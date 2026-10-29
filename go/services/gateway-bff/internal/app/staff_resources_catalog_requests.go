package app

import (
	"context"
	"net/url"
	"sort"
	"strconv"
	"strings"

	"github.com/billionairedev24/diaspora-commerce/go/services/gateway-bff/internal/domain"
)

// ---------------------------------------------------------------- category requests (catalog)

// categoryRequestStatuses are shown newest decisions after the open queue.
var categoryRequestStatuses = []string{"PENDING_REVIEW", "APPROVED", "REJECTED"}

// categoryRequestsResource is the review queue of seller requests for a category that does not exist yet. A request
// names a place in the tree and a new category; the products sellers built against it wait until staff approve it.
// Approving creates the category (when it goes under a department) and the product-type leaf, with an existing
// schema or a new one, in one step; the sheet says what will be created before anyone confirms.
func categoryRequestsResource() *staffResource {
	return &staffResource{
		key: "category-requests", module: "catalog", title: "Category requests", statusKey: "status", exportable: true,
		searchPlaceholder: "Category, seller or reference",
		columns: []domain.Column{col("reference", "Request", "mono", 11), col("orgName", "Seller", "strong", 18), col("path", "Requested category", "text", 30),
			col("productsWaiting", "Products waiting", "number", 10), col("createdAt", "Requested", "datetime", 14), col("status", "Status", "status", 12)},
		facets: domain.StaffFacets(categoryRequestStatuses...),
		names:  []nameRef{sellerRef("orgId")},
		search: []string{"reference"},
		list: listAll(func(ctx context.Context, sc staffCtx, q staffQuery) ([]jobj, error) {
			statuses := categoryRequestStatuses
			if q.Status != "" {
				statuses = []string{q.Status}
			}
			all := []jobj{}
			for _, st := range statuses {
				var raw any
				if err := sc.get(ctx, sc.up.Catalog, "/v1/category-requests", url.Values{"status": {st}}, &raw); err != nil {
					return nil, err
				}
				page := jpage(raw).items
				if st != "PENDING_REVIEW" {
					// Decided requests: newest first.
					sort.SliceStable(page, func(i, j int) bool { return jstr(page[i], "decidedAt") > jstr(page[j], "decidedAt") })
				}
				all = append(all, page...)
			}
			return all, nil
		}),
		get: func(ctx context.Context, sc staffCtx, id string) (jobj, error) {
			var detail jobj
			if err := sc.get(ctx, sc.up.Catalog, "/v1/category-requests/"+url.PathEscape(id), nil, &detail); err != nil {
				return nil, err
			}
			o, _ := detail["request"].(map[string]any)
			if o == nil {
				return nil, notFoundResource()
			}
			o["products"] = detail["products"]
			o["suggestedParent"] = detail["suggestedParent"]
			if jstr(o, "status") == "PENDING_REVIEW" {
				o["plan"] = defaultApprovalPlan(ctx, sc, o)
			}
			return o, nil
		},
		titleOf: func(o jobj) string { return jstr(o, "name") },
		subtitleOf: func(o jobj) string {
			return strings.Trim(jstr(o, "reference")+" · "+jstr(o, "orgName")+" · "+jstr(o, "path"), " ·")
		},
		sections: func(o jobj) []domain.DetailSection {
			request := domain.DetailSection{Title: "Request", Fields: []domain.DetailField{
				fieldOf("Seller", jstr(o, "orgName"), "text"),
				fieldOf("Requested category", jstr(o, "path"), "text"),
				fieldOf("Goes under", jstr(o, "parentPath"), "text"),
				fieldOf("Example product", jstr(o, "exampleProduct"), "text"),
				fieldOf("Details shoppers need", jstr(o, "detailsNeeded"), "text"),
				fieldOf("Requested", jstr(o, "createdAt"), "datetime"),
				fieldOf("Status", jstr(o, "status"), "status"),
			}}
			if note := jstr(o, "decisionNote"); note != "" {
				request.Fields = append(request.Fields, fieldOf("Decision note", note, "text"), fieldOf("Decided", jstr(o, "decidedAt"), "datetime"))
			}
			secs := []domain.DetailSection{request}
			if jstr(o, "status") == "PENDING_REVIEW" {
				secs = append(secs, domain.DetailSection{Title: "If you approve as suggested", Fields: planFields(o)})
			}
			return secs
		},
		tables: func(o jobj) []domain.DetailTable {
			return []domain.DetailTable{tableOf("Products waiting for this category", objs(o, "products"),
				[]domain.Column{col("atin", "ATIN", "mono", 20), col("title", "Product", "strong", 80)}, "id")}
		},
		links: func(o jobj) []domain.RecordLink {
			return []domain.RecordLink{{Label: "Open the category tree", Href: "/moderation?tab=categories"}}
		},
		actions: []staffAction{
			{key: "approve", label: "Approve & create", tone: "primary", permission: permTaxonomy, level: domain.LevelApprove, when: inState("PENDING_REVIEW"),
				form: approveRequestForm,
				run: func(ctx context.Context, sc staffCtx, o jobj, v jobj) error {
					body, err := approvalBody(ctx, sc, v)
					if err != nil {
						return err
					}
					return sc.post(ctx, sc.up.Catalog, "/v1/category-requests/"+url.PathEscape(jstr(o, "id"))+"/approve", body, nil)
				}},
			{key: "reject", label: "Reject", tone: "danger", permission: permTaxonomy, level: domain.LevelApprove, when: inState("PENDING_REVIEW"),
				form: func(context.Context, staffCtx, jobj) *domain.Form {
					return &domain.Form{Title: "Reject the category request", SubmitLabel: "Reject",
						Intro: "The seller sees your reason. Products they built against this request are rejected with it.",
						Fields: []domain.FormField{withMax(withHelp(ff("note", "Reason", "textarea", true), "At least 3 characters"), 1000)}}
				},
				run: func(ctx context.Context, sc staffCtx, o jobj, v jobj) error {
					return sc.post(ctx, sc.up.Catalog, "/v1/category-requests/"+url.PathEscape(jstr(o, "id"))+"/reject", jobj{"note": vs(v, "note")}, nil)
				}},
		},
	}
}

func withHelp(f domain.FormField, help string) domain.FormField {
	f.Help = help
	return f
}

// defaultApprovalPlan asks catalog what approving would create with the obvious choices: the place the seller named
// and a new product type that starts from the placeholder schema. An unresolvable path or a clash is reported as the
// plan's "problem" so the sheet can say why the reviewer must choose.
func defaultApprovalPlan(ctx context.Context, sc staffCtx, o jobj) jobj {
	parent, _ := o["suggestedParent"].(map[string]any)
	if parent == nil {
		return jobj{"problem": "“" + jstr(o, "parentPath") + "” is not in the category tree. Choose where it goes when you approve."}
	}
	body, err := approvalBody(ctx, sc, jobj{"parentCategoryId": jstr(parent, "id"), "productType": newProductTypeChoice})
	if err != nil {
		return jobj{"problem": catalogProblemText(err)}
	}
	var plan jobj
	if err := sc.post(ctx, sc.up.Catalog, "/v1/category-requests/"+url.PathEscape(jstr(o, "id"))+"/approval-preview", body, &plan); err != nil {
		return jobj{"problem": catalogProblemText(err)}
	}
	return plan
}

func catalogProblemText(err error) string {
	if p, ok := domain.AsProblem(err); ok {
		for _, msg := range p.Errors {
			return msg
		}
		return p.Detail
	}
	return "The plan could not be worked out."
}

func planFields(o jobj) []domain.DetailField {
	plan, _ := o["plan"].(map[string]any)
	if plan == nil {
		return []domain.DetailField{fieldOf("Plan", "Choose where it goes when you approve.", "text")}
	}
	if problem := jstr(plan, "problem"); problem != "" {
		return []domain.DetailField{fieldOf("Plan", problem, "text")}
	}
	return planLines(plan)
}

// approveRequestForm offers the places a request can go (departments and categories) and the product-type schemas;
// the place and a new schema are suggested from the request.
func approveRequestForm(ctx context.Context, sc staffCtx, o jobj) *domain.Form {
	places := []domain.FieldOption{}
	var tree []any
	_ = sc.get(ctx, sc.up.Catalog, "/v1/categories", nil, &tree)
	for _, it := range tree {
		if n, ok := it.(map[string]any); ok && jstr(n, "level") != "PRODUCT_TYPE" {
			label := jstr(n, "path")
			if jstr(n, "level") == "DEPARTMENT" {
				label += " (new category is created)"
			}
			places = append(places, domain.FieldOption{Value: jstr(n, "id"), Label: label})
		}
	}
	sort.SliceStable(places, func(i, j int) bool { return places[i].Label < places[j].Label })
	types := []domain.FieldOption{{Value: newProductTypeChoice, Label: "Create a new product type for this category"}}
	existing := []domain.FieldOption{}
	var typeList []any
	_ = sc.get(ctx, sc.up.Catalog, "/v1/taxonomy/product-types", nil, &typeList)
	for _, it := range typeList {
		if t, ok := it.(map[string]any); ok && jstr(t, "code") != placeholderType {
			existing = append(existing, domain.FieldOption{Value: jstr(t, "code"), Label: jstr(t, "label") + " (" + strconv.Itoa(len(objs(t, "attributes"))) + " details)"})
		}
	}
	sort.SliceStable(existing, func(i, j int) bool { return existing[i].Label < existing[j].Label })
	types = append(types, existing...)
	starts := append([]domain.FieldOption{{Value: placeholderType, Label: "A general product (material, details, brand, made in)"}}, existing...)

	parent, _ := o["suggestedParent"].(map[string]any)
	intro := "Approving creates the category and its product type in one step, then moves the products waiting for it into listing review. Everything is recorded with your name."
	if plan, ok := o["plan"].(map[string]any); ok {
		if lines := planLines(plan); len(lines) > 0 {
			parts := []string{}
			for _, l := range lines {
				parts = append(parts, l.Label+": "+l.Value)
			}
			intro += " As suggested: " + strings.Join(parts, "; ") + "."
		}
	}
	return &domain.Form{Title: "Approve and create", SubmitLabel: "Approve & create", Intro: intro, Fields: []domain.FormField{
		withValue(withOptions(ff("parentCategoryId", "Where it goes", "select", true), places), jstr(parent, "id")),
		withMax(withHelp(ff("categoryName", "Category name (only when it goes under a department)", "text", false), "Defaults to the requested name"), 80),
		withValue(withOptions(ff("productType", "Details sellers fill in", "select", true), types), newProductTypeChoice),
		withValue(withOptions(withHelp(ff("basedOn", "A new product type starts from", "select", false), "Refine its details afterwards under Categories"), starts), placeholderType),
		withMax(withHelp(ff("note", "Note to the seller", "textarea", false), "Shown to the seller with the decision"), 1000),
	}}
}
