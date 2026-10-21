#!/usr/bin/env bash
# Run once on a fresh Ubuntu 24.04 server as root: firewall, k3s (without its bundled Traefik/servicelb: Traefik is
# installed by deploy.sh into the `ingress` namespace so the charts' NetworkPolicies let it in), helm, psql.
set -euo pipefail
[[ $EUID -eq 0 ]] || { echo "run as root"; exit 1; }
export DEBIAN_FRONTEND=noninteractive

echo "==> packages"
apt-get update -qq
apt-get install -y -qq curl ca-certificates jq ufw openssl postgresql-client python3 >/dev/null

echo "==> firewall: SSH, HTTP, HTTPS only"
ufw --force reset >/dev/null
ufw default deny incoming >/dev/null
ufw default allow outgoing >/dev/null
ufw allow 22/tcp >/dev/null
ufw allow 80/tcp >/dev/null
ufw allow 443/tcp >/dev/null
ufw --force enable >/dev/null

if ! command -v k3s >/dev/null; then
  echo "==> k3s"
  curl -sfL https://get.k3s.io | INSTALL_K3S_EXEC="server --disable traefik --disable servicelb --write-kubeconfig-mode 600 --kube-apiserver-arg=default-not-ready-toleration-seconds=30 --kube-apiserver-arg=default-unreachable-toleration-seconds=30" sh -
fi
export KUBECONFIG=/etc/rancher/k3s/k3s.yaml
for _ in $(seq 1 60); do kubectl get node 2>/dev/null | grep -q ' Ready' && break; sleep 3; done
kubectl get node | grep -q ' Ready' || { echo "k3s node is not Ready"; exit 1; }

if ! command -v helm >/dev/null; then
  echo "==> helm"
  curl -fsSL https://raw.githubusercontent.com/helm/helm/main/scripts/get-helm-3 | bash >/dev/null
fi

# Some images are large: keep the container runtime from filling the disk with unused layers.
echo "==> done: $(k3s --version | head -1), $(helm version --short)"
