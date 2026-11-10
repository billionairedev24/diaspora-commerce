#!/usr/bin/env python3
"""Merge the category tax verification matrix results into docs/engineering/category-tax-verification.md.

    python3 tools/taxonomy/verification_report.py            # write the report
    python3 tools/taxonomy/verification_report.py --stdout   # print it instead

Inputs (written by the Java tests, `make category-tax-verify` runs them first):
    java/services/catalog/target/category-tax-matrix/catalog.json       CategoryTaxMatrixIT          publish gate (a, b)
    java/services/tax-returns/target/category-tax-matrix/tax-returns.json  CategoryTaxMatrixTest     resolve and Texas quotes (c, d)
    java/services/orders/target/category-tax-matrix/orders.json         CategoryTaxMatrixTest        order snapshot and event line (e)
Exit status 1 when any category FAILs or an input is missing.
"""
import collections
import datetime
import json
import sys
from pathlib import Path

ROOT = Path(__file__).resolve().parents[2]
TAXONOMY = ROOT / "tools/taxonomy"
INPUTS = {
    "catalog": ROOT / "java/services/catalog/target/category-tax-matrix/catalog.json",
    "tax-returns": ROOT / "java/services/tax-returns/target/category-tax-matrix/tax-returns.json",
    "orders": ROOT / "java/services/orders/target/category-tax-matrix/orders.json",
}
OUT = ROOT / "docs/engineering/category-tax-verification.md"


def load():
    data, missing = {}, []
    for name, path in INPUTS.items():
        if path.exists():
            data[name] = json.loads(path.read_text(encoding="utf-8"))
        else:
            missing.append(str(path.relative_to(ROOT)))
    return data, missing


def build(data):
    tax_map = json.loads((TAXONOMY / "category_tax_map.json").read_text(encoding="utf-8"))
    dept_name = {d["code"]: d["name"] for d in tax_map["departments"]}
    by_code = {c["code"]: c for c in tax_map["categories"]}
    cat = {c["categoryCode"]: c for c in data["catalog"]["categories"]}
    tr = collections.defaultdict(list)
    for s in data["tax-returns"]["scenarios"]:
        tr[s["categoryCode"]].append(s)
    neg_tr = {n["categoryCode"]: n for n in data["tax-returns"]["negatives"]}
    od = collections.defaultdict(list)
    for s in data["orders"]["scenarios"]:
        od[s["categoryCode"]].append(s)

    rows, totals = [], collections.Counter()
    for code, c in cat.items():
        d = by_code.get(code)
        status = c["status"]
        dept = dept_name[d["department"]] if d else "Fashion & Accessories"
        scen = c["scenarios"]
        n_rows = len(d["decisionTable"]) if d else 0
        closed = status in ("NEEDS_APPROVAL", "BLOCKED_BUNDLE", "CONFLICT_OPEN")
        results = collections.Counter(s.get("publish", {}).get("result", "?") for s in scen)
        if closed:
            publish = "Refused: category closed (%d/%d)" % (results.get("REFUSED_CLOSED_CATEGORY", 0), len(scen))
        elif status == "REFINE_CODE":
            publish = "Held for the extra code check, not live (%d/%d)" % (sum(1 for s in scen if s["publish"].get("gate") == "REFINE_CODE" and s["publish"].get("refusedBeforeApproval")), len(scen))
        else:
            live = sum(1 for s in scen if s["publish"].get("result") == "LIVE")
            proof = sum(1 for s in scen if s["publish"].get("statusAfterSubmit") == "MISSING_FACTS")
            publish = "PENDING_REVIEW, then READY and LIVE after tax review (%d/%d)" % (live, len(scen))
            if proof:
                publish += "; proof upload needed first on %d" % proof
        outcomes = collections.Counter()
        for s in tr.get(code, []):
            exp = s["expected"]
            outcomes[exp["outcome"] if exp["resolveStatus"] == "RESOLVED" else "BLOCKED (%s)" % exp["resolveStatus"]] += 1
        outcome_txt = ", ".join("%s %d" % (k, v) for k, v in sorted(outcomes.items())) or "none (no profile)"
        ff = c["formFields"]
        fields = "%d tax, %d proof, %d detail" % (ff["taxQuestions"], ff["evidenceQuestions"], ff["detailQuestions"])
        neg = c.get("negatives") or {}
        n_removed = len(neg.get("requiredFactRemoved", []))
        n_wrong = len(neg.get("wrongType", []))
        ok = all(s.get("pass") for s in scen)
        ok &= all(s.get("pass") for s in tr.get(code, []))
        ok &= all(s.get("pass") for s in od.get(code, []))
        ok &= all(r.get("pass") for r in neg.get("requiredFactRemoved", [])) and all(r.get("pass") for r in neg.get("wrongType", []))
        tn = neg_tr.get(code)
        if tn:
            ok &= all(r.get("pass") for r in tn["requiredFactRemoved"]) and all(r.get("pass") for r in tn["wrongType"])
        if not closed and status != "REFINE_CODE" and not scen:
            ok = False
        rows.append({"dept": dept, "code": code, "name": c["name"], "status": status, "rows": n_rows, "publish": publish, "outcomes": outcome_txt,
                     "fields": fields, "neg": "%d removed, %d wrong type" % (n_removed + len((tn or {}).get("requiredFactRemoved", [])),
                                                                         n_wrong + len((tn or {}).get("wrongType", []))), "ok": ok,
                     "types": len(c["productTypes"])})
        totals["PASS" if ok else "FAIL"] += 1
    return rows, totals, tax_map


def render(data, rows, totals, tax_map):
    today = datetime.date.today().isoformat()
    scen_tr = data["tax-returns"]["scenarios"]
    quotes = sum(len(s["quotes"]) for s in scen_tr)
    neg_c = sum(len(c.get("negatives", {}).get("requiredFactRemoved", [])) + len(c.get("negatives", {}).get("wrongType", []))
                for c in data["catalog"]["categories"])
    neg_t = sum(len(n["requiredFactRemoved"]) + len(n["wrongType"]) for n in data["tax-returns"]["negatives"])
    catalog_runs = sum(len(c["scenarios"]) for c in data["catalog"]["categories"])
    fails = sum(len(data[k]["failures"]) for k in data)
    status_counts = collections.Counter(r["status"] for r in rows)
    out = []
    w = out.append
    w("# Category tax verification (end to end, all categories)\n")
    w("**Status:** generated by `tools/taxonomy/verification_report.py` on %s from the matrix tests listed below. **Not tax or legal advice.** "
      "This proves the software does what the dataset says; it does not prove the dataset is right: that needs the Texas tax professional "
      "(see the last section). Do not edit this file by hand: re-run `make category-tax-verify`.\n" % today)
    w("## Result\n")
    w("| | |\n|---|---|")
    w("| Categories | **%d PASS, %d FAIL** of %d (73 from the dataset + the provisional Fabrics & textiles) |" % (totals["PASS"], totals["FAIL"], len(rows)))
    w("| Decision rows (Appendix A scenarios) | 105, each run through resolve, the publish gate, a Texas quote and the order snapshot |")
    w("| Catalog publish gate runs | %d (every row on the category's first product type, the first row on every other product type) |" % catalog_runs)
    w("| Texas checkout quotes | %d (taxable lines in three ZIPs: 77002 8.25%%, 78205 8.00%%, 79401 6.25%%; exempt and blocked lines in one) |" % quotes)
    w("| Negative checks | %d at catalog (each required fact removed, text in a number or a yes/no) and %d at tax-returns |" % (neg_c, neg_t))
    w("| Individual assertion failures | %d |" % fails)
    w("| Status gates | " + ", ".join("%s %d" % (k, v) for k, v in sorted(status_counts.items())) + " |\n")
    w("## How it is verified\n")
    w("| Step | What | Where | Kind |\n|---|---|---|---|")
    w("| (a) build a valid minimal product through catalog's real validation | facts per variant, the seller's behaviour on the listing, required details, evidence uploaded where the rule needs proof | `CategoryTaxMatrixIT` (catalog) on the tree seeded by migration V20 in PostgreSQL | integration test (Testcontainers) |")
    w("| (b) publish gate | PENDING_REVIEW after submit (tax review required), READY once tax review approves, LIVE; gated categories refused or held; going live before approval refused | same | same |")
    w("| (c) resolve | outcome, internal code, Stripe code, status of every row | `CategoryTaxMatrixTest` (tax-returns), the real 73 profiles and the provider code map | unit test, real engine, in-memory ports |")
    w("| (d) checkout quote, Texas | EXEMPT 0; TAXABLE state 6.25% + local per the ZIP table (migration V4) on the discounted items and the delivery, rounded half up once per line; gated or missing: BLOCKED with the plain message and no rate | same | same |")
    w("| (e) order placement | the line snapshot (outcome, internal code, Stripe code, rule version, address precision, delivery share) and the order-placed line event; undecided lines stop the checkout with the plain message | `CategoryTaxMatrixTest` (orders) | unit test, the real checkout tax step, tax-returns' answer from the dataset |")
    w("| Implementations agree | the Python reference, tax-returns' evaluator and catalog's local resolver give the same decision on all 659 golden cases (105 scenarios, 440 fact combinations, 114 required facts removed) | `DecisionCasesTest`, `LocalDatasetTaxResolverGoldenTest`, `tools/taxonomy/decision_cases.py --check` | unit tests |")
    w("\nThe quote and the order steps use fakes at the HTTP edges (tax-returns is not called over HTTP from orders here): the shapes of those calls are held by "
      "`PublicVariantContractTest` / `HttpProductFactsContractTest` / `HttpProductFactsClientTest` (catalog's public variant, which tax-returns and orders read) and by "
      "the conformance tests of each contract. Nothing here drives a browser.\n")
    w("## Commands to re-run\n")
    w("```bash\nmake category-tax-verify                      # everything below, then rewrites this file (catalog's IT needs Docker)\n"
      "make category-tax-verify CATALOG_IT=0         # skip the catalog IT (no Docker); the report then needs its JSON from an earlier run\n\n"
      "make taxonomy-check                           # dataset, decision cases, reference evaluator\n"
      "cd java && ./mvnw -pl services/tax-returns test -Dtest='DecisionCasesTest,CategoryTaxMatrixTest'\n"
      "cd java && ./mvnw -pl services/orders test -Dtest=CategoryTaxMatrixTest\n"
      "cd java && ./mvnw -pl services/catalog test -Dtest=LocalDatasetTaxResolverGoldenTest\n"
      "cd java && ./mvnw -pl services/catalog verify -Dit.test=CategoryTaxMatrixIT -Dtest=NoUnitTests -Dsurefire.failIfNoSpecifiedTests=false   # Docker\n"
      "python3 tools/taxonomy/verification_report.py\n```\n")
    w("## Per category\n")
    w("Form fields: tax questions, proof (evidence file) questions and detail questions of the category's first product type. "
      "Negative checks: required facts removed and wrong-type answers tried (catalog plus tax-returns). Outcomes verified are the dataset's: "
      "EXEMPT and TAXABLE rows were quoted and snapshotted, the others were verified to block.\n")
    current = None
    for r in sorted(rows, key=lambda x: (x["dept"], x["name"])):
        if r["dept"] != current:
            current = r["dept"]
            w("\n### %s\n" % current)
            w("| Category | Status gate | Rows | Publish result (catalog) | Outcomes verified | Form fields | Negatives | Result |\n|---|---|---|---|---|---|---|---|")
        w("| %s (`%s`)%s | %s | %d | %s | %s | %s | %s | **%s** |" % (r["name"], r["code"], ", %d product types" % r["types"] if r["types"] > 1 else "",
                                                                     r["status"], r["rows"], r["publish"], r["outcomes"], r["fields"], r["neg"],
                                                                     "PASS" if r["ok"] else "FAIL"))
    w("\n## Real defects the matrix found, and their fixes\n")
    w("Found while wiring the four independently tested parts together (all fixed in this change; each has a test that fails without the fix):\n")
    w("1. **Catalog could not start.** The tree seed was a Java Flyway migration that extended `BaseJavaMigration`, which refuses class names that do not start with V or R; the service failed at start-up. The tree is now plain generated SQL in the catalog baseline (`V1_1__initial_data.sql`, `tools/taxonomy/gen_catalog_baseline.py`), so no Java migration remains.")
    w("2. **No product could ever pass the tax gate.** Products sit on a product-type node, but the tax profile and status live on the category above it; the gate read the product type's (empty) profile and answered NEEDS_APPROVAL for everything, and no tax event was published. Unit tests had faked a profile on the leaf. Product types now read their category's profile and status (`JdbcCategoryRepository.findById`), and a status change reaches their products (`findByTaxProfile`). Caught by `CategoryTaxMatrixIT` on the real tree.")
    w("3. **tax-returns read the wrong part of catalog's answer.** Its on-the-fly lookup read `taxFacts` and the category from the root of `GET /v1/public/variants/{atin}`, but they sit under `variant` and `product`; and catalog returned no category code or listing behaviour at all. Catalog now returns `product.categoryCode` and `product.listing.sellerBehaviour`; tax-returns reads `variant.taxFacts`, `product.categoryCode`, `product.listing.sellerBehaviour` and `product.sellerOrgId` (the behaviour stays with the owner's listing). Held by one fixture, `contracts/examples/catalog-public-variant.json`, read by catalog's, tax-returns' and orders' tests.")
    w("4. **Checkout never told tax-returns the seller's behaviour or the order.** Orders now sends `orderId` and each line's `sellerBehaviour` (from the owner's catalog listing, never another seller's); a category that needs it and does not get it blocks the line (MISSING_FACTS). The order-placed line event now carries `taxAddressPrecision`, which the tax ledger reads.")
    w("5. **Stored product types failed to load.** The starter `custom` and `electronics` types lack the `taxFact` and `evidence` flags and failed to read; absent now reads as false.")
    w("6. **Deployed services could not reach each other for tax.** Orders had no tax-returns address outside local (`localhost`), catalog had none for its resolver and did not request the `tax:classify` scope: every checkout and every publish would have failed in dev, uat and prod. Fixed, and `tools/ci/check_env_config.py` now checks it. Orders' perishable product types and commission categories still named the retired tree (`perishable`, `grocery`); they now name the new product types.")
    w("7. **Kafka ACL:** catalog consumes `at.taxreturns.events` but was not listed as a consumer.\n")
    w("## Known limits, by design (not failures)\n")
    w("- **Tax holiday calendar not built (TX-08).** Rows that need an active exemption window (car seats, scenario 73) resolve as the dataset says, but a quote prices the ordinary sale of the category: always the taxable direction.")
    w("- **ZIP rates, not exact addresses (TX-03).** Every quote line records `addressPrecision = ZIP`. A ZIP can span several local jurisdictions; this is the documented launch limitation.")
    w("- **Delivery attribution for mixed baskets (TX-07)** is the proportional allocation, an owner decision still open; **bundles (TX-09)** and **platform fee classification (TX-12)** are not built.")
    w("- **Seller is a bakery** is asked of the seller as part of \"Your listing\"; the seller profile flag (planned migration V89) does not exist yet.")
    w("- **Not run here:** the browser forms, Stripe or Avalara calls, the real Kafka path between catalog, tax-returns and orders, the HTTP calls between services (held by contract tests instead), mobile checkout.\n")
    w("## What still needs a human\n")
    w("1. **Browser pass of every category form** in Seller Center (Add product), with the product form's \"Price & stock\" and \"Your listing\" wording. The 13 department forms to walk through: "
      + "; ".join(sorted(d["name"] for d in tax_map["departments"])) + ". Plus the special groups that need their own pass:")
    w("   - heated food and utensils (`sold_heated`, `heated_by_seller`, `utensils_provided`, `seller_is_bakery`): prepared meals, bakery products, ready-to-drink coffee and tea;")
    w("   - label type (Nutrition Facts or Supplement Facts) for beverages, supplements and packaged food;")
    w("   - live versus recorded (event tickets, fitness classes, tutoring, online courses), and printed versus digital books and media;")
    w("   - bundles and gift baskets (blocked) and gift cards (held);")
    w("   - evidence upload: label photo, Drug Facts, Supplement Facts (PDF, JPEG or PNG up to 10 MB), the readiness block and its plain messages, and the staff tax review screens (Settings, Sales tax).")
    w("2. **Stripe code verification** against Stripe's current product tax code list: all 105 internal codes carry the workbook's Stripe code, none is marked verified. Stripe was never called. The cutover runbook (`docs/runbooks/tax-provider-cutover.md`) has the procedure.")
    w("3. **Texas tax professional sign-off** on the rules, the interpretations recorded as row notes (packaged snacks, juice, ready-to-drink coffee and tea, gift cards, the ten triggers), the seller wording, the ZIP-rate limitation and the shipping allocation. Nothing is switched on for real customers before it.")
    w("4. **Owner decisions:** the provisional **Fabrics & textiles** category (held NEEDS_APPROVAL; it has no profile); **gift cards** (held NEEDS_APPROVAL); and the five open items of the source document: (1) flavored water and soda, (2) tutoring and consulting, (3) owner and deadline for the generic-code categories, (4) named approver for digital products, photography and media, event tickets and gift cards, (5) the delivery attribution of mixed baskets.")
    w("5. **Commission categories for the new tree.** Orders maps the new product types to payments' fee schedule categories (groceries, fashion, beauty, home & art, books, health, services); Electronics, Baby & Kids, Pets, Digital and Other have no category rate and take the default. The owner should set them.")
    return "\n".join(out) + "\n"


def main():
    data, missing = load()
    if missing:
        sys.stderr.write("missing matrix results (run `make category-tax-verify`):\n  " + "\n  ".join(missing) + "\n")
        return 1
    rows, totals, tax_map = build(data)
    text = render(data, rows, totals, tax_map)
    if "--stdout" in sys.argv:
        sys.stdout.write(text)
    else:
        OUT.write_text(text, encoding="utf-8")
        print("wrote %s: %d PASS, %d FAIL" % (OUT.relative_to(ROOT), totals["PASS"], totals["FAIL"]))
    return 0 if totals["FAIL"] == 0 and not any(data[k]["failures"] for k in data) else 1


if __name__ == "__main__":
    sys.exit(main())
