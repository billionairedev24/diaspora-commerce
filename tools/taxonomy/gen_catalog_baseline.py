#!/usr/bin/env python3
"""Generate the catalog's initial-data migration (V1_1__initial_data.sql) of the clean baseline.

    python3 tools/taxonomy/gen_catalog_baseline.py            print it
    python3 tools/taxonomy/gen_catalog_baseline.py --write    write it
    python3 tools/taxonomy/gen_catalog_baseline.py --check    fail (exit 1) when the committed file is stale
    python3 tools/taxonomy/gen_catalog_baseline.py --refreeze copy the live dataset and schemas into baseline/, then write

The baseline gives an empty database its first tree: 13 departments > 74 categories (73 plus the provisional "Fabrics &
textiles") > 77 product-type leaves, the product types with their detail questions, tax questions, variation themes and
traits (fashion sizing included), the tax profile id and status of every category, the compliance of departments, and the
starter brand list with where each brand applies. It is the same data the old V2..V20 chain produced (see
tools/catalog/compare_migrations.sh).

The inputs are FROZEN copies in tools/taxonomy/baseline/ (the dataset category_tax_map.json, the detail schemas
schemas.json, the two product types that belong to no tree node, the starter brands). They are not the live dataset: a
migration that has been applied never changes, so later tree changes (tax rules, new categories, edited questions) are
made in the staff console or by the importer (POST /v1/taxonomy/import, import-on-start), never here. `--refreeze` is only
for the days before the first real deployment, when the whole database is recreated anyway.

The planning below mirrors TaxonomyPlanner, TaxFactSchema and TaxonomySchemas in the catalog service (ids, codes, paths,
order, JSON shapes); the equivalence is proven by tools/catalog/compare_migrations.sh and FreshCatalogDatabaseIT.
Deterministic: same inputs, same bytes.
"""
import hashlib
import json
import shutil
import sys
from pathlib import Path

HERE = Path(__file__).resolve().parent
REPO = HERE.parent.parent
FROZEN = HERE / "baseline"
LIVE_DATASET = HERE / "category_tax_map.json"
LIVE_SCHEMAS = REPO / "java/services/catalog/src/main/resources/taxonomy/schemas.json"
OUT = REPO / "java/services/catalog/src/main/resources/db/migration/V1_1__initial_data.sql"

NAMESPACE = "afrotransact.catalog.taxonomy.v2:"
BRAND_NAMESPACE = "afrotransact.catalog.brand.v1:"
SEP = " › "
SELLER_ANSWERS = {"VARIANT", "OFFER", "SELLER"}
KINDS = {"BOOLEAN": "BOOLEAN", "DECIMAL": "DECIMAL", "INTEGER": "INTEGER", "PERCENT": "PERCENT", "CHOICE": "ENUM",
         "FILE": "FILE"}


def load(path):
    return json.loads(Path(path).read_text(encoding="utf-8"))


def uuid_of(namespace, key):
    """Name-based UUID (SHA-1 of namespace + key, version 5 bits), as TaxonomyPlanner.idOf."""
    h = bytearray(hashlib.sha1((namespace + key).encode("utf-8")).digest()[:16])
    h[6] = (h[6] & 0x0F) | 0x50
    h[8] = (h[8] & 0x3F) | 0x80
    x = h.hex()
    return "%s-%s-%s-%s-%s" % (x[0:8], x[8:12], x[12:16], x[16:20], x[20:32])


def q(value):
    return "null" if value is None else "'" + str(value).replace("'", "''") + "'"


def j(value):
    return q(json.dumps(value, ensure_ascii=False, separators=(",", ":"))) + "::jsonb"


def text(value):
    """AttributeDefinition normalisation of help and unit: blank is null, otherwise trimmed."""
    return None if value is None or not str(value).strip() else str(value).strip()


def detail(a):
    """A detail attribute of the schema resource as the catalog stores it."""
    return {"key": a["key"], "label": a["label"], "options": list(a.get("options") or []),
            "required": a.get("required") is True, "multiline": a.get("multiline") is True, "kind": a["kind"],
            "units": list(a.get("units") or []), "section": a.get("section", "DETAILS"), "help": text(a.get("help")),
            "applies": True, "unit": None, "taxFact": False, "owner": None, "showWhen": None, "requiredWhen": None,
            "evidence": False, "optionLabels": {}}


def tax_question(fact, ref):
    kind = KINDS[fact["kind"]]
    options, labels = [], {}
    if kind == "ENUM":
        narrowed = ref.get("choices") or []
        for c in fact.get("choices") or []:
            if not narrowed or c["value"] in narrowed:
                options.append(c["value"])
                labels.setdefault(c["value"], c["label"])
    when = ref.get("requiredWhen") or None
    help_text = "Yes if your business is a bakery." if fact["owner"] == "SELLER" else fact.get("help")
    return {"key": fact["id"], "label": fact["label"], "options": options, "required": ref.get("required") is not False,
            "multiline": False, "kind": kind, "units": [], "section": "TAX", "help": text(help_text), "applies": True,
            "unit": text(fact.get("unit")), "taxFact": True,
            "owner": "VARIANT" if fact["owner"] == "VARIANT" else "OFFER", "showWhen": when, "requiredWhen": when,
            "evidence": fact.get("evidence") is True, "optionLabels": labels}


def tax_questions(dataset, category):
    facts = {f["id"]: f for f in dataset["facts"]}
    return [tax_question(facts[r["fact"]], r) for r in category.get("facts") or [] if facts[r["fact"]]["owner"] in SELLER_ANSWERS]


def schema_for(schemas, department, category, type_code):
    own = schemas["productTypes"].get(type_code)
    if own is None:
        name = schemas["categories"].get(category) or schemas["departments"].get(department)
        if name not in schemas["families"]:
            raise ValueError("No schema family %s for %s" % (name, category))
        own = schemas["families"][name]
    return own


def themes_of(schema):
    return [{"name": t["name"], "dimensions": [{"name": d["name"], "options": [str(o) for o in d["options"]],
                                                 "allowCustomValues": d.get("allowCustomValues") is True}
                                                for d in t["dimensions"]]} for t in schema.get("themes") or []]


def traits_of(schema):
    tr = schema.get("traits") or {}
    out = {"food": tr.get("food") is True, "perishable": tr.get("perishable") is True,
           "regulated": tr.get("regulated") is True, "preorder": tr.get("preorder") is True}
    if isinstance(schema.get("sizing"), dict):
        s = schema["sizing"]
        out["sizing"] = {"sizeChartRequired": s.get("sizeChartRequired") is True, "sizeDimension": s["sizeDimension"],
                         "suggestedMeasurements": [{"key": m["key"], "label": m["label"], "unit": m["unit"],
                                                    "help": m.get("help")} for m in s["suggestedMeasurements"]]}
    return out


def plan(dataset, schemas):
    """Nodes (dicts) and product types (in first-use order), as TaxonomyPlanner.plan."""
    nodes, types = [], {}
    departments = {}
    compliance = schemas.get("departmentCompliance") or {}
    for d in dataset["departments"]:
        node = {"id": uuid_of(NAMESPACE, d["code"]), "parent": None, "code": d["code"], "name": d["name"],
                "level": "DEPARTMENT", "type": None, "path": d["name"], "position": d.get("position", 0),
                "profile": None, "status": None, "compliance": ",".join(compliance.get(d["code"]) or []) or None}
        departments[d["code"]] = node
        nodes.append(node)

    def add_types(category, dept_code, code, defs, facts):
        for position, t in enumerate(defs):
            leaf = code + "-" + ("item" if t["code"] == code else t["code"])
            nodes.append({"id": uuid_of(NAMESPACE, leaf), "parent": category["id"], "code": leaf, "name": t["label"],
                          "level": "PRODUCT_TYPE", "type": t["code"], "path": category["path"] + SEP + t["label"],
                          "position": position, "profile": None, "status": None, "compliance": None})
            if t["code"] not in types:
                schema = schema_for(schemas, dept_code, code, t["code"])
                types[t["code"]] = {
                    "code": t["code"], "label": t["label"], "identifierRule": schema.get("identifierRule", "GTIN"),
                    "attributes": [detail(a) for a in schema.get("attributes") or []] + facts,
                    "themes": themes_of(schema), "traits": traits_of(schema)}

    def category_node(dept, code, name, position, profile, status):
        return {"id": uuid_of(NAMESPACE, code), "parent": dept["id"], "code": code, "name": name, "level": "CATEGORY",
                "type": None, "path": dept["path"] + SEP + name, "position": position, "profile": profile,
                "status": status, "compliance": None}

    counts = {}
    for c in dataset["categories"]:
        dept = departments[c["department"]]
        node = category_node(dept, c["code"], c["name"], c.get("position", 0), c["code"], c["status"])
        nodes.append(node)
        counts[c["department"]] = counts.get(c["department"], 0) + 1
        add_types(node, c["department"], c["code"], c.get("productTypes") or [], tax_questions(dataset, c))
    extra = 0
    for p in schemas.get("provisional") or []:
        dept = departments[p["department"]]
        node = category_node(dept, p["code"], p["name"], counts.get(p["department"], 0) + extra, None, p["status"])
        extra += 1
        nodes.append(node)
        add_types(node, p["department"], p["code"], [{"code": p["productType"], "label": p["name"]}], [])
    return nodes, list(types.values())


def generate(dataset, schemas, extra_types, brands):
    nodes, types = plan(dataset, schemas)
    types = types + [t for t in extra_types if t["code"] not in {x["code"] for x in types}]
    out = []
    w = out.append
    w("-- GENERATED by tools/taxonomy/gen_catalog_baseline.py from tools/taxonomy/baseline/ (do not edit by hand).")
    w("-- Initial data of an empty catalog database, applied right after V1__baseline.sql (version 1.1, so the next")
    w("-- migration is V2). Rule version %s; the tree of docs/engineering/category-tax-design.md:" % dataset["ruleVersion"])
    w("-- %d departments, %d categories, %d product-type leaves, %d product types, the starter brands and where they apply."
      % (sum(n["level"] == "DEPARTMENT" for n in nodes), sum(n["level"] == "CATEGORY" for n in nodes),
         sum(n["level"] == "PRODUCT_TYPE" for n in nodes), len(types)))
    w("--")
    w("-- This file is the last migration that writes rows staff own. From here the tree, the product types, brands and")
    w("-- size charts change in the staff console or through the importer (POST /v1/taxonomy/import), which is audited and")
    w("-- idempotent: see docs/engineering/taxonomy.md. Never edit this file after a database has applied it.")
    w("")
    w("insert into product_type (code, label, identifier_rule, attributes, themes, traits, version, updated_at) values")
    rows = ["    (%s, %s, %s, %s, %s, %s, 0, now())" % (q(t["code"]), q(t["label"]), q(t["identifierRule"]),
                                                      j(t["attributes"]), j(t["themes"]), j(t["traits"])) for t in types]
    w(",\n".join(rows) + ";")
    w("")
    for level, title in (("DEPARTMENT", "departments"), ("CATEGORY", "categories (tax profile id and status)"),
                         ("PRODUCT_TYPE", "product-type leaves")):
        w("-- %s" % title)
        w("insert into category (id, parent_id, code, name, level, product_type_code, path, position, active, version,")
        w("                      updated_at, compliance, tax_profile_id, tax_status) values")
        rows = ["    ('%s', %s, %s, %s, %s, %s, %s, %d, true, 0, now(), %s, %s, %s)" % (
            n["id"], "null" if n["parent"] is None else "'%s'" % n["parent"], q(n["code"]), q(n["name"]), q(n["level"]),
            q(n["type"]), q(n["path"]), n["position"], q(n["compliance"]), q(n["profile"]), q(n["status"]))
            for n in nodes if n["level"] == level]
        w(",\n".join(rows) + ";")
        w("")
    w("-- starter brands, approved; the registry's own rules apply from here on (staff extend it in the console)")
    w("insert into brand (id, name, normalized_name, status) values")
    rows = ["    ('%s', %s, brand_key(%s), 'APPROVED')" % (uuid_of(BRAND_NAMESPACE, b["name"]), q(b["name"]), q(b["name"]))
            for b in brands]
    w(",\n".join(rows) + ";")
    w("")
    w("-- where each starter brand applies (a department or category of the tree)")
    w("insert into brand_scope (brand_id, node_id)")
    w("select b.id, c.id from (values")
    rows = ["    (%s, %s)" % (q(b["name"]), q(code)) for b in brands for code in b["scopes"]]
    w(",\n".join(rows))
    w(") as v (brand_name, node_code)")
    w("join brand b on b.name = v.brand_name")
    w("join category c on c.code = v.node_code;")
    return "\n".join(out) + "\n"


def build():
    return generate(load(FROZEN / "category_tax_map.json"), load(FROZEN / "schemas.json"),
                    load(FROZEN / "extra_product_types.json"), load(FROZEN / "starter_brands.json"))


def main(argv):
    if "--refreeze" in argv:
        shutil.copyfile(LIVE_DATASET, FROZEN / "category_tax_map.json")
        shutil.copyfile(LIVE_SCHEMAS, FROZEN / "schemas.json")
        argv = [a for a in argv if a != "--refreeze"] + ["--write"]
    sql = build()
    if "--check" in argv:
        if not OUT.exists() or OUT.read_text(encoding="utf-8") != sql:
            print("gen_catalog_baseline: %s is stale; run python3 tools/taxonomy/gen_catalog_baseline.py --write"
                  % OUT.relative_to(REPO))
            return 1
        live = LIVE_DATASET.read_bytes() != (FROZEN / "category_tax_map.json").read_bytes() or \
            LIVE_SCHEMAS.read_bytes() != (FROZEN / "schemas.json").read_bytes()
        print("gen_catalog_baseline: ok%s" % ("; the live dataset has moved on since the baseline (the importer applies "
                                               "the difference at start-up, no migration)" if live else ""))
        return 0
    if "--write" in argv:
        OUT.write_text(sql, encoding="utf-8")
        print("wrote %s (%d bytes)" % (OUT.relative_to(REPO), len(sql.encode("utf-8"))))
        return 0
    sys.stdout.write(sql)
    return 0


if __name__ == "__main__":
    sys.exit(main(sys.argv[1:]))
