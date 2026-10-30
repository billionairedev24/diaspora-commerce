package app

import (
	"fmt"
	"io"
	"sort"
	"time"

	"github.com/billionairedev24/diaspora-commerce/tools/ops/kafka-replay/internal/domain"
)

// printer remembers the first write error so renderers can print freely and report once.
type printer struct {
	w   io.Writer
	err error
}

func (p *printer) f(format string, args ...any) {
	if p.err == nil {
		_, p.err = fmt.Fprintf(p.w, format, args...)
	}
}

func (p *printer) result(what string) error {
	if p.err != nil {
		return fmt.Errorf("render %s: %w", what, p.err)
	}
	return nil
}

// RenderSummary prints a `list` result.
func RenderSummary(w io.Writer, topic string, s *domain.Summary) error {
	p := &printer{w: w}
	p.f("%s: %d record(s)\n", topic, s.Total)
	if s.Total > 0 {
		p.f("oldest: %s\nnewest: %s\n", stamp(s.Oldest), stamp(s.Newest))
		for _, sec := range []struct {
			title  string
			counts map[string]int
		}{
			{"by exception class", s.ByException},
			{"by event type", s.ByEventType},
			{"by original topic", s.ByOriginal},
			{"by consumer group", s.ByConsumerGrp},
		} {
			p.f("\n%s\n", sec.title)
			for _, k := range sortedKeys(sec.counts) {
				p.f("  %6d  %s\n", sec.counts[k], k)
			}
		}
	}
	return p.result("summary")
}

// RenderRecord prints a `show` result: position, headers, then the payload.
func RenderRecord(w io.Writer, rec domain.Record) error {
	p := &printer{w: w}
	p.f("record   %s\ntime     %s\nkey      %s\nheaders\n", rec.ID(), stamp(rec.Timestamp), PrintablePayload(rec.Key))
	for _, h := range rec.Headers {
		p.f("  %-28s %s\n", h.Key, PrintablePayload(h.Value))
	}
	p.f("payload\n%s\n", PrintablePayload(rec.Value))
	return p.result("record")
}

// RenderReplay prints a `replay` report. Dry runs say so loudly.
func RenderReplay(w io.Writer, opts ReplayOptions, rep ReplayReport) error {
	p := &printer{w: w}
	mode := "DRY RUN (nothing was sent; add --execute to send)"
	if opts.Execute {
		mode = "EXECUTED"
	}
	p.f("%s\nscanned %d, matched %d from %s\n", mode, rep.Scanned, rep.Matched, opts.DLQTopic)
	for _, it := range rep.Items {
		p.f("  %-30s %s  event=%s type=%s -> %s\n", it.Action, it.ID, orDash(it.EventID), orDash(it.EventType),
			orDash(it.Target))
	}
	p.f("replayed %d, would replay %d, already replayed %d, no original topic %d\n",
		rep.Count(ActionReplayed), rep.Count(ActionWouldReplay), rep.Count(ActionAlreadyReplayed),
		rep.Count(ActionNoOriginalTopic))
	return p.result("replay report")
}

// RenderReset prints a `reset-offsets` result.
func RenderReset(w io.Writer, opts ResetOptions, res ResetResult) error {
	p := &printer{w: w}
	state := "unknown (broker not reachable)"
	if res.StateKnown {
		state = fmt.Sprintf("%s, %d member(s)", res.State.State, res.State.Members)
	}
	p.f("group %s: %s\n", opts.Group, state)
	if res.Executed {
		p.f("executed:\n  %s\n%s", res.ExecuteCommand, res.Output)
		return p.result("reset")
	}
	p.f("\n1. stop every consumer of the group (the service), then check the plan:\n  %s\n", res.DryRunCommand)
	p.f("\n2. apply it:\n  %s\n   (or: kafka-replay reset-offsets ... --execute, which refuses while the group is active)\n",
		res.ExecuteCommand)
	p.f("\n3. start the service again and watch the lag drain.\n")
	if res.StateKnown && !res.State.Inactive() {
		p.f("\nWARNING: the group is %s now; Kafka rejects a reset until all its members are gone.\n", res.State.State)
	}
	return p.result("reset")
}

func stamp(t time.Time) string {
	if t.IsZero() {
		return "-"
	}
	return t.UTC().Format(time.RFC3339)
}

func orDash(s string) string {
	if s == "" {
		return "-"
	}
	return s
}

func sortedKeys(m map[string]int) []string {
	keys := make([]string, 0, len(m))
	for k := range m {
		keys = append(keys, k)
	}
	sort.Slice(keys, func(i, j int) bool {
		if m[keys[i]] != m[keys[j]] {
			return m[keys[i]] > m[keys[j]]
		}
		return keys[i] < keys[j]
	})
	return keys
}
