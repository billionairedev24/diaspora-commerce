# syntax=docker/dockerfile:1.7
# Build context: go/   (docker build -f services/notifications/Dockerfile go)
FROM golang:1.27.1 AS build
WORKDIR /src
ENV CGO_ENABLED=0 GOWORK=off GOFLAGS=-trimpath
COPY pkg/go.mod pkg/go.sum ./pkg/
COPY services/notifications/go.mod services/notifications/go.sum ./services/notifications/
RUN --mount=type=cache,target=/go/pkg/mod cd services/notifications && go mod download
COPY pkg ./pkg
COPY services/notifications ./services/notifications
ARG VERSION=dev
RUN --mount=type=cache,target=/go/pkg/mod --mount=type=cache,target=/root/.cache/go-build \
    cd services/notifications && go build -ldflags="-s -w -X main.version=${VERSION}" -o /out/notifications ./cmd/notifications

FROM gcr.io/distroless/static-debian12:nonroot
COPY --from=build /out/notifications /usr/local/bin/notifications
USER nonroot:nonroot
ENV APP_ENV=prod PORT=8080
EXPOSE 8080
ENTRYPOINT ["/usr/local/bin/notifications"]
