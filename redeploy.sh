#!/usr/bin/env bash
# gg 프로덕션 재배포 스크립트.
# 사용: bash /home/ubuntu/keywordGame/keywordgame/redeploy.sh
# (프론트만 바뀐 경우엔  bash redeploy.sh front  로 백엔드 빌드 생략)
set -euo pipefail

REPO="/home/ubuntu/keywordGame/keywordgame"
BRANCH="claude/spyfall-mafia-game-url-ks6y07"
MODE="${1:-all}"   # all | front | back

cd "$REPO"
echo "▶ git pull ($BRANCH)"
git pull origin "$BRANCH"

if [ "$MODE" != "front" ]; then
  echo "▶ 백엔드 빌드/재시작"
  ( cd backend && mvn clean package -DskipTests && sudo systemctl restart wordplay-backend.service )
fi

if [ "$MODE" != "back" ]; then
  echo "▶ 프론트 빌드/재시작"
  ( cd frontend && npm install && npm run build && sudo systemctl restart wordplay-frontend.service )
fi

echo "✅ 재배포 완료 ($MODE)"
