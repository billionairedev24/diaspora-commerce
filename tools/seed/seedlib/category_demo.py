"""Demo answers for the category tax tree: valid tax facts for any category, and a demo product for every category that
can be published.

The tree and the questions of every category come from tools/taxonomy/category_tax_map.json (the catalog imports the same
file). `minimal_facts` answers a category's questions the way the catalog's matrix test does, without a proof file (the
seeder cannot upload one), so the product is accepted by the publish gate. NOT tax advice: demo data only.
"""
from __future__ import annotations

import itertools
import json
import sys
from pathlib import Path
from typing import Any

ROOT = Path(__file__).resolve().parents[3]
sys.path.insert(0, str(ROOT / "tools" / "taxonomy"))
from evaluator import evaluate  # noqa: E402  (the reference evaluator of the dataset)

MAP: dict[str, Any] = json.loads((ROOT / "tools" / "taxonomy" / "category_tax_map.json").read_text(encoding="utf-8"))
FACTS = {f["id"]: f for f in MAP["facts"]}
DEPARTMENTS = {d["code"]: d["name"] for d in MAP["departments"]}
CATEGORIES = {c["code"]: c for c in MAP["categories"]}
SELLER_ANSWERED = ("VARIANT", "OFFER", "SELLER")


def path_of(category: dict[str, Any], type_label: str | None = None) -> str:
    """The leaf path the catalog serves: department, category, product type (a single type of its own name is the category)."""
    label = type_label or category["productTypes"][0]["label"]
    return f"{DEPARTMENTS[category['department']]} › {category['name']} › {label}"


PATHS: dict[str, dict[str, Any]] = {path_of(c, t["label"]): c for c in MAP["categories"] for t in c["productTypes"]}


def _candidates(ref: dict[str, Any]) -> list[Any]:
    f = FACTS[ref["fact"]]
    if f["kind"] == "BOOLEAN":
        return [False, True]
    if f["kind"] == "DECIMAL":
        return [8.5]
    if f["kind"] == "INTEGER":
        return [2]
    if f["kind"] == "PERCENT":
        return [50]
    if f["kind"] == "CHOICE":
        return list(ref.get("choices") or [c["value"] for c in f["choices"]])
    return [None]


def minimal_facts(category: dict[str, Any]) -> tuple[dict[str, Any], dict[str, Any]]:
    """(variant facts, seller behaviour) that satisfy a category's questions with no proof file; ({}, {}) when it has none."""
    refs = [r for r in category["facts"] if FACTS[r["fact"]]["owner"] in SELLER_ANSWERED]
    if not refs:
        return {}, {}
    for combo in itertools.product(*[_candidates(r) for r in refs]):
        raw = {r["fact"]: v for r, v in zip(refs, combo) if v is not None}
        values = dict(raw)
        shown = [r for r in refs if evaluate(r["requiredWhen"], values)]
        values = {r["fact"]: raw[r["fact"]] for r in shown if r["fact"] in raw}
        if any(FACTS[r["fact"]]["kind"] == "FILE" and r["required"] and evaluate(r["requiredWhen"], values) for r in refs):
            continue
        if all(r["fact"] in values for r in shown if r["required"] and FACTS[r["fact"]]["kind"] != "FILE"):
            variant = {k: v for k, v in values.items() if FACTS[k]["owner"] == "VARIANT"}
            behaviour = {k: v for k, v in values.items() if FACTS[k]["owner"] != "VARIANT"}
            return variant, behaviour
    raise ValueError(f"no answers without a proof file satisfy {category['code']}")


def publishable(category: dict[str, Any]) -> bool:
    """Whether a category lets products go live: ACTIVE (the others are gated until staff open them)."""
    return category["status"] == "ACTIVE"


def coverage_titles() -> list[tuple[str, dict[str, Any]]]:
    """(leaf path, category) of every publishable category, one per product type."""
    return [(p, c) for p, c in PATHS.items() if publishable(c)]


def slug(path: str) -> str:
    """A short stable key part of a leaf path (types of the same category stay apart)."""
    import re
    return re.sub(r"[^a-z0-9]+", "-", path.split(" › ")[-1].lower()).strip("-")[:24]


def needs_proof(category: dict[str, Any]) -> bool:
    """True when every valid set of answers needs a proof file (OTC medicines: the Drug Facts label)."""
    try:
        minimal_facts(category)
    except ValueError:
        return True
    return False
