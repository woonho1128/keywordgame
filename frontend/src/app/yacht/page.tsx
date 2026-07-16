'use client';

import Link from 'next/link';
import { useCallback, useEffect, useRef, useState } from 'react';
import { api } from '@/lib/api';
import RoomChat from '@/components/RoomChat';

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

type PlayerView = { seat: number; name: string; bot: boolean; botLevel: string | null; host: boolean; me: boolean; card: Partial<Record<Cat, number>>; upper: number; total: number; left: boolean };
type State = {
  phase: string; isHost: boolean; joined: boolean;
  players: PlayerView[];
  turnSeat: number; turnName: string | null; myTurn: boolean; mySeat: number;
  dice: number[]; held: boolean[]; rollsLeft: number; rolled: boolean;
  winner: string | null; deadline: number; serverNow: number;
};
type Room = { code: string; status: string; playerCount: number; host: string };

function cid(): string {
  if (typeof window === 'undefined') return '';
  let id = localStorage.getItem('yacht_client_id');
  if (!id) { id = Math.random().toString(36).slice(2) + Date.now().toString(36); localStorage.setItem('yacht_client_id', id); }
  return id;
}

// 미리보기용 클라이언트 점수 계산(서버와 동일 규칙)
function counts(dice: number[]) { const c = [0, 0, 0, 0, 0, 0, 0]; dice.forEach((d) => c[d]++); return c; }
function hasRun(c: number[], len: number) { let run = 0; for (let v = 1; v <= 6; v++) { run = c[v] ? run + 1 : 0; if (run >= len) return true; } return false; }
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

// 주사위 눈(붉은 점)
const PIPS: Record<number, number[]> = { 1: [4], 2: [0, 8], 3: [0, 4, 8], 4: [0, 2, 6, 8], 5: [0, 2, 4, 6, 8], 6: [0, 2, 3, 5, 6, 8] };
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
  const id = useRef('');
  const [nick, setNick] = useState('');
  const [screen, setScreen] = useState<'entry' | 'lobby' | 'game'>('entry');
  const [roomCode, setRoomCode] = useState<string | null>(null);
  const [joinCode, setJoinCode] = useState('');
  const [rooms, setRooms] = useState<Room[]>([]);
  const [ss, setSs] = useState<State | null>(null);
  const [botLevel, setBotLevel] = useState('NORMAL');
  const [remaining, setRemaining] = useState(0);
  const [myRank, setMyRank] = useState<number | null>(null);
  const [ranking, setRanking] = useState<{ rank: number; nick: string; score: number }[]>([]);
  const roomRef = useRef<string | null>(null); roomRef.current = roomCode;
  const offsetRef = useRef(0);
  const submitted = useRef(false);

  useEffect(() => { id.current = cid(); try { setNick(localStorage.getItem('arcade_nick') || ''); } catch {} }, []);

  const loadRooms = useCallback(async () => { try { setRooms(await api(`/api/v1/yacht-room/rooms`)); } catch {} }, []);
  const loadRanking = useCallback(() => { api<any[]>(`/api/v1/scores/yacht?limit=10`).then(setRanking).catch(() => {}); }, []);
  useEffect(() => { if (screen === 'entry') { loadRooms(); loadRanking(); const t = setInterval(loadRooms, 3000); return () => clearInterval(t); } }, [screen, loadRooms, loadRanking]);

  // 폴링
  useEffect(() => {
    if (screen === 'entry' || !roomCode) return;
    let alive = true;
    const poll = async () => {
      try {
        const s = await api<State>(`/api/v1/yacht-room/me?roomCode=${roomCode}&clientId=${id.current}`);
        if (!alive) return;
        offsetRef.current = s.serverNow - Date.now();
        setSs(s);
        if (s.phase !== 'LOBBY' && screen === 'lobby') setScreen('game');
        if (s.phase === 'LOBBY' && screen === 'game') setScreen('lobby');
      } catch {}
    };
    const t = setInterval(poll, 1000); poll();
    return () => { alive = false; clearInterval(t); };
  }, [screen, roomCode]);

  // 남은 시간
  useEffect(() => {
    const t = setInterval(() => {
      const dl = ss?.deadline ?? 0;
      setRemaining(dl > 0 ? Math.max(0, Math.ceil((dl - (Date.now() + offsetRef.current)) / 1000)) : 0);
    }, 250);
    return () => clearInterval(t);
  }, [ss?.deadline]);

  // 게임 종료 시 내 총점 랭킹 등록(1회)
  useEffect(() => {
    if (ss?.phase !== 'ENDED' || submitted.current) return;
    const me = ss.players.find((p) => p.me);
    if (!me) return;
    submitted.current = true;
    (async () => {
      const n = (nick.trim() || me.name || '익명').slice(0, 16);
      try {
        const r = await api<{ myRank: number }>(`/api/v1/scores/yacht`, { method: 'POST', body: JSON.stringify({ nick: n, score: me.total }) });
        setMyRank(r.myRank);
      } catch {}
      loadRanking();
    })();
  }, [ss?.phase, ss?.players, nick, loadRanking]);

  const saveNick = (n: string) => { try { localStorage.setItem('arcade_nick', n); } catch {} };
  const create = async () => {
    const n = nick.trim(); if (!n) return; saveNick(n);
    try {
      const res = await api<{ roomCode: string; state: State }>(`/api/v1/yacht-room/new?clientId=${id.current}`, { method: 'POST', body: JSON.stringify({ nick: n }) });
      setRoomCode(res.roomCode); setSs(res.state); setScreen('lobby'); submitted.current = false; setMyRank(null);
    } catch (e: any) { alert(e?.message || '방 생성 실패'); }
  };
  const join = async (code: string, nickOverride?: string) => {
    const n = (nickOverride ?? nick).trim(); if (!n || !code) return; saveNick(n); setNick(n);
    try {
      const s = await api<State>(`/api/v1/yacht-room/join?roomCode=${code}&clientId=${id.current}`, { method: 'POST', body: JSON.stringify({ nick: n }) });
      setRoomCode(code.toUpperCase()); setSs(s); setScreen(s.phase === 'LOBBY' ? 'lobby' : 'game'); submitted.current = false; setMyRank(null);
    } catch (e: any) { alert(e?.message || '참가 실패'); }
  };

  // 메인에서 코드로 바로 입장(?join=CODE)
  useEffect(() => {
    const j = new URLSearchParams(window.location.search).get('join');
    if (!j) return;
    let n = ''; try { n = (localStorage.getItem('arcade_nick') || '').trim(); } catch {}
    if (!n) return;
    id.current = id.current || cid();
    join(j.toUpperCase(), n);
    try { window.history.replaceState({}, '', '/yacht'); } catch {}
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, []);
  const addBot = async () => { try { setSs(await api(`/api/v1/yacht-room/add-bot?roomCode=${roomCode}&clientId=${id.current}&level=${botLevel}`, { method: 'POST', body: '{}' })); } catch (e: any) { alert(e?.message); } };
  const startMatch = async () => { try { setSs(await api(`/api/v1/yacht-room/start?roomCode=${roomCode}&clientId=${id.current}`, { method: 'POST', body: '{}' })); setScreen('game'); } catch (e: any) { alert(e?.message); } };
  const roll = async () => { try { setSs(await api(`/api/v1/yacht-room/roll?roomCode=${roomCode}&clientId=${id.current}`, { method: 'POST', body: '{}' })); } catch (e: any) { alert(e?.message); } };
  const hold = async (i: number) => { try { setSs(await api(`/api/v1/yacht-room/hold?roomCode=${roomCode}&clientId=${id.current}&index=${i}`, { method: 'POST', body: '{}' })); } catch (e: any) { alert(e?.message); } };
  const pick = async (cat: Cat) => { try { setSs(await api(`/api/v1/yacht-room/pick?roomCode=${roomCode}&clientId=${id.current}&cat=${cat}`, { method: 'POST', body: '{}' })); } catch (e: any) { alert(e?.message); } };
  const leave = async () => { const rc = roomRef.current; if (rc) { try { await api(`/api/v1/yacht-room/leave?roomCode=${rc}&clientId=${id.current}`, { method: 'POST', body: '{}' }); } catch {} } setScreen('entry'); setRoomCode(null); setSs(null); };

  const home = <Link href="/" aria-label="홈으로" className="text-lg leading-none text-slate-500 hover:text-slate-800 dark:hover:text-slate-100">🏠</Link>;
  const ended = ss?.phase === 'ENDED';
  const dice = ss?.dice ?? [];
  const players = ss?.players ?? [];
  const mySeat = ss?.mySeat ?? -1;
  const capMax = 8;

  return (
    <main className="min-h-screen flex flex-col items-center p-3 sm:p-5 max-w-lg lg:max-w-4xl mx-auto w-full text-slate-800 dark:text-slate-100">
      <div className="w-full flex items-center justify-between mb-3">
        <div className="flex items-center gap-2">{home}<h1 className="text-xl sm:text-2xl font-extrabold">🎲 야찌</h1></div>
        {roomCode && <button onClick={leave} className="text-xs px-2 py-1 rounded bg-slate-200 dark:bg-slate-700 text-slate-600 dark:text-slate-100">나가기</button>}
      </div>

      {/* ── 입장 ── */}
      {screen === 'entry' && (
        <div className="w-full max-w-md mx-auto space-y-4">
          <input value={nick} onChange={(e) => setNick(e.target.value)} maxLength={16} placeholder="닉네임 (필수)"
            className={`w-full border bg-transparent rounded-lg px-3 py-2 focus:outline-none focus:border-indigo-500 ${nick.trim() ? 'border-slate-300 dark:border-slate-600' : 'border-indigo-400'}`} />
          <button onClick={create} disabled={!nick.trim()} className="w-full bg-indigo-600 text-white font-bold py-3 rounded-lg disabled:opacity-40">방 만들기</button>
          <div className="flex gap-2">
            <input value={joinCode} onChange={(e) => setJoinCode(e.target.value.toUpperCase())} maxLength={4} placeholder="코드" className="flex-1 border border-slate-300 dark:border-slate-600 bg-transparent rounded-lg px-3 py-2 uppercase" />
            <button onClick={() => join(joinCode)} disabled={!nick.trim() || !joinCode} className="px-5 bg-slate-700 text-white font-bold rounded-lg disabled:opacity-40">참가</button>
          </div>

          <details className="rounded-xl border border-slate-200 dark:border-slate-700 p-3 text-sm text-slate-500 dark:text-slate-400">
            <summary className="font-bold cursor-pointer select-none">📖 규칙</summary>
            <div className="mt-2 space-y-1">
              <p>· 자기 차례에 주사위 5개를 <b>최대 3번</b> 굴려요(고정 가능). 한 턴에 <b>족보 1칸</b> 확정.</p>
              <p>· 위쪽(1~6) 합이 <b>63 이상이면 보너스 +35</b>.</p>
              <p>· 13칸을 다 채우면 끝. <b>총점 높은 사람 승</b>. 봇도 넣을 수 있어요.</p>
            </div>
          </details>

          <div className="rounded-xl border border-slate-200 dark:border-slate-700 p-3">
            <p className="text-sm font-bold text-slate-600 dark:text-slate-300 mb-2">🎮 열린 방</p>
            {rooms.length === 0 ? <p className="text-slate-400 text-sm text-center py-2">방이 없어요. 만들어보세요!</p> : (
              <div className="space-y-1">
                {rooms.map((r) => (
                  <button key={r.code} onClick={() => join(r.code)} disabled={!nick.trim()} className="w-full flex justify-between text-sm py-1.5 px-2 rounded hover:bg-indigo-500/10 disabled:opacity-40">
                    <span className="font-bold">{r.code} · {r.host}</span>
                    <span className="text-slate-500 dark:text-slate-400">{r.status === 'WAITING' ? '모집중' : r.status === 'PLAYING' ? '진행중' : '종료'} · {r.playerCount}명</span>
                  </button>
                ))}
              </div>
            )}
          </div>

          <div className="rounded-xl border border-slate-200 dark:border-slate-700 p-3">
            <p className="text-sm font-bold text-slate-600 dark:text-slate-300 mb-2">🏆 랭킹 TOP 10</p>
            {ranking.length === 0 ? <p className="text-slate-400 text-sm text-center py-2">아직 기록이 없어요</p> : ranking.map((r) => (
              <div key={r.rank} className="flex justify-between text-sm px-1">
                <span className="font-bold">{r.rank <= 3 ? ['🥇', '🥈', '🥉'][r.rank - 1] : `${r.rank}.`} {r.nick}</span>
                <span className="text-indigo-600 dark:text-indigo-400 font-bold">{r.score}</span>
              </div>
            ))}
          </div>
        </div>
      )}

      {/* ── 로비 ── */}
      {screen === 'lobby' && ss && (
        <div className="w-full max-w-md mx-auto space-y-4">
          <div className="text-center">
            <p className="text-sm text-slate-500 dark:text-slate-400">방 코드</p>
            <p className="text-3xl font-extrabold tracking-widest text-indigo-600 dark:text-indigo-400">{roomCode}</p>
            <p className="text-xs text-slate-500 dark:text-slate-400">{players.filter((p) => !p.left).length}/{capMax}명 · 2명 이상이면 시작</p>
          </div>
          <div className="rounded-xl border border-slate-200 dark:border-slate-700 p-3 space-y-1">
            {players.filter((p) => !p.left).map((p) => (
              <div key={p.seat} className="flex justify-between text-sm px-1 py-0.5">
                <span className="font-bold">{p.host ? '👑 ' : ''}{p.bot ? '🤖 ' : ''}{p.name}{p.me ? ' (나)' : ''}</span>
                <span className="text-slate-500 dark:text-slate-400">{p.bot ? (p.botLevel === 'EASY' ? '초급' : p.botLevel === 'HARD' ? '고급' : '중급') : ''}</span>
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
              <button onClick={startMatch} disabled={players.filter((p) => !p.left).length < 2} className="w-full bg-indigo-600 text-white font-bold py-3 rounded-lg disabled:opacity-40">시작하기</button>
            </div>
          ) : <p className="text-center text-sm text-slate-500 dark:text-slate-400">방장이 시작하기를 기다리는 중…</p>}
        </div>
      )}

      {/* ── 게임/종료 ── */}
      {screen === 'game' && ss && (
        <div className="w-full">
          {ended && (() => {
            const ranked = [...players].filter((p) => !p.left).sort((a, b) => b.total - a.total);
            const win = ranked[0];
            return (
              <div className="w-full mb-4 text-center rounded-xl border-2 border-indigo-500 bg-indigo-50 dark:bg-indigo-500/10 p-3">
                <p className="text-lg font-bold">🏆 {win?.name} 승리! ({win?.total}점)</p>
                {myRank != null && <p className="text-sm text-slate-500 dark:text-slate-400">내 랭킹: {myRank}위</p>}
                <button onClick={leave} className="mt-2 px-5 py-2 rounded-lg bg-indigo-600 text-white font-bold">나가기</button>
              </div>
            );
          })()}

          <div className="w-full lg:grid lg:grid-cols-[minmax(0,340px)_minmax(0,1fr)] lg:gap-8 lg:items-start">
            {/* 주사위/턴 */}
            {!ended && (
              <div className="w-full mb-4 lg:mb-0 lg:sticky lg:top-5">
                <p className="text-center text-sm sm:text-base font-bold mb-3">
                  {ss.myTurn
                    ? <span className="text-indigo-600 dark:text-indigo-400">내 차례 · 굴림 {ss.rollsLeft}회 남음 {remaining > 0 && <span className="text-slate-400 font-normal">({remaining}s)</span>}</span>
                    : <span className="text-slate-500 dark:text-slate-400">{players[ss.turnSeat]?.bot ? '🤖 ' : ''}{ss.turnName} 차례… {remaining > 0 && `(${remaining}s)`}</span>}
                </p>
                <div className="flex justify-center gap-2 sm:gap-3 mb-3">
                  {dice.map((d, i) => (
                    <button key={i} onClick={() => ss.myTurn && hold(i)} disabled={!ss.myTurn}
                      className={`w-12 h-12 sm:w-14 sm:h-14 lg:w-16 lg:h-16 rounded-xl border-2 flex items-center justify-center transition ${ss.held[i] ? 'border-indigo-500 bg-indigo-100 dark:bg-indigo-500/20 -translate-y-1 shadow' : 'border-slate-300 dark:border-slate-600 bg-white dark:bg-slate-800'} ${ss.myTurn ? 'hover:border-indigo-300' : ''}`}>
                      <DiceFace v={d} />
                    </button>
                  ))}
                </div>
                {ss.myTurn && (
                  <div className="flex justify-center">
                    <button onClick={roll} disabled={ss.rollsLeft <= 0} className="px-8 py-2.5 rounded-lg bg-indigo-600 text-white font-bold text-base disabled:opacity-40 hover:bg-indigo-700">
                      🎲 굴리기 ({ss.rollsLeft})
                    </button>
                  </div>
                )}
                {ss.myTurn && <p className="text-[11px] sm:text-xs text-slate-400 text-center mt-2">고정할 주사위를 누르고 · 아래 족보를 눌러 점수 확정</p>}
              </div>
            )}

            {/* 점수판 */}
            <div className={`w-full overflow-x-auto ${ended ? 'lg:col-span-2' : ''}`}>
              <table className="w-full text-sm sm:text-base border-collapse">
                <thead>
                  <tr>
                    <th className="text-left py-1.5 px-1 text-slate-400 font-medium">족보</th>
                    {players.filter((p) => !p.left).map((p) => (
                      <th key={p.seat} className={`py-1.5 px-2 text-center ${p.seat === ss.turnSeat && !ended ? 'text-indigo-600 dark:text-indigo-400' : 'text-slate-500 dark:text-slate-400'}`}>{p.bot ? '🤖' : ''}{p.name}{p.me ? '*' : ''}</th>
                    ))}
                  </tr>
                </thead>
                <tbody>
                  {ALL.map((cat, idx) => (
                    <tr key={cat} className={`border-t border-slate-100 dark:border-slate-800 ${idx === 6 ? 'border-t-2 border-slate-300 dark:border-slate-600' : ''}`}>
                      <td className="py-1.5 px-1">
                        <span className="font-bold">{LABEL[cat]}</span> <span className="text-[10px] sm:text-[11px] text-slate-400">{HINT[cat]}</span>
                      </td>
                      {players.filter((p) => !p.left).map((p) => {
                        const filled = p.card[cat];
                        const canPreview = ss.myTurn && p.me && filled === undefined;
                        const preview = canPreview ? scoreOf(cat, dice) : null;
                        return (
                          <td key={p.seat} className="py-1.5 px-2 text-center">
                            {filled !== undefined
                              ? <span className="font-bold">{filled}</span>
                              : preview != null
                                ? <button onClick={() => pick(cat)} className="text-indigo-500 dark:text-indigo-400 font-bold hover:underline">+{preview}</button>
                                : <span className="text-slate-300 dark:text-slate-600">·</span>}
                          </td>
                        );
                      })}
                    </tr>
                  ))}
                  <tr className="border-t-2 border-slate-300 dark:border-slate-600 bg-slate-50 dark:bg-slate-800/50">
                    <td className="py-1.5 px-1 font-bold text-slate-500 dark:text-slate-400">보너스(63↑)</td>
                    {players.filter((p) => !p.left).map((p) => <td key={p.seat} className="py-1.5 px-2 text-center text-slate-400">{p.upper >= 63 ? '+35' : `${p.upper}/63`}</td>)}
                  </tr>
                  <tr className="bg-indigo-50 dark:bg-indigo-500/10">
                    <td className="py-2 px-1 font-extrabold">합계</td>
                    {players.filter((p) => !p.left).map((p) => <td key={p.seat} className="py-2 px-2 text-center font-extrabold text-indigo-700 dark:text-indigo-400 text-base sm:text-lg">{p.total}</td>)}
                  </tr>
                </tbody>
              </table>
            </div>
          </div>
        </div>
      )}

      {roomCode && <RoomChat game="yacht" roomCode={roomCode} clientId={id.current} nick={nick} />}
    </main>
  );
}
