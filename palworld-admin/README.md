# 팰월드 서버 관리 패널 (Palworld RCON Web Admin)

오라클 ARM 서버에서 돌아가는 팰월드 전용 서버를 **웹에서 관리**하는 작은 패널이에요.
접속자 목록 보기 · 강퇴 · 밴 · 공지 · 저장 · 안전 재시작을 브라우저에서 버튼으로 합니다.

- **백엔드**: Node.js(Express) + `rcon-client`
- **프론트**: 단일 HTML (별도 빌드 없음, 모바일 대응)
- **노출**: Cloudflare Tunnel → 무료 HTTPS, 포트 개방 불필요
- **보안 구조**: 브라우저 → (HTTPS, 로그인) → 이 패널 → `localhost` RCON. RCON 포트는 외부에 안 열립니다.

```
[브라우저] --HTTPS--> [Cloudflare Tunnel] --내부망--> [palworld-admin] --RCON--> [palworld-server:25575]
```

---

## 사전 준비 (팰월드 서버 쪽)

`PalWorldSettings.ini` 에 아래가 켜져 있어야 합니다:

```
RCONEnabled=True
RCONPort=25575
AdminPassword="..."   ← 이 값을 패널의 RCON_PASSWORD 로 씁니다
```

그리고 팰월드 `docker-compose.yml` 의 ports 에 RCON 이 (localhost 전용으로) 매핑돼 있어야 합니다:

```yaml
    ports:
      - '8211:8211/udp'
      - '127.0.0.1:25575:25575/tcp'
```

> 참고: 이 패널은 **도커 내부 네트워크**로 `palworld-server:25575` 에 직접 붙기 때문에,
> 위 `127.0.0.1:25575` 호스트 매핑이 없어도 동작합니다(있어도 무방).

---

## 배포 (오라클 서버에서)

### 1) 코드 가져오기

```bash
cd ~
git clone https://github.com/woonho1128/keywordgame.git
cd keywordgame/palworld-admin
```

> 이미 받아놨으면 `git pull` 로 업데이트.

### 2) 환경변수 설정

```bash
cp .env.example .env
nano .env
```

채울 값:
- `ADMINS` — 관리자 계정 JSON 배열. 각 계정은 `user`/`pass`/`role`.
  - `role: "super"` = 모든 기능 + **작업 로그 열람**
  - `role: "admin"` = 관리 기능만 (로그 못 봄)
  - 예: `ADMINS=[{"user":"woonho","pass":"...","role":"super"},{"user":"teru","pass":"...","role":"admin"}]`
  - ⚠️ 비밀번호엔 `"` 와 `\` 는 쓰지 마세요(JSON 깨짐).
- `SESSION_SECRET` — 긴 랜덤 문자열 (`openssl rand -hex 32` 결과 붙여넣기)
- `RCON_PASSWORD` — 팰월드 `AdminPassword` 와 동일하게
- `RCON_HOST=palworld-server`, `RCON_PORT=25575` (그대로 두면 됨)

> 단일 관리자만 쓸 거면 `ADMINS` 대신 `PANEL_PASSWORD` 하나만 넣어도 됩니다(슈퍼관리자로 동작).

### 3) 팰월드 네트워크 이름 확인

```bash
docker network ls | grep palworld
```

보통 `palworld-server_default`. 다르면 `docker-compose.yml` 의
`networks.palworld.name` 을 그 이름으로 바꾸세요.

### 4) 실행

```bash
docker compose up -d --build
```

### 5) 접속 주소 확인 (임시 터널)

```bash
docker compose logs cloudflared | grep trycloudflare
```

→ `https://무작위이름.trycloudflare.com` 주소가 나옵니다. 그 주소로 접속해서
`PANEL_PASSWORD` 로 로그인하면 끝.

> ⚠️ 임시 터널 URL 은 컨테이너 재시작 때마다 **바뀝니다**. 고정 주소/내 도메인을 원하면 아래 참고.

---

## 고정 주소 / 내 도메인 쓰기 (명명 터널)

랜덤 URL 말고 `palworld.내도메인.com` 같은 고정 주소를 쓰려면:

1. **Cloudflare 계정** 생성 + 도메인 연결 (도메인은 Cloudflare Registrar 원가 ~연 $10, DNS·터널은 무료)
2. Cloudflare 대시보드 → **Zero Trust → Networks → Tunnels → Create a tunnel**
3. 터널 토큰을 복사해서 `.env` 의 `TUNNEL_TOKEN=` 에 붙여넣기
4. Public Hostname 에 `palworld.내도메인.com → http://palworld-admin:3000` 매핑
5. `docker-compose.yml` 의 cloudflared `command`/`environment` 를 명명 터널용(주석 참고)으로 전환
6. `docker compose up -d`

도메인 비용이 부담되면 임시 터널(랜덤 URL)로도 기능은 100% 동일하게 동작합니다.

---

## 운영 명령

```bash
docker compose ps                 # 상태
docker compose logs -f palworld-admin   # 패널 로그
docker compose logs -f cloudflared      # 터널 로그/주소
docker compose restart palworld-admin   # 패널만 재시작
docker compose down                # 패널 + 터널 종료(팰월드 서버는 별개라 안 꺼짐)
```

---

## 서버 유지관리 (슈퍼관리자 전용)

패치가 나와 "비호환 버전"으로 접속이 막힐 때, SSH 없이 패널에서 처리할 수 있습니다.

| 버튼 | 하는 일 | 걸리는 시간 |
|---|---|---|
| 🔄 서버 재가동 | 컨테이너 재시작 | 3~10분 |
| ⬆️ 버전 업데이트 | stop → start 로 스팀 업데이트 유도 | 3~15분 |
| 🔧 강제 재설치 | 게임 바이너리 삭제 후 5GB 전체 재다운로드 | 10~25분 |
| 📜 부팅 로그 | 최근 로그 확인(다운로드 진행률 등) | — |

- 현재 **게임 버전**과 컨테이너 상태가 카드 우측에 표시됩니다. 게임 클라이언트 버전과 비교해 보세요.
- 버전이 안 맞으면 **버전 업데이트** → 그래도 그대로면 **강제 재설치** 순서로 시도하세요.
- **강제 재설치도 월드·설정(`Pal/Saved`)은 그대로 둡니다.** 게임 바이너리만 새로 받습니다.
- 작업은 한 번에 하나만 실행되며, 진행 상황이 카드에 표시되고 모든 실행이 작업 로그에 남습니다.

> ⚠️ 이 기능은 `docker.sock` 을 패널 컨테이너에 마운트해서 동작합니다. 즉 패널이 호스트의 도커를
> 제어할 수 있다는 뜻이므로, **패널 비밀번호를 반드시 강하게** 유지하세요. 기능이 필요 없으면
> `docker-compose.yml` 의 `docker.sock` 줄을 지우면 됩니다(나머지 기능은 그대로 동작).

## 관리자 역할 & 작업 로그

- **슈퍼관리자(super)**: 모든 관리 기능 + **작업 로그 탭** 열람. 누가/언제/무엇을 했는지(로그인·공지·강퇴·밴·저장·재시작) 전부 기록됩니다.
- **일반관리자(admin)**: 관리 기능은 쓰되 로그 탭은 안 보입니다.
- 로그는 `./data/actions.log` 에 JSON Lines 로 영구 저장됩니다(컨테이너 재시작/재배포에도 유지).

## 보안 메모

- 패널은 인터넷에 공개되므로 **`PANEL_PASSWORD` 를 반드시 강하게** 설정하세요.
- RCON 포트(25575)는 **절대 오라클 Security List 에 공개로 열지 마세요.** 이 구조는 도커 내부망으로만 RCON 에 붙습니다.
- 세션 쿠키는 HTTPS(터널)에서 자동으로 secure 처리됩니다.

---

## 팰월드 RCON 한계 (알아두면 좋은 점)

- `Broadcast` 는 공백을 만나면 잘리는 버그가 있어 패널이 공백을 `_` 로 치환합니다. 한글은 깨질 수 있어요.
- `ShowPlayers` 의 SteamID 가 가끔 비거나 0 으로 오는 경우가 있습니다(게임 버그). 그때는 강퇴/밴이 안 될 수 있어요.
