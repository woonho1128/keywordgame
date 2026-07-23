'use client';

import Link from 'next/link';
import { useState } from 'react';

/**
 * 부루마블(모두의마블 느낌) — 목업(비주얼 확인용).
 * 실제 룰/멀티는 아직 없음. 보드·말·주사위·패널 비주얼과 데모 이동만.
 */

// 건물 단계: 0 없음 · 1 별장 · 2 빌딩 · 3 호텔 · 4 랜드마크
const TIER = ['', '🏠', '🏢', '🏨', '🏛️'];
const TIER_NAME = ['', '별장', '빌딩', '호텔', '랜드마크'];

// 색 그룹(라인)
const GROUP: Record<string, string> = {
  A: '#38bdf8', B: '#a78bfa', C: '#fb7185', D: '#34d399', E: '#fbbf24', F: '#f472b6',
};

type Tile = {
  name: string;
  type: 'START' | 'CITY' | 'ISLAND' | 'TRAVEL' | 'FUND' | 'TAX' | 'GOLDKEY';
  group?: string;
  price?: number;   // 만원
  tier?: number;    // 건물 단계
  owner?: number;   // 소유 플레이어 index (-1 없음)
};

// 28칸(4변 × 6 + 코너 4). 코너: 0 출발 · 7 무인도 · 14 세계여행 · 21 기금
const TILES: Tile[] = [
  { name: '출발', type: 'START' },
  { name: '방콕', type: 'CITY', group: 'A', price: 50 },
  { name: '싱가포르', type: 'CITY', group: 'A', price: 60, owner: 0, tier: 1 },
  { name: '황금열쇠', type: 'GOLDKEY' },
  { name: '도쿄', type: 'CITY', group: 'A', price: 80, owner: 0, tier: 2 },
  { name: '베이징', type: 'CITY', group: 'B', price: 90 },
  { name: '국세청', type: 'TAX' },
  { name: '무인도', type: 'ISLAND' },
  { name: '타이베이', type: 'CITY', group: 'B', price: 100 },
  { name: '마닐라', type: 'CITY', group: 'B', price: 110 },
  { name: '황금열쇠', type: 'GOLDKEY' },
  { name: '두바이', type: 'CITY', group: 'C', price: 130, owner: 1, tier: 3 },
  { name: '카이로', type: 'CITY', group: 'C', price: 140, owner: 1, tier: 4 },
  { name: '케이프타운', type: 'CITY', group: 'C', price: 150 },
  { name: '세계여행', type: 'TRAVEL' },
  { name: '로마', type: 'CITY', group: 'D', price: 170 },
  { name: '파리', type: 'CITY', group: 'D', price: 180, owner: 2, tier: 2 },
  { name: '황금열쇠', type: 'GOLDKEY' },
  { name: '런던', type: 'CITY', group: 'D', price: 200, owner: 2, tier: 1 },
  { name: '베를린', type: 'CITY', group: 'E', price: 220 },
  { name: '모스크바', type: 'CITY', group: 'E', price: 240 },
  { name: '사회복지기금', type: 'FUND' },
  { name: '뉴욕', type: 'CITY', group: 'E', price: 270, owner: 3, tier: 3 },
  { name: '시카고', type: 'CITY', group: 'F', price: 290 },
  { name: '황금열쇠', type: 'GOLDKEY' },
  { name: '토론토', type: 'CITY', group: 'F', price: 310 },
  { name: '리우', type: 'CITY', group: 'F', price: 330, owner: 3, tier: 2 },
  { name: '시드니', type: 'CITY', group: 'F', price: 360 },
];

const SPECIAL: Record<string, { emoji: string; sub: string }> = {
  START: { emoji: '🏁', sub: '월급 받기' },
  ISLAND: { emoji: '🏝️', sub: '무인도' },
  TRAVEL: { emoji: '✈️', sub: '원하는 칸' },
  FUND: { emoji: '🎁', sub: '기금 수령' },
  TAX: { emoji: '💸', sub: '세금 납부' },
  GOLDKEY: { emoji: '🔑', sub: '찬스 카드' },
};

const PLAYERS = [
  { name: '라미', color: '#3b82f6' },
  { name: '우노', color: '#ef4444' },
  { name: '꿀벌', color: '#22c55e' },
  { name: '폴짝', color: '#eab308' },
];

// 8×8 그리드에서 테두리 칸 좌표(1-index) 계산
function gridPos(i: number): { r: number; c: number } {
  if (i <= 7) return { r: 8, c: 8 - i };          // 아래줄 →왼쪽
  if (i <= 14) return { r: 8 - (i - 7), c: 1 };   // 왼쪽줄 ↑
  if (i <= 21) return { r: 1, c: 1 + (i - 14) };  // 윗줄 →오른쪽
  return { r: 1 + (i - 21), c: 8 };               // 오른쪽줄 ↓
}
// 안쪽(중앙)을 향한 색띠 위치
function bandSide(i: number): 'top' | 'right' | 'bottom' | 'left' {
  if (i <= 7) return 'top';
  if (i <= 14) return 'right';
  if (i <= 21) return 'bottom';
  return 'left';
}

function DiceFace({ n }: { n: number }) {
  const P = [
    [], [4], [0, 8], [0, 4, 8], [0, 2, 6, 8], [0, 2, 4, 6, 8], [0, 2, 3, 5, 6, 8],
  ][n];
  return (
    <div className="grid grid-cols-3 grid-rows-3 w-9 h-9 sm:w-11 sm:h-11 bg-white rounded-lg shadow-inner border border-slate-300 p-1 gap-0.5">
      {Array.from({ length: 9 }).map((_, k) => (
        <span key={k} className="flex items-center justify-center">
          {P.includes(k) && <span className="w-1.5 h-1.5 sm:w-2 sm:h-2 rounded-full bg-red-600" />}
        </span>
      ))}
    </div>
  );
}

export default function MonopolyMockup() {
  const [pos, setPos] = useState([4, 12, 18, 22]);
  const [turn, setTurn] = useState(0);
  const [dice, setDice] = useState<[number, number]>([3, 4]);
  const [moving, setMoving] = useState(false);
  const [log, setLog] = useState<string[]>(['🎲 목업 데모 — 굴리기로 말이 움직여요.']);

  const addLog = (s: string) => setLog((L) => [s, ...L].slice(0, 8));

  const roll = () => {
    if (moving) return;
    const d1 = 1 + Math.floor(Math.random() * 6), d2 = 1 + Math.floor(Math.random() * 6);
    setDice([d1, d2]);
    const steps = d1 + d2;
    const me = turn;
    addLog(`${PLAYERS[me].name} 🎲 ${d1}+${d2}=${steps}`);
    setMoving(true);
    let n = 0;
    const id = setInterval(() => {
      n++;
      setPos((p) => { const q = [...p]; q[me] = (q[me] + 1) % TILES.length; return q; });
      if (n >= steps) {
        clearInterval(id);
        setPos((p) => {
          const land = TILES[p[me]];
          addLog(`${PLAYERS[me].name} → ${land.name} 도착`);
          return p;
        });
        setMoving(false);
        setTurn((t) => (t + 1) % PLAYERS.length);
      }
    }, 170);
  };

  const cur = TILES[pos[turn]];

  return (
    <main className="min-h-screen flex flex-col items-center p-3 sm:p-5 max-w-6xl mx-auto w-full text-slate-800 dark:text-slate-100">
      <div className="w-full flex items-center gap-2 mb-3">
        <Link href="/" aria-label="홈으로" className="text-lg leading-none text-slate-500 hover:text-slate-800 dark:hover:text-slate-100">🏠</Link>
        <h1 className="text-xl sm:text-2xl font-extrabold">🏙️ 부루마블</h1>
        <span className="text-[11px] font-bold px-2 py-0.5 rounded-full bg-amber-100 text-amber-700 dark:bg-amber-500/15 dark:text-amber-300">목업 · 비주얼 확인용</span>
      </div>

      <div className="w-full flex flex-col lg:flex-row gap-4 justify-center items-start">
        {/* 보드 */}
        <div className="w-full max-w-[680px] mx-auto">
          <div className="relative grid grid-cols-8 grid-rows-8 gap-1 aspect-square bg-emerald-50 dark:bg-slate-800/60 rounded-2xl border border-slate-200 dark:border-slate-700 p-1 shadow">
            {TILES.map((t, i) => {
              const { r, c } = gridPos(i);
              const corner = t.type !== 'CITY' && t.type !== 'GOLDKEY' && t.type !== 'TAX';
              const band = t.group ? GROUP[t.group] : undefined;
              const side = bandSide(i);
              const bStyle: React.CSSProperties = band
                ? side === 'top' ? { borderTop: `6px solid ${band}` }
                  : side === 'bottom' ? { borderBottom: `6px solid ${band}` }
                    : side === 'left' ? { borderLeft: `6px solid ${band}` }
                      : { borderRight: `6px solid ${band}` }
                : {};
              const here = pos.map((p, pi) => (p === i ? pi : -1)).filter((x) => x >= 0);
              const sp = SPECIAL[t.type];
              return (
                <div
                  key={i}
                  style={{ gridRow: r, gridColumn: c, ...bStyle }}
                  className={`relative rounded-md flex flex-col items-center justify-center text-center overflow-hidden px-0.5 py-0.5
                    ${corner ? 'bg-indigo-50 dark:bg-slate-700/70' : 'bg-white dark:bg-slate-800'} border border-slate-200 dark:border-slate-700`}
                >
                  {t.owner !== undefined && (
                    <span className="absolute top-0.5 right-0.5 w-2 h-2 rounded-full ring-1 ring-white dark:ring-slate-900" style={{ background: PLAYERS[t.owner].color }} />
                  )}
                  {corner ? (
                    <>
                      <span className="text-base sm:text-xl leading-none">{sp?.emoji}</span>
                      <span className="text-[8px] sm:text-[10px] font-bold leading-tight mt-0.5">{t.name}</span>
                    </>
                  ) : t.type === 'CITY' ? (
                    <>
                      <span className="text-[8px] sm:text-[11px] font-bold leading-tight">{t.name}</span>
                      {t.tier ? (
                        <span className="text-[9px] sm:text-xs leading-none">{TIER[t.tier]}</span>
                      ) : (
                        <span className="text-[7px] sm:text-[9px] text-slate-400 leading-none">{t.price}만</span>
                      )}
                    </>
                  ) : (
                    <>
                      <span className="text-sm sm:text-lg leading-none">{sp?.emoji}</span>
                      <span className="text-[7px] sm:text-[9px] font-semibold text-slate-500 leading-tight">{t.name}</span>
                    </>
                  )}
                  {/* 말(플레이어) */}
                  {here.length > 0 && (
                    <div className="absolute bottom-0.5 left-0.5 flex flex-wrap gap-0.5 max-w-[80%]">
                      {here.map((pi) => (
                        <span key={pi} className="w-2.5 h-2.5 sm:w-3 sm:h-3 rounded-full ring-2 ring-white dark:ring-slate-900 shadow" style={{ background: PLAYERS[pi].color }} />
                      ))}
                    </div>
                  )}
                </div>
              );
            })}

            {/* 중앙 패널 */}
            <div style={{ gridRow: '2 / 8', gridColumn: '2 / 8' }} className="flex flex-col items-center justify-center gap-2 rounded-xl bg-white/70 dark:bg-slate-900/50 border border-slate-200 dark:border-slate-700 m-1">
              <div className="text-2xl sm:text-4xl font-black tracking-tight text-indigo-600 dark:text-indigo-300 -rotate-6">부루마블</div>
              <div className="flex items-center gap-2">
                <DiceFace n={dice[0]} />
                <DiceFace n={dice[1]} />
                <span className="text-lg font-extrabold">= {dice[0] + dice[1]}</span>
              </div>
              <div className="text-xs sm:text-sm font-bold flex items-center gap-1.5">
                <span className="w-3 h-3 rounded-full" style={{ background: PLAYERS[turn].color }} />
                {PLAYERS[turn].name} 차례 · {cur.name}
              </div>
            </div>
          </div>
        </div>

        {/* 사이드 패널 */}
        <div className="w-full lg:w-80 shrink-0 space-y-3">
          <div className="rounded-xl border border-slate-200 dark:border-slate-700 p-3 space-y-2">
            <button onClick={roll} disabled={moving}
              className="w-full bg-indigo-600 text-white font-extrabold py-3 rounded-lg disabled:opacity-50 text-lg">
              {moving ? '이동 중…' : `🎲 ${PLAYERS[turn].name} 굴리기`}
            </button>
            <div className="grid grid-cols-3 gap-1.5">
              <button className="text-xs font-bold py-2 rounded-lg border border-slate-300 dark:border-slate-600 opacity-60">💰 구매</button>
              <button className="text-xs font-bold py-2 rounded-lg border border-slate-300 dark:border-slate-600 opacity-60">🏗️ 건축</button>
              <button className="text-xs font-bold py-2 rounded-lg border border-slate-300 dark:border-slate-600 opacity-60">⏭️ 패스</button>
            </div>
            <p className="text-[11px] text-slate-400 text-center">※ 목업이라 액션 버튼은 아직 동작 안 해요</p>
          </div>

          <div className="rounded-xl border border-slate-200 dark:border-slate-700 p-3">
            <p className="text-sm font-bold text-slate-600 dark:text-slate-300 mb-2">플레이어</p>
            <div className="space-y-1.5">
              {PLAYERS.map((p, i) => (
                <div key={i} className={`flex items-center gap-2 text-sm px-2 py-1.5 rounded-lg ${turn === i ? 'bg-indigo-50 dark:bg-indigo-500/10 ring-1 ring-indigo-300' : ''}`}>
                  <span className="w-3.5 h-3.5 rounded-full shrink-0" style={{ background: p.color }} />
                  <span className="font-bold">{p.name}</span>
                  <span className="text-xs text-slate-400">@ {TILES[pos[i]].name}</span>
                  <span className="ml-auto font-mono font-bold text-emerald-600 dark:text-emerald-400">{(1200 - i * 180).toLocaleString()}만</span>
                </div>
              ))}
            </div>
          </div>

          <div className="rounded-xl border border-slate-200 dark:border-slate-700 p-3">
            <p className="text-sm font-bold text-slate-600 dark:text-slate-300 mb-2">건물 단계</p>
            <div className="flex flex-wrap gap-x-3 gap-y-1 text-xs">
              {[1, 2, 3, 4].map((t) => (
                <span key={t} className="flex items-center gap-1">{TIER[t]} {TIER_NAME[t]}</span>
              ))}
            </div>
            <p className="text-[11px] text-slate-400 mt-1.5">같은 색(라인) 전부 소유 시 통행료 보너스 · 랜드마크는 최고 통행료</p>
          </div>

          <div className="rounded-xl border border-slate-200 dark:border-slate-700 p-3">
            <p className="text-sm font-bold text-slate-600 dark:text-slate-300 mb-2">진행 로그</p>
            <div className="space-y-0.5 text-xs text-slate-500 dark:text-slate-400">
              {log.map((l, i) => <div key={i} className={i === 0 ? 'font-bold text-slate-700 dark:text-slate-200' : ''}>{l}</div>)}
            </div>
          </div>
        </div>
      </div>
    </main>
  );
}
