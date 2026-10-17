FROM node:24-alpine AS build

WORKDIR /app
COPY package.json package-lock.json ./
RUN npm ci
COPY . .
RUN npm run build

FROM joseluisq/static-web-server:2.44.1

COPY --from=build /app/dist /public

# config.json is overridden by the chart (environment and version)
ENV SERVER_PORT=8080 \
    SERVER_ROOT=/public \
    SERVER_FALLBACK_PAGE=/public/index.html \
    SERVER_HEALTH=true \
    SERVER_LOG_LEVEL=info

USER 65534:65534
EXPOSE 8080
