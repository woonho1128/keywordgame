# 단어 게임 플랫폼 설계서

> **프로젝트명:** (가칭) WordPlay
> **버전:** 1.1
> **작성자:** 우노
> **최종 수정:** 2026-05-21

---

## 1. 프로젝트 개요

### 1.1 목적
친구들끼리 즐기는 캐주얼 단어 게임 플랫폼. 사용자가 직접 정답·힌트를 출제하고 공유 URL로 함께 플레이.

### 1.2 운영 전제 (v1.1 추가)
- **저트래픽**: 친구 단위 사용. 동시 접속 수십 명 이하 가정
- **저보안 요구**: 정답이 DB에 평문 저장돼도 운영상 무방
- **완전 무료 운영**: 클라우드/외부 API 비용 0원 목표

### 1.3 핵심 특징
- 회원가입 없음, 닉네임만으로 플레이
- 공유 URL 시스템 (`/g/{gameId}`)
- 게임별 공개 리더보드
- 단어 자유 입력 (느슨한 검증)

### 1.4 게임 모드

| 모드 | 설명 | 게임 방식 |
|------|------|----------|
| **WordSim** (꼬맨틀형) | 단어 유사도 게임 | 추측 단어와 정답의 의미적 유사도(0~100점) 및 순위 표시 |
| **WordGuess** (꼬들형) | 가변 자리수 한글 워들 | 정답 글자 수에 맞춰 보드 동적 조정. 자모(초/중/종) 단위 색상 피드백 |

---

## 2. v1.0 → v1.1 주요 변경 사항

| 영역 | v1.0 | v1.1 |
|------|------|------|
| 정답 저장 | AES-256 암호화 + SHA-256 해시 | **평문 저장** (보안 요구 낮음) |
| `creator_ip_hash` | 어뷰징 방지용 IP 해시 | **제거** |
| Rate Limiting | Bucket4j + Redis | **제거** (단순 in-memory만, 또는 생략) |
| Redis 캐싱 | precompute top-1000 | **제거** (DB 직접) |
| WORDSIM 임베딩 | Python + fastText 상시 서버 | **오프라인 사전 계산 → DB 저장** |
| PlayRecord 상태 | `is_solved` + `is_gave_up` boolean 2개 | `status` ENUM 1개 |
| `letter_result` | VARCHAR 인코딩 | **JSONB + Wordle 표준 규칙** 명시 |
| 단어 정규화 | 미정의 | NFC + trim 표준 |
| Supabase Pooler | 언급만 | `prepareThreshold=0` 명시 |

---

## 3. 시스템 아키텍처

### 3.1 구성도

```
┌──────────────────────────────────────────────────┐
│  Client (Browser)                                │
│  Next.js 14 + React + TypeScript + Tailwind     │
└────────────────────┬─────────────────────────────┘
                     │ HTTPS / REST
                     ▼
┌──────────────────────────────────────────────────┐
│  Spring Boot 3 Backend (Java 17)                 │
│  Controller → Service → Repository (JPA)         │
└────────────────────┬─────────────────────────────┘
                     │ JDBC (via Supabase Pooler)
                     ▼
┌──────────────────────────────────────────────────┐
│  Supabase PostgreSQL                             │
│  - TB_GAME / TB_PLAY_RECORD / TB_GUESS_LOG       │
│  - TB_SIMILARITY (WordSim 오프라인 사전)          │
└──────────────────────────────────────────────────┘

[빌드 타임 1회만]
fastText KR 모델 → 단어 5,000개 유사도 매트릭스 → CSV → Supabase
```

### 3.2 기술 스택

**Backend**
- Java 17, Spring Boot 3.3.x
- Spring Web, Spring Data JPA, Spring Validation
- HikariCP (PostgreSQL 연결 풀)
- Gradle (Groovy DSL)
- Lombok

**Frontend**
- Next.js 14 (App Router)
- TypeScript
- TailwindCSS
- TanStack Query (서버 상태)
- Zustand (클라이언트 상태)

**Database**
- Supabase PostgreSQL (Free Tier)
- Connection: Transaction Pooler (port 6543)

**임베딩 (오프라인 1회용)**
- Python 3.11 + gensim + fastText (개발자 PC 또는 Colab)
- 결과만 CSV로 Supabase에 적재

**Infra**
- Frontend: Vercel (Hobby)
- Backend: Oracle Cloud Free Tier ARM (4 OCPU, 24GB RAM)
- DB: Supabase Free Tier

---

## 4. 데이터베이스 설계

### 4.1 ERD 개요

```
TB_GAME (1) ─── (N) TB_PLAY_RECORD (1) ─── (N) TB_GUESS_LOG

TB_SIMILARITY (정적 사전) — WordSim 게임 생성/플레이 시 참조
```

### 4.2 TB_GAME

| 컬럼명 | 타입 | NULL | 설명 |
|--------|------|------|------|
| game_id | VARCHAR(12) | NN | PK. nanoid 8자리 |
| game_type | VARCHAR(20) | NN | `WORDSIM`, `WORDGUESS` |
| answer_word | VARCHAR(100) | NN | 정답 (평문, NFC 정규화) |
| word_length | INTEGER | NN | 정답 글자 수 (자동 계산) |
| hint_text | VARCHAR(500) | Y | 출제자 힌트 |
| creator_nick | VARCHAR(50) | Y | 출제자 닉네임 |
| is_public | BOOLEAN | NN | 공개 게임 목록 노출 (default TRUE) |
| play_count | INTEGER | NN | 총 플레이 수 (default 0) |
| solved_count | INTEGER | NN | 정답 맞춘 횟수 (default 0) |
| created_at | TIMESTAMP | NN | 생성 시각 (default now()) |

**제약**
- PK: `game_id`
- CHECK: `game_type IN ('WORDSIM', 'WORDGUESS')`

**인덱스**
- `idx_game_public_created`: `(is_public, created_at DESC)`

### 4.3 TB_PLAY_RECORD

| 컬럼명 | 타입 | NULL | 설명 |
|--------|------|------|------|
| record_id | BIGSERIAL | NN | PK |
| game_id | VARCHAR(12) | NN | FK → TB_GAME |
| player_nick | VARCHAR(50) | NN | 플레이어 닉네임 |
| session_key | VARCHAR(64) | NN | 쿠키 기반 UUID |
| status | VARCHAR(20) | NN | `IN_PROGRESS`, `SOLVED`, `GAVE_UP` |
| attempt_count | INTEGER | NN | 시도 횟수 (default 0) |
| time_spent_sec | INTEGER | Y | 풀이 시간(초) |
| started_at | TIMESTAMP | NN | 시작 시각 |
| finished_at | TIMESTAMP | Y | 종료 시각 |

**제약**
- PK: `record_id`
- FK: `game_id` → `TB_GAME(game_id)` ON DELETE CASCADE
- UNIQUE: `(game_id, session_key)`
- CHECK: `status IN ('IN_PROGRESS', 'SOLVED', 'GAVE_UP')`

**인덱스**
- `idx_play_leaderboard`: `(game_id, attempt_count ASC, time_spent_sec ASC) WHERE status = 'SOLVED'` (partial index)

### 4.4 TB_GUESS_LOG

| 컬럼명 | 타입 | NULL | 설명 |
|--------|------|------|------|
| log_id | BIGSERIAL | NN | PK |
| record_id | BIGINT | NN | FK → TB_PLAY_RECORD |
| guess_word | VARCHAR(100) | NN | 추측 단어 (NFC 정규화) |
| guess_order | INTEGER | NN | 시도 순서 (1부터) |
| similarity | REAL | Y | WordSim 유사도 (0~1) |
| rank_value | INTEGER | Y | WordSim 순위 (top-1000) |
| letter_result | JSONB | Y | WordGuess 자모 비교 결과 |
| is_correct | BOOLEAN | NN | 정답 여부 |
| created_at | TIMESTAMP | NN | 시도 시각 (default now()) |

**제약**
- PK: `log_id`
- FK: `record_id` → `TB_PLAY_RECORD(record_id)` ON DELETE CASCADE

**인덱스**
- `idx_log_record`: `(record_id, guess_order)`

**`letter_result` JSONB 스키마 (WordGuess)**

```json
[
  {"syllable": "사", "cho": "H", "jung": "H", "jong": null},
  {"syllable": "람", "cho": "S", "jung": "M", "jong": "S"}
]
```

- `H` = Hit (정답 위치에 정답 자모, 초록)
- `M` = Move (정답에 포함되지만 다른 위치, 노랑)
- `S` = Skip (정답에 없음, 회색)
- `null` = 자모 자체가 없음 (예: 종성 없음)

### 4.5 TB_SIMILARITY (WordSim용 정적 사전)

| 컬럼명 | 타입 | NULL | 설명 |
|--------|------|------|------|
| word_a | VARCHAR(50) | NN | 단어 A (NFC) |
| word_b | VARCHAR(50) | NN | 단어 B (NFC) |
| similarity | REAL | NN | 코사인 유사도 (0~1) |
| rank_value | INTEGER | NN | word_a 기준 word_b의 유사도 순위 |

**제약**
- PK: `(word_a, word_b)`

**인덱스**
- `idx_sim_lookup`: `(word_a, similarity DESC)`

**적재 방식**
- 빌드 타임 1회: Python으로 fastText KR 모델 로딩 → 자주 쓰는 단어 5,000개 선정 → 모든 페어 유사도 계산 → CSV로 export → Supabase에 COPY
- 데이터량: 5,000 × 1,000 = 약 500만 행 (각 단어별 top-1000 저장). 약 200MB 추정 → Supabase Free Tier 500MB 한도 내

### 4.6 Supabase 운영

- **RLS**: Spring Boot가 service role key로 접근 → 비활성화
- **Pooler**: Transaction mode (port 6543) 사용, `prepareThreshold=0` 필수
- **백업**: Free Tier 7일 자동 백업

---

## 5. API 설계

### 5.1 공통

**Base URL**: `/api/v1`

**공통 응답**
```json
{ "success": true, "data": { ... }, "error": null }
```

**에러 응답**
```json
{
  "success": false,
  "data": null,
  "error": { "code": "GAME_NOT_FOUND", "message": "..." }
}
```

### 5.2 게임 관리

#### POST `/api/v1/games`
**Request**
```json
{
  "gameType": "WORDSIM",
  "answerWord": "사과",
  "hintText": "빨갛고 둥근 과일",
  "creatorNick": "우노",
  "isPublic": true
}
```

**Response**
```json
{
  "success": true,
  "data": {
    "gameId": "aB3xK9Lm",
    "shareUrl": "https://wordplay.kr/g/aB3xK9Lm",
    "gameType": "WORDSIM",
    "wordLength": 2
  }
}
```

**검증 흐름**
1. 입력 정규화: `answer = NFC(trim(answer))`
2. 글자 수: 1~20
3. WordGuess: 모든 글자가 한글 음절(가~힣)인지 확인
4. WordSim: `TB_SIMILARITY`에 `word_a = answer` 행이 있는지 확인
   - 없으면 `WORD_NOT_IN_DICTIONARY` 에러 → "이 단어는 사전에 없습니다. 다른 단어를 사용해주세요"
5. nanoid 8자리 생성, INSERT

#### GET `/api/v1/games/{gameId}`
게임 메타 정보 조회 (정답 제외)

#### GET `/api/v1/games/recent?type=&page=0&size=20`
최근 공개 게임 목록

### 5.3 플레이

#### POST `/api/v1/games/{gameId}/start`
**Request**: `{ "playerNick": "재진" }`

**Response**: `recordId`, `sessionKey`, `startedAt` 반환 + `Set-Cookie: session_key=...; HttpOnly; SameSite=Lax`

#### POST `/api/v1/games/{gameId}/guess`
**Request**: `{ "guessWord": "딸기" }`

**WordSim Response**
```json
{
  "guessWord": "딸기",
  "guessOrder": 3,
  "inDictionary": true,
  "similarity": 0.6234,
  "similarityScore": 62.34,
  "rank": 245,
  "isCorrect": false,
  "isPersonalBest": true
}
```
- `inDictionary: false` 이면 similarity/rank null. "사전에 없는 단어" 메시지 표시

**WordGuess Response**
```json
{
  "guessWord": "사람",
  "guessOrder": 1,
  "letterResult": [
    {"syllable": "사", "cho": "H", "jung": "H", "jong": null},
    {"syllable": "람", "cho": "S", "jung": "M", "jong": "S"}
  ],
  "isCorrect": false
}
```

#### POST `/api/v1/games/{gameId}/giveup`
**Response**: `{ "answerWord": "사과", "totalAttempts": 15 }`

### 5.4 리더보드

#### GET `/api/v1/games/{gameId}/leaderboard?limit=50`
정렬: `attempt_count ASC, time_spent_sec ASC`, `status = 'SOLVED'` 만

### 5.5 에러 코드 카탈로그

| 코드 | HTTP | 설명 |
|------|------|------|
| `GAME_NOT_FOUND` | 404 | 게임 없음 |
| `GAME_ALREADY_FINISHED` | 400 | 이미 정답/포기 상태 |
| `INVALID_WORD_LENGTH` | 400 | 글자 수 불일치 |
| `INVALID_HANGUL` | 400 | 한글 음절 아님 |
| `WORD_NOT_IN_DICTIONARY` | 400 | WordSim 사전에 없는 단어 |
| `SESSION_NOT_FOUND` | 401 | 세션 키 없음/만료 |
| `DUPLICATE_GUESS` | 400 | 같은 단어 재추측 (선택) |

---

## 6. 핵심 비즈니스 로직

### 6.1 한글 처리 — `HangulUtil`

```java
public class HangulUtil {
    private static final int HANGUL_BASE = 0xAC00;
    private static final int CHO_COUNT = 19, JUNG_COUNT = 21, JONG_COUNT = 28;

    /** 한글 음절을 [초성, 중성, 종성] 인덱스로 분해. 종성 없으면 0 */
    public static int[] decompose(char syllable) { ... }

    /** 한글 음절 여부 */
    public static boolean isHangulSyllable(char c) {
        return c >= HANGUL_BASE && c < HANGUL_BASE + CHO_COUNT * JUNG_COUNT * JONG_COUNT;
    }
}
```

### 6.2 WordGuess 자모 비교 알고리즘

**입력**: 정답 `answer`, 추측 `guess` (둘 다 동일 음절 수)

**출력**: `List<SyllableResult>` — JSONB로 저장

**알고리즘 (Wordle 표준 2-pass)**:

```
1단계 [Hit 판정]
  for i in 0..N-1:
    for jamo in [cho, jung, jong]:
      if answer[i].jamo == guess[i].jamo:
        result[i].jamo = H
      else:
        result[i].jamo = (미정)

2단계 [Move/Skip 판정 — 풀(pool) 기반]
  pool = answer의 모든 자모 중 1단계에서 H로 매칭되지 않은 것들 (multiset)
  for i in 0..N-1:
    for jamo in [cho, jung, jong]:
      if result[i].jamo == 미정:
        if pool에 guess[i].jamo 있음:
          result[i].jamo = M
          pool에서 1개 제거
        else:
          result[i].jamo = S
```

**예시**: 정답 "사과" vs 추측 "사사"
- 1단계: 음절1 초성 ㅅ=ㅅ → H, 음절1 중성 ㅏ=ㅏ → H, 음절2 초성 ㅅ vs ㄱ → 미정, 음절2 중성 ㅏ vs ㅘ → 미정
- pool (H 제외한 정답 자모) = {ㄱ, ㅘ}
- 음절2 초성 ㅅ: pool에 없음 → **S**
- 음절2 중성 ㅏ: pool에 없음 → **S**

**결과**: `[{사 HH-}, {사 SS-}]` ✓

### 6.3 게임 생성 플로우

```
입력 받기 → 정규화(NFC + trim) → 검증
  → WordGuess: 한글 음절 검증
  → WordSim: TB_SIMILARITY에서 정답 존재 확인
nanoid 생성 → INSERT → shareUrl 반환
```

### 6.4 WordSim 추측 처리

```
1. 세션 검증 (쿠키 session_key → PlayRecord 조회)
2. 게임 상태 확인 (IN_PROGRESS 인지)
3. 추측 정규화 (NFC + trim)
4. 정답 비교: guess == answer ?
   → 일치: status=SOLVED, finished_at 갱신, isCorrect=true 응답
5. TB_SIMILARITY 조회:
   SELECT similarity, rank_value FROM TB_SIMILARITY
   WHERE word_a = :answer AND word_b = :guess
   → 행 없으면 inDictionary=false 응답
6. attempt_count 증가, GuessLog INSERT
```

### 6.5 출제자 본인 플레이 처리

- 게임 생성 시 사용한 `session_key`로 같은 게임을 플레이하면 리더보드에서 제외
- 구현: TB_GAME에 `creator_session_key` 컬럼 추가 (선택 사항, v1.1 미포함)
- v1.1에서는 신뢰 기반 — 친구끼리 규칙으로 약속

---

## 7. 프론트엔드 설계

### 7.1 페이지 구조

```
/                       홈 (모드 선택, 최근 게임)
/create/wordsim         WordSim 출제
/create/wordguess       WordGuess 출제
/g/[gameId]             게임 플레이 (모드 자동 인식)
/g/[gameId]/board       리더보드
/g/[gameId]/share       공유 페이지 (생성 직후)
```

### 7.2 컴포넌트

```
components/
├── common/         (NicknameInput, ShareButton, Layout)
├── wordsim/        (GuessInput, SimilarityBar, GuessHistory, RankBadge)
├── wordguess/      (HangulBoard, HangulKeyboard, SyllableCell, JamoTile)
└── leaderboard/    (RankingTable, PlayerRow)
```

### 7.3 한글 입력 (WordGuess)

- 1차 구현: 시스템 IME + composition event 처리
- 2차 (필요 시): 자체 가상 키보드 (두벌식)

### 7.4 접근성

- H/M/S는 색상 + 패턴 병기 (색맹 대응): 예) 초록 + ✓, 노랑 + ↔, 회색 + ✗

---

## 8. WordSim 사전 데이터 준비 (오프라인 1회)

### 8.1 단어 선정

소스 후보:
- 국립국어원 표준국어대사전 빈도 상위
- Wiktionary 한국어 빈도 리스트
- 5,000개 추출 (명사/형용사 중심)

### 8.2 유사도 계산 스크립트 (Python, 1회 실행)

```python
import gensim
import numpy as np
import csv

model = gensim.models.KeyedVectors.load_word2vec_format("cc.ko.300.vec")
words = open("kor_top5000.txt").read().splitlines()

with open("similarity.csv", "w", encoding="utf-8") as f:
    w = csv.writer(f)
    w.writerow(["word_a", "word_b", "similarity", "rank_value"])
    for a in words:
        if a not in model: continue
        vec_a = model[a]
        sims = []
        for b in words:
            if b not in model or a == b: continue
            sims.append((b, float(np.dot(vec_a, model[b]) / (np.linalg.norm(vec_a) * np.linalg.norm(model[b])))))
        sims.sort(key=lambda x: -x[1])
        for rank, (b, sim) in enumerate(sims[:1000], 1):
            w.writerow([a, b, f"{sim:.6f}", rank])
```

### 8.3 Supabase 적재

```sql
COPY TB_SIMILARITY(word_a, word_b, similarity, rank_value)
FROM '/tmp/similarity.csv' WITH (FORMAT csv, HEADER true);
```

---

## 9. 단계별 개발 계획

### Phase 1: WordGuess MVP (1~2주)
- [x] DESIGN.md v1.1
- [ ] Spring Boot 프로젝트 셋업
- [ ] Supabase DB 연결 + 스키마 생성 (`schema.sql`)
- [ ] Game 생성/조회 API
- [ ] HangulUtil 구현 + 단위 테스트
- [ ] WordGuess 추측 API (2-pass 알고리즘)
- [ ] Next.js 프론트엔드 (출제 + 플레이 + 가변 자리수 보드)
- [ ] 기본 리더보드

### Phase 2: 운영 기능 (1주)
- [ ] 공유 URL 페이지
- [ ] 세션 기반 중복 방지
- [ ] 최근 게임 목록
- [ ] 모바일 반응형

### Phase 3: WordSim 추가 (1~2주)
- [ ] fastText KR 모델로 단어 5,000개 유사도 사전 생성 (오프라인)
- [ ] Supabase TB_SIMILARITY 적재
- [ ] WordSim 출제/플레이 API + UI
- [ ] 유사도 게이지, 순위 표시

### Phase 4: 고도화 (이후)
- 통계, 검색, OG 태그, PWA, 일일 챌린지 등

---

## 10. 비용

| 서비스 | 플랜 | 한도 |
|--------|------|------|
| Vercel | Hobby (무료) | 100GB bandwidth/월 |
| Supabase | Free | 500MB DB, 2GB transfer |
| Oracle Cloud | Free Tier ARM | 4 OCPU, 24GB RAM 영구 무료 |
| 도메인 (선택) | .kr | 약 15,000원/년 |

**월 운영비: 0원** (도메인 제외)

---

## 부록 A: Spring Boot 패키지 구조

```
com.wordplay
├── WordPlayApplication.java
├── common/
│   ├── config/         (CorsConfig, WebConfig)
│   ├── exception/      (GlobalExceptionHandler, ErrorCode, BusinessException)
│   ├── util/           (HangulUtil, NanoIdGenerator, TextNormalizer)
│   └── dto/            (ApiResponse, ErrorResponse)
├── game/
│   ├── controller/     (GameController)
│   ├── service/        (GameService)
│   ├── repository/     (GameRepository)
│   ├── entity/         (Game, GameType)
│   └── dto/            (CreateGameRequest, GameResponse, ...)
├── play/
│   ├── controller/     (PlayController)
│   ├── service/        (PlayService, GuessService)
│   ├── repository/     (PlayRecordRepository, GuessLogRepository)
│   ├── entity/         (PlayRecord, GuessLog, PlayStatus)
│   └── dto/
├── similarity/
│   ├── repository/     (SimilarityRepository)
│   └── service/        (SimilarityService)
└── leaderboard/
    ├── controller/
    ├── service/
    └── dto/
```

## 부록 B: 변경 이력

| 버전 | 일자 | 변경 내용 | 작성자 |
|------|------|----------|--------|
| 1.0 | 2026-05-20 | 최초 작성 | 우노 |
| 1.1 | 2026-05-21 | 친구용 저트래픽 전제로 단순화: 정답 평문, AES/Redis/IP hash/Rate limit 제거, WordSim 오프라인 사전, status enum, JSONB letter_result, Wordle 표준 자모 규칙, Supabase pooler 설정 명시 | 우노 |
