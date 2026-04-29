FROM amazoncorretto:21-alpine

RUN apk add --no-cache bash curl gradle maven nodejs npm \
 && addgroup -S sandbox \
 && adduser -S -G sandbox sandbox \
 && mkdir -p /work \
 && chown -R sandbox:sandbox /work

USER sandbox
WORKDIR /work

ENV GRADLE_OPTS="-Dorg.gradle.daemon=false -Xmx1g" \
    MAVEN_OPTS="-Xmx1g" \
    NPM_CONFIG_LOGLEVEL=warn

CMD ["/bin/bash"]
