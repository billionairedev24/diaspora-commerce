# syntax=docker/dockerfile:1.7
# Build context: go/   (docker build -f services/storefront/Dockerfile go)
FROM golang:1.27.2 AS build
WORKDIR /src
ENV CGO_ENABLED=0 GOWORK=off GOFLAGS=-trimpath
COPY pkg/go.mod pkg/go.sum ./pkg/
COPY services/storefront/go.mod services/storefront/go.sum ./services/storefront/
WORKDIR /src/services/storefront
RUN --mount=type=cache,target=/go/pkg/mod go mod download
COPY pkg /src/pkg
COPY services/storefront /src/services/storefront
ARG VERSION=dev
RUN --mount=type=cache,target=/go/pkg/mod --mount=type=cache,target=/root/.cache/go-build \
    go build -ldflags="-s -w -X main.version=${VERSION}" -o /out/storefront ./cmd/storefront

FROM gcr.io/distroless/static-debian12:nonroot
COPY --from=build /out/storefront /usr/local/bin/storefront
USER nonroot:nonroot
ENV APP_ENV=prod PORT=8080
EXPOSE 8080
ENTRYPOINT ["/usr/local/bin/storefront"]
