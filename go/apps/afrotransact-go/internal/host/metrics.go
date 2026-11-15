package host

import (
	"strings"

	"github.com/prometheus/client_golang/prometheus"
	dto "github.com/prometheus/client_model/go"
)

// hostName labels the process-wide collectors (go_*, process_*) kept once instead of once per module.
const hostName = "afrotransact-go"

// labelled gathers one module's registry and adds the module label (and a service label when the metric has none, so
// dashboards keyed on service=<old service name> keep working).
type labelled struct {
	name     string
	g        prometheus.Gatherer
	keepProc bool
}

func (l labelled) Gather() ([]*dto.MetricFamily, error) {
	if l.g == nil {
		return nil, nil
	}
	fams, err := l.g.Gather()
	out := make([]*dto.MetricFamily, 0, len(fams))
	for _, f := range fams {
		proc := isProcessFamily(f.GetName())
		if proc && !l.keepProc {
			continue
		}
		module, service := l.name, l.name
		if proc {
			module, service = hostName, hostName
		}
		for _, m := range f.Metric {
			has := map[string]bool{}
			for _, lp := range m.Label {
				has[lp.GetName()] = true
			}
			if !has["module"] {
				m.Label = append(m.Label, &dto.LabelPair{Name: ptr("module"), Value: ptr(module)})
			}
			if !has["service"] {
				m.Label = append(m.Label, &dto.LabelPair{Name: ptr("service"), Value: ptr(service)})
			}
		}
		out = append(out, f)
	}
	return out, err
}

func isProcessFamily(name string) bool {
	return strings.HasPrefix(name, "go_") || strings.HasPrefix(name, "process_")
}

func ptr(s string) *string { return &s }

// newMergedGatherer merges the modules' registries; the first one also contributes the Go and process collectors.
func newMergedGatherer(mods []*running) prometheus.Gatherer {
	gs := make(prometheus.Gatherers, 0, len(mods))
	for i, m := range mods {
		gs = append(gs, labelled{name: m.spec.Name, g: m.mod.Gatherer, keepProc: i == 0})
	}
	return gs
}
