#!/usr/bin/env python3
"""Generates the seed INSERTs of seller migration V90 (selling_rule) from tools/listing-rules/rules.json.

Usage: python3 tools/listing-rules/gen_selling_seed.py > /tmp/seed.sql   (the output is pasted under the schema in
V90__selling_approvals.sql; `--check <migration>` verifies the migration still matches rules.json).
"""
import json, re, sys, uuid, pathlib

ROOT = pathlib.Path(__file__).resolve().parent
RULES = json.loads((ROOT / "rules.json").read_text())

NS = uuid.UUID("6f1c5a60-0c5e-4d1e-9a39-5f0f6a5b7a10")

def rid(kind, key):
    return str(uuid.uuid5(NS, f"{kind}:{key}"))

def q(s):
    return "'" + s.replace("'", "''") + "'"

def j(v):
    return q(json.dumps(v, separators=(",", ":")))

KIND_DOCS = {}  # kind -> {doc kind: code in rules.json}
FIELD_TO_DOC = {
    "Permit or licence": "PERMIT_LICENCE", "Brand authorization": "BRAND_AUTHORIZATION",
    "Safety or lab certificate": "SAFETY_CERTIFICATE", "Liability insurance": "LIABILITY_INSURANCE",
    "Authenticity or purchase invoice": "PURCHASE_INVOICE",
}
for f in RULES["fields"]:
    doc = FIELD_TO_DOC.get(f["name"])
    if doc:
        for kind, code in f["byKind"].items():
            KIND_DOCS.setdefault(kind, {})[doc] = code

# Per category overrides on top of the matrix (the category notes in rules.json say which are firm).
EXTRA_REQUIRED = {
    "baby-food-formula": ["PURCHASE_INVOICE"], "meat-poultry-fish": ["PURCHASE_INVOICE"],
    "dietary-supplements": ["SAFETY_CERTIFICATE"],
    "audiobooks": ["BRAND_AUTHORIZATION"], "digital-art-downloads": ["BRAND_AUTHORIZATION"],
    "downloadable-ebooks": ["BRAND_AUTHORIZATION"], "downloadable-software": ["SAFETY_CERTIFICATE"],
    "online-courses": ["PERMIT_LICENCE"], "event-tickets": ["PERMIT_LICENCE"], "photography-media": ["PHOTOS_ALL_SIDES"],
}
NO_DOCS = {"gift-cards"}  # the reviewer decides on the application text and fraud controls
FOOD_SERVICE = {"bakery-products", "prepared-meals"}

def docs_for(cat):
    if cat["code"] in NO_DOCS or cat["code"] in FOOD_SERVICE:
        return []
    out = {}
    for doc, code in KIND_DOCS.get(cat["kind"], {}).items():
        if doc == "BRAND_AUTHORIZATION" or doc == "PURCHASE_INVOICE" and cat["kind"] != "JWL":
            continue  # brand authorization belongs to brand rules; invoices only where the matrix says DOC
        if code == "document":
            out[doc] = False
        elif code == "required_or_exemption":
            out.setdefault(doc, True)
    for doc in EXTRA_REQUIRED.get(cat["code"], []):
        out[doc] = False
    return [{"kind": k, "optional": v} for k, v in sorted(out.items())]

BRAND_DOCS = [{"kind": "BRAND_AUTHORIZATION", "optional": False}, {"kind": "PURCHASE_INVOICE", "optional": False},
              {"kind": "PHOTOS_ALL_SIDES", "optional": False}]
CAT_NAMES = {c["code"]: c["name"] for c in RULES["categories"]}

FASHION_CATS = ["adult-clothing-footwear", "bags-purses", "childrens-clothing"]
ELECTRONICS_CATS = ["computers-laptops", "gaming", "phones", "tvs-audio-accessories"]
# Everyday words (Apple, Coach, Puma) are gated only inside the categories where the brand is the risk.
FASHION_BRANDS = ["Nike", "Adidas", "Puma", "Levi's", "Ralph Lauren", "Tommy Hilfiger", "Calvin Klein", "Michael Kors",
                  "Coach", "Kate Spade", "Ray-Ban", "Oakley", "The North Face", "Under Armour"]
ELECTRONICS_BRANDS = ["Apple", "Samsung", "Sony", "Microsoft", "Nintendo", "Bose", "Beats", "JBL", "Dell", "HP", "Lenovo",
                      "Canon", "Nikon", "GoPro", "Dyson", "LG", "Google"]
LUXURY_BRANDS = ["Gucci", "Louis Vuitton", "Prada", "Chanel", "Dior", "Burberry", "Versace", "Balenciaga", "Fendi",
                 "Hermes", "Yves Saint Laurent", "Tom Ford", "Rolex", "Omega", "Cartier", "Tiffany", "Givenchy",
                 "Valentino", "Bottega Veneta", "Jo Malone"]
PERFUME_BRANDS = ["Calvin Klein", "Dior", "Chanel", "Tom Ford", "Jo Malone", "Versace", "Gucci", "Prada", "Burberry",
                  "Yves Saint Laurent", "Dolce & Gabbana", "Giorgio Armani"]

def norm(brand):
    return re.sub(r"[^a-z0-9]+", "", brand.lower())

rows = []
def rule(stype, key, label, level, docs=(), reason="", note="", approval_level=None, capability=None,
         materials=(), watch=False, only="SEED"):
    rows.append(f"({q(rid(stype, key))}, {q(stype)}, {q(key)}, {q(label)}, {q(level)}, {j(list(docs))}, "
                f"{q(reason)}, {q(note)}, {q(approval_level) if approval_level else 'null'}, "
                f"{q(capability) if capability else 'null'}, {j(list(materials))}, {'true' if watch else 'false'}, {q(only)})")

rule("CAPABILITY", "FOOD_SERVICE", "Food service (cooks, bakers, caterers)", "APPLY",
     [{"kind": "PERMIT_LICENCE", "optional": False}, {"kind": "LIABILITY_INSURANCE", "optional": False}],
     note="A seller capability. One approval covers every food service category. Replaces the old bakery flag.")
for c in RULES["categories"]:
    lvl = {"open": "SELL", "brand_gated": "SELL", "provider_application": "SELL", "approval": "APPLY",
           "conditional_approval": "APPLY", "blocked": "NOT_AVAILABLE"}[c["approval"]]
    cap = "FOOD_SERVICE" if c["code"] in FOOD_SERVICE else None
    reason = ""
    if c["approval"] == "blocked":
        reason = "Not available yet: " + c["note"].rstrip(".").replace("Blocked until", "blocked until") + "."
    jewel = c["approval"] == "conditional_approval"
    rule("CATEGORY", c["code"], c["name"], lvl, docs_for(c) if lvl == "APPLY" and not jewel else
         ([{"kind": "PURCHASE_INVOICE", "optional": False}, {"kind": "PHOTOS_ALL_SIDES", "optional": False}] if jewel else []),
         reason=reason, note=c["note"], approval_level=c["approval"], capability=cap,
         materials=["gold", "silver", "gemstone"] if jewel else [], watch=jewel)
for brand in LUXURY_BRANDS:
    rule("BRAND", norm(brand), brand, "APPLY", BRAND_DOCS, note="Designer brand: approval needed in every category.")
for brands, cats in ((FASHION_BRANDS, FASHION_CATS), (ELECTRONICS_BRANDS, ELECTRONICS_CATS), (PERFUME_BRANDS, ["perfumes-fragrances"])):
    for brand in brands:
        for cat in cats:
            rule("BRAND_IN_CATEGORY", f"{norm(brand)}|{cat}", f"{brand} in {CAT_NAMES[cat]}", "APPLY", BRAND_DOCS,
                 note="Gated brand inside a brand-gated category.")

head = ("insert into selling_rule (id, scope_type, scope_key, scope_label, level, required_documents, reason, note, "
        "approval_level, capability, condition_materials, condition_watch, source) values\n")
sql = head + ",\n".join(rows) + ";\n"
if "--check" in sys.argv:
    mig = pathlib.Path(sys.argv[sys.argv.index("--check") + 1]).read_text()
    if sql.strip() not in mig:
        print("migration seed differs from rules.json: regenerate", file=sys.stderr)
        sys.exit(1)
    print("selling_rule seed matches rules.json:", len(rows), "rules")
else:
    print(sql, end="")
