import express from "express";
import session from "express-session";
import net from "net";
import fs from "fs";
import { execFile } from "child_process";
import { fileURLToPath } from "url";
import path from "path";
import { FIELDS, readSettings, writeSettings, validate } from "./settings.js";

const __dirname = path.dirname(fileURLToPath(import.meta.url));

const {
  PORT = 3000,
  PANEL_PASSWORD,
  ADMINS,
  SESSION_SECRET = "change-this-session-secret",
  RCON_HOST = "palworld-server",
  RCON_PORT = 25575,
  RCON_PASSWORD,
  LOG_FILE = "/data/actions.log",
  PAL_CONTAINER = "palworld-server",
  PAL_CONFIG_DIR = "/palconfig",
} = process.env;

// ---- 관리자 계정 로드 ----
// ADMINS 는 JSON 배열: [{"user":"이름","pass":"비번","role":"super|admin"}]
// super = 모든 기능 + 작업 로그 열람 / admin = 관리 기능만(로그 못 봄)
let accounts = [];
if (ADMINS) {
  try {
    accounts = JSON.parse(ADMINS);
    if (!Array.isArray(accounts)) throw new Error("배열이 아님");
  } catch (e) {
    console.error("[FATAL] ADMINS 파싱 실패 — JSON 형식을 확인하세요:", e.message);
    process.exit(1);
  }
}
// 하위호환: ADMINS 없으면 PANEL_PASSWORD 를 단일 슈퍼관리자로
if (!accounts.length && PANEL_PASSWORD) {
  accounts = [{ user: "admin", pass: PANEL_PASSWORD, role: "super" }];
}
if (!accounts.length) {
  console.error("[FATAL] 관리자 계정이 없습니다 — .env 의 ADMINS 또는 PANEL_PASSWORD 를 설정하세요.");
  process.exit(1);
}
if (!RCON_PASSWORD) {
  console.error("[FATAL] RCON_PASSWORD 환경변수가 필요합니다 (팰월드 AdminPassword)");
  process.exit(1);
}

// ---- 작업 로그 (누가/언제/뭘 했는지, 파일에 영구 기록) ----
function logAction(user, action, target, result) {
  const entry = { time: new Date().toISOString(), user, action, target, result };
  fs.appendFile(LOG_FILE, JSON.stringify(entry) + "\n", (err) => {
    if (err) console.error("[log] 기록 실패:", err.message);
  });
}

const app = express();
app.set("trust proxy", 1); // Cloudflare Tunnel 뒤에서 동작
app.use(express.json());
app.use(
  session({
    secret: SESSION_SECRET,
    resave: false,
    saveUninitialized: false,
    cookie: {
      httpOnly: true,
      sameSite: "lax",
      secure: "auto", // HTTPS(Tunnel)에서는 자동으로 secure 쿠키
      maxAge: 1000 * 60 * 60 * 12, // 12시간
    },
  })
);

// ---- 팰월드 호환 최소 RCON 클라이언트 ----
// rcon-client 는 응답 종료를 감지하려고 "추가 빈 패킷"을 보내고 그 답을 기다리는데,
// 팰월드 RCON 은 그 답을 안 보내서 timeout 이 납니다(AUTH 는 되는데 send 가 멈춤).
// 그래서 표준 Source RCON 패킷을 직접 주고받되, 응답 패킷이 오면 짧은 유휴 후 종료합니다.
//
// 팰월드 RCON 은 동시 연결에 약해서(상태+접속자를 한꺼번에 부르면 한쪽이 실패),
// 모든 명령을 큐로 직렬화해 한 번에 하나씩만 처리한다.
let rconQueue = Promise.resolve();
function rcon(command) {
  const result = rconQueue.then(() => rconExec(command), () => rconExec(command));
  rconQueue = result.catch(() => {});
  return result;
}
function rconExec(command) {
  return new Promise((resolve, reject) => {
    const socket = net.connect({ host: RCON_HOST, port: Number(RCON_PORT) });
    socket.setNoDelay(true);

    const AUTH_ID = 1;
    const CMD_ID = 2;
    let stage = "auth";
    let buf = Buffer.alloc(0);
    let body = "";
    let got = false;
    let settled = false;
    let idle = null;

    const overall = setTimeout(() => finish(reject, new Error("RCON timeout")), 8000);

    function finish(fn, arg) {
      if (settled) return;
      settled = true;
      clearTimeout(overall);
      if (idle) clearTimeout(idle);
      socket.destroy();
      fn(arg);
    }
    const ok = () => finish(resolve, body);
    const fail = (e) => finish(reject, e);

    // Source RCON 패킷 만들기: [length][id][type][body\0][\0]
    function packet(id, type, str) {
      const b = Buffer.from(str, "utf8");
      const out = Buffer.alloc(14 + b.length);
      out.writeInt32LE(10 + b.length, 0); // length = id+type+body+2null
      out.writeInt32LE(id, 4);
      out.writeInt32LE(type, 8);
      b.copy(out, 12);
      return out; // 마지막 2바이트는 alloc 으로 이미 0
    }

    socket.on("connect", () => socket.write(packet(AUTH_ID, 3, RCON_PASSWORD))); // SERVERDATA_AUTH
    socket.on("error", fail);
    socket.on("end", () => { if (got) ok(); });

    socket.on("data", (chunk) => {
      buf = Buffer.concat([buf, chunk]);
      while (buf.length >= 4) {
        const size = buf.readInt32LE(0);
        if (buf.length < 4 + size) break; // 패킷이 덜 도착
        const id = buf.readInt32LE(4);
        const type = buf.readInt32LE(8);
        const payload = buf.subarray(12, 4 + size - 2).toString("utf8");
        buf = buf.subarray(4 + size);

        if (stage === "auth") {
          if (type === 2) { // SERVERDATA_AUTH_RESPONSE
            if (id === -1) return fail(new Error("Authentication failed"));
            stage = "command";
            socket.write(packet(CMD_ID, 2, command)); // SERVERDATA_EXECCOMMAND
          }
          // auth 단계의 다른 패킷(빈 RESPONSE_VALUE 등)은 무시
        } else if (type === 0) { // SERVERDATA_RESPONSE_VALUE
          body += payload;
          got = true;
          if (idle) clearTimeout(idle);
          idle = setTimeout(ok, 250); // 응답이 여러 패킷이면 마지막 후 250ms 뒤 종료
        }
      }
    });
  });
}

// RCON 재시도 래퍼: 팰월드 RCON 이 FEX 부하로 간헐적으로 느려/끊길 때 대비.
// 조회(Info/ShowPlayers)처럼 여러 번 해도 안전한 명령에만 사용.
async function rconRetry(command, tries = 2) {
  let lastErr;
  for (let i = 0; i < tries; i++) {
    try {
      return await rcon(command);
    } catch (e) {
      lastErr = e;
      if (i < tries - 1) await new Promise((r) => setTimeout(r, 400));
    }
  }
  throw lastErr;
}

// 조회 명령 캐시 + 요청 합치기.
// RCON 은 큐로 직렬 처리되는데, 여러 브라우저 탭이 15초마다 Info/ShowPlayers 를 부르면
// 큐에 요청이 쌓여 응답이 계속 밀린다. 같은 명령이 이미 진행 중이면 그 결과를 함께 쓰고,
// 방금 받은 결과는 잠깐 캐시해서 큐가 넘치지 않게 한다.
const readCache = new Map(); // command -> { at, value }
const failCache = new Map(); // command -> { at, err }
const inFlight = new Map(); // command -> Promise
const READ_TTL = 5000;
const FAIL_TTL = 30000; // 실패한 명령은 30초 동안 재시도하지 않음

function rconRead(command) {
  const cached = readCache.get(command);
  if (cached && Date.now() - cached.at < READ_TTL) return Promise.resolve(cached.value);

  // 응답하지 않는 명령(예: 1.0.3 의 Info)을 매번 8초씩 기다리면 큐가 낭비된다
  const failed = failCache.get(command);
  if (failed && Date.now() - failed.at < FAIL_TTL) return Promise.reject(failed.err);

  const running = inFlight.get(command);
  if (running) return running; // 이미 같은 조회가 진행 중 → 결과 공유

  const p = rconRetry(command)
    .then((value) => {
      readCache.set(command, { at: Date.now(), value });
      failCache.delete(command);
      return value;
    })
    .catch((err) => {
      failCache.set(command, { at: Date.now(), err });
      throw err;
    })
    .finally(() => inFlight.delete(command));

  inFlight.set(command, p);
  return p;
}

// ShowPlayers 응답(CSV)을 파싱: 첫 줄은 헤더(name,playeruid,steamid)
function parsePlayers(raw) {
  const lines = String(raw || "").trim().split(/\r?\n/).filter(Boolean);
  if (lines.length <= 1) return [];
  const players = [];
  for (let i = 1; i < lines.length; i++) {
    const parts = lines[i].split(",");
    const name = (parts[0] || "").trim();
    if (!name) continue;
    players.push({
      name,
      playeruid: (parts[1] || "").trim(),
      steamid: (parts[2] || "").trim(),
    });
  }
  return players;
}

// ---- 인증 ----
app.post("/api/login", (req, res) => {
  const { username, password } = req.body || {};
  const acc = accounts.find((a) => a.user === username && a.pass === password);
  if (acc) {
    req.session.user = acc.user;
    req.session.role = acc.role === "super" ? "super" : "admin";
    logAction(acc.user, "로그인", "", "성공");
    return res.json({ ok: true, user: acc.user, role: req.session.role });
  }
  logAction(String(username || "?"), "로그인", "", "실패");
  return res.status(401).json({ ok: false, error: "아이디 또는 비밀번호가 틀렸습니다." });
});

app.post("/api/logout", (req, res) => {
  req.session.destroy(() => res.json({ ok: true }));
});

app.get("/api/session", (req, res) => {
  res.json({ authed: !!req.session.user, user: req.session.user || null, role: req.session.role || null });
});

function requireAuth(req, res, next) {
  if (req.session.user) return next();
  return res.status(401).json({ ok: false, error: "로그인이 필요합니다." });
}

function requireSuper(req, res, next) {
  if (req.session.user && req.session.role === "super") return next();
  return res.status(403).json({ ok: false, error: "슈퍼관리자만 접근할 수 있습니다." });
}

// 작업 로그 조회 (슈퍼관리자 전용)
app.get("/api/logs", requireSuper, (req, res) => {
  fs.readFile(LOG_FILE, "utf8", (err, data) => {
    if (err) return res.json({ ok: true, logs: [] });
    const logs = data
      .trim()
      .split("\n")
      .filter(Boolean)
      .map((l) => { try { return JSON.parse(l); } catch { return null; } })
      .filter(Boolean)
      .slice(-300)
      .reverse();
    res.json({ ok: true, logs });
  });
});

// ---- 관리 API ----
app.get("/api/players", requireAuth, async (req, res) => {
  try {
    const raw = await rconRead("ShowPlayers");
    res.json({ ok: true, players: parsePlayers(raw), raw });
  } catch (e) {
    res.status(500).json({ ok: false, error: rconErr(e) });
  }
});

app.get("/api/info", requireAuth, async (req, res) => {
  try {
    const raw = await rconRead("Info");
    const info = String(raw).trim();
    if (info) return res.json({ ok: true, info });
    throw new Error("빈 응답");
  } catch (e) {
    // 팰월드 1.0.3 부터 Info 가 RCON 응답을 주지 않는 경우가 있다.
    // ShowPlayers 가 되면 서버는 분명히 살아있으므로 그걸로 온라인 판정한다.
    try {
      await rconRead("ShowPlayers");
      return res.json({ ok: true, info: "온라인" });
    } catch (e2) {
      return res.status(500).json({ ok: false, error: rconErr(e2) });
    }
  }
});

app.post("/api/broadcast", requireAuth, async (req, res) => {
  const message = String((req.body && req.body.message) || "").trim();
  if (!message) return res.status(400).json({ ok: false, error: "메시지를 입력하세요." });
  try {
    // 팰월드 Broadcast는 공백을 만나면 잘리는 버그가 있어 공백을 _ 로 치환
    const raw = await rcon(`Broadcast ${message.replace(/\s+/g, "_")}`);
    logAction(req.session.user, "공지", message, "성공");
    res.json({ ok: true, raw: String(raw).trim() });
  } catch (e) {
    logAction(req.session.user, "공지", message, "실패: " + rconErr(e));
    res.status(500).json({ ok: false, error: rconErr(e) });
  }
});

app.post("/api/kick", requireAuth, async (req, res) => {
  const steamid = String((req.body && req.body.steamid) || "").trim();
  const name = String((req.body && req.body.name) || "").trim();
  if (!steamid) return res.status(400).json({ ok: false, error: "steamid가 필요합니다." });
  try {
    const raw = await rcon(`KickPlayer ${steamid}`);
    logAction(req.session.user, "강퇴", `${name || "?"}(${steamid})`, "성공");
    res.json({ ok: true, raw: String(raw).trim() });
  } catch (e) {
    logAction(req.session.user, "강퇴", `${name || "?"}(${steamid})`, "실패: " + rconErr(e));
    res.status(500).json({ ok: false, error: rconErr(e) });
  }
});

app.post("/api/ban", requireAuth, async (req, res) => {
  const steamid = String((req.body && req.body.steamid) || "").trim();
  const name = String((req.body && req.body.name) || "").trim();
  if (!steamid) return res.status(400).json({ ok: false, error: "steamid가 필요합니다." });
  try {
    const raw = await rcon(`BanPlayer ${steamid}`);
    logAction(req.session.user, "밴", `${name || "?"}(${steamid})`, "성공");
    res.json({ ok: true, raw: String(raw).trim() });
  } catch (e) {
    logAction(req.session.user, "밴", `${name || "?"}(${steamid})`, "실패: " + rconErr(e));
    res.status(500).json({ ok: false, error: rconErr(e) });
  }
});

app.post("/api/save", requireAuth, async (req, res) => {
  try {
    const raw = await rcon("Save");
    logAction(req.session.user, "수동 저장", "", "성공");
    res.json({ ok: true, raw: String(raw).trim() });
  } catch (e) {
    logAction(req.session.user, "수동 저장", "", "실패: " + rconErr(e));
    res.status(500).json({ ok: false, error: rconErr(e) });
  }
});

app.post("/api/shutdown", requireAuth, async (req, res) => {
  const seconds = Number((req.body && req.body.seconds) || 30);
  const message = String((req.body && req.body.message) || "ServerShutdown").trim().replace(/\s+/g, "_");
  try {
    const raw = await rcon(`Shutdown ${seconds} ${message}`);
    logAction(req.session.user, "안전 재시작", `${seconds}초 후`, "성공");
    res.json({ ok: true, raw: String(raw).trim() });
  } catch (e) {
    logAction(req.session.user, "안전 재시작", `${seconds}초 후`, "실패: " + rconErr(e));
    res.status(500).json({ ok: false, error: rconErr(e) });
  }
});

function rconErr(e) {
  const msg = (e && e.message) || String(e);
  if (/auth/i.test(msg)) return "RCON 인증 실패 — AdminPassword(RCON_PASSWORD)를 확인하세요.";
  if (/ENOTFOUND|getaddrinfo/i.test(msg))
    return "RCON 호스트를 찾을 수 없습니다 — RCON_HOST(컨테이너 이름)와 도커 네트워크 연결을 확인하세요.";
  if (/ECONN|timeout|EHOSTUNREACH|ECONNREFUSED/i.test(msg))
    return "RCON 서버에 연결할 수 없습니다 — 팰월드 서버가 켜져 있고 RCONEnabled=True 인지 확인하세요.";
  return msg;
}

// ============ 서버 유지관리 (Docker 제어) — 슈퍼관리자 전용 ============
// docker CLI 를 고정 인자로만 호출한다(셸을 거치지 않으므로 명령 주입 불가).
function docker(args, timeoutMs = 30000) {
  return new Promise((resolve, reject) => {
    execFile("docker", args, { timeout: timeoutMs, maxBuffer: 4 * 1024 * 1024 }, (err, stdout, stderr) => {
      if (err) return reject(new Error((stderr || err.message || "").trim().slice(0, 500)));
      resolve(String(stdout || "").trim());
    });
  });
}

// 진행 중인 유지관리 작업 상태 (한 번에 하나만)
let maint = { running: false, task: null, startedAt: null, user: null, done: false, ok: null, message: "" };

function maintStart(task, user) {
  maint = { running: true, task, startedAt: Date.now(), user, done: false, ok: null, message: "시작됨" };
}
function maintEnd(ok, message) {
  maint = { ...maint, running: false, done: true, ok, message };
}

// 팰월드 컨테이너 로그에서 현재 게임 버전 읽기
async function readGameVersion() {
  try {
    const out = await docker(["logs", "--tail", "800", PAL_CONTAINER]);
    const matches = String(out).match(/Game version is (v[\d.]+)/g);
    return matches && matches.length ? matches[matches.length - 1].replace("Game version is ", "") : null;
  } catch {
    return null;
  }
}

// 컨테이너 상태 + 버전
app.get("/api/server-status", requireAuth, async (req, res) => {
  try {
    const state = await docker(["inspect", "-f", "{{.State.Status}}|{{.State.StartedAt}}", PAL_CONTAINER]);
    const [status, startedAt] = state.split("|");
    const version = await readGameVersion();
    res.json({ ok: true, status, startedAt, version, maint });
  } catch (e) {
    res.json({ ok: false, error: e.message, maint });
  }
});

// 유지관리 작업 진행 상태(폴링용)
app.get("/api/maintenance", requireSuper, (req, res) => res.json({ ok: true, maint }));

// 최근 부팅 로그 (다운로드 진행률 확인용)
app.get("/api/server-logs", requireSuper, async (req, res) => {
  try {
    // 판정에는 넓은 범위를 본다. RCON 로그가 15초마다 쌓여서 부팅 완료 표시가
    // 금방 밀려나기 때문에, 짧게 보면 가동 중인 서버를 "부팅 중"으로 오판한다.
    const out = await docker(["logs", "--tail", "2000", PAL_CONTAINER]);
    const all = String(out)
      .replace(/[^\x20-\x7E\n]/g, "")
      .split("\n")
      .map((l) => l.trim())
      .filter(Boolean);

    // 화면에는 RCON 폴링 잡음을 걷어낸 의미 있는 줄을 우선 보여준다
    const isNoise = (l) => /RCON executed the command/i.test(l);
    const meaningful = all.filter((l) => !isNoise(l));
    const lines = (meaningful.length ? meaningful : all).slice(-30);

    // 진행 상황 요약: 다운로드 퍼센트 / 준비 완료 여부 / 현재 버전
    let phase;
    let percent = null;
    const dl = [...all].reverse().find((l) => /progress:\s*([\d.]+)/.test(l));
    const ready = [...all].reverse().find((l) => /Running Palworld dedicated server/.test(l));
    const verLine = [...all].reverse().find((l) => /Game version is/.test(l));
    const readyIdx = ready ? all.lastIndexOf(ready) : -1;
    const dlIdx = dl ? all.lastIndexOf(dl) : -1;
    // RCON 이 최근에 응답했다면 서버는 확실히 가동 중 (부팅 완료 표시가 밀려났어도 판정 가능)
    const rconAlive = all.slice(-40).some(isNoise);

    if (dl && dlIdx > readyIdx) {
      percent = parseFloat(dl.match(/progress:\s*([\d.]+)/)[1]);
      phase = /verifying/i.test(dl) ? "검증 중" : "다운로드 중";
    } else if (readyIdx >= 0 || rconAlive) {
      phase = "가동 중";
    } else {
      phase = "부팅 중";
    }

    res.json({
      ok: true,
      lines,
      phase,
      percent,
      version: verLine ? (verLine.match(/Game version is (v[\d.]+)/) || [])[1] || null : null,
    });
  } catch (e) {
    res.status(500).json({ ok: false, error: e.message });
  }
});

// 유지관리 작업 실행 (재가동 / 업데이트 / 강제 재설치)
app.post("/api/maintenance/:task", requireSuper, async (req, res) => {
  const task = req.params.task;
  if (!["restart", "update", "reinstall"].includes(task)) {
    return res.status(400).json({ ok: false, error: "알 수 없는 작업입니다." });
  }
  if (maint.running) {
    return res.status(409).json({ ok: false, error: `이미 '${maint.task}' 작업이 진행 중입니다.` });
  }
  // 강제 재설치는 오타 방지를 위해 확인 문구를 요구
  if (task === "reinstall" && (req.body || {}).confirm !== "REINSTALL") {
    return res.status(400).json({ ok: false, error: "확인 문구가 필요합니다." });
  }

  const label = { restart: "서버 재가동", update: "버전 업데이트", reinstall: "강제 재설치" }[task];
  maintStart(label, req.session.user);
  logAction(req.session.user, label, "", "시작");
  res.json({ ok: true, started: label }); // 즉시 응답하고 백그라운드로 진행

  (async () => {
    try {
      if (task === "restart") {
        await docker(["restart", PAL_CONTAINER], 120000);
      } else {
        // update/reinstall: 컨테이너를 멈추고 필요 시 게임 바이너리를 지운 뒤 다시 시작
        await docker(["stop", PAL_CONTAINER], 120000);
        if (task === "reinstall") {
          // 세이브(Pal/Saved)는 건드리지 않고 게임 바이너리만 제거 → 스팀이 전체를 새로 받음
          await docker(
            ["run", "--rm", "--volumes-from", PAL_CONTAINER, "alpine",
             "sh", "-c", "rm -rf /palworld/steamapps /palworld/Pal/Binaries /palworld/Pal/Content"],
            600000
          );
        }
        await docker(["start", PAL_CONTAINER], 120000);
      }
      maintEnd(true, `${label} 완료 — 서버가 켜지는 중입니다(3~20분).`);
      logAction(maint.user, label, "", "성공");
    } catch (e) {
      maintEnd(false, `${label} 실패: ${e.message}`);
      logAction(maint.user, label, "", "실패: " + e.message);
    }
  })();
});

// ============ 서버 설정 편집 (PalWorldSettings.ini) — 슈퍼관리자 전용 ============
app.get("/api/settings", requireSuper, (req, res) => {
  try {
    const { values } = readSettings(PAL_CONFIG_DIR);
    res.json({ ok: true, fields: FIELDS, values });
  } catch (e) {
    res.status(500).json({ ok: false, error: e.message, fields: FIELDS, values: {} });
  }
});

app.post("/api/settings", requireSuper, async (req, res) => {
  const { updates, restart } = req.body || {};
  const { clean, errors } = validate(updates);
  if (errors.length) return res.status(400).json({ ok: false, error: errors.join("\n") });
  if (!Object.keys(clean).length) return res.status(400).json({ ok: false, error: "변경할 설정이 없습니다." });

  try {
    // 설정 파일은 서버가 켜질 때만 읽으므로, 재시작 전에 파일부터 안전하게 쓴다
    writeSettings(PAL_CONFIG_DIR, clean);
    const changed = Object.keys(clean).join(", ");
    logAction(req.session.user, "설정 변경", changed, "성공");

    if (!restart) return res.json({ ok: true, changed: Object.keys(clean).length, restarted: false });

    if (maint.running) {
      return res.json({
        ok: true, changed: Object.keys(clean).length, restarted: false,
        note: `다른 작업(${maint.task})이 진행 중이라 재가동은 건너뛰었습니다. 나중에 재가동하면 적용됩니다.`,
      });
    }
    maintStart("설정 적용 재가동", req.session.user);
    logAction(req.session.user, "설정 적용 재가동", "", "시작");
    res.json({ ok: true, changed: Object.keys(clean).length, restarted: true });

    docker(["restart", PAL_CONTAINER], 120000)
      .then(() => {
        maintEnd(true, "설정을 적용하고 재가동했습니다 — 서버가 켜지는 중입니다(3~10분).");
        logAction(maint.user, "설정 적용 재가동", "", "성공");
      })
      .catch((e) => {
        maintEnd(false, "재가동 실패: " + e.message);
        logAction(maint.user, "설정 적용 재가동", "", "실패: " + e.message);
      });
  } catch (e) {
    logAction(req.session.user, "설정 변경", "", "실패: " + e.message);
    if (!res.headersSent) res.status(500).json({ ok: false, error: e.message });
  }
});

app.use(express.static(path.join(__dirname, "public")));

app.listen(PORT, () => {
  console.log(`[palworld-admin] http://0.0.0.0:${PORT} (RCON → ${RCON_HOST}:${RCON_PORT})`);
});
