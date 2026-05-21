# WordPlay

친구들과 함께 즐기는 한국어 단어 게임 플랫폼.

- **WordGuess** — 가변 자리수 한글 워들 (꼬들형)
- **WordSim** — 의미 유사도 게임 (꼬맨틀형, Phase 3 예정)

## 디렉토리

```
keywordgaem/
├── DESIGN.md           ← 설계서 v1.1
├── README.md
├── db/
│   └── schema.sql      ← Supabase에 실행할 DDL
├── backend/            ← Spring Boot 3 (Java 17)
└── frontend/           ← Next.js 14 (TypeScript)
```

## 빠른 시작

### 1) DB 준비
1. Supabase 프로젝트 생성 (Free Tier)
2. SQL Editor에서 `db/schema.sql` 실행
3. 프로젝트 설정 → Database → Connection Pooler (Transaction mode) 정보 복사
   - host: `aws-0-{region}.pooler.supabase.com`
   - port: `6543`
   - user: `postgres.{project_ref}`

### 2) 백엔드 실행

Maven 또는 Gradle 둘 다 지원. 환경에 맞게 선택:

```powershell
cd backend

# Windows에서 환경변수 로드 후 실행 (PowerShell)
$env:DB_URL      = "jdbc:postgresql://aws-1-ap-northeast-2.pooler.supabase.com:6543/postgres?sslmode=require&channelBinding=disable&prepareThreshold=0"
$env:DB_USERNAME = "postgres.your_project_ref"
$env:DB_PASSWORD = "..."
$env:CORS_ORIGINS = "http://localhost:3000"

# Maven
mvn spring-boot:run
```

> ⚠️ **Supabase JDBC 접속 핵심**: `channelBinding=disable` 옵션이 **필수**입니다. JDBC 드라이버의 SCRAM-SHA-256 처리가 Supabase Pooler와 충돌해서 인증 실패가 나는 알려진 이슈. 이 옵션 빼면 비밀번호가 맞아도 `password authentication failed` 에러가 납니다.

빌드 검증:
- `mvn test` → HangulUtil 7개 테스트 통과
- `mvn package -DskipTests` → 실행 가능 JAR 생성

서버: http://localhost:8080

### 3) 프론트엔드 실행

```powershell
cd frontend
copy .env.example .env.local
npm install
npm run dev
```

브라우저: http://localhost:3000

## 진행 상황

- [x] 설계서 v1.1
- [x] DB 스키마 (TB_GAME / TB_PLAY_RECORD / TB_GUESS_LOG / TB_SIMILARITY)
- [x] **Phase 1: WordGuess MVP 완료**
  - [x] Spring Boot 백엔드
    - 공통 모듈 (ApiResponse, BusinessException, ErrorCode, GlobalExceptionHandler, CORS)
    - HangulUtil + 6개 단위 테스트 (Wordle 표준 2-pass)
    - SessionManager (쿠키 기반 session_key)
    - Game 도메인: 생성/조회/최근 목록 API
    - Play 도메인: /start, /guess (WordGuess 완성, WordSim stub), /giveup
    - Leaderboard API
  - [x] Next.js 프론트엔드
    - 홈, 출제 폼, 게임 플레이 (HangulBoard), 리더보드
    - JamoTile (색맹 대응 ✓/↔/✗ 아이콘 병기)
- [ ] Phase 2: 공유 URL 페이지, 최근 게임 목록 UI, 모바일 반응형 다듬기
- [ ] Phase 3: WordSim 사전 적재 + UI

## API 엔드포인트

| Method | Path | 설명 |
|--------|------|------|
| POST | `/api/v1/games` | 게임 생성 |
| GET | `/api/v1/games/{id}` | 게임 메타 조회 |
| GET | `/api/v1/games/recent?type=&page=&size=` | 최근 게임 목록 |
| POST | `/api/v1/games/{id}/start` | 게임 시작 (세션 쿠키 발급) |
| POST | `/api/v1/games/{id}/guess` | 단어 추측 |
| POST | `/api/v1/games/{id}/giveup` | 포기 (정답 공개) |
| GET | `/api/v1/games/{id}/leaderboard?limit=` | 리더보드 |

## 의사결정 요약 (v1.1 핵심)

- 정답은 **평문 저장** (친구용, 보안 요구 낮음)
- AES 암호화 / IP 해시 / Rate Limit / Redis **모두 제거** → 운영 단순화
- WordSim 임베딩은 **오프라인 사전 계산** → DB 저장 → 비용 0원
- WordGuess 자모 비교는 **Wordle 표준 2-pass** (예: 정답 "사과" + 추측 "사사" → 두 번째 ㅅ은 회색)
- Supabase Pooler 사용 시 `prepareThreshold=0` 필수

자세한 내용은 [`DESIGN.md`](./DESIGN.md) 참고.
