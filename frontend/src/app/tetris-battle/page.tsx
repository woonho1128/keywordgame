'use client';

import Link from 'next/link';
import { useCallback, useEffect, useRef, useState } from 'react';
import { api } from '@/lib/api';
import RoomChat from '@/components/RoomChat';
import { PieceType, STATES } from '../tetris/srs';
import {
  TetrisEngine, attackLines, COLS, VIS_ROWS, HIDDEN, ROWS, LockResult,
} from '../tetris/engine';

const CELL = 26;
const LOCK_DELAY = 500, MAX_RESETS = 15, DAS = 133, ARR = 25, SOFT_MS = 28;
const COLORS: Record<PieceType | 'G', string> = {
  I: '#22d3ee', O: '#facc15', T: '#c084fc', S: '#4ade80', Z: '#f87171', J: '#60a5fa', L: '#fb923c', G: '#64748b',
};

type Opponent = { name: string; bot: boolean; alive: boolean; heights: number[]; placement: number | null };
type ServerState = {
  phase: string; format: string; isHost: boolean; joined: boolean;
  players: { name: string; bot: boolean; botLevel: string | null; host: boolean; me: boolean; alive: boolean }[];
  me: { alive: boolean; placement: number | null; incomingGarbage: number };
  opponents: Opponent[];
  aliveCount: number; totalPlayers: number; winner: string | null; myPlacement: number | null;
};
type Room = { code: string; status: string; playerCount: number; host: string };

function clientId(): string {
  if (typeof window === 'undefined') return '';
  let id = localStorage.getItem('tetris_battle_client_id');
  if (!id) { id = Math.random().toString(36).slice(2) + Date.now().toString(36); localStorage.setItem('tetris_battle_client_id', id); }
  return id;
}

function MiniPiece({ type }: { type: PieceType | null }) {
  return (
    <div className="grid" style={{ gridTemplateColumns: 'repeat(4,1fr)', width: 48, height: 48, gap: 1 }}>
      {Array.from({ length: 16 }).map((_, i) => {
        const r = Math.floor(i / 4), c = i % 4;
        const on = type && STATES[type][0].some(([rr, cc]) => rr === r && cc === c);
        return <div key={i} className="rounded-[2px]" style={{ background: on ? COLORS[type!] : 'transparent' }} />;
      })}
    </div>
  );
}

// 상대 미니보드: 10칸 열 높이 실루엣
function OppBoard({ o }: { o: Opponent }) {
  const danger = o.heights.reduce((a, b) => a + b, 0) / 10 > 13;
  return (
    <div className={`rounded-lg p-1.5 border ${o.alive ? (danger ? 'border-red-500' : 'border-slate-700') : 'border-slate-800 opacity-50'} bg-slate-900`}>
      <div className="flex items-end gap-[1px] h-16" style={{ width: 60 }}>
        {(o.heights.length ? o.heights : new Array(10).fill(0)).map((h, i) => (
          <div key={i} className="flex-1 rounded-sm" style={{ height: `${Math.min(100, (h / 20) * 100)}%`, background: o.alive ? (danger ? '#ef4444' : '#475569') : '#334155' }} />
        ))}
      </div>
      <p className="text-[10px] mt-0.5 text-center truncate text-slate-300" style={{ width: 60 }}>
        {o.bot ? '🤖' : ''}{o.name}{!o.alive && o.placement ? ` ·${o.placement}등` : ''}
      </p>
    </div>
  );
}

export default function TetrisBattlePage() {
  const cid = useRef('');
  const [nick, setNick] = useState('');
  const [screen, setScreen] = useState<'entry' | 'lobby' | 'play'>('entry');
  const [roomCode, setRoomCode] = useState<string | null>(null);
  const [format, setFormat] = useState<'DUEL' | 'ROYALE'>('DUEL');
  const [joinCode, setJoinCode] = useState('');
  const [rooms, setRooms] = useState<Room[]>([]);
  const [ss, setSs] = useState<ServerState | null>(null);
  const [botLevel, setBotLevel] = useState('NORMAL');
  const [ranking, setRanking] = useState<{ rank: number; nick: string; wins: number }[]>([]);

  // 게임 표시 상태
  const [score, setScore] = useState(0);
  const [hold, setHold] = useState<PieceType | null>(null);
  const [nextQ, setNextQ] = useState<PieceType[]>([]);
  const [incoming, setIncoming] = useState(0);
  const [dead, setDead] = useState(false);

  const canvasRef = useRef<HTMLCanvasElement>(null);
  const engineRef = useRef<TetrisEngine | null>(null);
  const loopRef = useRef<any>({});
  const pendingGarbage = useRef(0);   // 받을 가비지 대기열
  const attackToSend = useRef(0);     // 다음 sync에 실어보낼 공격 누적
  const aliveRef = useRef(true);
  const screenRef = useRef(screen); screenRef.current = screen;
  const roomRef = useRef<string | null>(null); roomRef.current = roomCode;
  const endedRef = useRef(false); endedRef.current = ss?.phase === 'ENDED';

  useEffect(() => { cid.current = clientId(); try { setNick(localStorage.getItem('arcade_nick') || ''); } catch {} }, []);

  const loadRooms = useCallback(async () => {
    try { setRooms(await api<Room[]>(`/api/v1/tetris-battle/rooms`)); } catch {}
    try { setRanking(await api(`/api/v1/tetris-battle/ranking?limit=10`)); } catch {}
  }, []);
  useEffect(() => { if (screen === 'entry') { loadRooms(); const t = setInterval(loadRooms, 3000); return () => clearInterval(t); } }, [screen, loadRooms]);

  // ── 로비 폴링(플레이 중엔 /sync가 상태를 갱신하므로 로비에서만) ──
  useEffect(() => {
    if (screen !== 'lobby' || !roomCode) return;
    let alive = true;
    const poll = async () => {
      try {
        const s = await api<ServerState>(`/api/v1/tetris-battle/me?roomCode=${roomCode}&clientId=${cid.current}`);
        if (!alive) return;
        setSs(s);
        if (s.phase === 'PLAYING' && screenRef.current === 'lobby') startGame();
      } catch {}
    };
    const t = setInterval(poll, 1000); poll();
    return () => { alive = false; clearInterval(t); };
  }, [screen, roomCode]); // eslint-disable-line react-hooks/exhaustive-deps

  // ── 방 생성/참가 ────────────────────────────────────
  const create = async () => {
    const n = nick.trim(); if (!n) return;
    try { localStorage.setItem('arcade_nick', n); } catch {}
    try {
      const res = await api<{ roomCode: string; state: ServerState }>(`/api/v1/tetris-battle/new?clientId=${cid.current}`, {
        method: 'POST', body: JSON.stringify({ nick: n, format }),
      });
      setRoomCode(res.roomCode); setSs(res.state); setScreen('lobby');
    } catch (e: any) { alert(e?.message || '방 생성 실패'); }
  };
  const join = async (code: string) => {
    const n = nick.trim(); if (!n || !code) return;
    try { localStorage.setItem('arcade_nick', n); } catch {}
    try {
      const s = await api<ServerState>(`/api/v1/tetris-battle/join?roomCode=${code}&clientId=${cid.current}`, {
        method: 'POST', body: JSON.stringify({ nick: n }),
      });
      setRoomCode(code.toUpperCase()); setSs(s); setScreen('lobby');
    } catch (e: any) { alert(e?.message || '참가 실패'); }
  };
  const addBot = async () => {
    try { setSs(await api(`/api/v1/tetris-battle/add-bot?roomCode=${roomCode}&clientId=${cid.current}&level=${botLevel}`, { method: 'POST', body: '{}' })); } catch (e: any) { alert(e?.message); }
  };
  const startMatch = async () => {
    try { await api(`/api/v1/tetris-battle/start?roomCode=${roomCode}&clientId=${cid.current}`, { method: 'POST', body: '{}' }); } catch (e: any) { alert(e?.message); }
  };
  const leaveRoom = async () => {
    const rc = roomRef.current;
    if (rc) { try { await api(`/api/v1/tetris-battle/leave?roomCode=${rc}&clientId=${cid.current}`, { method: 'POST', body: '{}' }); } catch {} }
    if (loopRef.current.raf) cancelAnimationFrame(loopRef.current.raf);
    setScreen('entry'); setRoomCode(null); setSs(null); setDead(false);
  };

  // ── 게임 시작(서버가 PLAYING) ───────────────────────
  const startGame = () => {
    const e = new TetrisEngine('marathon');
    engineRef.current = e;
    loopRef.current = { last: performance.now(), grav: 0, rest: 0, resets: 0, dir: 0, dasT: 0, dasCharged: false, arr: 0, soft: false, softAcc: 0, raf: 0 };
    pendingGarbage.current = 0; attackToSend.current = 0; aliveRef.current = true;
    setDead(false); setIncoming(0); setScore(0); setHold(null); setNextQ(e.nextQueue(5));
    setScreen('play');
  };

  const syncState = useCallback(() => {
    const e = engineRef.current; if (!e) return;
    setScore(e.score); setHold(e.hold); setNextQ(e.nextQueue(5)); setIncoming(pendingGarbage.current);
  }, []);

  // ── 락 처리(가비지/상쇄/공격) ───────────────────────
  const onLock = useCallback((res: LockResult) => {
    const e = engineRef.current!;
    if (res.linesCleared > 0) {
      let atk = attackLines(res);
      const cancel = Math.min(atk, pendingGarbage.current);
      pendingGarbage.current -= cancel; atk -= cancel;
      if (atk > 0) attackToSend.current += atk;
    } else if (pendingGarbage.current > 0) {
      const hole = Math.floor(Math.random() * COLS);
      e.addGarbage(pendingGarbage.current, hole);
      pendingGarbage.current = 0;
    }
    if (e.over) { aliveRef.current = false; setDead(true); }
    syncState();
  }, [syncState]);

  // ── sync 폴링(플레이 중) ────────────────────────────
  useEffect(() => {
    if (screen !== 'play') return;
    let busy = false;
    const tick = async () => {
      if (busy) return; busy = true;
      const e = engineRef.current;
      const atk = attackToSend.current; attackToSend.current = 0;
      try {
        const s = await api<ServerState>(`/api/v1/tetris-battle/sync?roomCode=${roomRef.current}&clientId=${cid.current}`, {
          method: 'POST', body: JSON.stringify({ attacks: atk, heights: e ? e.columnHeights() : new Array(10).fill(0), alive: aliveRef.current }),
        });
        setSs(s);
        if (s.me.incomingGarbage > 0) { pendingGarbage.current += s.me.incomingGarbage; setIncoming(pendingGarbage.current); }
        if (s.phase === 'ENDED' && loopRef.current.raf) cancelAnimationFrame(loopRef.current.raf);
      } catch { attackToSend.current += atk; } // 실패 시 공격 롤백
      finally { busy = false; }
    };
    const t = setInterval(tick, 1000); tick();
    return () => clearInterval(t);
  }, [screen]);

  // ── 게임 루프 ───────────────────────────────────────
  useEffect(() => {
    if (screen !== 'play') return;
    const e = engineRef.current!; const l = loopRef.current;
    l.last = performance.now();
    const step = (now: number) => {
      const dt = Math.min(64, now - l.last); l.last = now;
      if (!aliveRef.current || endedRef.current) { draw(); l.raf = requestAnimationFrame(step); return; }
      if (l.dir !== 0) {
        l.dasT += dt;
        if (!l.dasCharged && l.dasT >= DAS) { l.dasCharged = true; l.arr = 0; }
        if (l.dasCharged) { l.arr += dt; while (l.arr >= ARR) { if (e.move(l.dir)) { l.rest = 0; l.resets = Math.min(MAX_RESETS, l.resets + 1); } l.arr -= ARR; } }
      }
      if (l.soft) { l.softAcc += dt; while (l.softAcc >= SOFT_MS) { if (e.softDrop()) { l.rest = 0; } l.softAcc -= SOFT_MS; } }
      l.grav += dt; const g = 800;
      while (l.grav >= g) { l.grav -= g; if (!e.moveDown()) break; }
      if (e.isResting()) {
        l.rest += dt;
        if (l.rest >= LOCK_DELAY || l.resets >= MAX_RESETS) { const r = e.lock(); l.rest = 0; l.resets = 0; onLock(r); }
      } else l.rest = 0;
      draw();
      l.raf = requestAnimationFrame(step);
    };
    const draw = () => {
      const canvas = canvasRef.current; if (!canvas) return;
      const ctx = canvas.getContext('2d')!; const dpr = window.devicePixelRatio || 1;
      const w = COLS * CELL, h = VIS_ROWS * CELL;
      if (canvas.width !== w * dpr || canvas.height !== h * dpr) { canvas.width = w * dpr; canvas.height = h * dpr; }
      ctx.setTransform(dpr, 0, 0, dpr, 0, 0);
      ctx.fillStyle = '#0f172a'; ctx.fillRect(0, 0, w, h);
      ctx.strokeStyle = 'rgba(148,163,184,0.10)'; ctx.lineWidth = 1;
      for (let c = 1; c < COLS; c++) { ctx.beginPath(); ctx.moveTo(c * CELL, 0); ctx.lineTo(c * CELL, h); ctx.stroke(); }
      for (let r = 1; r < VIS_ROWS; r++) { ctx.beginPath(); ctx.moveTo(0, r * CELL); ctx.lineTo(w, r * CELL); ctx.stroke(); }
      const cell = (r: number, c: number, color: string, ghost = false) => {
        const y = (r - HIDDEN) * CELL; if (r < HIDDEN) return;
        if (ghost) { ctx.strokeStyle = color; ctx.lineWidth = 2; ctx.strokeRect(c * CELL + 2, y + 2, CELL - 4, CELL - 4); }
        else { ctx.fillStyle = color; ctx.fillRect(c * CELL + 1, y + 1, CELL - 2, CELL - 2); ctx.fillStyle = 'rgba(255,255,255,0.18)'; ctx.fillRect(c * CELL + 1, y + 1, CELL - 2, 3); }
      };
      for (let r = 0; r < ROWS; r++) for (let c = 0; c < COLS; c++) { const v = e.board[r][c]; if (v) cell(r, c, COLORS[v]); }
      const gh = e.ghost(); if (gh) for (const [r, c] of e.cellsOf(gh)) cell(r, c, COLORS[gh.type], true);
      if (e.cur) for (const [r, c] of e.cellsOf(e.cur)) cell(r, c, COLORS[e.cur.type]);
    };
    l.raf = requestAnimationFrame(step);
    return () => { if (l.raf) cancelAnimationFrame(l.raf); };
  }, [screen, onLock]);

  // ── 입력 ────────────────────────────────────────────
  const act = useCallback((fn: (e: TetrisEngine) => void) => {
    const e = engineRef.current; if (!e || !aliveRef.current) return;
    fn(e); const l = loopRef.current;
    if (e.isResting()) { l.rest = 0; l.resets = Math.min(MAX_RESETS, l.resets + 1); }
    syncState();
  }, [syncState]);
  const hardDrop = useCallback(() => { const e = engineRef.current; if (!e || !aliveRef.current) return; const r = e.hardDrop(); loopRef.current.rest = 0; loopRef.current.resets = 0; onLock(r); }, [onLock]);
  const doHold = useCallback(() => { const e = engineRef.current; if (!e || !aliveRef.current) return; if (e.holdPiece()) { loopRef.current.rest = 0; syncState(); } }, [syncState]);

  useEffect(() => {
    if (screen !== 'play') return;
    const down = (ev: KeyboardEvent) => {
      const k = ev.key; if (['ArrowLeft', 'ArrowRight', 'ArrowUp', 'ArrowDown', ' '].includes(k)) ev.preventDefault();
      const l = loopRef.current;
      switch (k) {
        case 'ArrowLeft': if (!ev.repeat) { act((e) => e.move(-1)); l.dir = -1; l.dasT = 0; l.dasCharged = false; } break;
        case 'ArrowRight': if (!ev.repeat) { act((e) => e.move(1)); l.dir = 1; l.dasT = 0; l.dasCharged = false; } break;
        case 'ArrowDown': l.soft = true; break;
        case 'ArrowUp': case 'x': case 'X': if (!ev.repeat) act((e) => e.rotate(1)); break;
        case 'z': case 'Z': case 'Control': if (!ev.repeat) act((e) => e.rotate(-1)); break;
        case ' ': if (!ev.repeat) hardDrop(); break;
        case 'c': case 'C': case 'Shift': if (!ev.repeat) doHold(); break;
      }
    };
    const up = (ev: KeyboardEvent) => { const l = loopRef.current; if (ev.key === 'ArrowLeft' && l.dir === -1) l.dir = 0; if (ev.key === 'ArrowRight' && l.dir === 1) l.dir = 0; if (ev.key === 'ArrowDown') l.soft = false; };
    window.addEventListener('keydown', down); window.addEventListener('keyup', up);
    return () => { window.removeEventListener('keydown', down); window.removeEventListener('keyup', up); };
  }, [screen, act, hardDrop, doHold]);

  const pressDir = (d: -1 | 1) => { act((e) => e.move(d)); const l = loopRef.current; l.dir = d; l.dasT = 0; l.dasCharged = false; };
  const releaseDir = (d: -1 | 1) => { const l = loopRef.current; if (l.dir === d) l.dir = 0; };

  // ═══════════════ 렌더 ═══════════════
  const ended = ss?.phase === 'ENDED';

  return (
    <main className="min-h-screen flex flex-col items-center p-3 max-w-4xl mx-auto w-full text-slate-100">
      <div className="w-full flex items-center justify-between mb-3">
        <div className="flex items-center gap-2">
          <Link href="/" aria-label="홈으로" className="text-lg leading-none text-slate-500 hover:text-slate-800 dark:hover:text-slate-100">🏠</Link>
          <h1 className="text-xl font-bold text-slate-800 dark:text-slate-100">🧱⚔️ 테트리스 배틀</h1>
        </div>
        {roomCode && <button onClick={leaveRoom} className="text-xs px-2 py-1 rounded bg-slate-700 text-slate-100">나가기</button>}
      </div>

      {/* ── 입장 ── */}
      {screen === 'entry' && (
        <div className="w-full space-y-4 text-slate-800 dark:text-slate-100">
          <input value={nick} onChange={(e) => setNick(e.target.value)} maxLength={16} placeholder="닉네임 (필수)"
            className={`w-full border bg-transparent rounded-lg px-3 py-2 focus:outline-none focus:border-fuchsia-500 ${nick.trim() ? 'border-slate-300 dark:border-slate-600' : 'border-fuchsia-400'}`} />
          <div className="grid grid-cols-2 gap-2">
            {(['DUEL', 'ROYALE'] as const).map((f) => (
              <button key={f} onClick={() => setFormat(f)} className={`rounded-xl border-2 p-3 text-left ${format === f ? 'border-fuchsia-500 bg-fuchsia-500/10' : 'border-slate-300 dark:border-slate-600'}`}>
                <p className="font-bold">{f === 'DUEL' ? '⚔️ 1v1' : '👑 배틀로얄'}</p>
                <p className="text-xs text-slate-400">{f === 'DUEL' ? '1:1 맞대결' : '최대 6인 최후생존'}</p>
              </button>
            ))}
          </div>
          <button onClick={create} disabled={!nick.trim()} className="w-full bg-fuchsia-600 text-white font-bold py-3 rounded-lg disabled:opacity-40">방 만들기</button>
          <div className="flex gap-2">
            <input value={joinCode} onChange={(e) => setJoinCode(e.target.value.toUpperCase())} maxLength={4} placeholder="코드"
              className="flex-1 border border-slate-300 dark:border-slate-600 bg-transparent rounded-lg px-3 py-2 uppercase" />
            <button onClick={() => join(joinCode)} disabled={!nick.trim() || !joinCode} className="px-5 bg-slate-700 text-white font-bold rounded-lg disabled:opacity-40">참가</button>
          </div>

          <div className="rounded-xl border border-slate-200 dark:border-slate-700 p-3">
            <p className="text-sm font-bold text-slate-500 mb-2">🎮 열린 방</p>
            {rooms.length === 0 ? <p className="text-slate-400 text-sm text-center py-2">방이 없어요. 만들어보세요!</p> : (
              <div className="space-y-1">
                {rooms.map((r) => (
                  <button key={r.code} onClick={() => join(r.code)} disabled={!nick.trim()} className="w-full flex justify-between text-sm py-1.5 px-2 rounded hover:bg-fuchsia-500/10 disabled:opacity-40">
                    <span className="font-bold">{r.code} · {r.host}</span>
                    <span className="text-slate-400">{r.status === 'WAITING' ? '모집중' : r.status === 'PLAYING' ? '진행중' : '종료'} · {r.playerCount}명</span>
                  </button>
                ))}
              </div>
            )}
          </div>

          <div className="rounded-xl border border-slate-200 dark:border-slate-700 p-3">
            <p className="text-sm font-bold text-slate-500 mb-2">🏆 우승 랭킹</p>
            {ranking.length === 0 ? <p className="text-slate-400 text-sm text-center py-2">아직 기록이 없어요</p> : (
              <div className="space-y-0.5">
                {ranking.map((r) => (
                  <div key={r.rank} className="flex justify-between text-sm px-1">
                    <span className="font-bold">{r.rank <= 3 ? ['🥇', '🥈', '🥉'][r.rank - 1] : `${r.rank}.`} {r.nick}</span>
                    <span className="text-fuchsia-500 font-bold">{r.wins}승</span>
                  </div>
                ))}
              </div>
            )}
          </div>
        </div>
      )}

      {/* ── 로비 ── */}
      {screen === 'lobby' && ss && (
        <div className="w-full space-y-4 text-slate-800 dark:text-slate-100">
          <div className="text-center">
            <p className="text-sm text-slate-400">방 코드</p>
            <p className="text-3xl font-extrabold tracking-widest text-fuchsia-500">{roomCode}</p>
            <p className="text-xs text-slate-400">{ss.format === 'DUEL' ? '1v1' : '배틀로얄'} · {ss.totalPlayers}/{ss.format === 'DUEL' ? 2 : 6}명</p>
          </div>
          <div className="rounded-xl border border-slate-200 dark:border-slate-700 p-3 space-y-1">
            {ss.players.map((p, i) => (
              <div key={i} className="flex justify-between text-sm px-1 py-0.5">
                <span className="font-bold">{p.host ? '👑 ' : ''}{p.bot ? '🤖 ' : ''}{p.name}{p.me ? ' (나)' : ''}</span>
                <span className="text-slate-400">{p.bot ? (p.botLevel === 'EASY' ? '초급' : p.botLevel === 'HARD' ? '고급' : '중급') : ''}</span>
              </div>
            ))}
          </div>
          {ss.isHost ? (
            <div className="space-y-2">
              <div className="flex gap-2">
                <select value={botLevel} onChange={(e) => setBotLevel(e.target.value)} className="flex-1 border border-slate-300 dark:border-slate-600 bg-transparent rounded-lg px-3 py-2">
                  <option value="EASY">봇 초급</option><option value="NORMAL">봇 중급</option><option value="HARD">봇 고급</option>
                </select>
                <button onClick={addBot} className="px-4 bg-slate-700 text-white font-bold rounded-lg">봇 추가</button>
              </div>
              <button onClick={startMatch} disabled={ss.totalPlayers < 2} className="w-full bg-fuchsia-600 text-white font-bold py-3 rounded-lg disabled:opacity-40">시작하기</button>
            </div>
          ) : <p className="text-center text-sm text-slate-400">방장이 시작하기를 기다리는 중…</p>}
        </div>
      )}

      {/* ── 플레이 ── */}
      {screen === 'play' && (
        <div className="w-full flex flex-col items-center">
          {ended && (
            <div className="mb-3 text-center rounded-xl border-2 border-fuchsia-500 bg-fuchsia-500/10 px-6 py-3">
              <p className="text-lg font-bold">{ss?.winner ? `🏆 ${ss.winner} 우승!` : '게임 종료'}</p>
              {ss?.myPlacement && <p className="text-sm text-slate-300">내 등수: {ss.myPlacement}등 / {ss.totalPlayers}명</p>}
              <button onClick={leaveRoom} className="mt-2 px-5 py-2 rounded-lg bg-fuchsia-600 font-bold">나가기</button>
            </div>
          )}
          <div className="flex gap-3 items-start justify-center w-full">
            {/* HOLD + 받을 가비지 게이지 */}
            <div className="hidden sm:flex flex-col gap-2 items-center">
              <span className="text-[11px] text-slate-400 font-bold">HOLD</span>
              <div className="p-1.5 rounded-lg bg-slate-800/70 border border-slate-700"><MiniPiece type={hold} /></div>
              <span className="text-[11px] text-slate-400 font-bold mt-2">받을 공격</span>
              <div className="w-4 rounded bg-slate-800 border border-slate-700 flex flex-col-reverse" style={{ height: 120 }}>
                <div className="w-full rounded bg-red-500 transition-all" style={{ height: `${Math.min(100, (incoming / 20) * 100)}%` }} />
              </div>
              <span className="text-xs font-bold text-red-400">{incoming > 0 ? incoming : ''}</span>
            </div>
            {/* 보드 */}
            <div className="relative">
              <canvas ref={canvasRef} className="rounded-lg border-2 border-slate-700 bg-slate-900 touch-none block w-auto h-auto max-w-[70vw] max-h-[62vh] sm:max-w-[300px] sm:max-h-[74vh]" />
              {incoming > 0 && <div className="sm:hidden absolute -left-3 top-0 bottom-0 w-2 rounded bg-red-500/70" style={{ height: `${Math.min(100, (incoming / 20) * 100)}%` }} />}
              {dead && !ended && <div className="absolute inset-0 flex items-center justify-center bg-slate-900/80 rounded-lg"><span className="font-bold text-red-400">💀 탈락 · 관전 중</span></div>}
            </div>
            {/* NEXT + 상대 */}
            <div className="flex flex-col gap-2 items-center">
              <span className="text-[11px] text-slate-400 font-bold">NEXT</span>
              <div className="p-1 rounded-lg bg-slate-800/70 border border-slate-700 flex flex-col gap-0.5">
                {nextQ.slice(0, 4).map((t, i) => <MiniPiece key={i} type={t} />)}
              </div>
            </div>
          </div>
          {/* 상대 미니보드 */}
          <div className="mt-3 flex gap-2 flex-wrap justify-center max-w-full">
            {(ss?.opponents || []).map((o, i) => <OppBoard key={i} o={o} />)}
          </div>
          <p className="text-xs text-slate-400 mt-1">생존 {ss?.aliveCount ?? '-'}명 · 줄을 지워 상대에게 공격! 받은 공격은 되받아치면 상쇄돼요.</p>

          {/* 모바일 조작 */}
          <div className="sm:hidden mt-3 pb-20 w-full max-w-sm select-none">
            <div className="grid grid-cols-3 gap-2 mb-2">
              <button onPointerDown={() => act((e) => e.rotate(-1))} className="py-3 rounded-lg bg-slate-700 font-bold text-lg">⟲</button>
              <button onPointerDown={hardDrop} className="py-3 rounded-lg bg-fuchsia-600 font-bold">⤓</button>
              <button onPointerDown={() => act((e) => e.rotate(1))} className="py-3 rounded-lg bg-slate-700 font-bold text-lg">⟳</button>
            </div>
            <div className="grid grid-cols-4 gap-2">
              <button onPointerDown={() => pressDir(-1)} onPointerUp={() => releaseDir(-1)} onPointerLeave={() => releaseDir(-1)} className="py-3 rounded-lg bg-slate-700 font-bold text-lg">◀</button>
              <button onPointerDown={() => { loopRef.current.soft = true; }} onPointerUp={() => { loopRef.current.soft = false; }} onPointerLeave={() => { loopRef.current.soft = false; }} className="py-3 rounded-lg bg-slate-700 font-bold text-lg">▼</button>
              <button onPointerDown={() => pressDir(1)} onPointerUp={() => releaseDir(1)} onPointerLeave={() => releaseDir(1)} className="py-3 rounded-lg bg-slate-700 font-bold text-lg">▶</button>
              <button onPointerDown={doHold} className="py-3 rounded-lg bg-slate-700 font-bold text-sm">HOLD</button>
            </div>
          </div>
        </div>
      )}

      {roomCode && <RoomChat game="tetris-battle" roomCode={roomCode} clientId={cid.current} nick={nick} />}
    </main>
  );
}
