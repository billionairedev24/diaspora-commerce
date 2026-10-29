package deliveryruns

import (
	"context"
	"encoding/json"
	"fmt"
	"net/http"
	"net/url"
	"strings"

	"github.com/billionairedev24/diaspora-commerce/go/services/cart/internal/domain"
	"github.com/billionairedev24/diaspora-commerce/go/services/cart/internal/port"
)

type runsImpl struct {
	baseURL string
	client  *http.Client
}

var _ port.DeliveryRuns = (*runsImpl)(nil)

// NewRuns returns the open-runs adapter over delivery-runs' public GET /v1/public/runs/available (no token).
func NewRuns(baseURL string, client *http.Client) port.DeliveryRuns {
	return &runsImpl{baseURL: strings.TrimSuffix(baseURL, "/"), client: client}
}

type publicRun struct {
	RunID          string          `json:"runId"`
	Town           string          `json:"town"`
	CutoffAt       string          `json:"cutoffAt"`
	DeliversOn     string          `json:"deliversOn"`
	TimeZone       string          `json:"timeZone"`
	FeeMinor       int64           `json:"feeMinor"`
	Currency       string          `json:"currency"`
	DropMode       string          `json:"dropMode"`
	AcceptingOrder *bool           `json:"acceptingOrders"`
	Full           bool            `json:"full"`
	MinOrders      int             `json:"minOrders"`
	OrdersSoFar    int             `json:"ordersSoFar"`
	FeeRule        *domain.FeeRule `json:"feeRule"`
}

func (r *runsImpl) OpenRuns(ctx context.Context, zip string) ([]domain.OpenRun, error) {
	req, err := http.NewRequestWithContext(ctx, http.MethodGet,
		r.baseURL+"/v1/public/runs/available?zip="+url.QueryEscape(zip), nil)
	if err != nil {
		return nil, fmt.Errorf("request: %w", err)
	}
	res, err := r.client.Do(req)
	if err != nil {
		return nil, fmt.Errorf("delivery-runs: %w", err)
	}
	defer func() { _ = res.Body.Close() }()
	if res.StatusCode != http.StatusOK {
		return nil, fmt.Errorf("delivery-runs open runs: status %d", res.StatusCode)
	}
	var body struct {
		Runs []publicRun `json:"runs"`
	}
	if err := json.NewDecoder(res.Body).Decode(&body); err != nil {
		return nil, fmt.Errorf("delivery-runs decode: %w", err)
	}
	out := make([]domain.OpenRun, 0, len(body.Runs))
	for _, p := range body.Runs {
		if p.RunID == "" || p.CutoffAt == "" || p.DeliversOn == "" || (p.AcceptingOrder != nil && !*p.AcceptingOrder) {
			continue
		}
		pickup := strings.EqualFold(p.DropMode, "PICKUP_POINT")
		out = append(out, domain.OpenRun{RunID: p.RunID, Town: p.Town, CutoffAt: p.CutoffAt, DeliversOn: p.DeliversOn,
			TimeZone: p.TimeZone, FeeMinor: p.FeeMinor, Currency: first(p.Currency, "USD"), Door: !pickup, Pickup: pickup,
			FeeRule: p.FeeRule, MinOrders: p.MinOrders, OrdersSoFar: p.OrdersSoFar, Full: p.Full})
	}
	return out, nil
}
