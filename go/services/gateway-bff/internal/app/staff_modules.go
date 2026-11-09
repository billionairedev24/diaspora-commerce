package app

import (
	"context"
	"fmt"
	"net/url"
	"sync"
	"time"

	"github.com/billionairedev24/diaspora-commerce/go/services/gateway-bff/internal/domain"
)

// Module summaries of the staff console home. Each module needs the module in the staff member's matrix and the
// permission its upstream reads need; a queue whose upstream cannot answer is omitted from the module (never guessed).

// staffQueues are the queue sizes the home reads besides the dashboard's own feeds. A nil count means "could not read".
type staffQueues struct {
	support        *int64
	categoryReqs   *int64
	deptReqs       *int64
	moderation     *int64
	reconciliation *int64
	oldestApp      *time.Time
	appsWaiting    int64
}

func (s *staffDashboardImpl) queues(ctx context.Context, sc staffCtx) staffQueues {
	var q staffQueues
	var wg sync.WaitGroup
	var mu sync.Mutex
	set := func(dst **int64, n int64) { mu.Lock(); *dst = &n; mu.Unlock() }
	count := func(raw any) int64 {
		switch v := raw.(type) {
		case []any:
			return int64(len(v))
		case map[string]any:
			if t, ok := v["total"].(float64); ok {
				return int64(t)
			}
			return int64(len(jpage(raw).items))
		}
		return 0
	}
	read := func(dst **int64, ok bool, up func() (any, error)) {
		if !ok {
			return
		}
		wg.Go(func() {
			if raw, err := up(); err == nil {
				set(dst, count(raw))
			}
		})
	}
	read(&q.support, sc.access.Can("support:respond") && sc.access.Allows("support", domain.LevelView), func() (any, error) {
		var raw any
		err := sc.get(ctx, sc.up.TrustOps, "/v1/support-cases", url.Values{"status": {"OPEN"}, "size": {"1"}}, &raw)
		return raw, err
	})
	read(&q.categoryReqs, sc.access.Allows("catalog", domain.LevelView) && sc.up.Catalog != nil, func() (any, error) {
		var raw any
		err := sc.get(ctx, sc.up.Catalog, "/v1/category-requests", url.Values{"status": {"PENDING_REVIEW"}}, &raw)
		return raw, err
	})
	read(&q.deptReqs, sc.access.Can("applications:review") && sc.access.Allows(domain.DepartmentRequestsModule, domain.LevelView), func() (any, error) {
		var raw any
		err := sc.get(ctx, sc.up.Seller, "/v1/department-requests", url.Values{"status": {domain.DeptRequestPending}}, &raw)
		return raw, err
	})
	read(&q.moderation, sc.access.Allows("catalog", domain.LevelView) && sc.up.TrustOps != nil, func() (any, error) {
		var raw any
		err := sc.get(ctx, sc.up.TrustOps, "/v1/moderation/items", url.Values{"status": {"PENDING"}, "size": {"1"}}, &raw)
		return raw, err
	})
	read(&q.reconciliation, sc.access.CanAny("payouts:read", "payouts:approve") && sc.access.Allows("payouts", domain.LevelView), func() (any, error) {
		var raw any
		err := sc.get(ctx, sc.up.Payments, "/v1/reconciliation/items", url.Values{"status": {"OPEN"}, "limit": {"500"}}, &raw)
		return raw, err
	})
	// Oldest application still waiting for a first review: only trusted when the page holds every waiting application.
	if sc.access.CanAny("applications:review", "applications:approve", "orgs:read") && sc.access.Allows("apps", domain.LevelView) {
		wg.Go(func() {
			var page struct {
				Items []jobj `json:"items"`
				Total int64  `json:"total"`
			}
			if err := sc.get(ctx, sc.up.Seller, "/v1/applications", url.Values{"status": {"PENDING_REVIEW"}, "size": {"100"}}, &page); err != nil {
				return
			}
			mu.Lock()
			defer mu.Unlock()
			q.appsWaiting = page.Total
			if int64(len(page.Items)) < page.Total {
				return
			}
			for _, a := range page.Items {
				if t := timeOf(a, "submittedAt"); !t.IsZero() && (q.oldestApp == nil || t.Before(*q.oldestApp)) {
					t := t
					q.oldestApp = &t
				}
			}
		})
	}
	wg.Wait()
	return q
}

type staffFigures struct {
	orders     orderStats
	apps       map[string]int64
	appsOK     bool
	payouts    map[string]int64
	payoutsOK  bool
	queued     int64
	queuedN    int
	refunds    map[string]int64
	refundsOK  bool
	openClaims int64
	q          staffQueues
}

func staffActions(sc staffCtx, items ...[3]string) []domain.ModuleAction {
	out := []domain.ModuleAction{}
	for _, it := range items {
		if it[0] == "" || sc.access.Allows(it[0], domain.LevelView) {
			out = append(out, moduleAction(it[1], it[2]))
		}
	}
	if len(out) > 3 {
		out = out[:3]
	}
	return out
}

func ageLabel(from, now time.Time) string {
	d := int(now.Sub(from).Hours() / 24)
	switch {
	case d < 1:
		return "today"
	case d == 1:
		return "1 day ago"
	}
	return fmt.Sprintf("%d days ago", d)
}

func (s *staffDashboardImpl) modules(ctx context.Context, sc staffCtx, days int, from, to time.Time, f staffFigures) []domain.ModuleSummary {
	mods := []domain.ModuleSummary{}
	add := func(m domain.ModuleSummary) { mods = append(mods, m) }
	q := f.q

	// Orders & shipments.
	if sc.access.Can("orders:read") && sc.access.Allows("orders", domain.LevelView) {
		o := f.orders
		switch {
		case !o.ok:
			add(unavailableModule("orders", "orders", "Orders & shipments"))
		case o.count == 0:
			add(emptyModule("orders", "orders", "Orders & shipments", "inbox", "Orders show up here as soon as shoppers check out.", moduleAction("Open orders", "/orders")))
		default:
			m := readyModule("orders", "orders", "Orders & shipments")
			m.Headline = headline(fmt.Sprintf("%d", o.count), "orders", lastDays(days), "positive")
			pts := []domain.ModulePoint{}
			for day := from.AddDate(0, 0, 1); !day.After(to); day = day.AddDate(0, 0, 1) {
				pts = append(pts, domain.ModulePoint{Label: shortDay(day.Format(time.DateOnly)), Value: float64(o.perDay[day.Format(time.DateOnly)])})
			}
			m.Chart = chartOf("sparkline", fmt.Sprintf("Orders per day, last %d days", days), "count", pts)
			line := moneyLabel(o.gross) + " gross sales"
			if o.sampled {
				line += " (first 2,000 orders)"
			}
			m.TotalLine = &line
			m.Actions = staffActions(sc, [3]string{"orders", "All orders", "/orders"}, [3]string{"dispatch", "Dispatch", "/dispatch"}, [3]string{"exports", "Exports", "/exports"})
			add(m)
		}
	}

	// Applications.
	if sc.access.CanAny("applications:review", "applications:approve", "orgs:read") && sc.access.Allows("apps", domain.LevelView) {
		switch {
		case !f.appsOK:
			add(unavailableModule("applications", "applications", "Applications"))
		default:
			var total int64
			for _, n := range f.apps {
				total += n
			}
			waiting := f.apps["PENDING_REVIEW"] + f.apps["FLAGGED"] + f.apps["AWAITING_FINAL_APPROVAL"]
			if total == 0 {
				add(emptyModule("applications", "applications", "Applications", "inbox", "New seller and provider applications will appear here for review.", moduleAction("Open applications", "/applications")))
				break
			}
			m := readyModule("applications", "applications", "Applications")
			if waiting == 0 {
				m.Headline = headline("Queue clear", "nothing waiting for review", nil, "positive")
			} else {
				m.Headline = headline(fmt.Sprintf("%d", waiting), "waiting for review", nil, "attention")
			}
			tones := map[string]string{"PENDING_REVIEW": "gold", "FLAGGED": "red", "AWAITING_FINAL_APPROVAL": "blue", "INFO_REQUESTED": "grey", "APPROVED": "green", "REJECTED": "ink"}
			pts := []domain.ModulePoint{}
			for _, st := range applicationStatuses {
				if n := f.apps[st]; n > 0 {
					pts = append(pts, domain.ModulePoint{Label: domain.Humanize(st), Value: float64(n), Tone: tones[st]})
				}
			}
			m.Chart = chartOf("donut", "Applications by status", "count", pts)
			line := counted(int(total), "application", "applications") + " in total"
			if q.oldestApp != nil {
				line = "Oldest waiting since " + ageLabel(*q.oldestApp, sc.now) + " · " + line
			}
			m.TotalLine = &line
			m.Actions = staffActions(sc, [3]string{"apps", "Review queue", "/applications?status=PENDING_REVIEW"}, [3]string{"apps", "Flagged", "/applications?status=FLAGGED"},
				[3]string{domain.DepartmentRequestsModule, "Department requests", "/department-requests"})
			add(m)
		}
	}

	// Payments & payouts.
	if sc.access.CanAny("payouts:read", "payouts:approve") && sc.access.Allows("payouts", domain.LevelView) {
		recon := int64(0)
		if q.reconciliation != nil {
			recon = *q.reconciliation
		}
		switch {
		case !f.payoutsOK:
			add(unavailableModule("payments", "payments", "Payments & payouts"))
		case f.queuedN == 0 && recon == 0 && len(f.payouts) == 0:
			add(emptyModule("payments", "payments", "Payments & payouts", "payout", "Payout requests and ledger differences will show up here.", moduleAction("Open payouts", "/payouts")))
		default:
			m := readyModule("payments", "payments", "Payments & payouts")
			if f.queuedN == 0 {
				m.Headline = headline("None waiting", "no payouts to approve", nil, "positive")
			} else {
				m.Headline = headline(fmt.Sprintf("%d", f.queuedN), "payouts to approve", nil, "attention")
			}
			pts := []domain.ModulePoint{}
			for wk := weekStart(from); !wk.After(to); wk = wk.AddDate(0, 0, 7) {
				pts = append(pts, domain.ModulePoint{Label: "Wk of " + shortDay(wk.Format(time.DateOnly)), Value: dollars(f.payouts[wk.Format(time.DateOnly)])})
			}
			m.Chart = chartOf("bars", fmt.Sprintf("Payouts requested per week, last %d days", days), "money", pts)
			line := moneyLabel(f.queued) + " waiting"
			if q.reconciliation != nil {
				line += " · " + counted(int(recon), "open difference", "open differences")
			}
			m.TotalLine = &line
			m.Actions = staffActions(sc, [3]string{"payouts", "Payouts to approve", "/payouts?tab=payouts&payouts.status=REQUESTED"},
				[3]string{"payouts", "Open differences", "/payouts?tab=reconciliation&reconciliation.status=OPEN"}, [3]string{"fees", "Fees", "/fees"})
			add(m)
		}
	}

	// Trust & safety.
	if sc.access.Can("disputes:manage") && sc.access.Allows("disputes", domain.LevelView) {
		switch {
		case !f.refundsOK:
			add(unavailableModule("trust", "trust", "Trust & safety"))
		default:
			var refunded int64
			pts := []domain.ModulePoint{}
			for wk := weekStart(from); !wk.After(to); wk = wk.AddDate(0, 0, 7) {
				v := f.refunds[wk.Format(time.DateOnly)]
				refunded += v
				pts = append(pts, domain.ModulePoint{Label: "Wk of " + shortDay(wk.Format(time.DateOnly)), Value: dollars(v)})
			}
			if f.openClaims == 0 && refunded == 0 {
				add(emptyModule("trust", "trust", "Trust & safety", "inbox", "Claims and returns from shoppers will show up here.", moduleAction("Open claims", "/disputes")))
				break
			}
			m := readyModule("trust", "trust", "Trust & safety")
			if f.openClaims == 0 {
				m.Headline = headline("None open", "no claims under review", nil, "positive")
			} else {
				m.Headline = headline(fmt.Sprintf("%d", f.openClaims), "claims under review", nil, "attention")
			}
			m.Chart = chartOf("bars", fmt.Sprintf("Claim refunds per week, last %d days", days), "money", pts)
			m.TotalLine = strPtr(moneyLabel(refunded) + " refunded through claims")
			m.Actions = staffActions(sc, [3]string{"disputes", "Claims", "/disputes?tab=claims"}, [3]string{"disputes", "Returns", "/disputes?tab=returns"})
			add(m)
		}
	}

	// Support.
	if sc.access.Can("support:respond") && sc.access.Allows("support", domain.LevelView) && q.support != nil {
		if *q.support == 0 {
			add(emptyModule("support", "support", "Support", "inbox", "Open support tickets will appear here.", moduleAction("Open support", "/support")))
		} else {
			m := readyModule("support", "support", "Support")
			m.Headline = headline(fmt.Sprintf("%d", *q.support), "open tickets", nil, "attention")
			m.TotalLine = strPtr("Waiting for a first answer or a resolution")
			m.Actions = staffActions(sc, [3]string{"support", "Open tickets", "/support"})
			add(m)
		}
	}

	// Catalog.
	if sc.access.Allows("catalog", domain.LevelView) && (q.categoryReqs != nil || q.moderation != nil || q.deptReqs != nil) {
		pts := []domain.ModulePoint{}
		var total int64
		for _, qq := range []struct {
			label string
			n     *int64
		}{{"Category requests", q.categoryReqs}, {"Department requests", q.deptReqs}, {"Moderation queue", q.moderation}} {
			if qq.n != nil {
				pts = append(pts, domain.ModulePoint{Label: qq.label, Value: float64(*qq.n)})
				total += *qq.n
			}
		}
		if total == 0 {
			add(emptyModule("catalog", "catalog", "Catalog", "listings", "Category requests and listings to moderate will show up here.", moduleAction("Open catalog", "/moderation")))
		} else {
			m := readyModule("catalog", "catalog", "Catalog")
			m.Headline = headline(fmt.Sprintf("%d", total), "waiting for a decision", nil, "attention")
			m.Chart = chartOf("bars", "Catalog queues waiting", "count", pts)
			m.TotalLine = strPtr("Category requests, department requests and moderation")
			m.Actions = staffActions(sc, [3]string{"catalog", "Category requests", "/moderation?tab=category-requests&category-requests.status=PENDING_REVIEW"},
				[3]string{"catalog", "Moderation queue", "/moderation?tab=moderation"}, [3]string{domain.DepartmentRequestsModule, "Department requests", "/department-requests"})
			add(m)
		}
	}

	// Platform health: the maintenance switch. Failed jobs and dead-letter queues are not exposed by any service yet.
	if sc.maint != nil && sc.access.Allows("siteCfg", domain.LevelView) {
		mt := sc.maint.Current(ctx)
		m := readyModule("platform", "platform", "Platform health")
		if mt.Enabled {
			m.Headline = headline("Maintenance on", "the platform is closed to "+map[bool]string{true: "shoppers and sellers", false: "shoppers"}[mt.Scope == domain.MaintenanceAll], nil, "attention")
			if mt.ExpectedBackAt != nil {
				m.TotalLine = strPtr("Expected back " + mt.ExpectedBackAt.Format("Jan 2, 3:04 PM MST"))
			}
		} else {
			m.Headline = headline("Open", "no maintenance mode", nil, "positive")
			if !mt.UpdatedAt.IsZero() {
				m.TotalLine = strPtr("Last switched " + mt.UpdatedAt.Format("Jan 2, 2006"))
			} else {
				m.TotalLine = strPtr("Normal operation")
			}
		}
		m.Actions = staffActions(sc, [3]string{"siteCfg", "Maintenance mode", "/site-content?tab=maintenance"}, [3]string{"audit", "Audit log", "/audit"})
		add(m)
	}
	return mods
}
