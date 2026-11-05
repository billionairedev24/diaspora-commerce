package app

import (
	"context"
	"net/url"
	"strconv"
	"strings"

	"github.com/billionairedev24/diaspora-commerce/go/services/gateway-bff/internal/domain"
)

func partnersResources() []*staffResource {
	return []*staffResource{organisationsResource(), connectAccountsResource()}
}

// ---------------------------------------------------------------- organisations (seller directory mirror)

func organisationsResource() *staffResource {
	loadMirror := func(ctx context.Context, sc staffCtx, q staffQuery) ([]jobj, error) {
		out := []jobj{}
		for page := 0; page < 10; page++ {
			var p jobj
			if err := sc.get(ctx, sc.up.Seller, "/v1/orgs/mirror", url.Values{
				"page": {strconv.Itoa(page)}, "size": {"200"},
			}, &p); err != nil {
				return out, err
			}
			items := objs(p, "items")
			out = append(out, items...)
			total := int(num(p, "total"))
			if len(items) < 200 || (total > 0 && len(out) >= total) {
				break
			}
		}
		if q.Q != "" {
			needle := strings.ToLower(strings.TrimSpace(q.Q))
			filtered := out[:0]
			for _, it := range out {
				id := strings.ToLower(jstr(it, "orgId"))
				name := strings.ToLower(firstNonEmpty(jstr(it, "displayName"), jstr(it, "legalName")))
				if strings.Contains(id, needle) || strings.Contains(name, needle) {
					filtered = append(filtered, it)
				}
			}
			out = filtered
		}
		if q.Status != "" {
			want := strings.ToUpper(strings.TrimSpace(q.Status))
			filtered := out[:0]
			for _, it := range out {
				if strings.EqualFold(jstr(it, "status"), want) {
					filtered = append(filtered, it)
				}
			}
			out = filtered
		}
		return out, nil
	}
	list := listAll(loadMirror)
	return &staffResource{
		key: "organisations", module: "orgs", title: "Organisations", statusKey: "status", exportable: true,
		searchPlaceholder: "Organisation name or id",
		columns: []domain.Column{
			col("displayName", "Name", "strong", 28),
			col("kind", "Kind", "text", 10), col("offering", "Offering", "text", 12), col("country", "Country", "text", 8),
			col("status", "Status", "status", 14),
		},
		facets: domain.StaffFacets("APPROVED", "SUSPENDED", "DRAFT", "PENDING_REVIEW"),
		list:   list,
		get: func(ctx context.Context, sc staffCtx, id string) (jobj, error) {
			var o jobj
			if err := sc.get(ctx, sc.up.Seller, "/v1/orgs/"+url.PathEscape(id)+"/mirror", nil, &o); err != nil {
				return nil, err
			}
			return o, nil
		},
		idOf: func(o jobj) string { return jstr(o, "orgId") },
		actions: []staffAction{
			// trust-ops runs the suspension (seller-service hold: listings hidden and payouts paused; buy-box
			// restriction) and writes the audit entry with who, what and why. sellers:enforce is the gate: Trust & Safety
			// and Operations hold it with VIEW on this module, so the module level asked for is VIEW. One person decides;
			// AfroTransact Retail is refused by trust-ops (retail-exempt).
			{key: "suspend", label: "Suspend…", tone: "danger", permission: "sellers:enforce", level: domain.LevelView,
				form: suspendOrgForm,
				when: inState("APPROVED"),
				run: func(ctx context.Context, sc staffCtx, o jobj, v jobj) error {
					return sc.post(ctx, sc.up.TrustOps, "/v1/seller-enforcement/"+url.PathEscape(jstr(o, "orgId"))+"/suspend",
						jobj{"reason": vs(v, "reason"), "note": vs(v, "note")}, nil)
				}},
			{key: "reinstate", label: "Reinstate…", tone: "primary", permission: "sellers:enforce", level: domain.LevelView,
				form: reinstateOrgForm,
				when: inState("SUSPENDED"),
				run: func(ctx context.Context, sc staffCtx, o jobj, v jobj) error {
					return sc.post(ctx, sc.up.TrustOps, "/v1/seller-enforcement/"+url.PathEscape(jstr(o, "orgId"))+"/reinstate",
						jobj{"note": vs(v, "note")}, nil)
				}},
		},
		rowActions: []string{"suspend", "reinstate"},
		cells: func(o jobj) map[string]any {
			return map[string]any{"displayName": cellValue(firstNonEmpty(jstr(o, "displayName"), jstr(o, "legalName")))}
		},
		titleOf: func(o jobj) string {
			return firstNonEmpty(jstr(o, "displayName"), jstr(o, "legalName"), "Unnamed organisation")
		},
		subtitleOf: func(o jobj) string { return domain.Humanize(jstr(o, "kind")) },
		links: func(o jobj) []domain.RecordLink {
			id := jstr(o, "orgId")
			if id == "" {
				return nil
			}
			return []domain.RecordLink{
				{Label: "Stripe compliance", Href: "/organisations?tab=connect-accounts"},
			}
		},
	}
}

// suspendReasonCodes are trust-ops' suspension reasons.
var suspendReasonCodes = []domain.FieldOption{
	{Value: "POOR_PERFORMANCE", Label: "Poor performance"}, {Value: "PROHIBITED_ITEMS", Label: "Prohibited items"},
	{Value: "COUNTERFEIT", Label: "Counterfeit goods"}, {Value: "FRAUD_RISK", Label: "Fraud risk"},
	{Value: "POLICY_VIOLATION", Label: "Policy violation"}, {Value: "COMPLIANCE", Label: "Compliance"}, {Value: "OTHER", Label: "Other"},
}

// minEnforceReason is the fewest characters of the reason staff give for a suspension or reinstatement (the audit entry).
const minEnforceReason = 10

func suspendOrgForm(context.Context, staffCtx, jobj) *domain.Form {
	return &domain.Form{Title: "Suspend this organisation", SubmitLabel: "Suspend",
		Intro: "Its listings are hidden from the marketplace and its payouts are paused straight away, and it is restricted in the buy box. " +
			"Open orders are not cancelled. The reason is kept in the audit log. Reinstating restores the listings and payouts.",
		Fields: []domain.FormField{
			withOptions(ff("reason", "Reason", "select", true), suspendReasonCodes),
			withMax(withMin(ff("note", "What happened (at least 10 characters)", "textarea", true), minEnforceReason), 1000),
		}}
}

func reinstateOrgForm(context.Context, staffCtx, jobj) *domain.Form {
	return &domain.Form{Title: "Reinstate this organisation", SubmitLabel: "Reinstate",
		Intro: "Its listings are shown again and its payouts resume. A payout hold from Stripe compliance, if there is one, stays until Stripe clears it. " +
			"The reason is kept in the audit log.",
		Fields: []domain.FormField{
			withMax(withMin(ff("note", "Why it is safe to reinstate (at least 10 characters)", "textarea", true), minEnforceReason), 1000),
		}}
}

// ---------------------------------------------------------------- Stripe Connect compliance (staff console)

func connectAccountsResource() *staffResource {
	load := func(ctx context.Context, sc staffCtx, q staffQuery) ([]jobj, error) {
		v := url.Values{}
		if q.Status != "" {
			v.Set("status", q.Status)
		}
		var raw any
		if err := sc.get(ctx, sc.up.Seller, "/v1/compliance/accounts", v, &raw); err != nil {
			return nil, err
		}
		out := jpage(raw).items
		if q.Q != "" {
			needle := strings.ToLower(strings.TrimSpace(q.Q))
			filtered := out[:0]
			for _, it := range out {
				if strings.Contains(strings.ToLower(jstr(it, "orgId")), needle) ||
					strings.Contains(strings.ToLower(jstr(it, "orgName")), needle) ||
					strings.Contains(strings.ToLower(jstr(it, "accountId")), needle) {
					filtered = append(filtered, it)
				}
			}
			out = filtered
		}
		return out, nil
	}
	list := listAll(load)
	return &staffResource{
		key: "connect-accounts", module: "orgs", title: "Stripe Connect", statusKey: "status", exportable: true,
		searchPlaceholder: "Organisation, name or acct_…",
		columns: []domain.Column{
			col("orgName", "Organisation", "strong", 26), col("accountId", "Stripe account", "mono", 20),
			col("status", "Status", "status", 16),
			col("payoutsPaused", "Payouts paused", "text", 12), col("listingsHidden", "Listings hidden", "text", 12),
		},
		facets: domain.StaffFacets("VERIFIED", "ACTION_NEEDED", "RESTRICTED", "REJECTED", "PENDING"),
		list:   list,
		get:    getByPath("/v1/compliance/accounts/", upSeller),
		idOf:   func(o jobj) string { return jstr(o, "accountId") },
		names:  []nameRef{orgRef("orgId")},
		search: []string{"orgId"},
		cells: func(o jobj) map[string]any {
			return map[string]any{
				"payoutsPaused":  cellValue(displayBool(boolean(o, "payoutsPaused"))),
				"listingsHidden": cellValue(displayBool(boolean(o, "listingsHidden"))),
			}
		},
		titleOf:    func(o jobj) string { return firstNonEmpty(jstr(o, "orgName"), "Unknown organisation") },
		subtitleOf: func(o jobj) string { return "Stripe account " + jstr(o, "accountId") },
		actions: []staffAction{
			{key: "remind", label: "Send reminder", tone: "neutral", permission: "compliance:remind",
				confirm: "Send a compliance reminder now (email, SMS and in-app)?",
				run: func(ctx context.Context, sc staffCtx, o jobj, _ jobj) error {
					return sc.post(ctx, sc.up.Seller, "/v1/compliance/accounts/"+url.PathEscape(jstr(o, "accountId"))+"/reminders", jobj{}, nil)
				}},
			{key: "sync-payments", label: "Sync all accounts with payments", tone: "neutral", permission: "compliance:remind",
				confirm: "Register every Stripe Connect account with payments now? This is the scheduled job (every minute locally, nightly elsewhere), run on demand; payments transfers a seller's earnings only to accounts it knows.",
				run: func(ctx context.Context, sc staffCtx, _ jobj, _ jobj) error {
					return sc.post(ctx, sc.up.Seller, "/v1/compliance/payments-sync", jobj{}, nil)
				}},
			{key: "pause-payouts", label: "Pause payouts", tone: "danger", permission: "compliance:hold",
				form: noteForm("Pause payouts on this connected account", "Pause", false),
				run: func(ctx context.Context, sc staffCtx, o jobj, v jobj) error {
					return sc.post(ctx, sc.up.Seller, "/v1/compliance/accounts/"+url.PathEscape(jstr(o, "accountId"))+"/payouts/pause",
						jobj{"note": vs(v, "note")}, nil)
				}},
			{key: "resume-payouts", label: "Release payout hold", tone: "primary", permission: "compliance:hold",
				form: noteForm("Release the manual payout hold", "Release", false),
				when: func(_ staffCtx, o jobj) string {
					if !boolean(o, "payoutsPaused") {
						return "Payouts are not paused on this account."
					}
					return ""
				},
				run: func(ctx context.Context, sc staffCtx, o jobj, v jobj) error {
					return sc.post(ctx, sc.up.Seller, "/v1/compliance/accounts/"+url.PathEscape(jstr(o, "accountId"))+"/payouts/resume",
						jobj{"note": vs(v, "note")}, nil)
				}},
		},
	}
}

func displayBool(on bool) string {
	if on {
		return "Yes"
	}
	return "No"
}
