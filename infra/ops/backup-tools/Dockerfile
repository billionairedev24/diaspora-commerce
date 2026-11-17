# Tools image of the nightly backup CronJobs (infra/k8s/platform/lean/backup): pg_dump/pg_restore/pg_dumpall (postgres:17-alpine), plus
# rclone, age and curl, installed at BUILD time so the CronJobs run as a non-root user with a read-only root filesystem and no
# capabilities (they used to run `apk add` as root at every start: Trivy KSV-0014 / KSV-0118). Built by
# .github/workflows/backup-tools-image.yml; the scripts run with SKIP_APK=1 because everything is already here.
FROM postgres:17-alpine
RUN apk add --no-cache rclone age curl \
 && install -d -o 70 -g 70 -m 0750 /work
# uid/gid 70 = the "postgres" user of the Alpine postgres image; HOME and the rclone cache point at the writable /tmp volume.
ENV HOME=/tmp XDG_CACHE_HOME=/tmp/.cache XDG_CONFIG_HOME=/tmp/.config RCLONE_CONFIG=/tmp/.config/rclone.conf
USER 70:70
WORKDIR /work
HEALTHCHECK NONE
ENTRYPOINT []
CMD ["rclone", "version"]
