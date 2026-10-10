# syntax=docker/dockerfile:1.7
# Build context: go/   (docker build -f services/media/Dockerfile go)
FROM golang:1.27.1 AS build
WORKDIR /src
ENV CGO_ENABLED=0 GOWORK=off GOFLAGS=-trimpath
COPY pkg/go.mod pkg/go.sum ./pkg/
COPY services/media/go.mod services/media/go.sum ./services/media/
RUN --mount=type=cache,target=/go/pkg/mod cd services/media && go mod download
COPY pkg ./pkg
COPY services/media ./services/media
ARG VERSION=dev
RUN --mount=type=cache,target=/go/pkg/mod --mount=type=cache,target=/root/.cache/go-build \
    cd services/media && go build -ldflags="-s -w -X main.version=${VERSION}" -o /out/media ./cmd/media

FROM gcr.io/distroless/static-debian12:nonroot
COPY --from=build /out/media /usr/local/bin/media
USER nonroot:nonroot
ENV APP_ENV=prod PORT=8080
EXPOSE 8080
ENTRYPOINT ["/usr/local/bin/media"]
