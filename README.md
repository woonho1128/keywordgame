# WordPlay

친구들과 함께 즐기는 한국어 단어 게임 플랫폼.

- **WordGuess** — 가변 자리수 한글 워들 (꼬들형)
- **WordSim** — 의미 유사도 게임 (꼬맨틀형, Phase 3 예정)
- **Lie Hint** — 힌트 3개 중 거짓말까지 찾는 게임
- **AI 사주** — 사주팔자는 서버가 계산하고 해석만 AI가 (종합/연애/재물/직업/공부/건강/올해)
- **궁합** — 두 사람의 사주를 맞대어 합·충을 판정 (연인/부부/친구/동료/가족)

## 디렉토리

```
keywordgaem/
├── DESIGN.md              ← 설계서 v1.1
├── LIE_HINT_DESIGN.md     ← Lie Hint 설계안
├── SAJU_DESIGN.md         ← AI 사주 설계안 (만세력 계산 규칙 포함)
├── README.md
├── db/
│   ├── schema.sql         ← Supabase에 실행할 DDL (전체)
│   ├── lie_hint_migration.sql
│   └── saju_migration.sql ← 기존 DB에 사주 테이블만 추가
├── backend/               ← Spring Boot 3 (Java 17)
└── frontend/              ← Next.js 14 (TypeScript)
```

## 빠른 시작

### 1) DB 준비
1. Supabase 프로젝트 생성 (Free Tier)
2. SQL Editor에서 `db/schema.sql` 실행
   - 이미 운영 중인 DB라면 전체 대신 `db/saju_migration.sql`(사주·궁합 테이블)만 실행
   - `CREATE TABLE IF NOT EXISTS`라 이미 적용한 DB에 다시 실행해도 안전
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

# AI 사주 / WordSim 임베딩용 (없으면 사주는 비활성화)
$env:OPENAI_API_KEY = "sk-..."

# Maven
mvn spring-boot:run
```

> ⚠️ **Supabase JDBC 접속 핵심**: `channelBinding=disable` 옵션이 **필수**입니다. JDBC 드라이버의 SCRAM-SHA-256 처리가 Supabase Pooler와 충돌해서 인증 실패가 나는 알려진 이슈. 이 옵션 빼면 비밀번호가 맞아도 `password authentication failed` 에러가 납니다.

빌드 검증:
- `mvn test` → 전체 테스트 통과 (HangulUtil / 만세력 계산 / 사주 API)
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
- [x] **AI 사주 완료**
  - [x] 만세력 계산기 (절기 기반 연·월주, 율리우스일 일주, 오자둔 시주, 대운/세운) + 단위 테스트
  - [x] OpenAI Chat 연동 (JSON 응답, 모델 호환 재시도, 호출 한도, 동일 입력 재사용)
  - [x] 사주 7종 (종합/연애/재물/직업/공부/건강/올해의 운세)
  - [x] `/saju` 입력 화면, `/saju/{id}` 결과 + 공유 (OG 메타태그)
  - [x] 해석 상세화 — 종류별 6섹션, 강점·주의점·시기별 흐름, 십성 분포/지장간/일간의 힘 근거 제공
- [x] **궁합 완료**
  - [x] 천간합·충, 지지 육합·삼합·충·형·해·파 판정 (12×12 전 조합을 전통 목록과 대조 검증)
  - [x] 자리별 가중치(일지 최우선) 기반 점수 — A·B 순서를 바꿔도 같은 값
  - [x] 궁합 5종 (연인/부부/친구/동료/가족), `/saju/compat` 입력·결과 화면
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
| GET | `/api/v1/saju/types` | 사주 종류 목록 + 사용 가능 여부 |
| POST | `/api/v1/saju` | 사주 보기 (AI 해석 생성) |
| GET | `/api/v1/saju/{readingId}` | 사주 결과 다시 보기 |
| GET | `/api/v1/saju/compat/types` | 궁합 종류 목록 |
| POST | `/api/v1/saju/compat` | 궁합 보기 (AI 해석 생성) |
| GET | `/api/v1/saju/compat/{compatId}` | 궁합 결과 다시 보기 |

## 의사결정 요약 (v1.1 핵심)

- 정답은 **평문 저장** (친구용, 보안 요구 낮음)
- AES 암호화 / IP 해시 / Rate Limit / Redis **모두 제거** → 운영 단순화
- WordSim 임베딩은 **오프라인 사전 계산** → DB 저장 → 비용 0원
- WordGuess 자모 비교는 **Wordle 표준 2-pass** (예: 정답 "사과" + 추측 "사사" → 두 번째 ㅅ은 회색)
- Supabase Pooler 사용 시 `prepareThreshold=0` 필수

### AI 사주 (추가)

- 사주팔자 계산은 **서버(Java)**, 해석만 **AI**. LLM은 60갑자·절기를 자주 틀린다
- 절입 시각은 근사식 대신 **태양황경 직접 계산** — 근사식은 베이징 기준이라 한국시 자정 근처에서 하루씩 어긋난다
- **양력만 입력** (음력 생일은 사용자가 변환)
- 같은 입력 + 같은 해면 이전 해석 **재사용** (사주는 안 바뀌고 AI 비용도 아낀다)
- AI 호출은 실제 비용이 나가므로 **in-memory 호출 한도**를 둔다 (게임 쪽 Rate Limit 제거 방침의 예외)
- 궁합의 합·충 판정과 점수도 **서버 계산** — AI는 점수에 맞춰 이유를 설명만 한다
- 사주 해석은 `app.saju.chat-*`, 마피아 봇은 `app.openai.chat-*` — **설정 키를 분리**한다
  (같은 키를 쓰면 한쪽 모델을 바꿀 때 다른 쪽까지 끌려간다)

자세한 내용은 [`DESIGN.md`](./DESIGN.md), [`SAJU_DESIGN.md`](./SAJU_DESIGN.md) 참고.
