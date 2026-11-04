package app

import (
	"context"
	"strings"
)

// Relayed documents. Some screens show an upstream document as it is (pre-order windows, delivery fees, run economics,
// inventory operations). They name people and organisations the same way as the resource tables do.

// whoKeys are the fields of a relayed upstream document that say which person did something.
var whoKeys = []string{"updatedBy", "createdBy", "actor", "actorId", "changedBy", "decidedBy", "requestedBy", "resolvedBy", "approvedBy",
	"cancelledBy", "publishedBy", "submittedBy", "reviewedBy", "performedBy", "countedBy", "postedBy", "uploadedBy", "assignedBy", "by"}

// uuidLike reports an identity user id (the 8-4-4-4-12 form).
func uuidLike(s string) bool {
	if len(s) != 36 {
		return false
	}
	for i, r := range s {
		switch {
		case i == 8 || i == 13 || i == 18 || i == 23:
			if r != '-' {
				return false
			}
		case !(r >= '0' && r <= '9' || r >= 'a' && r <= 'f' || r >= 'A' && r <= 'F'):
			return false
		}
	}
	return true
}

// isOrgKey is a field that holds an organisation id; the name goes in the sibling field (sellerOrgId → sellerOrgName).
func isOrgKey(k string) bool {
	return k == "orgId" || k == "sellerOrgId" || k == "ownerOrgId" || k == "stockOwnerOrgId" || k == "ownerOrg"
}

// nameWho names the people and organisations of an upstream document the BFF only relays: a person's id in a who-field
// that is itself a label (updatedBy, actor…) is replaced by their name ("Unknown user" when nobody can name them); in a
// field that is an id (actorId) the name goes in the sibling field (actorName), like an organisation's name
// (sellerOrgId → sellerOrgName) and an order's number (orderId → orderNumber). Only identity user ids (UUIDs) are looked up, so "system" and names a person typed stay
// as they are.
func (n *staffNames) nameWho(ctx context.Context, sc staffCtx, doc any) {
	var people, orgs, orders []string
	walkDoc(doc, func(m map[string]any) {
		if s, ok := m["orderId"].(string); ok && s != "" {
			orders = append(orders, s)
		}
		for _, k := range whoKeys {
			if s, ok := m[k].(string); ok && uuidLike(s) {
				people = append(people, s)
			}
		}
		for k, v := range m {
			if s, ok := v.(string); ok && isOrgKey(k) && s != "" {
				orgs = append(orgs, s)
			}
		}
	})
	if len(people) == 0 && len(orgs) == 0 && len(orders) == 0 {
		return
	}
	who := n.labels(ctx, sc, personRef("actorId", "Person"), people...)
	seller := n.labels(ctx, sc, sellerRef("orgId"), orgs...)
	numbers := n.labels(ctx, sc, orderRef("orderId"), orders...)
	walkDoc(doc, func(m map[string]any) {
		if s, ok := m["orderId"].(string); ok && numbers[s] != "" {
			if _, has := m["orderNumber"]; !has {
				m["orderNumber"] = numbers[s]
			}
		}
		for _, k := range whoKeys {
			s, ok := m[k].(string)
			if !ok || who[s] == "" {
				continue
			}
			if strings.HasSuffix(k, "Id") {
				if _, has := m[nameKeyOf(k)]; !has {
					m[nameKeyOf(k)] = who[s]
				}
				continue
			}
			m[k] = who[s]
		}
		for k, v := range m {
			if s, ok := v.(string); ok && isOrgKey(k) && seller[s] != "" {
				if _, has := m[nameKeyOf(k)]; !has {
					m[nameKeyOf(k)] = seller[s]
				}
			}
		}
	})
}

// walkDoc calls visit for every JSON object of a decoded document.
func walkDoc(v any, visit func(map[string]any)) {
	switch t := v.(type) {
	case map[string]any:
		visit(t)
		for _, c := range t {
			walkDoc(c, visit)
		}
	case []any:
		for _, c := range t {
			walkDoc(c, visit)
		}
	}
}
