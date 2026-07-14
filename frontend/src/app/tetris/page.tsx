'use client';

import { useCallback, useEffect, useRef, useState } from 'react';
import { api } from '@/lib/api';
import { PieceType, STATES } from './srs';
import {
  TetrisEngine, gravityMs, COLS, VIS_ROWS, HIDDEN, ROWS, SPRINT_GOAL,
  Mode, LockResult,
} from './engine';

const CELL = 30;                 // 논리 셀 크기(px). devicePixelRatio로 실제 해상도 보정
const LOCK_DELAY = 500;          // 바닥 접촉 후 고정까지 여유(ms)
const MAX_RESETS = 15;           // 락딜레이 무한 리셋 방지
const DAS = 133;                 // 좌우 자동이동 시작 지연(ms)
const ARR = 25;                  // 자동이동 반복 간격(ms)
const SOFT_MS = 28;              // 소프트드롭 1칸 간격(ms)

const COLORS: Record<PieceType | 'G', string> = {
  I: '#22d3ee', O: '#facc15', T: '#c084fc', S: '#4ade80', Z: '#f87171', J: '#60a5fa', L: '#fb923c',
  G: '#64748b', // 가비지(방해) 블록
};
const SPRINT_KEY_BASE = 10_000_000; // 스프린트 랭킹: 저장점수 = BASE - 소요ms (클수록 빠름)

type Phase = 'ready' | 'playing' | 'paused' | 'over';
type FloatText = { text: string; sub?: string; id: number };

// ── 홀드/넥스트 미니 블록 ───────────────────────────────
function MiniPiece({ type }: { type: PieceType | null }) {
  return (
    <div className="grid" style={{ gridTemplateColumns: 'repeat(4, 1fr)', width: 64, height: 64, gap: 1 }}>
      {Array.from({ length: 16 }).map((_, i) => {
        const r = Math.floor(i / 4), c = i % 4;
        const on = type && STATES[type][0].some(([rr, cc]) => rr === r && cc === c);
        return <div key={i} className="rounded-[2px]" style={{ background: on ? COLORS[type!] : 'transparent' }} />;
      })}
    </div>
  );
}

export default function TetrisPage() {
  const canvasRef = useRef<HTMLCanvasElement>(null);
  const [phase, setPhase] = useState<Phase>('ready');
  const [mode, setMode] = useState<Mode>('marathon');
  const [nick, setNick] = useState('');
  const [showGuide, setShowGuide] = useState(false);

  // 표시용 상태
  const [score, setScore] = useState(0);
  const [lines, setLines] = useState(0);
  const [level, setLevel] = useState(1);
  const [elapsed, setElapsed] = useState(0);
  const [hold, setHold] = useState<PieceType | null>(null);
  const [nextQ, setNextQ] = useState<PieceType[]>([]);
  const [float, setFloat] = useState<FloatText | null>(null);
  const [shake, setShake] = useState(0);

  // 결과
  const [result, setResult] = useState<{ score: number; lines: number; timeMs: number; cleared40: boolean } | null>(null);
  const [myRank, setMyRank] = useState<number | null>(null);
  const [submittedNick, setSubmittedNick] = useState('');
  const [refreshKey, setRefreshKey] = useState(0);

  const engineRef = useRef<TetrisEngine | null>(null);
  const loopRef = useRef<any>({});
  const phaseRef = useRef<Phase>(phase);
  const modeRef = useRef<Mode>(mode);
  const gameNickRef = useRef(''); // 게임 시작 시점에 확정된 닉(클로저/타이밍 무관하게 이 값으로 등록)
  phaseRef.current = phase;
  modeRef.current = mode;

  useEffect(() => { try { setNick(localStorage.getItem('arcade_nick') || ''); } catch {} }, []);

  // ── 상태 동기화 (값이 바뀔 때만 setState → 매 프레임 리렌더로 인한 버벅임 방지) ──
  const syncCache = useRef({ score: -1, lines: -1, level: -1, hold: undefined as PieceType | null | undefined, nextKey: '' });
  const sync = useCallback(() => {
    const e = engineRef.current; if (!e) return;
    const c = syncCache.current;
    if (e.score !== c.score) { c.score = e.score; setScore(e.score); }
    if (e.lines !== c.lines) { c.lines = e.lines; setLines(e.lines); }
    if (e.level !== c.level) { c.level = e.level; setLevel(e.level); }
    if (e.hold !== c.hold) { c.hold = e.hold; setHold(e.hold); }
    const nk = e.nextQueue(5).join(',');
    if (nk !== c.nextKey) { c.nextKey = nk; setNextQ(e.nextQueue(5)); }
  }, []);

  const showFloat = (text: string, sub?: string) => {
    setFloat({ text, sub, id: Date.now() + Math.random() });
  };

  // ── 락 결과 처리 ───────────────────────────────────────
  const applyLock = useCallback((res: LockResult) => {
    sync();
    if (res.linesCleared > 0 || res.tspin !== 'none') {
      const names = ['', 'SINGLE', 'DOUBLE', 'TRIPLE'];
      let text = '';
      if (res.tspin === 'full') text = 'T-SPIN ' + (names[res.linesCleared] || '');
      else if (res.tspin === 'mini') text = 'T-SPIN MINI ' + (names[res.linesCleared] || '');
      else if (res.linesCleared === 4) text = 'TETRIS!';
      else text = names[res.linesCleared] || '';
      if (res.perfectClear) text = 'PERFECT CLEAR!';
      const subs: string[] = [];
      if (res.b2b) subs.push('BACK-TO-BACK');
      if (res.combo > 0) subs.push(`${res.combo} COMBO`);
      showFloat(text.trim(), subs.join(' · ') || undefined);
      if (res.linesCleared === 4 || res.tspin === 'full' || res.perfectClear) setShake(Date.now());
    }
    if (res.gameOver) endGame(res.goalReached);
  }, [sync]); // eslint-disable-line react-hooks/exhaustive-deps

  const submit = useCallback(async (m: Mode, sc: number, timeMs: number, cleared40: boolean) => {
    let raw = gameNickRef.current.trim();
    if (!raw) { try { raw = (localStorage.getItem('arcade_nick') || '').trim(); } catch {} } // 폴백
    const n = (raw || '익명').slice(0, 16);
    setSubmittedNick(n); // 게임오버 화면에 실제 등록된 닉 표시(진단 겸 UX)
    try {
      if (m === 'marathon') {
        const r = await api<{ myRank: number }>(`/api/v1/scores/tetris`, { method: 'POST', body: JSON.stringify({ nick: n, score: sc }) });
        setMyRank(r.myRank);
      } else if (cleared40) {
        const stored = Math.max(1, SPRINT_KEY_BASE - Math.round(timeMs));
        const r = await api<{ myRank: number }>(`/api/v1/scores/tetris_sprint`, { method: 'POST', body: JSON.stringify({ nick: n, score: stored }) });
        setMyRank(r.myRank);
      }
    } catch {}
    setRefreshKey((k) => k + 1);
  }, []);

  const endGame = useCallback((goalReached: boolean) => {
    const e = engineRef.current; if (!e) return;
    const l = loopRef.current;
    if (l.raf) cancelAnimationFrame(l.raf);
    const timeMs = l.startTime ? performance.now() - l.startTime : 0;
    const m = modeRef.current;
    const cleared40 = m === 'sprint' && goalReached;
    setResult({ score: e.score, lines: e.lines, timeMs, cleared40 });
    setMyRank(null);
    setPhase('over');
    submit(m, e.score, timeMs, cleared40);
  }, [submit]);

  // ── 시작 ───────────────────────────────────────────────
  const start = () => {
    const n = nick.trim(); if (!n) return;
    gameNickRef.current = n; // 이 판의 닉을 확정 저장
    try { localStorage.setItem('arcade_nick', n); } catch {}
    const e = new TetrisEngine(mode);
    engineRef.current = e;
    loopRef.current = {
      last: performance.now(), startTime: performance.now(),
      grav: 0, rest: 0, resets: 0, resting: false,
      dir: 0, dasT: 0, dasCharged: false, arr: 0, softAcc: 0, raf: 0,
    };
    syncCache.current = { score: -1, lines: -1, level: -1, hold: undefined, nextKey: '' };
    setResult(null); setMyRank(null); setElapsed(0); setFloat(null);
    sync();
    setPhase('playing');
  };

  // ── 조작 액션 ──────────────────────────────────────────
  const act = useCallback((fn: (e: TetrisEngine) => void) => {
    const e = engineRef.current; if (!e || phaseRef.current !== 'playing') return;
    fn(e);
    // 이동/회전으로 접지 상태가 풀리거나 재조정되면 락딜레이 리셋
    const l = loopRef.current;
    if (e.isResting()) { l.rest = 0; l.resets = Math.min(MAX_RESETS, l.resets + 1); }
    sync();
  }, [sync]);

  const doHardDrop = useCallback(() => {
    const e = engineRef.current; if (!e || phaseRef.current !== 'playing') return;
    const res = e.hardDrop();
    loopRef.current.rest = 0; loopRef.current.resets = 0; loopRef.current.resting = false;
    applyLock(res);
  }, [applyLock]);

  const doHold = useCallback(() => {
    const e = engineRef.current; if (!e || phaseRef.current !== 'playing') return;
    if (e.holdPiece()) { const l = loopRef.current; l.rest = 0; l.resets = 0; sync(); }
  }, [sync]);

  const togglePause = useCallback(() => {
    setPhase((p) => (p === 'playing' ? 'paused' : p === 'paused' ? 'playing' : p));
  }, []);

  // ── 게임 루프 ──────────────────────────────────────────
  useEffect(() => {
    if (phase !== 'playing') return;
    const e = engineRef.current!; const l = loopRef.current;
    l.last = performance.now();

    const step = (now: number) => {
      const dt = Math.min(64, now - l.last); l.last = now;

      // 좌우 DAS/ARR
      if (l.dir !== 0) {
        l.dasT += dt;
        if (!l.dasCharged && l.dasT >= DAS) { l.dasCharged = true; l.arr = 0; }
        if (l.dasCharged) { l.arr += dt; while (l.arr >= ARR) { if (e.move(l.dir)) { l.rest = 0; l.resets = Math.min(MAX_RESETS, l.resets + 1); } l.arr -= ARR; } }
      }
      // 소프트드롭
      if (l.soft) {
        l.softAcc += dt;
        while (l.softAcc >= SOFT_MS) { if (e.softDrop()) { l.rest = 0; l.resets = Math.min(MAX_RESETS, l.resets + 1); } l.softAcc -= SOFT_MS; }
      }
      // 중력
      l.grav += dt;
      const g = gravityMs(e.level);
      while (l.grav >= g) { l.grav -= g; if (!e.moveDown()) break; }

      // 락딜레이
      if (e.isResting()) {
        l.rest += dt;
        if (l.rest >= LOCK_DELAY || l.resets >= MAX_RESETS) {
          const res = e.lock();
          l.rest = 0; l.resets = 0;
          applyLock(res);
          if (e.over) return;
        }
      } else { l.rest = 0; }

      sync();
      draw();
      if (!e.over) l.raf = requestAnimationFrame(step);
    };

    const draw = () => {
      const canvas = canvasRef.current; if (!canvas) return;
      const ctx = canvas.getContext('2d')!;
      const dpr = window.devicePixelRatio || 1;
      const w = COLS * CELL, h = VIS_ROWS * CELL;
      if (canvas.width !== w * dpr || canvas.height !== h * dpr) { canvas.width = w * dpr; canvas.height = h * dpr; }
      ctx.setTransform(dpr, 0, 0, dpr, 0, 0);
      // 배경 + 그리드
      ctx.fillStyle = '#0f172a'; ctx.fillRect(0, 0, w, h);
      ctx.strokeStyle = 'rgba(148,163,184,0.10)'; ctx.lineWidth = 1;
      for (let c = 1; c < COLS; c++) { ctx.beginPath(); ctx.moveTo(c * CELL, 0); ctx.lineTo(c * CELL, h); ctx.stroke(); }
      for (let r = 1; r < VIS_ROWS; r++) { ctx.beginPath(); ctx.moveTo(0, r * CELL); ctx.lineTo(w, r * CELL); ctx.stroke(); }

      const cell = (r: number, c: number, color: string, alpha = 1, ghost = false) => {
        const y = (r - HIDDEN) * CELL; if (r < HIDDEN) return;
        ctx.globalAlpha = alpha;
        if (ghost) {
          ctx.strokeStyle = color; ctx.lineWidth = 2;
          ctx.strokeRect(c * CELL + 2, y + 2, CELL - 4, CELL - 4);
        } else {
          ctx.fillStyle = color; ctx.fillRect(c * CELL + 1, y + 1, CELL - 2, CELL - 2);
          ctx.fillStyle = 'rgba(255,255,255,0.18)'; ctx.fillRect(c * CELL + 1, y + 1, CELL - 2, 4);
        }
        ctx.globalAlpha = 1;
      };

      // 고정된 보드
      for (let r = 0; r < ROWS; r++) for (let c = 0; c < COLS; c++) {
        const v = e.board[r][c]; if (v) cell(r, c, COLORS[v]);
      }
      // 고스트
      const gh = e.ghost();
      if (gh) for (const [r, c] of e.cellsOf(gh)) cell(r, c, COLORS[gh.type], 0.9, true);
      // 현재 조각
      if (e.cur) for (const [r, c] of e.cellsOf(e.cur)) cell(r, c, COLORS[e.cur.type]);
    };

    draw();
    l.raf = requestAnimationFrame(step);
    return () => { if (l.raf) cancelAnimationFrame(l.raf); };
  }, [phase, applyLock, sync]);

  // ── 스프린트 타이머 ────────────────────────────────────
  useEffect(() => {
    if (phase !== 'playing') return;
    const id = setInterval(() => {
      const l = loopRef.current; if (l.startTime) setElapsed(performance.now() - l.startTime);
    }, 100);
    return () => clearInterval(id);
  }, [phase]);

  // ── 플로팅 텍스트 자동 소멸 ────────────────────────────
  useEffect(() => { if (!float) return; const id = setTimeout(() => setFloat(null), 900); return () => clearTimeout(id); }, [float]);

  // ── 탭 숨김 시 일시정지 ────────────────────────────────
  useEffect(() => {
    const onVis = () => { if (document.hidden) setPhase((p) => (p === 'playing' ? 'paused' : p)); };
    document.addEventListener('visibilitychange', onVis);
    return () => document.removeEventListener('visibilitychange', onVis);
  }, []);

  // ── 키보드 입력 ────────────────────────────────────────
  useEffect(() => {
    const down = (ev: KeyboardEvent) => {
      const k = ev.key;
      if (['ArrowLeft', 'ArrowRight', 'ArrowUp', 'ArrowDown', ' '].includes(k)) ev.preventDefault();
      if (phaseRef.current === 'paused' && (k === 'Escape' || k === 'p' || k === 'P')) { togglePause(); return; }
      if (phaseRef.current !== 'playing') return;
      const l = loopRef.current;
      switch (k) {
        case 'ArrowLeft': if (!ev.repeat) { act((e) => e.move(-1)); l.dir = -1; l.dasT = 0; l.dasCharged = false; } break;
        case 'ArrowRight': if (!ev.repeat) { act((e) => e.move(1)); l.dir = 1; l.dasT = 0; l.dasCharged = false; } break;
        case 'ArrowDown': l.soft = true; break;
        case 'ArrowUp': case 'x': case 'X': if (!ev.repeat) act((e) => e.rotate(1)); break;
        case 'z': case 'Z': case 'Control': if (!ev.repeat) act((e) => e.rotate(-1)); break;
        case ' ': if (!ev.repeat) doHardDrop(); break;
        case 'c': case 'C': case 'Shift': if (!ev.repeat) doHold(); break;
        case 'Escape': case 'p': case 'P': togglePause(); break;
      }
    };
    const up = (ev: KeyboardEvent) => {
      const l = loopRef.current;
      if (ev.key === 'ArrowLeft' && l.dir === -1) l.dir = 0;
      if (ev.key === 'ArrowRight' && l.dir === 1) l.dir = 0;
      if (ev.key === 'ArrowDown') l.soft = false;
    };
    window.addEventListener('keydown', down);
    window.addEventListener('keyup', up);
    return () => { window.removeEventListener('keydown', down); window.removeEventListener('keyup', up); };
  }, [act, doHardDrop, doHold, togglePause]);

  // ── 모바일 버튼 헬퍼 ───────────────────────────────────
  const pressDir = (d: -1 | 1) => { act((e) => e.move(d)); const l = loopRef.current; l.dir = d; l.dasT = 0; l.dasCharged = false; };
  const releaseDir = (d: -1 | 1) => { const l = loopRef.current; if (l.dir === d) l.dir = 0; };
  const pressSoft = () => { loopRef.current.soft = true; };
  const releaseSoft = () => { loopRef.current.soft = false; };

  const fmtTime = (ms: number) => {
    const s = ms / 1000; const m = Math.floor(s / 60); const rest = (s - m * 60);
    return (m > 0 ? `${m}:` : '') + rest.toFixed(2).padStart(m > 0 ? 5 : 4, '0');
  };

  const playing = phase === 'playing' || phase === 'paused';

  return (
    <main className="min-h-screen flex flex-col items-center p-3 max-w-3xl mx-auto w-full text-slate-100">
      <div className="w-full flex items-center justify-between mb-3">
        <h1 className="text-xl font-bold text-slate-800 dark:text-slate-100">🧱 테트리스</h1>
        {playing && (
          <div className="flex items-center gap-3 text-sm">
            <span className="font-bold text-fuchsia-500">{score.toLocaleString()}</span>
            {mode === 'sprint'
              ? <span className="text-cyan-500 font-mono">{lines}/{SPRINT_GOAL}줄 · {fmtTime(elapsed)}</span>
              : <span className="text-slate-400">Lv{level} · {lines}줄</span>}
            <button onClick={togglePause} className="hidden sm:inline-block px-2 py-0.5 rounded bg-slate-700 text-xs">{phase === 'paused' ? '▶' : '⏸'}</button>
          </div>
        )}
      </div>

      {playing ? (
        <div className="w-full flex flex-col items-center">
          <div className="flex gap-3 items-start justify-center w-full">
            {/* 좌: HOLD */}
            <div className="hidden sm:flex flex-col gap-2 items-center pt-1">
              <span className="text-[11px] text-slate-400 font-bold">HOLD</span>
              <div className="p-2 rounded-lg bg-slate-800/70 border border-slate-700"><MiniPiece type={hold} /></div>
              <div className="mt-3 text-center text-[11px] text-slate-400 space-y-1">
                <p className="text-slate-500">SCORE</p><p className="text-fuchsia-400 font-bold text-sm">{score.toLocaleString()}</p>
                <p className="text-slate-500 pt-1">LEVEL</p><p className="text-cyan-400 font-bold text-sm">{level}</p>
                <p className="text-slate-500 pt-1">LINES</p><p className="text-emerald-400 font-bold text-sm">{lines}</p>
              </div>
            </div>

            {/* 중: 보드 */}
            <div className="relative" style={{ transform: shake && Date.now() - shake < 160 ? 'translateY(2px)' : 'none' }}>
              <canvas ref={canvasRef}
                className="rounded-lg border-2 border-slate-700 bg-slate-900 touch-none block w-auto h-auto max-w-[80vw] max-h-[46vh] sm:max-w-[360px] sm:max-h-[82vh]" />
              {float && (
                <div key={float.id} className="pointer-events-none absolute inset-0 flex flex-col items-center justify-center">
                  <span className="text-2xl font-extrabold text-white drop-shadow-[0_2px_8px_rgba(0,0,0,0.9)] animate-pulse">{float.text}</span>
                  {float.sub && <span className="text-sm font-bold text-amber-300 drop-shadow">{float.sub}</span>}
                </div>
              )}
              {phase === 'paused' && (
                <div className="absolute inset-0 flex flex-col items-center justify-center bg-slate-900/80 rounded-lg gap-3">
                  <span className="text-lg font-bold">일시정지</span>
                  <button onClick={togglePause} className="px-5 py-2 rounded-lg bg-fuchsia-600 font-bold">계속하기</button>
                </div>
              )}
            </div>

            {/* 우: NEXT */}
            <div className="flex flex-col gap-1.5 items-center pt-1">
              <span className="text-[11px] text-slate-400 font-bold">NEXT</span>
              <div className="p-1.5 rounded-lg bg-slate-800/70 border border-slate-700 flex flex-col gap-1">
                {nextQ.slice(0, 5).map((t, i) => (
                  <div key={i} className={i >= 4 ? 'hidden sm:block' : ''}><MiniPiece type={t} /></div>
                ))}
              </div>
            </div>
          </div>

          {/* 모바일 조작 바 */}
          <div className="sm:hidden mt-3 pb-6 w-full max-w-sm select-none">
            <div className="flex gap-2 mb-2">
              <button onClick={doHold} className="flex-1 py-2 rounded-lg bg-slate-700 text-sm font-bold">HOLD{hold ? ` (${hold})` : ''}</button>
              <button onClick={togglePause} className="flex-1 py-2 rounded-lg bg-slate-700 text-sm font-bold">{phase === 'paused' ? '▶ 계속' : '⏸ 일시정지'}</button>
            </div>
            <div className="grid grid-cols-3 gap-2 mb-2">
              <button onPointerDown={() => act((e) => e.rotate(-1))} className="py-3 rounded-lg bg-slate-700 font-bold text-lg">⟲</button>
              <button onPointerDown={doHardDrop} className="py-3 rounded-lg bg-fuchsia-600 font-bold text-lg">⤓ 하드</button>
              <button onPointerDown={() => act((e) => e.rotate(1))} className="py-3 rounded-lg bg-slate-700 font-bold text-lg">⟳</button>
            </div>
            <div className="grid grid-cols-3 gap-2">
              <button onPointerDown={() => pressDir(-1)} onPointerUp={() => releaseDir(-1)} onPointerLeave={() => releaseDir(-1)} className="py-3 rounded-lg bg-slate-700 font-bold text-lg">◀</button>
              <button onPointerDown={pressSoft} onPointerUp={releaseSoft} onPointerLeave={releaseSoft} className="py-3 rounded-lg bg-slate-700 font-bold text-lg">▼</button>
              <button onPointerDown={() => pressDir(1)} onPointerUp={() => releaseDir(1)} onPointerLeave={() => releaseDir(1)} className="py-3 rounded-lg bg-slate-700 font-bold text-lg">▶</button>
            </div>
          </div>
          <p className="hidden sm:block text-xs text-slate-400 mt-3 text-center">
            ← → 이동 · ↓ 소프트 · Space 하드드롭 · ↑/X 회전 · Z 반회전 · C 홀드 · P 일시정지
          </p>
        </div>
      ) : (
        <div className="w-full space-y-4 text-slate-800 dark:text-slate-100">
          {phase === 'over' && result && (
            <div className="text-center rounded-xl border-2 border-fuchsia-500 p-4 space-y-1 bg-fuchsia-500/5">
              {result.cleared40
                ? (<><p className="text-lg font-bold">🏁 스프린트 40줄 완주!</p><p className="text-3xl font-extrabold text-fuchsia-500 font-mono">{fmtTime(result.timeMs)}</p></>)
                : mode === 'sprint'
                  ? (<><p className="text-lg font-bold">게임 오버</p><p className="text-sm text-slate-400">40줄 미달({result.lines}줄) — 기록 미등록</p></>)
                  : (<><p className="text-lg font-bold">게임 오버</p><p className="text-3xl font-extrabold text-fuchsia-500">{result.score.toLocaleString()}점</p><p className="text-sm text-slate-400">{result.lines}줄 클리어</p></>)}
              {submittedNick && <p className="text-sm text-slate-500">「{submittedNick}」 기록 등록{myRank != null ? ` · ${myRank}위` : ''}</p>}
            </div>
          )}

          {/* 모드 선택 */}
          <div className="grid grid-cols-2 gap-2">
            {([['marathon', '🏔️ 마라톤', '죽을 때까지 최고 점수'], ['sprint', '🏁 스프린트', '40줄 타임어택']] as const).map(([m, label, desc]) => (
              <button key={m} onClick={() => setMode(m)}
                className={`rounded-xl border-2 p-3 text-left transition ${mode === m ? 'border-fuchsia-500 bg-fuchsia-500/10' : 'border-slate-300 dark:border-slate-600'}`}>
                <p className="font-bold">{label}</p><p className="text-xs text-slate-400">{desc}</p>
              </button>
            ))}
          </div>

          <div>
            <input value={nick} onChange={(e) => setNick(e.target.value)} maxLength={16} placeholder="닉네임 (필수)"
              className={`w-full border bg-transparent rounded-lg px-3 py-2 focus:outline-none focus:border-fuchsia-500 ${nick.trim() ? 'border-slate-300 dark:border-slate-600' : 'border-fuchsia-400'}`} />
            {!nick.trim() && <p className="mt-1 text-xs text-fuchsia-500">랭킹 등록을 위해 닉네임을 입력하세요.</p>}
          </div>
          <button onClick={start} disabled={!nick.trim()} className="w-full bg-fuchsia-600 text-white font-bold py-3 rounded-lg disabled:opacity-40 disabled:cursor-not-allowed">
            {phase === 'over' ? '다시 하기' : '시작하기'}
          </button>

          {/* 가이드 */}
          <div className="rounded-xl border border-slate-200 dark:border-slate-700">
            <button onClick={() => setShowGuide((v) => !v)} className="w-full flex items-center justify-between px-4 py-3 font-bold text-sm">
              <span>📖 규칙 · 조작법</span><span>{showGuide ? '▲' : '▼'}</span>
            </button>
            {showGuide && (
              <div className="px-4 pb-4 text-sm text-slate-500 dark:text-slate-300 space-y-3">
                <div>
                  <p className="font-bold text-slate-700 dark:text-slate-200 mb-1">🎯 목표</p>
                  <p>블록을 쌓아 가로 한 줄을 꽉 채우면 그 줄이 사라져요. 4줄을 한 번에 지우면 <b>테트리스</b>(고득점)! 마라톤은 죽을 때까지 최고 점수, 스프린트는 40줄을 가장 빨리 지우는 타임어택입니다.</p>
                </div>
                <div>
                  <p className="font-bold text-slate-700 dark:text-slate-200 mb-1">🕹️ 조작 (PC)</p>
                  <ul className="list-disc pl-5 space-y-0.5">
                    <li>← → : 좌우 이동 · ↓ : 소프트 드롭</li>
                    <li>Space : 하드 드롭(즉시 바닥, 보너스)</li>
                    <li>↑ 또는 X : 시계 회전 · Z : 반시계 회전</li>
                    <li>C : 홀드(블록 보관/교체, 한 블록당 1회)</li>
                    <li>P / Esc : 일시정지</li>
                  </ul>
                </div>
                <div>
                  <p className="font-bold text-slate-700 dark:text-slate-200 mb-1">📱 조작 (모바일)</p>
                  <p>하단 버튼으로 이동/회전/드롭. ◀▶ 길게 누르면 연속 이동, ▼는 빠른 하강.</p>
                </div>
                <div>
                  <p className="font-bold text-slate-700 dark:text-slate-200 mb-1">✨ 고급 기술</p>
                  <ul className="list-disc pl-5 space-y-0.5">
                    <li><b>홀드</b>: 지금 블록을 잠깐 보관하고 다른 걸로 교체. 위기 때 I 블록을 아껴두기.</li>
                    <li><b>고스트</b>: 지금 하드드롭하면 떨어질 위치가 반투명으로 보여요.</li>
                    <li><b>T-스핀</b>: T블록을 회전으로 좁은 틈에 비틀어 끼워 줄을 지우면 <b>일반보다 훨씬 높은 점수</b>! 벽·바닥 근처에서 막힌 자리에 T를 돌려 넣어보세요.</li>
                    <li><b>백투백</b>: 테트리스·T스핀을 연달아 성공하면 추가 보너스(×1.5).</li>
                  </ul>
                </div>
              </div>
            )}
          </div>

          {/* 랭킹 */}
          {mode === 'marathon'
            ? <MarathonBoard refreshKey={refreshKey} highlight={nick.trim()} />
            : <SprintBoard refreshKey={refreshKey} highlight={nick.trim()} />}
        </div>
      )}
    </main>
  );
}

// ── 랭킹 (마라톤: 점수) ────────────────────────────────
function MarathonBoard({ refreshKey, highlight }: { refreshKey: number; highlight: string }) {
  const [rows, setRows] = useState<{ rank: number; nick: string; score: number }[]>([]);
  useEffect(() => { api<any[]>(`/api/v1/scores/tetris?limit=10`).then(setRows).catch(() => {}); }, [refreshKey]);
  return (
    <div className="w-full rounded-xl border border-slate-200 dark:border-slate-700 p-3">
      <p className="text-sm font-bold text-slate-500 mb-2">🏆 마라톤 랭킹 TOP 10</p>
      {rows.length === 0 ? <p className="text-slate-400 text-sm text-center py-3">아직 기록이 없어요</p> : (
        <div className="space-y-0.5">
          {rows.map((r) => (
            <div key={`${r.rank}-${r.nick}`} className={`flex justify-between text-sm py-0.5 px-1 rounded ${highlight && r.nick === highlight ? 'bg-fuchsia-500/10' : ''}`}>
              <span className="font-bold">{r.rank <= 3 ? ['🥇', '🥈', '🥉'][r.rank - 1] : `${r.rank}.`} {r.nick}</span>
              <span className="text-fuchsia-500 font-bold">{r.score.toLocaleString()}</span>
            </div>
          ))}
        </div>
      )}
    </div>
  );
}

// ── 랭킹 (스프린트: 시간, 저장점수 = BASE - ms 를 되돌려 표시) ──
function SprintBoard({ refreshKey, highlight }: { refreshKey: number; highlight: string }) {
  const [rows, setRows] = useState<{ rank: number; nick: string; score: number }[]>([]);
  useEffect(() => { api<any[]>(`/api/v1/scores/tetris_sprint?limit=10`).then(setRows).catch(() => {}); }, [refreshKey]);
  const fmt = (stored: number) => {
    const ms = SPRINT_KEY_BASE - stored; const s = ms / 1000; const m = Math.floor(s / 60);
    return (m > 0 ? `${m}:` : '') + (s - m * 60).toFixed(2).padStart(m > 0 ? 5 : 4, '0');
  };
  return (
    <div className="w-full rounded-xl border border-slate-200 dark:border-slate-700 p-3">
      <p className="text-sm font-bold text-slate-500 mb-2">🏁 스프린트 40줄 랭킹 (빠른 순)</p>
      {rows.length === 0 ? <p className="text-slate-400 text-sm text-center py-3">아직 기록이 없어요</p> : (
        <div className="space-y-0.5">
          {rows.map((r) => (
            <div key={`${r.rank}-${r.nick}`} className={`flex justify-between text-sm py-0.5 px-1 rounded ${highlight && r.nick === highlight ? 'bg-fuchsia-500/10' : ''}`}>
              <span className="font-bold">{r.rank <= 3 ? ['🥇', '🥈', '🥉'][r.rank - 1] : `${r.rank}.`} {r.nick}</span>
              <span className="text-cyan-500 font-bold font-mono">{fmt(r.score)}</span>
            </div>
          ))}
        </div>
      )}
    </div>
  );
}
