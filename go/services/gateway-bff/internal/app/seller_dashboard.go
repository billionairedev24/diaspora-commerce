package app

import (
	"context"
	"fmt"
	"math"
	"sort"
	"sync"
	"time"

	"github.com/billionairedev24/diaspora-commerce/go/services/gateway-bff/internal/domain"
	"github.com/billionairedev24/diaspora-commerce/go/services/gateway-bff/internal/port"
)

// SellerDashboardService builds the home KPIs and charts for sellers (orders analytics, inventory, payouts) and
// providers (bookings analytics, payouts). Every source degrades on its own: a chart whose upstream is down is
// returned with available=false and the rest of the page still renders.
type SellerDashboardService interface {
	Dashboard(ctx context.Context, c *domain.Caller, rangeDays int) (domain.Dashboard, error)
}

type sellerDashboardImpl struct{ r *orgResolver }

var _ SellerDashboardService = (*sellerDashboardImpl)(nil)

// NewSellerDashboardService returns the SellerDashboardService.
func NewSellerDashboardService(d SellerDeps) SellerDashboardService {
	return &sellerDashboardImpl{r: newResolver(d)}
}

func dollars(minor int64) float64 { return math.Round(float64(minor)) / 100 }

// moneyLabel renders minor units as "$1,284.55".
func moneyLabel(minor int64) string {
	neg := minor < 0
	if neg {
		minor = -minor
	}
	whole, cents := minor/100, minor%100
	s := fmt.Sprintf("%d", whole)
	for i := len(s) - 3; i > 0; i -= 3 {
		s = s[:i] + "," + s[i:]
	}
	out := fmt.Sprintf("$%s.%02d", s, cents)
	if neg {
		return "−" + out
	}
	return out
}

func shortDay(iso string) string {
	t, err := time.Parse(time.DateOnly, iso)
	if err != nil {
		return iso
	}
	return t.Format("Jan 2")
}

func (s *sellerDashboardImpl) Dashboard(ctx context.Context, c *domain.Caller, rangeDays int) (domain.Dashboard, error) {
	switch rangeDays {
	case 7, 30, 90:
	case 0:
		rangeDays = 30
	default:
		return domain.Dashboard{}, domain.ValidationProblem(map[string]string{"rangeDays": "Choose 7, 30 or 90 days"})
	}
	sc, err := s.r.require(ctx, c, "", "home", domain.LevelView)
	if err != nil {
		return domain.Dashboard{}, err
	}
	now := s.r.d.Clock.now().In(s.r.d.Location)
	to := time.Date(now.Year(), now.Month(), now.Day(), 0, 0, 0, 0, s.r.d.Location)
	from := to.AddDate(0, 0, -(rangeDays - 1))
	if sc.kind() == domain.OrgKindProvider {
		return s.provider(ctx, c, sc, from, to, rangeDays), nil
	}
	return s.seller(ctx, c, sc, from, to, rangeDays), nil
}

type dashSources struct {
	sales     *port.SalesAnalytics
	positions []domain.StockPosition
	posOK     bool
	balance   *port.PayoutBalanceResponse
	health    *port.AccountHealth
	bookings  *port.BookingsAnalytics
	requests  *int
}

func (s *sellerDashboardImpl) gather(ctx context.Context, c *domain.Caller, sc sellerCtx, from, to time.Time) dashSources {
	var (
		wg  sync.WaitGroup
		mu  sync.Mutex
		src dashSources
		b   = c.BearerToken
	)
	lock := func(f func()) { mu.Lock(); f(); mu.Unlock() }
	if domain.AtLeast(sc.access.Level("payouts"), domain.LevelView) {
		wg.Go(func() {
			if v, err := s.r.d.Payouts.Balance(ctx, b, sc.org.ID); err == nil {
				lock(func() { src.balance = &v })
			}
		})
	}
	if domain.AtLeast(sc.access.Level("account-health"), domain.LevelView) {
		wg.Go(func() {
			if v, err := s.r.d.Sellers.AccountHealth(ctx, b, sc.org.ID); err == nil {
				lock(func() { src.health = &v })
			}
		})
	}
	if sc.kind() == domain.OrgKindProvider {
		wg.Go(func() {
			if v, err := s.r.d.Bookings.Analytics(ctx, b, from, to); err == nil {
				lock(func() { src.bookings = &v })
			}
		})
		wg.Go(func() {
			if v, err := s.r.d.Bookings.Requests(ctx, b); err == nil {
				n := len(v)
				lock(func() { src.requests = &n })
			}
		})
	} else {
		wg.Go(func() {
			if v, err := s.r.d.Orders.Analytics(ctx, b, from, to); err == nil {
				lock(func() { src.sales = &v })
			}
		})
		wg.Go(func() {
			if v, err := s.r.d.Stock.Positions(ctx, b, sc.org.ID, false); err == nil {
				lock(func() { src.positions, src.posOK = v, true })
			}
		})
	}
	wg.Wait()
	return src
}

func healthKpi(h *port.AccountHealth) domain.Kpi {
	if h == nil {
		return domain.Kpi{Label: "Account health", Value: "—", Detail: "Status unavailable", Tone: "neutral"}
	}
	if h.Status == "" || h.Status == domain.ComplianceCompliant {
		return domain.Kpi{Label: "Account health", Value: "Good", Detail: "Nothing outstanding", Tone: "positive"}
	}
	return domain.Kpi{Label: "Account health", Value: "Action needed", Detail: fmt.Sprintf("%d item(s) open", max(h.OpenItems, len(h.Requirements))), Tone: "attention"}
}

func balanceKpi(b *port.PayoutBalanceResponse) domain.Kpi {
	if b == nil {
		return domain.Kpi{Label: "Available to request", Value: "—", Detail: "Balance unavailable", Tone: "neutral"}
	}
	return domain.Kpi{Label: "Available to request", Value: moneyLabel(b.AvailableMinor), Detail: moneyLabel(b.OnHoldMinor) + " still settling", Tone: "positive"}
}

func (s *sellerDashboardImpl) seller(ctx context.Context, c *domain.Caller, sc sellerCtx, from, to time.Time, days int) domain.Dashboard {
	src := s.gather(ctx, c, sc, from, to)
	d := domain.Dashboard{Portal: domain.PortalSeller, RangeDays: days, Kpis: []domain.Kpi{}, Charts: []domain.ChartSpec{}, Todo: []domain.TodoItem{}}
	trendSeries := []domain.ChartSeries{{Key: "sales", Label: "Sales", Tone: "ink", Format: "money"}, {Key: "units", Label: "Units", Tone: "gold", Format: "count"}}
	topSeries := []domain.ChartSeries{{Key: "revenue", Label: "Revenue", Tone: "ink", Format: "money"}}
	ffSeries := []domain.ChartSeries{{Key: "orders", Label: "Orders", Tone: "ink", Format: "count"}}
	stockSeries := []domain.ChartSeries{{Key: "available", Label: "Available", Tone: "green"}, {Key: "reserved", Label: "Reserved", Tone: "blue"}, {Key: "inbound", Label: "Inbound", Tone: "gold"}}
	title := fmt.Sprintf("Sales · last %d days", days)
	if a := src.sales; a != nil {
		var sales int64
		var units int
		trend := domain.ChartSpec{ID: "sales", Title: title, Kind: "trend", CategoryLabel: "Day", ValueFormat: "money", Series: trendSeries, Rows: []domain.ChartRow{}, Available: true}
		for _, day := range a.Days {
			sales += day.SalesMinor
			units += day.Units
			trend.Rows = append(trend.Rows, domain.ChartRow{Category: shortDay(day.Date), Values: map[string]float64{"sales": dollars(day.SalesMinor), "units": float64(day.Units)}})
		}
		top := domain.ChartSpec{ID: "top-products", Title: "Top products by revenue", Kind: "hbar", CategoryLabel: "Product", ValueFormat: "money", Series: topSeries, Rows: []domain.ChartRow{}, Available: true}
		for _, p := range a.TopProducts {
			top.Rows = append(top.Rows, domain.ChartRow{Category: p.Title, Values: map[string]float64{"revenue": dollars(p.RevenueMinor)}})
		}
		ff := domain.ChartSpec{ID: "fulfilment", Title: "Orders by fulfilment", Kind: "donut", CategoryLabel: "Fulfilment", ValueFormat: "count", Series: ffSeries, Rows: []domain.ChartRow{}, Available: true}
		for _, f := range a.ByFulfilment {
			label := map[string]string{"AFROTRANSACT": "Fulfilled by AfroTransact", "SELLER": "Ship yourself", "LOCAL": "Local driver"}[f.Fulfilment]
			if label == "" {
				label = f.Fulfilment
			}
			ff.Rows = append(ff.Rows, domain.ChartRow{Category: label, Values: map[string]float64{"orders": float64(f.Orders)}})
		}
		d.Charts = append(d.Charts, trend, top, ff)
		d.Kpis = append(d.Kpis,
			domain.Kpi{Label: "To ship", Value: fmt.Sprintf("%d", a.ToShip), Detail: "Buy labels and mark ready", Tone: map[bool]string{true: "attention", false: "positive"}[a.ToShip > 0]},
			domain.Kpi{Label: fmt.Sprintf("Sales, %d days", days), Value: moneyLabel(sales), Detail: fmt.Sprintf("%d units", units), Tone: "positive"})
		if a.ToShip > 0 {
			d.Todo = append(d.Todo, domain.TodoItem{Label: fmt.Sprintf("%d orders to ship — buy labels", a.ToShip), Module: "orders", Tone: "info"})
		}
	} else {
		d.Charts = append(d.Charts,
			domain.UnavailableChart("sales", title, "trend", "Day", "money", trendSeries),
			domain.UnavailableChart("top-products", "Top products by revenue", "hbar", "Product", "money", topSeries),
			domain.UnavailableChart("fulfilment", "Orders by fulfilment", "donut", "Fulfilment", "count", ffSeries))
		d.Kpis = append(d.Kpis, domain.Kpi{Label: "To ship", Value: "—", Detail: "Orders unavailable", Tone: "neutral"},
			domain.Kpi{Label: fmt.Sprintf("Sales, %d days", days), Value: "—", Detail: "Orders unavailable", Tone: "neutral"})
	}
	stock := domain.UnavailableChart("stock", "Stock by warehouse", "stack", "Warehouse", "count", stockSeries)
	if src.posOK {
		stock.Available = true
		byNode := map[string]map[string]float64{}
		low := 0
		for _, p := range src.positions {
			v := byNode[p.NodeID]
			if v == nil {
				v = map[string]float64{"available": 0, "reserved": 0, "inbound": 0}
				byNode[p.NodeID] = v
			}
			v["available"] += float64(p.Available)
			v["reserved"] += float64(p.Reserved)
			v["inbound"] += float64(p.Inbound)
			if p.LowStock {
				low++
			}
		}
		nodes := make([]string, 0, len(byNode))
		for n := range byNode {
			nodes = append(nodes, n)
		}
		sort.Strings(nodes)
		for _, n := range nodes {
			stock.Rows = append(stock.Rows, domain.ChartRow{Category: n, Values: byNode[n]})
		}
		if low > 0 {
			d.Todo = append(d.Todo, domain.TodoItem{Label: fmt.Sprintf("%d SKUs low on stock", low), Module: "inventory", Tone: "warning"})
		}
	}
	d.Charts = append(d.Charts, stock)
	if domain.AtLeast(sc.access.Level("payouts"), domain.LevelView) {
		d.Kpis = append(d.Kpis, balanceKpi(src.balance))
	}
	if domain.AtLeast(sc.access.Level("account-health"), domain.LevelView) {
		d.Kpis = append(d.Kpis, healthKpi(src.health))
		if src.health != nil && src.health.Status != "" && src.health.Status != domain.ComplianceCompliant {
			d.Todo = append(d.Todo, domain.TodoItem{Label: "Stripe needs more information — open Account health", Module: "account-health", Tone: "warning"})
		}
	}
	return d
}

func (s *sellerDashboardImpl) provider(ctx context.Context, c *domain.Caller, sc sellerCtx, from, to time.Time, days int) domain.Dashboard {
	src := s.gather(ctx, c, sc, from, to)
	d := domain.Dashboard{Portal: domain.PortalProvider, RangeDays: days, Kpis: []domain.Kpi{}, Charts: []domain.ChartSpec{}, Todo: []domain.TodoItem{}}
	trendSeries := []domain.ChartSeries{{Key: "bookings", Label: "Bookings", Tone: "ink", Format: "count"}, {Key: "deposits", Label: "Deposits ($)", Tone: "gold", Format: "money"}}
	svcSeries := []domain.ChartSeries{{Key: "bookings", Label: "Bookings", Tone: "ink", Format: "count"}}
	statusSeries := []domain.ChartSeries{{Key: "count", Label: "Bookings", Tone: "ink", Format: "count"}}
	weekSeries := []domain.ChartSeries{{Key: "paidOut", Label: "Paid out", Tone: "green", Format: "money"}, {Key: "pending", Label: "Pending", Tone: "gold", Format: "money"}}
	title := fmt.Sprintf("Bookings & deposits · last %d days", days)
	if a := src.bookings; a != nil {
		trend := domain.ChartSpec{ID: "bookings", Title: title, Kind: "trend", CategoryLabel: "Day", ValueFormat: "count", Series: trendSeries, Rows: []domain.ChartRow{}, Available: true}
		total, deposits := 0, int64(0)
		for _, day := range a.Days {
			total += day.Bookings
			deposits += day.DepositMinor
			trend.Rows = append(trend.Rows, domain.ChartRow{Category: shortDay(day.Date), Values: map[string]float64{"bookings": float64(day.Bookings), "deposits": dollars(day.DepositMinor)}})
		}
		svc := domain.ChartSpec{ID: "by-service", Title: "Bookings by service", Kind: "hbar", CategoryLabel: "Service", ValueFormat: "count", Series: svcSeries, Rows: []domain.ChartRow{}, Available: true}
		for _, x := range a.ByService {
			svc.Rows = append(svc.Rows, domain.ChartRow{Category: x.Service, Values: map[string]float64{"bookings": float64(x.Bookings)}})
		}
		st := domain.ChartSpec{ID: "status", Title: "Booking status", Kind: "donut", CategoryLabel: "Status", ValueFormat: "count", Series: statusSeries, Rows: []domain.ChartRow{}, Available: true}
		for _, x := range a.ByStatus {
			st.Rows = append(st.Rows, domain.ChartRow{Category: x.Status, Values: map[string]float64{"count": float64(x.Count)}})
		}
		wk := domain.ChartSpec{ID: "earnings", Title: "Earnings by week", Kind: "stack", CategoryLabel: "Week", ValueFormat: "money", Series: weekSeries, Rows: []domain.ChartRow{}, Available: true}
		for _, x := range a.Weeks {
			wk.Rows = append(wk.Rows, domain.ChartRow{Category: x.Week, Values: map[string]float64{"paidOut": dollars(x.PaidOutMinor), "pending": dollars(x.PendingMinor)}})
		}
		d.Charts = append(d.Charts, trend, svc, st, wk)
		d.Kpis = append(d.Kpis, domain.Kpi{Label: fmt.Sprintf("Bookings, %d days", days), Value: fmt.Sprintf("%d", total), Detail: moneyLabel(deposits) + " in deposits", Tone: "positive"})
	} else {
		d.Charts = append(d.Charts,
			domain.UnavailableChart("bookings", title, "trend", "Day", "count", trendSeries),
			domain.UnavailableChart("by-service", "Bookings by service", "hbar", "Service", "count", svcSeries),
			domain.UnavailableChart("status", "Booking status", "donut", "Status", "count", statusSeries),
			domain.UnavailableChart("earnings", "Earnings by week", "stack", "Week", "money", weekSeries))
		d.Kpis = append(d.Kpis, domain.Kpi{Label: fmt.Sprintf("Bookings, %d days", days), Value: "—", Detail: "Bookings unavailable", Tone: "neutral"})
	}
	if src.requests != nil {
		n := *src.requests
		d.Kpis = append(d.Kpis, domain.Kpi{Label: "Requests", Value: fmt.Sprintf("%d", n), Detail: "Expire after 4 hours", Tone: map[bool]string{true: "attention", false: "positive"}[n > 0]})
		if n > 0 {
			d.Todo = append(d.Todo, domain.TodoItem{Label: fmt.Sprintf("%d booking requests to confirm within 4 h", n), Module: "requests", Tone: "info"})
		}
	}
	if domain.AtLeast(sc.access.Level("payouts"), domain.LevelView) {
		d.Kpis = append(d.Kpis, balanceKpi(src.balance))
	}
	if domain.AtLeast(sc.access.Level("account-health"), domain.LevelView) {
		d.Kpis = append(d.Kpis, healthKpi(src.health))
	}
	return d
}
