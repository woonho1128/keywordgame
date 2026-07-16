'use client';

import Link from 'next/link';
import { useCallback, useEffect, useRef, useState } from 'react';
import { api } from '@/lib/api';
import RoomChat from '@/components/RoomChat';

type FrontCard = { value: number | null; revealed: boolean };
type PlayerView = { seat: number; name: string; bot: boolean; host: boolean; me: boolean; handCount: number; front: FrontCard[]; inMojo: boolean; frontRevealed: number; total: number; roundScore: number; left: boolean; hasMojo: boolean };
type State = {
  phase: string; doublePile: boolean; isHost: boolean; joined: boolean;
  players: PlayerView[];
  discardTops: number[]; discardSizes: number[]; drawCount: number;
  turnSeat: number; turnName: string | null; myTurn: boolean; mySeat: number;
  myInMojo: boolean; mustChain: boolean; myHand: number[];
  roundNum: number; mojoHolderSeat: number; winner: string | null; deadline: number; serverNow: number;
};
type Room = { code: string; status: string; playerCount: number; host: string };

function cid(): string {
  if (typeof window === 'undefined') return '';
  let id = localStorage.getItem('mojo_client_id');
  if (!id) { id = Math.random().toString(36).slice(2) + Date.now().toString(36); localStorage.setItem('mojo_client_id', id); }
  return id;
}

const COLORS = ['#3b82f6', '#22c55e', '#eab308', '#f97316', '#ef4444']; // 파랑 초록 노랑 주황 빨강
const colorOf = (n: number) => (n <= 1 ? 0 : n <= 4 ? 1 : n <= 7 ? 2 : n <= 10 ? 3 : 4);
function NumCard({ n, sm, onClick, dim, sel }: { n: number | null; sm?: boolean; onClick?: () => void; dim?: boolean; sel?: boolean }) {
  const size = sm ? 'w-8 h-11 text-base lg:w-11 lg:h-16 lg:text-xl' : 'w-12 h-[68px] sm:w-14 sm:h-20 lg:w-[72px] lg:h-[104px] text-2xl sm:text-3xl lg:text-4xl';
  if (n === null) return <div className={`${size} rounded-md border-2 border-slate-400 bg-slate-300 dark:bg-slate-600 flex items-center justify-center font-bold text-slate-500`}>?</div>;
  return (
    <button onClick={onClick} disabled={!onClick}
      className={`${size} rounded-md border-2 flex items-center justify-center font-extrabold text-white shrink-0 transition ${sel ? '-translate-y-2 ring-2 ring-slate-800 dark:ring-white' : ''} ${dim ? 'opacity-45' : ''} ${onClick ? 'active:scale-95 hover:-translate-y-1 cursor-pointer' : 'cursor-default'}`}
      style={{ background: COLORS[colorOf(n)], borderColor: 'rgba(0,0,0,0.2)' }}>
      {n}
    </button>
  );
}

export default function MojoPage() {
  const id = useRef('');
  const [nick, setNick] = useState('');
  const [screen, setScreen] = useState<'entry' | 'lobby' | 'game'>('entry');
  const [roomCode, setRoomCode] = useState<string | null>(null);
  const [doublePile, setDoublePile] = useState(false);
  const [joinCode, setJoinCode] = useState('');
  const [rooms, setRooms] = useState<Room[]>([]);
  const [ss, setSs] = useState<State | null>(null);
  const [botLevel, setBotLevel] = useState('NORMAL');
  const [remaining, setRemaining] = useState(0);
  const [selPile, setSelPile] = useState(0);
  const roomRef = useRef<string | null>(null); roomRef.current = roomCode;
  const offsetRef = useRef(0);

  useEffect(() => { id.current = cid(); try { setNick(localStorage.getItem('arcade_nick') || ''); } catch {} }, []);

  const loadRooms = useCallback(async () => { try { setRooms(await api(`/api/v1/mojo/rooms`)); } catch {} }, []);
  useEffect(() => { if (screen === 'entry') { loadRooms(); const t = setInterval(loadRooms, 3000); return () => clearInterval(t); } }, [screen, loadRooms]);

  useEffect(() => {
    if (screen === 'entry' || !roomCode) return;
    let alive = true;
    const poll = async () => {
      try {
        const s = await api<State>(`/api/v1/mojo/me?roomCode=${roomCode}&clientId=${id.current}`);
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

  useEffect(() => {
    const t = setInterval(() => {
      const dl = ss?.deadline ?? 0;
      setRemaining(dl > 0 ? Math.max(0, Math.ceil((dl - (Date.now() + offsetRef.current)) / 1000)) : 0);
    }, 250);
    return () => clearInterval(t);
  }, [ss?.deadline]);

  const saveNick = (n: string) => { try { localStorage.setItem('arcade_nick', n); } catch {} };
  const create = async () => {
    const n = nick.trim(); if (!n) return; saveNick(n);
    try {
      const res = await api<{ roomCode: string; state: State }>(`/api/v1/mojo/new?clientId=${id.current}`, { method: 'POST', body: JSON.stringify({ nick: n, doublePile }) });
      setRoomCode(res.roomCode); setSs(res.state); setScreen('lobby');
    } catch (e: any) { alert(e?.message || '방 생성 실패'); }
  };
  const join = async (code: string, nickOverride?: string) => {
    const n = (nickOverride ?? nick).trim(); if (!n || !code) return; setNick(n); saveNick(n);
    try {
      const s = await api<State>(`/api/v1/mojo/join?roomCode=${code}&clientId=${id.current}`, { method: 'POST', body: JSON.stringify({ nick: n }) });
      setRoomCode(code.toUpperCase()); setSs(s); setScreen(s.phase === 'LOBBY' ? 'lobby' : 'game');
    } catch (e: any) { alert(e?.message || '참가 실패'); }
  };
  useEffect(() => {
    const j = new URLSearchParams(window.location.search).get('join');
    if (!j) return;
    let n = ''; try { n = (localStorage.getItem('arcade_nick') || '').trim(); } catch {}
    if (!n) return;
    id.current = id.current || cid();
    join(j.toUpperCase(), n);
    try { window.history.replaceState({}, '', '/mojo'); } catch {}
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, []);

  const addBot = async () => { try { setSs(await api(`/api/v1/mojo/add-bot?roomCode=${roomCode}&clientId=${id.current}&level=${botLevel}`, { method: 'POST', body: '{}' })); } catch (e: any) { alert(e?.message); } };
  const startMatch = async () => { try { setSs(await api(`/api/v1/mojo/start?roomCode=${roomCode}&clientId=${id.current}`, { method: 'POST', body: '{}' })); setScreen('game'); } catch (e: any) { alert(e?.message); } };
  const play = async (value: number) => { try { setSs(await api(`/api/v1/mojo/play?roomCode=${roomCode}&clientId=${id.current}&value=${value}&pile=${selPile}`, { method: 'POST', body: '{}' })); } catch (e: any) { alert(e?.message); } };
  const reveal = async () => { try { setSs(await api(`/api/v1/mojo/reveal?roomCode=${roomCode}&clientId=${id.current}`, { method: 'POST', body: '{}' })); } catch (e: any) { alert(e?.message); } };
  const leave = async () => { const rc = roomRef.current; if (rc) { try { await api(`/api/v1/mojo/leave?roomCode=${rc}&clientId=${id.current}`, { method: 'POST', body: '{}' }); } catch {} } setScreen('entry'); setRoomCode(null); setSs(null); };

  const home = <Link href="/" aria-label="홈으로" className="text-lg leading-none text-slate-500 hover:text-slate-800 dark:hover:text-slate-100">🏠</Link>;
  const ended = ss?.phase === 'ENDED';
  const players = ss?.players ?? [];

  return (
    <main className="min-h-screen flex flex-col items-center p-3 sm:p-5 max-w-2xl lg:max-w-3xl mx-auto w-full text-slate-800 dark:text-slate-100">
      <div className="w-full flex items-center justify-between mb-3">
        <div className="flex items-center gap-2">{home}<h1 className="text-xl sm:text-2xl font-extrabold">🎴 모죠</h1></div>
        {roomCode && <button onClick={leave} className="text-xs px-2 py-1 rounded bg-slate-200 dark:bg-slate-700 text-slate-600 dark:text-slate-100">나가기</button>}
      </div>

      {/* ── 입장 ── */}
      {screen === 'entry' && (
        <div className="w-full max-w-md mx-auto space-y-4">
          <input value={nick} onChange={(e) => setNick(e.target.value)} maxLength={16} placeholder="닉네임 (필수)"
            className={`w-full border bg-transparent rounded-lg px-3 py-2 focus:outline-none focus:border-indigo-500 ${nick.trim() ? 'border-slate-300 dark:border-slate-600' : 'border-indigo-400'}`} />
          <label className="flex items-center gap-2 text-sm rounded-lg border border-slate-200 dark:border-slate-700 px-3 py-2">
            <input type="checkbox" checked={doublePile} onChange={(e) => setDoublePile(e.target.checked)} />
            🌀 이중 버림더미 변형 (더미 2개 중 선택해 내기)
          </label>
          <button onClick={create} disabled={!nick.trim()} className="w-full bg-indigo-600 text-white font-bold py-3 rounded-lg disabled:opacity-40">방 만들기</button>
          <div className="flex gap-2">
            <input value={joinCode} onChange={(e) => setJoinCode(e.target.value.toUpperCase())} maxLength={4} placeholder="코드" className="flex-1 border border-slate-300 dark:border-slate-600 bg-transparent rounded-lg px-3 py-2 uppercase" />
            <button onClick={() => join(joinCode)} disabled={!nick.trim() || !joinCode} className="px-5 bg-slate-700 text-white font-bold rounded-lg disabled:opacity-40">참가</button>
          </div>

          <details className="rounded-xl border border-slate-200 dark:border-slate-700 p-3 text-sm text-slate-500 dark:text-slate-400">
            <summary className="font-bold cursor-pointer select-none">📖 규칙</summary>
            <div className="mt-2 space-y-1">
              <p>· 카드 숫자=벌점, 색은 숫자 구간(🔵0-1 🟢2-4 🟡5-7 🟠8-10 🔴11-12).</p>
              <p>· 차례에 카드 1장 내고 <b>직전 버림더미와 비교</b>: 낮으면 종료, <b>높으면 1장 뽑고</b> 종료, 같으면 즉시 한 장 더.</p>
              <p>· 손패 ≤ 3장(2인 2장)이 되면 <b>모죠타임</b> — 남은 손패를 앞에 뒷면으로 깔고 매 차례 1장씩 공개.</p>
              <p>· 점수: 앞면+손패에서 <b>색상별 최고 숫자만</b> 합산. 모죠 카드 보유자는 최저점이면 0, 아니면 +10.</p>
              <p>· 누적 <b>50점</b> 도달 시 종료, <b>최저 총점 승</b>.</p>
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
        </div>
      )}

      {/* ── 로비 ── */}
      {screen === 'lobby' && ss && (
        <div className="w-full max-w-md mx-auto space-y-4">
          <div className="text-center">
            <p className="text-sm text-slate-500 dark:text-slate-400">방 코드</p>
            <p className="text-3xl font-extrabold tracking-widest text-indigo-600 dark:text-indigo-400">{roomCode}</p>
            <p className="text-xs text-slate-500 dark:text-slate-400">{players.filter((p) => !p.left).length}/8명 · 2명 이상이면 시작 {ss.doublePile && '· 🌀이중더미'}</p>
          </div>
          <div className="rounded-xl border border-slate-200 dark:border-slate-700 p-3 space-y-1">
            {players.filter((p) => !p.left).map((p) => (
              <div key={p.seat} className="flex justify-between text-sm px-1 py-0.5">
                <span className="font-bold">{p.host ? '👑 ' : ''}{p.bot ? '🤖 ' : ''}{p.name}{p.me ? ' (나)' : ''}</span>
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
        <div className="w-full space-y-4">
          <details className="rounded-lg border border-slate-200 dark:border-slate-700 px-3 py-2 text-sm text-slate-500 dark:text-slate-400">
            <summary className="font-bold cursor-pointer select-none text-slate-600 dark:text-slate-300">📖 규칙 보기</summary>
            <div className="mt-2 space-y-1">
              <p>· 카드 숫자=벌점, 색은 숫자 구간(🔵0-1 🟢2-4 🟡5-7 🟠8-10 🔴11-12).</p>
              <p>· 차례에 카드 1장 내고 <b>직전 버림더미와 비교</b>: 낮으면 종료, <b>높으면 1장 뽑고</b> 종료, 같으면 즉시 한 장 더.</p>
              <p>· 손패 ≤ 3장(2인 2장)이 되면 <b>모죠타임</b> — 남은 손패를 뒷면으로 깔고 매 차례 1장씩 공개.</p>
              <p>· 점수: 앞면+손패에서 <b>색상별 최고 숫자만</b> 합산. 🟣모죠 카드 보유자는 최저점이면 0, 아니면 +10.</p>
              <p>· 누적 <b>50점</b> 도달 시 종료, <b>최저 총점 승</b>{ss.doublePile && ' · 🌀이중더미: 낼 더미를 골라서 냄'}.</p>
            </div>
          </details>

          {ended ? (
            <div className="w-full text-center rounded-xl border-2 border-indigo-500 bg-indigo-50 dark:bg-indigo-500/10 p-3">
              <p className="text-lg font-bold">🏆 {ss.winner} 승리!</p>
              <p className="text-xs text-slate-500 dark:text-slate-400 mt-1">최저 총점이 이겨요</p>
              <button onClick={leave} className="mt-2 px-5 py-2 rounded-lg bg-indigo-600 text-white font-bold">나가기</button>
            </div>
          ) : (
            <div className="text-center text-sm font-bold">
              {ss.myTurn
                ? (ss.myInMojo
                    ? <span className="text-indigo-600 dark:text-indigo-400">내 차례 · 카드 공개 {remaining > 0 && <span className="text-slate-400 font-normal">({remaining}s 후 자동)</span>}</span>
                    : <span className="text-indigo-600 dark:text-indigo-400">내 차례 {ss.mustChain && '· 이어서 한 장 더!'} {remaining > 0 && <span className="text-slate-400 font-normal">({remaining}s)</span>}</span>)
                : <span className="text-slate-500 dark:text-slate-400">{players[ss.turnSeat]?.bot ? '🤖 ' : ''}{ss.turnName} 차례… {remaining > 0 && `(${remaining}s)`}</span>}
              <span className="ml-2 text-xs text-slate-400 font-normal">R{ss.roundNum}</span>
            </div>
          )}

          {/* 버림더미 / 뽑기 */}
          {!ended && (
            <div className="flex items-center justify-center gap-4">
              <div className="flex gap-3">
                {ss.discardTops.map((top, i) => (
                  <div key={i} className="flex flex-col items-center gap-1">
                    <div onClick={() => ss.doublePile && setSelPile(i)} className={ss.doublePile ? 'cursor-pointer' : ''}>
                      <NumCard n={top} sel={ss.doublePile && selPile === i && ss.myTurn && !ss.myInMojo} />
                    </div>
                    <span className="text-[10px] text-slate-400">더미{ss.doublePile ? i + 1 : ''} ({ss.discardSizes[i]})</span>
                  </div>
                ))}
              </div>
              <div className="flex flex-col items-center gap-1">
                <div className="w-12 h-[68px] sm:w-14 sm:h-20 lg:w-[72px] lg:h-[104px] rounded-md border-2 border-slate-400 bg-slate-500 flex items-center justify-center text-white font-bold text-sm">뽑기</div>
                <span className="text-[10px] text-slate-400">{ss.drawCount}장</span>
              </div>
            </div>
          )}
          {!ended && ss.doublePile && ss.myTurn && !ss.myInMojo && <p className="text-center text-[11px] text-slate-400">낼 버림더미를 먼저 고르세요 (현재 더미{selPile + 1})</p>}

          {/* 점수판 */}
          <div className="rounded-xl border border-slate-200 dark:border-slate-700 divide-y divide-slate-100 dark:divide-slate-800">
            {players.filter((p) => !p.left).map((p) => (
              <div key={p.seat} className={`p-2 ${p.seat === ss.turnSeat && !ended ? 'bg-indigo-50 dark:bg-indigo-500/10' : ''}`}>
                <div className="flex items-center justify-between">
                  <span className="text-sm font-bold">
                    {p.bot ? '🤖 ' : ''}{p.name}{p.me ? ' (나)' : ''}{p.hasMojo && ' 🟣'}
                    {p.inMojo && <span className="ml-1 text-[10px] text-fuchsia-500">모죠타임</span>}
                  </span>
                  <span className="text-sm"><b>{p.total}</b>점 {!ended && p.roundScore > 0 && <span className="text-[10px] text-slate-400">(+{p.roundScore})</span>} · 손패 {p.handCount}</span>
                </div>
                {p.front.length > 0 && (
                  <div className="flex gap-1 mt-1 flex-wrap">
                    {p.front.map((f, i) => <NumCard key={i} n={f.value} sm dim={!f.revealed} />)}
                  </div>
                )}
              </div>
            ))}
          </div>

          {/* 내 손패 / 액션 */}
          {!ended && ss.mySeat >= 0 && (
            <div className="w-full">
              {ss.myInMojo ? (
                ss.myTurn && <div className="flex justify-center"><button onClick={reveal} className="px-6 py-2.5 rounded-lg bg-fuchsia-600 text-white font-bold">🃏 카드 공개</button></div>
              ) : (
                <>
                  <p className="text-xs text-slate-400 mb-1">내 손패 {ss.myTurn && !ss.myInMojo ? '(누르면 냄)' : ''}</p>
                  <div className="flex gap-1.5 flex-wrap justify-center">
                    {ss.myHand.map((v, i) => (
                      <NumCard key={i} n={v} onClick={ss.myTurn ? () => play(v) : undefined} />
                    ))}
                    {ss.myHand.length === 0 && <span className="text-slate-400 text-sm py-4">손패 없음</span>}
                  </div>
                </>
              )}
            </div>
          )}
        </div>
      )}

      {roomCode && <RoomChat game="mojo" roomCode={roomCode} clientId={id.current} nick={nick} />}
    </main>
  );
}
