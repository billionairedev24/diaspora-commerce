// Package homemetrics is the Prometheus implementation of the dynamic home's metrics. Every label is a small fixed set
// (module registry, reason enum, mode, variant names, five locales): no product, seller or shopper ever becomes a label.
package homemetrics

import (
	"github.com/prometheus/client_golang/prometheus"

	"github.com/billionairedev24/diaspora-commerce/go/services/gateway-bff/internal/domain"
	"github.com/billionairedev24/diaspora-commerce/go/services/gateway-bff/internal/port"
)

// knownVariants are the variant labels a metric may carry; anything else is folded into "other".
var knownVariants = map[string]bool{"control": true, "a": true, "b": true, "c": true, "d": true}

type metricsImpl struct {
	eligibility *prometheus.CounterVec
	mismatch    *prometheus.CounterVec
	compose     *prometheus.HistogramVec
	age         prometheus.Gauge
	events      *prometheus.CounterVec
}

var _ port.HomeMetrics = (*metricsImpl)(nil)

// New registers home_module_eligibility{module,shown,reason}, home_shadow_mismatch{module}, home_compose_seconds{mode},
// home_signals_age_seconds and home_module_events{module,event,variant,locale} on reg.
func New(reg prometheus.Registerer) port.HomeMetrics {
	m := &metricsImpl{
		eligibility: prometheus.NewCounterVec(prometheus.CounterOpts{Name: "home_module_eligibility",
			Help: "Home module decisions per composition, by module, shown and reason."}, []string{"module", "shown", "reason"}),
		mismatch: prometheus.NewCounterVec(prometheus.CounterOpts{Name: "home_shadow_mismatch",
			Help: "Shadow mode: modules whose eligibility decision differs from what the legacy composition showed."}, []string{"module"}),
		compose: prometheus.NewHistogramVec(prometheus.HistogramOpts{Name: "home_compose_seconds",
			Help: "Home composition time.", Buckets: []float64{.01, .025, .05, .1, .25, .5, 1, 2.5}}, []string{"mode"}),
		age: prometheus.NewGauge(prometheus.GaugeOpts{Name: "home_signals_age_seconds", Help: "Age of the signals snapshot used by the last composition."}),
		events: prometheus.NewCounterVec(prometheus.CounterOpts{Name: "home_module_events",
			Help: "Organic home module impressions and clicks (no shopper identity)."}, []string{"module", "event", "variant", "locale"}),
	}
	reg.MustRegister(m.eligibility, m.mismatch, m.compose, m.age, m.events)
	return m
}

func (m *metricsImpl) Eligibility(module string, shown bool, reason string) {
	s := "false"
	if shown {
		s = "true"
	}
	m.eligibility.WithLabelValues(module, s, reason).Inc()
}
func (m *metricsImpl) Mismatch(module string) { m.mismatch.WithLabelValues(module).Inc() }
func (m *metricsImpl) ComposeSeconds(mode string, seconds float64) {
	m.compose.WithLabelValues(mode).Observe(seconds)
}
func (m *metricsImpl) SignalsAge(seconds float64) { m.age.Set(seconds) }

// ModuleEvent counts an event; unknown modules and locales are folded so the label set stays fixed.
func (m *metricsImpl) ModuleEvent(module, event, variant, locale string) {
	if !domain.IsModule(module) {
		module = "other"
	}
	known := false
	for _, l := range domain.Locales {
		known = known || l == locale
	}
	if !known {
		locale = "en"
	}
	if !knownVariants[variant] { // the variant comes from a cookie: only the experiment names we run may become a label
		variant = "other"
	}
	m.events.WithLabelValues(module, event, variant, locale).Inc()
}
