"""Normalise an exported OpenAPI document: standard servers per environment, sorted paths.

Usage: python3 tools/contracts/normalize_openapi.py contracts/openapi/<service>.yaml <service> <local-port>
Springdoc exports contain the ephemeral URL of the instance they came from; contracts must list the stable
gateway URLs instead so diffs only show real API changes.
"""
import sys

import yaml


def main():
    path, service, port = sys.argv[1], sys.argv[2], sys.argv[3]
    with open(path, encoding="utf-8") as f:
        doc = yaml.safe_load(f)
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
