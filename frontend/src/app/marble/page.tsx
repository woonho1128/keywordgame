'use client';

import Link from 'next/link';
import { useCallback, useEffect, useRef, useState } from 'react';
import Matter from 'matter-js';

// 로직 좌표계(캔버스는 이 크기를 화면에 맞춰 스케일)
const W = 420, H = 680;
const FINISH_Y = H - 34;
const COLORS = ['#ff8fab', '#8ec5ff', '#ffd97d', '#a0e8af', '#c8a2ff', '#ffb37d', '#7fd8d8', '#ff9ecd', '#b3e05a', '#ff7d7d', '#9db4ff', '#ffc46b'];

type Marble = { body: Matter.Body; name: string; color: number; finished: boolean };

export default function MarblePage() {
  const [namesText, setNamesText] = useState('라미\n우노\n꿀벌\n폴짝');
  const [lastWins, setLastWins] = useState(false); // 꼴찌 뽑기
  const [running, setRunning] = useState(false);
  const [ranking, setRanking] = useState<{ name: string; color: number }[]>([]);
  const [winner, setWinner] = useState<{ name: string; color: number } | null>(null);

  const canvasRef = useRef<HTMLCanvasElement>(null);
  const engineRef = useRef<Matter.Engine | null>(null);
  const rafRef = useRef<number | null>(null);
  const marblesRef = useRef<Marble[]>([]);
  const pegsRef = useRef<{ x: number; y: number; r: number }[]>([]);
  const barsRef = useRef<{ body: Matter.Body; spin: number }[]>([]);
  const finishRef = useRef<{ name: string; color: number }[]>([]);
  const startAtRef = useRef(0);
  const lastWinsRef = useRef(lastWins); lastWinsRef.current = lastWins;

  const stop = useCallback(() => {
    if (rafRef.current) cancelAnimationFrame(rafRef.current);
    rafRef.current = null;
  }, []);

  const buildCourse = (world: Matter.World) => {
    const { Bodies, Composite } = Matter;
    const opt = { isStatic: true, restitution: 0.5, friction: 0.2 };
    const walls = [
      Bodies.rectangle(-10, H / 2, 20, H * 2, opt),
      Bodies.rectangle(W + 10, H / 2, 20, H * 2, opt),
    ];
    Composite.add(world, walls);

    // 못(peg) 지그재그 배치
    const pegs: { x: number; y: number; r: number }[] = [];
    const bodies: Matter.Body[] = [];
    const rows = 8, r = 6;
    for (let row = 0; row < rows; row++) {
      const y = 130 + row * ((FINISH_Y - 160) / rows);
      const cols = row % 2 === 0 ? 5 : 4;
      const gap = W / (cols + 1);
      const off = row % 2 === 0 ? 0 : gap / 2;
      for (let c = 1; c <= cols; c++) {
        const x = off + c * gap - (row % 2 === 0 ? 0 : 0);
        if (x < 20 || x > W - 20) continue;
        pegs.push({ x, y, r });
        bodies.push(Bodies.circle(x, y, r, { isStatic: true, restitution: 0.6 }));
      }
    }
    // 경사 범퍼(양쪽)
    const bump = (x: number, y: number, a: number) => Bodies.rectangle(x, y, 90, 12, { isStatic: true, angle: a, restitution: 0.5, chamfer: { radius: 6 } });
    bodies.push(bump(70, 250, 0.5), bump(W - 70, 250, -0.5), bump(70, 470, 0.5), bump(W - 70, 470, -0.5));
    Composite.add(world, bodies);
    pegsRef.current = pegs;

    // 회전 막대 2개
    const bars: { body: Matter.Body; spin: number }[] = [];
    const bar1 = Bodies.rectangle(W / 2, 350, 150, 12, { isStatic: true, restitution: 0.5, chamfer: { radius: 6 } });
    const bar2 = Bodies.rectangle(W / 2, 560, 120, 12, { isStatic: true, restitution: 0.5, chamfer: { radius: 6 } });
    Composite.add(world, [bar1, bar2]);
    bars.push({ body: bar1, spin: 0.018 }, { body: bar2, spin: -0.024 });
    barsRef.current = bars;
  };

  const start = () => {
    // 각 줄 파싱: "이름" 또는 "이름 x3" / "이름 *3" → 그 수만큼 추가(동일 이름 여러 개)
    const names: string[] = [];
    for (const raw of namesText.split('\n')) {
      const line = raw.trim(); if (!line) continue;
      const m = line.match(/^(.*?)\s*[x*×]\s*(\d+)$/i);
      if (m && m[1].trim()) { const cnt = Math.min(24, Math.max(1, parseInt(m[2], 10))); for (let i = 0; i < cnt; i++) names.push(m[1].trim()); }
      else names.push(line);
      if (names.length >= 24) break;
    }
    const list = names.slice(0, 24);
    if (list.length < 2) { alert('참가자를 2명 이상 입력하세요 (한 줄에 한 명, "이름 x3"으로 여러 개)'); return; }
    stop();
    setRanking([]); setWinner(null); finishRef.current = [];

    const engine = Matter.Engine.create();
    engine.gravity.y = 1;
    engineRef.current = engine;
    buildCourse(engine.world);

    // 마블 생성(상단에 흩뿌림)
    const marbles: Marble[] = [];
    list.forEach((name, i) => {
      const perRow = 6;
      const rowN = Math.floor(i / perRow), col = i % perRow;
      const rowCount = Math.min(perRow, list.length - rowN * perRow);
      const gap = W / (rowCount + 1);
      const x = (col + 1) * gap + (Math.random() * 8 - 4);
      const y = 24 + rowN * 30;
      const body = Matter.Bodies.circle(x, y, 11, { restitution: 0.35, friction: 0.02, frictionAir: 0.006, density: 0.02 });
      Matter.Composite.add(engine.world, body);
      marbles.push({ body, name, color: i % COLORS.length, finished: false });
    });
    marblesRef.current = marbles;
    startAtRef.current = performance.now();
    setRunning(true);
    loop();
  };

  const loop = () => {
    const engine = engineRef.current; const canvas = canvasRef.current;
    if (!engine || !canvas) return;
    // 회전 막대
    barsRef.current.forEach((b) => Matter.Body.rotate(b.body, b.spin));
    Matter.Engine.update(engine, 1000 / 60);

    // 결승 판정
    const now = performance.now();
    const timeout = now - startAtRef.current > 30000;
    let liveCount = 0;
    for (const m of marblesRef.current) {
      if (m.finished) continue;
      if (m.body.position.y > FINISH_Y || (timeout && m.body.position.y > H * 0.5)) {
        m.finished = true;
        finishRef.current.push({ name: m.name, color: m.color });
        Matter.Composite.remove(engine.world, m.body);
        setRanking([...finishRef.current]);
      } else liveCount++;
    }
    if (timeout) {
      // 남은 것 강제 정리(아래쪽 순)
      marblesRef.current.filter((m) => !m.finished).sort((a, b) => b.body.position.y - a.body.position.y)
        .forEach((m) => { m.finished = true; finishRef.current.push({ name: m.name, color: m.color }); Matter.Composite.remove(engine.world, m.body); });
      if (finishRef.current.length) setRanking([...finishRef.current]);
      liveCount = 0;
    }

    draw();

    if (liveCount === 0) {
      const fin = finishRef.current;
      const w = lastWinsRef.current ? fin[fin.length - 1] : fin[0];
      setWinner(w ?? null); setRunning(false); stop(); return;
    }
    rafRef.current = requestAnimationFrame(loop);
  };

  const draw = () => {
    const canvas = canvasRef.current; if (!canvas) return;
    const ctx = canvas.getContext('2d'); if (!ctx) return;
    const dpr = Math.min(2, window.devicePixelRatio || 1);
    const dark = document.documentElement.classList.contains('dark') || (document.documentElement.getAttribute('data-theme') !== 'light' && window.matchMedia?.('(prefers-color-scheme: dark)').matches);
    ctx.setTransform(dpr, 0, 0, dpr, 0, 0);
    ctx.clearRect(0, 0, W, H);
    // 배경
    ctx.fillStyle = dark ? '#1e293b' : '#eef2f7';
    ctx.fillRect(0, 0, W, H);

    // 결승선
    ctx.strokeStyle = '#22c55e'; ctx.lineWidth = 3; ctx.setLineDash([8, 6]);
    ctx.beginPath(); ctx.moveTo(0, FINISH_Y); ctx.lineTo(W, FINISH_Y); ctx.stroke(); ctx.setLineDash([]);
    ctx.fillStyle = '#22c55e'; ctx.font = 'bold 12px sans-serif'; ctx.textAlign = 'right'; ctx.fillText('🏁 결승', W - 8, FINISH_Y - 6);

    // 못
    ctx.fillStyle = dark ? '#64748b' : '#94a3b8';
    pegsRef.current.forEach((p) => { ctx.beginPath(); ctx.arc(p.x, p.y, p.r, 0, 7); ctx.fill(); });
    // 막대(회전) — 정적 범퍼 포함해서 world의 사각형들 그림
    ctx.fillStyle = dark ? '#475569' : '#cbd5e1';
    Matter.Composite.allBodies(engineRef.current!.world).forEach((b) => {
      if (!b.isStatic || (b as any).circleRadius) return;
      const v = b.vertices; if (v.length < 3) return;
      ctx.beginPath(); ctx.moveTo(v[0].x, v[0].y); for (let i = 1; i < v.length; i++) ctx.lineTo(v[i].x, v[i].y); ctx.closePath(); ctx.fill();
    });

    // 마블
    marblesRef.current.forEach((m) => { if (!m.finished) drawMarble(ctx, m.body.position.x, m.body.position.y, 11, COLORS[m.color], m.name); });
  };

  const drawMarble = (ctx: CanvasRenderingContext2D, x: number, y: number, r: number, color: string, name: string) => {
    ctx.beginPath(); ctx.arc(x, y, r, 0, 7); ctx.fillStyle = color; ctx.fill();
    ctx.lineWidth = 2; ctx.strokeStyle = 'rgba(0,0,0,.22)'; ctx.stroke();
    const ey = y - r * 0.08, ex = r * 0.38;
    [-ex, ex].forEach((dx) => {
      ctx.beginPath(); ctx.arc(x + dx, ey, r * 0.24, 0, 7); ctx.fillStyle = '#fff'; ctx.fill();
      ctx.beginPath(); ctx.arc(x + dx, ey + r * 0.03, r * 0.12, 0, 7); ctx.fillStyle = '#333'; ctx.fill();
    });
    ctx.fillStyle = 'rgba(255,110,130,.55)';
    [-r * 0.55, r * 0.55].forEach((dx) => { ctx.beginPath(); ctx.ellipse(x + dx, y + r * 0.3, r * 0.17, r * 0.1, 0, 0, 7); ctx.fill(); });
    ctx.font = 'bold 11px sans-serif'; ctx.textAlign = 'center';
    ctx.lineWidth = 3; ctx.strokeStyle = 'rgba(255,255,255,.92)'; ctx.strokeText(name, x, y - r - 4);
    ctx.fillStyle = '#1f2937'; ctx.fillText(name, x, y - r - 4);
  };

  // 캔버스 해상도 세팅 + 초기 배경
  useEffect(() => {
    const canvas = canvasRef.current; if (!canvas) return;
    const dpr = Math.min(2, window.devicePixelRatio || 1);
    canvas.width = W * dpr; canvas.height = H * dpr;
    const ctx = canvas.getContext('2d');
    if (ctx) { ctx.setTransform(dpr, 0, 0, dpr, 0, 0); ctx.fillStyle = '#eef2f7'; ctx.fillRect(0, 0, W, H); }
    return () => stop();
  }, [stop]);

  return (
    <main className="min-h-screen flex flex-col items-center p-3 sm:p-5 max-w-5xl mx-auto w-full text-slate-800 dark:text-slate-100">
      <div className="w-full flex items-center gap-2 mb-3">
        <Link href="/" aria-label="홈으로" className="text-lg leading-none text-slate-500 hover:text-slate-800 dark:hover:text-slate-100">🏠</Link>
        <h1 className="text-xl sm:text-2xl font-extrabold">🎱 마블 레이스</h1>
        <span className="text-xs text-slate-400">이름 넣고 굴려서 뽑기</span>
      </div>

      <div className="w-full grid lg:grid-cols-[420px_minmax(0,1fr)] gap-4 items-start">
        {/* 코스 */}
        <div className="rounded-2xl overflow-hidden border border-slate-200 dark:border-slate-700 shadow mx-auto" style={{ width: '100%', maxWidth: 420 }}>
          <canvas ref={canvasRef} className="block w-full h-auto" style={{ aspectRatio: `${W}/${H}` }} />
        </div>

        {/* 조작 */}
        <div className="space-y-3">
          {winner && (
            <div className="rounded-xl border-2 border-emerald-500 bg-emerald-50 dark:bg-emerald-500/10 p-3 text-center">
              <p className="text-lg font-extrabold">🎉 {lastWins ? '꼴찌' : '1등'}: <span style={{ color: COLORS[winner.color] }}>{winner.name}</span></p>
            </div>
          )}

          <div className="rounded-xl border border-slate-200 dark:border-slate-700 p-3 space-y-2">
            <p className="text-sm font-bold text-slate-600 dark:text-slate-300">참가자 (한 줄에 한 명, 최대 24)</p>
            <p className="text-[11px] text-slate-400">같은 이름 여러 개는 줄 반복 또는 <b>이름 x3</b> 처럼 개수 지정</p>
            <textarea value={namesText} onChange={(e) => setNamesText(e.target.value)} rows={6} disabled={running}
              className="w-full border border-slate-300 dark:border-slate-600 bg-transparent rounded-lg px-3 py-2 text-sm resize-y disabled:opacity-50" placeholder={'라미\n우노\n...'} />
            <label className="flex items-center gap-2 text-sm">
              <input type="checkbox" checked={lastWins} onChange={(e) => setLastWins(e.target.checked)} disabled={running} /> 꼴찌 뽑기 (기본은 1등 뽑기)
            </label>
            <button onClick={start} disabled={running} className="w-full bg-indigo-600 text-white font-bold py-3 rounded-lg disabled:opacity-50">
              {running ? '굴러가는 중…' : '🎬 출발!'}
            </button>
          </div>

          {ranking.length > 0 && (
            <div className="rounded-xl border border-slate-200 dark:border-slate-700 p-3">
              <p className="text-sm font-bold text-slate-600 dark:text-slate-300 mb-2">🏆 도착 순위</p>
              <div className="space-y-0.5">
                {ranking.map((r, i) => (
                  <div key={i} className="flex items-center gap-2 text-sm">
                    <span className="w-5 text-right font-bold text-slate-400">{i + 1}</span>
                    <span className="w-3.5 h-3.5 rounded-full" style={{ background: COLORS[r.color] }} />
                    <span className="font-bold">{r.name}</span>
                  </div>
                ))}
              </div>
            </div>
          )}
          <p className="text-xs text-slate-400">누가 살지·순서 정하기 등 랜덤 뽑기용. 못·범퍼·회전 막대에 튕기며 내려가 먼저 결승선에 닿으면 1등!</p>
        </div>
      </div>
    </main>
  );
}
