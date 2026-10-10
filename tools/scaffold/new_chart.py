"""Scaffold a service's own Helm chart (deploy/helm) on top of the at-service library chart.

Usage: python3 tools/scaffold/new_chart.py <service-name> <java|go> <service-dir> [--secrets KEY,KEY]
Example: python3 tools/scaffold/new_chart.py catalog java java/services/catalog --secrets DB_PASSWORD

Creates Chart.yaml, templates/all.yaml, values.yaml and values-{dev,uat,prod}.yaml. Cloud (aws|gcp|azure),
workload identity and the secret store are injected per environment by Argo CD from
infra/argocd/envs/<env>/platform-values.yaml, so the chart itself stays cloud-neutral.
"""
import argparse
import os

ROOT = os.path.dirname(os.path.dirname(os.path.dirname(os.path.abspath(__file__))))

ENV = {
    "dev": dict(replicas=1, autoscaling=False, min=1, max=2, cpu="100m", mem_java="512Mi", mem_go="64Mi",
                lim_java="1Gi", lim_go="256Mi", rollout=False, sampling="1.0", spot=True),
    "uat": dict(replicas=2, autoscaling=True, min=2, max=4, cpu="250m", mem_java="768Mi", mem_go="128Mi",
                lim_java="1536Mi", lim_go="384Mi", rollout=True, sampling="0.5", spot=True),
    "prod": dict(replicas=3, autoscaling=True, min=3, max=12, cpu="500m", mem_java="1Gi", mem_go="256Mi",
                 lim_java="2Gi", lim_go="512Mi", rollout=True, sampling="0.1", spot=False),
}


def write(path, text):
    os.makedirs(os.path.dirname(path), exist_ok=True)
    with open(path, "w", encoding="utf-8") as f:
        f.write(text)


def main():
    p = argparse.ArgumentParser()
    p.add_argument("name")
    p.add_argument("runtime", choices=["java", "go"])
    p.add_argument("dir")
    p.add_argument("--secrets", default="")
    a = p.parse_args()
    chart = os.path.join(ROOT, a.dir, "deploy", "helm")
    lib = os.path.relpath(os.path.join(ROOT, "infra", "helm", "library", "at-service"), chart)
    secrets = [s for s in a.secrets.split(",") if s]

    write(os.path.join(chart, "Chart.yaml"), f"""apiVersion: v2
name: {a.name}
description: AfroTransact {a.name} service ({a.runtime}). Deployed independently by its own Argo CD Application.
type: application
version: 0.1.0
appVersion: "0.1.0"
dependencies:
  - name: at-service
    version: 0.1.0
    repository: "file://{lib}"
""")
    write(os.path.join(chart, "templates", "all.yaml"), '{{ include "at-service.all" . }}\n')
    secret_doc = ("\n".join("#   - " + s for s in secrets)) or "#   (none)"
    write(os.path.join(chart, "values.yaml"), f"""# Base values for {a.name}. Environment files override; Argo CD adds platform-values.yaml (cloud, identity, store).
service:
  name: {a.name}
  runtime: {a.runtime}
  port: 8080
env: dev
cloud: none            # aws | gcp | azure | none (kind / local clusters)
image:
  registry: ghcr.io/billionairedev24/diaspora-commerce
  repository: {a.name}{"-service" if a.runtime == "java" else ""}
  tag: latest
replicas: 1
autoscaling:
  enabled: false
  min: 1
  max: 3
  cpu: 70
rollout:
  enabled: false
  pause: 2m
  maxErrorRate: "0.01"
resources:
  requests: {{cpu: 100m, memory: {"512Mi" if a.runtime == "java" else "64Mi"}}}
  limits: {{memory: {"1Gi" if a.runtime == "java" else "256Mi"}}}
telemetry:
  otlpEndpoint: otel-collector.observability:4318
  sampling: "1.0"
monitoring:
  serviceMonitor: true
  prometheusUrl: http://kube-prometheus-stack-prometheus.observability:9090
# Secret keys expected at afrotransact/<env>/{a.name} in the cloud secret store:
{secret_doc}
secrets:
  enabled: {"true" if secrets else "false"}
  store: platform-secrets
  keySeparator: "/"     # "-" on GCP Secret Manager and Azure Key Vault (set in platform-values.yaml)
workloadIdentity:
  aws: {{roleArn: ""}}
  gcp: {{serviceAccount: ""}}
  azure: {{clientId: ""}}
extraEnv: {{}}
nodeSelector: {{}}
tolerations: []
""")
    for env, c in ENV.items():
        mem = c["mem_java"] if a.runtime == "java" else c["mem_go"]
        lim = c["lim_java"] if a.runtime == "java" else c["lim_go"]
        spot = ("tolerations:\n  - {key: afrotransact.com/spot, operator: Exists, effect: NoSchedule}\n"
                if c["spot"] else "tolerations: []\n")
        write(os.path.join(chart, f"values-{env}.yaml"), f"""env: {env}
replicas: {c['replicas']}
autoscaling:
  enabled: {str(c['autoscaling']).lower()}
  min: {c['min']}
  max: {c['max']}
  cpu: 70
rollout:
  enabled: {str(c['rollout']).lower()}
resources:
  requests: {{cpu: {c['cpu']}, memory: {mem}}}
  limits: {{memory: {lim}}}
telemetry:
  otlpEndpoint: otel-collector.observability:4318
  sampling: "{c['sampling']}"
{spot}""")
    print("chart written to", os.path.relpath(chart, ROOT))


if __name__ == "__main__":
    main()
