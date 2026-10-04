FROM node:22-trixie-slim AS web-build
WORKDIR /workspace/web
COPY Frontend/traina/traina/package.json Frontend/traina/traina/package-lock.json ./
RUN npm ci
COPY Frontend/traina/traina/ ./
RUN npm run build && npm prune --omit=dev

FROM maven:3.9.9-eclipse-temurin-21 AS api-build
WORKDIR /workspace/api
COPY Backend/Capacitación/apprentice-training-api/apprentice-training-api/pom.xml ./
RUN mvn -q -DskipTests dependency:go-offline
COPY Backend/Capacitación/apprentice-training-api/apprentice-training-api/src ./src
RUN mvn -q -DskipTests package

FROM node:22-trixie-slim
RUN apt-get update && apt-get install -y --no-install-recommends openjdk-21-jre-headless ffmpeg curl ca-certificates \
    && rm -rf /var/lib/apt/lists/*
WORKDIR /app
ENV NODE_ENV=production HOST=0.0.0.0 APP_DATA_DIR=/app/data
COPY --from=api-build /workspace/api/target/training-api-1.0.0.jar /app/api.jar
COPY --from=web-build /workspace/web/package.json /app/web/package.json
COPY --from=web-build /workspace/web/node_modules /app/web/node_modules
COPY --from=web-build /workspace/web/.output /app/web/.output
COPY railway-seed/data/ /seed/data/
COPY railway-seed/render.env.seed /app/.env
COPY deploy/start-railway.sh /app/start-railway.sh
RUN chmod 600 /app/.env
EXPOSE 3000
ENTRYPOINT ["/bin/sh", "/app/start-railway.sh"]
