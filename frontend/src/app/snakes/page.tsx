'use client';

import Link from 'next/link';
import { useCallback, useEffect, useRef, useState } from 'react';
import { api } from '@/lib/api';
import RoomChat from '@/components/RoomChat';

type PlayerView = { seat: number; name: string; bot: boolean; host: boolean; me: boolean; left: boolean; pos: number; rolls: number };
type JumpView = { from: number; to: number };
type LastMove = { seat: number; die: number; from: number; landed: number; to: number; how: string };
type State = {
  phase: string; cols: number; size: number; boardSeed: number; turnSec: number; noTimeLimit: boolean;
  isHost: boolean; joined: boolean; players: PlayerView[];
  ladders: JumpView[]; snakes: JumpView[];
  turnSeat: number; turnName: string | null; nextSeat: number; myTurn: boolean; mySeat: number;
  lastDie: number; lastMove: LastMove | null; moveSeq: number;
  lastAction: string | null; log: string[]; winnerSeat: number; winnerLabel: string | null;
  deadline: number; serverNow: number;
};

/** 연출 타이밍(ms). 서버의 봇 대기시간(2800)이 이 합보다 길어야 연출이 겹치지 않는다. */
const DICE_SPIN_MS = 700, STEP_MS = 160, JUMP_MS = 700, JUMP_PAUSE_MS = 260;
type Room = { code: string; status: string; playerCount: number; host: string };

function cid(): string {
  if (typeof window === 'undefined') return '';
  let id = localStorage.getItem('snakes_client_id');
  if (!id) { id = Math.random().toString(36).slice(2) + Date.now().toString(36); localStorage.setItem('snakes_client_id', id); }
  return id;
}

const SEAT_COLORS = ['#f43f5e', '#3b82f6', '#10b981', '#f59e0b', '#8b5cf6', '#ec4899', '#14b8a6', '#f97316', '#6366f1', '#84cc16'];
/** 칸 배경 — 체크무늬처럼 두 색을 번갈아 쓴다. */
const CELL_A = '#fef9e7', CELL_B = '#e8f5e9';

/** 주사위 눈 1~6. rolling이면 흔들린다. */
function DiceFace({ v, size = 44, rolling = false }: { v: number; size?: number; rolling?: boolean }) {
  const pips: Record<number, [number, number][]> = {
    1: [[50, 50]],
    2: [[28, 28], [72, 72]],
    3: [[26, 26], [50, 50], [74, 74]],
    4: [[28, 28], [72, 28], [28, 72], [72, 72]],
    5: [[26, 26], [74, 26], [50, 50], [26, 74], [74, 74]],
    6: [[28, 22], [72, 22], [28, 50], [72, 50], [28, 78], [72, 78]],
  };
  return (
    <span className="relative inline-block shrink-0 rounded-2xl border-2 border-slate-300 bg-white shadow-[inset_0_-3px_0_rgba(0,0,0,0.08),0_2px_6px_rgba(0,0,0,0.15)] align-middle"
      style={{ width: size, height: size, animation: rolling ? 'sn-shake .22s linear infinite' : undefined }}>
      {v <= 0 ? (
        <span className="absolute inset-0 flex items-center justify-center font-extrabold text-slate-300" style={{ fontSize: size * 0.5 }}>?</span>
      ) : (
        (pips[v] || []).map(([x, y], i) => (
          <span key={i} className="absolute rounded-full bg-slate-800"
            style={{ width: size * 0.16, height: size * 0.16, left: `${x}%`, top: `${y}%`, transform: 'translate(-50%,-50%)' }} />
        ))
      )}
    </span>
  );
}

export default function SnakesPage() {
  const id = useRef('');
  const [nick, setNick] = useState('');
  const [screen, setScreen] = useState<'entry' | 'lobby' | 'game'>('entry');
  const [roomCode, setRoomCode] = useState<string | null>(null);
  const [turnSec, setTurnSec] = useState(20);
  const [joinCode, setJoinCode] = useState('');
  const [rooms, setRooms] = useState<Room[]>([]);
  const [ss, setSs] = useState<State | null>(null);
  const roomRef = useRef<string | null>(null); roomRef.current = roomCode;
  const syncRef = useRef({ serverNow: 0, at: 0 });
  const [, forceTick] = useState(0);

  /*
   * 이동 연출.
   *
   * 서버는 결과만 준다(lastMove: 출발칸 → 밟은칸 → 최종칸). 화면에서 주사위를 굴리고
   * 말을 한 칸씩 옮기는 건 전부 여기서 한다. 연출 중인 말은 칸에서 숨기고 보드 위에
   * 뜬 토큰으로 그려서, CSS transition으로 실제로 움직이는 것처럼 보이게 한다.
   *
   * 이동이 몰려 들어와도 겹치지 않게 큐에 쌓아 하나씩 재생한다.
   */
  const [anim, setAnim] = useState<{ seat: number; cell: number; kind: 'step' | 'jump'; how: string } | null>(null);
  const [spinFace, setSpinFace] = useState(0);          // 굴리는 중에 보여줄 임시 눈
  const queue = useRef<LastMove[]>([]);
  const playing = useRef(false);
  const seenSeq = useRef(0);
  const aliveRef = useRef(true);
  useEffect(() => () => { aliveRef.current = false; }, []);

  const sleep = (ms: number) => new Promise((r) => setTimeout(r, ms));

  const playQueue = useCallback(async () => {
    if (playing.current) return;
    playing.current = true;
    while (queue.current.length && aliveRef.current) {
      const m = queue.current.shift()!;

      // 1) 주사위가 구르는 동안 눈이 계속 바뀐다.
      const spin = setInterval(() => setSpinFace(1 + Math.floor(Math.random() * 6)), 80);
      await sleep(DICE_SPIN_MS);
      clearInterval(spin);
      setSpinFace(0);
      if (!aliveRef.current) break;

      // 2) 출발칸에서 밟은 칸까지 한 칸씩.
      if (m.landed !== m.from) {
        const dir = m.landed > m.from ? 1 : -1;
        for (let c = m.from + dir; ; c += dir) {
          if (!aliveRef.current) break;
          setAnim({ seat: m.seat, cell: c, kind: 'step', how: m.how });
          await sleep(STEP_MS);
          if (c === m.landed) break;
        }
      }

      // 3) 뱀·사다리면 미끄러진다.
      if (m.to !== m.landed && aliveRef.current) {
        await sleep(JUMP_PAUSE_MS);
        setAnim({ seat: m.seat, cell: m.to, kind: 'jump', how: m.how });
        await sleep(JUMP_MS);
      }
      setAnim(null);
    }
    playing.current = false;
  }, []);

  useEffect(() => { id.current = cid(); try { setNick(localStorage.getItem('arcade_nick') || ''); } catch {} }, []);

  /** 서버 상태를 반영하고, 못 본 이동이 있으면 연출 큐에 넣는다. */
  const applyState = useCallback((s: State) => {
    setSs(s);
    if (s.lastMove && s.moveSeq > seenSeq.current) {
      seenSeq.current = s.moveSeq;
      queue.current.push(s.lastMove);
      playQueue();
    }
    // 새 판이 시작되면(서버가 0으로 리셋) 기준도 되돌린다.
    if (s.moveSeq === 0) { seenSeq.current = 0; queue.current.length = 0; }
  }, [playQueue]);

  const loadRooms = useCallback(async () => { try { setRooms(await api(`/api/v1/snakes/rooms`)); } catch {} }, []);
  useEffect(() => { if (screen === 'entry') { loadRooms(); const t = setInterval(loadRooms, 3000); return () => clearInterval(t); } }, [screen, loadRooms]);

  useEffect(() => {
    if (screen === 'entry' || !roomCode) return;
    let alive = true;
    const poll = async () => {
      try {
        const s = await api<State>(`/api/v1/snakes/me?roomCode=${roomCode}&clientId=${id.current}`);
        if (!alive) return;
        syncRef.current = { serverNow: s.serverNow, at: Date.now() };
        applyState(s);
        if (s.phase !== 'LOBBY' && screen === 'lobby') setScreen('game');
        if (s.phase === 'LOBBY' && screen === 'game') setScreen('lobby');
      } catch {}
    };
    const t = setInterval(poll, 1000); poll();
    return () => { alive = false; clearInterval(t); };
  }, [screen, roomCode, applyState]);

  useEffect(() => {
    if (screen !== 'game') return;
    const t = setInterval(() => forceTick((x) => x + 1), 250);
    return () => clearInterval(t);
  }, [screen]);

  const create = async () => {
    const n = nick.trim(); if (!n) return; try { localStorage.setItem('arcade_nick', n); } catch {}
    try {
      const res = await api<{ roomCode: string; state: State }>(`/api/v1/snakes/new?clientId=${id.current}`, { method: 'POST', body: JSON.stringify({ nick: n, turnSec }) });
      setRoomCode(res.roomCode); setSs(res.state); setScreen('lobby');
    } catch (e: any) { alert(e?.message || '방 생성 실패'); }
  };
  const join = async (code: string, nickOverride?: string) => {
    const n = (nickOverride ?? nick).trim(); if (!n || !code) return; setNick(n); try { localStorage.setItem('arcade_nick', n); } catch {}
    try {
      const s = await api<State>(`/api/v1/snakes/join?roomCode=${code}&clientId=${id.current}`, { method: 'POST', body: JSON.stringify({ nick: n }) });
      setRoomCode(code.toUpperCase()); setSs(s); setScreen(s.phase === 'LOBBY' ? 'lobby' : 'game');
    } catch (e: any) { alert(e?.message || '참가 실패'); }
  };

  useEffect(() => {
    const j = new URLSearchParams(window.location.search).get('join');
    if (!j) return;
    let n = ''; try { n = (localStorage.getItem('arcade_nick') || '').trim(); } catch {}
    if (!n) return;
    id.current = id.current || cid();
    join(j.toUpperCase(), n);
    try { window.history.replaceState({}, '', '/snakes'); } catch {}
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, []);

  const addBot = async () => { try { setSs(await api(`/api/v1/snakes/add-bot?roomCode=${roomCode}&clientId=${id.current}`, { method: 'POST', body: '{}' })); } catch (e: any) { alert(e?.message); } };
  const startMatch = async () => { try { setSs(await api(`/api/v1/snakes/start?roomCode=${roomCode}&clientId=${id.current}`, { method: 'POST', body: '{}' })); setScreen('game'); } catch (e: any) { alert(e?.message); } };
  const roll = async () => { try { applyState(await api(`/api/v1/snakes/roll?roomCode=${roomCode}&clientId=${id.current}`, { method: 'POST', body: '{}' })); } catch (e: any) { alert(e?.message); } };
  const leave = async () => { const rc = roomRef.current; if (rc) { try { await api(`/api/v1/snakes/leave?roomCode=${rc}&clientId=${id.current}`, { method: 'POST', body: '{}' }); } catch {} } setScreen('entry'); setRoomCode(null); setSs(null); };

  const beaconLeave = () => {
    const rc = roomRef.current;
    if (!rc || !id.current) return;
    try { navigator.sendBeacon(`/api/v1/snakes/leave?roomCode=${rc}&clientId=${encodeURIComponent(id.current)}`); } catch {}
  };
  const home = <Link href="/" aria-label="홈으로" onClick={beaconLeave} className="text-xl leading-none text-slate-500 hover:text-slate-800">🏠</Link>;
  const ended = ss?.phase === 'ENDED';

  const localNow = syncRef.current.at ? syncRef.current.serverNow + (Date.now() - syncRef.current.at) : (ss?.serverNow ?? 0);
  const remainSec = ss && ss.deadline > 0 ? Math.ceil(Math.max(0, ss.deadline - localNow) / 1000) : 0;

  // 연출이 도는 동안에는 굴리기를 막고 승리 배너도 미룬다.
  const busyAnim = spinFace > 0 || anim !== null;

  const active = ss ? ss.players.filter((p) => !p.left) : [];
  const bySeat = [...active].sort((a, b) => a.seat - b.seat);
  const ranked = [...active].sort((a, b) => b.pos - a.pos);
  const rankOf = (seat: number) => ranked.findIndex((p) => p.seat === seat) + 1;
  const seatName = (s: number) => active.find((p) => p.seat === s)?.name ?? '?';

  /*
   * 보드 좌표 계산.
   * 뱀 모양(보아스트로피돈) 배치 — 1번이 왼쪽 아래, 홀수 줄은 오른쪽으로,
   * 짝수 줄은 왼쪽으로 번호가 붙는다. 화면 위쪽이 마지막 줄이다.
   */
  const cols = ss?.cols ?? 10;
  const cellOf = (sq: number) => {
    const i = sq - 1;
    const row = Math.floor(i / cols);            // 0 = 맨 아래 줄
    const inRow = i % cols;
    const col = row % 2 === 0 ? inRow : cols - 1 - inRow;
    return { row, col };
  };
  /** SVG 오버레이용 백분율 좌표(칸 중앙). */
  const centerOf = (sq: number) => {
    const { row, col } = cellOf(sq);
    return { x: ((col + 0.5) / cols) * 100, y: ((cols - 1 - row + 0.5) / cols) * 100 };
  };

  const boardCells = () => {
    const size = ss?.size ?? cols * cols;
    const out: number[] = [];
    // 위 줄부터 그린다: 마지막 줄이 화면 맨 위.
    for (let row = cols - 1; row >= 0; row--) {
      for (let c = 0; c < cols; c++) {
        const inRow = row % 2 === 0 ? c : cols - 1 - c;
        out.push(row * cols + inRow + 1);
      }
    }
    return out.filter((n) => n <= size);
  };

  const jumpFrom = (sq: number) => {
    if (!ss) return null;
    const l = ss.ladders.find((j) => j.from === sq);
    if (l) return { kind: 'L' as const, to: l.to };
    const s = ss.snakes.find((j) => j.from === sq);
    if (s) return { kind: 'S' as const, to: s.to };
    return null;
  };
  const jumpTo = (sq: number) => {
    if (!ss) return null;
    if (ss.ladders.some((j) => j.to === sq)) return 'L' as const;
    if (ss.snakes.some((j) => j.to === sq)) return 'S' as const;
    return null;
  };

  /** 뱀: 구불구불한 곡선. 사다리: 두 세로줄 + 가로대. */
  function BoardOverlay() {
    if (!ss || ss.phase === 'LOBBY') return null;
    return (
      <svg viewBox="0 0 100 100" preserveAspectRatio="none" className="absolute inset-0 w-full h-full pointer-events-none">
        {ss.ladders.map((l, i) => {
          const a = centerOf(l.from), b = centerOf(l.to);
          const dx = b.x - a.x, dy = b.y - a.y;
          const len = Math.hypot(dx, dy) || 1;
          const nx = (-dy / len) * 1.6, ny = (dx / len) * 1.6;   // 레일 간격
          const rungs = Math.max(2, Math.round(len / 4));
          return (
            <g key={`l${i}`} stroke="#b45309" strokeWidth="0.7" strokeLinecap="round" opacity="0.95">
              <line x1={a.x + nx} y1={a.y + ny} x2={b.x + nx} y2={b.y + ny} />
              <line x1={a.x - nx} y1={a.y - ny} x2={b.x - nx} y2={b.y - ny} />
              {Array.from({ length: rungs }).map((_, k) => {
                const t = (k + 0.5) / rungs;
                const px = a.x + dx * t, py = a.y + dy * t;
                return <line key={k} x1={px + nx} y1={py + ny} x2={px - nx} y2={py - ny} strokeWidth="0.5" />;
              })}
            </g>
          );
        })}
        {ss.snakes.map((s, i) => {
          const a = centerOf(s.from), b = centerOf(s.to);   // a=머리(위), b=꼬리(아래)
          const mx = (a.x + b.x) / 2, my = (a.y + b.y) / 2;
          const dx = b.x - a.x, dy = b.y - a.y;
          const len = Math.hypot(dx, dy) || 1;
          const wob = 9;                                    // 구불거림 정도
          const c1x = mx + (-dy / len) * wob, c1y = my + (dx / len) * wob;
          const c2x = mx - (-dy / len) * wob, c2y = my - (dx / len) * wob;
          const hue = (i * 67) % 360;
          return (
            <g key={`s${i}`}>
              <path d={`M ${a.x} ${a.y} C ${c1x} ${c1y}, ${c2x} ${c2y}, ${b.x} ${b.y}`}
                fill="none" stroke={`hsl(${hue} 65% 42%)`} strokeWidth="2.2" strokeLinecap="round" opacity="0.92" />
              <circle cx={a.x} cy={a.y} r="2.1" fill={`hsl(${hue} 65% 34%)`} />
              <circle cx={a.x - 0.7} cy={a.y - 0.5} r="0.45" fill="#fff" />
              <circle cx={a.x + 0.7} cy={a.y - 0.5} r="0.45" fill="#fff" />
            </g>
          );
        })}
      </svg>
    );
  }

  return (
    <main className="min-h-screen flex flex-col items-center p-3 sm:p-5 max-w-6xl xl:max-w-[1500px] 2xl:max-w-[1760px] mx-auto w-full text-slate-800">
      <style>{`
        @keyframes sn-pop { 0% { transform: scale(.85); opacity: 0 } 100% { transform: scale(1); opacity: 1 } }
        @keyframes sn-shake { 0%,100% { transform: rotate(0) } 25% { transform: rotate(-16deg) } 75% { transform: rotate(16deg) } }
        @keyframes sn-float { 0%,100% { transform: translateY(0) } 50% { transform: translateY(-4px) } }
      `}</style>

      <div className="w-full flex items-center justify-between mb-3 sm:mb-4">
        <div className="flex items-center gap-2 sm:gap-3">
          {home}
          <div>
            <h1 className="text-xl sm:text-3xl font-extrabold tracking-tight">🐍 뱀과 사다리</h1>
            <p className="hidden sm:block text-xs text-slate-400 -mt-0.5">사다리 타고 쑥, 뱀에 물려 뚝 — 보드가 매판 새로 만들어져요!</p>
          </div>
        </div>
        {roomCode && (
          <div className="flex items-center gap-2">
            <span className="hidden sm:inline text-xs font-bold text-slate-400">방 {roomCode}</span>
            <button onClick={leave} className="text-xs px-3 py-1.5 rounded-lg bg-slate-700 text-slate-100 font-bold">나가기</button>
          </div>
        )}
      </div>

      {/* 입장 */}
      {screen === 'entry' && (
        <div className="w-full max-w-md space-y-4">
          <div className="rounded-2xl overflow-hidden border-2 border-emerald-200">
            <div className="bg-gradient-to-br from-emerald-100 via-amber-50 to-rose-100 px-4 py-5 text-center">
              <p className="text-4xl mb-1">🐍🪜</p>
              <p className="text-sm font-bold text-slate-600">주사위를 굴려 100칸 먼저 도착! 매판 사다리와 뱀 위치가 바뀝니다</p>
            </div>
          </div>
          <input value={nick} onChange={(e) => setNick(e.target.value)} maxLength={16} placeholder="닉네임"
            className="w-full border-2 border-slate-200 rounded-xl px-4 py-3 focus:outline-none focus:border-emerald-400" />

          <div className="rounded-2xl border-2 border-slate-200 p-3 space-y-2">
            <p className="text-sm font-bold text-slate-500">차례 제한시간</p>
            <div className="grid grid-cols-4 gap-2">
              {[0, 10, 20, 40].map((s) => (
                <button key={s} onClick={() => setTurnSec(s)}
                  className={`py-2 rounded-lg text-sm font-bold border-2 ${turnSec === s ? 'border-emerald-400 bg-emerald-50 text-emerald-700' : 'border-slate-200 text-slate-500'}`}>
                  {s === 0 ? '없음' : `${s}초`}
                </button>
              ))}
            </div>
            <p className="text-[11px] text-slate-400">시간이 지나면 자동으로 굴려요. &lsquo;없음&rsquo;이면 재촉하지 않아요.</p>
          </div>

          <button onClick={create} disabled={!nick.trim()}
            className="w-full bg-gradient-to-b from-emerald-500 to-emerald-600 text-white font-extrabold py-3 rounded-xl shadow-md disabled:opacity-40 active:scale-[0.98] transition">
            방 만들기
          </button>

          <div className="flex gap-2">
            <input value={joinCode} onChange={(e) => setJoinCode(e.target.value.toUpperCase())} maxLength={4} placeholder="방 코드"
              className="flex-1 border-2 border-slate-200 rounded-xl px-4 py-3 tracking-[0.3em] font-bold text-center focus:outline-none focus:border-emerald-400" />
            <button onClick={() => join(joinCode)} disabled={!nick.trim() || joinCode.length < 4}
              className="px-5 rounded-xl bg-slate-700 text-white font-bold disabled:opacity-40">참가</button>
          </div>

          {rooms.length > 0 && (
            <div className="rounded-2xl border-2 border-slate-200 divide-y">
              {rooms.map((r) => (
                <button key={r.code} onClick={() => join(r.code)} disabled={!nick.trim()}
                  className="w-full flex items-center justify-between px-4 py-3 text-left hover:bg-slate-50 disabled:opacity-40">
                  <span className="font-bold tracking-widest">{r.code}</span>
                  <span className="text-xs text-slate-400">{r.host} · {r.playerCount}명 · {r.status === 'WAITING' ? '대기중' : '진행중'}</span>
                </button>
              ))}
            </div>
          )}
        </div>
      )}

      {/* 로비 */}
      {screen === 'lobby' && ss && (
        <div className="w-full max-w-md mx-auto space-y-4">
          <div className="text-center rounded-2xl border-2 border-emerald-200 bg-gradient-to-b from-emerald-50 to-white py-4">
            <p className="text-sm text-slate-400">방 코드</p>
            <p className="text-4xl font-extrabold tracking-[0.3em] text-emerald-600 pl-2">{roomCode}</p>
            <p className="text-xs text-slate-400 mt-1">
              {ss.noTimeLimit ? '⏳ 제한시간 없음' : `차례 ${ss.turnSec}초`} · {ss.players.length}/10명
            </p>
          </div>
          <div className="rounded-2xl border-2 border-slate-200 p-3 space-y-1">
            {ss.players.map((p, i) => (
              <div key={i} className="flex items-center gap-2 text-sm px-2 py-1.5 rounded-lg hover:bg-slate-50">
                <span className="w-3.5 h-3.5 rounded-full border border-white shadow" style={{ background: SEAT_COLORS[i % SEAT_COLORS.length] }} />
                <span className="font-bold">{p.host ? '👑 ' : ''}{p.bot ? '🤖 ' : ''}{p.name}{p.me ? ' (나)' : ''}</span>
              </div>
            ))}
          </div>
          <p className="text-center text-xs font-bold text-slate-500 bg-slate-50 rounded-xl py-2">
            {(() => { const n = ss.players.length; const c = n <= 4 ? 10 : n <= 7 ? 8 : 7; const j = c >= 10 ? 8 : c >= 8 ? 6 : 5;
              return `현재 ${n}인 → ${c}×${c} (${c * c}칸) · 🪜 ${j}개 · 🐍 ${j}개`; })()}
          </p>
          {ss.isHost ? (
            <div className="space-y-2">
              <button onClick={addBot} className="w-full border-2 border-slate-200 py-2.5 rounded-xl font-bold text-slate-600">🤖 봇 추가</button>
              <button onClick={startMatch} disabled={ss.players.length < 2}
                className="w-full bg-gradient-to-b from-emerald-500 to-emerald-600 text-white font-extrabold py-3 rounded-xl shadow-md disabled:opacity-40">
                게임 시작
              </button>
            </div>
          ) : <p className="text-center text-sm text-slate-400">방장이 시작하기를 기다리는 중…</p>}
        </div>
      )}

      {/* 게임 */}
      {screen === 'game' && ss && (
        <div className="w-full grid grid-cols-1 lg:grid-cols-[1fr_320px] xl:grid-cols-[1fr_380px] gap-3 sm:gap-4 lg:gap-5 items-start">
          {/* 왼쪽: 보드 */}
          <div className="flex flex-col gap-3 min-w-0">
            {/* 모바일 진행 로그 — 화면 아래는 플로팅 버튼에 가려서 위에 접어 둔다 */}
            {ss.log.length > 0 && (
              <details className="lg:hidden rounded-xl border border-slate-200 bg-white overflow-hidden flex-none">
                <summary className="px-3 py-2 text-xs cursor-pointer select-none flex items-center gap-2">
                  <span className="font-extrabold text-slate-400 flex-none">진행 로그</span>
                  <span className="text-slate-500 truncate">{ss.log[ss.log.length - 1]}</span>
                </summary>
                <div className="px-3 pb-2.5 max-h-40 overflow-y-auto text-xs text-slate-500 space-y-1 border-t border-slate-100 pt-2">
                  {[...ss.log].reverse().map((l, i) => <p key={ss.log.length - i}>{l}</p>)}
                </div>
              </details>
            )}

            <div className="flex items-center justify-between text-xs sm:text-sm flex-none">
              <span className="px-3 py-1.5 rounded-full bg-emerald-100 text-emerald-800 font-bold">
                {ss.cols}×{ss.cols} · 🪜 {ss.ladders.length} · 🐍 {ss.snakes.length}
              </span>
              <span className="font-extrabold text-emerald-700">
                {ended ? '게임 종료'
                  : ss.myTurn ? '내 차례 — 주사위를 굴리세요!'
                  : `${ss.turnName ?? ''}님 차례…`}
                {!ended && ss.nextSeat >= 0 && ss.nextSeat !== ss.turnSeat && (
                  <span className="ml-2 font-normal text-[11px] sm:text-xs text-slate-400">▸ 다음 {seatName(ss.nextSeat)}</span>
                )}
              </span>
            </div>

            {/*
              보드는 정사각형이라 폭을 그대로 쓰면 넓은 화면에서 세로가 화면을 넘어간다.
              화면 높이에 맞춰 상한을 두고 가운데 정렬한다(모바일은 폭이 더 작아 그대로 꽉 참).
            */}
            <div className="relative rounded-2xl border-4 border-emerald-800/70 overflow-hidden shadow-lg mx-auto w-full"
              style={{ aspectRatio: '1 / 1', maxWidth: 'calc(100dvh - 16rem)' }}>
              <div className="absolute inset-0 grid" style={{ gridTemplateColumns: `repeat(${cols}, 1fr)` }}>
                {boardCells().map((sq) => {
                  const { row } = cellOf(sq);
                  const j = jumpFrom(sq), jt = jumpTo(sq);
                  // 연출 중인 말은 칸에서 빼고 아래 떠 있는 토큰으로 그린다(두 번 그리지 않게).
                  const here = active.filter((p) => p.pos === sq && p.seat !== anim?.seat);
                  return (
                    <div key={sq} className="relative border border-white/60 flex items-start justify-start"
                      style={{ background: (row + sq) % 2 === 0 ? CELL_A : CELL_B }}>
                      <span className="absolute top-0 left-0.5 text-[8px] sm:text-[10px] font-bold text-slate-400 leading-tight">{sq}</span>
                      {sq === ss.size && <span className="absolute inset-0 flex items-center justify-center text-sm sm:text-xl">🏁</span>}
                      {j?.kind === 'L' && <span className="absolute bottom-0 right-0.5 text-[9px] sm:text-xs">🪜</span>}
                      {j?.kind === 'S' && <span className="absolute bottom-0 right-0.5 text-[9px] sm:text-xs">🐍</span>}
                      {jt && <span className="absolute bottom-0 left-0.5 text-[7px] sm:text-[9px] text-slate-400">{jt === 'L' ? '▲' : '▼'}</span>}
                      {/* 말 */}
                      <div className="absolute inset-0 flex flex-wrap items-center justify-center gap-0.5 p-0.5">
                        {here.map((p) => (
                          <span key={p.seat} title={p.name}
                            className="rounded-full border border-white shadow font-extrabold text-white flex items-center justify-center"
                            style={{
                              width: here.length > 2 ? '38%' : '52%', height: here.length > 2 ? '38%' : '52%',
                              fontSize: '0.5rem',
                              background: SEAT_COLORS[p.seat % SEAT_COLORS.length],
                              animation: !ended && p.seat === ss.turnSeat ? 'sn-float 1.1s ease-in-out infinite' : undefined,
                            }}>
                            {p.name.slice(0, 1)}
                          </span>
                        ))}
                      </div>
                    </div>
                  );
                })}
              </div>
              <BoardOverlay />

              {/*
                움직이는 말. 칸 격자 위에 절대 위치로 띄우고 left/top에 transition을 걸어
                한 칸씩 걸어가는 것처럼 보이게 한다. 뱀·사다리 구간은 더 느리게 미끄러진다.
              */}
              {anim && (() => {
                const p = active.find((q) => q.seat === anim.seat);
                if (!p) return null;
                const c = centerOf(anim.cell);
                const dur = anim.kind === 'jump' ? JUMP_MS : STEP_MS;
                return (
                  <span className="absolute z-20 rounded-full border-2 border-white font-extrabold text-white flex items-center justify-center shadow-lg"
                    style={{
                      left: `${c.x}%`, top: `${c.y}%`,
                      width: `${(1 / cols) * 62}%`, height: `${(1 / cols) * 62}%`,
                      transform: 'translate(-50%,-50%)',
                      transition: `left ${dur}ms ${anim.kind === 'jump' ? 'ease-in-out' : 'linear'}, top ${dur}ms ${anim.kind === 'jump' ? 'ease-in-out' : 'linear'}`,
                      background: SEAT_COLORS[anim.seat % SEAT_COLORS.length],
                      fontSize: '0.55rem',
                    }}>
                    {p.name.slice(0, 1)}
                    {anim.kind === 'jump' && (
                      <span className="absolute -top-4 left-1/2 -translate-x-1/2 text-base drop-shadow">
                        {anim.how === 'LADDER' ? '🪜' : '🐍'}
                      </span>
                    )}
                  </span>
                );
              })()}
            </div>

            {/* 출발 대기 중인 말 */}
            {active.some((p) => p.pos === 0) && (
              <div className="flex items-center gap-2 text-xs text-slate-400">
                <span className="font-bold">출발 대기</span>
                {active.filter((p) => p.pos === 0).map((p) => (
                  <span key={p.seat} className="w-5 h-5 rounded-full border border-white shadow text-[10px] font-extrabold text-white flex items-center justify-center"
                    style={{ background: SEAT_COLORS[p.seat % SEAT_COLORS.length] }}>{p.name.slice(0, 1)}</span>
                ))}
              </div>
            )}

            {/* 주사위 + 굴리기 */}
            {!ended && (
              <div className="flex items-center justify-center gap-4 rounded-2xl border-2 border-slate-200 bg-white py-3">
                <DiceFace v={spinFace > 0 ? spinFace : ss.lastDie} rolling={spinFace > 0} />
                {ss.myTurn ? (
                  <button onClick={roll} disabled={busyAnim}
                    className="px-6 py-3 rounded-xl bg-gradient-to-b from-emerald-500 to-emerald-600 text-white font-extrabold shadow-md active:scale-95 disabled:opacity-50">
                    {busyAnim ? '움직이는 중…' : `🎲 굴리기${!ss.noTimeLimit && remainSec > 0 ? ` (${remainSec})` : ''}`}
                  </button>
                ) : (
                  <span className="text-sm text-slate-400">
                    {spinFace > 0 || anim ? `${seatName(ss.lastMove?.seat ?? ss.turnSeat)}님이 움직이는 중…` : `${ss.turnName}님이 굴리는 중…`}
                  </span>
                )}
              </div>
            )}

            {ss.lastAction && !ended && (
              <p key={ss.lastAction} className="text-center text-sm font-bold text-slate-600" style={{ animation: 'sn-pop .3s ease' }}>
                {ss.lastAction}
              </p>
            )}

            {ended && !busyAnim && (
              <div className="text-center rounded-3xl border-2 border-emerald-400 bg-gradient-to-b from-emerald-50 to-amber-50 px-6 py-6 shadow" style={{ animation: 'sn-pop .35s ease' }}>
                <p className="text-3xl mb-1">🎉🏁🎉</p>
                <p className="text-xl sm:text-2xl font-extrabold">{ss.winnerLabel} 승리!</p>
                <div className="mt-3 flex flex-wrap justify-center gap-2 text-sm">
                  {ranked.map((p, i) => (
                    <span key={p.seat} className="px-2.5 py-1 rounded-full bg-white border font-bold">
                      {i + 1}위 {p.name} · {p.pos}칸 · 🎲{p.rolls}
                    </span>
                  ))}
                </div>
              </div>
            )}
          </div>

          {/* 오른쪽: 순서/점수판 + 로그 */}
          <div className="flex flex-col gap-3 sm:gap-4 min-h-0">
            <div className="rounded-2xl border-2 border-slate-200 overflow-hidden bg-white flex-none">
              <p className="px-3 py-2 text-xs lg:text-sm font-extrabold text-slate-400 bg-slate-50 border-b border-slate-100 flex items-center justify-between">
                <span>순서 · 위치</span>
                <span className="font-normal text-[10px] text-slate-300">위에서 아래로 차례가 돌아요</span>
              </p>
              {bySeat.map((p) => {
                const isTurn = p.seat === ss.turnSeat && !ended;
                const isNext = p.seat === ss.nextSeat && !ended && !isTurn;
                return (
                  <div key={p.seat} className={`px-3 py-2 border-b border-slate-50 last:border-b-0 ${isTurn ? 'bg-emerald-50 border-l-4 border-l-emerald-400' : isNext ? 'bg-slate-50/70' : ''}`}>
                    <div className="flex items-center justify-between">
                      <span className="font-bold text-sm flex items-center gap-1.5 min-w-0">
                        <span className={`w-4 flex-none text-center text-xs ${isTurn ? 'text-emerald-500' : 'text-slate-300'}`}>
                          {isTurn ? '▶' : isNext ? '⌄' : ''}
                        </span>
                        <span className="w-3 h-3 rounded-full border border-white shadow flex-none" style={{ background: SEAT_COLORS[p.seat % SEAT_COLORS.length] }} />
                        <span className="truncate">{p.bot ? '🤖 ' : ''}{p.name}{p.me ? ' (나)' : ''}</span>
                      </span>
                      <span className="flex items-center gap-1.5 flex-none">
                        <span className={`text-[10px] font-bold px-1.5 py-0.5 rounded-full ${rankOf(p.seat) === 1 ? 'bg-amber-100 text-amber-700' : 'bg-slate-100 text-slate-400'}`}>
                          {rankOf(p.seat)}위
                        </span>
                        <span className="font-extrabold text-sm">{p.pos}칸</span>
                      </span>
                    </div>
                  </div>
                );
              })}
            </div>

            {ss.log.length > 0 && (
              <div className="hidden lg:flex rounded-2xl border-2 border-slate-200 bg-white overflow-hidden flex-col">
                <p className="px-3 py-2.5 text-sm font-extrabold text-slate-400 bg-slate-50 border-b border-slate-100 flex-none">진행 로그</p>
                <div className="p-3 max-h-[60vh] overflow-y-auto text-sm text-slate-500 space-y-1.5">
                  {[...ss.log].reverse().map((l, i) => <p key={ss.log.length - i}>{l}</p>)}
                </div>
              </div>
            )}
          </div>
        </div>
      )}

      {roomCode && <RoomChat game="snakes" roomCode={roomCode} clientId={id.current} nick={nick} />}
    </main>
  );
}
