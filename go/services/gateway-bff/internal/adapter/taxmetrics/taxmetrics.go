// Package taxmetrics is the Prometheus implementation of the checkout tax estimate's counters.
package taxmetrics

import (
	"github.com/prometheus/client_golang/prometheus"

	"github.com/billionairedev24/diaspora-commerce/go/services/gateway-bff/internal/app"
)

type taxImpl struct {
	unavailable *prometheus.CounterVec
	lock        *prometheus.CounterVec
}

var _ app.TaxMetrics = (*taxImpl)(nil)

// New registers tax_estimate_unavailable_total{reason} (a checkout summary with no tax estimate, by reason) and
// checkout_tax_quote_lock_total{result} (the tax quote lock at Place order: issued, honoured, inputs_changed, expired,
// invalid, missing, total_changed) on reg.
func New(reg prometheus.Registerer) app.TaxMetrics {
	m := &taxImpl{
		unavailable: prometheus.NewCounterVec(prometheus.CounterOpts{Name: "tax_estimate_unavailable_total",
			Help: "Checkout summaries without a tax estimate, by reason (shopper states and failures; alert on the failures)."}, []string{"reason"}),
		lock: prometheus.NewCounterVec(prometheus.CounterOpts{Name: "checkout_tax_quote_lock_total",
			Help: "Tax quote lock (taxQuoteId) events: issued with a summary, or its result at Place order."}, []string{"result"}),
	}
	reg.MustRegister(m.unavailable, m.lock)
	return m
}

func (m *taxImpl) EstimateUnavailable(reason string) { m.unavailable.WithLabelValues(reason).Inc() }
func (m *taxImpl) QuoteLock(result string)           { m.lock.WithLabelValues(result).Inc() }
