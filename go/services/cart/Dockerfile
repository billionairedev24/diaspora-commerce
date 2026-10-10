# syntax=docker/dockerfile:1.7
# Build context: go/   (docker build -f services/cart/Dockerfile go)
FROM golang:1.27.1 AS build
WORKDIR /src
ENV CGO_ENABLED=0 GOWORK=off GOFLAGS=-trimpath
COPY pkg/go.mod pkg/go.sum ./pkg/
COPY services/cart/go.mod services/cart/go.sum ./services/cart/
RUN --mount=type=cache,target=/go/pkg/mod cd services/cart && go mod download
COPY pkg ./pkg
COPY services/cart ./services/cart
ARG VERSION=dev
RUN --mount=type=cache,target=/go/pkg/mod --mount=type=cache,target=/root/.cache/go-build \
    cd services/cart && go build -ldflags="-s -w -X main.version=${VERSION}" -o /out/cart ./cmd/cart

FROM gcr.io/distroless/static-debian12:nonroot
COPY --from=build /out/cart /usr/local/bin/cart
USER nonroot:nonroot
ENV APP_ENV=prod PORT=8080
EXPOSE 8080
ENTRYPOINT ["/usr/local/bin/cart"]
