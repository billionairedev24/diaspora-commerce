# syntax=docker/dockerfile:1.7
# Build context: go/   (docker build -f services/gateway-bff/Dockerfile go)
FROM golang:1.27.2 AS build
WORKDIR /src
ENV CGO_ENABLED=0 GOWORK=off GOFLAGS="-trimpath -buildvcs=false"
COPY pkg/go.mod pkg/go.sum ./pkg/
COPY services/gateway-bff/go.mod services/gateway-bff/go.sum ./services/gateway-bff/
WORKDIR /src/services/gateway-bff
RUN --mount=type=cache,target=/go/pkg/mod go mod download
COPY pkg /src/pkg
COPY services/gateway-bff /src/services/gateway-bff
ARG VERSION=dev
RUN --mount=type=cache,target=/go/pkg/mod --mount=type=cache,target=/root/.cache/go-build \
    go build -ldflags="-s -w -X main.version=${VERSION}" -o /out/gateway-bff ./cmd/gateway-bff

FROM gcr.io/distroless/static-debian12:nonroot
COPY --from=build /out/gateway-bff /usr/local/bin/gateway-bff
USER nonroot:nonroot
ENV APP_ENV=prod PORT=8080
EXPOSE 8080
ENTRYPOINT ["/usr/local/bin/gateway-bff"]
