# syntax=docker/dockerfile:1.7
# Build context: go/   (docker build -f services/authz-sync/Dockerfile go)
FROM golang:1.27.2@sha256:820153096fda4de6ac1b7194d12e416b7dd8f4e9a33d17e0345b111b945162d8 AS build
WORKDIR /src
ENV CGO_ENABLED=0 GOWORK=off GOFLAGS="-trimpath -buildvcs=false"
COPY pkg/go.mod pkg/go.sum ./pkg/
COPY services/authz-sync/go.mod services/authz-sync/go.sum ./services/authz-sync/
WORKDIR /src/services/authz-sync
RUN --mount=type=cache,target=/go/pkg/mod go mod download
COPY pkg /src/pkg
COPY services/authz-sync /src/services/authz-sync
ARG VERSION=dev
RUN --mount=type=cache,target=/go/pkg/mod --mount=type=cache,target=/root/.cache/go-build \
    go build -ldflags="-s -w -X main.version=${VERSION}" -o /out/authz-sync ./cmd/authz-sync

FROM gcr.io/distroless/static-debian12:nonroot@sha256:afa5c872c891853ca7fcf1f12c3edb23f7eeef36189728842dd51042ff57f7ab
COPY --from=build /out/authz-sync /usr/local/bin/authz-sync
USER nonroot:nonroot
ENV APP_ENV=prod PORT=8080
EXPOSE 8080
ENTRYPOINT ["/usr/local/bin/authz-sync"]
