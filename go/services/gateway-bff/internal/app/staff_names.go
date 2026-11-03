package app

import (
	"context"
	"net/url"
	"slices"
	"strconv"
	"strings"
	"sync"
	"time"

	"github.com/billionairedev24/diaspora-commerce/go/services/gateway-bff/internal/domain"
)

// Names instead of ids. Staff must never have to read an internal id (org_rf8l…, a user's UUID) to know who or what a
// row is about. A resource says which of its fields hold an id (nameRef); the framework then, for every page it reads
// (a table page, a scan for search and sort, an export, a sheet), resolves all the ids of that page to display names in
// one pass per kind and puts them on the record under the ref's nameKey. Tables, search, sort, exports and sheets all
// work from that name; the id stays searchable and is shown, copyable, as an "ID" field in the sheet only.
//
// Resolution never fails a request: an id nobody can name (the directory is down, the person was deleted, the staff
// member may not read users) becomes a neutral label such as "Unknown seller".

type nameKind string

const (
	// nameOrg is a seller or provider organisation (seller's organisation directory); AfroTransact Retail is known
	// without asking.
	nameOrg nameKind = "org"
	// nameUser is an identity user: a customer, a staff member, a driver, a reviewer, the actor of a change.
	nameUser nameKind = "user"
	// nameRoute is a dispatch route (its name, e.g. "Houston AM 3").
	nameRoute nameKind = "route"
	// nameOrder is an order, named by its order number (AT-000123).
	nameOrder nameKind = "order"
	// nameStop is a stop of a dispatch route (its name).
	nameStop nameKind = "stop"
)

const (
	retailOrgName = "AfroTransact Retail"
	// defaultRetailOrgID is AfroTransact Retail's organisation id when the deployment does not configure one.
	defaultRetailOrgID = "org_afrotransact_retail"

	namesTTL         = 2 * time.Minute
	namesMissTTL     = 20 * time.Second
	namesMaxEntries  = 5000
	namesUserLookups = 40
	namesOrgLookups  = 20
	namesParallel    = 8
)

// nameRef marks one id field of a resource's records. path is "" for a field of the record itself, or the key of a list
// of objects whose elements each carry the id (a fee schedule's seller overrides).
type nameRef struct {
	path, idKey, nameKey string
	kind                 nameKind
	// label names the person or thing in a sheet ("Seller" → "Seller" and "Seller ID").
	label string
	// unknown is shown when the id cannot be named ("Unknown seller").
	unknown string
	// from is a path on the record whose text already names the id (an order's ship-to name for its customer); when it
	// is filled, the id is not looked up.
	from string
}

func nameKeyOf(idKey string) string {
	return strings.TrimSuffix(idKey, "Id") + "Name"
}

func newRef(kind nameKind, idKey, label, unknown string) nameRef {
	return nameRef{idKey: idKey, nameKey: nameKeyOf(idKey), kind: kind, label: label, unknown: unknown}
}

// sellerRef names the organisation in field idKey ("ownerOrgId" → "ownerOrgName").
func sellerRef(idKey string) nameRef { return newRef(nameOrg, idKey, "Seller", "Unknown seller") }

// orgRef is sellerRef for places that speak of an organisation rather than a seller.
func orgRef(idKey string) nameRef {
	return newRef(nameOrg, idKey, "Organisation", "Unknown organisation")
}

func customerRef(idKey string) nameRef {
	return newRef(nameUser, idKey, "Customer", "Unknown customer")
}

func driverRef(idKey string) nameRef { return newRef(nameUser, idKey, "Driver", "Unknown driver") }

func personRef(idKey, label string) nameRef { return newRef(nameUser, idKey, label, "Unknown user") }

func routeRef(idKey string) nameRef { return newRef(nameRoute, idKey, "Route", "Unknown route") }

// orderRef names an order id by its order number ("orderId" → "orderNumber").
func orderRef(idKey string) nameRef {
	r := newRef(nameOrder, idKey, "Order", "Unknown order")
	r.nameKey = strings.TrimSuffix(idKey, "Id") + "Number"
	return r
}

func stopRef(idKey string) nameRef { return newRef(nameStop, idKey, "Stop", "Unknown stop") }

// inList moves a ref to the objects of a list on the record.
func (n nameRef) inList(path string) nameRef {
	n.path = path
	return n
}

// orFrom names the id by a field of the record when that field is filled, without asking anyone.
func (n nameRef) orFrom(path string) nameRef {
	n.from = path
	return n
}

// named renames where the name is stored (when the column key differs from the default).
func (n nameRef) named(nameKey string) nameRef {
	n.nameKey = nameKey
	return n
}

// idLabel is the label of the id in a sheet: "Seller ID".
func (n nameRef) idLabel() string { return n.label + " ID" }

// ---------------------------------------------------------------- resolver

type nameEntry struct {
	name string // "" = looked up and not found
	at   time.Time
}

// staffNames resolves ids to display names, remembering answers for a short while per staff member (what a staff
// member may read depends on their permissions, so answers are never shared between people).
type staffNames struct {
	clock  Clock
	retail string
	mu     sync.Mutex
	known  map[string]nameEntry
	routes map[string]routeNames
}

func newStaffNames(clock Clock, retailOrgID string) *staffNames {
	if strings.TrimSpace(retailOrgID) == "" {
		retailOrgID = defaultRetailOrgID
	}
	return &staffNames{clock: clock, retail: strings.TrimSpace(retailOrgID), known: map[string]nameEntry{}, routes: map[string]routeNames{}}
}

func nameKey(owner string, kind nameKind, id string) string {
	return owner + "\x00" + string(kind) + "\x00" + id
}

// cached returns a remembered answer: the name, whether the id was looked up (hit) and whether it was found.
func (n *staffNames) cached(owner string, kind nameKind, id string) (name string, hit bool) {
	n.mu.Lock()
	defer n.mu.Unlock()
	e, ok := n.known[nameKey(owner, kind, id)]
	if !ok {
		return "", false
	}
	ttl := namesTTL
	if e.name == "" {
		ttl = namesMissTTL
	}
	if n.clock().Sub(e.at) >= ttl {
		delete(n.known, nameKey(owner, kind, id))
		return "", false
	}
	return e.name, true
}

func (n *staffNames) remember(owner string, kind nameKind, found map[string]string, asked []string) {
	n.mu.Lock()
	defer n.mu.Unlock()
	if len(n.known) > namesMaxEntries {
		n.known = map[string]nameEntry{}
	}
	now := n.clock()
	for _, id := range asked {
		n.known[nameKey(owner, kind, id)] = nameEntry{name: found[id], at: now}
	}
}

// resolve names the ids of one kind; ids it cannot name are left out of the answer. It makes at most one directory read
// per page of 200 organisations, one routes read, or one user read per distinct person (identity has no bulk lookup).
func (n *staffNames) resolve(ctx context.Context, sc staffCtx, kind nameKind, ids []string) map[string]string {
	out := map[string]string{}
	if n == nil || len(ids) == 0 {
		return out
	}
	owner := sc.me.ID
	var missing []string
	for _, id := range ids {
		if kind == nameOrg && id == n.retail {
			out[id] = retailOrgName
			continue
		}
		if kind == nameUser && (isSystem(id) || isPlainText(id)) {
			continue
		}
		if kind == nameOrder && strings.HasPrefix(id, "AT-") {
			out[id] = id // already an order number
			continue
		}
		if name, hit := n.cached(owner, kind, id); hit {
			if name != "" {
				out[id] = name
			}
			continue
		}
		missing = append(missing, id)
	}
	if len(missing) == 0 {
		return out
	}
	var found map[string]string
	switch kind {
	case nameOrg:
		found = n.lookupOrgs(ctx, sc, missing)
	case nameUser:
		found = n.lookupUsers(ctx, sc, missing)
	case nameOrder:
		found = n.lookupOrders(ctx, sc, missing)
	case nameRoute, nameStop:
		routes, stops := n.routeSnapshot(ctx, sc)
		found = routes
		if kind == nameStop {
			found = stops
		}
	}
	n.remember(owner, kind, found, missing)
	for _, id := range missing {
		if name := found[id]; name != "" {
			out[id] = name
		}
	}
	return out
}

// lookupOrgs reads seller's organisation directory (200 per page, ordered by id) until every wanted id was seen, then
// asks for the few it did not list one by one.
func (n *staffNames) lookupOrgs(ctx context.Context, sc staffCtx, ids []string) map[string]string {
	want := make(map[string]bool, len(ids))
	for _, id := range ids {
		want[id] = true
	}
	found := map[string]string{}
	for page := range orgDirectoryPages {
		var p jobj
		if err := sc.get(ctx, sc.up.Seller, "/v1/orgs/directory", url.Values{"page": {strconv.Itoa(page)}, "size": {"200"}}, &p); err != nil {
			break
		}
		items := objs(p, "items")
		for _, it := range items {
			if id := firstNonEmpty(jstr(it, "id"), jstr(it, "orgId")); want[id] {
				if name := firstNonEmpty(jstr(it, "displayName"), jstr(it, "legalName")); name != "" {
					found[id] = name
				}
			}
		}
		if len(found) >= len(want) || len(items) < 200 {
			break
		}
	}
	var rest []string
	for _, id := range ids {
		if found[id] == "" {
			rest = append(rest, id)
		}
	}
	if len(rest) > namesOrgLookups {
		rest = rest[:namesOrgLookups]
	}
	for id, name := range fanOut(ctx, rest, func(ctx context.Context, id string) string {
		var m jobj
		if sc.get(ctx, sc.up.Seller, "/v1/orgs/"+url.PathEscape(id)+"/mirror", nil, &m) != nil {
			return ""
		}
		return firstNonEmpty(jstr(m, "displayName"), jstr(m, "legalName"))
	}) {
		found[id] = name
	}
	return found
}

// lookupUsers reads each distinct user (identity has no lookup by several ids yet); a person's name, else their email.
func (n *staffNames) lookupUsers(ctx context.Context, sc staffCtx, ids []string) map[string]string {
	if len(ids) > namesUserLookups {
		ids = ids[:namesUserLookups]
	}
	return fanOut(ctx, ids, func(ctx context.Context, id string) string {
		var u jobj
		if sc.get(ctx, sc.up.Identity, "/v1/admin/users/"+url.PathEscape(id), nil, &u) != nil {
			return ""
		}
		return firstNonEmpty(strings.TrimSpace(jstr(u, "name")), strings.TrimSpace(jstr(u, "firstName")+" "+jstr(u, "lastName")), jstr(u, "email"))
	})
}

// lookupOrders names orders by their order number, one read per distinct order.
func (n *staffNames) lookupOrders(ctx context.Context, sc staffCtx, ids []string) map[string]string {
	if len(ids) > namesUserLookups {
		ids = ids[:namesUserLookups]
	}
	return fanOut(ctx, ids, func(ctx context.Context, id string) string {
		var o jobj
		if sc.get(ctx, sc.up.Orders, "/v1/orders/"+url.PathEscape(id), nil, &o) != nil {
			return ""
		}
		return jstr(o, "orderNumber")
	})
}

// isSystem is the actor of a job or a seed ("system", "system:seed", the seed migrations' "seed" and the legal document
// loader's "legal-loader"), not a person.
func isSystem(id string) bool {
	return id == "system" || strings.HasPrefix(id, "system:") || id == "seed" || id == "legal-loader"
}

// isPlainText is a value that is already something a person wrote (a name or an email), not an identifier.
func isPlainText(s string) bool { return strings.ContainsAny(s, " @") }

// routeSnapshot is dispatch's routes and their stops by id, read once and kept for a short while: routes and stops are
// named from the same read, whichever kind a page asks for first.
func (n *staffNames) routeSnapshot(ctx context.Context, sc staffCtx) (routes, stops map[string]string) {
	key := sc.me.ID
	n.mu.Lock()
	if s, ok := n.routes[key]; ok && n.clock().Sub(s.at) < namesTTL {
		n.mu.Unlock()
		return s.routes, s.stops
	}
	n.mu.Unlock()
	routes, stops = n.lookupRoutes(ctx, sc)
	n.mu.Lock()
	n.routes[key] = routeNames{routes: routes, stops: stops, at: n.clock()}
	n.mu.Unlock()
	return routes, stops
}

type routeNames struct {
	routes, stops map[string]string
	at            time.Time
}

// lookupRoutes reads dispatch's routes once and names every route and every stop on them.
func (n *staffNames) lookupRoutes(ctx context.Context, sc staffCtx) (routes, stops map[string]string) {
	var raw any
	routes, stops = map[string]string{}, map[string]string{}
	if sc.get(ctx, sc.up.Fulfilment, "/v1/dispatch/routes", url.Values{"limit": {"500"}}, &raw) != nil {
		return routes, stops
	}
	for _, r := range jpage(raw).items {
		if name := jstr(r, "name"); name != "" {
			routes[jstr(r, "id")] = name
		}
		for _, st := range objs(r, "stops") {
			if name := firstNonEmpty(jstr(st, "name"), jstr(st, "addressLine")); name != "" {
				stops[jstr(st, "id")] = name
			}
		}
	}
	return routes, stops
}

// fanOut runs look for every id, namesParallel at a time, and keeps the non-empty answers.
func fanOut(ctx context.Context, ids []string, look func(ctx context.Context, id string) string) map[string]string {
	out := map[string]string{}
	var mu sync.Mutex
	var wg sync.WaitGroup
	sem := make(chan struct{}, namesParallel)
	for _, id := range ids {
		wg.Add(1)
		sem <- struct{}{}
		go func() {
			defer wg.Done()
			defer func() { <-sem }()
			if name := look(ctx, id); name != "" {
				mu.Lock()
				out[id] = name
				mu.Unlock()
			}
		}()
	}
	wg.Wait()
	return out
}

// name is one id's display name, or the neutral label. "system" (a job, not a person) reads "System".
func (n *staffNames) name(ctx context.Context, sc staffCtx, ref nameRef, id string) string {
	id = strings.TrimSpace(id)
	switch {
	case id == "":
		return ""
	case ref.kind == nameUser && isSystem(id):
		return "System"
	case ref.kind == nameUser && isPlainText(id):
		return id
	}
	if got := n.resolve(ctx, sc, ref.kind, []string{id})[id]; got != "" {
		return got
	}
	return ref.unknown
}

// labels names every given id for code that fills typed structs: the display name, "System" for jobs, or the ref's
// neutral label; empty ids are left out. One resolve pass per call.
func (n *staffNames) labels(ctx context.Context, sc staffCtx, ref nameRef, ids ...string) map[string]string {
	out := map[string]string{}
	var asked []string
	for _, id := range ids {
		if id = strings.TrimSpace(id); id != "" && !slices.Contains(asked, id) {
			asked = append(asked, id)
		}
	}
	got := n.resolve(ctx, sc, ref.kind, asked)
	for _, id := range asked {
		switch {
		case ref.kind == nameUser && isSystem(id):
			out[id] = "System"
		case ref.kind == nameUser && isPlainText(id):
			out[id] = id
		case got[id] != "":
			out[id] = got[id]
		default:
			out[id] = ref.unknown
		}
	}
	return out
}

// enrich puts the name of every ref's id on the records, resolving each kind once for all of them. A record that
// already carries a name for the id (the upstream sent one) keeps it.
func (n *staffNames) enrich(ctx context.Context, sc staffCtx, refs []nameRef, items []jobj) {
	if len(refs) == 0 || len(items) == 0 {
		return
	}
	want := map[nameKind][]string{}
	for _, ref := range refs {
		for _, o := range ref.targets(items) {
			if id := jstr(o, ref.idKey); id != "" && !hasName(o, ref) && !slices.Contains(want[ref.kind], id) {
				want[ref.kind] = append(want[ref.kind], id)
			}
		}
	}
	resolved := map[nameKind]map[string]string{}
	for kind, ids := range want {
		resolved[kind] = n.resolve(ctx, sc, kind, ids)
	}
	for _, ref := range refs {
		for _, o := range ref.targets(items) {
			id := strings.TrimSpace(jstr(o, ref.idKey))
			if id == "" || hasName(o, ref) {
				continue
			}
			switch {
			case ref.kind == nameUser && isSystem(id):
				o[ref.nameKey] = "System"
			case ref.kind == nameUser && isPlainText(id):
				o[ref.nameKey] = id
			case resolved[ref.kind][id] != "":
				o[ref.nameKey] = resolved[ref.kind][id]
			case ref.kind == nameOrg && n != nil && id == n.retail:
				o[ref.nameKey] = retailOrgName
			default:
				o[ref.nameKey] = ref.unknown
			}
		}
	}
}

// hasName reports that the record already names the id (not just repeats it).
func hasName(o jobj, ref nameRef) bool {
	if ref.from != "" {
		if from := strings.TrimSpace(jstr(o, ref.from)); from != "" {
			o[ref.nameKey] = from
			return true
		}
	}
	have := strings.TrimSpace(jstr(o, ref.nameKey))
	return have != "" && have != jstr(o, ref.idKey)
}

// targets are the objects that carry the ref's id field.
func (n nameRef) targets(items []jobj) []jobj {
	if n.path == "" {
		return items
	}
	var out []jobj
	for _, it := range items {
		out = append(out, objs(it, n.path)...)
	}
	return out
}

// ---------------------------------------------------------------- resources

// withNames makes the resource's list and get name every id its refs mark, wherever the BFF reads the resource (table
// page, search and sort scans, exports, sheets, actions).
func (r *staffResource) withNames() *staffResource {
	if len(r.names) == 0 {
		return r
	}
	list, get := r.list, r.get
	if list != nil {
		r.list = func(ctx context.Context, sc staffCtx, q staffQuery) (staffList, error) {
			page, err := list(ctx, sc, q)
			if err == nil {
				sc.names.enrich(ctx, sc, r.names, page.items)
			}
			return page, err
		}
	}
	if get != nil {
		r.get = func(ctx context.Context, sc staffCtx, id string) (jobj, error) {
			o, err := get(ctx, sc, id)
			if err == nil && o != nil {
				sc.names.enrich(ctx, sc, r.names, []jobj{o})
			}
			return o, err
		}
	}
	return r
}

// searchIDs are the ids of the record that its row hides: the search box finds them though the table shows names.
func (r *staffResource) searchIDs(o jobj) []string {
	var ids []string
	for _, k := range r.search {
		if v := jstr(o, k); v != "" {
			ids = append(ids, v)
		}
	}
	for _, ref := range r.names {
		for _, t := range ref.targets([]jobj{o}) {
			if id := jstr(t, ref.idKey); id != "" {
				ids = append(ids, id)
			}
		}
	}
	return ids
}

// nameSkips are the keys autoSections leaves out: the ids and names the sheet shows its own way.
func (r *staffResource) nameSkips() []string {
	var skip []string
	for _, ref := range r.names {
		if ref.path == "" {
			skip = append(skip, ref.idKey, ref.nameKey)
		}
	}
	return skip
}

// nameSections puts the name where the sheet showed the raw id, and lists the ids (copyable) in one section at the end:
// a field whose value is exactly a marked id is replaced by the name under the ref's label; a name the sheet does not
// show yet leads the first section.
func (r *staffResource) nameSections(o jobj, sections []domain.DetailSection) []domain.DetailSection {
	var ids []domain.DetailField
	var lead []domain.DetailField
	for _, ref := range r.names {
		if ref.path != "" {
			continue
		}
		id, name := jstr(o, ref.idKey), jstr(o, ref.nameKey)
		if id == "" || name == "" {
			continue
		}
		shown := false
		for si := range sections {
			// The upstream document's own "… name" field (auto-laid sheets list every key) is this name again.
			sections[si].Fields = slices.DeleteFunc(sections[si].Fields, func(f domain.DetailField) bool {
				return f.Label == domain.Humanize(ref.nameKey) && f.Value == name
			})
			for fi := range sections[si].Fields {
				f := &sections[si].Fields[fi]
				switch f.Value {
				case id:
					f.Value, f.Kind, f.Label = name, "text", ref.label
					shown = true
				case name:
					shown = true
				}
			}
		}
		if !shown {
			lead = append(lead, domain.DetailField{Label: ref.label, Value: name, Kind: "text"})
		}
		ids = append(ids, domain.DetailField{Label: ref.idLabel(), Value: id, Kind: "mono"})
	}
	if len(lead) > 0 {
		if len(sections) == 0 {
			sections = []domain.DetailSection{{Title: "Details", Fields: []domain.DetailField{}}}
		}
		sections[0].Fields = append(lead, sections[0].Fields...)
	}
	if len(ids) > 0 {
		sections = append(sections, domain.DetailSection{Title: "IDs", Fields: ids})
	}
	return sections
}

// nameField is the sheet field for a ref: the name, for sheets that lay out their own sections.
func nameField(o jobj, ref nameRef) domain.DetailField {
	return domain.DetailField{Label: ref.label, Value: displayValue(o[ref.nameKey]), Kind: "text"}
}

// nameOf is the record's resolved name for a ref ("" when the record has no such id).
func nameOf(o jobj, ref nameRef) string { return jstr(o, ref.nameKey) }

// whoName is the display name of one identity user for places that build a line of text by hand (activity, audit).
func whoName(ctx context.Context, sc staffCtx, id string) string {
	return sc.names.name(ctx, sc, personRef("actorId", "Person"), id)
}

// nameAll names the ids of a list of records in place; for code that builds its own rows.
func nameAll(ctx context.Context, sc staffCtx, items []jobj, refs ...nameRef) {
	sc.names.enrich(ctx, sc, refs, items)
}
