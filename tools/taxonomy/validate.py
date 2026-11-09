"""Validator for category_tax_map.json and scenarios.json; used by build_map.py --check and the tests. Returns a list of errors."""
import itertools
import re

from evaluator import evaluate, fact_expressions, load, required_facts, resolve

KEBAB = re.compile(r"^[a-z0-9]+(-[a-z0-9]+)*$")
DOMAIN = {"DECIMAL": [0.5, 2.4, 2.5, 9.0], "INTEGER": [0, 1, 2, 5], "PERCENT": [0, 10, 50, 50.5, 100], "FILE": ["evidence://x"], "BOOLEAN": [True, False]}


def validate(tax_map, scen, source):
    err = []
    cats, facts = tax_map["categories"], {f["id"]: f for f in tax_map["facts"]}
    depts = {d["code"]: d["name"] for d in tax_map["departments"]}
    if len(depts) != 13: err.append("expected 13 departments, got %d" % len(depts))
    if len(cats) != 73: err.append("expected 73 categories, got %d" % len(cats))
    if len(scen["scenarios"]) != 105: err.append("expected 105 scenarios, got %d" % len(scen["scenarios"]))
    if len(tax_map["factGroups"]) != 10: err.append("expected 10 fact groups (the document lists ten triggers), got %d" % len(tax_map["factGroups"]))
    if depts.get("pets") != "Pets": err.append("department 'pets' must be spelled 'Pets'")
    for label, vals in (("department codes", list(depts)), ("category codes", [c["code"] for c in cats]), ("category names", [c["name"] for c in cats]),
                        ("fact ids", [f["id"] for f in tax_map["facts"]]), ("row ids", [r["id"] for c in cats for r in c["decisionTable"]]),
                        ("internal tax codes", [r["internalTaxCode"] for c in cats for r in c["decisionTable"]]),
                        ("product type codes", [t["code"] for c in cats for t in c["productTypes"]])):
        if len(vals) != len(set(vals)): err.append("duplicate %s: %s" % (label, sorted({v for v in vals if vals.count(v) > 1})))
        for v in vals:
            if label.endswith("codes") and label != "internal tax codes" and not KEBAB.match(v): err.append("%s not kebab-case: %s" % (label, v))
    if sum(1 for c in cats if c["department"] not in depts): err.append("category with unknown department")
    for d in depts:
        if not any(c["department"] == d for c in cats): err.append("department without categories: " + d)
    # facts and groups
    for f in tax_map["facts"]:
        if f["kind"] not in tax_map["factKinds"] or f["owner"] not in tax_map["owners"]: err.append("bad kind/owner on fact " + f["id"])
        if f["kind"] == "CHOICE" and not f.get("choices"): err.append("CHOICE fact without choices: " + f["id"])
        if f["kind"] in ("DECIMAL", "PERCENT") and not f["unit"]: err.append("fact without unit: " + f["id"])
        if f["kind"] == "FILE" and not f["evidence"]: err.append("FILE fact must be evidence: " + f["id"])
        if not f["help"]: err.append("fact without help text: " + f["id"])
    for g in tax_map["factGroups"]:
        for fid in g["facts"]:
            if fid not in facts: err.append("group %s references unknown fact %s" % (g["id"], fid))
    grouped = [x for g in tax_map["factGroups"] for x in g["facts"]]
    for fid in facts:
        if fid not in grouped: err.append("fact in no group: " + fid)
    # categories and tables
    src = {s["n"]: s for s in source["scenarios"]}
    covered = []
    for c in cats:
        if not c["decisionTable"]: err.append("category without a decision table: " + c["code"])
        refs = {r["fact"] for r in c["facts"]}
        for r in c["facts"]:
            if r["fact"] not in facts: err.append("%s references unknown fact %s" % (c["code"], r["fact"]))
            for x in fact_expressions(r["requiredWhen"]):
                if x not in refs: err.append("%s requiredWhen uses fact not on the category: %s" % (c["code"], x))
        for row in c["decisionTable"]:
            covered.append(row["scenario"])
            for x in fact_expressions(row["when"]):
                if x not in refs: err.append("%s row %s uses fact not on the category: %s" % (c["code"], row["id"], x))
            s = src[row["scenario"]]
            if row["stripeCode"] not in c["stripeCodes"]: err.append("%s row %s stripe code not in the category list" % (c["code"], row["id"]))
            if row["stripeCode"] != s["stripeCode"] or s["subcategory"].replace("pets", "Pets") != c["name"] or depts[c["department"]] != s["department"]:
                err.append("%s row %s does not match Appendix row %d" % (c["code"], row["id"], row["scenario"]))
        if c["status"] not in tax_map["statuses"]: err.append("bad status " + c["code"])
        if c["status"] != "ACTIVE" and not c["statusReasons"]: err.append("gated category without reason: " + c["code"])
        if c["status"] == "CONFLICT_OPEN" and len(c["conflictingCodes"]) != 2: err.append("conflict without both codes: " + c["code"])
    if sorted(covered) != list(range(1, 106)): err.append("scenarios not covered exactly once by decision rows")
    counts = {}
    for c in cats: counts[c["status"]] = counts.get(c["status"], 0) + 1
    if counts != {"ACTIVE": 57, "NEEDS_APPROVAL": 7, "REFINE_CODE": 6, "CONFLICT_OPEN": 2, "BLOCKED_BUNDLE": 1}: err.append("unexpected status counts %s" % counts)
    # every scenario reachable by exactly one decision row
    by_code = {c["code"]: c for c in cats}
    for s in scen["scenarios"]:
        c = by_code.get(s["categoryCode"])
        if c is None:
            err.append("scenario %d has no category %s" % (s["scenario"], s["categoryCode"]))
            continue
        vals = {}
        for part in s["inputs"].values(): vals.update(part)
        if required_facts(tax_map, c, vals): err.append("scenario %d inputs miss required facts" % s["scenario"])
        hit = [r["id"] for r in c["decisionTable"] if evaluate(r["when"], vals)]
        if hit != [s["rowId"]]: err.append("scenario %d matches rows %s, expected [%s]" % (s["scenario"], hit, s["rowId"]))
        res = resolve(tax_map, s["categoryCode"], s["inputs"]["facts"], s["inputs"]["sellerBehaviour"], s["inputs"]["context"])
        exp = s["expect"]
        got = {"outcome": res["outcome"], "internalTaxCode": res["internalTaxCode"], "stripeCode": res["providerCodes"].get("STRIPE"), "resolveStatus": res["status"]}
        if any(got[k] != exp[k] for k in got): err.append("scenario %d: got %s expected %s" % (s["scenario"], got, {k: exp[k] for k in got}))
    # exhaustiveness: for every combination of a category's facts, exactly one row matches the REQUIRED facts
    for c in cats:
        refs = c["facts"]
        doms = []
        for r in refs:
            f = facts[r["fact"]]
            doms.append(r.get("choices") or [x["value"] for x in f["choices"]] if f["kind"] == "CHOICE" else DOMAIN[f["kind"]])
        for combo in itertools.product(*doms):
            vals = {r["fact"]: v for r, v in zip(refs, combo)}
            req = {r["fact"] for r in refs if r["required"] and evaluate(r["requiredWhen"], vals)}
            used = {k: v for k, v in vals.items() if k in req}
            n = sum(1 for row in c["decisionTable"] if evaluate(row["when"], used))
            if n != 1:
                err.append("%s: %d rows match %s" % (c["code"], n, used)); break
    return err
