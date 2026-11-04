package app

import (
	"context"
	"net/http"
	"net/url"
	"regexp"
	"slices"
	"strconv"
	"strings"
	"time"

	"github.com/billionairedev24/diaspora-commerce/go/services/gateway-bff/internal/domain"
	"github.com/billionairedev24/diaspora-commerce/go/services/gateway-bff/internal/port"
)

// StaffSearchService is Search analytics in the staff console (contracts/openapi/gateway-bff-staff.yaml, tag Search
// analytics) over the search service: top queries, zero-result queries and click-through by day (search:analytics.view),
// and the synonym rules that fix zero-result queries (search:synonyms.edit to add and delete; listing needs either
// permission). The search service reports whole windows (at most 500 queries), so the BFF filters, sorts and pages
// them for the console's server-driven tables. Edits are logged here with the staff member; the search service keeps no
// audit trail of synonym changes.
type StaffSearchService interface {
	Markets(ctx context.Context, c *domain.Caller) (domain.StaffSearchMarkets, error)
	TopQueries(ctx context.Context, c *domain.Caller, q StaffSearchQuery) (domain.StaffQueryReport, error)
	ZeroResults(ctx context.Context, c *domain.Caller, q StaffSearchQuery) (domain.StaffQueryReport, error)
	ClickThrough(ctx context.Context, c *domain.Caller, q StaffSearchQuery) (domain.StaffClickThrough, error)
	Synonyms(ctx context.Context, c *domain.Caller) (domain.StaffSynonymList, error)
	AddSynonym(ctx context.Context, c *domain.Caller, terms []string) (domain.StaffSynonymRule, error)
	DeleteSynonym(ctx context.Context, c *domain.Caller, ruleID string) error
}

// StaffSearchQuery is a report request: the market, the days (YYYY-MM-DD, both inclusive; empty = the service's last
// 7 days), a filter on the query text, the sort (`<column>,asc|desc`) and the page (1-based).
type StaffSearchQuery struct {
	Market, From, To, Q string
	Sort                []string
	Page, Size          int
}

type staffSearchImpl struct {
	r       *staffResolver
	markets []string
}

var _ StaffSearchService = (*staffSearchImpl)(nil)

// NewStaffSearchService returns the Search analytics use case; markets are the search markets staff can report on
// (the first is the default).
func NewStaffSearchService(d StaffDeps, markets []string) StaffSearchService {
	clean := []string{}
	for _, m := range markets {
		if m = strings.ToLower(strings.TrimSpace(m)); m != "" && !slices.Contains(clean, m) {
			clean = append(clean, m)
		}
	}
	if len(clean) == 0 {
		clean = []string{"us"}
	}
	return &staffSearchImpl{r: newStaffResolver(d), markets: clean}
}

const (
	searchModule     = "search"
	maxSearchPage    = 100
	maxSearchFilter  = 120
	maxSynonymTerms  = 20
	maxSynonymLength = 60
)

var (
	searchDateRe  = regexp.MustCompile(`^\d{4}-\d{2}-\d{2}$`)
	synonymIDRe   = regexp.MustCompile(`^[a-z0-9][a-z0-9-]{0,62}$`)
	synonymSlugRe = regexp.MustCompile(`[^a-z0-9]+`)
)

// need resolves the staff member with at least VIEW on search and the permission the call needs.
func (s *staffSearchImpl) need(ctx context.Context, c *domain.Caller, level string, perms ...string) (staffCtx, error) {
	sc, err := s.r.require(ctx, c, searchModule, level)
	if err != nil {
		return sc, err
	}
	if !sc.access.CanAny(perms...) {
		return sc, needsPermission(perms[0])
	}
	if sc.up.Search == nil {
		return sc, domain.UpstreamUnavailable("search")
	}
	return sc, nil
}

func (s *staffSearchImpl) Markets(ctx context.Context, c *domain.Caller) (domain.StaffSearchMarkets, error) {
	sc, err := s.r.require(ctx, c, searchModule, domain.LevelView)
	if err != nil {
		return domain.StaffSearchMarkets{}, err
	}
	out := domain.StaffSearchMarkets{Items: []domain.StaffSearchMarket{}, Default: s.markets[0], CanEditSynonyms: sc.access.Can(domain.PermSearchSynonymsEdit)}
	for _, m := range s.markets {
		out.Items = append(out.Items, domain.StaffSearchMarket{Code: m, Name: domain.SearchMarketName(m)})
	}
	return out, nil
}

// window validates market and days and returns the upstream query (the service's default window when no days are given).
func (s *staffSearchImpl) window(q StaffSearchQuery) (url.Values, error) {
	errs := map[string]string{}
	market := strings.ToLower(strings.TrimSpace(q.Market))
	if market == "" {
		market = s.markets[0]
	}
	if !slices.Contains(s.markets, market) {
		errs["market"] = "Choose one of the listed markets."
	}
	var from, to time.Time
	parse := func(name, raw string, dst *time.Time) {
		if raw == "" {
			return
		}
		t, err := time.Parse(time.DateOnly, raw)
		if err != nil || !searchDateRe.MatchString(raw) {
			errs[name] = "Use a date like 2026-10-31."
			return
		}
		*dst = t
	}
	parse("from", q.From, &from)
	parse("to", q.To, &to)
	if len(errs) == 0 && !from.IsZero() && !to.IsZero() {
		switch {
		case from.After(to):
			errs["from"] = "The first day is after the last day."
		case to.Sub(from) >= time.Duration(domain.SearchMaxDays)*24*time.Hour:
			errs["to"] = "Reports cover at most 366 days."
		}
	}
	if len(errs) > 0 {
		return nil, domain.ValidationProblem(errs)
	}
	v := url.Values{"market": {market}}
	if q.From != "" {
		v.Set("from", q.From)
	}
	if q.To != "" {
		v.Set("to", q.To)
	}
	return v, nil
}

func paging(page, size int) (int, int, error) {
	if page == 0 {
		page = 1
	}
	if size == 0 {
		size = 25
	}
	if page < 1 || page > 10_000 || size < 1 || size > domain.SearchReportMaxRows {
		return 0, 0, domain.ValidationProblem(map[string]string{"page": "Out of range"})
	}
	return page, size, nil
}

// upstreamQueryReport is search's QueryReport / CTRReport.
type upstreamQueryReport struct {
	Market string                  `json:"market"`
	From   string                  `json:"from"`
	To     string                  `json:"to"`
	Items  []domain.StaffQueryStat `json:"items"`
}

type upstreamCTRReport struct {
	Market string            `json:"market"`
	From   string            `json:"from"`
	To     string            `json:"to"`
	Days   []domain.StaffDay `json:"days"`
	Total  domain.StaffDay   `json:"total"`
}

func (s *staffSearchImpl) TopQueries(ctx context.Context, c *domain.Caller, q StaffSearchQuery) (domain.StaffQueryReport, error) {
	return s.queries(ctx, c, "/v1/search/analytics/top-queries", domain.QuerySort{Column: "searches", Desc: true}, q)
}

func (s *staffSearchImpl) ZeroResults(ctx context.Context, c *domain.Caller, q StaffSearchQuery) (domain.StaffQueryReport, error) {
	return s.queries(ctx, c, "/v1/search/analytics/zero-results", domain.QuerySort{Column: "zeroResults", Desc: true}, q)
}

func (s *staffSearchImpl) queries(ctx context.Context, c *domain.Caller, path string, def domain.QuerySort, q StaffSearchQuery) (domain.StaffQueryReport, error) {
	if len(q.Q) > maxSearchFilter {
		return domain.StaffQueryReport{}, domain.ValidationProblem(map[string]string{"q": "Too long"})
	}
	page, size, err := paging(q.Page, q.Size)
	if err != nil {
		return domain.StaffQueryReport{}, err
	}
	sc, err := s.need(ctx, c, domain.LevelView, domain.PermSearchAnalyticsView)
	if err != nil {
		return domain.StaffQueryReport{}, err
	}
	v, err := s.window(q)
	if err != nil {
		return domain.StaffQueryReport{}, err
	}
	v.Set("limit", strconv.Itoa(domain.SearchReportMaxRows))
	var rep upstreamQueryReport
	if err := sc.get(ctx, sc.up.Search, path, v, &rep); err != nil {
		return domain.StaffQueryReport{}, err
	}
	all := make([]domain.StaffQueryStat, 0, len(rep.Items))
	var totals domain.StaffQueryTotals
	term := strings.ToLower(strings.TrimSpace(q.Q))
	for _, it := range rep.Items {
		it.ZeroResultRate = domain.Ratio(it.ZeroResults, it.Searches)
		totals.Searches += it.Searches
		totals.ZeroResults += it.ZeroResults
		totals.Clicks += it.Clicks
		if term == "" || strings.Contains(strings.ToLower(it.Query), term) {
			all = append(all, it)
		}
	}
	// The "no results" view lists only queries that found nothing at least once.
	if strings.HasSuffix(path, "zero-results") {
		all = slices.DeleteFunc(all, func(i domain.StaffQueryStat) bool { return i.ZeroResults == 0 })
	}
	totals.CTR, totals.ZeroResultRate = domain.Ratio(totals.Clicks, totals.Searches), domain.Ratio(totals.ZeroResults, totals.Searches)
	domain.SortQueryStats(all, domain.ParseQuerySort(q.Sort, def))
	start := min((page-1)*size, len(all))
	items := all[start:min(start+size, len(all))]
	market := firstNonEmpty(rep.Market, v.Get("market"))
	return domain.StaffQueryReport{
		StaffSearchWindow: domain.StaffSearchWindow{Market: market, MarketName: domain.SearchMarketName(market), From: rep.From, To: rep.To},
		Items:             items, Total: len(all), Page: page, Size: size, Truncated: len(rep.Items) >= domain.SearchReportMaxRows, Totals: totals,
	}, nil
}

func (s *staffSearchImpl) ClickThrough(ctx context.Context, c *domain.Caller, q StaffSearchQuery) (domain.StaffClickThrough, error) {
	if len(q.Q) > maxSearchFilter {
		return domain.StaffClickThrough{}, domain.ValidationProblem(map[string]string{"q": "Too long"})
	}
	page, size, err := paging(q.Page, q.Size)
	if err != nil {
		return domain.StaffClickThrough{}, err
	}
	sc, err := s.need(ctx, c, domain.LevelView, domain.PermSearchAnalyticsView)
	if err != nil {
		return domain.StaffClickThrough{}, err
	}
	v, err := s.window(q)
	if err != nil {
		return domain.StaffClickThrough{}, err
	}
	var rep upstreamCTRReport
	if err := sc.get(ctx, sc.up.Search, "/v1/search/analytics/ctr", v, &rep); err != nil {
		return domain.StaffClickThrough{}, err
	}
	days := make([]domain.StaffDay, len(rep.Days))
	copy(days, rep.Days)
	slices.SortStableFunc(days, func(a, b domain.StaffDay) int { return strings.Compare(a.Day, b.Day) })
	paged := slices.Clone(days)
	if term := strings.TrimSpace(q.Q); term != "" {
		paged = slices.DeleteFunc(paged, func(d domain.StaffDay) bool { return !strings.Contains(d.Day, term) })
	}
	domain.SortDays(paged, domain.ParseQuerySort(q.Sort, domain.QuerySort{Column: "day", Desc: true}))
	start := min((page-1)*size, len(paged))
	market := firstNonEmpty(rep.Market, v.Get("market"))
	return domain.StaffClickThrough{
		StaffSearchWindow: domain.StaffSearchWindow{Market: market, MarketName: domain.SearchMarketName(market), From: rep.From, To: rep.To},
		Items:             paged[start:min(start+size, len(paged))], Total: len(paged), Page: page, Size: size, Trend: days, Totals: rep.Total,
	}, nil
}

// upstreamSynonyms is search's rule list; a rule's synonyms are "a, b, c".
type upstreamSynonyms struct {
	Rules []struct {
		ID       string `json:"id"`
		Synonyms string `json:"synonyms"`
	} `json:"rules"`
}

func termsOf(synonyms string) []string {
	out := []string{}
	for _, t := range strings.Split(synonyms, ",") {
		if t = strings.TrimSpace(t); t != "" {
			out = append(out, t)
		}
	}
	return out
}

func (s *staffSearchImpl) rules(ctx context.Context, sc staffCtx) ([]domain.StaffSynonymRule, error) {
	var up upstreamSynonyms
	if err := sc.get(ctx, sc.up.Search, "/v1/search/synonyms", nil, &up); err != nil {
		return nil, err
	}
	out := make([]domain.StaffSynonymRule, 0, len(up.Rules))
	for _, r := range up.Rules {
		out = append(out, domain.StaffSynonymRule{ID: r.ID, Terms: termsOf(r.Synonyms)})
	}
	slices.SortFunc(out, func(a, b domain.StaffSynonymRule) int {
		return strings.Compare(strings.ToLower(strings.Join(a.Terms, " ")), strings.ToLower(strings.Join(b.Terms, " ")))
	})
	return out, nil
}

func (s *staffSearchImpl) Synonyms(ctx context.Context, c *domain.Caller) (domain.StaffSynonymList, error) {
	sc, err := s.need(ctx, c, domain.LevelView, domain.PermSearchAnalyticsView, domain.PermSearchSynonymsEdit)
	if err != nil {
		return domain.StaffSynonymList{}, err
	}
	rules, err := s.rules(ctx, sc)
	if err != nil {
		return domain.StaffSynonymList{}, err
	}
	return domain.StaffSynonymList{Items: rules, CanEdit: sc.access.Can(domain.PermSearchSynonymsEdit)}, nil
}

// slugOf is a rule id from its first term ("agbada kids" → "agbada-kids"); terms with no letters or digits get "rule".
func slugOf(term string) string {
	slug := strings.Trim(synonymSlugRe.ReplaceAllString(strings.ToLower(term), "-"), "-")
	if slug == "" {
		slug = "rule"
	}
	if len(slug) > 56 {
		slug = strings.Trim(slug[:56], "-")
	}
	return slug
}

func (s *staffSearchImpl) AddSynonym(ctx context.Context, c *domain.Caller, terms []string) (domain.StaffSynonymRule, error) {
	clean := []string{}
	seen := map[string]bool{}
	for _, t := range terms {
		t = strings.Join(strings.Fields(t), " ")
		if t == "" || seen[strings.ToLower(t)] {
			continue
		}
		if strings.Contains(t, ",") {
			return domain.StaffSynonymRule{}, domain.FieldProblem(domain.CodeValidationFailed, "Check the words", "terms", "Separate words with commas, one entry each.")
		}
		if len(t) > maxSynonymLength {
			return domain.StaffSynonymRule{}, domain.FieldProblem(domain.CodeValidationFailed, "Check the words", "terms", "Each word or phrase is at most 60 characters.")
		}
		seen[strings.ToLower(t)] = true
		clean = append(clean, t)
	}
	if len(clean) < 2 || len(clean) > maxSynonymTerms {
		return domain.StaffSynonymRule{}, domain.FieldProblem(domain.CodeValidationFailed, "Check the words", "terms", "Give 2 to 20 different words or phrases.")
	}
	sc, err := s.need(ctx, c, domain.LevelEdit, domain.PermSearchSynonymsEdit)
	if err != nil {
		return domain.StaffSynonymRule{}, err
	}
	existing, err := s.rules(ctx, sc)
	if err != nil {
		return domain.StaffSynonymRule{}, err
	}
	taken := map[string]bool{}
	for _, r := range existing {
		taken[r.ID] = true
	}
	id := slugOf(clean[0])
	for n := 2; taken[id]; n++ {
		id = slugOf(clean[0]) + "-" + strconv.Itoa(n)
	}
	if !synonymIDRe.MatchString(id) {
		return domain.StaffSynonymRule{}, domain.FieldProblem(domain.CodeValidationFailed, "Check the words", "terms", "The first word needs letters or digits.")
	}
	var rule struct {
		ID       string `json:"id"`
		Synonyms string `json:"synonyms"`
	}
	err = sc.call(ctx, sc.up.Search, port.StaffCall{Method: http.MethodPut, Path: "/v1/search/synonyms/" + url.PathEscape(id), Body: map[string]string{"synonyms": strings.Join(clean, ", ")}}, &rule)
	if err != nil {
		return domain.StaffSynonymRule{}, synonymField(err)
	}
	s.r.d.Log.InfoContext(ctx, "staff search synonym added", "staff", sc.me.ID, "rule", id, "terms", clean)
	return domain.StaffSynonymRule{ID: firstNonEmpty(rule.ID, id), Terms: termsOf(firstNonEmpty(rule.Synonyms, strings.Join(clean, ", ")))}, nil
}

// synonymField attaches the search service's 422 explanation to the words field.
func synonymField(err error) error {
	if p, ok := domain.AsProblem(err); ok && p.Status == http.StatusUnprocessableEntity && len(p.Errors) == 0 {
		detail := p.Detail
		if i := strings.LastIndex(detail, "\n"); i >= 0 {
			detail = detail[i+1:]
		}
		return domain.FieldProblem(domain.CodeValidationFailed, "Check the words", "terms", capitalise(detail))
	}
	return err
}

func capitalise(s string) string {
	s = strings.TrimSpace(s)
	if s == "" {
		return "Check the words."
	}
	return strings.ToUpper(s[:1]) + s[1:]
}

func (s *staffSearchImpl) DeleteSynonym(ctx context.Context, c *domain.Caller, ruleID string) error {
	if !synonymIDRe.MatchString(ruleID) {
		return domain.NewProblem(http.StatusNotFound, domain.CodeNotFound, "Not found", "No such synonym rule.")
	}
	sc, err := s.need(ctx, c, domain.LevelEdit, domain.PermSearchSynonymsEdit)
	if err != nil {
		return err
	}
	if err := sc.call(ctx, sc.up.Search, port.StaffCall{Method: http.MethodDelete, Path: "/v1/search/synonyms/" + url.PathEscape(ruleID)}, nil); err != nil {
		if p, ok := domain.AsProblem(err); ok && p.Status == http.StatusNotFound {
			return domain.NewProblem(http.StatusNotFound, domain.CodeNotFound, "Not found", "That synonym rule is already gone.")
		}
		return err
	}
	s.r.d.Log.InfoContext(ctx, "staff search synonym deleted", "staff", sc.me.ID, "rule", ruleID)
	return nil
}
