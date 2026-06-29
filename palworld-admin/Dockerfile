# ARM64(오라클 Ampere)에서 네이티브로 동작 — 에뮬레이션 불필요
FROM node:20-alpine

WORKDIR /app

COPY package*.json ./
RUN npm install --omit=dev

COPY . .

EXPOSE 3000
CMD ["node", "server.js"]
