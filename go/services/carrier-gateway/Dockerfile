# syntax=docker/dockerfile:1.7
# Build context: go/   (docker build -f services/carrier-gateway/Dockerfile go)
FROM golang:1.27.4@sha256:5e8afd493a7124a8586e134d9b532731161d4b9becf9dee5a5956b107426f93d AS build
WORKDIR /src
ENV CGO_ENABLED=0 GOWORK=off GOFLAGS="-trimpath -buildvcs=false"
COPY pkg/go.mod pkg/go.sum ./pkg/
COPY services/carrier-gateway/go.mod services/carrier-gateway/go.sum ./services/carrier-gateway/
WORKDIR /src/services/carrier-gateway
RUN --mount=type=cache,target=/go/pkg/mod go mod download
COPY pkg /src/pkg
COPY services/carrier-gateway /src/services/carrier-gateway
ARG VERSION=dev
RUN --mount=type=cache,target=/go/pkg/mod --mount=type=cache,target=/root/.cache/go-build \
    go build -ldflags="-s -w -X main.version=${VERSION}" -o /out/carrier-gateway ./cmd/carrier-gateway

FROM gcr.io/distroless/static-debian12:nonroot@sha256:afa5c872c891853ca7fcf1f12c3edb23f7eeef36189728842dd51042ff57f7ab
COPY --from=build /out/carrier-gateway /usr/local/bin/carrier-gateway
USER nonroot:nonroot
ENV APP_ENV=prod PORT=8080
EXPOSE 8080
ENTRYPOINT ["/usr/local/bin/carrier-gateway"]
