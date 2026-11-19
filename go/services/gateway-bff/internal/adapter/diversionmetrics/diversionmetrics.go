// Package diversionmetrics counts text refused by the off-platform diversion scan.
package diversionmetrics

import "github.com/prometheus/client_golang/prometheus"

// New registers diversion_text_rejected_total{field} on reg and returns the hook to call with a field path ("about.body").
// The text itself is never a label or a log value.
func New(reg prometheus.Registerer) func(field string) {
	c := prometheus.NewCounterVec(prometheus.CounterOpts{Name: "diversion_text_rejected_total",
		Help: "Seller text refused for steering customers away from AfroTransact (urls, emails, phones, handles, phrases), by field."}, []string{"field"})
	reg.MustRegister(c)
	return func(field string) { c.WithLabelValues(field).Inc() }
}
