# syntax=docker/dockerfile:1.7
# Build context: go/   (docker build -f services/indexer/Dockerfile go)
FROM golang:1.27.3@sha256:2920bb124f1a2e12a1d39f0cab756ae0068441ee1da7655dec7f5e374944095b AS build
WORKDIR /src
ENV CGO_ENABLED=0 GOWORK=off GOFLAGS="-trimpath -buildvcs=false"
COPY pkg/go.mod pkg/go.sum ./pkg/
COPY services/indexer/go.mod services/indexer/go.sum ./services/indexer/
WORKDIR /src/services/indexer
RUN --mount=type=cache,target=/go/pkg/mod go mod download
COPY pkg /src/pkg
COPY services/indexer /src/services/indexer
ARG VERSION=dev
RUN --mount=type=cache,target=/go/pkg/mod --mount=type=cache,target=/root/.cache/go-build \
    go build -ldflags="-s -w -X main.version=${VERSION}" -o /out/indexer ./cmd/indexer

FROM gcr.io/distroless/static-debian12:nonroot@sha256:afa5c872c891853ca7fcf1f12c3edb23f7eeef36189728842dd51042ff57f7ab
# One binary, two roles: the Kafka consumer (no arguments) and `indexer reindex`, the search index rebuild that the
# chart's suspended CronJob indexer-reindex runs as a Kubernetes Job (deploy/helm/templates/reindex-cronjob.yaml).
COPY --from=build /out/indexer /usr/local/bin/indexer
USER nonroot:nonroot
ENV APP_ENV=prod PORT=8080
EXPOSE 8080
ENTRYPOINT ["/usr/local/bin/indexer"]
