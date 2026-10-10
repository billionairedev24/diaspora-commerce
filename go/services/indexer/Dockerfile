# syntax=docker/dockerfile:1.7
# Build context: go/   (docker build -f services/indexer/Dockerfile go)
FROM golang:1.27.1 AS build
WORKDIR /src
ENV CGO_ENABLED=0 GOWORK=off GOFLAGS=-trimpath
COPY pkg/go.mod pkg/go.sum ./pkg/
COPY services/indexer/go.mod services/indexer/go.sum ./services/indexer/
RUN --mount=type=cache,target=/go/pkg/mod cd services/indexer && go mod download
COPY pkg ./pkg
COPY services/indexer ./services/indexer
ARG VERSION=dev
RUN --mount=type=cache,target=/go/pkg/mod --mount=type=cache,target=/root/.cache/go-build \
    cd services/indexer && go build -ldflags="-s -w -X main.version=${VERSION}" -o /out/indexer ./cmd/indexer

FROM gcr.io/distroless/static-debian12:nonroot
COPY --from=build /out/indexer /usr/local/bin/indexer
USER nonroot:nonroot
ENV APP_ENV=prod PORT=8080
EXPOSE 8080
ENTRYPOINT ["/usr/local/bin/indexer"]
