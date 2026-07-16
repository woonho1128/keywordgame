'use client';

import Link from 'next/link';
import { useCallback, useEffect, useRef, useState } from 'react';
import { api } from '@/lib/api';

type Cat = 'ones' | 'twos' | 'threes' | 'fours' | 'fives' | 'sixes'
  | 'threeKind' | 'fourKind' | 'fullHouse' | 'smallStraight' | 'largeStraight' | 'yahtzee' | 'chance';

const UPPER: Cat[] = ['ones', 'twos', 'threes', 'fours', 'fives', 'sixes'];
const LOWER: Cat[] = ['threeKind', 'fourKind', 'fullHouse', 'smallStraight', 'largeStraight', 'yahtzee', 'chance'];
const ALL: Cat[] = [...UPPER, ...LOWER];
const LABEL: Record<Cat, string> = {
  ones: '① 1', twos: '② 2', threes: '③ 3', fours: '④ 4', fives: '⑤ 5', sixes: '⑥ 6',
  threeKind: '트리플(3개)', fourKind: '포카드(4개)', fullHouse: '풀하우스', smallStraight: '스몰스트레이트',
  largeStraight: '라지스트레이트', yahtzee: '야찌(5개)', chance: '찬스',
};
const HINT: Record<Cat, string> = {
  ones: '1의 합', twos: '2의 합', threes: '3의 합', fours: '4의 합', fives: '5의 합', sixes: '6의 합',
  threeKind: '같은 3개↑ → 전체 합', fourKind: '같은 4개↑ → 전체 합', fullHouse: '3+2 → 25',
  smallStraight: '연속 4 → 30', largeStraight: '연속 5 → 40', yahtzee: '같은 5개 → 50', chance: '전체 합',
};

type Card = Partial<Record<Cat, number>>;
type Player = { name: string; bot: boolean; card: Card };

function rollDie() { return 1 + Math.floor(Math.random() * 6); }
function counts(dice: number[]) { const c = [0, 0, 0, 0, 0, 0, 0]; dice.forEach((d) => c[d]++); return c; }
function hasRun(c: number[], len: number) {
  let run = 0;
  for (let v = 1; v <= 6; v++) { run = c[v] ? run + 1 : 0; if (run >= len) return true; }
  return false;
}
function scoreOf(cat: Cat, dice: number[]): number {
  const c = counts(dice); const sum = dice.reduce((a, b) => a + b, 0);
  switch (cat) {
    case 'ones': return c[1] * 1; case 'twos': return c[2] * 2; case 'threes': return c[3] * 3;
    case 'fours': return c[4] * 4; case 'fives': return c[5] * 5; case 'sixes': return c[6] * 6;
    case 'threeKind': return c.some((x) => x >= 3) ? sum : 0;
    case 'fourKind': return c.some((x) => x >= 4) ? sum : 0;
    case 'fullHouse': return (c.some((x) => x === 3) && c.some((x) => x === 2)) ? 25 : 0;
    case 'smallStraight': return hasRun(c, 4) ? 30 : 0;
    case 'largeStraight': return hasRun(c, 5) ? 40 : 0;
    case 'yahtzee': return c.some((x) => x === 5) ? 50 : 0;
    case 'chance': return sum;
  }
}
function upperSum(card: Card) { return UPPER.reduce((a, c) => a + (card[c] ?? 0), 0); }
function total(card: Card) {
  const up = upperSum(card); const bonus = up >= 63 ? 35 : 0;
  return ALL.reduce((a, c) => a + (card[c] ?? 0), 0) + bonus;
}

// 봇: 3굴림 greedy(가장 많은 값 유지) → 남은 족보 중 최고 점수 선택
function botPlay(card: Card): { dice: number[]; cat: Cat; pts: number } {
  let dice = Array.from({ length: 5 }, rollDie);
  for (let r = 0; r < 2; r++) {
    const c = counts(dice);
    let keep = 1; for (let v = 6; v >= 1; v--) if (c[v] >= c[keep]) keep = v; // 최다(동수 시 큰 값)
    dice = dice.map((d) => (d === keep ? d : rollDie()));
  }
  const open = ALL.filter((cat) => card[cat] === undefined);
  let best = open[0], bestPts = -1;
  for (const cat of open) { const p = scoreOf(cat, dice); if (p > bestPts) { bestPts = p; best = cat; } }
  return { dice, cat: best, pts: bestPts };
}

// 주사위 눈 위치(3×3 격자 인덱스). 눈금은 붉은색으로 그려 테두리와 확실히 구분.
const PIPS: Record<number, number[]> = {
  1: [4], 2: [0, 8], 3: [0, 4, 8], 4: [0, 2, 6, 8], 5: [0, 2, 4, 6, 8], 6: [0, 2, 3, 5, 6, 8],
};
function DiceFace({ v }: { v: number }) {
  const on = new Set(PIPS[v] ?? []);
  return (
    <div className="grid grid-cols-3 grid-rows-3 gap-[1px] w-[64%] h-[64%]">
      {Array.from({ length: 9 }, (_, k) => (
        <span key={k} className="flex items-center justify-center">
          {on.has(k) && <span className="block rounded-full bg-red-500 shadow-[0_0_1px_rgba(0,0,0,0.3)]" style={{ width: '72%', aspectRatio: '1/1' }} />}
        </span>
      ))}
    </div>
  );
}

export default function YachtPage() {
  const [screen, setScreen] = useState<'setup' | 'play'>('setup');
  const [nick, setNick] = useState('');
  const [botCount, setBotCount] = useState(1);
  const [players, setPlayers] = useState<Player[]>([]);
  const [cur, setCur] = useState(0);
  const [dice, setDice] = useState<number[]>([1, 1, 1, 1, 1]);
  const [held, setHeld] = useState<boolean[]>([false, false, false, false, false]);
  const [rollsLeft, setRollsLeft] = useState(3);
  const [rolled, setRolled] = useState(false);
  const [log, setLog] = useState<string[]>([]);
  const [over, setOver] = useState(false);
  const [myRank, setMyRank] = useState<number | null>(null);
  const [rows, setRows] = useState<{ rank: number; nick: string; score: number }[]>([]);
  const submitted = useRef(false);

  const loadRanking = useCallback(() => { api<any[]>(`/api/v1/scores/yacht?limit=10`).then(setRows).catch(() => {}); }, []);
  useEffect(() => { try { setNick(localStorage.getItem('arcade_nick') || ''); } catch {}; loadRanking(); }, [loadRanking]);

  const submit = useCallback(async (sc: number) => {
    const n = (nick.trim() || '익명').slice(0, 16);
    try {
      const r = await api<{ myRank: number }>(`/api/v1/scores/yacht`, { method: 'POST', body: JSON.stringify({ nick: n, score: sc }) });
      setMyRank(r.myRank);
    } catch {}
    loadRanking();
  }, [nick, loadRanking]);

  const start = () => {
    const n = nick.trim(); if (!n) return;
    try { localStorage.setItem('arcade_nick', n); } catch {}
    const ps: Player[] = [{ name: n.slice(0, 16), bot: false, card: {} }];
    for (let i = 0; i < botCount; i++) ps.push({ name: `봇${i + 1}`, bot: true, card: {} });
    setPlayers(ps); setCur(0); setLog([]); setOver(false); setMyRank(null);
    submitted.current = false;
    setScreen('play');
    startTurn();
  };

  const startTurn = () => {
    setDice([rollDie(), rollDie(), rollDie(), rollDie(), rollDie()]);
    setHeld([false, false, false, false, false]);
    setRollsLeft(2); setRolled(true); // 첫 굴림 자동
  };

  const roll = () => {
    if (rollsLeft <= 0) return;
    setDice((d) => d.map((v, i) => (held[i] ? v : rollDie())));
    setRollsLeft((r) => r - 1); setRolled(true);
  };
  const toggleHold = (i: number) => { if (!rolled) return; setHeld((h) => h.map((x, k) => (k === i ? !x : x))); };

  const pick = (cat: Cat) => {
    if (players[cur].bot || players[cur].card[cat] !== undefined || !rolled) return;
    const pts = scoreOf(cat, dice);
    const updated = players.map((p, i) => (i === cur ? { ...p, card: { ...p.card, [cat]: pts } } : p));
    setLog((l) => [`${players[cur].name}: ${LABEL[cat]} ${pts}점`, ...l].slice(0, 6));
    advance(updated, cur);
  };

  const advance = (ps: Player[], fromIdx: number) => {
    if (ps.every((p) => ALL.every((c) => p.card[c] !== undefined))) {
      setPlayers(ps); finish(ps); return;
    }
    const next = (fromIdx + 1) % ps.length;
    setPlayers(ps); setCur(next);
    if (ps[next].bot) setTimeout(() => runBot(ps, next), 650);
    else startTurn();
  };

  const runBot = (ps: Player[], idx: number) => {
    const { dice: bd, cat, pts } = botPlay(ps[idx].card);
    setDice(bd); setHeld([true, true, true, true, true]); setRolled(true); setRollsLeft(0);
    const updated = ps.map((p, i) => (i === idx ? { ...p, card: { ...p.card, [cat]: pts } } : p));
    setLog((l) => [`🤖 ${ps[idx].name}: ${LABEL[cat]} ${pts}점`, ...l].slice(0, 6));
    setTimeout(() => advance(updated, idx), 500);
  };

  const finish = (ps: Player[]) => {
    setOver(true);
    const me = ps[0];
    if (!submitted.current && !me.bot) { submitted.current = true; submit(total(me.card)); }
  };

  const meCard = players[cur]?.card ?? {};
  const isMyTurn = screen === 'play' && !over && players[cur] && !players[cur].bot;

  // ── 렌더 ──
  if (screen === 'setup') {
    return (
      <main className="min-h-screen flex flex-col items-center p-4 max-w-md mx-auto w-full">
        <div className="w-full flex items-center gap-2 mb-4">
          <Link href="/" aria-label="홈" className="text-lg text-slate-500 hover:text-slate-800">🏠</Link>
          <h1 className="text-2xl font-extrabold">🎲 야찌</h1>
        </div>
        <div className="w-full space-y-4">
          <input value={nick} onChange={(e) => setNick(e.target.value)} maxLength={16} placeholder="닉네임"
            className="w-full border border-gray-300 rounded-lg px-3 py-2 focus:outline-none focus:border-indigo-500" />
          <div>
            <p className="text-sm text-gray-500 mb-1">봇 인원 (0 = 혼자·랭킹)</p>
            <div className="flex gap-2">
              {[0, 1, 2, 3].map((n) => (
                <button key={n} onClick={() => setBotCount(n)} className={`flex-1 py-2 rounded-lg border-2 font-bold ${botCount === n ? 'border-indigo-500 bg-indigo-50 text-indigo-600' : 'border-gray-200 text-gray-500'}`}>{n === 0 ? '혼자' : `봇${n}`}</button>
              ))}
            </div>
          </div>
          <button onClick={start} disabled={!nick.trim()} className="w-full bg-indigo-600 text-white font-bold py-3 rounded-lg disabled:opacity-40">시작하기</button>
          <details className="rounded-xl border border-gray-200 p-3 text-sm text-gray-500">
            <summary className="font-bold cursor-pointer">📖 규칙</summary>
            <div className="mt-2 space-y-1">
              <p>· 주사위 5개를 <b>최대 3번</b> 굴려요(원하는 주사위는 고정). 한 턴에 <b>족보 1칸</b>을 채웁니다.</p>
              <p>· 위쪽(1~6) 합이 <b>63 이상이면 보너스 +35</b>.</p>
              <p>· 트리플/포카드=전체 합, 풀하우스=25, 스몰=30, 라지=40, 야찌=50, 찬스=전체 합.</p>
              <p>· 13칸을 다 채우면 끝. <b>총점 높은 사람 승</b>(혼자면 랭킹 등록).</p>
            </div>
          </details>
          <div className="rounded-xl border border-gray-200 p-3">
            <p className="text-sm font-bold text-gray-600 mb-2">🏆 랭킹 TOP 10</p>
            {rows.length === 0 ? <p className="text-gray-300 text-sm text-center py-2">아직 기록이 없어요</p> : rows.map((r) => (
              <div key={r.rank} className="flex justify-between text-sm px-1">
                <span className="font-bold text-gray-700">{r.rank <= 3 ? ['🥇', '🥈', '🥉'][r.rank - 1] : `${r.rank}.`} {r.nick}</span>
                <span className="text-indigo-600 font-bold">{r.score}</span>
              </div>
            ))}
          </div>
        </div>
      </main>
    );
  }

  return (
    <main className="min-h-screen flex flex-col items-center p-3 sm:p-5 max-w-lg lg:max-w-4xl mx-auto w-full">
      <div className="w-full flex items-center justify-between mb-3">
        <div className="flex items-center gap-2">
          <Link href="/" aria-label="홈" className="text-lg text-slate-500 hover:text-slate-800">🏠</Link>
          <h1 className="text-xl sm:text-2xl font-extrabold">🎲 야찌</h1>
        </div>
        <button onClick={() => setScreen('setup')} className="text-xs px-2 py-1 rounded bg-slate-200 text-slate-600">나가기</button>
      </div>

      {over && (
        <div className="w-full mb-4 text-center rounded-xl border-2 border-indigo-500 bg-indigo-50 p-3">
          {(() => {
            const ranked = [...players].map((p) => ({ p, t: total(p.card) })).sort((a, b) => b.t - a.t);
            const win = ranked[0];
            return (<>
              <p className="text-lg font-bold">🏆 {win.p.name} 승리! ({win.t}점)</p>
              {myRank != null && <p className="text-sm text-gray-500">내 랭킹: {myRank}위</p>}
              <button onClick={() => setScreen('setup')} className="mt-2 px-5 py-2 rounded-lg bg-indigo-600 text-white font-bold">새 게임</button>
            </>);
          })()}
        </div>
      )}

      <div className="w-full lg:grid lg:grid-cols-[minmax(0,340px)_minmax(0,1fr)] lg:gap-8 lg:items-start">
        {/* 턴/주사위 */}
        {!over && (
          <div className="w-full mb-4 lg:mb-0 lg:sticky lg:top-5">
            <p className="text-center text-sm sm:text-base font-bold mb-3">
              {isMyTurn ? <span className="text-indigo-600">내 차례 · 굴림 {rollsLeft}회 남음</span> : <span className="text-gray-400">🤖 {players[cur]?.name} 차례…</span>}
            </p>
            <div className="flex justify-center gap-2 sm:gap-3 mb-3">
              {dice.map((d, i) => (
                <button key={i} onClick={() => toggleHold(i)} disabled={!isMyTurn}
                  className={`w-12 h-12 sm:w-14 sm:h-14 lg:w-16 lg:h-16 rounded-xl border-2 flex items-center justify-center transition ${held[i] ? 'border-indigo-500 bg-indigo-100 -translate-y-1 shadow' : 'border-gray-300 bg-white'} ${isMyTurn ? 'hover:border-indigo-300' : ''}`}>
                  <DiceFace v={d} />
                </button>
              ))}
            </div>
            {isMyTurn && (
              <div className="flex justify-center">
                <button onClick={roll} disabled={rollsLeft <= 0} className="px-8 py-2.5 rounded-lg bg-indigo-600 text-white font-bold text-base disabled:opacity-40 hover:bg-indigo-700">
                  🎲 굴리기 ({rollsLeft})
                </button>
              </div>
            )}
            {isMyTurn && <p className="text-[11px] sm:text-xs text-gray-400 text-center mt-2">고정할 주사위를 누르고 · 아래 족보를 눌러 점수 확정</p>}

            {log.length > 0 && (
              <div className="hidden lg:block w-full mt-5 rounded-xl border border-gray-100 bg-gray-50 p-3 text-xs text-gray-500 space-y-1">
                {log.map((l, i) => <p key={i}>{l}</p>)}
              </div>
            )}
          </div>
        )}

        {/* 점수판 */}
        <div className={`w-full overflow-x-auto ${over ? 'lg:col-span-2' : ''}`}>
          <table className="w-full text-sm sm:text-base border-collapse">
            <thead>
              <tr>
                <th className="text-left py-1.5 px-1 text-gray-400 font-medium">족보</th>
                {players.map((p, i) => (
                  <th key={i} className={`py-1.5 px-2 text-center ${i === cur && !over ? 'text-indigo-600' : 'text-gray-500'}`}>{p.bot ? '🤖' : ''}{p.name}</th>
                ))}
              </tr>
            </thead>
            <tbody>
              {ALL.map((cat, idx) => (
                <tr key={cat} className={`border-t border-gray-100 ${idx === 6 ? 'border-t-2 border-gray-300' : ''}`}>
                  <td className="py-1.5 px-1">
                    <button onClick={() => pick(cat)} disabled={!isMyTurn || meCard[cat] !== undefined}
                      className={`text-left ${isMyTurn && meCard[cat] === undefined ? 'text-gray-800 font-bold' : 'text-gray-400'}`}>
                      {LABEL[cat]} <span className="text-[10px] sm:text-[11px] text-gray-300">{HINT[cat]}</span>
                    </button>
                  </td>
                  {players.map((p, i) => {
                    const filled = p.card[cat];
                    const preview = i === cur && isMyTurn && filled === undefined ? scoreOf(cat, dice) : null;
                    return (
                      <td key={i} className="py-1.5 px-2 text-center">
                        {filled !== undefined
                          ? <span className="font-bold text-gray-700">{filled}</span>
                          : preview != null
                            ? <button onClick={() => pick(cat)} className="text-indigo-500 font-bold hover:underline">+{preview}</button>
                            : <span className="text-gray-200">·</span>}
                      </td>
                    );
                  })}
                </tr>
              ))}
              <tr className="border-t-2 border-gray-300 bg-gray-50">
                <td className="py-1.5 px-1 font-bold text-gray-500">보너스(63↑)</td>
                {players.map((p, i) => <td key={i} className="py-1.5 px-2 text-center text-gray-400">{upperSum(p.card) >= 63 ? '+35' : `${upperSum(p.card)}/63`}</td>)}
              </tr>
              <tr className="bg-indigo-50">
                <td className="py-2 px-1 font-extrabold">합계</td>
                {players.map((p, i) => <td key={i} className="py-2 px-2 text-center font-extrabold text-indigo-700 text-base sm:text-lg">{total(p.card)}</td>)}
              </tr>
            </tbody>
          </table>

          {log.length > 0 && (
            <div className="lg:hidden w-full mt-4 text-xs text-gray-400 space-y-0.5">
              {log.map((l, i) => <p key={i}>{l}</p>)}
            </div>
          )}
        </div>
      </div>
    </main>
  );
}
