package app

import (
	"fmt"
	"regexp"
	"strconv"
	"strings"
	"unicode"

	"github.com/billionairedev24/diaspora-commerce/go/services/gateway-bff/internal/domain"
)

// Plain words for the tax screens (category-tax-design.md): gate and record statuses, outcomes, fact owners, and a
// decision row's condition read as a sentence. Staff never see an expression tree or an enum.

type taxLabel struct{ label, explain string }

// taxGates are the profile status gates, in the design's precedence order.
var taxGates = map[string]taxLabel{
	"ACTIVE":         {"Ready", "Products in this category can go live once tax review approves them."},
	"NEEDS_APPROVAL": {"Needs tax sign-off", "The Texas tax professional must approve this category's rules before any product in it can go live."},
	"BLOCKED_BUNDLE": {"Blocked until bundles are itemised", "Mixed bundles cannot be sold until each item is its own line with its own price and category."},
	"REFINE_CODE":    {"Needs a specific provider code", "The provider code is generic. Each product needs a verified specific code from tax review before it can go live."},
	"CONFLICT_OPEN":  {"Waiting on a decision", "Two codes conflict. An open decision must be made and the rules updated before this category can go live."},
}

var taxGateOrder = []string{"ACTIVE", "NEEDS_APPROVAL", "REFINE_CODE", "BLOCKED_BUNDLE", "CONFLICT_OPEN"}

var taxRecordStatus = map[string]string{
	"DRAFT":          "Draft",
	"MISSING_FACTS":  "Seller still has to answer questions",
	"PENDING_REVIEW": "Waiting for tax review",
	"APPROVED":       "Approved",
	"REJECTED":       "Sent back to the seller",
	"STALE":          "Changed since approval, needs a new review",
}

var taxResolveStatus = map[string]string{
	"RESOLVED":       "Tax is decided",
	"MISSING_FACTS":  "Answers are missing",
	"BLOCKED":        "Cannot be sold as listed",
	"NEEDS_APPROVAL": "Category needs tax sign-off",
	"REFINE_CODE":    "Needs a specific provider code",
	"CONFLICT_OPEN":  "Category waits on a decision",
}

var taxOutcomes = map[string]string{"EXEMPT": "Exempt (no tax)", "TAXABLE": "Taxable", "BLOCKED": "Blocked"}

var taxOwners = map[string]string{
	"VARIANT": "Product (the seller answers)",
	"OFFER":   "Listing (the seller answers)",
	"SELLER":  "Seller profile",
	"STAFF":   "Tax team only",
	"CONTEXT": "Worked out by the system",
}

var taxProviders = map[string]string{"STRIPE": "Stripe Tax", "AVALARA": "Avalara"}

var taxAuditActions = map[string]string{
	"FACTS_CHANGED": "Facts changed", "SUBMITTED": "Submitted for review", "APPROVED": "Approved", "REJECTED": "Sent back",
	"RECLASSIFIED": "Moved to another category", "MARKED_STALE": "Marked out of date", "EVIDENCE_ADDED": "Evidence added",
	"INFORMATION_REQUESTED": "Asked the seller for more", "AUDIT_SAMPLED": "Put in the audit sample", "AUDIT_CONFIRMED": "Audit sample confirmed",
}

var taxDepartments = map[string]string{
	"groceries-food": "Groceries & Food", "beverages": "Beverages", "beauty-personal-care": "Beauty & Personal Care",
	"fashion-accessories": "Fashion & Accessories", "home-household": "Home & Household", "electronics": "Electronics",
	"books-media": "Books & Media", "health-wellness": "Health & Wellness", "baby-kids": "Baby & Kids", "pets": "Pets",
	"digital-products": "Digital Products", "services": "Services", "other-specialty": "Other & Specialty",
}

func taxDepartmentName(code string) string {
	if n, ok := taxDepartments[code]; ok {
		return n
	}
	return domain.Humanize(code)
}

func taxGateLabel(status string) string {
	if g, ok := taxGates[status]; ok {
		return g.label
	}
	return domain.Humanize(status)
}

func taxOutcomeLabel(o string) string {
	if l, ok := taxOutcomes[o]; ok {
		return l
	}
	return domain.Humanize(o)
}

func taxProviderLabel(p string) string {
	if l, ok := taxProviders[p]; ok {
		return l
	}
	return domain.Humanize(p)
}

// taxFact is what the renderer needs of a fact definition.
type taxFact struct {
	id, label, kind, unit string
	choices               map[string]string
}

func taxFactsOf(profile jobj) map[string]taxFact {
	out := map[string]taxFact{}
	for _, f := range objs(profile, "facts") {
		tf := taxFact{id: jstr(f, "id"), label: jstr(f, "label"), kind: jstr(f, "kind"), unit: jstr(f, "unit"), choices: map[string]string{}}
		for _, c := range objs(f, "choices") {
			tf.choices[jstr(c, "value")] = jstr(c, "label")
		}
		out[tf.id] = tf
	}
	return out
}

var parenthetical = regexp.MustCompile(`\s*\([^)]*\)`)

// phrase is a fact's label inside a sentence: "Net weight (oz)" reads "net weight".
func (f taxFact) phrase() string {
	label := strings.TrimSpace(parenthetical.ReplaceAllString(f.label, ""))
	if label == "" {
		label = domain.Humanize(f.id)
	}
	r := []rune(label)
	if len(r) > 1 && unicode.IsUpper(r[1]) {
		return label // an acronym or a proper name
	}
	r[0] = unicode.ToLower(r[0])
	return string(r)
}

func (f taxFact) subject() string {
	if f.kind == "BOOLEAN" || f.kind == "CHOICE" {
		return `"` + strings.TrimSpace(parenthetical.ReplaceAllString(f.label, "")) + `"`
	}
	return f.phrase()
}

func (f taxFact) valueText(v any) string {
	switch x := v.(type) {
	case bool:
		return taxYesNo(x)
	case float64:
		s := strconv.FormatFloat(x, 'f', -1, 64)
		return strings.TrimSpace(s + unitSuffix(f.unit))
	case string:
		if l, ok := f.choices[x]; ok {
			return l
		}
		return x
	}
	return fmt.Sprint(v)
}

func unitSuffix(unit string) string {
	switch unit {
	case "":
		return ""
	case "%":
		return "%"
	default:
		return " " + unit
	}
}

func taxYesNo(b bool) string {
	if b {
		return "yes"
	}
	return "no"
}

// describeCondition reads a decision-table expression as words, e.g. "net weight is at least 2.5 oz and servings per
// package is more than 1". A nil expression means the row applies whenever the facts are complete.
func describeCondition(expr any, facts map[string]taxFact) string {
	m, ok := expr.(map[string]any)
	if !ok || len(m) == 0 {
		return ""
	}
	return describeNode(m, facts, "")
}

func describeNode(m map[string]any, facts map[string]taxFact, parent string) string {
	switch {
	case m["all"] != nil:
		return joinNodes(m["all"], facts, "and", parent)
	case m["any"] != nil:
		return joinNodes(m["any"], facts, "or", parent)
	case m["not"] != nil:
		inner, _ := m["not"].(map[string]any)
		if inner["op"] != nil {
			return describeLeaf(inner, facts, true)
		}
		return "not (" + describeNode(inner, facts, "") + ")"
	default:
		return describeLeaf(m, facts, false)
	}
}

func joinNodes(raw any, facts map[string]taxFact, word, parent string) string {
	list, _ := raw.([]any)
	parts := make([]string, 0, len(list))
	for _, it := range list {
		child, ok := it.(map[string]any)
		if !ok {
			continue
		}
		parts = append(parts, describeNode(child, facts, word))
	}
	switch len(parts) {
	case 0:
		return ""
	case 1:
		return parts[0]
	}
	text := strings.Join(parts, " "+word+" ")
	if parent != "" && parent != word {
		return "(" + text + ")"
	}
	return text
}

func describeLeaf(m map[string]any, facts map[string]taxFact, negate bool) string {
	id, _ := m["fact"].(string)
	op, _ := m["op"].(string)
	f, ok := facts[id]
	if !ok {
		f = taxFact{id: id, label: domain.Humanize(id), choices: map[string]string{}}
	}
	if negate {
		if inv, ok := map[string]string{"isTrue": "isFalse", "isFalse": "isTrue", "eq": "ne", "in": "notin", "gt": "lte", "gte": "lt", "lt": "gte", "lte": "gt"}[op]; ok {
			op = inv
		}
	}
	val := f.valueText(m["value"])
	switch op {
	case "isTrue":
		return f.subject() + " is yes"
	case "isFalse":
		return f.subject() + " is no"
	case "eq":
		return f.subject() + " is " + val
	case "ne":
		return f.subject() + " is not " + val
	case "in", "notin":
		list, _ := m["values"].([]any)
		names := make([]string, 0, len(list))
		for _, v := range list {
			names = append(names, f.valueText(v))
		}
		verb := " is one of "
		if op == "notin" {
			verb = " is none of "
		}
		return f.subject() + verb + strings.Join(names, ", ")
	case "gt":
		return f.subject() + " is more than " + val
	case "gte":
		return f.subject() + " is at least " + val
	case "lt":
		return f.subject() + " is less than " + val
	case "lte":
		return f.subject() + " is at most " + val
	}
	return f.subject() + " " + op + " " + val
}

// describeRow is the row as one sentence: "If net weight is at least 2.5 oz, then exempt."
func describeRow(row jobj, facts map[string]taxFact) string {
	cond := describeCondition(row["when"], facts)
	then := strings.ToLower(taxOutcomeLabel(jstr(row, "outcome")))
	if i := strings.Index(then, " ("); i > 0 {
		then = then[:i]
	}
	if cond == "" {
		return "Always, then " + then
	}
	return "If " + cond + ", then " + then
}

// requiredText says when a fact is needed.
func requiredText(req jobj, facts map[string]taxFact) string {
	if !boolean(req, "required") {
		return "Optional"
	}
	if cond := describeCondition(req["requiredWhen"], facts); cond != "" {
		return "Needed when " + cond
	}
	return "Always needed"
}
