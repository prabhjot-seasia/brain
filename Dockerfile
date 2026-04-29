FROM eclipse-temurin:21-jre-alpine

RUN apk add --no-cache wget ca-certificates nodejs npm chromium ttf-freefont \
    && npm install -g @mermaid-js/mermaid-cli \
    && mkdir -p /etc/mmdc \
    && printf '{"args":["--no-sandbox","--disable-setuid-sandbox","--disable-dev-shm-usage"]}\n' > /etc/mmdc/puppeteer-config.json \
    && printf '#!/bin/sh\nexec /usr/local/bin/mmdc -p /etc/mmdc/puppeteer-config.json "$@"\n' > /usr/local/bin/mmdc-sandboxed \
    && chmod +x /usr/local/bin/mmdc-sandboxed

ENV PUPPETEER_SKIP_DOWNLOAD=true \
    PUPPETEER_EXECUTABLE_PATH=/usr/bin/chromium-browser \
    BRAIN_DOCS_MERMAID_CLI_PATH=mmdc-sandboxed

RUN addgroup -S brain && adduser -S -G brain -h /home/brain brain

WORKDIR /app
COPY build/libs/project-brain.jar /app/

RUN chown -R brain:brain /app

USER brain

EXPOSE 8080

HEALTHCHECK --interval=30s --timeout=5s --start-period=120s --retries=3 \
    CMD wget -q --spider http://localhost:8080/actuator/health || exit 1

ENTRYPOINT ["java", "-jar", "/app/project-brain.jar"]
