"""Which licenses the seeder accepts, normalised to a short canonical name.

Accepted: CC0, public domain (incl. PDM), CC BY and CC BY-SA (any version). Rejected: NonCommercial (NC), NoDerivatives
(ND: we resize and strip metadata), GFDL-only, fair use and anything unrecognised.
"""
from __future__ import annotations

import re

_CC = re.compile(r"^cc[- ]?(by(?:[- ]sa)?)(?:[- ]([0-9.]+))?", re.IGNORECASE)


def canonical(short_name: str, code: str = "") -> str | None:
    """Canonical license name, or None when the license is not acceptable for the demo data."""
    for raw in (short_name or "", code or ""):
        s = raw.strip()
        if not s:
            continue
        low = s.lower()
        if any(bad in low for bad in ("-nc", " nc", "noncommercial", "-nd", " nd", "noderiv", "fair use", "gfdl")):
            return None
        if low in ("cc0", "cc-zero", "cc0 1.0", "cc0-1.0") or low.startswith("cc0"):
            return "CC0"
        if low in ("pd", "public domain", "pdm", "pdm-owner") or low.startswith("pd-") or "public domain" in low:
            return "Public domain"
        m = _CC.match(low.replace("cc by", "cc-by"))
        if m:
            kind = "CC BY-SA" if "sa" in m.group(1) else "CC BY"
            return f"{kind} {m.group(2)}" if m.group(2) else kind
    return None


def license_url(name: str, given: str = "") -> str:
    """The license deed URL (the API's, else the standard one)."""
    if given:
        return given if given.startswith("http") else "https:" + given if given.startswith("//") else given
    if name == "CC0":
        return "https://creativecommons.org/publicdomain/zero/1.0/"
    if name == "Public domain":
        return "https://creativecommons.org/publicdomain/mark/1.0/"
    m = re.match(r"CC (BY(?:-SA)?) ?([0-9.]+)?", name)
    if m:
        return f"https://creativecommons.org/licenses/{m.group(1).lower()}/{m.group(2) or '4.0'}/"
    return ""
