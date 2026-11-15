package host

import (
	"errors"
	"fmt"
	"net/http"
	"net/url"
	"sort"
	"strings"

	"gopkg.in/yaml.v3"
)

// checkRoutes refuses a host in which two flat-mounted modules claim the same request. Every operation each module
// documents in its OpenAPI specs is replayed against every other flat module's own mux; a hit names both modules.
func (h *hostImpl) checkRoutes() error {
	var errs []error
	seen := map[string]bool{}
	for _, a := range h.mods {
		if a.mount != MountFlat {
			continue
		}
		for _, op := range operations(a) {
			req := &http.Request{Method: op.method, URL: &url.URL{Path: op.path}, Host: "check"}
			_, own := a.mod.Router.Handler(req)
			if own == "" || standardPaths[patternPath(own)] {
				continue
			}
			for _, b := range h.mods {
				if b == a || b.mount != MountFlat {
					continue
				}
				_, other := b.mod.Router.Handler(req)
				if other == "" || standardPaths[patternPath(other)] {
					continue
				}
				x, y := a, b
				if x.spec.Name > y.spec.Name {
					x, y = y, x
				}
				key := x.spec.Name + "|" + y.spec.Name + "|" + op.method + " " + op.path
				if seen[key] {
					continue
				}
				seen[key] = true
				errs = append(errs, fmt.Errorf("route %s %s is claimed by module %q (pattern %q) and module %q (pattern %q): "+
					"set AT_MODULES_%s_MOUNT=prefixed or AT_MODULES_%s_MOUNT=prefixed",
					op.method, op.path, a.spec.Name, own, b.spec.Name, other, EnvName(a.spec.Name), EnvName(b.spec.Name)))
			}
		}
	}
	if len(errs) == 0 {
		return nil
	}
	sort.Slice(errs, func(i, j int) bool { return errs[i].Error() < errs[j].Error() })
	if len(errs) > 20 {
		errs = append(errs[:20], fmt.Errorf("... and %d more", len(errs)-20))
	}
	return fmt.Errorf("duplicate routes: %w", errors.Join(errs...))
}

type operation struct{ method, path string }

// operations lists the (method, concrete path) pairs of a module's OpenAPI documents; path parameters become "x".
func operations(r *running) []operation {
	var out []operation
	for _, raw := range r.mod.Specs {
		var doc struct {
			Paths map[string]map[string]yaml.Node `yaml:"paths"`
		}
		if err := yaml.Unmarshal(raw, &doc); err != nil {
			continue
		}
		for p, ops := range doc.Paths {
			concrete := concretePath(p)
			for m := range ops {
				switch mm := strings.ToUpper(m); mm {
				case "GET", "POST", "PUT", "PATCH", "DELETE":
					out = append(out, operation{method: mm, path: concrete})
				}
			}
		}
	}
	return out
}

func concretePath(p string) string {
	segs := strings.Split(p, "/")
	for i, s := range segs {
		if strings.HasPrefix(s, "{") && strings.HasSuffix(s, "}") {
			segs[i] = "x"
		}
	}
	return strings.Join(segs, "/")
}
