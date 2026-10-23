package app

import (
	"context"
	"regexp"
	"strings"
	"time"

	"github.com/billionairedev24/diaspora-commerce/go/services/gateway-bff/internal/domain"
	"github.com/billionairedev24/diaspora-commerce/go/services/gateway-bff/internal/port"
)

// SellerTaxService is Seller Center's tax statement: the sales tax AfroTransact collected on the seller's behalf, by state and
// month, so the seller can reconcile their books. AfroTransact collects and remits it as marketplace facilitator.
type SellerTaxService interface {
	// Statement is the month's statement (month "" = the current month); the same access as Payouts.
	Statement(ctx context.Context, c *domain.Caller, month string) (domain.SellerTaxStatement, error)
	// Export is the same statement as CSV.
	Export(ctx context.Context, c *domain.Caller, month string) (domain.FileDownload, error)
}

type sellerTaxImpl struct{ r *orgResolver }

var _ SellerTaxService = (*sellerTaxImpl)(nil)

// NewSellerTaxService returns the SellerTaxService.
func NewSellerTaxService(d SellerDeps) SellerTaxService { return &sellerTaxImpl{r: newResolver(d)} }

var taxMonthPattern = regexp.MustCompile(`^\d{4}-(0[1-9]|1[0-2])$`)

// statementMonths is how many months the picker offers.
const statementMonths = 24

func (s *sellerTaxImpl) month(requested string) (string, error) {
	now := s.r.d.Clock()
	if requested == "" {
		return now.Format("2006-01"), nil
	}
	if !taxMonthPattern.MatchString(requested) {
		return "", domain.ValidationProblem(map[string]string{"month": "Use the form 2026-09"})
	}
	return requested, nil
}

func (s *sellerTaxImpl) client() (port.TaxStatementClient, error) {
	if s.r.d.TaxStatements == nil {
		return nil, domain.UpstreamUnavailable("tax")
	}
	return s.r.d.TaxStatements, nil
}

func (s *sellerTaxImpl) Statement(ctx context.Context, c *domain.Caller, requested string) (domain.SellerTaxStatement, error) {
	sc, err := s.r.require(ctx, c, "", "payouts", domain.LevelView)
	if err != nil {
		return domain.SellerTaxStatement{}, err
	}
	month, err := s.month(requested)
	if err != nil {
		return domain.SellerTaxStatement{}, err
	}
	cl, err := s.client()
	if err != nil {
		return domain.SellerTaxStatement{}, err
	}
	res, err := cl.Statement(ctx, c.BearerToken, sc.org.ID, month)
	if err != nil {
		return domain.SellerTaxStatement{}, mapErr("tax", err)
	}
	out := domain.SellerTaxStatement{Month: month, Currency: domain.Currency, Rows: []domain.SellerTaxStateRow{}, Months: recentMonths(s.r.d.Clock(), statementMonths)}
	for _, r := range res.Rows {
		if r.Currency != "" && !strings.EqualFold(r.Currency, domain.Currency) {
			continue
		}
		out.Rows = append(out.Rows, domain.SellerTaxStateRow{Jurisdiction: r.Jurisdiction, State: strings.TrimPrefix(r.Jurisdiction, "US-"), Orders: r.Orders,
			GrossSalesMinor: r.GrossSalesMinor, TaxableSalesMinor: r.TaxableSalesMinor, TaxCollectedMinor: r.TaxCollectedMinor, TaxRefundedMinor: r.TaxRefundedMinor, NetTaxMinor: r.NetTaxMinor})
		t := &out.Totals
		t.Orders += r.Orders
		t.GrossSalesMinor += r.GrossSalesMinor
		t.TaxableSalesMinor += r.TaxableSalesMinor
		t.TaxCollectedMinor += r.TaxCollectedMinor
		t.TaxRefundedMinor += r.TaxRefundedMinor
		t.NetTaxMinor += r.NetTaxMinor
	}
	return out, nil
}

func (s *sellerTaxImpl) Export(ctx context.Context, c *domain.Caller, requested string) (domain.FileDownload, error) {
	sc, err := s.r.require(ctx, c, "", "payouts", domain.LevelView)
	if err != nil {
		return domain.FileDownload{}, err
	}
	month, err := s.month(requested)
	if err != nil {
		return domain.FileDownload{}, err
	}
	cl, err := s.client()
	if err != nil {
		return domain.FileDownload{}, err
	}
	body, err := cl.StatementCSV(ctx, c.BearerToken, sc.org.ID, month)
	if err != nil {
		return domain.FileDownload{}, mapErr("tax", err)
	}
	return domain.FileDownload{FileName: "sales-tax-statement-" + month + ".csv", ContentType: "text/csv; charset=utf-8", Content: body}, nil
}

// recentMonths lists the current month and the n-1 before it, newest first.
func recentMonths(now time.Time, n int) []string {
	first := time.Date(now.Year(), now.Month(), 1, 0, 0, 0, 0, time.UTC)
	out := make([]string, 0, n)
	for i := 0; i < n; i++ {
		out = append(out, first.AddDate(0, -i, 0).Format("2006-01"))
	}
	return out
}
