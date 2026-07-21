'use client';

import Link from 'next/link';
import { useCallback, useEffect, useRef, useState } from 'react';
import Matter from 'matter-js';

// 뷰포트(화면에 보이는 창) + 월드(전체 긴 트랙)
const VW = 720, VH = 760;    // 넓게(가로 활용)
const SCALE = 2;             // 캔버스 백킹 해상도 배율(확대 시 선명)
const TALL = 4600;           // 전체 코스 높이(약간 짧게)
const FINISH_Y = TALL - 70;
const SEG_H = 300, DROP = 190; // 구간 높이 + 게이트 낙차
const COLORS = ['#ff8fab', '#8ec5ff', '#ffd97d', '#a0e8af', '#c8a2ff', '#ffb37d', '#7fd8d8', '#ff9ecd', '#b3e05a', '#ff7d7d', '#9db4ff', '#ffc46b'];

type Marble = { body: Matter.Body; name: string; color: number; finished: boolean; stuck: number };

const clampCam = (c: number) => Math.max(0, Math.min(TALL - VH, c));

export default function MarblePage() {
  const [namesText, setNamesText] = useState('라미\n우노\n꿀벌\n폴짝');
  const [lastWins, setLastWins] = useState(false);
  const [running, setRunning] = useState(false);
  const [ranking, setRanking] = useState<{ name: string; color: number }[]>([]);
  const [winner, setWinner] = useState<{ name: string; color: number } | null>(null);
  const [fs, setFs] = useState(false);
  const [entries, setEntries] = useState<{ name: string; color: number }[]>([]);
  const [focusIdx, setFocusIdx] = useState<number | null>(null);

  const wrapRef = useRef<HTMLDivElement>(null);
  const canvasRef = useRef<HTMLCanvasElement>(null);
  const engineRef = useRef<Matter.Engine | null>(null);
  const rafRef = useRef<number | null>(null);
  const marblesRef = useRef<Marble[]>([]);
  const pegsRef = useRef<{ x: number; y: number; r: number }[]>([]);
  const bumpersRef = useRef<{ x: number; y: number; r: number; color: string }[]>([]);
  const barsRef = useRef<{ body: Matter.Body; spin: number; half: number; color: string }[]>([]);
  const finishRef = useRef<{ name: string; color: number }[]>([]);
  const startAtRef = useRef(0);
  const camRef = useRef(0);
  const lastWinsRef = useRef(lastWins); lastWinsRef.current = lastWins;
  const runningRef = useRef(running); runningRef.current = running;
  const drawRef = useRef<() => void>(() => {});
  const focusRef = useRef<number | null>(null);

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
    const bumpers: { x: number; y: number; r: number; color: string }[] = [];
    const bars: { body: Matter.Body; spin: number; half: number; color: string }[] = [];
    const rnd = (a: number, b: number) => a + Math.random() * (b - a);
    const chance = (p: number) => Math.random() < p;
    const pick = <T,>(arr: T[]): T => arr[Math.floor(Math.random() * arr.length)];
    const PAL = ['#f97316', '#ec4899', '#8b5cf6', '#0ea5e9', '#22c55e', '#eab308', '#ef4444', '#14b8a6'];

    // 옆벽
    bodies.push(Bodies.rectangle(-12, TALL / 2, 24, TALL * 1.2, { isStatic: true, restitution: 0.3 }));
    bodies.push(Bodies.rectangle(VW + 12, TALL / 2, 24, TALL * 1.2, { isStatic: true, restitution: 0.3 }));

    const peg = (x: number, y: number, r = 8) => { pegs.push({ x, y, r }); bodies.push(Bodies.circle(x, y, r, { isStatic: true, restitution: 0.75 })); };
    const bump = (x: number, y: number, r: number, color: string) => { bodies.push(Bodies.circle(x, y, r, { isStatic: true, restitution: 1.3 })); bumpers.push({ x, y, r, color }); };
    const ramp = (x: number, y: number, len: number, ang: number, rest = 0.2) => bodies.push(Bodies.rectangle(x, y, len, 20, { isStatic: true, angle: ang, restitution: rest, friction: 0.06, chamfer: { radius: 10 } }));
    const NEON = ['#f9a8d4', '#a7f3d0', '#93c5fd', '#fca5a5', '#c4b5fd', '#fde68a'];
    const spinner = (x: number, y: number, len: number) => { const b = Bodies.rectangle(x, y, len, 16, { isStatic: true, restitution: 0.6, chamfer: { radius: 8 } }); bodies.push(b); bars.push({ body: b, spin: (chance(0.5) ? 1 : -1) * rnd(0.028, 0.05), half: len / 2, color: pick(NEON) }); };

    // ── 매 판 랜덤 코스: 구간마다 다른 장애물 타입을 뽑아 다양하게 ──
    const END = FINISH_Y - 430;
    let y = 170, lastType = '';
    while (y < END - 120) {
      const h = rnd(SEG_H - 40, SEG_H + 50);
      let type = pick(['pegs', 'pegs', 'gate', 'spinner', 'bumpers']);
      if (type === lastType && chance(0.6)) type = pick(['pegs', 'gate', 'spinner', 'bumpers']);
      lastType = type;

      if (type === 'gate') {
        // 한쪽만 열린 긴 램프 → 병목·추월
        const dir = chance(0.5) ? 1 : -1;
        const gap = rnd(110, 175);
        const x1 = dir === 1 ? -6 : VW + 6, x2 = dir === 1 ? VW - gap : gap;
        ramp((x1 + x2) / 2, y + DROP / 2 + 10, Math.hypot(x2 - x1, DROP), Math.atan2(DROP, x2 - x1), 0.15);
        const ps = rnd(54, 66); let row = 0;
        for (let ry = y + DROP + 40; ry < y + h - 20 && ry < END; ry += rnd(44, 54)) {
          const off = (row % 2) * (ps / 2);
          for (let px = 24 + off; px < VW - 16; px += ps) if (chance(0.85)) peg(px, ry, rnd(6, 8));
          row++;
        }
      } else if (type === 'spinner') {
        // 회전 바람개비 — 마블을 실제로 퍼올려 튕김
        if (chance(0.5)) {
          // 큰 중앙 바 하나
          spinner(VW * rnd(0.42, 0.58), y + h * 0.5, VW * rnd(0.42, 0.56));
        } else {
          // 좌우 엇갈린 바 두 개(레퍼런스 느낌)
          spinner(VW * rnd(0.24, 0.34), y + h * rnd(0.32, 0.42), VW * rnd(0.34, 0.44));
          spinner(VW * rnd(0.66, 0.76), y + h * rnd(0.6, 0.72), VW * rnd(0.34, 0.44));
        }
        for (const px of [VW * 0.12, VW * 0.88]) peg(px, y + h * 0.85, 9);
        if (chance(0.5)) bump(VW * rnd(0.35, 0.65), y + h * 0.9, rnd(16, 20), pick(PAL));
      } else if (type === 'bumpers') {
        // 흩뿌린 바운시 범퍼 밭
        const n = Math.round(rnd(4, 7));
        for (let i = 0; i < n; i++) bump(rnd(60, VW - 60), y + rnd(30, h - 30), rnd(14, 22), pick(PAL));
        for (let px = 50; px < VW - 40; px += rnd(60, 80)) peg(px, y + h - 24, 8);
      } else {
        // 촘촘 플린코 못밭(간격 랜덤)
        const ps = rnd(52, 64); let row = 0;
        for (let ry = y + 30; ry < y + h - 16 && ry < END; ry += rnd(42, 52)) {
          const off = (row % 2) * (ps / 2);
          for (let px = 22 + off; px < VW - 14; px += ps) if (chance(0.88)) peg(px, ry, rnd(6, 8));
          row++;
        }
        if (chance(0.4)) bump(VW * rnd(0.25, 0.75), y + h * rnd(0.4, 0.7), rnd(15, 20), pick(PAL));
      }
      y += h;
    }

    // 좌우 벽 직행 방지: 양쪽 벽에 안쪽으로 튀어나온 엇갈린 돌기(벽 타고 직행하는 마블을 중앙으로)
    for (let wy = 220; wy < END - 40; wy += rnd(150, 200)) {
      peg(rnd(5, 12), wy, rnd(9, 12));
      peg(VW - rnd(5, 12), wy + rnd(60, 100), rnd(9, 12));
    }

    // ── 결승 핀볼 존: 깔때기 → 범퍼 → ★중앙 블로커(직진 마블을 튕겨 옆으로) ──
    const zoneTop = FINISH_Y - 300;
    ramp(VW * 0.19, zoneTop, VW * 0.5, 0.6, 0.4);
    ramp(VW * 0.81, zoneTop, VW * 0.5, -0.6, 0.4);
    bump(VW * 0.5, zoneTop + 82, 22, '#f97316');
    bump(VW * 0.32, zoneTop + 158, 18, '#ec4899');
    bump(VW * 0.68, zoneTop + 158, 18, '#8b5cf6');
    // ★ 결승 직전 중앙 블로커 + 좌우 튕김 범퍼 — 그냥 못 지나가고 핀볼처럼 튕김
    bump(VW * 0.5, FINISH_Y - 62, 30, '#ef4444');
    bump(VW * 0.25, FINISH_Y - 26, 17, '#22c55e');
    bump(VW * 0.75, FINISH_Y - 26, 17, '#0ea5e9');

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
    focusRef.current = null; setFocusIdx(null);

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
      marbles.push({ body, name, color: i % COLORS.length, finished: false, stuck: 0 });
    });
    marblesRef.current = marbles;
    setEntries(marbles.map((m) => ({ name: m.name, color: m.color })));
    startAtRef.current = performance.now();
    setRunning(true);
    loop();
  };

  const loop = () => {
    const engine = engineRef.current, canvas = canvasRef.current;
    if (!engine || !canvas) return;
    barsRef.current.forEach((b) => Matter.Body.rotate(b.body, b.spin));
    Matter.Engine.update(engine, 1000 / 60);

    // 회전 바에 닿은 마블을 회전 방향으로 실어 올림(핀볼 패들처럼 퍼올림)
    for (const b of barsRef.current) {
      const bx = b.body.position.x, by = b.body.position.y;
      for (const m of marblesRef.current) {
        if (m.finished) continue;
        const dx = m.body.position.x - bx, dy = m.body.position.y - by;
        const d = Math.hypot(dx, dy);
        if (d < 8 || d > b.half + 16) continue;
        // 접선 속도 = 회전 방향으로 표면이 움직이는 방향(위로 올라오는 쪽은 위로)
        const vx = -dy * b.spin * 2.6, vy = dx * b.spin * 2.6;
        Matter.Body.setVelocity(m.body, { x: m.body.velocity.x * 0.35 + vx, y: m.body.velocity.y * 0.35 + vy });
      }
    }

    // 끼임 방지: 거의 멈춘 마블은 잠깐 뒤 살짝 흔들어 내려보냄
    for (const m of marblesRef.current) {
      if (m.finished) continue;
      const sp = Math.hypot(m.body.velocity.x, m.body.velocity.y);
      if (sp < 0.4) {
        m.stuck += 1000 / 60;
        if (m.stuck > 420) {
          Matter.Body.setVelocity(m.body, { x: (Math.random() * 2 - 1) * 3.5, y: 2.6 });
          Matter.Body.setPosition(m.body, { x: m.body.position.x + (Math.random() * 2 - 1) * 3, y: m.body.position.y });
          m.stuck = 0;
        }
      } else m.stuck = 0;
    }

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
    // 카메라: 선택 마블(있으면) 또는 선두를 화면 42% 지점에 두고 부드럽게 추적
    const fi = focusRef.current;
    const foc = fi != null ? marblesRef.current[fi] : null;
    const followY = foc && !foc.finished ? foc.body.position.y : leadY;
    const target = clampCam(followY - VH * 0.42);
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
    if (engineRef.current) Matter.Composite.allBodies(engineRef.current.world).forEach((b) => {
      if (!b.isStatic || (b as any).circleRadius) return;
      if (!inView(b.position.y, 120)) return;
      const v = b.vertices; if (v.length < 3) return;
      ctx.beginPath(); ctx.moveTo(v[0].x, v[0].y); for (let i = 1; i < v.length; i++) ctx.lineTo(v[i].x, v[i].y); ctx.closePath(); ctx.fill();
    });
    // 못
    ctx.fillStyle = dark ? '#64748b' : '#94a3b8';
    pegsRef.current.forEach((p) => { if (inView(p.y)) { ctx.beginPath(); ctx.arc(p.x, p.y, p.r, 0, 7); ctx.fill(); } });
    // 회전 바람개비(스피너) — 네온 글로우로 눈에 띄게
    barsRef.current.forEach((b) => {
      if (!inView(b.body.position.y, 120)) return;
      const v = b.body.vertices; if (v.length < 3) return;
      ctx.save();
      ctx.shadowColor = b.color; ctx.shadowBlur = 16;
      ctx.fillStyle = b.color;
      ctx.beginPath(); ctx.moveTo(v[0].x, v[0].y); for (let i = 1; i < v.length; i++) ctx.lineTo(v[i].x, v[i].y); ctx.closePath(); ctx.fill();
      ctx.restore();
      ctx.beginPath(); ctx.arc(b.body.position.x, b.body.position.y, 5, 0, 7); ctx.fillStyle = dark ? '#1e293b' : '#475569'; ctx.fill();
    });
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
    // 선택 마블 강조 링
    const fi = focusRef.current;
    const foc = fi != null ? marblesRef.current[fi] : null;
    if (foc && !foc.finished && inView(foc.body.position.y, 30)) {
      ctx.beginPath(); ctx.arc(foc.body.position.x, foc.body.position.y, 17, 0, 7);
      ctx.lineWidth = 3; ctx.strokeStyle = '#4f46e5'; ctx.setLineDash([4, 3]); ctx.stroke(); ctx.setLineDash([]);
    }
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

  drawRef.current = draw;

  // 끝난 뒤 휠 스크롤로 맵 확인(경기 중 비활성)
  useEffect(() => {
    const c = canvasRef.current; if (!c) return;
    const onWheel = (e: WheelEvent) => {
      if (runningRef.current) return;
      e.preventDefault();
      camRef.current = clampCam(camRef.current + e.deltaY);
      drawRef.current();
    };
    c.addEventListener('wheel', onWheel, { passive: false });
    return () => c.removeEventListener('wheel', onWheel);
  }, []);

  const onDragStart = (e: React.PointerEvent) => {
    if (runningRef.current) return;
    const rect = canvasRef.current!.getBoundingClientRect();
    const k = VH / rect.height;               // 화면px → 논리px
    const startY = e.clientY, startCam = camRef.current;
    const move = (ev: PointerEvent) => { camRef.current = clampCam(startCam - (ev.clientY - startY) * k); drawRef.current(); };
    const up = () => { window.removeEventListener('pointermove', move); window.removeEventListener('pointerup', up); };
    window.addEventListener('pointermove', move);
    window.addEventListener('pointerup', up);
  };

  // 참가자 클릭 → 해당 마블로 카메라 이동/추적
  const focusOn = (i: number) => {
    if (focusRef.current === i) { focusRef.current = null; setFocusIdx(null); return; } // 다시 클릭 시 해제
    focusRef.current = i; setFocusIdx(i);
    const m = marblesRef.current[i];
    if (m && !runningRef.current) { camRef.current = clampCam(m.body.position.y - VH * 0.42); drawRef.current(); }
  };

  useEffect(() => {
    const canvas = canvasRef.current; if (!canvas) return;
    canvas.width = VW * SCALE; canvas.height = VH * SCALE;
    const ctx = canvas.getContext('2d');
    if (ctx) { ctx.setTransform(SCALE, 0, 0, SCALE, 0, 0); ctx.fillStyle = '#eef2f7'; ctx.fillRect(0, 0, VW, VH); }
    return () => stop();
  }, [stop]);

  // 참가자 라벨(같은 이름은 번호 붙임: 라미 1, 라미 2 …)
  const seen: Record<string, number> = {};
  const totals: Record<string, number> = {};
  entries.forEach((e) => { totals[e.name] = (totals[e.name] || 0) + 1; });
  const entryLabels = entries.map((e) => {
    seen[e.name] = (seen[e.name] || 0) + 1;
    return totals[e.name] > 1 ? `${e.name} ${seen[e.name]}` : e.name;
  });

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
          <canvas ref={canvasRef} onPointerDown={onDragStart}
            className={`block ${running ? '' : 'cursor-grab active:cursor-grabbing'}`}
            style={{ height: fs ? '100vh' : 'min(84vh, 940px)', aspectRatio: `${VW}/${VH}`, maxWidth: '100%', touchAction: running ? 'auto' : 'none' }} />
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
          {entries.length > 0 && (
            <div className="rounded-xl border border-slate-200 dark:border-slate-700 p-3">
              <div className="flex items-center justify-between mb-2">
                <p className="text-sm font-bold text-slate-600 dark:text-slate-300">👀 참가자 {entries.length} · 클릭해 따라가기</p>
                {focusIdx != null && (
                  <button onClick={() => { focusRef.current = null; setFocusIdx(null); }} className="text-xs font-bold text-indigo-500 hover:underline">선두 보기</button>
                )}
              </div>
              <div className="grid grid-cols-2 gap-1 max-h-56 overflow-auto">
                {entries.map((e, i) => (
                  <button key={i} onClick={() => focusOn(i)}
                    className={`flex items-center gap-1.5 text-sm px-2 py-1 rounded-lg border text-left ${focusIdx === i ? 'border-indigo-500 bg-indigo-50 dark:bg-indigo-500/10 font-bold' : 'border-transparent hover:bg-slate-100 dark:hover:bg-slate-800'}`}>
                    <span className="w-3 h-3 rounded-full shrink-0" style={{ background: COLORS[e.color] }} />
                    <span className="truncate">{entryLabels[i]}</span>
                  </button>
                ))}
              </div>
            </div>
          )}
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
          {!running && <p className="text-xs text-indigo-400">🖱️ 경기가 끝나면 화면을 <b>드래그·스크롤</b>해서 맵 전체를 살펴볼 수 있어요.</p>}
        </div>
      </div>
    </main>
  );
}
