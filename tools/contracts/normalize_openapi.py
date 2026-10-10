"""Normalise an exported OpenAPI document: standard servers per environment, sorted paths.

Usage: python3 tools/contracts/normalize_openapi.py contracts/openapi/<service>.yaml <service> <local-port>
Springdoc exports contain the ephemeral URL of the instance they came from; contracts must list the stable
gateway URLs instead so diffs only show real API changes.
"""
import sys

import yaml


class _NoSexagesimalLoader(yaml.SafeLoader):
    """YAML 1.1 reads unquoted 10:00 as the base-60 integer 600; keep such scalars as strings (times, ratios)."""


def _int_or_text(loader, node):
    value = loader.construct_scalar(node)
    return value if ":" in value else yaml.SafeLoader.construct_yaml_int(loader, node)


def _float_or_text(loader, node):
    value = loader.construct_scalar(node)
    return value if ":" in value else yaml.SafeLoader.construct_yaml_float(loader, node)


_NoSexagesimalLoader.add_constructor("tag:yaml.org,2002:int", _int_or_text)
_NoSexagesimalLoader.add_constructor("tag:yaml.org,2002:float", _float_or_text)


def main():
    path, service, port = sys.argv[1], sys.argv[2], sys.argv[3]
    with open(path, encoding="utf-8") as f:
        doc = yaml.load(f, Loader=_NoSexagesimalLoader)  # noqa: S506 - SafeLoader subclass
    doc["servers"] = [
        {"url": "http://localhost:%s" % port, "description": "local"},
        {"url": "https://api.dev.afrotransact.com/%s" % service, "description": "dev"},
        {"url": "https://api.uat.afrotransact.com/%s" % service, "description": "uat"},
        {"url": "https://api.afrotransact.com/%s" % service, "description": "prod"},
    ]
    doc["paths"] = dict(sorted(doc.get("paths", {}).items()))
    with open(path, "w", encoding="utf-8") as f:
        yaml.safe_dump(doc, f, sort_keys=False, allow_unicode=True, width=120)


if __name__ == "__main__":
    main()
