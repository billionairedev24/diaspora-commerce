# syntax=docker/dockerfile:1.7
# Build context: go/   (docker build -f services/geo/Dockerfile go)
FROM golang:1.27.2 AS build
WORKDIR /src
ENV CGO_ENABLED=0 GOWORK=off GOFLAGS=-trimpath
COPY pkg/go.mod pkg/go.sum ./pkg/
COPY services/geo/go.mod services/geo/go.sum ./services/geo/
WORKDIR /src/services/geo
RUN --mount=type=cache,target=/go/pkg/mod go mod download
COPY pkg /src/pkg
COPY services/geo /src/services/geo
ARG VERSION=dev
RUN --mount=type=cache,target=/go/pkg/mod --mount=type=cache,target=/root/.cache/go-build \
    go build -ldflags="-s -w -X main.version=${VERSION}" -o /out/geo ./cmd/geo

FROM gcr.io/distroless/static-debian12:nonroot
COPY --from=build /out/geo /usr/local/bin/geo
USER nonroot:nonroot
ENV APP_ENV=prod PORT=8080
EXPOSE 8080
ENTRYPOINT ["/usr/local/bin/geo"]
