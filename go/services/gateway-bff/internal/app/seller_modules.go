package app

import (
	"context"
	"fmt"
	"sort"
	"strings"
	"sync"
	"time"

	"github.com/billionairedev24/diaspora-commerce/go/services/gateway-bff/internal/domain"
	"github.com/billionairedev24/diaspora-commerce/go/services/gateway-bff/internal/port"
)

// Module summaries of the Seller Center and Provider Hub home. Each module is limited to what the member's role and the
// organisation's plan open (the access matrix), built only from figures an upstream returned, and left out or shown
// empty when it cannot be computed.

// listingCounts is catalog's product totals by status (size=1 pages, `total` only).
type listingCounts struct{ all, live, review int64 }

// reviewSample is the newest page of reviews (up to 100) and the count still waiting for a reply.
type reviewSample struct {
	total, unanswered int64
	items             []port.UpSellerReview
}

func (s *sellerDashboardImpl) gatherModules(ctx context.Context, c *domain.Caller, sc sellerCtx, src *dashSources, from, to time.Time) {
	b := c.BearerToken
	can := func(module string) bool { return domain.AtLeast(sc.access.Level(module), domain.LevelView) }
	var wg sync.WaitGroup
	run := func(f func()) { wg.Go(f) }
	if sc.kind() == domain.OrgKindProvider {
		if can("calendar") && s.r.d.Bookings != nil {
			now := s.r.d.Clock.now().In(s.r.d.Location)
			end := time.Date(now.Year(), now.Month()+1, 1, 0, 0, 0, 0, s.r.d.Location)
			start := time.Date(now.Year(), now.Month(), 1, 0, 0, 0, 0, s.r.d.Location)
			run(func() {
				if v, err := s.r.d.Bookings.Calendar(ctx, b, sc.provider(), start, end); err == nil {
					src.mu.Lock()
					src.calendar = &v
					src.mu.Unlock()
				}
			})
		}
	} else {
		if can("listings") && s.r.d.Catalog != nil {
			run(func() {
				lc := listingCounts{}
				ok := true
				for status, dst := range map[string]*int64{"": &lc.all, "LIVE": &lc.live, "IN_REVIEW": &lc.review} {
					p, err := s.r.d.Catalog.Products(ctx, b, port.ProductQuery{Status: status, Size: 1})
					if err != nil {
						ok = false
						break
					}
					*dst = p.Total
				}
				if ok {
					src.mu.Lock()
					src.listings = &lc
					src.mu.Unlock()
				}
			})
		}
		if can("reviews") && s.r.d.Reviews != nil {
			run(func() {
				all, err := s.r.d.Reviews.SellerList(ctx, b, false, 0, 100)
				if err != nil {
					return
				}
				rs := reviewSample{total: all.Total, items: all.Items}
				if un, err := s.r.d.Reviews.SellerList(ctx, b, true, 0, 1); err == nil {
					rs.unanswered = un.Total
				}
				src.mu.Lock()
				src.reviews = &rs
				src.mu.Unlock()
			})
		}
		if can("coupons") && s.r.d.Offers != nil {
			run(func() {
				if v, err := s.r.d.Offers.ListCoupons(ctx, b); err == nil {
					src.mu.Lock()
					src.coupons, src.couponsOK = v, true
					src.mu.Unlock()
				}
			})
		}
	}
	wg.Wait()
}

// links keeps only the quick actions whose module the member may open (at least View; a "+" prefix on the module means
// Edit, for actions that create something), at most three.
func (sc sellerCtx) links(items ...[3]string) []domain.ModuleAction {
	out := []domain.ModuleAction{}
	for _, it := range items {
		module, level := it[0], domain.LevelView
		if strings.HasPrefix(module, "+") {
			module, level = module[1:], domain.LevelEdit
		}
		if module == "" || domain.AtLeast(sc.access.Level(module), level) {
			out = append(out, moduleAction(it[1], it[2]))
		}
	}
	if len(out) > 3 {
		out = out[:3]
	}
	return out
}

func (sc sellerCtx) can(module string) bool {
	return domain.AtLeast(sc.access.Level(module), domain.LevelView)
}

func (s *sellerDashboardImpl) sellerModules(sc sellerCtx, src *dashSources, days int) []domain.ModuleSummary {
	mods := []domain.ModuleSummary{}
	add := func(m domain.ModuleSummary) { mods = append(mods, m) }
	addListing := moduleAction("Add your first product", "/listings/new")
	if sc.can("orders") {
		a := src.sales
		switch {
		case a == nil:
			add(unavailableModule("sales", "sales", "Sales"))
		case a.Totals.Orders == 0 && a.ToShip == 0 && salesTotal(a) == 0:
			add(emptyModule("sales", "sales", "Sales", "chart", "Your sales will show up here after your first order.", addListing))
		default:
			var sales int64
			units := 0
			pts := []domain.ModulePoint{}
			for _, d := range a.Days {
				sales += d.SalesMinor
				units += d.Units
				pts = append(pts, domain.ModulePoint{Label: shortDay(d.Date), Value: dollars(d.SalesMinor)})
			}
			m := readyModule("sales", "sales", "Sales")
			m.Headline = headline(moneyLabel(sales), "in sales", lastDays(days), "positive")
			m.Chart = chartOf("sparkline", fmt.Sprintf("Sales per day, last %d days", days), "money", pts)
			m.TotalLine = strPtr(fmt.Sprintf("%s · %s", counted(a.Totals.Orders, "order", "orders"), counted(units, "unit", "units")))
			m.Actions = sc.links([3]string{"orders", "All orders", "/orders"}, [3]string{"+listings", "Add product", "/listings/new"}, [3]string{"payouts", "Payouts", "/payouts"})
			add(m)
		}
		if a != nil {
			add(s.ordersModule(sc, a, days))
		}
	}
	if sc.can("listings") && src.listings != nil {
		lc := src.listings
		if lc.all == 0 {
			add(emptyModule("listings", "listings", "Listings", "listings", "Add your first product to start selling.", addListing))
		} else {
			other := lc.all - lc.live - lc.review
			m := readyModule("listings", "listings", "Listings")
			m.Headline = headline(fmt.Sprintf("%d", lc.live), "live now", nil, "neutral")
			pts := []domain.ModulePoint{{Label: "Live", Value: float64(lc.live), Tone: "green"}, {Label: "In review", Value: float64(lc.review), Tone: "gold"}, {Label: "Paused or other", Value: float64(max(other, 0)), Tone: "grey"}}
			m.Chart = chartOf("donut", "Listings by status", "count", pts)
			m.TotalLine = strPtr(fmt.Sprintf("%s in total · %d in review", counted(int(lc.all), "listing", "listings"), lc.review))
			m.Actions = sc.links([3]string{"listings", "All listings", "/listings"}, [3]string{"+listings", "Add product", "/listings/new"}, [3]string{"+listings", "Bulk upload", "/listings/bulk"})
			add(m)
		}
	}
	if sc.can("payouts") {
		add(payoutsModule(sc, src.balance))
	}
	if sc.can("inventory") && src.posOK {
		add(inventoryModule(sc, src.positions))
	}
	if sc.can("reviews") && src.reviews != nil {
		add(reviewsModule(sc, src.reviews))
	}
	if sc.can("account-health") {
		add(healthModule(sc, src.health))
	}
	if sc.can("coupons") && src.couponsOK {
		add(promotionsModule(sc, src.coupons))
	}
	return mods
}

func salesTotal(a *port.SalesAnalytics) int64 {
	var t int64
	for _, d := range a.Days {
		t += d.SalesMinor
	}
	return t
}

func (s *sellerDashboardImpl) ordersModule(sc sellerCtx, a *port.SalesAnalytics, days int) domain.ModuleSummary {
	if a.Totals.Orders == 0 && a.ToShip == 0 {
		return emptyModule("orders", "orders", "Orders", "inbox", "Orders from shoppers will appear here as soon as they buy.", moduleAction("See your listings", "/listings"))
	}
	m := readyModule("orders", "orders", "Orders")
	m.Headline = headline(fmt.Sprintf("%d", a.ToShip), "to ship now", nil, toneAttention(a.ToShip > 0))
	pts := []domain.ModulePoint{}
	tones := map[string]string{"AFROTRANSACT": "green", "SELLER": "ink", "LOCAL": "gold"}
	for _, f := range a.ByFulfilment {
		label := map[string]string{"AFROTRANSACT": "Fulfilled by AfroTransact", "SELLER": "Ship yourself", "LOCAL": "Local driver"}[f.Fulfilment]
		if label == "" {
			label = f.Fulfilment
		}
		pts = append(pts, domain.ModulePoint{Label: label, Value: float64(f.Orders), Tone: tones[f.Fulfilment]})
	}
	if len(pts) > 0 {
		m.Chart = chartOf("donut", fmt.Sprintf("Orders by fulfilment, last %d days", days), "count", pts)
	}
	m.TotalLine = strPtr(fmt.Sprintf("%s in the last %d days", counted(a.Totals.Orders, "order", "orders"), days))
	m.Actions = sc.links([3]string{"orders", "To ship", "/orders"}, [3]string{"returns", "Returns", "/returns"}, [3]string{"orders", "Ship-from address", "/orders/ship-from"})
	return m
}

func payoutsModule(sc sellerCtx, b *port.PayoutBalanceResponse) domain.ModuleSummary {
	if b == nil {
		return unavailableModule("payouts", "payouts", "Payouts")
	}
	if b.AvailableMinor == 0 && b.OnHoldMinor == 0 && b.PaidMinor == 0 && b.RequestedOrInTransitMinor == 0 {
		return emptyModule("payouts", "payouts", "Payouts", "payout", "Your earnings and payouts will appear here after your first sale.", moduleAction("Open payouts", "/payouts"))
	}
	m := readyModule("payouts", "payouts", "Payouts")
	m.Headline = headline(moneyLabel(b.AvailableMinor), "available to request", nil, "positive")
	line := moneyLabel(b.OnHoldMinor) + " still settling"
	if next := earliestRelease(b); next != "" {
		line += " · next release " + next
	}
	if b.PayoutsPaused {
		line = "Payouts are paused · " + line
		m.Headline.Tone = "attention"
	}
	m.TotalLine = &line
	m.Actions = sc.links([3]string{"payouts", "Payouts", "/payouts"}, [3]string{"earnings", "Earnings", "/earnings"}, [3]string{"fees", "Fees", "/fees"})
	return m
}

// earliestRelease is the first date money on hold becomes available ("Nov 12"), empty when nothing is held.
func earliestRelease(b *port.PayoutBalanceResponse) string {
	var first time.Time
	for _, h := range b.OnHold {
		t, err := time.Parse(time.RFC3339, h.ReleaseAt)
		if err != nil {
			if t, err = time.Parse(time.DateOnly, h.ReleaseAt); err != nil {
				continue
			}
		}
		if first.IsZero() || t.Before(first) {
			first = t
		}
	}
	if first.IsZero() {
		return ""
	}
	return first.Format("Jan 2")
}

func inventoryModule(sc sellerCtx, positions []domain.StockPosition) domain.ModuleSummary {
	if len(positions) == 0 {
		return emptyModule("inventory", "inventory", "Inventory", "listings", "Stock you send to an AfroTransact warehouse shows up here.", moduleAction("Open inventory", "/inventory"))
	}
	low, units := 0, 0
	skus := map[string]bool{}
	byNode := map[string]float64{}
	for _, p := range positions {
		skus[p.SKU] = true
		units += p.Available
		byNode[p.NodeID] += float64(p.Available)
		if p.LowStock {
			low++
		}
	}
	m := readyModule("inventory", "inventory", "Inventory")
	if low > 0 {
		m.Headline = headline(fmt.Sprintf("%d", low), "SKUs low on stock", nil, "attention")
	} else {
		m.Headline = headline("All stocked", "no SKU is low on stock", nil, "positive")
	}
	nodes := make([]string, 0, len(byNode))
	for n := range byNode {
		nodes = append(nodes, n)
	}
	sort.Strings(nodes)
	pts := []domain.ModulePoint{}
	for _, n := range nodes {
		pts = append(pts, domain.ModulePoint{Label: n, Value: byNode[n]})
	}
	m.Chart = chartOf("bars", "Units available by warehouse", "count", pts)
	m.TotalLine = strPtr(fmt.Sprintf("%s available across %s", counted(units, "unit", "units"), counted(len(skus), "SKU", "SKUs")))
	m.Actions = sc.links([3]string{"inventory", "Inventory", "/inventory"}, [3]string{"listings", "Listings", "/listings"})
	return m
}

func reviewsModule(sc sellerCtx, r *reviewSample) domain.ModuleSummary {
	if r.total == 0 || len(r.items) == 0 {
		return emptyModule("reviews", "reviews", "Reviews", "star", "Verified buyers’ reviews will appear here after your first deliveries.", moduleAction("Open reviews", "/reviews"))
	}
	sum := 0
	stars := [6]int{}
	for _, it := range r.items {
		sum += it.Rating
		if it.Rating >= 1 && it.Rating <= 5 {
			stars[it.Rating]++
		}
	}
	avg := float64(sum) / float64(len(r.items))
	m := readyModule("reviews", "reviews", "Reviews")
	m.Headline = headline(fmt.Sprintf("%.1f", avg), "average rating out of 5", nil, "neutral")
	pts := []domain.ModulePoint{}
	for s := 5; s >= 1; s-- {
		pts = append(pts, domain.ModulePoint{Label: counted(s, "star", "stars"), Value: float64(stars[s])})
	}
	m.Chart = chartOf("bars", "Reviews by star rating", "count", pts)
	line := counted(int(r.total), "review", "reviews")
	if int64(len(r.items)) < r.total {
		line = fmt.Sprintf("Average of your latest %d of %s", len(r.items), counted(int(r.total), "review", "reviews"))
	}
	if r.unanswered > 0 {
		line += fmt.Sprintf(" · %d waiting for a reply", r.unanswered)
	}
	m.TotalLine = &line
	m.Actions = sc.links([3]string{"reviews", "Reply to reviews", "/reviews"}, [3]string{"listings", "Listings", "/listings"})
	return m
}

func healthModule(sc sellerCtx, h *port.AccountHealth) domain.ModuleSummary {
	if h == nil {
		return unavailableModule("health", "health", "Account health")
	}
	m := readyModule("health", "health", "Account health")
	if h.Status == "" || h.Status == domain.ComplianceCompliant {
		m.Headline = headline("Good", "standing", nil, "positive")
		m.TotalLine = strPtr("Nothing outstanding")
	} else {
		open := max(h.OpenItems, len(h.Requirements))
		m.Headline = headline("Action needed", "on your account", nil, "attention")
		line := counted(open, "item", "items") + " open"
		if h.CurrentDeadline != nil {
			line += " · due " + h.CurrentDeadline.Format("Jan 2")
		}
		m.TotalLine = &line
	}
	m.Actions = sc.links([3]string{"account-health", "Account health", "/account-health"}, [3]string{"team", "Team", "/team"}, [3]string{"policy", "Policies", "/policy"})
	return m
}

func promotionsModule(sc sellerCtx, coupons []port.SellerCouponUp) domain.ModuleSummary {
	if len(coupons) == 0 {
		return emptyModule("promotions", "promotions", "Promotions", "chart", "Run a promotion to bring shoppers back.", moduleAction("Create your first promotion", "/coupons"))
	}
	active, paused, other := 0, 0, 0
	for _, c := range coupons {
		switch c.Status {
		case "ACTIVE":
			active++
		case "PAUSED":
			paused++
		default:
			other++
		}
	}
	m := readyModule("promotions", "promotions", "Promotions")
	m.Headline = headline(fmt.Sprintf("%d", active), "active now", nil, "neutral")
	m.Chart = chartOf("donut", "Promotions by status", "count", []domain.ModulePoint{{Label: "Active", Value: float64(active), Tone: "green"}, {Label: "Paused", Value: float64(paused), Tone: "gold"}, {Label: "Ended or other", Value: float64(other), Tone: "grey"}})
	m.TotalLine = strPtr(counted(len(coupons), "promotion", "promotions") + " in total")
	m.Actions = sc.links([3]string{"coupons", "All promotions", "/coupons"})
	return m
}

func (s *sellerDashboardImpl) providerModules(sc sellerCtx, src *dashSources, days int, now time.Time) []domain.ModuleSummary {
	mods := []domain.ModuleSummary{}
	add := func(m domain.ModuleSummary) { mods = append(mods, m) }
	services := moduleAction("Add your first service", "/services")
	bookings := sc.can("requests") || sc.can("calendar")
	if bookings {
		a := src.bookings
		reqs := 0
		if src.requests != nil {
			reqs = *src.requests
		}
		total, deposits := 0, int64(0)
		pts := []domain.ModulePoint{}
		if a != nil {
			for _, d := range a.Days {
				total += d.Bookings
				deposits += d.DepositMinor
				pts = append(pts, domain.ModulePoint{Label: shortDay(d.Date), Value: float64(d.Bookings)})
			}
		}
		switch {
		case a == nil:
			add(unavailableModule("bookings", "bookings", "Bookings"))
		case total == 0 && reqs == 0:
			add(emptyModule("bookings", "bookings", "Bookings", "calendar", "Bookings from clients will appear here once your services are live.", services))
		default:
			m := readyModule("bookings", "bookings", "Bookings")
			m.Headline = headline(fmt.Sprintf("%d", total), "bookings", lastDays(days), "positive")
			m.Chart = chartOf("sparkline", fmt.Sprintf("Bookings per day, last %d days", days), "count", pts)
			line := moneyLabel(deposits) + " in deposits"
			if reqs > 0 {
				line = counted(reqs, "request", "requests") + " to confirm · " + line
			}
			m.TotalLine = &line
			if reqs > 0 {
				m.Headline.Tone = "positive"
			}
			m.Actions = sc.links([3]string{"requests", "Requests", "/requests"}, [3]string{"calendar", "Calendar", "/calendar"}, [3]string{"services", "Services", "/services"})
			add(m)
		}
	}
	if sc.can("earnings") || sc.can("payouts") {
		add(providerRevenue(sc, src.bookings, days))
	}
	if sc.can("calendar") && src.calendar != nil {
		add(providerCalendar(sc, src.calendar, now, s.r.d.Location))
	}
	if sc.can("payouts") {
		add(payoutsModule(sc, src.balance))
	}
	if sc.can("account-health") {
		add(healthModule(sc, src.health))
	}
	return mods
}

func providerRevenue(sc sellerCtx, a *port.BookingsAnalytics, days int) domain.ModuleSummary {
	if a == nil {
		return unavailableModule("revenue", "revenue", "Revenue")
	}
	var paid, pending int64
	pts := []domain.ModulePoint{}
	for _, w := range a.Weeks {
		paid += w.PaidOutMinor
		pending += w.PendingMinor
		pts = append(pts, domain.ModulePoint{Label: "Wk " + w.Week, Value: dollars(w.PaidOutMinor + w.PendingMinor)})
	}
	if paid+pending == 0 {
		return emptyModule("revenue", "revenue", "Revenue", "chart", "Your earnings will show here after your first completed booking.", moduleAction("Open earnings", "/earnings"))
	}
	m := readyModule("revenue", "revenue", "Revenue")
	m.Headline = headline(moneyLabel(paid+pending), "earned", lastDays(days), "positive")
	m.Chart = chartOf("bars", "Earnings by week", "money", pts)
	m.TotalLine = strPtr(moneyLabel(paid) + " paid out · " + moneyLabel(pending) + " pending")
	m.Actions = sc.links([3]string{"earnings", "Earnings", "/earnings"}, [3]string{"payouts", "Payouts", "/payouts"}, [3]string{"fees", "Fees", "/fees"})
	return m
}

func providerCalendar(sc sellerCtx, cal *port.BookingsCalendar, now time.Time, loc *time.Location) domain.ModuleSummary {
	open := false
	for _, d := range cal.OpenHours {
		if len(d.Ranges) > 0 {
			open = true
		}
	}
	if !open {
		return emptyModule("calendar", "calendar", "Availability", "calendar", "Set your working hours so clients can book you.", moduleAction("Set your hours", "/hours"))
	}
	if loc == nil {
		loc = time.UTC
	}
	today := time.Date(now.In(loc).Year(), now.In(loc).Month(), now.In(loc).Day(), 0, 0, 0, 0, loc)
	week := today.AddDate(0, 0, 7)
	perDay := map[int]int{}
	upcoming, month := 0, 0
	for _, b := range cal.Bookings {
		if b.Status != "CONFIRMED" {
			continue
		}
		month++
		start := b.StartAt.In(loc)
		if !start.Before(today) && start.Before(week) {
			upcoming++
			perDay[int(start.Sub(today).Hours()/24)]++
		}
	}
	m := readyModule("calendar", "calendar", "Availability")
	pts := []domain.ModulePoint{}
	for i := 0; i < 7; i++ {
		pts = append(pts, domain.ModulePoint{Label: dayLabel(today.AddDate(0, 0, i)), Value: float64(perDay[i])})
	}
	m.Headline = headline(fmt.Sprintf("%d", upcoming), "confirmed in the next 7 days", nil, "neutral")
	m.Chart = chartOf("bars", "Confirmed bookings, next 7 days", "count", pts)
	m.TotalLine = strPtr(counted(month, "confirmed booking", "confirmed bookings") + " this month")
	m.Actions = sc.links([3]string{"calendar", "Calendar", "/calendar"}, [3]string{"hours", "Working hours", "/hours"})
	return m
}
