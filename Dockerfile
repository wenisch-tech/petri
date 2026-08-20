# syntax=docker/dockerfile:1.26

FROM cgr.dev/chainguard/jre:latest
WORKDIR /app

ARG BUILD_DATE
ARG BUILD_VERSION
ARG BUILD_REVISION

LABEL org.opencontainers.image.title="Petri" \
      org.opencontainers.image.description="Self-hosted orchestrator for AI coding agents" \
      org.opencontainers.image.url="https://github.com/wenisch-tech/petri" \
      org.opencontainers.image.source="https://github.com/wenisch-tech/petri" \
      org.opencontainers.image.documentation="https://github.com/wenisch-tech/petri/tree/main/docs" \
      org.opencontainers.image.authors="JFWenisch" \
      org.opencontainers.image.licenses="AGPL-3.0" \
      org.opencontainers.image.vendor="JFWenisch" \
      org.opencontainers.image.version="${BUILD_VERSION}" \
      org.opencontainers.image.revision="${BUILD_REVISION}" \
      org.opencontainers.image.created="${BUILD_DATE}"

ENV JAVA_TOOL_OPTIONS="-XX:+UseContainerSupport -XX:MaxRAMPercentage=75.0 -XX:InitialRAMPercentage=20.0 -XX:+UseG1GC -Djava.security.egd=file:/dev/urandom"

# Create the data directory in the image, owned by the non-root runtime user.
# Docker initialises a fresh named volume from the image path it is mounted
# over, ownership included; without this the volume arrives root-owned and the
# process cannot create its database. There is no shell in this image, so this
# is a COPY --chown rather than a RUN mkdir.
COPY --chown=65532:65532 docker/data/.keep /app/data/.keep

COPY --chown=65532:65532 target/petri-*.jar /app/app.jar

EXPOSE 8080
ENTRYPOINT ["java", "-jar", "/app/app.jar"]
