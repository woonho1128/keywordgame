'use client';

import { useCallback, useEffect, useRef, useState } from 'react';
import { api } from '@/lib/api';
import Leaderboard from '@/components/Leaderboard';

const G = 44;            // 격자 크기
const STEP_MS = 110;     // 한 칸 이동 간격
const BOT_N = 4;
const DIRS = [{ x: 0, y: -1 }, { x: 1, y: 0 }, { x: 0, y: 1 }, { x: -1, y: 0 }]; // 상 우 하 좌
// id별 색: 0=빈칸, 1=플레이어, 2.. 봇
const FILL = ['#ffffff', '#bfdbfe', '#bbf7d0', '#fde68a', '#f5d0fe', '#fecaca'];
const SOLID = ['#ffffff', '#3b82f6', '#22c55e', '#f59e0b', '#a855f7', '#ef4444'];

type P = { id: number; cx: number; cy: number; dir: number; nextDir: number; alive: boolean; name: string; bot: boolean; sx: number; sy: number; out: number; respawnAt: number };

export default function TerritoryPage() {
  const canvasRef = useRef<HTMLCanvasElement>(null);
  const [phase, setPhase] = useState<'ready' | 'playing' | 'over'>('ready');
  const [nick, setNick] = useState('');
  const [score, setScore] = useState(0);
  const [finalScore, setFinalScore] = useState(0);
  const [myRank, setMyRank] = useState<number | null>(null);
  const [refreshKey, setRefreshKey] = useState(0);

  const gRef = useRef<{ grid: Int32Array; trail: Int32Array; players: P[]; me: P | null; raf: number; acc: number; last: number }>(
    { grid: new Int32Array(G * G), trail: new Int32Array(G * G), players: [], me: null, raf: 0, acc: 0, last: 0 });
  const keyRef = useRef<Set<string>>(new Set());
  const phaseRef = useRef(phase); phaseRef.current = phase;

  useEffect(() => { try { setNick(localStorage.getItem('arcade_nick') || ''); } catch {} }, []);

  const at = (x: number, y: number) => y * G + x;
  const inb = (x: number, y: number) => x >= 0 && x < G && y >= 0 && y < G;

  const claimBlock = (grid: Int32Array, cx: number, cy: number, id: number) => {
    for (let dy = -1; dy <= 1; dy++) for (let dx = -1; dx <= 1; dx++) {
      const x = cx + dx, y = cy + dy; if (inb(x, y)) grid[at(x, y)] = id;
    }
  };
  const countCells = (grid: Int32Array, id: number) => { let n = 0; for (let i = 0; i < grid.length; i++) if (grid[i] === id) n++; return n; };

  const submitScore = useCallback(async (sc: number) => {
    const n = (nick.trim() || '익명').slice(0, 16);
    try { const res = await api<{ myRank: number }>(`/api/v1/scores/territory`, { method: 'POST', body: JSON.stringify({ nick: n, score: sc }) }); setMyRank(res.myRank); } catch {}
    setRefreshKey((k) => k + 1);
  }, [nick]);

  const endGame = useCallback((sc: number) => { setFinalScore(sc); setPhase('over'); submitScore(sc); }, [submitScore]);

  const start = () => {
    const n = nick.trim(); if (!n) return;
    try { localStorage.setItem('arcade_nick', n); } catch {}
    const g = gRef.current;
    g.grid = new Int32Array(G * G); g.trail = new Int32Array(G * G); g.players = []; g.acc = 0; g.last = 0;
    const spots = [[8, 8], [G - 9, 8], [8, G - 9], [G - 9, G - 9], [Math.floor(G / 2), 6]];
    for (let i = 0; i <= BOT_N; i++) {
      const [sx, sy] = spots[i % spots.length];
      const p: P = { id: i + 1, cx: sx, cy: sy, dir: 1, nextDir: 1, alive: true, name: i === 0 ? n.slice(0, 16) : '봇' + i, bot: i !== 0, sx, sy, out: 0, respawnAt: 0 };
      claimBlock(g.grid, sx, sy, p.id);
      g.players.push(p);
    }
    g.me = g.players[0];
    setScore(9); setMyRank(null);
    setPhase('playing');
  };

  const killPlayer = (g: typeof gRef.current, p: P) => {
    for (let i = 0; i < g.trail.length; i++) if (g.trail[i] === p.id) g.trail[i] = 0;
    for (let i = 0; i < g.grid.length; i++) if (g.grid[i] === p.id) g.grid[i] = 0;
    p.alive = false;
    if (p === g.me) { endGame(countCellsPeak.current); return; }
    p.respawnAt = performance.now() + 2500;
  };
  const countCellsPeak = useRef(0);

  // 트레일 회수 + 내부 채우기(밖에서 도달 못하는 칸 = 내 땅)
  const capture = (g: typeof gRef.current, id: number) => {
    const grid = g.grid, trail = g.trail;
    for (let i = 0; i < trail.length; i++) if (trail[i] === id) { grid[i] = id; trail[i] = 0; }
    // 경계에서 flood fill, id가 아닌 칸 중 도달 가능 표시
    const reach = new Uint8Array(G * G);
    const stack: number[] = [];
    for (let x = 0; x < G; x++) { for (const y of [0, G - 1]) { const c = at(x, y); if (grid[c] !== id && !reach[c]) { reach[c] = 1; stack.push(c); } } }
    for (let y = 0; y < G; y++) { for (const x of [0, G - 1]) { const c = at(x, y); if (grid[c] !== id && !reach[c]) { reach[c] = 1; stack.push(c); } } }
    while (stack.length) {
      const c = stack.pop()!; const x = c % G, y = (c / G) | 0;
      for (const d of DIRS) { const nx = x + d.x, ny = y + d.y; if (inb(nx, ny)) { const nc = at(nx, ny); if (grid[nc] !== id && !reach[nc]) { reach[nc] = 1; stack.push(nc); } } }
    }
    for (let i = 0; i < grid.length; i++) if (grid[i] !== id && !reach[i]) grid[i] = id;
  };

  const gridStep = (g: typeof gRef.current) => {
    // 봇 AI
    for (const p of g.players) {
      if (!p.alive || !p.bot) continue;
      const safe = (dir: number) => { const nx = p.cx + DIRS[dir].x, ny = p.cy + DIRS[dir].y; if (!inb(nx, ny)) return false; if (g.trail[at(nx, ny)] === p.id) return false; return true; };
      // 트레일이 길면 집으로
      if (p.out > 5) {
        const towardX = p.sx - p.cx, towardY = p.sy - p.cy;
        const pref = Math.abs(towardX) > Math.abs(towardY) ? (towardX > 0 ? 1 : 3) : (towardY > 0 ? 2 : 0);
        if (safe(pref) && pref !== (p.dir + 2) % 4) p.nextDir = pref;
        else { for (let d = 0; d < 4; d++) if (safe(d) && d !== (p.dir + 2) % 4) { p.nextDir = d; break; } }
      } else {
        if (!safe(p.dir) || Math.random() < 0.15) { const opts = [0, 1, 2, 3].filter((d) => safe(d) && d !== (p.dir + 2) % 4); if (opts.length) p.nextDir = opts[Math.floor(Math.random() * opts.length)]; }
      }
    }
    // 이동
    for (const p of g.players) {
      if (!p.alive) continue;
      if (p.nextDir !== (p.dir + 2) % 4) p.dir = p.nextDir; // 180도 금지
      const nx = p.cx + DIRS[p.dir].x, ny = p.cy + DIRS[p.dir].y;
      if (!inb(nx, ny)) { killPlayer(g, p); continue; }
      const c = at(nx, ny);
      const tr = g.trail[c];
      if (tr !== 0) {
        if (tr === p.id) { killPlayer(g, p); continue; }       // 자기 트레일 → 사망
        else { const victim = g.players.find((q) => q.id === tr); if (victim) killPlayer(g, victim); } // 남 트레일 끊음 → 그 사람 사망
      }
      p.cx = nx; p.cy = ny;
      if (g.grid[c] === p.id) {
        // 내 땅 복귀 → 트레일 있으면 회수
        let has = false; for (let i = 0; i < g.trail.length; i++) if (g.trail[i] === p.id) { has = true; break; }
        if (has) capture(g, p.id);
        p.out = 0;
      } else {
        g.trail[c] = p.id; p.out++;
      }
    }
    // 봇 리스폰
    for (const p of g.players) if (p.bot && !p.alive && p.respawnAt > 0 && performance.now() > p.respawnAt) { p.alive = true; p.out = 0; p.dir = 1; p.nextDir = 1; p.cx = p.sx; p.cy = p.sy; claimBlock(g.grid, p.sx, p.sy, p.id); }
    // 내 점수
    if (g.me && g.me.alive) { const sc = countCells(g.grid, g.me.id); countCellsPeak.current = sc; setScore(sc); }
  };

  useEffect(() => {
    if (phase !== 'playing') return;
    const canvas = canvasRef.current!; const ctx = canvas.getContext('2d')!;
    const g = gRef.current; const cell = canvas.width / G;

    const loop = (t: number) => {
      if (!g.last) g.last = t;
      g.acc += t - g.last; g.last = t;
      while (g.acc >= STEP_MS) { gridStep(g); g.acc -= STEP_MS; if (phaseRef.current !== 'playing') return; }
      // 키 입력 → 내 방향
      if (g.me && g.me.alive) {
        const k = keyRef.current;
        if (k.has('ArrowUp') || k.has('w')) g.me.nextDir = 0;
        else if (k.has('ArrowRight') || k.has('d')) g.me.nextDir = 1;
        else if (k.has('ArrowDown') || k.has('s')) g.me.nextDir = 2;
        else if (k.has('ArrowLeft') || k.has('a')) g.me.nextDir = 3;
      }
      // 렌더
      ctx.fillStyle = '#f8fafc'; ctx.fillRect(0, 0, canvas.width, canvas.height);
      for (let y = 0; y < G; y++) for (let x = 0; x < G; x++) {
        const owner = g.grid[at(x, y)]; const tr = g.trail[at(x, y)];
        if (tr) { ctx.fillStyle = SOLID[((tr - 1) % (SOLID.length - 1)) + 1] + '99'; ctx.fillRect(x * cell, y * cell, cell, cell); }
        else if (owner) { ctx.fillStyle = FILL[((owner - 1) % (FILL.length - 1)) + 1]; ctx.fillRect(x * cell, y * cell, cell, cell); }
      }
      for (const p of g.players) {
        if (!p.alive) continue;
        ctx.fillStyle = SOLID[((p.id - 1) % (SOLID.length - 1)) + 1];
        ctx.fillRect(p.cx * cell, p.cy * cell, cell, cell);
        ctx.strokeStyle = '#111827'; ctx.lineWidth = 1.5; ctx.strokeRect(p.cx * cell + 1, p.cy * cell + 1, cell - 2, cell - 2);
      }
      g.raf = requestAnimationFrame(loop);
    };
    g.raf = requestAnimationFrame(loop);
    return () => cancelAnimationFrame(g.raf);
  }, [phase]);

  useEffect(() => {
    const kd = (e: KeyboardEvent) => { const k = e.key.length === 1 ? e.key.toLowerCase() : e.key; if (['w', 'a', 's', 'd', 'ArrowUp', 'ArrowDown', 'ArrowLeft', 'ArrowRight'].includes(k)) { keyRef.current.clear(); keyRef.current.add(k); e.preventDefault(); } };
    const ku = (e: KeyboardEvent) => { const k = e.key.length === 1 ? e.key.toLowerCase() : e.key; keyRef.current.delete(k); };
    window.addEventListener('keydown', kd); window.addEventListener('keyup', ku);
    return () => { window.removeEventListener('keydown', kd); window.removeEventListener('keyup', ku); };
  }, []);

  const dpad = (dir: number) => () => { if (gRef.current.me) gRef.current.me.nextDir = dir; };

  return (
    <main className="min-h-screen flex flex-col items-center p-4 max-w-xl mx-auto w-full">
      <div className="w-full flex items-center justify-between mb-3">
        <h1 className="text-xl font-bold">🗺️ 땅따먹기</h1>
        {phase === 'playing' && <span className="text-sm font-bold text-hit">내 땅 {score}칸 ({Math.round(score / (G * G) * 100)}%)</span>}
      </div>

      {phase === 'playing' ? (
        <>
          <canvas ref={canvasRef} width={660} height={660} className="w-full rounded-xl border border-gray-200 touch-none" style={{ aspectRatio: '1/1' }} />
          <div className="grid grid-cols-3 gap-1 w-40 mt-3 sm:hidden select-none">
            <div /><button onTouchStart={dpad(0)} className="bg-gray-100 rounded-lg py-3 font-bold">▲</button><div />
            <button onTouchStart={dpad(3)} className="bg-gray-100 rounded-lg py-3 font-bold">◀</button>
            <button onTouchStart={dpad(2)} className="bg-gray-100 rounded-lg py-3 font-bold">▼</button>
            <button onTouchStart={dpad(1)} className="bg-gray-100 rounded-lg py-3 font-bold">▶</button>
          </div>
          <p className="text-xs text-gray-400 mt-2 text-center">WASD/방향키로 이동 · 내 땅 밖으로 나가 선을 긋고 돌아오면 안쪽이 내 땅! 선이 끊기면 죽어요.</p>
        </>
      ) : (
        <div className="w-full space-y-4">
          {phase === 'over' && (
            <div className="text-center rounded-xl border-2 border-hit p-4 space-y-1">
              <p className="text-lg font-bold">게임 오버</p>
              <p className="text-3xl font-extrabold text-hit">{finalScore}칸</p>
              {myRank != null && <p className="text-sm text-gray-500">내 순위: {myRank}위</p>}
            </div>
          )}
          <input value={nick} onChange={(e) => setNick(e.target.value)} maxLength={16} placeholder="닉네임"
            className="w-full border border-gray-300 rounded-lg px-3 py-2 focus:outline-none focus:border-hit" />
          <button onClick={start} disabled={!nick.trim()} className="w-full bg-hit text-white font-bold py-3 rounded-lg disabled:opacity-40">
            {phase === 'over' ? '다시 하기' : '시작하기'}
          </button>
          <p className="text-center text-xs text-gray-400">땅 밖으로 나가 영역을 그리고 돌아오면 안쪽이 내 땅이 돼요. 봇과 땅 넓히기 경쟁!</p>
          <Leaderboard game="territory" refreshKey={refreshKey} highlight={nick.trim()} />
        </div>
      )}
    </main>
  );
}
