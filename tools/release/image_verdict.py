#!/usr/bin/env python3
"""Is the signed image verified (built after a green `verify`) or was it a one-time manual UAT build that skipped it?

Reads the JSON `cosign verify` prints (one entry per signature). The publish-image action signs every image with the annotation
`org.afrotransact.verified=true|false`; the annotation is part of the keyless signature, so it cannot be changed without a
certificate of an allowed workflow. Verdicts: verified | unverified | unmarked (signed before the annotation existed).

  prod and trial accept `verified` only (`unmarked` is also accepted for a per-service image, which predates the annotation, never for
  afrotransact-core / afrotransact-go: those have never been published without it). uat and dev accept everything and record it.

    cosign verify ... > verify.json; python3 tools/release/image_verdict.py --env prod --service afrotransact-core --file verify.json
    python3 tools/release/test_image_verdict.py
"""
import argparse
import json
import os
import sys

ANNOTATION = "org.afrotransact.verified"
DEPLOYABLES = {"afrotransact-core", "afrotransact-go"}
STRICT_ENVS = {"prod", "trial"}


def verdict(signatures):
    states = set()
    for sig in signatures:
        value = ((sig or {}).get("optional") or {}).get(ANNOTATION)
        states.add("unmarked" if value is None else str(value).lower())
    if "false" in states:
        return "unverified"          # any signature that says so wins: one unverified signature taints the digest
    if states == {"true"}:
        return "verified"
    return "unmarked"


def decide(env, service, signatures):
    """(verdict, error or None)."""
    v = verdict(signatures)
    if env in STRICT_ENVS:
        if v == "unverified":
            return v, f"REFUSED: this {service} image was built by the manual UAT-only path that skips verify; {env} needs an image from a green verify"
        if v == "unmarked" and service in DEPLOYABLES:
            return v, f"REFUSED: this {service} image carries no {ANNOTATION}=true signature annotation; {env} needs an image from a green verify"
    return v, None


def main(argv=None):
    ap = argparse.ArgumentParser()
    ap.add_argument("--env", required=True)
    ap.add_argument("--service", required=True)
    ap.add_argument("--file", required=True, help="output of `cosign verify`")
    args = ap.parse_args(argv)
    with open(args.file, encoding="utf-8") as fh:
        signatures = json.load(fh)
    v, err = decide(args.env, args.service, signatures)
    print(f"image verdict for {args.service} -> {args.env}: {v}")
    out = os.environ.get("GITHUB_OUTPUT")
    if out:
        with open(out, "a", encoding="utf-8") as fh:
            fh.write(f"verdict={v}\n")
    if err:
        print(f"::error::{err}")
        return 1
    return 0


if __name__ == "__main__":
    sys.exit(main())
