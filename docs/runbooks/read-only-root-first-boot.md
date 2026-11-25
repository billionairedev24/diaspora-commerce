# Read-only root filesystem workloads: first-boot checklist

The lean production platform (`infra/k8s/platform/lean`) runs every workload with `readOnlyRootFilesystem: true`, non-root, all
capabilities dropped, `allowPrivilegeEscalation: false` and the `RuntimeDefault` seccomp profile (Trivy KSV-0014, KSV-0118; the
`security` workflow fails on any HIGH). Nothing here was started against a cluster or Docker when it was written: the writable
paths below come from each image's documented entrypoint and layout, not from a run. **UAT is the dry run: do this checklist there
first, then in prod.**

## Writable paths per workload (and where each came from)

| Workload | Writable mounts | Source / confidence |
|---|---|---|
| clamav | `/var/lib/clamav` (signatures), `/run/clamav` (socket, pid), `/var/log/clamav`, `/tmp` | These four were already mounted before the change. A non-root uid 100 could only write dirs owned by `clamav` plus mounts, so the set is the same. RISK: low |
| meilisearch | `/meili_data` (PVC: index, `./dumps`, `./snapshots`, tasks), `/tmp` | Meilisearch docs: db, dumps and snapshots default to paths under the working dir `/meili_data`; temp files in `/tmp`. RISK: low |
| kafka | `/var/lib/kafka/data` (PVC, KRaft log dir and `meta.properties`), `/opt/kafka/config` (emptyDir seeded by init container `config-seed` from the image's own `/opt/kafka/config`), `/opt/kafka/logs` and `/usr/logs` (emptyDir), `/tmp` | apache/kafka docker `run`/`configure` scripts render `server.properties` and log4j into `/opt/kafka/config` from `KAFKA_*`; `kafka-run-class.sh` creates `$KAFKA_HOME/logs`. RISK: MEDIUM. `/usr/logs` is a guess from older image layouts; if the container writes some other path the broker crash-loops (see below) |
| kafka-dlq-topics Job | `/opt/kafka/logs`, `/tmp` | `kafka-topics.sh` goes through `kafka-run-class.sh` (log dir) and the JVM (`/tmp`). RISK: low |
| temporal (auto-setup) | `/etc/temporal/config` (emptyDir seeded by `config-seed` from the image's config tree: `config_template.yaml`, `dynamicconfig/`), `/tmp`, `HOME=/tmp` | `auto-setup` renders `docker.yaml` from `config_template.yaml` with dockerize at every start. RISK: MEDIUM: schema setup tools or the CLI may write elsewhere |
| alloy | `/var/lib/alloy/data` (already was), config ConfigMap read-only | Unchanged, already read-only root. See "kubelet scrape" below |
| pg-backup, r2-backup CronJobs | `/work` (dumps, scratch), `/tmp` (HOME, rclone cache and config) | Scripts only write `$WORK`; rclone/age/curl use env config. RISK: low, but see "backup-tools image" |

## First-boot checklist (each workload, in UAT then prod)

1. `kubectl -n <ns> get pods -w`: the pod reaches `Running` and `Ready` and the restart count stays 0 for 10 minutes.
2. `kubectl -n <ns> logs <pod> [-c <container>] --previous` is empty of `Read-only file system`, `Permission denied`, `EROFS`,
   `Could not create` and `cannot create directory`.
3. Init containers: `kubectl -n kafka logs kafka-0 -c config-seed` and `kubectl -n temporal logs deploy/temporal -c config-seed`
   print nothing and exit 0.
4. Function, not just liveness: kafka: produce and consume one test message; temporal: `temporal operator namespace describe afrotransact`
   and one checkout workflow in UAT; meilisearch: `GET /health` and a re-index; clamav: send the EICAR test string through media and
   see it quarantined.
5. Alloy: in Grafana, `up{job=~"kubelet|cadvisor"} == 1` (see below), logs arrive in Loki.
6. Backups: `kubectl -n ops create job --from=cronjob/pg-backup pg-backup-first` and the same for `r2-backup`; the logs end with
   `backup complete`; an object is in the Backblaze bucket.
7. Record the result in the UAT dry-run notes. Anything you had to add (a mount) goes into the manifest AND the table above.

## Reading a CrashLoopBackOff caused by a missing writable mount

Symptoms: the pod restarts quickly (`CrashLoopBackOff`, `Error`, exit code 1, 2 or 126/127), the container never becomes Ready, and
`kubectl describe pod` shows `Last State: Terminated  Reason: Error`.

1. Get the crash output: `kubectl -n <ns> logs <pod> -c <container> --previous`.
2. Look for the path in the error. Typical lines:
   * `mkdir: cannot create directory '/x': Read-only file system`, `EROFS: read-only file system, open '/x'`,
     `could not create lock file "/x": Read-only file system` (postgres), `Permission denied` on a path outside a mount
     (the process is non-root, so root-owned image dirs are not writable either way).
   * Java: `java.io.FileNotFoundException: /x (Read-only file system)`, `Failed to create directory`.
3. Decide whether the path is data (needs a PVC), scratch (an `emptyDir`, give it a `sizeLimit`) or config the image renders at start
   (an `emptyDir` seeded by an init container, as `config-seed` does for kafka and temporal).
4. If the log is empty or the container dies before logging, run the same image with a shell and trace it:
   `kubectl -n <ns> debug <pod> --copy-to=dbg --container=<container> --image=<same image> -- sleep 3600` (the copy keeps the pod's
   mounts; `kubectl exec dbg -- sh`, then try the start command and `touch` the suspect directory).
   `strace -f -e trace=openat,mkdir,rename` shows the failing path if the image has strace.
5. Add the mount to the manifest (volume + `volumeMounts`), never relax `readOnlyRootFilesystem`: a Trivy HIGH fails the
   `security` workflow, and the owner's rule is no ignores. Update the table above, commit, let Argo CD sync.
6. A mount over a directory hides what the image ships there (`/opt/kafka/config`, `/etc/temporal/config`): if a required file is
   now missing (`No such file or directory`), the seed init container does not copy that directory completely; fix the `cp`.
7. `fsGroup` matters for volumes: an emptyDir or PVC is writable by the pod's `fsGroup`; if you see `Permission denied` on a mount,
   check `runAsUser`, `runAsGroup` and `fsGroup` agree with the image's user (clamav 100/101, postgres 999, meilisearch/kafka/temporal 1000,
   backup tools 70).

## kubelet scrape (Alloy, KSV-0047)

Alloy no longer holds `nodes/proxy` (its `get` lets a holder run commands in any pod through the kubelet). It scrapes
`https://<node InternalIP>:10250/metrics` and `/metrics/cadvisor` directly, which the kubelet authorises as `nodes/metrics`. UNVERIFIED on
OVH MKS: the kubelet serving certificate must chain to the cluster CA (`/var/run/secrets/kubernetes.io/serviceaccount/ca.crt`). If
`up{job=~"kubelet|cadvisor"}` is 0 and the Alloy logs say `x509`, the node's kubelet cert is not signed by the cluster CA: fix that on the node
pool or, as a documented decision by the owner, change `tls_config` in `infra/k8s/platform/lean/alloy/config.alloy`; do not give Alloy `nodes/proxy` back.

## backup-tools image

The backup CronJobs use `ghcr.io/billionairedev24/diaspora-commerce/backup-tools:1.0.0` (`infra/ops/backup-tools/Dockerfile`: Alpine
postgres 17 client + rclone + age + curl, user 70) so that they run non-root with a read-only root instead of `apk add` as root. Before the
first scheduled run (02:30 UTC) in each environment:

1. Run the workflow **backup-tools-image** (Actions, Run workflow). It builds, pushes and Trivy-scans (CRITICAL/HIGH fail).
2. Make the GHCR package `backup-tools` public (it holds only distro packages, no secrets), or copy the pull secret `ghcr` into namespace
   `ops` (the CronJobs already reference it as an optional `imagePullSecret`).
3. Run the two test jobs from step 6 of the checklist. Until the image exists the CronJob pods stay in `ImagePullBackOff` and
   the `BackupStale` / `R2BackupStale` alerts fire after 26 hours.
4. Optional hardening: pin the digest printed in the workflow summary (`image: ...:1.0.0@sha256:...`).
