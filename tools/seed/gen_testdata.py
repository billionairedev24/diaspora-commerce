#!/usr/bin/env python3
"""Writes tools/seed/testdata/*.json: the catalog's category tree, product-type schemas and listing rules, offline.

The catalog reads these from the same files the seeder fixtures are derived from, so no running stack is needed:

  categories.json      GET /v1/categories        from tools/taxonomy/baseline (the catalog's baseline initial data)
  product_types.json   GET /v1/product-types/*   same plan (gen_catalog_baseline.plan), by product type code
  listing_rules.json   GET /v1/listing-rules     tools/listing-rules/rules.json resolved like ClasspathRulesRegistry; the field
                                                 controls, options and units are read from RuleField.java

    python3 tools/seed/gen_testdata.py            # rewrite the fixtures
    python3 tools/seed/gen_testdata.py --check    # fail when they are stale (make seed-check, CI)
    python3 tools/seed/gen_testdata.py --record URL   # refresh them from a running catalog instead (read-only GETs)
"""
from __future__ import annotations

import json
import re
import sys
import urllib.request
import uuid
from pathlib import Path

HERE = Path(__file__).resolve().parent
REPO = HERE.parents[1]
OUT = HERE / "testdata"
sys.path.insert(0, str(REPO / "tools" / "taxonomy"))
import gen_catalog_baseline as G  # noqa: E402

RULE_FIELD_JAVA = REPO / "java/services/catalog/src/main/java/com/afrotransact/catalog/domain/rules/RuleField.java"
RULES_JSON = REPO / "tools/listing-rules/rules.json"
PER_VARIANT = {"product_id", "price", "quantity", "package_size", "package_weight"}
FOOD_FIELDS = {"net_weight", "servings", "shelf_life", "storage", "ingredients", "allergens"}


def java_lists() -> dict[str, list[str]]:
    src = RULE_FIELD_JAVA.read_text(encoding="utf-8")
    out = {}
    for name, body in re.findall(r"public static final List<String> (\w+) = List\.of\(([^;]*?)\);", src, re.S):
        out[name] = re.findall(r'"([^"]*)"', body)
    return out


def rule_fields() -> dict[str, dict]:
    """id -> control, attributeKey, options, units, perVariant, from the RuleField enum."""
    src = RULE_FIELD_JAVA.read_text(encoding="utf-8")
    lists = java_lists()
    out = {}
    for _, fid, control, key in re.findall(r'^\s*(\w+)\("(\w+)", FieldControl\.(\w+), (null|"\w+")\)', src, re.M):
        attr = None if key == "null" else key.strip('"')
        options = {"storage": lists["STORAGE_OPTIONS"], "allergens": lists["ALLERGEN_OPTIONS"]}.get(fid, [])
        units = {"shelf_life": lists["SHELF_UNITS"], "net_weight": lists["NET_UNITS"]}.get(fid, [])
        out[fid] = {"control": control.lower(), "attributeKey": attr, "options": options, "units": units,
                    "perVariant": fid in PER_VARIANT}
    return out


def plain_label(fid: str, name: str) -> str:
    return {"ingredients": "Ingredients", "allergens": "Allergens", "product_id": "Barcode or ISBN"}.get(fid, name)


def rules_response(root: dict, code: str, fields: dict[str, dict]) -> dict:
    kinds = {k["code"]: k["name"] for k in root["kinds"]}
    cats = {c["code"]: c for c in root["categories"]}
    if code in cats:
        c = cats[code]
        meta = dict(categoryName=c["name"], department=c["department"], kind=c["kind"], approval=c["approval"],
                    note=c.get("note", ""), provisional=bool(c.get("provisional")))
        overrides = c.get("overrides") or {}
    else:  # RuleBook.general: the general goods (HOM) rules
        meta = dict(categoryName="General goods", department="", kind="HOM", approval="open", note="", provisional=True)
        overrides = {}
    kind = meta["kind"]
    out_fields = []
    for f in root["fields"]:
        spec = fields[f["id"]]
        out_fields.append({"id": f["id"], "group": f["group"], "label": plain_label(f["id"], f["name"]),
                           "status": overrides.get(f["id"], f["byKind"][kind]),
                           "control": spec["control"], "attributeKey": spec["attributeKey"], "options": spec["options"],
                           "units": spec["units"], "perVariant": spec["perVariant"]})
    return {"categoryCode": code, "categoryName": meta["categoryName"], "department": meta["department"], "kind": kind,
            "kindName": kinds[kind], "approval": meta["approval"], "note": meta["note"], "provisional": meta["provisional"],
            "fields": out_fields}


def build() -> dict[str, object]:
    nodes, types = G.plan(G.load(G.FROZEN / "category_tax_map.json"), G.load(G.FROZEN / "schemas.json"))
    categories = [{"id": n["id"], "parentId": n["parent"], "code": n["code"], "name": n["name"], "level": n["level"],
                   "productType": n["type"], "path": n["path"], "taxStatus": n["status"]} for n in nodes]
    root = json.loads(RULES_JSON.read_text(encoding="utf-8"))
    fields = rule_fields()
    codes = sorted({c["code"] for c in root["categories"]} | {"general"})
    return {
        "categories.json": categories,
        "product_types.json": {t["code"]: {k: t[k] for k in ("code", "label", "identifierRule", "attributes", "themes", "traits")}
                               for t in types},
        "listing_rules.json": {c: rules_response(root, c, fields) for c in codes},
    }


def dump(obj: object) -> str:
    """Readable JSON; the rules, which are large, one category per line."""
    if isinstance(obj, dict) and all(isinstance(v, dict) and "fields" in v for v in obj.values()):
        lines = [json.dumps(k) + ": " + json.dumps(v, ensure_ascii=False, sort_keys=True, separators=(",", ":"))
                 for k, v in sorted(obj.items())]
        return "{\n" + ",\n".join(lines) + "\n}\n"
    return json.dumps(obj, indent=1, ensure_ascii=False, sort_keys=True) + "\n"


def record(base: str) -> dict[str, object]:
    def get(path: str):
        with urllib.request.urlopen(base.rstrip("/") + path, timeout=30) as res:
            return json.loads(res.read().decode())
    cats = get("/v1/categories")
    types = {t["code"]: t for t in get("/v1/product-types")}
    codes = sorted({c["code"] for c in cats if c["level"] == "CATEGORY"} | {"general"})
    return {"categories.json": cats, "product_types.json": types,
            "listing_rules.json": {c: get(f"/v1/listing-rules?categoryCode={c}") for c in codes}}


def main(argv: list[str]) -> int:
    files = record(argv[argv.index("--record") + 1]) if "--record" in argv else build()
    OUT.mkdir(exist_ok=True)
    stale = []
    for name, data in files.items():
        text = dump(data)
        path = OUT / name
        if "--check" in argv:
            if not path.exists() or path.read_text(encoding="utf-8") != text:
                stale.append(name)
        else:
            path.write_text(text, encoding="utf-8")
    if stale:
        print("seed testdata is stale: " + ", ".join(stale) + " (run python3 tools/seed/gen_testdata.py)")
        return 1
    return 0


if __name__ == "__main__":
    sys.exit(main(sys.argv[1:]))
