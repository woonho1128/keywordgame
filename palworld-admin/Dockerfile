# ARM64(오라클 Ampere)에서 네이티브로 동작 — 에뮬레이션 불필요
FROM node:20-alpine

# 서버 재가동/업데이트 기능에 필요한 docker CLI (호스트 소켓을 통해 사용)
RUN apk add --no-cache docker-cli

WORKDIR /app

COPY package*.json ./
RUN npm install --omit=dev

COPY . .

EXPOSE 3000
CMD ["node", "server.js"]
