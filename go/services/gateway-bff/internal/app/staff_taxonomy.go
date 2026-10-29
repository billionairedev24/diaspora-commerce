package app

import (
	"context"
	"net/http"
	"net/url"
	"regexp"
	"slices"
	"strconv"
	"strings"
	"unicode/utf8"

	"github.com/billionairedev24/diaspora-commerce/go/services/gateway-bff/internal/domain"
	"github.com/billionairedev24/diaspora-commerce/go/services/gateway-bff/internal/port"
)

// StaffTaxonomyService is Catalog → Categories (contracts/openapi/gateway-bff-staff.yaml, tag Taxonomy): the department ›
// category › product type tree with product counts, the attribute schema of each product type, and the audit trail,
// over catalog's /v1/taxonomy API. Every change carries a reason and the version it was loaded at (If-Match), so two
// people cannot overwrite each other; catalog refuses to retire a node products still use. The category-request queue
// is the table "category-requests" of the records service (staff_resources_catalog_requests.go); this service adds the
// preview of what approving a request would create.
type StaffTaxonomyService interface {
	Tree(ctx context.Context, c *domain.Caller) (any, error)
	CreateNode(ctx context.Context, c *domain.Caller, body map[string]any) (any, error)
	RenameNode(ctx context.Context, c *domain.Caller, id, ifMatch string, body map[string]any) (any, error)
	MoveNode(ctx context.Context, c *domain.Caller, id, ifMatch string, body map[string]any) (any, error)
	RetireNode(ctx context.Context, c *domain.Caller, id, ifMatch string, body map[string]any) (any, error)
	RestoreNode(ctx context.Context, c *domain.Caller, id, ifMatch string, body map[string]any) (any, error)
	Reorder(ctx context.Context, c *domain.Caller, body map[string]any) (any, error)
	ProductTypes(ctx context.Context, c *domain.Caller) (any, error)
	ProductType(ctx context.Context, c *domain.Caller, code string) (any, error)
	CreateProductType(ctx context.Context, c *domain.Caller, body map[string]any) (any, error)
	UpdateProductType(ctx context.Context, c *domain.Caller, code, ifMatch string, body map[string]any) (any, error)
	Audit(ctx context.Context, c *domain.Caller, subject string, limit int) (any, error)
	// PreviewApproval says what approving a category request with these choices would create; nothing is created.
	PreviewApproval(ctx context.Context, c *domain.Caller, requestID string, body map[string]any) (any, error)
}

type staffTaxonomyImpl struct{ r *staffResolver }

var _ StaffTaxonomyService = (*staffTaxonomyImpl)(nil)

// NewStaffTaxonomyService returns the Categories use case.
func NewStaffTaxonomyService(d StaffDeps) StaffTaxonomyService {
	return &staffTaxonomyImpl{r: newStaffResolver(d)}
}

const (
	taxonomyModule = "catalog"
	permTaxonomy   = "catalog:taxonomy"
	// newProductTypeChoice is the value of the product-type field that asks for a new schema.
	newProductTypeChoice = "__new__"
	// placeholderType is the schema a new product type starts from unless staff pick another.
	placeholderType = "custom"
)

var (
	taxonomyIDRe    = regexp.MustCompile(`^[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}$`)
	productTypeRe   = regexp.MustCompile(`^[a-z][a-z0-9_]{1,39}$`)
	taxonomyLevels  = []string{"DEPARTMENT", "CATEGORY", "PRODUCT_TYPE"}
	identifierRules = []string{"GTIN", "ISBN"}
	attributeKinds  = []string{"TEXT", "MULTILINE", "ENUM", "NUMBER"}
	attributeSecs   = []string{"DETAILS", "FOOD", "REGULATED"}
)

func notFoundNode() error {
	return domain.NewProblem(http.StatusNotFound, domain.CodeNotFound, "Not found", "No such category.")
}

// need resolves the staff member: reading the tree needs the catalog module and catalog:taxonomy, changing it also
// the Edit level.
func (s *staffTaxonomyImpl) need(ctx context.Context, c *domain.Caller, edit bool) (staffCtx, bool, error) {
	sc, err := s.r.require(ctx, c, taxonomyModule, domain.LevelView)
	if err != nil {
		return sc, false, err
	}
	if !sc.access.Can(permTaxonomy) {
		return sc, false, needsPermission(permTaxonomy)
	}
	canEdit := sc.access.Allows(taxonomyModule, domain.LevelEdit)
	if edit && !canEdit {
		return sc, false, staffModuleDenied(taxonomyModule, domain.LevelEdit)
	}
	if sc.up.Catalog == nil {
		return sc, canEdit, domain.UpstreamUnavailable("catalog")
	}
	return sc, canEdit, nil
}

func (s *staffTaxonomyImpl) catalog(ctx context.Context, sc staffCtx, method, path, ifMatch string, q url.Values, body any) (jobj, error) {
	var out jobj
	err := sc.call(ctx, sc.up.Catalog, port.StaffCall{Method: method, Path: path, Query: q, Body: body, IfMatch: ifMatch}, &out)
	return out, err
}

func (s *staffTaxonomyImpl) catalogList(ctx context.Context, sc staffCtx, path string, q url.Values) ([]any, error) {
	var out []any
	err := sc.call(ctx, sc.up.Catalog, port.StaffCall{Path: path, Query: q}, &out)
	if out == nil {
		out = []any{}
	}
	return out, err
}

func nodePath(id string, rest ...string) (string, error) {
	if !taxonomyIDRe.MatchString(id) {
		return "", notFoundNode()
	}
	return "/v1/taxonomy/nodes/" + id + strings.Join(rest, ""), nil
}

// ---------------------------------------------------------------- validation (the BFF names the field)

func tidy(v any) string {
	s, _ := v.(string)
	return strings.Join(strings.Fields(s), " ")
}

// taxReasonOf is the reason every change carries: 3 to 1000 characters.
func taxReasonOf(body map[string]any, errs map[string]string) string {
	r := strings.TrimSpace(vs(body, "reason"))
	switch n := utf8.RuneCountInString(r); {
	case n < 3:
		errs["reason"] = "Say why you are making this change (at least 3 characters)"
	case n > 1000:
		errs["reason"] = "At most 1,000 characters"
	}
	return r
}

func taxNameOf(body map[string]any, key string, errs map[string]string) string {
	n := tidy(body[key])
	if l := utf8.RuneCountInString(n); l < 2 || l > 80 {
		errs[key] = "2 to 80 characters"
	} else if strings.Contains(n, "›") {
		errs[key] = "Remove the › character"
	}
	return n
}

func uuidOf(body map[string]any, key string, required bool, errs map[string]string) string {
	id := strings.TrimSpace(vs(body, key))
	if id == "" && !required {
		return ""
	}
	if !taxonomyIDRe.MatchString(id) {
		errs[key] = "Choose one"
	}
	return id
}

func checkIfMatch(ifMatch string) error {
	if strings.TrimSpace(ifMatch) == "" {
		return domain.ValidationProblem(map[string]string{"version": "Reload the page and try again"})
	}
	return nil
}

func problemOf(errs map[string]string) error {
	if len(errs) == 0 {
		return nil
	}
	return domain.ValidationProblem(errs)
}

// ---------------------------------------------------------------- reads

func (s *staffTaxonomyImpl) Tree(ctx context.Context, c *domain.Caller) (any, error) {
	sc, canEdit, err := s.need(ctx, c, false)
	if err != nil {
		return nil, err
	}
	tree, err := s.catalog(ctx, sc, http.MethodGet, "/v1/taxonomy/tree", "", nil, nil)
	if err != nil {
		return nil, err
	}
	if tree["nodes"] == nil {
		tree["nodes"] = []any{}
	}
	tree["canEdit"] = canEdit
	tree["canDecide"] = sc.access.Allows(taxonomyModule, domain.LevelApprove)
	return tree, nil
}

func (s *staffTaxonomyImpl) ProductTypes(ctx context.Context, c *domain.Caller) (any, error) {
	sc, canEdit, err := s.need(ctx, c, false)
	if err != nil {
		return nil, err
	}
	list, err := s.catalogList(ctx, sc, "/v1/taxonomy/product-types", nil)
	if err != nil {
		return nil, err
	}
	return jobj{"items": list, "canEdit": canEdit}, nil
}

func (s *staffTaxonomyImpl) ProductType(ctx context.Context, c *domain.Caller, code string) (any, error) {
	if !productTypeRe.MatchString(code) {
		return nil, domain.NewProblem(http.StatusNotFound, domain.CodeNotFound, "Not found", "No such product type.")
	}
	sc, canEdit, err := s.need(ctx, c, false)
	if err != nil {
		return nil, err
	}
	out, err := s.catalog(ctx, sc, http.MethodGet, "/v1/taxonomy/product-types/"+url.PathEscape(code), "", nil, nil)
	if err != nil {
		return nil, err
	}
	out["canEdit"] = canEdit
	return out, nil
}

// Audit lists the changes newest first with the people named; subject narrows it to one node, product type or request.
func (s *staffTaxonomyImpl) Audit(ctx context.Context, c *domain.Caller, subject string, limit int) (any, error) {
	sc, _, err := s.need(ctx, c, false)
	if err != nil {
		return nil, err
	}
	if limit <= 0 || limit > 200 {
		limit = 50
	}
	q := url.Values{"limit": {strconv.Itoa(limit)}}
	if subject = strings.TrimSpace(subject); subject != "" {
		q.Set("subject", subject)
	}
	raw, err := s.catalogList(ctx, sc, "/v1/taxonomy/audit", q)
	if err != nil {
		return nil, err
	}
	entries := make([]jobj, 0, len(raw))
	ids := []string{}
	for _, it := range raw {
		if m, ok := it.(map[string]any); ok {
			entries = append(entries, m)
			ids = append(ids, jstr(m, "actorId"))
		}
	}
	names := sc.names.resolve(ctx, sc, nameUser, uniqueStrings(ids))
	items := make([]any, 0, len(entries))
	for _, e := range entries {
		actor := names[jstr(e, "actorId")]
		if actor == "" {
			actor = "Unknown user"
		}
		items = append(items, jobj{"id": jstr(e, "id"), "at": jstr(e, "at"), "actorName": actor, "action": jstr(e, "action"),
			"actionLabel": auditActionLabel(jstr(e, "action")), "subjectKind": jstr(e, "subjectKind"), "subjectLabel": jstr(e, "subjectLabel"),
			"reason": jstr(e, "reason"), "summary": auditSummary(e)})
	}
	return jobj{"items": items}, nil
}

func uniqueStrings(in []string) []string {
	seen := map[string]bool{}
	out := []string{}
	for _, s := range in {
		if s != "" && !seen[s] {
			seen[s] = true
			out = append(out, s)
		}
	}
	return out
}

var auditActions = map[string]string{
	"NODE_CREATED": "Created", "NODE_RENAMED": "Renamed", "NODE_MOVED": "Moved", "NODE_RETIRED": "Retired", "NODE_RESTORED": "Restored",
	"NODES_REORDERED": "Reordered", "TYPE_CREATED": "Product type created", "TYPE_UPDATED": "Product type changed",
	"REQUEST_APPROVED": "Request approved", "REQUEST_REJECTED": "Request rejected",
}

func auditActionLabel(action string) string {
	if l, ok := auditActions[action]; ok {
		return l
	}
	return domain.Humanize(action)
}

// auditSummary says in a line what a change did, from the details catalog recorded.
func auditSummary(e jobj) string {
	d, _ := e["details"].(map[string]any)
	label := jstr(e, "subjectLabel")
	switch jstr(e, "action") {
	case "NODE_CREATED":
		return "Created " + label
	case "NODE_RENAMED":
		return "Renamed “" + jstr(d, "from") + "” to “" + jstr(d, "to") + "”"
	case "NODE_MOVED":
		return "Moved from " + jstr(d, "fromPath") + " to " + jstr(d, "toPath")
	case "NODE_RETIRED":
		return "Retired " + label
	case "NODE_RESTORED":
		return "Restored " + label
	case "NODES_REORDERED":
		return "Reordered " + label + ": " + strings.Join(strs(d, "order"), ", ")
	case "TYPE_CREATED":
		return "Created product type " + label + " with " + jstr(d, "attributes") + " attributes"
	case "TYPE_UPDATED":
		parts := []string{}
		for _, k := range []string{"label", "identifierRule", "traits"} {
			if _, ok := d[k]; ok {
				parts = append(parts, domain.Humanize(k))
			}
		}
		if a, ok := d["attributes"].(map[string]any); ok {
			for _, k := range []string{"added", "removed", "changed"} {
				if l := strs(a, k); len(l) > 0 {
					parts = append(parts, k+" "+strings.Join(l, ", "))
				}
			}
			if _, ok := a["reordered"]; ok {
				parts = append(parts, "reordered attributes")
			}
		}
		return "Changed " + label + ": " + strings.Join(parts, "; ")
	case "REQUEST_APPROVED":
		return "Approved and created " + jstr(d, "createdPath")
	case "REQUEST_REJECTED":
		return "Rejected " + label
	default:
		return label
	}
}

// ---------------------------------------------------------------- node changes

func (s *staffTaxonomyImpl) CreateNode(ctx context.Context, c *domain.Caller, body map[string]any) (any, error) {
	errs := map[string]string{}
	level := strings.ToUpper(strings.TrimSpace(vs(body, "level")))
	if !slices.Contains(taxonomyLevels, level) {
		errs["level"] = "Department, category or product type"
	}
	b := jobj{"level": level, "name": taxNameOf(body, "name", errs), "reason": taxReasonOf(body, errs)}
	if level != "DEPARTMENT" && level != "" {
		b["parentId"] = uuidOf(body, "parentId", true, errs)
	}
	if code := strings.TrimSpace(vs(body, "code")); code != "" {
		b["code"] = code
	}
	if level == "PRODUCT_TYPE" {
		existing := strings.TrimSpace(vs(body, "productType"))
		nt, hasNew := body["newProductType"].(map[string]any)
		switch {
		case existing != "" && hasNew, existing == "" && !hasNew:
			errs["productType"] = "Choose an existing product type or define a new one"
		case existing != "":
			if !productTypeRe.MatchString(existing) {
				errs["productType"] = "Choose a product type"
			}
			b["productType"] = existing
		default:
			t, terrs := newTypeBody(nt)
			for k, v := range terrs {
				errs["newProductType."+k] = v
			}
			b["newProductType"] = t
		}
	}
	if err := problemOf(errs); err != nil {
		return nil, err
	}
	sc, _, err := s.need(ctx, c, true)
	if err != nil {
		return nil, err
	}
	return s.catalog(ctx, sc, http.MethodPost, "/v1/taxonomy/nodes", "", nil, b)
}

func (s *staffTaxonomyImpl) RenameNode(ctx context.Context, c *domain.Caller, id, ifMatch string, body map[string]any) (any, error) {
	path, err := nodePath(id)
	if err != nil {
		return nil, err
	}
	errs := map[string]string{}
	b := jobj{"name": taxNameOf(body, "name", errs), "reason": taxReasonOf(body, errs)}
	if err := problemOf(errs); err != nil {
		return nil, err
	}
	if err := checkIfMatch(ifMatch); err != nil {
		return nil, err
	}
	sc, _, err := s.need(ctx, c, true)
	if err != nil {
		return nil, err
	}
	return s.catalog(ctx, sc, http.MethodPatch, path, ifMatch, nil, b)
}

func (s *staffTaxonomyImpl) MoveNode(ctx context.Context, c *domain.Caller, id, ifMatch string, body map[string]any) (any, error) {
	path, err := nodePath(id, "/move")
	if err != nil {
		return nil, err
	}
	errs := map[string]string{}
	b := jobj{"parentId": uuidOf(body, "parentId", true, errs), "reason": taxReasonOf(body, errs)}
	if err := problemOf(errs); err != nil {
		return nil, err
	}
	if err := checkIfMatch(ifMatch); err != nil {
		return nil, err
	}
	sc, _, err := s.need(ctx, c, true)
	if err != nil {
		return nil, err
	}
	return s.catalog(ctx, sc, http.MethodPost, path, ifMatch, nil, b)
}

func (s *staffTaxonomyImpl) reasoned(ctx context.Context, c *domain.Caller, id, suffix, ifMatch string, body map[string]any) (any, error) {
	path, err := nodePath(id, suffix)
	if err != nil {
		return nil, err
	}
	errs := map[string]string{}
	b := jobj{"reason": taxReasonOf(body, errs)}
	if err := problemOf(errs); err != nil {
		return nil, err
	}
	if err := checkIfMatch(ifMatch); err != nil {
		return nil, err
	}
	sc, _, err := s.need(ctx, c, true)
	if err != nil {
		return nil, err
	}
	return s.catalog(ctx, sc, http.MethodPost, path, ifMatch, nil, b)
}

// RetireNode is refused by catalog (409) while products use the category or anything under it.
func (s *staffTaxonomyImpl) RetireNode(ctx context.Context, c *domain.Caller, id, ifMatch string, body map[string]any) (any, error) {
	return s.reasoned(ctx, c, id, "/retire", ifMatch, body)
}

func (s *staffTaxonomyImpl) RestoreNode(ctx context.Context, c *domain.Caller, id, ifMatch string, body map[string]any) (any, error) {
	return s.reasoned(ctx, c, id, "/restore", ifMatch, body)
}

func (s *staffTaxonomyImpl) Reorder(ctx context.Context, c *domain.Caller, body map[string]any) (any, error) {
	errs := map[string]string{}
	b := jobj{"reason": taxReasonOf(body, errs)}
	if parent := strings.TrimSpace(vs(body, "parentId")); parent != "" {
		b["parentId"] = uuidOf(body, "parentId", true, errs)
	}
	ids := vlist(body, "ids")
	if len(ids) < 2 || len(ids) > 500 {
		errs["ids"] = "Arrange at least two items"
	}
	for _, id := range ids {
		if !taxonomyIDRe.MatchString(id) {
			errs["ids"] = "Each item must be one of the list"
			break
		}
	}
	b["ids"] = ids
	if err := problemOf(errs); err != nil {
		return nil, err
	}
	sc, _, err := s.need(ctx, c, true)
	if err != nil {
		return nil, err
	}
	var out any
	err = sc.call(ctx, sc.up.Catalog, port.StaffCall{Method: http.MethodPost, Path: "/v1/taxonomy/reorder", Body: b}, &out)
	if err != nil {
		return nil, err
	}
	return jobj{"items": out}, nil
}

// ---------------------------------------------------------------- product types

// attributeBody checks one attribute definition's shape; catalog applies the full rules (unique keys, choices, units).
func attributeBody(a map[string]any) jobj {
	out := jobj{"key": strings.TrimSpace(vs(a, "key")), "label": tidy(a["label"]), "kind": strings.ToUpper(vs(a, "kind")),
		"required": vb(a, "required"), "section": strings.ToUpper(firstNonEmpty(vs(a, "section"), "DETAILS")),
		"options": vlist(a, "options"), "units": vlist(a, "units")}
	if h := strings.TrimSpace(vs(a, "help")); h != "" {
		out["help"] = h
	}
	if v, ok := a["applies"].(bool); ok {
		out["applies"] = v
	} else {
		out["applies"] = true
	}
	return out
}

func attributesOf(raw any, errs map[string]string) []jobj {
	list, ok := raw.([]any)
	if !ok {
		errs["attributes"] = "Send the list of attributes"
		return nil
	}
	if len(list) > 40 {
		errs["attributes"] = "At most 40 attributes"
		return nil
	}
	out := make([]jobj, 0, len(list))
	for i, it := range list {
		m, ok := it.(map[string]any)
		if !ok {
			errs["attributes["+strconv.Itoa(i)+"]"] = "Not an attribute"
			continue
		}
		a := attributeBody(m)
		at := "attributes[" + strconv.Itoa(i) + "]."
		if jstr(a, "key") == "" {
			errs[at+"key"] = "Give it a key"
		}
		if jstr(a, "label") == "" {
			errs[at+"label"] = "Give it a label"
		}
		if !slices.Contains(attributeKinds, jstr(a, "kind")) {
			errs[at+"kind"] = "Text, long text, choice or number"
		}
		if !slices.Contains(attributeSecs, jstr(a, "section")) {
			errs[at+"section"] = "Details, food or regulated"
		}
		out = append(out, a)
	}
	return out
}

func traitsBody(raw any) jobj {
	m, _ := raw.(map[string]any)
	return jobj{"food": vb(m, "food"), "perishable": vb(m, "perishable"), "regulated": vb(m, "regulated"), "preorder": vb(m, "preorder")}
}

// newTypeBody validates the definition of a new product type (also used inside a new product-type node).
func newTypeBody(body map[string]any) (jobj, map[string]string) {
	errs := map[string]string{}
	rule := strings.ToUpper(strings.TrimSpace(vs(body, "identifierRule")))
	if !slices.Contains(identifierRules, rule) {
		errs["identifierRule"] = "GTIN or ISBN"
	}
	label := tidy(body["label"])
	if label != "" {
		if n := utf8.RuneCountInString(label); n < 2 || n > 80 {
			errs["label"] = "2 to 80 characters"
		}
	}
	b := jobj{"identifierRule": rule, "traits": traitsBody(body["traits"])}
	if label != "" {
		b["label"] = label
	}
	if code := strings.TrimSpace(vs(body, "code")); code != "" {
		if !productTypeRe.MatchString(code) {
			errs["code"] = "Start with a letter; a-z, 0-9 and _"
		}
		b["code"] = code
	}
	if basedOn := strings.TrimSpace(vs(body, "basedOn")); basedOn != "" {
		b["basedOn"] = basedOn
	}
	if raw, ok := body["attributes"]; ok {
		b["attributes"] = attributesOf(raw, errs)
	} else {
		b["attributes"] = []jobj{}
	}
	return b, errs
}

func (s *staffTaxonomyImpl) CreateProductType(ctx context.Context, c *domain.Caller, body map[string]any) (any, error) {
	b, errs := newTypeBody(body)
	if tidy(body["label"]) == "" {
		errs["label"] = "Name the product type"
	}
	b["reason"] = taxReasonOf(body, errs)
	if err := problemOf(errs); err != nil {
		return nil, err
	}
	sc, _, err := s.need(ctx, c, true)
	if err != nil {
		return nil, err
	}
	return s.catalog(ctx, sc, http.MethodPost, "/v1/taxonomy/product-types", "", nil, b)
}

// UpdateProductType sends only what the form changed (label, identifierRule, traits, attributes); catalog refuses a
// save in which nothing differs.
func (s *staffTaxonomyImpl) UpdateProductType(ctx context.Context, c *domain.Caller, code, ifMatch string, body map[string]any) (any, error) {
	if !productTypeRe.MatchString(code) {
		return nil, domain.NewProblem(http.StatusNotFound, domain.CodeNotFound, "Not found", "No such product type.")
	}
	errs := map[string]string{}
	b := jobj{"reason": taxReasonOf(body, errs)}
	if _, ok := body["label"]; ok {
		label := tidy(body["label"])
		if n := utf8.RuneCountInString(label); n < 2 || n > 80 {
			errs["label"] = "2 to 80 characters"
		}
		b["label"] = label
	}
	if _, ok := body["identifierRule"]; ok {
		rule := strings.ToUpper(strings.TrimSpace(vs(body, "identifierRule")))
		if !slices.Contains(identifierRules, rule) {
			errs["identifierRule"] = "GTIN or ISBN"
		}
		b["identifierRule"] = rule
	}
	if t, ok := body["traits"]; ok {
		b["traits"] = traitsBody(t)
	}
	if raw, ok := body["attributes"]; ok {
		b["attributes"] = attributesOf(raw, errs)
	}
	if len(b) == 1 && len(errs) == 0 {
		errs["form"] = "Nothing has changed. Change it to save."
	}
	if err := problemOf(errs); err != nil {
		return nil, err
	}
	if err := checkIfMatch(ifMatch); err != nil {
		return nil, err
	}
	sc, _, err := s.need(ctx, c, true)
	if err != nil {
		return nil, err
	}
	out, err := s.catalog(ctx, sc, http.MethodPatch, "/v1/taxonomy/product-types/"+url.PathEscape(code), ifMatch, nil, b)
	if err != nil {
		return nil, err
	}
	out["canEdit"] = true
	return out, nil
}

// ---------------------------------------------------------------- category requests: what approving creates

// approvalBody turns the approve form's values into catalog's approval request. "Create a new product type" copies the
// attributes, identifier rule and traits of the schema staff pick to start from (the placeholder by default); the
// attribute editor refines it afterwards.
func approvalBody(ctx context.Context, sc staffCtx, v jobj) (jobj, error) {
	errs := map[string]string{}
	b := jobj{"parentCategoryId": uuidOf(v, "parentCategoryId", true, errs)}
	if n := tidy(v["categoryName"]); n != "" {
		if l := utf8.RuneCountInString(n); l < 2 || l > 80 {
			errs["categoryName"] = "2 to 80 characters"
		}
		b["categoryName"] = n
	}
	choice := strings.TrimSpace(vs(v, "productType"))
	switch {
	case choice == "":
		errs["productType"] = "Choose a product type"
	case choice != newProductTypeChoice:
		if !productTypeRe.MatchString(choice) {
			errs["productType"] = "Choose a product type"
		}
		b["productType"] = choice
	default:
		basedOn := firstNonEmpty(strings.TrimSpace(vs(v, "basedOn")), placeholderType)
		if !productTypeRe.MatchString(basedOn) {
			errs["basedOn"] = "Choose a product type to start from"
			break
		}
		var tpl jobj
		if err := sc.get(ctx, sc.up.Catalog, "/v1/taxonomy/product-types/"+url.PathEscape(basedOn), nil, &tpl); err != nil {
			return nil, err
		}
		attrs := []jobj{}
		for _, a := range objs(tpl, "attributes") {
			attrs = append(attrs, attributeBody(a))
		}
		b["newProductType"] = jobj{"identifierRule": firstNonEmpty(jstr(tpl, "identifierRule"), "GTIN"), "traits": traitsBody(tpl["traits"]),
			"attributes": attrs, "basedOn": basedOn}
	}
	if note := strings.TrimSpace(vs(v, "note")); note != "" {
		b["note"] = note
	}
	if err := problemOf(errs); err != nil {
		return nil, err
	}
	return b, nil
}

func (s *staffTaxonomyImpl) PreviewApproval(ctx context.Context, c *domain.Caller, requestID string, body map[string]any) (any, error) {
	if !taxonomyIDRe.MatchString(requestID) {
		return nil, domain.NewProblem(http.StatusNotFound, domain.CodeNotFound, "Not found", "No such category request.")
	}
	sc, _, err := s.need(ctx, c, false)
	if err != nil {
		return nil, err
	}
	b, err := approvalBody(ctx, sc, body)
	if err != nil {
		return nil, err
	}
	return s.catalog(ctx, sc, http.MethodPost, "/v1/category-requests/"+requestID+"/approval-preview", "", nil, b)
}

// planLines describe an approval plan for a sheet: each node that will be created or reused, the schema and the
// products that move.
func planLines(plan jobj) []domain.DetailField {
	out := []domain.DetailField{}
	for _, n := range objs(plan, "nodes") {
		kind := "Category"
		if jstr(n, "level") == "PRODUCT_TYPE" {
			kind = "Product type"
		}
		state := "already exists"
		if boolean(n, "created") {
			state = "will be created"
		}
		out = append(out, domain.DetailField{Label: kind, Value: jstr(n, "path") + " — " + state, Kind: "text"})
	}
	if t, ok := plan["productType"].(map[string]any); ok {
		state := "existing schema"
		if boolean(t, "created") {
			state = "new schema"
		}
		out = append(out, domain.DetailField{Label: "Details sellers fill in", Value: jstr(t, "label") + " (" + state + ", " + jstr(t, "attributeCount") + " attributes)", Kind: "text"})
	}
	if n := int(num(plan, "productsMoving")); n > 0 {
		out = append(out, domain.DetailField{Label: "Products waiting", Value: strconv.Itoa(n) + " move into listing review", Kind: "text"})
	}
	return out
}
