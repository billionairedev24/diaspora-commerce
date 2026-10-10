// Package accounts implements port.AccountResolver from configuration: carrier accounts and negotiated-rate
// settings per fulfilment centre or seller, with platform defaults for every other origin.
package accounts

import (
	"context"

	"github.com/billionairedev24/diaspora-commerce/go/services/carrier-gateway/internal/domain"
	"github.com/billionairedev24/diaspora-commerce/go/services/carrier-gateway/internal/port"
)

// Entry is one configured carrier account.
type Entry struct {
	Carrier       string  `yaml:"carrier"`
	Number        string  `yaml:"number"`
	Negotiated    bool    `yaml:"negotiated"`
	AdjustmentPct float64 `yaml:"adjustmentPct"`
}

// Config maps origin nodes (FC ids, seller org ids) to accounts; Defaults apply to unlisted origins.
type Config struct {
	Defaults []Entry            `yaml:"defaults"`
	Nodes    map[string][]Entry `yaml:"nodes"`
}

type resolverImpl struct {
	cfg     Config
	enabled map[domain.CarrierCode]bool
}

var _ port.AccountResolver = (*resolverImpl)(nil)

// New returns a resolver limited to enabled carriers.
func New(cfg Config, enabled []domain.CarrierCode) port.AccountResolver {
	m := map[domain.CarrierCode]bool{}
	for _, c := range enabled {
		m[c] = true
	}
	return &resolverImpl{cfg: cfg, enabled: m}
}

func (r *resolverImpl) Accounts(_ context.Context, origin string) ([]domain.Account, error) {
	entries, ok := r.cfg.Nodes[origin]
	if !ok {
		entries = r.cfg.Defaults
	}
	out := make([]domain.Account, 0, len(entries))
	for _, e := range entries {
		c, err := domain.ParseCarrier(e.Carrier)
		if err != nil || !r.enabled[c] {
			continue
		}
		out = append(out, domain.Account{Carrier: c, Number: e.Number, Negotiated: e.Negotiated, AdjustmentPct: e.AdjustmentPct})
	}
	return out, nil
}
