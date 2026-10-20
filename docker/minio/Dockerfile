FROM golang:1.24-alpine3.22@sha256:3641e0d9b931dc4f2f185dcd669c4679670e9277c8166a838ddb98a2d4389cb5 AS build

ARG MINIO_VERSION=RELEASE.2025-10-15T17-29-55Z

RUN CGO_ENABLED=0 go install -trimpath -ldflags="-s -w" "github.com/minio/minio@${MINIO_VERSION}"

FROM alpine:3.22@sha256:5291449c3df73caf6ed85e649dec1b9e818b39a5d8c871e97afc13e9cd5e8fa8

RUN apk add --no-cache ca-certificates

COPY --from=build /go/bin/minio /usr/bin/minio

EXPOSE 9000 9001
VOLUME ["/data"]

ENTRYPOINT ["minio"]
