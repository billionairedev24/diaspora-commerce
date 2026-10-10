# syntax=docker/dockerfile:1.7
# Build context: go/   (docker build -f services/carrier-gateway/Dockerfile go)
FROM golang:1.27.1 AS build
WORKDIR /src
ENV CGO_ENABLED=0 GOWORK=off GOFLAGS=-trimpath
COPY pkg/go.mod pkg/go.sum ./pkg/
COPY services/carrier-gateway/go.mod services/carrier-gateway/go.sum ./services/carrier-gateway/
RUN --mount=type=cache,target=/go/pkg/mod cd services/carrier-gateway && go mod download
COPY pkg ./pkg
COPY services/carrier-gateway ./services/carrier-gateway
ARG VERSION=dev
RUN --mount=type=cache,target=/go/pkg/mod --mount=type=cache,target=/root/.cache/go-build \
    cd services/carrier-gateway && go build -ldflags="-s -w -X main.version=${VERSION}" -o /out/carrier-gateway ./cmd/carrier-gateway

FROM gcr.io/distroless/static-debian12:nonroot
COPY --from=build /out/carrier-gateway /usr/local/bin/carrier-gateway
USER nonroot:nonroot
ENV APP_ENV=prod PORT=8080
EXPOSE 8080
ENTRYPOINT ["/usr/local/bin/carrier-gateway"]
