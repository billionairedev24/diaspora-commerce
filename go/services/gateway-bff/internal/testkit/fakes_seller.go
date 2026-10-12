package testkit

import (
	"encoding/json"
	"fmt"
	"net/http"
	"slices"
	"strings"
	"sync"
	"time"
)

// Organisation ids of the fake member.
const (
	SellerOrgID   = "11111111-1111-1111-1111-111111111111"
	ProviderOrgID = "22222222-2222-2222-2222-222222222222"
	PendingOrgID  = "33333333-3333-3333-3333-333333333333"
)

// MissingAssetID is an upload media does not know (POST /v1/assets/{id}/complete answers 404).
const MissingAssetID = "asset-missing"

// MemberOrg is an organisation the fake identity lists in GET /v1/me.
type MemberOrg struct {
	ID, Kind, Name, Status, Role string
}

// DefaultMemberOrgs returns an approved seller (owner), an approved provider (owner) and a seller still in review.
func DefaultMemberOrgs() []MemberOrg {
	return []MemberOrg{
		{ID: SellerOrgID, Kind: "SELLER", Name: "Ade's Kitchen", Status: "APPROVED", Role: "seller.owner"},
		{ID: ProviderOrgID, Kind: "PROVIDER", Name: "Ama's Braiding Studio", Status: "APPROVED", Role: "provider.owner"},
		{ID: PendingOrgID, Kind: "SELLER", Name: "Bonwire Weavers", Status: "PENDING", Role: "seller.owner"},
	}
}

func (u *Upstreams) memberOrgsJSON() []map[string]any {
	out := []map[string]any{}
	for _, o := range u.MemberOrgs {
		out = append(out, map[string]any{"id": o.ID, "kind": o.Kind, "name": o.Name, "status": o.Status, "role": o.Role})
	}
	return out
}

func (u *Upstreams) orgByID(id string) (MemberOrg, bool) {
	for _, o := range u.MemberOrgs {
		if o.ID == id {
			return o, true
		}
	}
	return MemberOrg{}, false
}

type sellerState struct {
	plan          string
	planFeeMode   string
	defaultPM     string
	listingStatus map[string]string
	shipStatus    string
	tracking      string
	storefront    map[string]any
}

func newSellerState() *sellerState {
	return &sellerState{plan: "seller_growth", planFeeMode: "EARNINGS_FIRST", defaultPM: "aaaaaaaa-0000-0000-0000-000000000001", listingStatus: map[string]string{"prod-1": "LIVE"},
		shipStatus: "placed", storefront: map[string]any{"orgId": SellerOrgID, "kind": "SELLER", "slug": "ade-s-kitchen", "name": "Ade's Kitchen",
			"tagline": "Home-style Nigerian pantry", "theme": map[string]any{"primary": "#0A8264", "accent": "#FFD400"}, "status": "DRAFT",
			"social": []any{map[string]any{"network": "instagram", "url": "https://instagram.com/adeskitchen", "status": "APPROVED"}}, "version": 3}}
}

func bearerSub(w http.ResponseWriter, r *http.Request) (string, bool) {
	sub := subjectOf(r)
	if sub == "" {
		problem(w, 401, "auth.unauthenticated", "Bearer token required", nil)
		return "", false
	}
	return sub, true
}

func (u *Upstreams) sellerRoutes(mux *http.ServeMux) {
	u.identitySellerRoutes(mux)
	u.sellerServiceRoutes(mux)
	u.catalogSellerRoutes(mux)
	u.moneyRoutes(mux)
	if u.opts.SellerPending {
		u.ordersRoutes(mux)
		u.bookingsRoutes(mux)
	}
}

func (u *Upstreams) identitySellerRoutes(mux *http.ServeMux) {
	u.teamRoutes(mux)
	u.phoneRoutes(mux)
	mux.HandleFunc("PUT /v1/me/active-org", func(w http.ResponseWriter, r *http.Request) {
		sub, ok := bearerSub(w, r)
		if !ok {
			return
		}
		var b struct {
			OrgID string `json:"orgId"`
		}
		_ = json.NewDecoder(r.Body).Decode(&b)
		u.mu.Lock()
		defer u.mu.Unlock()
		if _, ok := u.orgByID(b.OrgID); !ok {
			problem(w, 403, "identity.not-a-member", "Not a member", nil)
			return
		}
		u.ActiveOrgID = b.OrgID
		writeJSON(w, 200, map[string]any{"id": sub, "firstName": "Adaeze", "lastName": "Nwosu", "email": "adaeze@example.com", "orgId": b.OrgID, "orgs": u.memberOrgsJSON()})
	})
	mux.HandleFunc("GET /v1/admin/module-access", func(w http.ResponseWriter, _ *http.Request) {
		u.mu.Lock()
		denied := u.AccessDeniedMatrix
		u.mu.Unlock()
		if denied {
			problem(w, 403, "identity.forbidden", "access:read required", nil)
			return
		}
		writeJSON(w, 200, map[string]any{"portal": "SELLER", "modules": []map[string]any{
			{"key": "sHome", "label": "Home", "levels": map[string]string{"seller.owner": "full", "seller.fulfilment": "view"}},
			{"key": "sOrders", "label": "Orders", "levels": map[string]string{"seller.owner": "full", "seller.fulfilment": "full"}},
		}})
	})
}

func (u *Upstreams) sellerServiceRoutes(mux *http.ServeMux) {
	mux.HandleFunc("GET /v1/orgs/{id}", func(w http.ResponseWriter, r *http.Request) {
		u.mu.Lock()
		o, ok := u.orgByID(r.PathValue("id"))
		u.mu.Unlock()
		if !ok {
			problem(w, 404, "seller.org-not-found", "No such organisation", nil)
			return
		}
		status := o.Status
		if status == "PENDING" {
			status = "INFO_REQUESTED"
		}
		writeJSON(w, 200, map[string]any{"id": o.ID, "kind": o.Kind, "displayName": o.Name, "legalName": o.Name + " LLC", "country": "US", "status": status,
			"members": []map[string]any{{"userId": "user-1", "roleKey": o.Role, "createdAt": "2026-04-10T12:00:00Z"},
				{"userId": "user-2-abcdef", "roleKey": strings.Split(o.Role, ".")[0] + ".finance", "createdAt": "2026-05-01T12:00:00Z"}}})
	})
	mux.HandleFunc("POST /v1/me/roles/{role}", func(w http.ResponseWriter, r *http.Request) {
		if _, ok := bearerSub(w, r); !ok {
			return
		}
		u.mu.Lock()
		u.ApplicantRoles = append(u.ApplicantRoles, r.PathValue("role"))
		u.mu.Unlock()
		writeJSON(w, 200, map[string]any{"id": subjectOf(r)})
	})
	mux.HandleFunc("POST /v1/applications/me", func(w http.ResponseWriter, r *http.Request) {
		if _, ok := bearerSub(w, r); !ok {
			return
		}
		var b map[string]any
		_ = json.NewDecoder(r.Body).Decode(&b)
		u.mu.Lock()
		defer u.mu.Unlock()
		if u.Application != nil {
			if u.Application["kind"] != b["kind"] {
				problem(w, 409, "seller.application-exists", "You already have a "+strings.ToLower(fmt.Sprint(u.Application["kind"]))+" application; continue that one", nil)
				return
			}
			writeJSON(w, 200, u.Application)
			return
		}
		pre, _ := b["prefill"].(map[string]any)
		u.LastPrefill = pre
		prefilled := []string{"business.contactEmail", "businessDetails.legalFirstName", "businessDetails.legalLastName", "payouts.accountHolderName"}
		business := map[string]any{"contactEmail": "adaeze@example.com"}
		details := map[string]any{"legalFirstName": "Adaeze", "legalLastName": "Nwosu"}
		if pre["phone"] != nil {
			business["phone"], prefilled = pre["phone"], append(prefilled, "business.phone")
		}
		if pre["storeName"] != nil {
			business["storeName"], prefilled = pre["storeName"], append(prefilled, "business.storeName")
		}
		if pre["address"] != nil {
			details["address"], prefilled = pre["address"], append(prefilled, "businessDetails.address")
		}
		u.Application = map[string]any{"id": "app-new", "reference": "APP-40001", "orgId": "org_new", "kind": b["kind"], "status": "DRAFT",
			"currentStep": "business", "steps": []string{"business", "business-details", "owners", "stripe-verification", "payouts", "plan", "catalog-compliance"},
			"completedSteps": []string{}, "requestedItems": []string{}, "prefilledFields": prefilled, "business": business, "businessDetails": details}
		writeJSON(w, 201, u.Application)
	})
	mux.HandleFunc("PUT /v1/applications/me/steps/{step}", func(w http.ResponseWriter, r *http.Request) {
		if _, ok := bearerSub(w, r); !ok {
			return
		}
		var b map[string]any
		_ = json.NewDecoder(r.Body).Decode(&b)
		u.mu.Lock()
		defer u.mu.Unlock()
		if u.Application == nil {
			problem(w, 404, "seller.no-application", "You haven't started an application yet", nil)
			return
		}
		step := r.PathValue("step")
		if u.StepBodies == nil {
			u.StepBodies = map[string]map[string]any{}
		}
		u.StepBodies[step] = b
		switch step {
		case "business":
			if s, _ := b["storeName"].(string); len(s) == 1 {
				problem(w, 422, "seller.invalid-application", "Some fields are invalid", map[string]any{"fieldErrors": map[string]string{"storeName": "2 to 80 characters"}})
				return
			}
			u.Application["business"] = b
			if b["entityType"] == "sole_proprietor" {
				u.Application["steps"] = []string{"business", "business-details", "stripe-verification", "payouts", "plan", "catalog-compliance"}
			}
		case "business-details":
			d := map[string]any{}
			for k, v := range b {
				if k != "taxId" {
					d[k] = v
				}
			}
			if t, _ := b["taxId"].(string); len(t) >= 4 {
				d["taxIdType"], d["taxIdMasked"] = "SSN", "•••-••-"+t[len(t)-4:]
			}
			u.Application["businessDetails"] = d
		case "owners":
			total := 0.0
			list, _ := b["owners"].([]any)
			for _, o := range list {
				if m, ok := o.(map[string]any); ok {
					pct, _ := m["ownershipPercent"].(float64)
					total += pct
				}
			}
			if total > 100 {
				problem(w, 422, "seller.invalid-application", "Some fields are invalid", map[string]any{"fieldErrors": map[string]string{"owners": "Ownership can't exceed 100%"}})
				return
			}
			u.Application["owners"] = b["owners"]
		case "stripe-verification", "payouts":
			// Stripe-collected accounts: Stripe's embedded components collect these, never a step body.
			problem(w, 422, "seller.step-managed-by-stripe", "Stripe collects this in its embedded components", nil)
			return
		case "plan":
			if c, _ := b["planCode"].(string); c == "" {
				problem(w, 422, "seller.invalid-application", "Some fields are invalid", map[string]any{"fieldErrors": map[string]string{"plan.planCode": "Choose a plan"}})
				return
			}
			b["planName"] = "Seller Growth"
			b["firstCharge"] = map[string]any{"chargeOn": "2026-11-09", "amountCents": 3900, "sentence": "Free for 30 days. First charge of $39.00 on Nov 9, 2026."}
			u.Application["plan"] = b
			// S298: the Plan step offers paying the plan from sales.
			u.Application["planFeeCollectionOption"] = map[string]any{"available": true, "platformDefault": "EARNINGS_FIRST"}
		case "catalog-compliance":
			if fp, ok := b["foodPermit"].(map[string]any); ok {
				if t, _ := fp["permitType"].(string); !slices.Contains([]string{"FOOD_ESTABLISHMENT", "COTTAGE_FOOD", "FDA_FACILITY", "FOOD_HANDLER"}, t) {
					problem(w, 422, "seller.invalid-application", "Some fields are invalid",
						map[string]any{"fieldErrors": map[string]string{"compliance.foodPermit.permitType": "Choose the permit type"}})
					return
				}
			}
			u.Application["compliance"] = b
		}
		done, _ := u.Application["completedSteps"].([]string)
		if !slices.Contains(done, step) {
			u.Application["completedSteps"] = append(done, step)
		}
		writeJSON(w, 200, u.Application)
	})
	mux.HandleFunc("POST /v1/applications/me/stripe/account-session", func(w http.ResponseWriter, r *http.Request) {
		if _, ok := bearerSub(w, r); !ok {
			return
		}
		u.mu.Lock()
		defer u.mu.Unlock()
		if u.Application == nil {
			problem(w, 404, "seller.no-application", "You haven't started an application yet", nil)
			return
		}
		u.Application["stripe"] = map[string]any{"accountId": "acct_local_1", "detailsSubmitted": false, "identityStatus": "unverified", "taxIdStatus": "pending", "currentlyDue": []string{}}
		//nolint:gosec // a test fixture, not a credential
		writeJSON(w, 200, map[string]any{"accountId": "acct_local_1", "clientSecret": "accs_secret_1", "expiresAt": "2026-10-10T13:00:00Z", "localTest": u.StripeLocalTest})
	})
	mux.HandleFunc("POST /v1/applications/me/stripe/refresh", func(w http.ResponseWriter, r *http.Request) {
		if _, ok := bearerSub(w, r); !ok {
			return
		}
		u.mu.Lock()
		defer u.mu.Unlock()
		if u.Application == nil {
			problem(w, 404, "seller.no-application", "You haven't started an application yet", nil)
			return
		}
		u.Application["stripe"] = map[string]any{"accountId": "acct_local_1", "detailsSubmitted": true, "chargesEnabled": true, "payoutsEnabled": true,
			"identityStatus": "verified", "taxIdStatus": "verified", "currentlyDue": []string{}}
		// The payout bank Stripe's embedded onboarding collected, mirrored read-only.
		u.Application["payouts"] = map[string]any{"bankName": "STRIPE TEST BANK", "accountLast4": "6789", "externalAccountLinked": true, "bankStatus": "new"}
		done, _ := u.Application["completedSteps"].([]string)
		u.Application["completedSteps"] = append(done, "stripe-verification", "payouts")
		writeJSON(w, 200, u.Application)
	})
	mux.HandleFunc("POST /v1/applications/me/submit", func(w http.ResponseWriter, r *http.Request) {
		if _, ok := bearerSub(w, r); !ok {
			return
		}
		u.mu.Lock()
		defer u.mu.Unlock()
		if u.Application == nil {
			problem(w, 404, "seller.no-application", "You haven't started an application yet", nil)
			return
		}
		if u.Application["compliance"] == nil {
			problem(w, 422, "seller.application-incomplete", "Some steps are incomplete or invalid; fix the highlighted fields and submit again",
				map[string]any{"fieldErrors": map[string]string{"compliance.agreementsAccepted": "Required"}})
			return
		}
		u.Application["status"], u.Application["submittedAt"] = "PENDING_REVIEW", "2026-10-10T12:00:00Z"
		u.Application["timeline"] = []map[string]any{{"action": "SUBMITTED", "toStatus": "PENDING_REVIEW", "occurredAt": "2026-10-10T12:00:00Z"}}
		writeJSON(w, 200, u.Application)
	})
	mux.HandleFunc("GET /v1/applications/me", func(w http.ResponseWriter, r *http.Request) {
		if _, ok := bearerSub(w, r); !ok {
			return
		}
		u.mu.Lock()
		app, none := u.Application, u.NoApplication
		u.mu.Unlock()
		if app != nil {
			writeJSON(w, 200, app)
			return
		}
		if none {
			problem(w, 404, "seller.no-application", "You haven't started an application yet", nil)
			return
		}
		writeJSON(w, 200, map[string]any{"id": "app-1", "reference": "APP-30131", "orgId": PendingOrgID, "kind": "SELLER", "status": "INFO_REQUESTED",
			"currentStep": "COMPLIANCE", "steps": []string{"BUSINESS", "OWNERS", "STRIPE", "PAYOUTS", "PLAN", "COMPLIANCE"},
			"completedSteps": []string{"BUSINESS", "OWNERS", "STRIPE", "PAYOUTS", "PLAN"}, "requestedItems": []string{"Harris County food permit"}})
	})
	mux.HandleFunc("GET /v1/orgs/{id}/account-health", func(w http.ResponseWriter, r *http.Request) {
		if u.AccountHealthForbidden {
			problem(w, 403, "seller.org-role-required", "Only the organisation's finance or owner can do this", nil)
			return
		}
		if r.PathValue("id") == ProviderOrgID {
			writeJSON(w, 200, map[string]any{"status": "COMPLIANT", "chargesEnabled": true, "payoutsEnabled": true, "openItems": 0, "requirements": []any{}})
			return
		}
		writeJSON(w, 200, map[string]any{"accountId": "acct_1", "orgId": r.PathValue("id"), "status": "ACTION_NEEDED", "chargesEnabled": true,
			"payoutsEnabled": true, "payoutsPaused": false, "listingsHidden": false, "openItems": 2, "currentDeadline": "2026-10-24T00:00:00Z",
			"payoutBank": map[string]any{"id": "ba_1", "bankName": "STRIPE TEST BANK", "last4": "6789", "status": "errored"},
			"requirements": []map[string]any{{"requirement": "company.tax_id", "bucket": "currently_due", "form": map[string]any{"component": "account_onboarding"}},
				{"requirement": "external_account", "bucket": "past_due", "form": map[string]any{"component": "account_management"}}}})
	})
	mux.HandleFunc("POST /v1/orgs/{id}/stripe/account-session", func(w http.ResponseWriter, r *http.Request) {
		if u.AccountHealthForbidden {
			problem(w, 403, "seller.org-role-required", "Only the organisation's finance or owner can do this", nil)
			return
		}
		if r.PathValue("id") == PendingOrgID {
			problem(w, 404, "seller.no-connected-account", "No Stripe account yet", nil)
			return
		}
		u.mu.Lock()
		local := u.StripeLocalTest
		u.mu.Unlock()
		//nolint:gosec // a test fixture, not a credential
		writeJSON(w, 200, map[string]any{"accountId": "acct_1", "clientSecret": "accs_secret_org", "expiresAt": "2026-10-10T13:00:00Z", "localTest": local})
	})
	billing := func() map[string]any {
		return map[string]any{"orgId": SellerOrgID, "planCode": u.seller.plan, "planName": "Seller Growth", "cadence": "QUARTERLY", "status": "ACTIVE",
			"price": map[string]any{"cadence": "QUARTERLY", "months": 3, "totalCents": 11115, "perMonthCents": 3705, "savingCents": 585,
				"perMonthLabel": "$37.05/mo", "billedLabel": "Billed $111.15 every 3 months", "savingLabel": "Save $5.85 vs monthly"},
			"currentPeriodEnd": "2027-01-10T00:00:00Z", "nextChargeAt": "2027-01-10T00:00:00Z", "nextChargeCents": 11115, "nextChargeLabel": "$111.15 on Jan 10, 2027",
			"cancelAtPeriodEnd": false, "paymentMethods": u.paymentMethodsJSON(), "planVersion": 2, "creditBalanceCents": 2500,
			"trialSource": "CAMPAIGN"}
	}
	mux.HandleFunc("GET /v1/orgs/{id}/billing", func(w http.ResponseWriter, _ *http.Request) {
		u.mu.Lock()
		defer u.mu.Unlock()
		writeJSON(w, 200, billing())
	})
	mux.HandleFunc("GET /v1/orgs/{id}/billing/invoices", func(w http.ResponseWriter, _ *http.Request) {
		writeJSON(w, 200, []map[string]any{{"id": "in_s3", "number": "AT-0003", "periodStart": "2026-07-10T00:00:00Z", "periodEnd": "2026-10-10T00:00:00Z",
			"amountDueCents": 11115, "amountPaidCents": 11115, "currency": "usd", "status": "paid", "invoicePdf": "https://files.example/in_s3.pdf",
			"hostedInvoiceUrl": "https://invoice.example/in_s3", "createdAt": "2026-10-10T00:00:00Z", "paidFromSalesCents": 9000,
			"paidByCardCents": 2115}})
	})
	// S298: how the organisation pays its plan.
	planFees := func() map[string]any {
		return map[string]any{"orgId": SellerOrgID, "available": true, "mode": u.seller.planFeeMode, "effective": u.seller.planFeeMode,
			"source": "SELLER", "changedAt": "2026-10-11T10:00:00Z", "changedBy": "user-1", "platformDefault": "EARNINGS_FIRST",
			"appliesFromInvoiceAt": "2027-01-10T00:00:00Z", "appliesFromLabel": "Applies from your next invoice, on Jan 10, 2027",
			"inProgressInvoiceId": nil, "history": []any{}}
	}
	mux.HandleFunc("GET /v1/orgs/{id}/billing/plan-fee-collection", func(w http.ResponseWriter, _ *http.Request) {
		u.mu.Lock()
		defer u.mu.Unlock()
		writeJSON(w, 200, planFees())
	})
	mux.HandleFunc("PUT /v1/orgs/{id}/billing/plan-fee-collection", func(w http.ResponseWriter, r *http.Request) {
		var b struct {
			Mode string `json:"mode"`
		}
		_ = json.NewDecoder(r.Body).Decode(&b)
		u.mu.Lock()
		defer u.mu.Unlock()
		if u.PlanFeeCollectionOff {
			problem(w, 409, "seller.earnings-collection-off", "Paying plans from sales is switched off on this platform", nil)
			return
		}
		u.seller.planFeeMode = b.Mode
		writeJSON(w, 200, planFees())
	})
	mux.HandleFunc("GET /v1/plans", func(w http.ResponseWriter, _ *http.Request) {
		price := func(cad string, months int, total, per, save int64) map[string]any {
			return map[string]any{"cadence": cad, "months": months, "totalCents": total, "perMonthCents": per, "savingCents": save, "perMonthLabel": "$x/mo",
				"billedLabel": "Billed", "savingLabel": ""}
		}
		writeJSON(w, 200, []map[string]any{
			{"code": "seller_essentials", "kind": "SELLER", "name": "Seller Essentials", "tagline": "Start selling", "monthlyCents": 0, "trialDays": 0,
				"features": []string{"Up to 50 listings"}, "prices": []any{price("MONTHLY", 1, 0, 0, 0)}},
			{"code": "seller_growth", "kind": "SELLER", "name": "Seller Growth", "tagline": "Most popular", "monthlyCents": 3900, "trialDays": 30,
				"features": []string{"Up to 1,000 listings"}, "prices": []any{price("MONTHLY", 1, 3900, 3900, 0), price("QUARTERLY", 3, 11115, 3705, 585), price("ANNUAL", 12, 39000, 3250, 7800)}},
		})
	})
	// S292–S297: the plans this organisation may choose, rewards, previews and adding a payment method.
	mux.HandleFunc("GET /v1/orgs/{id}/billing/plans", func(w http.ResponseWriter, _ *http.Request) {
		writeJSON(w, 200, []map[string]any{
			{"code": "seller_growth", "kind": "SELLER", "name": "Seller Growth", "tagline": "Most popular", "monthlyCents": 3900, "trialDays": 60,
				"features": []string{"Up to 1,000 listings"}, "version": 2, "visibility": "PUBLIC", "prices": []any{
					map[string]any{"cadence": "QUARTERLY", "months": 3, "totalCents": 11115, "perMonthCents": 3705, "savingCents": 585,
						"perMonthLabel": "$37.05/mo", "billedLabel": "Billed $111.15 every 3 months", "savingLabel": "Save $5.85 vs monthly"}}},
			{"code": "seller_partner", "kind": "SELLER", "name": "Growth Partner", "tagline": "Invited", "monthlyCents": 2900, "trialDays": 30,
				"features": []string{"Priority support"}, "version": 1, "visibility": "INVITE_ONLY", "prices": []any{}},
		})
	})
	mux.HandleFunc("GET /v1/orgs/{id}/billing/rewards", func(w http.ResponseWriter, _ *http.Request) {
		writeJSON(w, 200, map[string]any{
			"available": []any{map[string]any{"ruleId": "6d1c0b6a-0000-4000-8000-000000000001", "name": "First $1,000", "description": nil,
				"metric": "GROSS_SALES", "value": 64000, "threshold": 100000, "percent": 64, "qualifies": false,
				"label": "$640 of $1,000 in sales → 1 month free", "rewardLabel": "1 month free",
				"conditionLabel": "$1,000 in sales within 60 days of approval", "endsAt": nil, "comparison": "AT_LEAST",
				"windowAnchor": "APPROVAL", "windowDays": 60, "pending": false, "checkAt": nil, "windowClosesAt": nil},
				// S299: low-sales relief, decided once 24 h before the trial ends.
				map[string]any{"ruleId": "6d1c0b6a-0000-4000-8000-000000000002", "name": "Low-sales relief", "description": nil,
					"metric": "GROSS_SALES", "value": 20000, "threshold": 100000, "percent": 20, "qualifies": false,
					"label": "$200 in sales so far (less than $1,000 → 1 month free)", "rewardLabel": "1 month free",
					"conditionLabel": "less than $1,000 in sales in the first 30 days of the subscription", "endsAt": nil,
					"comparison": "LESS_THAN", "windowAnchor": "SUBSCRIPTION_START", "windowDays": 30, "pending": true,
					"checkAt": "2026-11-08T10:00:00Z", "windowClosesAt": "2026-11-09T10:00:00Z"}},
			"earned": []any{map[string]any{"id": "7a1b2c3d-4e5f-4a6b-8c7d-9e0f1a2b3c4d", "orgId": SellerOrgID, "source": "RULE",
				"type": "CREDIT", "summary": "$25 credit", "note": "Two orders", "status": "APPLIED", "creditCents": 2500, "freeDays": nil,
				"createdAt": "2026-10-01T10:00:00Z", "appliedAt": "2026-10-01T10:00:01Z", "ruleComparison": "AT_LEAST", "newTrialEnd": nil},
				map[string]any{"id": "7a1b2c3d-4e5f-4a6b-8c7d-9e0f1a2b3c4e", "orgId": SellerOrgID, "source": "RULE",
					"type": "FREE_DAYS", "summary": "1 month free", "note": "Low-sales relief", "status": "APPLIED", "creditCents": nil,
					"freeDays": 30, "createdAt": "2026-10-08T10:00:00Z", "appliedAt": "2026-10-08T10:00:01Z", "ruleComparison": "LESS_THAN",
					"previousTrialEnd": "2026-10-09T10:00:00Z", "newTrialEnd": "2026-11-08T10:00:00Z"}},
		})
	})
	mux.HandleFunc("GET /v1/orgs/{id}/billing/plan/preview", func(w http.ResponseWriter, r *http.Request) {
		if r.URL.Query().Get("planCode") == "nope" {
			problem(w, 422, "seller.unknown-plan", "Plan nope is not available", map[string]any{"fieldErrors": map[string]string{"planCode": "Unknown"}})
			return
		}
		writeJSON(w, 200, map[string]any{"change": "IMMEDIATE_UPGRADE", "planCode": r.URL.Query().Get("planCode"), "planName": "Seller Pro",
			"cadence": "QUARTERLY", "effectiveAt": "2026-10-10T00:00:00Z", "amountDueNowCents": 1734, "nextChargeCents": 36765,
			"nextChargeAt": "2027-01-10T00:00:00Z", "trialing": false})
	})
	mux.HandleFunc("POST /v1/orgs/{id}/billing/setup-intents", func(w http.ResponseWriter, _ *http.Request) {
		//nolint:gosec // a test fixture, not a credential
		writeJSON(w, 201, map[string]any{"customerId": "cus_1", "setupIntentId": "seti_1", "clientSecret": "seti_1_secret_test"})
	})
	mux.HandleFunc("POST /v1/orgs/{id}/billing/payment-methods", func(w http.ResponseWriter, _ *http.Request) {
		writeJSON(w, 201, map[string]any{"id": "aaaaaaaa-0000-0000-0000-000000000009", "kind": "CARD", "brand": "visa", "last4": "4242",
			"expMonth": 12, "expYear": 2030, "verified": true, "receivesPayouts": false, "isDefault": false, "label": "Visa ••4242"})
	})
	mux.HandleFunc("POST /v1/orgs/{id}/billing/plan", func(w http.ResponseWriter, r *http.Request) {
		var b struct {
			PlanCode string `json:"planCode"`
		}
		_ = json.NewDecoder(r.Body).Decode(&b)
		u.mu.Lock()
		defer u.mu.Unlock()
		applied := "AT_RENEWAL"
		if b.PlanCode == "seller_pro" {
			applied = "IMMEDIATE_UPGRADE"
			u.seller.plan = b.PlanCode
		}
		writeJSON(w, 200, map[string]any{"applied": applied, "billing": billing()})
	})
	mux.HandleFunc("POST /v1/orgs/{id}/billing/payment-methods/{pm}/default", func(w http.ResponseWriter, r *http.Request) {
		u.mu.Lock()
		defer u.mu.Unlock()
		u.seller.defaultPM = r.PathValue("pm")
		writeJSON(w, 200, u.paymentMethodsJSON())
	})
	mux.HandleFunc("DELETE /v1/orgs/{id}/billing/payment-methods/{pm}", func(w http.ResponseWriter, r *http.Request) {
		u.mu.Lock()
		defer u.mu.Unlock()
		if r.PathValue("pm") == u.seller.defaultPM {
			problem(w, 409, "billing.default-payment-method", "Make another method the default first", nil)
			return
		}
		writeJSON(w, 200, u.paymentMethodsJSON()[:1])
	})
	// storefront service
	mux.HandleFunc("GET /v1/storefronts/me/draft", func(w http.ResponseWriter, _ *http.Request) {
		u.mu.Lock()
		defer u.mu.Unlock()
		writeJSON(w, 200, u.seller.storefront)
	})
	mux.HandleFunc("PUT /v1/storefronts/me/draft", func(w http.ResponseWriter, r *http.Request) {
		var doc map[string]any
		if err := json.NewDecoder(r.Body).Decode(&doc); err != nil {
			problem(w, 422, "storefront.invalid", "Invalid draft", nil)
			return
		}
		u.mu.Lock()
		defer u.mu.Unlock()
		u.seller.storefront = doc
		writeJSON(w, 200, doc)
	})
	mux.HandleFunc("POST /v1/storefronts/me/publish", func(w http.ResponseWriter, _ *http.Request) {
		u.mu.Lock()
		defer u.mu.Unlock()
		u.seller.storefront["status"] = "PUBLISHED"
		u.seller.storefront["publishedAt"] = "2026-10-10T12:00:00Z"
		w.WriteHeader(200)
	})
}

func (u *Upstreams) paymentMethodsJSON() []map[string]any {
	card := map[string]any{"id": "aaaaaaaa-0000-0000-0000-000000000001", "kind": "card", "brand": "visa", "last4": "4242", "expMonth": 8, "expYear": 2028,
		"verified": true, "isDefault": u.seller.defaultPM == "aaaaaaaa-0000-0000-0000-000000000001", "label": "Visa ending in 4242"}
	bank := map[string]any{"id": "aaaaaaaa-0000-0000-0000-000000000002", "kind": "bank", "bankName": "Chase", "accountType": "Business checking",
		"last4": "4417", "verified": true, "receivesPayouts": true, "isDefault": u.seller.defaultPM == "aaaaaaaa-0000-0000-0000-000000000002"}
	return []map[string]any{card, bank}
}

func (u *Upstreams) catalogSellerRoutes(mux *http.ServeMux) {
	product := func(id, status string) map[string]any {
		return map[string]any{"id": id, "atin": "ATADE00001", "ownerKind": "SELLER", "ownerOrgId": SellerOrgID, "title": "Ofada Rice, Destoned · 5 lb",
			"images": []map[string]any{{"url": "https://img.example/ofada.webp", "contentType": "image/webp"}},
			"variants": []map[string]any{{"id": "v1", "atin": "ATADEV0001", "sellerSku": "OFADA-5LB", "options": map[string]string{"Net weight": "5 lb"}, "priceMinor": 2499, "currency": "USD", "quantity": 40},
				{"id": "v2", "atin": "ATADEV0002", "sellerSku": "OFADA-10LB", "options": map[string]string{"Net weight": "10 lb"}, "priceMinor": 4299, "currency": "USD", "quantity": 12}},
			"status": status, "updatedAt": "2026-10-09T10:00:00Z"}
	}
	mux.HandleFunc("GET /v1/products", func(w http.ResponseWriter, r *http.Request) {
		if _, ok := bearerSub(w, r); !ok {
			return
		}
		u.mu.Lock()
		st := u.seller.listingStatus["prod-1"]
		u.mu.Unlock()
		items := []any{}
		if f := r.URL.Query().Get("status"); f == "" || f == st {
			items = append(items, product("prod-1", st))
		}
		writeJSON(w, 200, map[string]any{"items": items, "total": len(items), "page": 0, "size": 25})
	})
	mux.HandleFunc("POST /v1/products", func(w http.ResponseWriter, r *http.Request) {
		var b map[string]any
		_ = json.NewDecoder(r.Body).Decode(&b)
		status := "IN_REVIEW"
		if _, ok := b["categoryRequestId"]; ok {
			status = "PENDING_CATEGORY_APPROVAL"
		}
		p := product("prod-2", status)
		p["title"] = b["title"]
		writeJSON(w, 200, p)
	})
	for _, action := range []string{"pause", "resume"} {
		mux.HandleFunc("POST /v1/products/{id}/"+action, func(w http.ResponseWriter, r *http.Request) {
			u.mu.Lock()
			defer u.mu.Unlock()
			st := "PAUSED"
			if action == "resume" {
				st = "LIVE"
			}
			u.seller.listingStatus[r.PathValue("id")] = st
			writeJSON(w, 200, product(r.PathValue("id"), st))
		})
	}
	mux.HandleFunc("GET /v1/product-types/{code}", func(w http.ResponseWriter, r *http.Request) {
		if r.PathValue("code") != "grocery" {
			problem(w, 404, "catalog.product-type-not-found", "Unknown product type", nil)
			return
		}
		writeJSON(w, 200, map[string]any{"code": "grocery", "label": "packaged food", "identifierRule": "GTIN",
			"attributes": []map[string]any{{"key": "brand", "label": "Brand", "required": true}, {"key": "allergens", "label": "Allergens (FALCPA)", "options": []string{"None", "Contains peanuts"}, "required": true}},
			"themes":     []map[string]any{{"name": "Net weight", "dimensions": []map[string]any{{"name": "Net weight", "options": []string{"1 lb", "5 lb"}}}}}})
	})
	mux.HandleFunc("GET /v1/identifiers/gtin/{code}", func(w http.ResponseWriter, r *http.Request) {
		writeJSON(w, 200, map[string]any{"digits": r.PathValue("code"), "valid": true, "message": "Valid GTIN-13"})
	})
	mux.HandleFunc("GET /v1/category-requests/mine", func(w http.ResponseWriter, _ *http.Request) {
		writeJSON(w, 200, []map[string]any{{"id": "cr-1", "reference": "REQ-4821", "path": "Fashion › Women › Bridal aso-ebi sets", "status": "PENDING_REVIEW", "createdAt": "2026-10-08T09:00:00Z"}})
	})
	mux.HandleFunc("POST /v1/category-requests", func(w http.ResponseWriter, r *http.Request) {
		var b map[string]string
		_ = json.NewDecoder(r.Body).Decode(&b)
		writeJSON(w, 201, map[string]any{"id": "cr-2", "reference": "REQ-4822", "path": b["parentPath"] + " › " + b["name"], "status": "PENDING_REVIEW", "createdAt": "2026-10-10T09:00:00Z"})
	})
	// inventory, media, carrier-gateway
	mux.HandleFunc("GET /v1/positions", func(w http.ResponseWriter, _ *http.Request) {
		writeJSON(w, 200, map[string]any{"items": []map[string]any{
			{"sku": "OFADA-5LB", "nodeId": "HOU1", "ownerOrg": SellerOrgID, "onHand": 120, "reserved": 8, "available": 112, "inbound": 40},
			{"sku": "OFADA-10LB", "nodeId": "ATL1", "ownerOrg": SellerOrgID, "onHand": 9, "reserved": 2, "available": 7, "inbound": 0}}, "total": 2})
	})
	// inventory: Send to AfroTransact (S281). The BFF must filter lists to the active org and never relay ownerOrg.
	fbaShipment := func(status string) map[string]any {
		return map[string]any{"id": "7d4a3c2e-0000-4000-8000-000000000001", "number": "FBA-IN-006601", "source": "FBA", "ownerOrg": SellerOrgID,
			"nodeId": "ATL-FC", "status": status, "expectedUnits": 12, "receivedUnits": 0, "version": 0,
			"lines": []map[string]any{{"lineNo": 1, "sku": "ATF1234567", "atin": "AT00000042", "unitLabel": "ATF1234567", "expectedQty": 12,
				"receivedGood": 0, "receivedDamaged": 0, "shortQty": 12, "overQty": 0, "casePack": 6, "cases": 2, "lotNo": "B-77",
				"mfgDate": "2026-09-01", "expDate": "2027-09-01"}}, "boxes": []map[string]any{}}
	}
	mux.HandleFunc("GET /v1/inbound/shipments", func(w http.ResponseWriter, r *http.Request) {
		if r.URL.Query().Get("ownerOrg") != SellerOrgID {
			writeJSON(w, 400, map[string]any{"status": 400, "title": "ownerOrg must be the active org", "code": "test.owner"})
			return
		}
		writeJSON(w, 200, map[string]any{"items": []map[string]any{fbaShipment("WORKING")}, "total": 1, "page": 0, "size": 25})
	})
	mux.HandleFunc("POST /v1/inbound/shipments", func(w http.ResponseWriter, r *http.Request) {
		var b map[string]any
		_ = json.NewDecoder(r.Body).Decode(&b)
		if _, ok := b["ownerOrg"]; ok {
			writeJSON(w, 400, map[string]any{"status": 400, "title": "ownerOrg must not be relayed", "code": "test.owner"})
			return
		}
		writeJSON(w, 201, fbaShipment("WORKING"))
	})
	mux.HandleFunc("GET /v1/inbound/shipments/{id}", func(w http.ResponseWriter, _ *http.Request) { writeJSON(w, 200, fbaShipment("WORKING")) })
	mux.HandleFunc("PUT /v1/inbound/shipments/{id}", func(w http.ResponseWriter, _ *http.Request) { writeJSON(w, 200, fbaShipment("WORKING")) })
	mux.HandleFunc("PUT /v1/inbound/shipments/{id}/tracking", func(w http.ResponseWriter, _ *http.Request) {
		writeJSON(w, 200, map[string]any{"shipment": fbaShipment("WORKING"), "trackingRegistered": true})
	})
	mux.HandleFunc("POST /v1/inbound/shipments/{id}/cancel", func(w http.ResponseWriter, _ *http.Request) { writeJSON(w, 200, fbaShipment("CANCELLED")) })
	mux.HandleFunc("GET /v1/nodes", func(w http.ResponseWriter, _ *http.Request) {
		writeJSON(w, 200, []map[string]any{{"id": "ATL-FC", "name": "Atlanta FC", "kind": "FULFILMENT_CENTRE", "country": "US", "active": true}})
	})
	mux.HandleFunc("GET /v1/fba/inventory", func(w http.ResponseWriter, r *http.Request) {
		if r.URL.Query().Get("ownerOrg") != SellerOrgID {
			writeJSON(w, 400, map[string]any{"status": 400, "title": "ownerOrg must be the active org", "code": "test.owner"})
			return
		}
		writeJSON(w, 200, map[string]any{"ownerOrg": SellerOrgID, "items": []map[string]any{}, "total": 0, "page": 0, "size": 50})
	})
	mux.HandleFunc("GET /v1/lots/markdown", func(w http.ResponseWriter, _ *http.Request) { writeJSON(w, 200, []map[string]any{}) })
	mux.HandleFunc("GET /v1/lots/{id}", func(w http.ResponseWriter, _ *http.Request) {
		writeJSON(w, 200, map[string]any{"id": "5b1d0c9e-0000-4000-8000-000000000002", "sku": "ATF1234567", "lotNo": "B-77", "nodeId": "ATL-FC",
			"ownerOrg": SellerOrgID, "qtyOnHand": 11, "qtyReserved": 1, "qtyAvailable": 10, "status": "SELLABLE", "markdownFlag": "NONE", "version": 0})
	})
	mux.HandleFunc("POST /v1/inbound/shipments/{id}/ship", func(w http.ResponseWriter, _ *http.Request) { writeJSON(w, 200, fbaShipment("SHIPPED")) })
	mux.HandleFunc("GET /v1/inbound/shipments/{id}/labels", func(w http.ResponseWriter, _ *http.Request) {
		w.Header().Set("Content-Type", "application/pdf")
		w.Header().Set("Content-Disposition", `inline; filename="box-FBA-IN-006601.pdf"`)
		_, _ = w.Write([]byte("%PDF-1.7 labels"))
	})
	mux.HandleFunc("GET /v1/lots", func(w http.ResponseWriter, r *http.Request) {
		if r.URL.Query().Get("ownerOrg") != SellerOrgID {
			writeJSON(w, 400, map[string]any{"status": 400, "title": "ownerOrg must be the active org", "code": "test.owner"})
			return
		}
		writeJSON(w, 200, map[string]any{"items": []map[string]any{{"id": "5b1d0c9e-0000-4000-8000-000000000002", "sku": "ATF1234567", "lotNo": "B-77",
			"nodeId": "ATL-FC", "ownerOrg": SellerOrgID, "qtyOnHand": 11, "qtyReserved": 1, "qtyAvailable": 10, "expDate": "2027-09-01",
			"status": "SELLABLE", "markdownFlag": "NONE", "version": 0}}, "total": 1, "page": 0, "size": 50})
	})
	// seller ship-from addresses (one org's list, default first)
	shipFrom := []map[string]any{}
	listShipFrom := func(w http.ResponseWriter, status int) {
		out := []map[string]any{}
		for _, a := range shipFrom {
			if a["isDefault"] == true {
				out = append([]map[string]any{a}, out...)
			} else {
				out = append(out, a)
			}
		}
		writeJSON(w, status, out)
	}
	mux.HandleFunc("GET /v1/orgs/{id}/ship-from-addresses", func(w http.ResponseWriter, r *http.Request) {
		u.mu.Lock()
		defer u.mu.Unlock()
		if r.PathValue("id") != SellerOrgID {
			problem(w, 403, "seller.org-role-required", "not your organisation", nil)
			return
		}
		listShipFrom(w, 200)
	})
	mux.HandleFunc("POST /v1/orgs/{id}/ship-from-addresses", func(w http.ResponseWriter, r *http.Request) {
		u.mu.Lock()
		defer u.mu.Unlock()
		var a map[string]any
		_ = json.NewDecoder(r.Body).Decode(&a)
		a["id"] = newUUID()
		a["version"] = 0
		// geo-service geocodes every address (S255): street level, except the fake's "unlocatable" ZIP 00000.
		a["position"] = map[string]any{"lat": 29.7604, "lng": -95.3698, "precision": "ADDRESS", "geocodedAt": "2026-10-10T09:00:00Z"}
		if a["postalCode"] == "00000" {
			a["position"] = map[string]any{"lat": 29.7, "lng": -95.3, "precision": "ZIP", "geocodedAt": "2026-10-10T09:00:00Z"}
		}
		a["afrotransactPickup"] = map[string]any{"enabled": false, "hours": []any{}}
		a["isDefault"] = len(shipFrom) == 0 || a["makeDefault"] == true
		if a["isDefault"] == true {
			for _, x := range shipFrom {
				x["isDefault"] = false
			}
		}
		delete(a, "makeDefault")
		shipFrom = append(shipFrom, a)
		writeJSON(w, 201, a)
	})
	mux.HandleFunc("PUT /v1/orgs/{id}/ship-from-addresses/{aid}", func(w http.ResponseWriter, r *http.Request) {
		u.mu.Lock()
		defer u.mu.Unlock()
		var b map[string]any
		_ = json.NewDecoder(r.Body).Decode(&b)
		for _, a := range shipFrom {
			if a["id"] == r.PathValue("aid") {
				if v, ok := b["version"].(float64); ok && int(v) != a["version"].(int) {
					problem(w, 409, "seller.concurrent-update", "changed by someone else", nil)
					return
				}
				for k, v := range b {
					if k != "version" && k != "makeDefault" {
						a[k] = v
					}
				}
				a["version"] = a["version"].(int) + 1
				writeJSON(w, 200, a)
				return
			}
		}
		problem(w, 404, "seller.ship-from-not-found", "not found", nil)
	})
	// AfroTransact pickup (S255): hours required, street-level position, inside the delivery area (Houston ZIPs 77xxx).
	mux.HandleFunc("PUT /v1/orgs/{id}/ship-from-addresses/{aid}/afrotransact-pickup", func(w http.ResponseWriter, r *http.Request) {
		u.mu.Lock()
		defer u.mu.Unlock()
		var b map[string]any
		_ = json.NewDecoder(r.Body).Decode(&b)
		for _, a := range shipFrom {
			if a["id"] != r.PathValue("aid") {
				continue
			}
			hours, _ := b["hours"].([]any)
			pos, _ := a["position"].(map[string]any)
			switch {
			case b["enabled"] == true && len(hours) == 0:
				problem(w, 422, "seller.invalid-pickup-hours", "Add at least one pickup window", nil)
			case b["enabled"] == true && pos["precision"] != "ADDRESS":
				problem(w, 422, "seller.ship-from-not-geocoded", "Not located at street level", nil)
			case b["enabled"] == true && !strings.HasPrefix(fmt.Sprint(a["postalCode"]), "77"):
				problem(w, 422, "seller.pickup-outside-delivery-area", "Katy is 31 miles from the Houston Metro delivery zone", nil)
			default:
				a["afrotransactPickup"] = map[string]any{"enabled": b["enabled"], "hours": hours, "zoneName": "Houston Metro", "enabledAt": "2026-10-10T09:00:00Z"}
				a["version"] = a["version"].(int) + 1
				writeJSON(w, 200, a)
			}
			return
		}
		problem(w, 404, "seller.ship-from-not-found", "not found", nil)
	})
	mux.HandleFunc("POST /v1/orgs/{id}/ship-from-addresses/{aid}/default", func(w http.ResponseWriter, r *http.Request) {
		u.mu.Lock()
		defer u.mu.Unlock()
		for _, a := range shipFrom {
			a["isDefault"] = a["id"] == r.PathValue("aid")
		}
		listShipFrom(w, 200)
	})
	mux.HandleFunc("DELETE /v1/orgs/{id}/ship-from-addresses/{aid}", func(w http.ResponseWriter, r *http.Request) {
		u.mu.Lock()
		defer u.mu.Unlock()
		for i, a := range shipFrom {
			if a["id"] == r.PathValue("aid") {
				shipFrom = append(shipFrom[:i], shipFrom[i+1:]...)
				if a["isDefault"] == true && len(shipFrom) > 0 {
					shipFrom[0]["isDefault"] = true
				}
				listShipFrom(w, 200)
				return
			}
		}
		problem(w, 404, "seller.ship-from-not-found", "not found", nil)
	})
	mux.HandleFunc("POST /v1/uploads", func(w http.ResponseWriter, r *http.Request) {
		var body map[string]any
		_ = json.NewDecoder(r.Body).Decode(&body)
		u.mu.Lock()
		u.LastUploadPurpose, _ = body["purpose"].(string)
		u.mu.Unlock()
		writeJSON(w, 201, map[string]any{"asset": map[string]any{"id": "asset-1", "status": "PENDING"}, "upload": map[string]any{"method": "PUT",
			"url": "https://uploads.example/asset-1", "headers": map[string]string{"Content-Type": "image/webp"}, "publicUrl": "https://img.example/asset-1.webp", "expiresAt": "2026-10-10T12:15:00Z"}})
	})
	mux.HandleFunc("POST /v1/assets/{id}/complete", func(w http.ResponseWriter, r *http.Request) {
		if r.PathValue("id") == MissingAssetID {
			problem(w, 404, "media.asset-not-found", "No such upload", nil)
			return
		}
		writeJSON(w, 200, map[string]any{"id": r.PathValue("id"), "url": "https://img.example/asset-1.webp", "contentType": "image/webp", "bytes": 182000, "width": 1600, "height": 1600, "status": "READY"})
	})
	mux.HandleFunc("POST /v1/rates", func(w http.ResponseWriter, _ *http.Request) {
		writeJSON(w, 200, map[string]any{"quotes": []map[string]any{
			{"carrier": "ups", "service": "03", "serviceName": "UPS Ground", "amountMinor": 1189, "currency": "USD", "transitDays": 3},
			{"carrier": "usps", "service": "USPS_GROUND_ADVANTAGE", "serviceName": "USPS Ground Advantage", "amountMinor": 845, "currency": "USD", "transitDays": 4}}, "partial": false})
	})
	// Customer pickup locations (sellers' own, kind SELLER): kept per id for the test run.
	pickups := map[string]map[string]any{}
	var pickupMu sync.Mutex
	mux.HandleFunc("GET /v1/pickup-locations", func(w http.ResponseWriter, r *http.Request) {
		pickupMu.Lock()
		defer pickupMu.Unlock()
		items := []map[string]any{}
		for _, l := range pickups {
			if owner := r.URL.Query().Get("ownerOrg"); owner == "" || l["ownerOrg"] == owner {
				items = append(items, l)
			}
		}
		writeJSON(w, 200, map[string]any{"items": items})
	})
	mux.HandleFunc("PUT /v1/pickup-locations/{id}", func(w http.ResponseWriter, r *http.Request) {
		var l map[string]any
		if err := json.NewDecoder(r.Body).Decode(&l); err != nil || l["kind"] != "SELLER" || l["ownerOrg"] == "" {
			problem(w, 400, "carrier-gateway.invalid-request", "Invalid pickup location", nil)
			return
		}
		l["id"] = r.PathValue("id")
		pickupMu.Lock()
		pickups[r.PathValue("id")] = l
		pickupMu.Unlock()
		writeJSON(w, 200, l)
	})
	mux.HandleFunc("POST /v1/labels", func(w http.ResponseWriter, r *http.Request) {
		if r.Header.Get("Idempotency-Key") == "" {
			problem(w, 400, "carrier.idempotency-key-required", "Idempotency-Key required", nil)
			return
		}
		writeJSON(w, 201, map[string]any{"carrier": "usps", "service": "USPS_GROUND_ADVANTAGE", "trackingNumber": "9400111899223344556677", "costMinor": 845,
			"currency": "USD", "status": "purchased", "documentUrl": "https://labels.example/l1.pdf"})
	})
}

// FeeScheduleUpcoming is when the fake upcoming fee-schedule version (v2) takes effect.
const FeeScheduleUpcoming = "2099-01-01T00:00:00Z"

// feeRoutes fakes payments' fee schedules: v1 in force, v2 scheduled, v3 cancelled. Only the BFF's service token
// (scope payments:fees.read) may read them, as in payments.
func (u *Upstreams) feeRoutes(mux *http.ServeMux) {
	service := func(w http.ResponseWriter, r *http.Request) bool {
		if r.Header.Get("Authorization") != "Bearer svc-payments:fees.read" {
			problem(w, 403, "payments.staff-only", "Payment splits and the ledger are staff views", nil)
			return false
		}
		return true
	}
	mux.HandleFunc("GET /v1/fee-schedules", func(w http.ResponseWriter, r *http.Request) {
		if !service(w, r) {
			return
		}
		writeJSON(w, 200, []map[string]any{
			{"id": "fee-3", "version": 3, "effectiveFrom": "2098-01-01T00:00:00Z", "categoryBps": map[string]any{}, "defaultCommissionBps": 100,
				"overrides": []any{}, "cancelledAt": "2026-10-01T00:00:00Z"},
			{"id": "fee-2", "version": 2, "effectiveFrom": FeeScheduleUpcoming, "categoryBps": map[string]any{"groceries": 800, "fashion": 1300},
				"defaultCommissionBps": 1000, "overrides": []any{map[string]any{"orgId": SellerOrgID, "category": "groceries", "commissionBps": 600},
					map[string]any{"orgId": "someone-else", "category": "beauty", "commissionBps": 100}},
				"deliveryPricing": map[string]any{"enabled": true, "maxRadiusMiles": 25,
					"customerCharge": map[string]any{"mode": "DISTANCE", "baseMinor": 499, "perMileMinor": 85, "minMinor": 599, "maxMinor": 2499},
					"sellerFee":      map[string]any{"mode": "FLAT", "flatPerPackageMinor": 200},
					"sellerOverrides": []any{map[string]any{"orgId": SellerOrgID, "sellerFee": map[string]any{"mode": "FLAT", "flatPerPackageMinor": 150}},
						map[string]any{"orgId": "someone-else", "sellerFee": map[string]any{"mode": "FLAT", "flatPerPackageMinor": 0}}}}},
			{"id": "fee-1", "version": 1, "effectiveFrom": "2020-01-01T00:00:00Z", "categoryBps": map[string]any{"groceries": 800, "fashion": 1200},
				"defaultCommissionBps": 1000, "overrides": []any{map[string]any{"orgId": SellerOrgID, "category": "groceries", "commissionBps": 600}},
				"deliveryPricing": map[string]any{"enabled": true, "maxRadiusMiles": 25,
					"customerCharge": map[string]any{"mode": "DISTANCE", "baseMinor": 499, "perMileMinor": 85, "minMinor": 599, "maxMinor": 2499},
					"sellerFee":      map[string]any{"mode": "FLAT", "flatPerPackageMinor": 200}}},
		})
	})
	mux.HandleFunc("GET /v1/fee-schedules/effective", func(w http.ResponseWriter, r *http.Request) {
		if !service(w, r) {
			return
		}
		q := r.URL.Query()
		next := q.Get("at") >= FeeScheduleUpcoming
		version, from, fba, rates := 1, "2020-01-01T00:00:00Z", 325, map[string]int{"groceries": 800, "fashion": 1200}
		if next {
			version, from, fba, rates = 2, FeeScheduleUpcoming, 350, map[string]int{"groceries": 800, "fashion": 1300}
		}
		cat := strings.ToLower(q.Get("category"))
		bps, source := 1000, "DEFAULT"
		if v, ok := rates[cat]; ok {
			bps, source = v, "CATEGORY"
		}
		if q.Get("orgId") == SellerOrgID && cat == "groceries" {
			bps, source = 600, "OVERRIDE"
		}
		kind, plan := strings.ToUpper(firstNonEmptyStr(q.Get("kind"), "PRODUCT")), ""
		if kind == "SERVICE" {
			// Connection fees: Provider Studio's plan rate (8 %) for the provider, the 10 % default otherwise.
			bps, source, fba = 1000, "DEFAULT", 0
			if q.Get("orgId") == ProviderOrgID {
				bps, source, plan = 800, "PLAN", "provider_studio"
			}
		}
		writeJSON(w, 200, map[string]any{"feeScheduleId": fmt.Sprintf("fee-%d", version), "version": version, "effectiveFrom": from, "currency": "USD",
			"category": cat, "orgId": q.Get("orgId"), "commissionBps": bps, "commissionSource": source, "cardPctBps": 290, "cardFixedMinor": 30,
			"fbaPerUnitMinor": fba, "reverseCommissionOnRefund": true, "kind": kind, "planCode": nilIfEmpty(plan)})
	})
	// The organisation's rate card per kind: the seller is on Seller Growth with a negotiated groceries rate until the
	// end of the year; the provider on Provider Studio (8 % connection fee, hair & beauty negotiated at 7 %).
	mux.HandleFunc("GET /v1/orgs/{id}/rate-card", func(w http.ResponseWriter, r *http.Request) {
		if !service(w, r) {
			return
		}
		org, kind := r.PathValue("id"), strings.ToUpper(firstNonEmptyStr(r.URL.Query().Get("kind"), "PRODUCT"))
		until := "2026-12-31T23:59:59Z"
		rate := func(cat string, bps int, source, plan string, negotiated bool) map[string]any {
			m := map[string]any{"category": cat, "bps": bps, "source": source, "planCode": nilIfEmpty(plan), "negotiatedRateId": nil, "negotiatedUntil": nil}
			if negotiated {
				m["negotiatedRateId"], m["negotiatedUntil"] = "nr-1", until
			}
			return m
		}
		card := map[string]any{"orgId": org, "kind": kind, "feeScheduleId": "fee-1", "version": 1, "effectiveFrom": "2020-01-01T00:00:00Z",
			"at": "2026-10-12T00:00:00Z", "currency": "USD", "cardPctBps": 290, "cardFixedMinor": 30, "fbaPerUnitMinor": 0,
			"reverseCommissionOnRefund": true, "retail": false}
		if kind == "SERVICE" {
			card["planCode"] = "provider_studio"
			card["otherRate"] = rate("", 800, "PLAN", "provider_studio", false)
			card["categories"] = []any{rate("hair & beauty", 700, "OVERRIDE", "provider_studio", true)}
			card["negotiated"] = []any{map[string]any{"id": "nr-2", "orgId": org, "type": "CONNECTION_FEE", "category": "hair & beauty", "rateBps": 700,
				"startsAt": "2026-10-01T00:00:00Z", "endsAt": until, "status": "ACTIVE"}}
		} else {
			card["planCode"] = "seller_growth"
			card["fbaPerUnitMinor"] = 325
			card["otherRate"] = rate("", 1000, "PLAN", "seller_growth", false)
			card["categories"] = []any{rate("fashion", 1200, "PLAN", "seller_growth", false), rate("groceries", 600, "OVERRIDE", "seller_growth", true)}
			card["negotiated"] = []any{map[string]any{"id": "nr-1", "orgId": org, "type": "COMMISSION", "category": "groceries", "rateBps": 600,
				"startsAt": "2026-10-01T00:00:00Z", "endsAt": until, "status": "ACTIVE"}}
		}
		writeJSON(w, 200, card)
	})
}

func firstNonEmptyStr(v, def string) string {
	if v == "" {
		return def
	}
	return v
}

func nilIfEmpty(s string) any {
	if s == "" {
		return nil
	}
	return s
}

func (u *Upstreams) moneyRoutes(mux *http.ServeMux) {
	u.feeRoutes(mux)
	mux.HandleFunc("GET /v1/payouts/balance", func(w http.ResponseWriter, _ *http.Request) {
		writeJSON(w, 200, map[string]any{"orgId": SellerOrgID, "currency": "USD", "availableMinor": 128455, "onHoldMinor": 52725, "requestedOrInTransitMinor": 0,
			"paidMinor": 932210, "providerFeesMinor": 1500, "planFeesMinor": 0, "minimumPayoutMinor": 2500, "canRequestToday": true,
			"payoutsPaused": true, "payoutsPausedReason": "AUTO_PAST_DUE", "onHold": []map[string]any{{"orderId": "AT-2026-000123", "amountMinor": 52725}}})
	})
	// S298: plan invoices paid from sales (a DECLINED one took nothing and is not shown).
	mux.HandleFunc("GET /v1/orgs/{id}/plan-fee-withholdings", func(w http.ResponseWriter, _ *http.Request) {
		writeJSON(w, 200, []map[string]any{
			{"id": "pf-2", "orgId": SellerOrgID, "invoiceId": "in_s4", "invoiceNumber": nil, "currency": "USD", "requestedMinor": 11115,
				"withheldMinor": 0, "releasedMinor": 0, "settledMinor": 0, "outstandingMinor": 0, "status": "DECLINED",
				"reason": "No delivered, undisputed earnings to collect from", "label": "Plan fee (invoice in_s4)", "createdAt": "2026-10-11T00:00:00Z",
				"updatedAt": "2026-10-11T00:00:00Z", "deductions": []any{}},
			{"id": "pf-1", "orgId": SellerOrgID, "invoiceId": "in_s3", "invoiceNumber": "AT-0003", "currency": "USD", "requestedMinor": 11115,
				"withheldMinor": 9000, "releasedMinor": 0, "settledMinor": 9000, "outstandingMinor": 0, "status": "WITHHELD",
				"reason": "Only $90.00 of $111.15 was available; the card pays the rest", "label": "Plan fee (invoice AT-0003)",
				"createdAt": "2026-10-10T00:00:00Z", "updatedAt": "2026-10-12T00:00:00Z", "deductions": []any{map[string]any{
					"orderId": "AT-2026-000123", "amountMinor": 9000, "at": "2026-10-12T00:00:00Z",
					"label": "Plan fee (invoice AT-0003) deducted from order AT-2026-000123"}}}})
	})
	mux.HandleFunc("GET /v1/payouts/provider-fees", func(w http.ResponseWriter, _ *http.Request) {
		writeJSON(w, 200, []map[string]any{
			{"id": "fee-1", "orgId": ProviderOrgID, "bookingId": "23970", "bookingReference": "BK-23970", "amountMinor": 1500, "currency": "usd",
				"reason": "Late cancellation by provider", "status": "OUTSTANDING", "attempts": 1, "nextAttemptAt": "2026-10-11T12:00:00Z", "createdAt": "2026-10-05T16:00:00Z"},
			{"id": "fee-0", "orgId": ProviderOrgID, "bookingId": "23900", "bookingReference": "BK-23900", "amountMinor": 1500, "currency": "usd",
				"reason": "Late cancellation by provider", "status": "COLLECTED", "createdAt": "2026-09-20T16:00:00Z"}})
	})
	mux.HandleFunc("GET /v1/payouts/requests", func(w http.ResponseWriter, _ *http.Request) {
		problem(w, 403, "payments.forbidden", "payouts:approve required", nil)
	})
	mux.HandleFunc("POST /v1/payouts/requests", func(w http.ResponseWriter, r *http.Request) {
		var b struct {
			AmountMinor int64 `json:"amountMinor"`
			All         bool  `json:"all"`
		}
		_ = json.NewDecoder(r.Body).Decode(&b)
		if !b.All && b.AmountMinor < 2500 {
			problem(w, 422, "payments.below-minimum", "The minimum payout is $25.00", map[string]any{"errors": map[string]string{"amountMinor": "At least $25.00"}})
			return
		}
		amount := b.AmountMinor
		if b.All {
			amount = 128455
		}
		writeJSON(w, 201, map[string]any{"id": "po_1", "orgId": SellerOrgID, "amountMinor": amount, "currency": "USD", "status": "REQUESTED", "requestedAt": time.Now().UTC().Format(time.RFC3339)})
	})
}

// ---------------------------------------------------------------- services still being built (SellerPending)

func (u *Upstreams) orderJSON() map[string]any {
	st := u.seller.shipStatus
	next := map[string][]string{"placed": {"confirmed", "cancelled"}, "confirmed": {"packed"}, "packed": {"ready"}}[st]
	return map[string]any{"id": "o1", "orderNumber": "AT-2026-000123", "status": "in_progress", "placedAt": "2026-10-09T15:00:00Z", "currency": "USD", "totalMinor": 5437,
		"shipTo": "Houston, TX", "shippingAddress": map[string]any{"fullName": "Adaeze Nwosu", "phone": "+17135550142", "line1": "9800 Westheimer Rd", "city": "Houston", "state": "TX", "postalCode": "77042", "country": "US"},
		"shipments": []map[string]any{{"id": "sh-1", "shipmentId": "sh-1", "label": "Shipment 1 of 1 · Seller", "sellerOrgId": SellerOrgID, "fulfilmentType": "SELLER_FULFILLED",
			"deliveryMethod": "CARRIER", "status": st, "items": "Ofada rice ×2", "itemsMinor": 4998, "shippingMinor": 0, "totalMinor": 5437, "carrier": nil, "trackingNumber": u.seller.tracking,
			"nextActions": next, "lines": []map[string]any{{"variantAtin": "ATADEV0001", "title": "Ofada Rice · 5 lb", "quantity": 2, "unitPriceMinor": 2499, "itemsMinor": 4998}},
			"timeline": []map[string]any{{"from": "pending_payment", "to": "placed", "actorType": "SYSTEM", "at": "2026-10-09T15:00:00Z"}}},
			// AfroTransact Delivery from the seller's store (S255): the seller packs, a driver collects.
			{"id": "sh-2", "shipmentId": "sh-2", "label": "Shipment 2 of 2 · AfroTransact Delivery", "sellerOrgId": SellerOrgID, "fulfilmentType": "SELLER_FULFILLED",
				"deliveryMethod": "AFROTRANSACT_DELIVERY", "pickupLocationId": "loc-1", "status": "placed", "items": "Palm oil ×1", "itemsMinor": 1500, "shippingMinor": 1128,
				"totalMinor": 2752, "nextActions": []string{"confirmed"},
				"localDelivery": map[string]any{"shipFromLocationId": "loc-1", "roadMiles": 7.4, "durationMinutes": 18, "distanceMethod": "ROAD_OSRM",
					"customerChargeMinor": 1128, "sellerFeeMinor": 200, "pricingVersion": 9},
				"lines":    []map[string]any{{"variantAtin": "ATADEV0002", "title": "Red palm oil · 1 L", "quantity": 1, "unitPriceMinor": 1500, "itemsMinor": 1500}},
				"timeline": []map[string]any{{"from": "pending_payment", "to": "placed", "actorType": "SYSTEM", "at": "2026-10-09T15:00:00Z"}}}}}
}

func (u *Upstreams) ordersRoutes(mux *http.ServeMux) {
	mux.HandleFunc("GET /v1/seller/shipments", func(w http.ResponseWriter, _ *http.Request) {
		u.mu.Lock()
		defer u.mu.Unlock()
		o := u.orderJSON()
		sh := o["shipments"].([]map[string]any)[0]
		writeJSON(w, 200, map[string]any{"items": []any{map[string]any{"orderNumber": o["orderNumber"], "orderStatus": o["status"], "placedAt": o["placedAt"],
			"shipTo": o["shippingAddress"], "shipment": sh}}, "nextCursor": nil, "total": 1})
	})
	mux.HandleFunc("GET /v1/orders/{number}", func(w http.ResponseWriter, r *http.Request) {
		if r.PathValue("number") != "AT-2026-000123" {
			problem(w, 404, "orders.not-found", "No such order", nil)
			return
		}
		u.mu.Lock()
		defer u.mu.Unlock()
		writeJSON(w, 200, u.orderJSON())
	})
	mux.HandleFunc("POST /v1/shipments/{id}/transitions", func(w http.ResponseWriter, r *http.Request) {
		var b struct {
			To string `json:"to"`
		}
		_ = json.NewDecoder(r.Body).Decode(&b)
		u.mu.Lock()
		defer u.mu.Unlock()
		allowed := map[string]string{"placed": "confirmed", "confirmed": "packed", "packed": "ready"}
		if allowed[u.seller.shipStatus] != b.To {
			problem(w, 409, "orders.invalid-transition", "Can't move from "+u.seller.shipStatus+" to "+b.To, nil)
			return
		}
		u.seller.shipStatus = b.To
		writeJSON(w, 200, map[string]any{"outcome": "applied", "order": u.orderJSON()})
	})
	mux.HandleFunc("POST /v1/shipments/{id}/tracking", func(w http.ResponseWriter, r *http.Request) {
		var b struct {
			TrackingNumber string `json:"trackingNumber"`
		}
		_ = json.NewDecoder(r.Body).Decode(&b)
		u.mu.Lock()
		defer u.mu.Unlock()
		u.seller.tracking = b.TrackingNumber
		writeJSON(w, 200, u.orderJSON())
	})
	mux.HandleFunc("GET /v1/orders/{number}/payment-split", func(w http.ResponseWriter, _ *http.Request) {
		writeJSON(w, 200, map[string]any{"orderId": "o1", "chargeMinor": 8189, "currency": "USD", "recipients": []map[string]any{
			{"recipient": SellerOrgID, "kind": "SELLER", "itemsMinor": 6498, "shippingMinor": 0, "taxMinor": 0, "commissionMinor": 520, "fbaFeesMinor": 0,
				"deliveryFeesMinor": 200, "stripeFeeMinor": 190, "netMinor": 5588},
			{"recipient": "afrotransact", "kind": "PLATFORM", "itemsMinor": 0, "shippingMinor": 1128, "commissionMinor": 520, "netMinor": 1848}}})
	})
	// offers (seller side): the organisation's offers and the fulfilment choice (S255).
	offers := []map[string]any{
		{"id": "of-1", "atin": "ATADEV0001", "productAtin": "ATADE00001", "productId": "p-1", "sellerSku": "OFADA-5LB", "condition": "NEW", "priceMinor": 2499,
			"currency": "USD", "fulfilment": "SELLER", "quantity": 40, "availableQty": 40, "shipFrom": map[string]any{"city": "Houston", "region": "TX"},
			"shipsFrom": "Houston, TX", "status": "ACTIVE", "version": 3},
		{"id": "of-2", "atin": "ATADEV0003", "productAtin": "ATOTHER001", "productId": "p-9", "sellerSku": "OTHER", "condition": "NEW", "priceMinor": 999,
			"currency": "USD", "fulfilment": "AFROTRANSACT", "quantity": 0, "availableQty": 12, "status": "ACTIVE", "version": 1},
	}
	mux.HandleFunc("GET /v1/offers", func(w http.ResponseWriter, _ *http.Request) {
		u.mu.Lock()
		defer u.mu.Unlock()
		writeJSON(w, 200, map[string]any{"items": offers, "total": len(offers), "page": 0, "size": 100})
	})
	mux.HandleFunc("PATCH /v1/offers/{id}", func(w http.ResponseWriter, r *http.Request) {
		u.mu.Lock()
		defer u.mu.Unlock()
		var b map[string]any
		_ = json.NewDecoder(r.Body).Decode(&b)
		for _, o := range offers {
			if o["id"] != r.PathValue("id") {
				continue
			}
			loc, _ := b["shipFrom"].(map[string]any)
			if b["fulfilment"] == "AFROTRANSACT_DELIVERY" && loc["locationId"] != "loc-1" {
				problem(w, 422, "offers.rule-violation", "Pickup location is not enabled",
					map[string]any{"fieldErrors": map[string]any{"shipFrom.locationId": "Choose a ship-from location with AfroTransact pickup enabled"}})
				return
			}
			o["fulfilment"] = b["fulfilment"]
			if loc != nil {
				o["shipFrom"] = map[string]any{"city": "Houston", "region": "TX", "postalCode": "77002", "locationId": loc["locationId"]}
			}
			o["version"] = o["version"].(int) + 1
			writeJSON(w, 200, o)
			return
		}
		problem(w, 404, "offers.offer-not-found", "not found", nil)
	})
	mux.HandleFunc("GET /v1/seller/analytics", func(w http.ResponseWriter, _ *http.Request) {
		writeJSON(w, 200, map[string]any{"currency": "USD", "toShip": 9,
			"days":         []map[string]any{{"date": "2026-10-09", "salesMinor": 190000, "units": 64, "orders": 20}, {"date": "2026-10-10", "salesMinor": 210000, "units": 70, "orders": 22}},
			"topProducts":  []map[string]any{{"title": "Ofada rice 5 lb", "revenueMinor": 624000}},
			"byFulfilment": []map[string]any{{"fulfilment": "AFROTRANSACT", "orders": 412}, {"fulfilment": "SELLER", "orders": 268}}})
	})
}

func (u *Upstreams) bookingsRoutes(mux *http.ServeMux) {
	// Bookings' Provider Hub routes are org-scoped: /v1/providers/{orgId}/… (the BFF maps the active org).
	booking := func(id, status string, start time.Time) map[string]any {
		return map[string]any{"id": id, "reference": "BK-" + id, "orgId": ProviderOrgID, "customer": map[string]any{"name": "Folake Bello"},
			"service": map[string]any{"name": "Cornrows · 8–10 rows", "durationMinutes": 120, "priceMinor": 7500, "depositMinor": 2000,
				"currency": "usd", "memberName": "Ama"},
			"status": status, "startAt": start.Format(time.RFC3339), "endAt": start.Add(2 * time.Hour).Format(time.RFC3339),
			"payment": map[string]any{"state": "AUTHORIZED", "refundedMinor": 0}}
	}
	start := time.Date(2026, 10, 12, 15, 0, 0, 0, time.UTC)
	org := func(w http.ResponseWriter, r *http.Request) bool {
		if r.PathValue("org") != ProviderOrgID {
			problem(w, 403, "bookings.forbidden", "Not a member of this provider", nil)
			return false
		}
		return true
	}
	mux.HandleFunc("GET /v1/providers/{org}/calendar", func(w http.ResponseWriter, r *http.Request) {
		if !org(w, r) {
			return
		}
		writeJSON(w, 200, map[string]any{"orgId": ProviderOrgID, "timeZone": "America/Chicago", "from": r.URL.Query().Get("from"), "to": r.URL.Query().Get("to"),
			"days":     []map[string]any{{"memberId": "m-1", "date": "2026-10-13", "open": []map[string]string{{"start": "10:00", "end": "18:00"}}, "timeOff": false}},
			"bookings": []any{booking("24011", "CONFIRMED", start), booking("24018", "REQUESTED", start.Add(24*time.Hour))}})
	})
	mux.HandleFunc("GET /v1/providers/{org}/bookings", func(w http.ResponseWriter, r *http.Request) {
		if !org(w, r) {
			return
		}
		switch r.URL.Query().Get("status") {
		case "REQUESTED":
			writeJSON(w, 200, []any{booking("24018", "REQUESTED", start.Add(24*time.Hour))})
		case "":
			writeJSON(w, 200, []any{booking("24011", "COMPLETED", start), booking("24018", "REQUESTED", start.Add(24*time.Hour))})
		default:
			writeJSON(w, 200, []any{})
		}
	})
	mux.HandleFunc("POST /v1/bookings/{id}/{action}", func(w http.ResponseWriter, r *http.Request) {
		st := map[string]string{"confirm": "CONFIRMED", "decline": "DECLINED", "propose-time": "RESCHEDULE_PROPOSED", "complete": "COMPLETED", "no-show": "NO_SHOW", "cancel": "CANCELLED_PROVIDER"}[r.PathValue("action")]
		if st == "" || r.Header.Get("Idempotency-Key") == "" {
			problem(w, 400, "bookings.invalid", "unknown action or missing Idempotency-Key", nil)
			return
		}
		if st == "CANCELLED_PROVIDER" {
			writeJSON(w, 200, map[string]any{"booking": booking(r.PathValue("id"), st, start.Add(24*time.Hour))})
			return
		}
		writeJSON(w, 200, booking(r.PathValue("id"), st, start.Add(24*time.Hour)))
	})
	rules := map[string]any{"leadMinutes": 720, "horizonDays": 30, "bufferMinutes": 30, "stepMinutes": 30, "maxPerDay": 3, "instantBooking": false, "autoAcceptReschedule": true}
	policy := map[string]any{"kind": "MODERATE", "label": "Moderate", "freeHours": 48, "tiers": []map[string]any{{"hoursBefore": 48, "shareBps": 10000}, {"hoursBefore": 24, "shareBps": 5000}},
		"noShow": "DEPOSIT", "rescheduleHours": 48, "maxReschedules": 2}
	mux.HandleFunc("GET /v1/providers/{org}/settings", func(w http.ResponseWriter, r *http.Request) {
		if !org(w, r) {
			return
		}
		writeJSON(w, 200, map[string]any{"orgId": ProviderOrgID, "displayName": "Ama's Braiding Studio", "timeZone": "America/Chicago", "currency": "USD",
			"rules": rules, "policy": policy})
	})
	mux.HandleFunc("PUT /v1/providers/{org}/settings", func(w http.ResponseWriter, r *http.Request) {
		var b map[string]any
		_ = json.NewDecoder(r.Body).Decode(&b)
		if !org(w, r) {
			return
		}
		if b["displayName"] == nil || b["timeZone"] == nil {
			problem(w, 400, "bookings.invalid", "displayName and timeZone are required", nil)
			return
		}
		writeJSON(w, 200, b)
	})
	mux.HandleFunc("GET /v1/providers/{org}/team", func(w http.ResponseWriter, r *http.Request) {
		if !org(w, r) {
			return
		}
		writeJSON(w, 200, []any{map[string]any{"id": "m-1", "orgId": ProviderOrgID, "userId": "someone-else", "displayName": "Ama", "role": "OWNER",
			"bookable": true, "active": true, "hours": map[string]any{"days": map[string]any{"TUESDAY": []map[string]string{{"start": "10:00", "end": "18:00"}}}}}})
	})
	mux.HandleFunc("PUT /v1/providers/{org}/team/{member}/hours", func(w http.ResponseWriter, r *http.Request) {
		var b map[string]any
		_ = json.NewDecoder(r.Body).Decode(&b)
		if !org(w, r) || r.PathValue("member") != "m-1" {
			return
		}
		writeJSON(w, 200, map[string]any{"id": "m-1", "hours": b})
	})
	mux.HandleFunc("GET /v1/providers/{org}/team/{member}/overrides", func(w http.ResponseWriter, _ *http.Request) {
		writeJSON(w, 200, []any{map[string]any{"date": "2026-12-25", "closed": true, "ranges": []any{}, "note": "Christmas"}})
	})
	mux.HandleFunc("PUT /v1/providers/{org}/team/{member}/overrides", func(w http.ResponseWriter, r *http.Request) {
		var b map[string]any
		_ = json.NewDecoder(r.Body).Decode(&b)
		writeJSON(w, 200, b)
	})
	mux.HandleFunc("DELETE /v1/providers/{org}/team/{member}/overrides/{date}", func(w http.ResponseWriter, _ *http.Request) { w.WriteHeader(200) })
	svc := map[string]any{"id": "s1a", "orgId": ProviderOrgID, "name": "Knotless box braids · medium", "description": "Hair included", "category": "hair & beauty",
		"durationMinutes": 300, "priceMinor": 18000, "depositMinor": 4000, "currency": "USD", "modes": []string{"IN_PERSON"}, "active": true,
		"takeHome": map[string]any{"commissionBps": 1000, "commissionMinor": 1800, "cardFeesMinor": 552, "takeHomeMinor": 15648}}
	mux.HandleFunc("GET /v1/providers/{org}/services", func(w http.ResponseWriter, r *http.Request) {
		if org(w, r) {
			writeJSON(w, 200, []any{svc})
		}
	})
	mux.HandleFunc("POST /v1/providers/{org}/services", func(w http.ResponseWriter, r *http.Request) {
		var b map[string]any
		_ = json.NewDecoder(r.Body).Decode(&b)
		if b["modes"] == nil {
			problem(w, 400, "bookings.invalid", "modes are required", nil)
			return
		}
		writeJSON(w, 200, svc)
	})
	mux.HandleFunc("GET /v1/providers/{org}/services/{id}", func(w http.ResponseWriter, _ *http.Request) { writeJSON(w, 200, svc) })
	mux.HandleFunc("PUT /v1/providers/{org}/services/{id}", func(w http.ResponseWriter, _ *http.Request) { writeJSON(w, 200, svc) })
	mux.HandleFunc("POST /v1/providers/{org}/services/{id}/deactivate", func(w http.ResponseWriter, _ *http.Request) {
		off := map[string]any{}
		for k, v := range svc {
			off[k] = v
		}
		off["active"] = false
		writeJSON(w, 200, off)
	})
	mux.HandleFunc("POST /v1/providers/{org}/services/{id}/activate", func(w http.ResponseWriter, _ *http.Request) { writeJSON(w, 200, svc) })
	mux.HandleFunc("PUT /v1/providers/{org}/cancellation-policy", func(w http.ResponseWriter, r *http.Request) {
		var b map[string]any
		_ = json.NewDecoder(r.Body).Decode(&b)
		if org(w, r) {
			writeJSON(w, 200, map[string]any{"orgId": ProviderOrgID, "policy": policy})
		}
	})
	mux.HandleFunc("GET /v1/providers/{org}/earnings", func(w http.ResponseWriter, r *http.Request) {
		if !org(w, r) {
			return
		}
		writeJSON(w, 200, map[string]any{"orgId": ProviderOrgID, "currency": "usd", "depositsOnHoldMinor": 4000, "rows": []map[string]any{
			{"bookingId": "23990", "reference": "BK-23990", "customer": "Chioma Eze", "serviceName": "Fulani braids", "startAt": "2026-10-08T15:00:00Z", "status": "COMPLETED",
				"collectedMinor": 16000, "commissionBps": 1000, "commissionMinor": 1600, "cardFeesMinor": 494, "providerFeeMinor": 0, "netMinor": 13906},
			{"bookingId": "23970", "reference": "BK-23970", "customer": "Ama K.", "serviceName": "Cornrows", "startAt": "2026-10-05T15:00:00Z", "status": "CANCELLED_PROVIDER",
				"collectedMinor": 0, "commissionBps": 1000, "commissionMinor": 0, "cardFeesMinor": 0, "providerFeeMinor": 1500, "netMinor": -1500}}})
	})
}
