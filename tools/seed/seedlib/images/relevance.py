"""Is a photo about the product? A search result is kept only when its title or categories contain the words of one of
the product's own queries, and none of the words that mean it is about something else (a basketball player is not
"Ijebu garri").

Pure functions, unit-tested (tests/test_relevance.py). `groups_for(queries)` turns the product's queries into groups of
significant words; a candidate passes when ALL words of at least one group appear in its text (title + Commons
categories + alt text). Words that describe any photo ("food", "african", "photo") do not count, so a query of only such
words matches nothing rather than everything.
"""
from __future__ import annotations

import re
from collections.abc import Iterable, Sequence

# Words that say nothing about WHICH product it is.
GENERIC = frozenset("""
a an and the of for with in on to from by at or
african africa food foods market product products photo photograph image picture stock fresh traditional authentic
homemade home made natural organic pack packet package bag box bottle jar can pcs piece pieces each oz lb lbs kg
demo item items shop store
""".split())

# Subjects that are never a grocery, garment or gadget photo: a result whose text is about one of these is refused unless
# the query asks for it.
OFF_TOPIC = frozenset("""
basketball football soccer player players athlete team stadium match game
mosque church cathedral temple shrine building architecture skyline
shell shells seashell seashells beach shore coast island
portrait woman man men women girl boy child children family people crowd
map flag logo diagram chart icon seal coin stamp banknote poster drawing painting illustration screenshot
airport airplane aircraft train bus car truck road street
""".split())


def stem(word: str) -> str:
    """Cheap singular: pepper(s), sauce(s), berries -> berry, boxes -> box."""
    w = word.lower()
    if len(w) > 4 and w.endswith("ies"):
        return w[:-3] + "y"
    if len(w) > 4 and w.endswith(("ses", "xes", "ches", "shes")):
        return w[:-2]
    if len(w) > 3 and w.endswith("s") and not w.endswith("ss"):
        return w[:-1]
    return w


def tokens(text: str) -> list[str]:
    return [stem(t) for t in re.findall(r"[a-z0-9]+", (text or "").lower()) if len(t) >= 2]


def significant(query: str) -> frozenset[str]:
    return frozenset(t for t in tokens(query) if t not in GENERIC and not t.isdigit())


def split_alternatives(query: str) -> list[str]:
    """"Perfumes & fragrances" is two subjects: either one is enough."""
    parts = [p.strip() for p in re.split(r"\s*(?:&|,|/|\band\b|\bor\b)\s*", query) if p.strip()]
    return parts or [query]


def groups_for(queries: Iterable[str]) -> list[frozenset[str]]:
    """Groups of significant words, one per query alternative; empty groups (all generic words) are dropped."""
    out: list[frozenset[str]] = []
    for q in queries:
        for alt in split_alternatives(q):
            g = significant(alt)
            if g and g not in out:
                out.append(g)
    return out


class Verdict:
    __slots__ = ("ok", "matched", "reason")

    def __init__(self, ok: bool, matched: tuple[str, ...] = (), reason: str = ""):
        self.ok, self.matched, self.reason = ok, matched, reason

    def __bool__(self) -> bool:
        return self.ok


def judge(text: str, groups: Sequence[frozenset[str]]) -> Verdict:
    """Passes when every word of one group is in `text` and nothing in `text` is off topic."""
    if not groups:
        return Verdict(True, (), "no filter")
    words = set(tokens(text))
    wanted = set().union(*groups)
    off = sorted((words & OFF_TOPIC) - wanted)
    if off:
        return Verdict(False, (), "about " + ", ".join(off))
    best: tuple[str, ...] = ()
    for g in groups:
        if g <= words:
            return Verdict(True, tuple(sorted(g)))
        hit = tuple(sorted(g & words))
        if len(hit) > len(best):
            best = hit
    return Verdict(False, best, "title and categories do not mention " + " + ".join(sorted(min(groups, key=len))))


def candidate_text(title: str, extra: dict | None = None) -> str:
    """Everything a candidate says about itself: title, Commons categories, description."""
    extra = extra or {}
    cats = extra.get("categories") or []
    if isinstance(cats, str):
        cats = [cats]
    return " ".join([title or "", *cats, str(extra.get("description") or "")])


def strict_queries(queries: Sequence[str], community: str = "", food: bool = False, limit: int = 2) -> list[str]:
    """Search phrases from the strictest to the plain query: product + cuisine + food, product + food, product."""
    out: list[str] = []
    for q in list(queries)[:limit]:
        if food:
            cuisine = re.sub(r"(?i)\b(pan-?african|west african|african)\b", "", community or "").strip()
            for cand in (f"{q} {cuisine} food".replace("  ", " ").strip(), f"{q} food"):
                if cand not in out:
                    out.append(cand)
        if q not in out:
            out.append(q)
    return out
