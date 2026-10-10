#!/usr/bin/env python3
"""Offline check of the GitOps layer (used by `make helm-lint`).

For every environment (infra/argocd/envs/<env>):
  1. emulates the ApplicationSets (git files x git directories / list generators) and writes the generated
     Application manifests — one per service per environment, plus the platform apps;
  2. renders every service chart exactly as Argo CD would:
       helm template <svc> <chart> -n afrotransact-<env> -f values.yaml -f values-<env>.yaml -f <platform-values.yaml>
  3. runs kubeconform on the rendered charts, the generated Applications, the env kustomizations and the
     platform kustomize layers (CRDs validated through the datreeio CRDs-catalog when reachable, otherwise skipped).

  python3 infra/argocd/scripts/render_apps.py [--env dev] [--out DIR] [--cloud aws|gcp|azure]

--cloud renders the services with the commented cloud example of platform-values.yaml switched on, to prove the
workload-identity / registry wiring composes for that cloud too.
"""
import argparse
import copy
import glob
import os
import pathlib
import re
import shutil
import subprocess
import sys
import tempfile

import yaml

ROOT = pathlib.Path(__file__).resolve().parents[3]
ENVS = ["dev", "uat", "prod"]
HELM = shutil.which("helm") or os.path.expanduser("~/go/bin/helm")
KUBECONFORM = shutil.which("kubeconform") or os.path.expanduser("~/go/bin/kubeconform")
KUSTOMIZE = shutil.which("kustomize")
CRD_CATALOG = "https://raw.githubusercontent.com/datreeio/CRDs-catalog/main/{{.Group}}/{{.ResourceKind}}_{{.ResourceAPIVersion}}.json"

EXPR = re.compile(r"\{\{-?\s*([^{}]*?)\s*-?\}\}")


def lookup(params, dotted):
    cur = params
    for part in dotted.lstrip(".").split("."):
        if not isinstance(cur, dict) or part not in cur:
            raise KeyError(f"missing template key {dotted}")
        cur = cur[part]
    return cur


def eval_expr(expr, params):
    """Tiny subset of Go templates used by our ApplicationSets: .a.b, index .a.b N, and | replace "X" .y."""
    stages = [s.strip() for s in expr.split("|")]
    head = stages[0]
    if head.startswith("index "):
        _, ref, idx = head.split()
        val = lookup(params, ref)[int(idx)]
    else:
        val = lookup(params, head)
    for st in stages[1:]:
        m = re.match(r'replace\s+"([^"]*)"\s+(\S+)', st)
        if not m:
            raise ValueError(f"unsupported template stage: {st}")
        val = str(val).replace(m.group(1), str(lookup(params, m.group(2))))
    return val


def render(obj, params):
    if isinstance(obj, dict):
        return {render(k, params): render(v, params) for k, v in obj.items()}
    if isinstance(obj, list):
        return [render(v, params) for v in obj]
    if isinstance(obj, str):
        whole = EXPR.fullmatch(obj.strip())
        if whole:
            v = eval_expr(whole.group(1), params)
            return v if not isinstance(v, (dict, list)) else obj
        return EXPR.sub(lambda m: str(eval_expr(m.group(1), params)), obj)
    return obj


def path_params(path):
    p = pathlib.PurePosixPath(path)
    return {"path": str(p), "basename": p.name, "segments": list(p.parts)}


def generate(appset, env_dir):
    """Expand one ApplicationSet (matrix of git-files + git-directories/list) into Applications."""
    spec = appset["spec"]
    gens = spec["generators"][0]["matrix"]["generators"]
    files_gen = gens[0]["git"]["files"][0]["path"]
    pv_path = ROOT / files_gen
    pv = yaml.safe_load(pv_path.read_text())
    base = dict(pv)
    base["path"] = path_params(str(pathlib.PurePosixPath(files_gen).parent))
    second = gens[1]
    rows = []
    if "git" in second:
        prefix = second["git"].get("pathParamPrefix")
        for d in second["git"]["directories"]:
            for match in sorted(glob.glob(str(ROOT / d["path"]))):
                rel = str(pathlib.Path(match).relative_to(ROOT))
                row = copy.deepcopy(base)
                if prefix:
                    row[prefix] = {"path": path_params(rel)}
                else:
                    row["path"] = path_params(rel)
                rows.append(row)
    else:
        for el in second["list"]["elements"]:
            row = copy.deepcopy(base)
            row.update(el)
            rows.append(row)
    apps = []
    for row in rows:
        app = {"apiVersion": "argoproj.io/v1alpha1", "kind": "Application",
               "metadata": dict(render(spec["template"]["metadata"], row), namespace="argocd"),
               "spec": render(spec["template"]["spec"], row)}
        if row["argocd"].get("autoSync"):  # templatePatch
            app["spec"].setdefault("syncPolicy", {})["automated"] = {
                "prune": row["argocd"]["prune"], "selfHeal": row["argocd"]["selfHeal"]}
        apps.append(app)
    return apps, pv


def run(cmd, **kw):
    return subprocess.run(cmd, check=False, text=True, capture_output=True, **kw)


def kubeconform(paths, crds=True):
    cmd = [KUBECONFORM, "-strict", "-summary", "-ignore-missing-schemas", "-kubernetes-version", "1.33.0"]
    if crds:
        cmd += ["-schema-location", "default", "-schema-location", CRD_CATALOG]
    r = run(cmd + [str(p) for p in paths])
    out = (r.stdout + r.stderr).strip()
    return r.returncode, out


def kustomize_build(path):
    if KUSTOMIZE:
        r = run([KUSTOMIZE, "build", str(path)])
    else:
        r = run(["kubectl", "kustomize", str(path)]) if shutil.which("kubectl") else None
    if r is None:
        return None, "kustomize/kubectl not found — skipped"
    return (r.stdout if r.returncode == 0 else None), r.stderr


def cloud_example(pv_file, cloud):
    """Return platform-values text with the commented example block for `cloud` enabled."""
    lines = pv_file.read_text().splitlines()
    out, active = [], False
    header = {"aws": "AWS (EKS)", "gcp": "GCP (GKE)", "azure": "Azure (AKS)"}[cloud]
    for ln in lines:
        if ln.startswith("# ----") and "(" in ln:
            active = header in ln
            out.append(ln)
            continue
        if active and ln.startswith("# "):
            ln = ln[2:]
            if ln.startswith("cloud:") or ln.startswith("image:") or ln.startswith("workloadIdentity:"):
                key = ln.split(":")[0]
                out = [o for o in out if not o.startswith(key + ":") and not (key == "image" and o.startswith("  registry:"))]
        out.append(ln)
    return "\n".join(out) + "\n"


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("--env", choices=ENVS, action="append")
    ap.add_argument("--out", default=None)
    ap.add_argument("--cloud", choices=["aws", "gcp", "azure"])
    ap.add_argument("--no-crd-schemas", action="store_true")
    args = ap.parse_args()
    envs = args.env or ENVS
    out = pathlib.Path(args.out or tempfile.mkdtemp(prefix="argocd-render-"))
    out.mkdir(parents=True, exist_ok=True)
    failures = 0
    crds = not args.no_crd_schemas

    charts = sorted(glob.glob(str(ROOT / "java/services/*/deploy/helm")) + glob.glob(str(ROOT / "go/services/*/deploy/helm")))
    for chart in charts:
        r = run([HELM, "dependency", "build", chart])
        if r.returncode:
            print(f"FAIL helm dependency build {chart}\n{r.stderr}")
            failures += 1

    for env in envs:
        env_out = out / env
        env_out.mkdir(exist_ok=True)
        kbuild, err = kustomize_build(ROOT / "infra/argocd/envs" / env)
        if kbuild is None:
            print(f"[{env}] env kustomization: {err.strip()}")
            docs = []
            for f in ["projects/afrotransact.yaml", "applicationsets/services.yaml", "applicationsets/platform-charts.yaml",
                      "applicationsets/platform-manifests.yaml"]:
                docs += list(yaml.safe_load_all((ROOT / "infra/argocd" / f).read_text()))
            for d in docs:
                if d["kind"] == "ApplicationSet":
                    d["spec"]["generators"][0]["matrix"]["generators"][0]["git"]["files"][0]["path"] = f"infra/argocd/envs/{env}/platform-values.yaml"
                    d["metadata"]["name"] += f"-{env}"
        else:
            docs = [d for d in yaml.safe_load_all(kbuild) if d]
            (env_out / "argocd-env.yaml").write_text(kbuild)
        apps = []
        pv = None
        for d in docs:
            if d.get("kind") == "ApplicationSet":
                generated, pv = generate(d, env)
                apps += generated
        (env_out / "applications.yaml").write_text(yaml.safe_dump_all(apps, sort_keys=False))
        svc_apps = [a for a in apps if a["spec"]["project"].startswith("afrotransact-")]
        print(f"[{env}] generated {len(apps)} Applications ({len(svc_apps)} services: "
              f"{', '.join(a['metadata']['name'] for a in svc_apps)}); "
              f"sync={'automated' if pv['argocd']['autoSync'] else 'manual'}")

        pv_file = ROOT / "infra/argocd/envs" / env / "platform-values.yaml"
        if args.cloud:
            tmp = env_out / f"platform-values-{args.cloud}.yaml"
            tmp.write_text(cloud_example(pv_file, args.cloud))
            pv_file = tmp
        for app in svc_apps:
            src = app["spec"]["sources"][0]
            chart = ROOT / src["path"]
            files = []
            for vf in src["helm"]["valueFiles"]:
                files += ["-f", str(pv_file) if vf.startswith("$values/") else str(chart / vf)]
            name = src["helm"]["releaseName"]
            r = run([HELM, "template", name, str(chart), "-n", app["spec"]["destination"]["namespace"]] + files)
            target = env_out / f"{name}.yaml"
            if r.returncode:
                print(f"  FAIL helm template {name} ({env}): {r.stderr.strip()}")
                failures += 1
                continue
            target.write_text(r.stdout)
            kinds = sorted({d["kind"] for d in yaml.safe_load_all(r.stdout) if d})
            print(f"  rendered {name:<12} -> {', '.join(kinds)}")

        rendered = sorted(env_out.glob("*.yaml"))
        code, summary = kubeconform([p for p in rendered if not p.name.startswith("platform-values")], crds)
        print(f"  kubeconform: {summary.splitlines()[-1] if summary else ''}")
        if code:
            print(summary)
            failures += 1

    # Platform kustomize layers (env-independent + per-env Elasticsearch).
    layers = ["namespaces", "edge", "observability"] + [f"secret-stores/{c}" for c in ["aws", "gcp", "azure", "none"]] + \
             [f"elasticsearch/overlays/{e}" for e in envs]
    pdir = out / "platform"
    pdir.mkdir(exist_ok=True)
    for layer in layers:
        built, err = kustomize_build(ROOT / "infra/k8s/platform" / layer)
        if built is None:
            print(f"[platform] {layer}: {err.strip()}")
            if "not found" not in err:
                failures += 1
            continue
        (pdir / (layer.replace("/", "_") + ".yaml")).write_text(built)
    if any(pdir.iterdir()):
        code, summary = kubeconform(sorted(pdir.glob("*.yaml")), crds)
        print(f"[platform] kubeconform: {summary.splitlines()[-1] if summary else ''}")
        if code:
            print(summary)
            failures += 1

    print(f"output: {out}")
    sys.exit(1 if failures else 0)


if __name__ == "__main__":
    main()
