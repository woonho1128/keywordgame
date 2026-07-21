'use client';

import Link from 'next/link';
import { useCallback, useEffect, useRef, useState } from 'react';
import Matter from 'matter-js';

// 뷰포트(화면에 보이는 창) + 월드(전체 긴 트랙)
const VW = 720, VH = 760;    // 넓게(가로 활용)
const SCALE = 2;             // 캔버스 백킹 해상도 배율(확대 시 선명)
const TALL = 4600;           // 전체 코스 높이(약간 짧게)
const FINISH_Y = TALL - 70;
const GAP = 130, SEG_H = 300, DROP = 190; // 좁은 게이트 + 촘촘한 구간
const COLORS = ['#ff8fab', '#8ec5ff', '#ffd97d', '#a0e8af', '#c8a2ff', '#ffb37d', '#7fd8d8', '#ff9ecd', '#b3e05a', '#ff7d7d', '#9db4ff', '#ffc46b'];

type Marble = { body: Matter.Body; name: string; color: number; finished: boolean };

export default function MarblePage() {
  const [namesText, setNamesText] = useState('라미\n우노\n꿀벌\n폴짝');
  const [lastWins, setLastWins] = useState(false);
  const [running, setRunning] = useState(false);
  const [ranking, setRanking] = useState<{ name: string; color: number }[]>([]);
  const [winner, setWinner] = useState<{ name: string; color: number } | null>(null);
  const [fs, setFs] = useState(false);

  const wrapRef = useRef<HTMLDivElement>(null);
  const canvasRef = useRef<HTMLCanvasElement>(null);
  const engineRef = useRef<Matter.Engine | null>(null);
  const rafRef = useRef<number | null>(null);
  const marblesRef = useRef<Marble[]>([]);
  const pegsRef = useRef<{ x: number; y: number; r: number }[]>([]);
  const bumpersRef = useRef<{ x: number; y: number; r: number; color: string }[]>([]);
  const barsRef = useRef<{ body: Matter.Body; spin: number }[]>([]);
  const finishRef = useRef<{ name: string; color: number }[]>([]);
  const startAtRef = useRef(0);
  const camRef = useRef(0);
  const lastWinsRef = useRef(lastWins); lastWinsRef.current = lastWins;

  const stop = useCallback(() => { if (rafRef.current) cancelAnimationFrame(rafRef.current); rafRef.current = null; }, []);

  const toggleFullscreen = () => {
    const el = wrapRef.current; if (!el) return;
    if (document.fullscreenElement) document.exitFullscreen?.();
    else el.requestFullscreen?.();
  };
  useEffect(() => {
    const onFs = () => setFs(!!document.fullscreenElement);
    document.addEventListener('fullscreenchange', onFs);
    return () => document.removeEventListener('fullscreenchange', onFs);
  }, []);

  const buildCourse = (world: Matter.World) => {
    const { Bodies, Composite } = Matter;
    const bodies: Matter.Body[] = [];
    const pegs: { x: number; y: number; r: number }[] = [];
    // 옆벽
    bodies.push(Bodies.rectangle(-12, TALL / 2, 24, TALL * 1.2, { isStatic: true, restitution: 0.3 }));
    bodies.push(Bodies.rectangle(VW + 12, TALL / 2, 24, TALL * 1.2, { isStatic: true, restitution: 0.3 }));

    const bars: { body: Matter.Body; spin: number }[] = [];
    const peg = (x: number, y: number, r = 7) => { pegs.push({ x, y, r }); bodies.push(Bodies.circle(x, y, r, { isStatic: true, restitution: 0.75 })); };

    // 좁은 게이트 램프 + 촘촘한 지그재그 못밭(플린코) — 마블이 반드시 못에 부딪혀 섞이게
    const END = FINISH_Y - 500;
    const PS = 48;             // 못 가로 간격(마블 지름 22 대비 촘촘)
    let y = 170, k = 0;
    while (y < END - 120) {
      const dir = k % 2 === 0 ? 1 : -1;
      // 게이트 램프: 거의 전폭을 덮고 열린 쪽 끝에 좁은 gap → 병목·추월
      const x1 = dir === 1 ? -6 : VW + 6, x2 = dir === 1 ? VW - GAP : GAP;
      const midx = (x1 + x2) / 2, midy = (y + y + DROP) / 2;
      bodies.push(Bodies.rectangle(midx, midy, Math.hypot(x2 - x1, DROP), 22, { isStatic: true, angle: Math.atan2(DROP, x2 - x1), restitution: 0.15, friction: 0.06, chamfer: { radius: 10 } }));
      // 램프 아래 촘촘한 지그재그 못밭(각 행 offset → 마블이 매 행 못에 맞음)
      const zTop = y + DROP + 34, zBot = y + SEG_H - 20;
      let row = 0;
      for (let ry = zTop; ry < zBot && ry < END; ry += 40) {
        const off = (row % 2) * (PS / 2);
        for (let px = 40 + off; px < VW - 30; px += PS) peg(px, ry, 8);
        row++;
      }
      y += SEG_H; k++;
    }

    // ── 결승 핀볼 존: 깔때기 → 통통 튀는 범퍼 → 플리퍼 V로 결승 게이트에 모아줌(도움) ──
    const bumpers: { x: number; y: number; r: number; color: string }[] = [];
    const bump = (x: number, cy2: number, r: number, color: string) => { bodies.push(Bodies.circle(x, cy2, r, { isStatic: true, restitution: 1.35 })); bumpers.push({ x, y: cy2, r, color }); };
    const zoneTop = FINISH_Y - 470;
    // 깔때기(양쪽 벽 → 챔버로 모음)
    bodies.push(Bodies.rectangle(VW * 0.15, zoneTop, VW * 0.44, 18, { isStatic: true, angle: 0.5, restitution: 0.4, chamfer: { radius: 9 } }));
    bodies.push(Bodies.rectangle(VW * 0.85, zoneTop, VW * 0.44, 18, { isStatic: true, angle: -0.5, restitution: 0.4, chamfer: { radius: 9 } }));
    // 핀볼 범퍼(통통)
    bump(VW * 0.5, zoneTop + 100, 30, '#f97316');
    bump(VW * 0.31, zoneTop + 195, 26, '#ec4899');
    bump(VW * 0.69, zoneTop + 195, 26, '#8b5cf6');
    // 플리퍼 V — 아래로 모아 결승 게이트로 안내(양옆은 막음, 중앙만 통과)
    bodies.push(Bodies.rectangle(VW * 0.28, FINISH_Y - 95, VW * 0.36, 20, { isStatic: true, angle: 0.5, restitution: 0.85, chamfer: { radius: 10 } }));
    bodies.push(Bodies.rectangle(VW * 0.72, FINISH_Y - 95, VW * 0.36, 20, { isStatic: true, angle: -0.5, restitution: 0.85, chamfer: { radius: 10 } }));

    Composite.add(world, bodies);
    pegsRef.current = pegs; barsRef.current = bars; bumpersRef.current = bumpers;
  };

  const start = () => {
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
    setRanking([]); setWinner(null); finishRef.current = []; camRef.current = 0;

    const engine = Matter.Engine.create();
    engine.gravity.y = 0.72;
    engineRef.current = engine;
    buildCourse(engine.world);

    const marbles: Marble[] = [];
    list.forEach((name, i) => {
      const perRow = 6, rowN = Math.floor(i / perRow), col = i % perRow;
      const rowCount = Math.min(perRow, list.length - rowN * perRow);
      const gap = VW / (rowCount + 1);
      const x = (col + 1) * gap + (Math.random() * 8 - 4);
      const yy = 40 + rowN * 30;
      const body = Matter.Bodies.circle(x, yy, 11, { restitution: 0.18, friction: 0.05, frictionAir: 0.012, density: 0.02 });
      Matter.Composite.add(engine.world, body);
      marbles.push({ body, name, color: i % COLORS.length, finished: false });
    });
    marblesRef.current = marbles;
    startAtRef.current = performance.now();
    setRunning(true);
    loop();
  };

  const loop = () => {
    const engine = engineRef.current, canvas = canvasRef.current;
    if (!engine || !canvas) return;
    barsRef.current.forEach((b) => Matter.Body.rotate(b.body, b.spin));
    Matter.Engine.update(engine, 1000 / 60);

    const now = performance.now();
    const timeout = now - startAtRef.current > 90000;
    let live = 0, leadY = 0;
    for (const m of marblesRef.current) {
      if (m.finished) continue;
      if (m.body.position.y > FINISH_Y) {
        m.finished = true; finishRef.current.push({ name: m.name, color: m.color });
        Matter.Composite.remove(engine.world, m.body); setRanking([...finishRef.current]);
      } else { live++; leadY = Math.max(leadY, m.body.position.y); }
    }
    if (timeout) {
      marblesRef.current.filter((m) => !m.finished).sort((a, b) => b.body.position.y - a.body.position.y)
        .forEach((m) => { m.finished = true; finishRef.current.push({ name: m.name, color: m.color }); Matter.Composite.remove(engine.world, m.body); });
      if (finishRef.current.length) setRanking([...finishRef.current]); live = 0;
    }
    // 카메라: 선두를 화면 42% 지점에 두고 부드럽게 추적
    const target = Math.max(0, Math.min(TALL - VH, leadY - VH * 0.42));
    camRef.current += (target - camRef.current) * 0.12;

    draw();

    if (live === 0) {
      const fin = finishRef.current;
      setWinner((lastWinsRef.current ? fin[fin.length - 1] : fin[0]) ?? null);
      setRunning(false); stop(); return;
    }
    rafRef.current = requestAnimationFrame(loop);
  };

  const draw = () => {
    const canvas = canvasRef.current; if (!canvas) return;
    const ctx = canvas.getContext('2d'); if (!ctx) return;
    const root = document.documentElement;
    const dark = root.getAttribute('data-theme') === 'dark' || (root.getAttribute('data-theme') !== 'light' && window.matchMedia?.('(prefers-color-scheme: dark)').matches);
    ctx.setTransform(SCALE, 0, 0, SCALE, 0, 0);
    ctx.fillStyle = dark ? '#0f172a' : '#eef2f7';
    ctx.fillRect(0, 0, VW, VH);

    const cam = camRef.current;
    ctx.save();
    ctx.translate(0, -cam);

    const inView = (y: number, pad = 40) => y > cam - pad && y < cam + VH + pad;

    // 램프·막대(정적 사각형)
    ctx.fillStyle = dark ? '#334155' : '#cbd5e1';
    Matter.Composite.allBodies(engineRef.current!.world).forEach((b) => {
      if (!b.isStatic || (b as any).circleRadius) return;
      if (!inView(b.position.y, 120)) return;
      const v = b.vertices; if (v.length < 3) return;
      ctx.beginPath(); ctx.moveTo(v[0].x, v[0].y); for (let i = 1; i < v.length; i++) ctx.lineTo(v[i].x, v[i].y); ctx.closePath(); ctx.fill();
    });
    // 못
    ctx.fillStyle = dark ? '#64748b' : '#94a3b8';
    pegsRef.current.forEach((p) => { if (inView(p.y)) { ctx.beginPath(); ctx.arc(p.x, p.y, p.r, 0, 7); ctx.fill(); } });
    // 핀볼 범퍼
    bumpersRef.current.forEach((b) => {
      if (!inView(b.y, 60)) return;
      ctx.beginPath(); ctx.arc(b.x, b.y, b.r, 0, 7); ctx.fillStyle = b.color; ctx.fill();
      ctx.lineWidth = 3; ctx.strokeStyle = 'rgba(255,255,255,.85)'; ctx.stroke();
      ctx.beginPath(); ctx.arc(b.x, b.y, b.r * 0.45, 0, 7); ctx.fillStyle = 'rgba(255,255,255,.6)'; ctx.fill();
    });

    // 결승선
    if (inView(FINISH_Y, 40)) {
      ctx.strokeStyle = '#22c55e'; ctx.lineWidth = 4; ctx.setLineDash([10, 7]);
      ctx.beginPath(); ctx.moveTo(0, FINISH_Y); ctx.lineTo(VW, FINISH_Y); ctx.stroke(); ctx.setLineDash([]);
      ctx.fillStyle = '#22c55e'; ctx.font = 'bold 13px sans-serif'; ctx.textAlign = 'center'; ctx.fillText('🏁 결승', VW / 2, FINISH_Y - 8);
    }

    // 마블
    marblesRef.current.forEach((m) => { if (!m.finished && inView(m.body.position.y)) drawMarble(ctx, m.body.position.x, m.body.position.y, 11, COLORS[m.color], m.name); });
    ctx.restore();

    // 미니맵(오른쪽)
    const mmX = VW - 14, mmW = 8, mmY = 16, mmH = VH - 32;
    ctx.fillStyle = dark ? 'rgba(148,163,184,.18)' : 'rgba(100,116,139,.18)';
    ctx.fillRect(mmX, mmY, mmW, mmH);
    ctx.fillStyle = '#22c55e'; ctx.fillRect(mmX, mmY + mmH - 3, mmW, 3);
    // 뷰포트 표시
    ctx.strokeStyle = dark ? 'rgba(255,255,255,.5)' : 'rgba(0,0,0,.35)'; ctx.lineWidth = 1;
    ctx.strokeRect(mmX, mmY + (cam / TALL) * mmH, mmW, (VH / TALL) * mmH);
    marblesRef.current.forEach((m) => {
      if (m.finished) return;
      const my = mmY + Math.min(1, m.body.position.y / TALL) * mmH;
      ctx.fillStyle = COLORS[m.color]; ctx.beginPath(); ctx.arc(mmX + mmW / 2, my, 2, 0, 7); ctx.fill();
    });
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
    ctx.font = 'bold 12px sans-serif'; ctx.textAlign = 'center';
    ctx.lineWidth = 3.5; ctx.strokeStyle = 'rgba(255,255,255,.95)'; ctx.strokeText(name, x, y - r - 4);
    ctx.fillStyle = '#111827'; ctx.fillText(name, x, y - r - 4);
  };

  useEffect(() => {
    const canvas = canvasRef.current; if (!canvas) return;
    canvas.width = VW * SCALE; canvas.height = VH * SCALE;
    const ctx = canvas.getContext('2d');
    if (ctx) { ctx.setTransform(SCALE, 0, 0, SCALE, 0, 0); ctx.fillStyle = '#eef2f7'; ctx.fillRect(0, 0, VW, VH); }
    return () => stop();
  }, [stop]);

  return (
    <main className="min-h-screen flex flex-col items-center p-3 sm:p-5 max-w-6xl mx-auto w-full text-slate-800 dark:text-slate-100">
      <div className="w-full flex items-center gap-2 mb-3">
        <Link href="/" aria-label="홈으로" className="text-lg leading-none text-slate-500 hover:text-slate-800 dark:hover:text-slate-100">🏠</Link>
        <h1 className="text-xl sm:text-2xl font-extrabold">🎱 마블 레이스</h1>
        <span className="hidden sm:inline text-xs text-slate-400">긴 코스를 굴러 내려가는 랜덤 뽑기</span>
        <button onClick={toggleFullscreen} className="ml-auto text-sm px-3 py-1.5 rounded-lg border border-slate-300 dark:border-slate-600 font-bold hover:border-indigo-500">⛶ {fs ? '전체화면 종료' : '전체화면'}</button>
      </div>

      <div className="w-full flex flex-col lg:flex-row gap-4 justify-center items-start">
        <div ref={wrapRef} className={`rounded-2xl overflow-hidden border border-slate-200 dark:border-slate-700 shadow mx-auto ${fs ? 'flex items-center justify-center bg-black w-screen h-screen rounded-none border-0' : ''}`}>
          <canvas ref={canvasRef} className="block" style={{ height: fs ? '100vh' : 'min(84vh, 940px)', aspectRatio: `${VW}/${VH}`, maxWidth: '100%' }} />
        </div>

        <div className="w-full lg:w-80 shrink-0 space-y-3">
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
          <p className="text-xs text-slate-400">긴 지그재그 코스를 카메라가 따라가며 보여줘요. 오른쪽 미니맵으로 전체 진행 확인. 먼저 결승선에 닿으면 1등!</p>
        </div>
      </div>
    </main>
  );
}
