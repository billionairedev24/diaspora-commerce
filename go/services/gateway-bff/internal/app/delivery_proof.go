package app

import (
	"context"
	"strings"

	"github.com/billionairedev24/diaspora-commerce/go/services/gateway-bff/internal/domain"
	"github.com/billionairedev24/diaspora-commerce/go/services/gateway-bff/internal/port"
)

// deliveredProof is the proof on the step that delivered a shipment: the last "delivered" timeline entry that carries a
// handover. Nil for carrier deliveries (the carrier's own tracking is the record), pickups and manual confirmations,
// which have no driver proof. Links are filled in later by attachProofLinks.
func deliveredProof(entries []proofEntry) *domain.DeliveryProof {
	var out *domain.DeliveryProof
	for _, e := range entries {
		p := e.proof
		if !strings.EqualFold(e.to, "delivered") || p == nil || p.HandoverMethod == "" {
			continue
		}
		out = &domain.DeliveryProof{Method: strings.ToUpper(p.HandoverMethod), RecipientName: optStr(p.RecipientName),
			Relationship: optStr(p.Relationship), SafePlace: optStr(p.SafePlace), At: e.at,
			PhotoAssetID: p.PhotoAssetID, SignatureAssetID: p.SignatureAssetID}
	}
	return out
}

// proofEntry is a timeline entry reduced to what a proof needs, whichever upstream shape it came from.
type proofEntry struct {
	to, at string
	proof  *port.UpDeliveryProof
}

// attachProofLinks gives each proof short-lived links to its photo and signature. Best effort: a proof whose picture cannot
// be signed is still shown, without it.
func attachProofLinks(ctx context.Context, links port.ProofLinks, proofs []*domain.DeliveryProof) {
	if links == nil {
		return
	}
	ids := []string{}
	for _, p := range proofs {
		if p != nil {
			ids = append(ids, p.PhotoAssetID, p.SignatureAssetID)
		}
	}
	if len(ids) == 0 {
		return
	}
	urls := links.URLs(ctx, ids...)
	for _, p := range proofs {
		if p == nil {
			continue
		}
		if u := urls[p.PhotoAssetID]; u != "" {
			p.PhotoURL = &u
		}
		if u := urls[p.SignatureAssetID]; u != "" {
			p.SignatureURL = &u
		}
	}
}

// enrichStaffProof adds photoUrl and signatureUrl to the proof of every timeline entry of a raw orders-service order
// (staff read the order as orders returns it), so the sheet can show the pictures.
func enrichStaffProof(ctx context.Context, links port.ProofLinks, order jobj) {
	if links == nil {
		return
	}
	var ids []string
	for _, sh := range objs(order, "shipments") {
		for _, t := range objs(sh, "timeline") {
			ids = append(ids, jstr(t, "proof.photoAssetId"), jstr(t, "proof.signatureAssetId"))
		}
	}
	if len(ids) == 0 {
		return
	}
	urls := links.URLs(ctx, ids...)
	for _, sh := range objs(order, "shipments") {
		for _, t := range objs(sh, "timeline") {
			proof, ok := t["proof"].(map[string]any)
			if !ok {
				continue
			}
			if u := urls[jstr(t, "proof.photoAssetId")]; u != "" {
				proof["photoUrl"] = u
			}
			if u := urls[jstr(t, "proof.signatureAssetId")]; u != "" {
				proof["signatureUrl"] = u
			}
		}
	}
}

// proofSections is one "Proof of delivery" section per delivered shipment with a driver's proof: how it was handed
// over, to whom or where, the photo and the signature, and the driver's position (staff only).
func proofSections(order jobj) []domain.DetailSection {
	out := []domain.DetailSection{}
	for _, sh := range objs(order, "shipments") {
		var proof jobj
		for _, t := range objs(sh, "timeline") {
			if strings.EqualFold(jstr(t, "to"), "delivered") && jstr(t, "proof.handoverMethod") != "" {
				proof = jobj{}
				if p, ok := t["proof"].(map[string]any); ok {
					proof = p
				}
			}
		}
		if proof == nil {
			continue
		}
		sec := domain.DetailSection{Title: "Proof of delivery · " + jstr(sh, "label"), Fields: []domain.DetailField{}}
		add := func(label, value, kind string) {
			if strings.TrimSpace(value) != "" {
				sec.Fields = append(sec.Fields, domain.DetailField{Label: label, Value: value, Kind: kind})
			}
		}
		switch jstr(proof, "handoverMethod") {
		case "CUSTOMER":
			add("Handed over", "To the customer, who gave their code", "text")
		case "SOMEONE_ELSE":
			add("Handed over", "To someone else", "text")
			add("Received by", strings.TrimSpace(jstr(proof, "recipientName")+" "+bracketed(jstr(proof, "relationship"))), "text")
		case "SAFE_PLACE":
			add("Handed over", "Left in a safe place", "text")
			add("Left at", jstr(proof, "safePlace"), "text")
		}
		add("Photo", jstr(proof, "photoUrl"), "image")
		add("Signature", jstr(proof, "signatureUrl"), "image")
		if lat, lng := jstr(proof, "latitude"), jstr(proof, "longitude"); lat != "" && lng != "" {
			add("Driver position", lat+", "+lng, "text")
		}
		out = append(out, sec)
	}
	return out
}

func bracketed(s string) string {
	if strings.TrimSpace(s) == "" {
		return ""
	}
	return "(" + strings.ToLower(s) + ")"
}
