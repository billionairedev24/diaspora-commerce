"""Reference evaluator for category_tax_map.json (the golden behaviour the Java tax-returns engine must reproduce).

resolve() never guesses: missing facts give MISSING_FACTS, never a rate. Decision rows are evaluated in order, first match wins.
Status precedence after a row matched: outcome BLOCKED -> BLOCKED; else category gate CONFLICT_OPEN, BLOCKED_BUNDLE (-> BLOCKED),
NEEDS_APPROVAL, REFINE_CODE; else RESOLVED. NOT TAX OR LEGAL ADVICE (draft; Texas tax professional sign-off required).
"""
import json
from pathlib import Path

HERE = Path(__file__).resolve().parent


def load(name="category_tax_map.json"):
    return json.loads((HERE / name).read_text(encoding="utf-8"))


def _num(v):
    return v if isinstance(v, (int, float)) and not isinstance(v, bool) else None


def evaluate(expr, facts):
    """Evaluate a condition expression over a flat facts dict; null (None) facts make every leaf false."""
    if expr is None:
        return True
    if "all" in expr:
        return all(evaluate(e, facts) for e in expr["all"])
    if "any" in expr:
        return any(evaluate(e, facts) for e in expr["any"])
    if "not" in expr:
        return not evaluate(expr["not"], facts)
    op, v = expr["op"], facts.get(expr["fact"])
    if op == "isTrue":
        return v is True
    if op == "isFalse":
        return v is False
    if v is None:
        return False
    if op == "eq":
        return v == expr["value"]
    if op == "in":
        return v in expr["values"]
    n = _num(v)
    if n is None:
        return False
    return {"gt": n > expr.get("value", 0), "gte": n >= expr.get("value", 0), "lt": n < expr.get("value", 0), "lte": n <= expr.get("value", 0)}[op]


def fact_expressions(expr):
    """Fact ids referenced by an expression."""
    if expr is None:
        return set()
    out = set()
    for k in ("all", "any"):
        for e in expr.get(k, []):
            out |= fact_expressions(e)
    if "not" in expr:
        out |= fact_expressions(expr["not"])
    if "fact" in expr:
        out.add(expr["fact"])
    return out


def required_facts(tax_map, category, values):
    """Refs of the category's facts that are required for these values and absent or invalid: [(ref, message)]."""
    facts = {f["id"]: f for f in tax_map["facts"]}
    missing = []
    for ref in category["facts"]:
        f = facts[ref["fact"]]
        if not ref["required"] or not evaluate(ref["requiredWhen"], values):
            continue
        v = values.get(f["id"])
        if v is None or v == "":
            missing.append((ref, "%s is needed. %s" % (f["label"], f["help"])))
        elif f["kind"] == "CHOICE":
            allowed = ref.get("choices") or [c["value"] for c in f["choices"]]
            if v not in allowed:
                missing.append((ref, "%s: '%s' is not one of the choices." % (f["label"], v)))
    return missing


def resolve(tax_map, category_code, facts=None, seller_behaviour=None, context=None):
    cat = next((c for c in tax_map["categories"] if c["code"] == category_code), None)
    base = {"ruleVersion": tax_map["ruleVersion"], "outcome": None, "internalTaxCode": None, "providerCodes": {}, "missingFacts": [], "rowId": None, "reason": None}
    if cat is None:
        return dict(base, status="MISSING_FACTS", reason="Unknown category '%s'." % category_code)
    values = {}
    for part in (facts, seller_behaviour, context):
        values.update({k: v for k, v in (part or {}).items() if v is not None})
    missing = required_facts(tax_map, cat, values)
    if missing:
        return dict(base, status="MISSING_FACTS", missingFacts=[{"factId": r["fact"], "message": m} for r, m in missing],
                    reason="Facts are missing; the tax is never guessed.")
    # drop values of facts that are not required here, so a stale answer cannot steer the decision
    required = {r["fact"] for r in cat["facts"] if r["required"] and evaluate(r["requiredWhen"], values)}
    values = {k: v for k, v in values.items() if k in required}
    matches = [r for r in cat["decisionTable"] if evaluate(r["when"], values)]
    if not matches:
        return dict(base, status="MISSING_FACTS", reason="No rule matches these facts; escalate to tax review.")
    row = matches[0]
    res = dict(base, outcome=row["outcome"], internalTaxCode=row["internalTaxCode"], providerCodes={k: v for k, v in row["providerCodes"].items() if v},
               rowId=row["id"], reason=row["condition"])
    gate = cat["status"]
    if row["outcome"] == "BLOCKED" or gate == "BLOCKED_BUNDLE":
        res["status"] = "BLOCKED"
    elif gate in ("CONFLICT_OPEN", "NEEDS_APPROVAL", "REFINE_CODE"):
        res["status"] = gate
    else:
        res["status"] = "RESOLVED"
    if res["status"] != "RESOLVED":
        res["reason"] = "%s (%s)" % (row["condition"], "; ".join(r["text"] for r in cat["statusReasons"]) or "blocked")
    return res
