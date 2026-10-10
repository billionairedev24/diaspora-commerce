package app

import (
	"context"
	"fmt"
	"net/http"
	"net/url"
	"sort"
	"strconv"
	"sync"
	"time"

	"github.com/billionairedev24/diaspora-commerce/go/services/gateway-bff/internal/domain"
)

// StaffDashboardService serves GET /v1/staff/dashboard: one feed for every KPI and chart of the platform dashboard.
type StaffDashboardService interface {
	Dashboard(ctx context.Context, c *domain.Caller, rangeDays int) (domain.StaffDashboard, error)
}

type staffDashboardImpl struct{ r *staffResolver }

var _ StaffDashboardService = (*staffDashboardImpl)(nil)

// NewStaffDashboardService returns the dashboard use case.
func NewStaffDashboardService(d StaffDeps) StaffDashboardService {
	return &staffDashboardImpl{r: newStaffResolver(d)}
}

// dashboardOrderPages caps how many order pages (100 each) the dashboard reads for one range.
const dashboardOrderPages = 20

var applicationStatuses = []string{"PENDING_REVIEW", "FLAGGED", "AWAITING_FINAL_APPROVAL", "INFO_REQUESTED", "APPROVED", "REJECTED"}

type orderStats struct {
	ok      bool
	sampled bool
	count   int
	gross   int64
	byDay   map[string][2]int64
	byDept  map[string]int
}

func (s *staffDashboardImpl) Dashboard(ctx context.Context, c *domain.Caller, rangeDays int) (domain.StaffDashboard, error) {
	switch rangeDays {
	case 7, 30, 90:
	default:
		return domain.StaffDashboard{}, domain.ValidationProblem(map[string]string{"rangeDays": "Choose 7, 30 or 90 days"})
	}
	sc, err := s.r.require(ctx, c, "dash", domain.LevelView)
	if err != nil {
		return domain.StaffDashboard{}, err
	}
	to := sc.now.UTC()
	from := to.AddDate(0, 0, -rangeDays)
	var (
		wg                   sync.WaitGroup
		orders               orderStats
		apps                 map[string]int64
		appsOK               bool
		payouts, refunds     map[string]int64
		payoutsOK, refundsOK bool
		queued               int64
		queuedN              int
		openClaims           int64
	)
	wg.Add(4)
	go func() { defer wg.Done(); orders = s.orders(ctx, sc, from, to) }()
	go func() { defer wg.Done(); apps, appsOK = s.applications(ctx, sc) }()
	go func() { defer wg.Done(); payouts, queued, queuedN, payoutsOK = s.payouts(ctx, sc, from) }()
	go func() { defer wg.Done(); refunds, openClaims, refundsOK = s.refunds(ctx, sc, from) }()
	wg.Wait()

	d := domain.StaffDashboard{RangeDays: rangeDays, GeneratedAt: sc.now, Kpis: []domain.Kpi{}, Charts: []domain.ChartSpec{}}
	salesSeries := []domain.ChartSeries{{Key: "sales", Label: "Gross sales", Tone: "ink"}, {Key: "net", Label: "Net sales", Tone: "gold"}}
	deptSeries := []domain.ChartSeries{{Key: "orders", Label: "Orders", Tone: "gold"}}
	if orders.ok {
		trend := domain.ChartSpec{ID: "sales", Title: "Sales and net sales", Kind: "trend", CategoryLabel: "Day", ValueFormat: "money", Series: salesSeries, Rows: []domain.ChartRow{}, Available: true}
		for day := from.AddDate(0, 0, 1); !day.After(to); day = day.AddDate(0, 0, 1) {
			k := day.Format(time.DateOnly)
			v := orders.byDay[k]
			trend.Rows = append(trend.Rows, domain.ChartRow{Category: shortDay(k), Values: map[string]float64{"sales": dollars(v[0]), "net": dollars(v[1])}})
		}
		dept := domain.ChartSpec{ID: "departments", Title: "Orders by department", Kind: "hbar", CategoryLabel: "Department", ValueFormat: "count", Series: deptSeries, Rows: []domain.ChartRow{}, Available: true}
		for _, k := range sortedByCount(orders.byDept) {
			dept.Rows = append(dept.Rows, domain.ChartRow{Category: k, Values: map[string]float64{"orders": float64(orders.byDept[k])}})
		}
		d.Charts = append(d.Charts, trend, dept)
		detail := fmt.Sprintf("%d orders in %d days", orders.count, rangeDays)
		if orders.sampled {
			detail += " (first 2,000)"
		}
		d.Kpis = append(d.Kpis, domain.Kpi{Label: "Gross sales", Value: moneyLabel(orders.gross), Detail: detail, Tone: "positive"})
	} else {
		d.Charts = append(d.Charts, domain.UnavailableChart("sales", "Sales and net sales", "trend", "Day", "money", salesSeries),
			domain.UnavailableChart("departments", "Orders by department", "hbar", "Department", "count", deptSeries))
	}
	appSeries := []domain.ChartSeries{{Key: "count", Label: "Applications", Tone: "ink"}}
	if appsOK {
		ch := domain.ChartSpec{ID: "applications", Title: "Applications by status", Kind: "donut", CategoryLabel: "Status", ValueFormat: "count", Series: appSeries, Rows: []domain.ChartRow{}, Available: true}
		for _, st := range applicationStatuses {
			if n := apps[st]; n > 0 {
				ch.Rows = append(ch.Rows, domain.ChartRow{Category: domain.Humanize(st), Values: map[string]float64{"count": float64(n)}})
			}
		}
		d.Charts = append(d.Charts, ch)
		pending := apps["PENDING_REVIEW"] + apps["FLAGGED"] + apps["AWAITING_FINAL_APPROVAL"]
		d.Kpis = append(d.Kpis, domain.Kpi{Label: "Applications to review", Value: strconv.FormatInt(pending, 10), Detail: "Two-step review queue", Tone: toneIf(pending > 0)})
	} else {
		d.Charts = append(d.Charts, domain.UnavailableChart("applications", "Applications by status", "donut", "Status", "count", appSeries))
	}
	moneySeries := []domain.ChartSeries{{Key: "payouts", Label: "Payouts", Tone: "green"}, {Key: "refunds", Label: "Refunds", Tone: "red"}}
	if payoutsOK || refundsOK {
		ch := domain.ChartSpec{ID: "payouts-refunds", Title: "Payouts vs refunds", Kind: "stack", CategoryLabel: "Week", ValueFormat: "money", Series: moneySeries, Rows: []domain.ChartRow{}, Available: true}
		for wk := weekStart(from); !wk.After(to); wk = wk.AddDate(0, 0, 7) {
			k := wk.Format(time.DateOnly)
			ch.Rows = append(ch.Rows, domain.ChartRow{Category: "Wk of " + shortDay(k), Values: map[string]float64{"payouts": dollars(payouts[k]), "refunds": dollars(refunds[k])}})
		}
		d.Charts = append(d.Charts, ch)
	} else {
		d.Charts = append(d.Charts, domain.UnavailableChart("payouts-refunds", "Payouts vs refunds", "stack", "Week", "money", moneySeries))
	}
	if refundsOK {
		d.Kpis = append(d.Kpis, domain.Kpi{Label: "Open claims", Value: strconv.FormatInt(openClaims, 10), Detail: "A-to-z claims awaiting a decision", Tone: toneIf(openClaims > 0)})
	}
	if payoutsOK {
		d.Kpis = append(d.Kpis, domain.Kpi{Label: "Payouts to approve", Value: moneyLabel(queued), Detail: fmt.Sprintf("%d requests", queuedN), Tone: "neutral"})
	}
	return d, nil
}

func toneIf(attention bool) string {
	if attention {
		return "attention"
	}
	return "neutral"
}

func weekStart(t time.Time) time.Time {
	t = time.Date(t.Year(), t.Month(), t.Day(), 0, 0, 0, 0, time.UTC)
	return t.AddDate(0, 0, -((int(t.Weekday()) + 6) % 7))
}

func sortedByCount(m map[string]int) []string {
	keys := make([]string, 0, len(m))
	for k := range m {
		keys = append(keys, k)
	}
	sort.Slice(keys, func(i, j int) bool {
		if m[keys[i]] != m[keys[j]] {
			return m[keys[i]] > m[keys[j]]
		}
		return keys[i] < keys[j]
	})
	return keys
}

// orders pages through orders `GET /v1/admin/orders` in the range (up to 2,000 orders).
func (s *staffDashboardImpl) orders(ctx context.Context, sc staffCtx, from, to time.Time) orderStats {
	st := orderStats{byDay: map[string][2]int64{}, byDept: map[string]int{}}
	if !sc.access.Can("orders:read") {
		return st
	}
	cursor := ""
	for page := 0; page < dashboardOrderPages; page++ {
		q := url.Values{"from": {from.Format(time.RFC3339)}, "to": {to.Format(time.RFC3339)}, "limit": {"100"}}
		if cursor != "" {
			q.Set("cursor", cursor)
		}
		var raw any
		if err := sc.get(ctx, sc.up.Orders, "/v1/admin/orders", q, &raw); err != nil {
			s.r.d.Log.DebugContext(ctx, "dashboard orders unavailable", "error", err.Error())
			return st
		}
		pg := jpage(raw)
		for _, o := range pg.items {
			if jstr(o, "status") == "CANCELLED" {
				continue
			}
			day := timeOf(o, "placedAt").UTC().Format(time.DateOnly)
			total, net := int64(num(o, "totalMinor")), int64(num(o, "itemsMinor")-num(o, "discountMinor"))
			v := st.byDay[day]
			st.byDay[day] = [2]int64{v[0] + total, v[1] + net}
			st.gross += total
			st.count++
			seen := map[string]bool{}
			for _, sh := range objs(o, "shipments") {
				for _, l := range objs(sh, "lines") {
					if cat := firstNonEmpty(jstr(l, "category"), "Other"); !seen[cat] {
						seen[cat] = true
						st.byDept[domain.Humanize(cat)]++
					}
				}
			}
		}
		if pg.next == nil {
			st.ok = true
			return st
		}
		cursor = *pg.next
	}
	st.ok, st.sampled = true, true
	return st
}

// applications counts seller `GET /v1/applications` per status (size=1, read `total`).
func (s *staffDashboardImpl) applications(ctx context.Context, sc staffCtx) (map[string]int64, bool) {
	out := map[string]int64{}
	if !sc.access.CanAny("applications:review", "applications:approve", "orgs:read") {
		return out, false
	}
	var mu sync.Mutex
	var wg sync.WaitGroup
	failed := false
	for _, st := range applicationStatuses {
		wg.Add(1)
		go func() {
			defer wg.Done()
			var page struct {
				Total int64 `json:"total"`
			}
			err := sc.get(ctx, sc.up.Seller, "/v1/applications", url.Values{"status": {st}, "size": {"1"}}, &page)
			mu.Lock()
			defer mu.Unlock()
			if err != nil {
				failed = true
				return
			}
			out[st] = page.Total
		}()
	}
	wg.Wait()
	return out, !failed
}

// payouts sums payments `GET /v1/payouts/requests` by week and the requests waiting for approval.
func (s *staffDashboardImpl) payouts(ctx context.Context, sc staffCtx, from time.Time) (map[string]int64, int64, int, bool) {
	byWeek := map[string]int64{}
	if !sc.access.CanAny("payouts:read", "payouts:approve") {
		return byWeek, 0, 0, false
	}
	var list []jobj
	if err := sc.get(ctx, sc.up.Payments, "/v1/payouts/requests", url.Values{"limit": {"500"}}, &list); err != nil {
		return byWeek, 0, 0, false
	}
	var queued int64
	n := 0
	for _, p := range list {
		amt := int64(num(p, "amountMinor"))
		if jstr(p, "status") == "REQUESTED" {
			queued += amt
			n++
		}
		at := timeOf(p, "requestedAt")
		if at.Before(from) || jstr(p, "status") == "FAILED" {
			continue
		}
		byWeek[weekStart(at).Format(time.DateOnly)] += amt
	}
	return byWeek, queued, n, true
}

// refunds sums trust-ops claims resolved with a refund by week, and counts open claims.
func (s *staffDashboardImpl) refunds(ctx context.Context, sc staffCtx, from time.Time) (map[string]int64, int64, bool) {
	byWeek := map[string]int64{}
	if !sc.access.Can("disputes:manage") {
		return byWeek, 0, false
	}
	var resolved, open struct {
		Items []jobj `json:"items"`
		Total int64  `json:"total"`
	}
	if err := sc.get(ctx, sc.up.TrustOps, "/v1/claims", url.Values{"status": {"RESOLVED_REFUND"}, "size": {"200"}}, &resolved); err != nil {
		return byWeek, 0, false
	}
	for _, c := range resolved.Items {
		at := timeOf(c, "decidedAt")
		if at.Before(from) {
			continue
		}
		byWeek[weekStart(at).Format(time.DateOnly)] += int64(num(c, "refundMinor"))
	}
	if err := sc.get(ctx, sc.up.TrustOps, "/v1/claims", url.Values{"size": {"1"}}, &open); err != nil {
		var p *domain.Problem
		if pp, ok := domain.AsProblem(err); ok {
			p = pp
		}
		if p == nil || p.Status != http.StatusForbidden {
			s.r.d.Log.DebugContext(ctx, "open claims count unavailable", "error", err.Error())
		}
	}
	return byWeek, open.Total, true
}
