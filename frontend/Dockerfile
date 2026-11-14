# ---- build ----
FROM node:24-alpine AS build
WORKDIR /app
COPY package.json package-lock.json ./
RUN npm ci
COPY . .
RUN npm run build

# ---- serve ----
FROM nginxinc/nginx-unprivileged:stable-alpine
COPY nginx.conf /etc/nginx/templates/default.conf.template
COPY --from=build /app/dist /usr/share/nginx/html
# Where nginx forwards /api. Overridden by docker-compose or the hosting platform.
ENV API_UPSTREAM=http://backend:8080
# Only this variable is substituted into the nginx template, so nginx variables like $host are left alone.
ENV NGINX_ENVSUBST_FILTER=API_UPSTREAM
EXPOSE 8080
