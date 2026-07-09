'use client';

import { useCallback, useEffect, useRef, useState } from 'react';
import { api } from '@/lib/api';
import Leaderboard from '@/components/Leaderboard';

const W = 900;                 // 월드 크기(정사각)
const BASE_R = 7;              // 기본 반지름
const SPEED = 2.6;             // 전진 속도(px/frame)
const TURN = 0.14;             // 프레임당 최대 회전(rad)
const SEG_GAP = 4;             // 세그먼트 간 경로 포인트 간격
const START_SEG = 12;
const FOOD_N = 90;
const BOT_N = 6;
const COLORS = ['#22c55e', '#3b82f6', '#f59e0b', '#a855f7', '#ec4899', '#14b8a6', '#ef4444'];

type Vec = { x: number; y: number };
type Snake = {
  path: Vec[]; segs: number; angle: number; target: number;
  r: number; color: string; alive: boolean; bot: boolean; name: string;
  score: number; respawnAt: number; invulnUntil: number;
};
type Food = { x: number; y: number; r: number; c: string };
type Obstacle = { x: number; y: number; r: number };

function rand(a: number, b: number) { return a + Math.random() * (b - a); }
function head(s: Snake) { return s.path[0]; }
function body(s: Snake): Vec[] {
  const out: Vec[] = [];
  for (let i = 0; i < s.segs; i++) { const idx = i * SEG_GAP; if (idx < s.path.length) out.push(s.path[idx]); }
  return out;
}

export default function SnakePage() {
  const canvasRef = useRef<HTMLCanvasElement>(null);
  const [phase, setPhase] = useState<'ready' | 'playing' | 'over'>('ready');
  const [nick, setNick] = useState('');
  const [score, setScore] = useState(0);
  const [finalScore, setFinalScore] = useState(0);
  const [myRank, setMyRank] = useState<number | null>(null);
  const [refreshKey, setRefreshKey] = useState(0);

  const gameRef = useRef<{ snakes: Snake[]; foods: Food[]; obstacles: Obstacle[]; me: Snake | null; raf: number }>({ snakes: [], foods: [], obstacles: [], me: null, raf: 0 });
  const inputRef = useRef<{ mouse: Vec | null; keys: Set<string> }>({ mouse: null, keys: new Set() });
  const phaseRef = useRef(phase);
  phaseRef.current = phase;

  useEffect(() => { try { setNick(localStorage.getItem('arcade_nick') || ''); } catch {} }, []);

  const spawnFood = (foods: Food[]) => {
    foods.push({ x: rand(20, W - 20), y: rand(20, W - 20), r: rand(4, 6), c: COLORS[Math.floor(Math.random() * COLORS.length)] });
  };
  const newSnake = (name: string, bot: boolean, color: string, pos?: Vec, invulnMs = 0): Snake => {
    const x = pos ? pos.x : rand(150, W - 150), y = pos ? pos.y : rand(150, W - 150);
    const angle = rand(0, Math.PI * 2);
    const path: Vec[] = [];
    for (let i = 0; i < START_SEG * SEG_GAP + 5; i++) path.push({ x: x - Math.cos(angle) * i, y: y - Math.sin(angle) * i });
    return { path, segs: START_SEG, angle, target: angle, r: BASE_R, color, alive: true, bot, name, score: 0, respawnAt: 0, invulnUntil: performance.now() + invulnMs };
  };

  // 플레이어·장애물에서 충분히 떨어진 스폰 위치(스폰 즉사 방지)
  const safePos = (g: typeof gameRef.current): Vec => {
    for (let t = 0; t < 30; t++) {
      const p = { x: rand(150, W - 150), y: rand(150, W - 150) };
      let ok = true;
      if (g.me && g.me.alive) for (const b of body(g.me)) if ((b.x - p.x) ** 2 + (b.y - p.y) ** 2 < 150 ** 2) { ok = false; break; }
      if (ok) for (const o of g.obstacles) if ((o.x - p.x) ** 2 + (o.y - p.y) ** 2 < (o.r + 50) ** 2) { ok = false; break; }
      if (ok) return p;
    }
    return { x: rand(150, W - 150), y: rand(150, W - 150) };
  };

  const submitScore = useCallback(async (sc: number) => {
    const n = (nick.trim() || '익명').slice(0, 16);
    try {
      const res = await api<{ myRank: number }>(`/api/v1/scores/snake`, { method: 'POST', body: JSON.stringify({ nick: n, score: sc }) });
      setMyRank(res.myRank);
    } catch {}
    setRefreshKey((k) => k + 1);
  }, [nick]);

  const endGame = useCallback((sc: number) => {
    setFinalScore(sc);
    setPhase('over');
    submitScore(sc);
  }, [submitScore]);

  const start = () => {
    const n = nick.trim();
    if (!n) return;
    try { localStorage.setItem('arcade_nick', n); } catch {}
    const g = gameRef.current;
    g.foods = []; for (let i = 0; i < FOOD_N; i++) spawnFood(g.foods);
    // 플레이어 먼저(중앙 시작) + 시작 5초 무적
    g.me = newSnake(n.slice(0, 16), false, '#111827', { x: W / 2, y: W / 2 }, 5000);
    g.snakes = [g.me];
    // 장애물은 플레이어 시작 위치를 피해 배치
    g.obstacles = [];
    for (let i = 0; i < 5; i++) {
      let p = { x: rand(150, W - 150), y: rand(150, W - 150) };
      const hh = head(g.me);
      for (let t = 0; t < 20; t++) { p = { x: rand(150, W - 150), y: rand(150, W - 150) }; if ((p.x - hh.x) ** 2 + (p.y - hh.y) ** 2 > 180 ** 2) break; }
      g.obstacles.push({ x: p.x, y: p.y, r: rand(24, 44) });
    }
    // 봇은 플레이어에게서 떨어진 안전 위치 + 2초 무적
    for (let i = 0; i < BOT_N; i++) g.snakes.push(newSnake('봇' + (i + 1), true, COLORS[i % COLORS.length], safePos(g), 2000));
    setScore(0); setMyRank(null);
    setPhase('playing');
  };

  // 게임 루프
  useEffect(() => {
    if (phase !== 'playing') return;
    const canvas = canvasRef.current!;
    const ctx = canvas.getContext('2d')!;
    const g = gameRef.current;

    const worldMouse = (): Vec | null => {
      const m = inputRef.current.mouse; if (!m) return null;
      const rect = canvas.getBoundingClientRect();
      return { x: (m.x - rect.left) * (W / rect.width), y: (m.y - rect.top) * (W / rect.height) };
    };

    const step = () => {
      // 입력 → 내 목표 각도
      if (g.me && g.me.alive) {
        const keys = inputRef.current.keys;
        let kx = 0, ky = 0;
        if (keys.has('ArrowUp') || keys.has('w')) ky -= 1;
        if (keys.has('ArrowDown') || keys.has('s')) ky += 1;
        if (keys.has('ArrowLeft') || keys.has('a')) kx -= 1;
        if (keys.has('ArrowRight') || keys.has('d')) kx += 1;
        if (kx || ky) g.me.target = Math.atan2(ky, kx);
        else { const wm = worldMouse(); if (wm) { const h = head(g.me); if (Math.hypot(wm.x - h.x, wm.y - h.y) > 4) g.me.target = Math.atan2(wm.y - h.y, wm.x - h.x); } }
      }
      // 봇 AI
      for (const s of g.snakes) {
        if (!s.alive || !s.bot) continue;
        const h = head(s);
        // 가장 가까운 먹이
        let best: Food | null = null, bd = 1e9;
        for (const f of g.foods) { const d = (f.x - h.x) ** 2 + (f.y - h.y) ** 2; if (d < bd) { bd = d; best = f; } }
        if (best) s.target = Math.atan2(best.y - h.y, best.x - h.x);
        // 벽 회피
        const margin = 60;
        if (h.x < margin) s.target = 0; else if (h.x > W - margin) s.target = Math.PI;
        if (h.y < margin) s.target = Math.PI / 2; else if (h.y > W - margin) s.target = -Math.PI / 2;
        if (Math.random() < 0.02) s.target += rand(-0.6, 0.6);
      }
      // 이동
      for (const s of g.snakes) {
        if (!s.alive) continue;
        let d = s.target - s.angle;
        while (d > Math.PI) d -= Math.PI * 2; while (d < -Math.PI) d += Math.PI * 2;
        s.angle += Math.max(-TURN, Math.min(TURN, d));
        const h = head(s);
        const nh = { x: h.x + Math.cos(s.angle) * SPEED, y: h.y + Math.sin(s.angle) * SPEED };
        s.path.unshift(nh);
        const maxLen = s.segs * SEG_GAP + 6;
        if (s.path.length > maxLen) s.path.length = maxLen;
        s.r = BASE_R + Math.min(6, s.segs / 40);
      }
      // 먹이 섭취(머리·몸통·꼬리 어디든 닿으면 먹음 → 몸 밑에 끼어 안 사라지는 문제 해결)
      for (const s of g.snakes) {
        if (!s.alive) continue;
        const parts = body(s);
        for (let i = g.foods.length - 1; i >= 0; i--) {
          const f = g.foods[i];
          const rr = s.r + f.r;
          for (let j = 0; j < parts.length; j++) {
            const p = parts[j];
            if ((f.x - p.x) ** 2 + (f.y - p.y) ** 2 < rr * rr) {
              g.foods.splice(i, 1); s.segs = Math.min(500, s.segs + 3); s.score += 1;
              if (s === g.me) setScore(s.score);
              spawnFood(g.foods);
              break;
            }
          }
        }
      }
      // 충돌 판정(머리 → 벽/장애물/다른 뱀 몸/자기 몸)
      const nowT = performance.now();
      for (const s of g.snakes) {
        if (!s.alive) continue;
        if (nowT < s.invulnUntil) continue;   // 무적 동안엔 죽지 않음
        const h = head(s);
        let dead = false;
        let killer: Snake | null = null;
        if (h.x < 0 || h.x > W || h.y < 0 || h.y > W) dead = true;
        if (!dead) for (const o of g.obstacles) if ((o.x - h.x) ** 2 + (o.y - h.y) ** 2 < (o.r + s.r) ** 2) { dead = true; break; }
        if (!dead) for (const other of g.snakes) {
          if (!other.alive) continue;
          const segs = body(other);
          const startI = other === s ? 8 : 0; // 자기 몸은 앞부분 제외
          for (let i = startI; i < segs.length; i++) {
            const b = segs[i];
            if ((b.x - h.x) ** 2 + (b.y - h.y) ** 2 < (other.r + s.r) ** 2 * 0.7) { dead = true; killer = other === s ? null : other; break; }
          }
          if (dead) break;
        }
        if (dead) {
          s.alive = false;
          for (const b of body(s)) if (Math.random() < 0.5) g.foods.push({ x: b.x, y: b.y, r: 5, c: s.color });
          if (killer && killer.alive && killer !== s) {   // 내 몸에 부딪혀 죽은 경우 킬 보너스 +10
            killer.score += 10;
            if (killer === g.me) setScore(killer.score);
          }
          if (s === g.me) { endGame(s.score); return; }
          else s.respawnAt = performance.now() + 2500;
        }
      }
      // 봇 리스폰(플레이어에게서 떨어진 안전 위치 + 2초 무적)
      for (const s of g.snakes) {
        if (s.bot && !s.alive && performance.now() > s.respawnAt && s.respawnAt > 0) {
          const ns = newSnake(s.name, true, s.color, safePos(g), 2000);
          Object.assign(s, ns);
        }
      }

      // 렌더
      ctx.fillStyle = '#f8fafc'; ctx.fillRect(0, 0, W, W);
      ctx.strokeStyle = '#e2e8f0'; ctx.lineWidth = 6; ctx.strokeRect(3, 3, W - 6, W - 6);
      for (const o of g.obstacles) { ctx.fillStyle = '#94a3b8'; ctx.beginPath(); ctx.arc(o.x, o.y, o.r, 0, Math.PI * 2); ctx.fill(); }
      for (const f of g.foods) { ctx.fillStyle = f.c; ctx.beginPath(); ctx.arc(f.x, f.y, f.r, 0, Math.PI * 2); ctx.fill(); }
      for (const s of g.snakes) {
        if (!s.alive) continue;
        const inv = performance.now() < s.invulnUntil;
        const segs = body(s);
        const h = head(s);
        ctx.save();
        if (inv) ctx.globalAlpha = 0.45 + 0.35 * Math.abs(Math.sin(performance.now() / 120));
        ctx.fillStyle = s.color;
        for (let i = segs.length - 1; i >= 0; i--) { ctx.beginPath(); ctx.arc(segs[i].x, segs[i].y, s.r, 0, Math.PI * 2); ctx.fill(); }
        ctx.fillStyle = '#fff'; ctx.beginPath(); ctx.arc(h.x + Math.cos(s.angle) * 2, h.y + Math.sin(s.angle) * 2, s.r * 0.4, 0, Math.PI * 2); ctx.fill();
        ctx.restore();
        if (inv) { ctx.strokeStyle = '#fbbf24'; ctx.lineWidth = 3; ctx.beginPath(); ctx.arc(h.x, h.y, s.r + 4, 0, Math.PI * 2); ctx.stroke(); }
        ctx.fillStyle = '#334155'; ctx.font = '12px sans-serif'; ctx.textAlign = 'center';
        ctx.fillText(s.name, h.x, h.y - s.r - 4);
        if (inv && s === g.me) {
          ctx.fillStyle = '#f59e0b'; ctx.font = 'bold 12px sans-serif';
          ctx.fillText('🛡️ ' + Math.ceil((s.invulnUntil - performance.now()) / 1000) + 's', h.x, h.y - s.r - 18);
        }
      }
      g.raf = requestAnimationFrame(step);
    };
    g.raf = requestAnimationFrame(step);
    return () => cancelAnimationFrame(g.raf);
  }, [phase, endGame]);

  // 입력 리스너
  useEffect(() => {
    const onMove = (e: MouseEvent) => { inputRef.current.mouse = { x: e.clientX, y: e.clientY }; };
    const onTouch = (e: TouchEvent) => { if (e.touches[0]) inputRef.current.mouse = { x: e.touches[0].clientX, y: e.touches[0].clientY }; };
    const onKeyDown = (e: KeyboardEvent) => { const k = e.key.length === 1 ? e.key.toLowerCase() : e.key; if (['w', 'a', 's', 'd', 'ArrowUp', 'ArrowDown', 'ArrowLeft', 'ArrowRight'].includes(k)) { inputRef.current.keys.add(k); e.preventDefault(); } };
    const onKeyUp = (e: KeyboardEvent) => { const k = e.key.length === 1 ? e.key.toLowerCase() : e.key; inputRef.current.keys.delete(k); };
    window.addEventListener('mousemove', onMove);
    window.addEventListener('touchmove', onTouch, { passive: true });
    window.addEventListener('keydown', onKeyDown);
    window.addEventListener('keyup', onKeyUp);
    return () => { window.removeEventListener('mousemove', onMove); window.removeEventListener('touchmove', onTouch); window.removeEventListener('keydown', onKeyDown); window.removeEventListener('keyup', onKeyUp); };
  }, []);

  return (
    <main className="min-h-screen flex flex-col items-center p-4 max-w-xl mx-auto w-full">
      <div className="w-full flex items-center justify-between mb-3">
        <h1 className="text-xl font-bold">🐍 지렁이</h1>
        {phase === 'playing' && <span className="text-sm font-bold text-hit">점수 {score}</span>}
        <a href="/" className="text-xs text-gray-400 underline">홈</a>
      </div>

      {phase === 'playing' ? (
        <>
          <canvas ref={canvasRef} width={W} height={W} className="w-full rounded-xl border border-gray-200 bg-slate-50 touch-none" style={{ aspectRatio: '1/1' }} />
          <p className="text-xs text-gray-400 mt-2 text-center">마우스/WASD로 조준 · 벽·장애물·몸에 닿으면 끝! · 🛡️ 시작 5초 무적 · 상대를 잡으면 +10점</p>
        </>
      ) : (
        <div className="w-full space-y-4">
          {phase === 'over' && (
            <div className="text-center rounded-xl border-2 border-hit p-4 space-y-1">
              <p className="text-lg font-bold">게임 오버</p>
              <p className="text-3xl font-extrabold text-hit">{finalScore}점</p>
              {myRank != null && <p className="text-sm text-gray-500">내 순위: {myRank}위</p>}
            </div>
          )}
          <input value={nick} onChange={(e) => setNick(e.target.value)} maxLength={16} placeholder="닉네임"
            className="w-full border border-gray-300 rounded-lg px-3 py-2 focus:outline-none focus:border-hit" />
          <button onClick={start} disabled={!nick.trim()} className="w-full bg-hit text-white font-bold py-3 rounded-lg disabled:opacity-40">
            {phase === 'over' ? '다시 하기' : '시작하기'}
          </button>
          <p className="text-center text-xs text-gray-400">먹이를 먹고 지렁이를 키우세요. 봇과 경쟁! 마우스 또는 WASD/방향키.</p>
          <Leaderboard game="snake" refreshKey={refreshKey} highlight={nick.trim()} />
        </div>
      )}
    </main>
  );
}
