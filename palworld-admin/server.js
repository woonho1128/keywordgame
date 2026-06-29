import express from "express";
import session from "express-session";
import net from "net";
import { fileURLToPath } from "url";
import path from "path";

const __dirname = path.dirname(fileURLToPath(import.meta.url));

const {
  PORT = 3000,
  PANEL_PASSWORD,
  SESSION_SECRET = "change-this-session-secret",
  RCON_HOST = "palworld-server",
  RCON_PORT = 25575,
  RCON_PASSWORD,
} = process.env;

if (!PANEL_PASSWORD) {
  console.error("[FATAL] PANEL_PASSWORD 환경변수가 필요합니다 (.env 확인)");
  process.exit(1);
}
if (!RCON_PASSWORD) {
  console.error("[FATAL] RCON_PASSWORD 환경변수가 필요합니다 (팰월드 AdminPassword)");
  process.exit(1);
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
function rcon(command) {
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
  const { password } = req.body || {};
  if (password && password === PANEL_PASSWORD) {
    req.session.authed = true;
    return res.json({ ok: true });
  }
  return res.status(401).json({ ok: false, error: "비밀번호가 틀렸습니다." });
});

app.post("/api/logout", (req, res) => {
  req.session.destroy(() => res.json({ ok: true }));
});

app.get("/api/session", (req, res) => {
  res.json({ authed: !!req.session.authed });
});

function requireAuth(req, res, next) {
  if (req.session.authed) return next();
  return res.status(401).json({ ok: false, error: "로그인이 필요합니다." });
}

// ---- 관리 API ----
app.get("/api/players", requireAuth, async (req, res) => {
  try {
    const raw = await rcon("ShowPlayers");
    res.json({ ok: true, players: parsePlayers(raw), raw });
  } catch (e) {
    res.status(500).json({ ok: false, error: rconErr(e) });
  }
});

app.get("/api/info", requireAuth, async (req, res) => {
  try {
    const raw = await rcon("Info");
    res.json({ ok: true, info: String(raw).trim() });
  } catch (e) {
    res.status(500).json({ ok: false, error: rconErr(e) });
  }
});

app.post("/api/broadcast", requireAuth, async (req, res) => {
  const message = String((req.body && req.body.message) || "").trim();
  if (!message) return res.status(400).json({ ok: false, error: "메시지를 입력하세요." });
  try {
    // 팰월드 Broadcast는 공백을 만나면 잘리는 버그가 있어 공백을 _ 로 치환
    const raw = await rcon(`Broadcast ${message.replace(/\s+/g, "_")}`);
    res.json({ ok: true, raw: String(raw).trim() });
  } catch (e) {
    res.status(500).json({ ok: false, error: rconErr(e) });
  }
});

app.post("/api/kick", requireAuth, async (req, res) => {
  const steamid = String((req.body && req.body.steamid) || "").trim();
  if (!steamid) return res.status(400).json({ ok: false, error: "steamid가 필요합니다." });
  try {
    const raw = await rcon(`KickPlayer ${steamid}`);
    res.json({ ok: true, raw: String(raw).trim() });
  } catch (e) {
    res.status(500).json({ ok: false, error: rconErr(e) });
  }
});

app.post("/api/ban", requireAuth, async (req, res) => {
  const steamid = String((req.body && req.body.steamid) || "").trim();
  if (!steamid) return res.status(400).json({ ok: false, error: "steamid가 필요합니다." });
  try {
    const raw = await rcon(`BanPlayer ${steamid}`);
    res.json({ ok: true, raw: String(raw).trim() });
  } catch (e) {
    res.status(500).json({ ok: false, error: rconErr(e) });
  }
});

app.post("/api/save", requireAuth, async (req, res) => {
  try {
    const raw = await rcon("Save");
    res.json({ ok: true, raw: String(raw).trim() });
  } catch (e) {
    res.status(500).json({ ok: false, error: rconErr(e) });
  }
});

app.post("/api/shutdown", requireAuth, async (req, res) => {
  const seconds = Number((req.body && req.body.seconds) || 30);
  const message = String((req.body && req.body.message) || "ServerShutdown").trim().replace(/\s+/g, "_");
  try {
    const raw = await rcon(`Shutdown ${seconds} ${message}`);
    res.json({ ok: true, raw: String(raw).trim() });
  } catch (e) {
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

app.use(express.static(path.join(__dirname, "public")));

app.listen(PORT, () => {
  console.log(`[palworld-admin] http://0.0.0.0:${PORT} (RCON → ${RCON_HOST}:${RCON_PORT})`);
});
