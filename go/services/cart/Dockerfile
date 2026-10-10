# syntax=docker/dockerfile:1.7
# Build context: go/   (docker build -f services/cart/Dockerfile go)
FROM golang:1.27.2 AS build
WORKDIR /src
ENV CGO_ENABLED=0 GOWORK=off GOFLAGS=-trimpath
COPY pkg/go.mod pkg/go.sum ./pkg/
COPY services/cart/go.mod services/cart/go.sum ./services/cart/
WORKDIR /src/services/cart
RUN --mount=type=cache,target=/go/pkg/mod go mod download
COPY pkg /src/pkg
COPY services/cart /src/services/cart
ARG VERSION=dev
RUN --mount=type=cache,target=/go/pkg/mod --mount=type=cache,target=/root/.cache/go-build \
    go build -ldflags="-s -w -X main.version=${VERSION}" -o /out/cart ./cmd/cart

FROM gcr.io/distroless/static-debian12:nonroot
COPY --from=build /out/cart /usr/local/bin/cart
USER nonroot:nonroot
ENV APP_ENV=prod PORT=8080
EXPOSE 8080
ENTRYPOINT ["/usr/local/bin/cart"]
