# Stage 1 — build the Angular app
FROM node:20-alpine AS build
WORKDIR /app
COPY package*.json ./
RUN npm ci
COPY . .
RUN npm run build

# Stage 2 — serve static build with nginx (also proxies /api/* to the agents)
FROM nginx:alpine
COPY nginx.conf /etc/nginx/conf.d/default.conf
# Angular application builder emits to dist/<project>/browser
COPY --from=build /app/dist/zupptrade-ui/browser /usr/share/nginx/html
EXPOSE 80
