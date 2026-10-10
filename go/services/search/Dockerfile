# syntax=docker/dockerfile:1.7
# Build context: go/   (docker build -f services/search/Dockerfile go)
FROM golang:1.27.1 AS build
WORKDIR /src
ENV CGO_ENABLED=0 GOWORK=off GOFLAGS=-trimpath
COPY pkg/go.mod pkg/go.sum ./pkg/
COPY services/search/go.mod services/search/go.sum ./services/search/
RUN --mount=type=cache,target=/go/pkg/mod cd services/search && go mod download
COPY pkg ./pkg
COPY services/search ./services/search
ARG VERSION=dev
RUN --mount=type=cache,target=/go/pkg/mod --mount=type=cache,target=/root/.cache/go-build \
    cd services/search && go build -ldflags="-s -w -X main.version=${VERSION}" -o /out/search ./cmd/search

FROM gcr.io/distroless/static-debian12:nonroot
COPY --from=build /out/search /usr/local/bin/search
USER nonroot:nonroot
ENV APP_ENV=prod PORT=8080
EXPOSE 8080
ENTRYPOINT ["/usr/local/bin/search"]
