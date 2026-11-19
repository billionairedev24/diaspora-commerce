"""Answers to the catalog's publish gate (listing rules), derived from the catalog's own schema instead of hard-coded.

The catalog judges every listing by the rules of its category (`GET /v1/listing-rules?categoryCode=`: the status of each
field, its control, options and units) and by the product type's own details (`GET /v1/product-types/{code}`). This module
reads those two answers and fills what they ask for, per product: the description, the typed facts (net weight, servings,
shelf life, storage, ingredients, allergens, material, model, age grade), the dangerous goods answer, the package of every
variant, a barcode where the barcode is required and a label photo where one is needed. Nothing is keyed on a category:
a field is answered because the schema says it is shown and expects an answer, from the product's own data when the
dataset has it, else from a plausible default derived from the field's type, options and units.

`check_listing` is a port of the catalog's `ListingRuleChecker` + attribute checks, used offline by `make seed-check`
against recorded schemas. NOT tax or compliance data: demo values only.
"""
from __future__ import annotations

import hashlib
import math
import re
from dataclasses import dataclass, field
from typing import Any

ANSWER = ("required", "required_or_exemption")
# GS1 prefixes 200-299 are "restricted circulation": never assigned to a company, so a demo code cannot be a real product's.
DEMO_GTIN_PREFIX = "200"
MAX_EDGE_IN, MAX_WEIGHT_LB = 108.0, 150.0
DEFAULT_HANDLING_DAYS = 2
DANGEROUS = re.compile(r"\b(aerosol|spray|flammable|batter(?:y|ies)|candles?|cleaner|lighter|bleach|fuel)\b", re.I)

_UNIT_ALIASES = {"lbs": "lb", "pound": "lb", "pounds": "lb", "liter": "l", "liters": "l", "litre": "l", "litres": "l",
                 "fl. oz": "fl oz", "fl oz": "fl oz", "floz": "fl oz"}
_TO_LB = {"oz": 1 / 16, "lb": 1.0, "g": 1 / 453.592, "kg": 2.20462, "fl oz": 1.04 / 16, "ml": 1.05 / 473.176, "l": 2.2}
_MEASURE = re.compile(r"(\d+(?:[.,]\d+)?)\s*(fl\.?\s*oz|oz|lbs?|pounds?|kg|ml|g|liters?|litres?|l)\b", re.I)


# ---------------------------------------------------------------------------------------------------- measures
def _unit(raw: str) -> str:
    u = re.sub(r"\s+", " ", raw.lower().strip())
    return _UNIT_ALIASES.get(u, u)


def measures(text: str) -> list[tuple[float, str]]:
    """Every "<number> <unit>" in the text with a unit of the schema's weight and volume units (oz, lb, g, kg, fl oz, ml, l)."""
    out = []
    for m in _MEASURE.finditer(text or ""):
        unit = _unit(m.group(2))
        if unit in _TO_LB:
            out.append((float(m.group(1).replace(",", ".")), unit))
    return out


def _fmt(n: float) -> str:
    return str(int(n)) if float(n).is_integer() else f"{n:g}"


def pick(raw: str, options: list[str]) -> str:
    """The option that says what `raw` says: exact (any case), then one containing the other, else the first option."""
    low = (raw or "").strip().lower()
    for o in options:
        if o.lower() == low:
            return o
    if low:
        for o in options:
            if o.lower() in low or low in o.lower():
                return o
    return options[0]


# ---------------------------------------------------------------------------------------------------- rules
class Rules:
    """The answer of GET /v1/listing-rules for one category."""

    def __init__(self, response: dict[str, Any]):
        self.response = response
        self.fields: list[dict[str, Any]] = response.get("fields") or []
        self.by_id = {f["id"]: f for f in self.fields}

    def status(self, field_id: str) -> str:
        return self.by_id.get(field_id, {}).get("status", "hidden")

    def expects(self, field_id: str) -> bool:
        return self.status(field_id) in ANSWER

    def shown(self, field_id: str) -> bool:
        return self.status(field_id) != "hidden"

    @property
    def physical(self) -> bool:
        return self.shown("package_size")


def category_code(leaf: dict[str, Any], by_id: dict[str, dict[str, Any]]) -> str:
    """The code listing rules are looked up by: the category above a product type (the catalog's own rule), else the node's."""
    if leaf.get("level") == "PRODUCT_TYPE" and leaf.get("parentId") in by_id:
        return by_id[leaf["parentId"]].get("code") or leaf.get("code") or "general"
    return leaf.get("code") or "general"


# ---------------------------------------------------------------------------------------------------- answers
@dataclass
class Answers:
    attributes: dict[str, str] = field(default_factory=dict)  # rule-owned product attributes (override the type's defaults)
    description: str = ""
    dangerous: bool | None = None
    packages: list[dict[str, float] | None] = field(default_factory=list)  # one per variant (None: not physical)
    gtins: list[str | None] = field(default_factory=list)  # one per variant (None: none needed)
    label_wanted: bool = False  # a label photo is asked for (the seeder generates one)
    handling_days: int = DEFAULT_HANDLING_DAYS
    notes: list[str] = field(default_factory=list)


def description_for(p: Any) -> str:
    sentences = [p.title.rstrip(".") + "."] + [b.strip().rstrip(".") + "." for b in p.bullets]
    sentences.append(f"{p.community} staple from {p.origin}." if p.origin else f"{p.community} favourite.")
    return " ".join(sentences)[:4900]


def gtin13(seed: str) -> str:
    """A valid GTIN-13 (GS1 mod-10 check digit) in the restricted 200 prefix, from a stable seed: DEMO data, never a real code."""
    body = DEMO_GTIN_PREFIX + f"{int(hashlib.sha256(seed.encode()).hexdigest(), 16) % 10 ** 9:09d}"
    total = sum(int(d) * (3 if i % 2 == 0 else 1) for i, d in enumerate(reversed(body)))
    return body + str((10 - total % 10) % 10)


def package_for(texts: list[str], seed: str = "") -> dict[str, float]:
    """The box as shipped (inches, pounds) from the weight or volume the texts name (the variant's size, the product's unit),
    else a 1 lb box. The box is the contents plus 0.2 lb, shaped from the volume at about 45 cubic inches a pound."""
    net = 1.0
    for text in texts:
        found = measures(text)
        if found:
            n, unit = found[0]
            net = n * _TO_LB[unit]
            break
    weight = round(min(MAX_WEIGHT_LB - 1, max(0.1, net + 0.2)), 2)
    edge = max(24.0, weight * 45) ** (1 / 3)
    dims = [min(MAX_EDGE_IN - 1, max(1.0, round(edge * f, 1))) for f in (1.5, 1.0, 0.67)]
    return {"lengthIn": dims[0], "widthIn": dims[1], "heightIn": dims[2], "weightLb": weight}


def _measure_answer(f: dict[str, Any], raw: str, p: Any) -> str:
    units = [u.lower() for u in f.get("units") or []]
    if f["id"] == "shelf_life":
        found = [m for m in measures(raw) if m[1] in units] if raw else []
        m = re.match(r"^\s*(\d+)\s*(\w*)\s*$", raw or "")
        if m and (m.group(2).lower() in units or not m.group(2)):
            unit = m.group(2).lower() or ("days" if "days" in units else units[0])
            return f"{m.group(1)} {unit}"
        if found:
            return f"{_fmt(found[0][0])} {found[0][1]}"
        unit = "months" if "months" in units else units[0]
        return f"{18 if unit == 'months' else 540 if unit == 'days' else 2} {unit}"
    for text in (raw, p.unit, p.title, " ".join(p.bullets), *[str(v) for v, _ in p.variants if isinstance(v, str)]):
        for n, unit in measures(text):
            if unit in units:
                return f"{_fmt(n)} {unit}"
    unit = "oz" if "oz" in units else units[0]
    return f"{16 if unit == 'oz' else 1} {unit}"


def _text_answer(f: dict[str, Any], raw: str, p: Any) -> str:
    if raw:
        return raw
    if f["id"] == "model_number":
        return "DEMO-" + hashlib.sha256(p.key.encode()).hexdigest()[:8].upper()
    if f["id"] == "age_grade":
        return "All ages"
    if f["id"] == "material":
        return "As described in the listing"
    return p.title


def value_for(f: dict[str, Any], type_attr: dict[str, Any] | None, p: Any) -> str:
    """One rule field's answer, from the product's own data (`p.attrs`) or a plausible default of the field's control."""
    key = f["attributeKey"]
    raw = str(p.attrs.get(key, "") or "").strip()
    control = f.get("control", "text")
    type_options = (type_attr or {}).get("options") or []
    if type_options:  # the product type also lists the allowed values: both schemas must accept the answer
        return pick(raw, type_options)
    if control == "measure":
        return _measure_answer(f, raw, p)
    if control == "whole_number":
        if raw.isdigit() and int(raw) > 0:
            return raw
        m = re.search(r"(\d+)\s*servings?", " ".join([p.unit, p.title, *p.bullets]), re.I)
        return m.group(1) if m else "10"
    if control == "choice":
        return pick(raw, f.get("options") or [raw or "Room temperature"])
    if control == "multi_choice":
        options = f.get("options") or []
        parts = [x.strip() for x in raw.split(",") if x.strip()]
        if not parts or raw.lower() == "none":
            return "None"
        good = [pick(x, options) for x in parts if any(x.lower() == o.lower() for o in options)]
        return ", ".join(good) if good else "None"
    if control == "long_text":
        return raw or p.title
    return _text_answer(f, raw, p)[:120]


def build_answers(rules: Rules, ptype: dict[str, Any], p: Any, variant_texts: list[list[str]], gtin_seeds: list[str]) -> Answers:
    """Everything the schema asks of this product, ready to merge into the create request."""
    out = Answers(description=description_for(p))
    type_attrs = {a["key"]: a for a in ptype.get("attributes") or []}
    for f in rules.fields:
        key = f.get("attributeKey")
        if not key or key == "brand" or f["status"] == "hidden":
            continue
        if f["status"] == "optional" and key not in p.attrs:
            continue
        out.attributes[key] = value_for(f, type_attrs.get(key), p)
    if rules.expects("dangerous_goods"):
        out.dangerous = bool(DANGEROUS.search(" ".join([p.title, *p.queries, *p.bullets])))
    out.label_wanted = rules.expects("label_photo")
    barcode = rules.status("product_id")
    for texts, seed in zip(variant_texts, gtin_seeds):
        out.packages.append(package_for(texts + [p.unit], seed) if rules.physical else None)
        out.gtins.append(gtin13(seed) if barcode == "required" and ptype.get("identifierRule") != "ISBN" else None)
    return out


# ---------------------------------------------------------------------------------------------------- the check
def _number_problem(attr: dict[str, Any], v: str) -> str | None:
    m = re.match(r"^(-?\d+(?:[.,]\d+)?)\s*(.*)$", v)
    units = attr.get("units") or []
    if not m:
        return "Enter a number" + (f" and a unit ({', '.join(units)})" if units else "")
    if m.group(1).startswith("-"):
        return "Cannot be negative"
    unit = m.group(2).strip()
    if not unit or not units:
        return None if not unit else "No unit expected"
    return None if any(u.lower() == unit.lower() for u in units) else "Unit must be one of " + ", ".join(units)


def attribute_problem(attr: dict[str, Any], value: str | None) -> str | None:
    """Port of AttributeDefinition.problem."""
    if value is None or not value.strip():
        return "Required" if attr.get("required") else None
    v, kind = value.strip(), attr.get("kind", "TEXT")
    if kind == "ENUM":
        opts = attr.get("options") or []
        return None if not opts or v in opts else f"Choose one of {opts}"
    if kind == "NUMBER":
        return _number_problem(attr, v)
    if kind in ("TEXT", "MULTILINE"):
        return "Too long" if len(v) > (5000 if kind == "MULTILINE" else 500) else None
    return None


def _gtin_valid(digits: str) -> bool:
    if not re.fullmatch(r"\d{8}|\d{12,14}", digits):
        return False
    body = digits[:-1]
    total = sum(int(d) * (3 if i % 2 == 0 else 1) for i, d in enumerate(reversed(body)))
    return (10 - total % 10) % 10 == int(digits[-1])


def _package_problem(pkg: dict[str, Any] | None) -> str | None:
    if not pkg:
        return "Add the package length, width, height and weight of the box as shipped"
    edges = [pkg.get("lengthIn", 0), pkg.get("widthIn", 0), pkg.get("heightIn", 0)]
    if min(edges) <= 0:
        return "Length, width and height must be above zero"
    if max(edges) > MAX_EDGE_IN:
        return f"Each side must be at most {int(MAX_EDGE_IN)} in"
    w = pkg.get("weightLb", 0)
    return None if 0 < w <= MAX_WEIGHT_LB else f"Weight must be above zero and at most {int(MAX_WEIGHT_LB)} lb"


def check_listing(rules: Rules, ptype: dict[str, Any], body: dict[str, Any], min_images: int = 1) -> dict[str, str]:
    """Field path to message for what the catalog's publish gate would refuse in this create request (empty: it passes)."""
    errors: dict[str, str] = {}
    listing = body.get("listing") or {}
    attrs = {k: str(v) for k, v in (body.get("attributes") or {}).items()}
    exemptions = listing.get("exemptions") or {}
    images = body.get("images") or []
    labels = [i for i in images if i.get("role") == "LABEL"]

    if rules.expects("description") and not (listing.get("description") or "").strip():
        errors["description"] = "Describe the product for shoppers"
    status = rules.status("label_photo")
    if not labels and status in ANSWER and not (status == "required_or_exemption" and exemptions.get("label_photo")):
        errors["labelPhoto"] = "Add a photo of the label"
    if rules.expects("dangerous_goods") and listing.get("dangerousGoods") is None:
        errors["dangerousGoods"] = "Answer yes or no: is it an aerosol, flammable, battery, candle or cleaner?"
    if len([i for i in images if i.get("role") != "LABEL"]) < min_images:
        errors["images"] = "Add at least one image" if min_images == 1 else f"Add at least {min_images} images"

    type_attrs = {a["key"]: a for a in ptype.get("attributes") or []}
    for f in rules.fields:
        key = f.get("attributeKey")
        if not key or key == "brand" or f["status"] == "hidden":
            continue
        value = attrs.get(key, "").strip()
        if not value:
            if f["status"] in ANSWER and not exemptions.get(f["id"]):
                errors[f"attributes.{key}"] = "Required, or say why it does not apply"
            continue
        problem = None
        if f["id"] == "servings" and not (re.fullmatch(r"\d{1,6}", value) and int(value) > 0):
            problem = "Enter a whole number above zero"
        elif f["id"] == "allergens" and value.lower() != "none":
            opts = [o.lower() for o in f.get("options") or []]
            parts = [x.strip().lower() for x in value.split(",") if x.strip()]
            if not parts or not all(x in opts for x in parts):
                problem = "Tick the allergens it contains, or choose None"
        elif f["id"] in ("material", "model_number", "age_grade") and len(value) > 120:
            problem = "At most 120 characters"
        elif f.get("control") == "measure":
            problem = _number_problem({"units": f.get("units") or []}, value)
        if problem:
            errors[f"attributes.{key}"] = problem
    for key, attr in type_attrs.items():
        if attr.get("taxFact") or attr.get("applies") is False or key == "brand":
            continue
        problem = attribute_problem(attr, attrs.get(key))
        if problem:
            errors.setdefault(f"attributes.{key}", problem)

    barcode = rules.status("product_id")
    for i, v in enumerate(body.get("variants") or []):
        p = f"variants[{i}]."
        digits = re.sub(r"\D", "", v.get("gtin") or "")
        if barcode != "hidden":
            if ptype.get("identifierRule") == "ISBN":
                if not (_gtin_valid(digits) and len(digits) == 13 and digits[:3] in ("978", "979")):
                    errors[p + "gtin"] = "ISBN-13 required (978/979 prefix, valid check digit)"
            elif v.get("gtinExempt"):
                if barcode == "required":
                    errors[p + "gtin"] = "A barcode is required for this kind of product"
            elif not digits:
                if barcode != "optional":
                    errors[p + "gtin"] = "Required: enter the barcode" if barcode == "required" else "Required, or request a barcode exemption"
            elif not _gtin_valid(digits):
                errors[p + "gtin"] = "Invalid check digit"
        if rules.physical:
            problem = _package_problem(v.get("packaging") or listing.get("packaging"))
            if problem:
                errors[p + "packaging"] = problem
    return errors
