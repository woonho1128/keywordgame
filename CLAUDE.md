# 프로젝트 메모

## 재배포 (서버)

프로덕션 서버에서 사용하는 재배포 명령어 (백엔드·프론트 모두 systemd 서비스로 운영):

```bash
cd /home/ubuntu/keywordGame/keywordgame && git pull origin claude/spyfall-mafia-game-url-ks6y07 && \
cd backend && mvn clean package -DskipTests && sudo systemctl restart wordplay-backend.service && \
cd ../frontend && npm install && npm run build && sudo systemctl restart wordplay-frontend.service && echo "✅ 완료"
```

- 서버 경로: `/home/ubuntu/keywordGame/keywordgame`
- 백엔드 서비스: `wordplay-backend.service` (Spring Boot)
- 프론트 서비스: `wordplay-frontend.service` (Next.js)
- 개발 브랜치: `claude/spyfall-mafia-game-url-ks6y07`
- 도메인: https://gg.wonono1128.com
- 백엔드 변경이 있으면 반드시 백엔드도 재빌드/재시작해야 함(프론트만 바꿨으면 프론트만).
