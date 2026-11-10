#!/usr/bin/env python3
"""Golden decision cases for every implementation of the tax rules (reference evaluator, catalog's local resolver,
tax-returns' evaluator, the verification matrix).

    python3 tools/taxonomy/decision_cases.py          # write decision_cases.jsonl
    python3 tools/taxonomy/decision_cases.py --check  # fail when the committed file is stale

One JSON object per line:
  SCENARIO  the 105 Appendix A rows (inputs from scenarios.json)
  COMBO     for every category, every combination of its facts over the validator's value domains (440 in all)
  MISSING   for every scenario, each required fact removed in turn (never guessed: MISSING_FACTS, no outcome)
Each line carries the reference evaluator's answer in `expect`. A Java test per implementation reads the same file and must
reproduce every line, so the implementations are identical on every case.
"""
import itertools
import json
import sys
from pathlib import Path

HERE = Path(__file__).resolve().parent
sys.path.insert(0, str(HERE))

from evaluator import evaluate, load, required_facts, resolve  # noqa: E402
from validate import DOMAIN  # noqa: E402

OUT = HERE / "decision_cases.jsonl"


def expectation(res):
    return {"status": res["status"], "outcome": res["outcome"], "internalTaxCode": res["internalTaxCode"],
            "stripeCode": res["providerCodes"].get("STRIPE"), "rowId": res["rowId"],
            "missingFactIds": sorted(m["factId"] for m in res["missingFacts"])}


def split(tax_map, values):
    """Facts by owner: VARIANT/STAFF facts, OFFER/SELLER behaviour, CONTEXT (what tax-returns' resolve takes)."""
    owner = {f["id"]: f["owner"] for f in tax_map["facts"]}
    facts, behaviour, context = {}, {}, {}
    for k, v in values.items():
        o = owner[k]
        (behaviour if o in ("OFFER", "SELLER") else context if o == "CONTEXT" else facts)[k] = v
    return facts, behaviour, context


def cases():
    tax_map, scen = load(), load("scenarios.json")
    facts_by_id = {f["id"]: f for f in tax_map["facts"]}
    out = []
    for s in scen["scenarios"]:
        i = s["inputs"]
        out.append({"id": "scenario-%03d" % s["scenario"], "kind": "SCENARIO", "category": s["categoryCode"], "facts": i["facts"],
                    "sellerBehaviour": i["sellerBehaviour"], "context": i["context"],
                    "expect": expectation(resolve(tax_map, s["categoryCode"], i["facts"], i["sellerBehaviour"], i["context"]))})
    for c in tax_map["categories"]:
        refs = c["facts"]
        doms = []
        for r in refs:
            f = facts_by_id[r["fact"]]
            doms.append(r.get("choices") or [x["value"] for x in f["choices"]] if f["kind"] == "CHOICE" else DOMAIN[f["kind"]])
        for n, combo in enumerate(itertools.product(*doms)):
            values = {r["fact"]: v for r, v in zip(refs, combo)}
            f, b, x = split(tax_map, values)
            out.append({"id": "combo-%s-%03d" % (c["code"], n), "kind": "COMBO", "category": c["code"], "facts": f, "sellerBehaviour": b,
                        "context": x, "expect": expectation(resolve(tax_map, c["code"], f, b, x))})
    for s in scen["scenarios"]:
        c = next(c for c in tax_map["categories"] if c["code"] == s["categoryCode"])
        i = s["inputs"]
        values = {}
        for part in i.values():
            values.update(part)
        for ref in c["facts"]:
            if not ref["required"] or not evaluate(ref["requiredWhen"], values):
                continue
            without = {k: v for k, v in values.items() if k != ref["fact"]}
            f, b, x = split(tax_map, without)
            out.append({"id": "missing-%03d-%s" % (s["scenario"], ref["fact"]), "kind": "MISSING", "category": c["code"], "facts": f,
                        "sellerBehaviour": b, "context": x, "removed": ref["fact"],
                        "expect": expectation(resolve(tax_map, c["code"], f, b, x))})
    return out


def dump(rows):
    return "".join(json.dumps(r, sort_keys=True, ensure_ascii=False, separators=(",", ":")) + "\n" for r in rows)


def main():
    text = dump(cases())
    if "--check" in sys.argv:
        if not OUT.exists() or OUT.read_text(encoding="utf-8") != text:
            sys.exit("tools/taxonomy/decision_cases.jsonl is stale: run python3 tools/taxonomy/decision_cases.py")
        print("decision_cases.jsonl is current (%d cases)" % text.count("\n"))
        return
    OUT.write_text(text, encoding="utf-8")
    print("wrote %d cases to %s" % (text.count("\n"), OUT))


if __name__ == "__main__":
    main()
