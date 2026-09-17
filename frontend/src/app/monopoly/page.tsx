'use client';

import Link from 'next/link';
import { useCallback, useEffect, useRef, useState } from 'react';
import { api } from '@/lib/api';
import RoomChat from '@/components/RoomChat';

// ── 상수(백엔드 보드와 동일 순서) ──
const N = 32;
const CORNERS = new Set([0, 8, 16, 24]);
const GROUP: Record<string, string> = {
  A: '#38bdf8', B: '#a78bfa', C: '#fb7185', D: '#34d399', E: '#fbbf24', F: '#f472b6', G: '#f97316',
};
const TIER = ['', '🏠', '🏢', '🏨', '🏛️'];
const TIER_NAME = ['땅', '별장', '빌딩', '호텔', '랜드마크'];
const PCOL = ['#3b82f6', '#ef4444', '#22c55e', '#eab308'];
const TEAMCOL = ['#3b82f6', '#ef4444'];
const SPECIAL: Record<string, { emoji: string }> = {
  START: { emoji: '🏁' }, ISLAND: { emoji: '🏝️' }, TRAVEL: { emoji: '✈️' },
  FESTIVAL: { emoji: '🏅' }, FUND: { emoji: '🎁' }, TAX: { emoji: '💸' }, GOLDKEY: { emoji: '🔑' },
};

type TileView = { index: number; name: string; type: string; group: string | null; price: number; ownerSeat: number; tier: number; festival: boolean };
type PlayerView = { seat: number; name: string; bot: boolean; host: boolean; me: boolean; color: number; team: number; cash: number; pos: number; alive: boolean; inIsland: boolean; left: boolean; props: number };
type Card = { icon: string; title: string; desc: string };
type Pending = { type: string; tile: number; toll: number; buyPrice: number; upgradeCost: number; takeoverCost: number; canBuild: boolean; travelOptions: number[] | null; card: Card | null };
type State = {
  phase: string; teamMode: boolean; isHost: boolean; joined: boolean;
  players: PlayerView[]; board: TileView[]; turnSeat: number; turnName: string | null;
  myTurn: boolean; mySeat: number; dice: [number, number]; lastDouble: boolean; step: string;
  pending: Pending | null; pot: number; lastAction: string | null; log: string[];
  winnerSeat: number; winnerLabel: string | null; deadline: number; serverNow: number;
};
type Room = { code: string; status: string; playerCount: number; host: string };

function cid(): string {
  if (typeof window === 'undefined') return '';
  let id = localStorage.getItem('monopoly_client_id');
  if (!id) { id = Math.random().toString(36).slice(2) + Date.now().toString(36); localStorage.setItem('monopoly_client_id', id); }
  return id;
}
function gridPos(i: number): { r: number; c: number } {
  if (i <= 8) return { r: 9, c: 9 - i };
  if (i <= 16) return { r: 9 - (i - 8), c: 1 };
  if (i <= 24) return { r: 1, c: 1 + (i - 16) };
  return { r: 1 + (i - 24), c: 9 };
}
function bandSide(i: number): 'top' | 'right' | 'bottom' | 'left' {
  if (i <= 8) return 'top';
  if (i <= 16) return 'right';
  if (i <= 24) return 'bottom';
  return 'left';
}
const won = (n: number) => `${n.toLocaleString()}만`;

function DiceFace({ n, rolling }: { n: number; rolling?: boolean }) {
  const P = [[], [4], [0, 8], [0, 4, 8], [0, 2, 6, 8], [0, 2, 4, 6, 8], [0, 2, 3, 5, 6, 8]][Math.max(0, Math.min(6, n))];
  return (
    <div className={`grid grid-cols-3 grid-rows-3 w-9 h-9 sm:w-11 sm:h-11 bg-white rounded-lg shadow-inner border border-slate-300 p-1 gap-0.5 ${rolling ? 'animate-spin' : ''}`}>
      {Array.from({ length: 9 }).map((_, k) => (
        <span key={k} className="flex items-center justify-center">
          {P.includes(k) && <span className="w-1.5 h-1.5 sm:w-2 sm:h-2 rounded-full bg-red-600" />}
        </span>
      ))}
    </div>
  );
}

export default function MonopolyPage() {
  const id = useRef('');
  const [nick, setNick] = useState('');
  const [screen, setScreen] = useState<'entry' | 'lobby' | 'game'>('entry');
  const [roomCode, setRoomCode] = useState<string | null>(null);
  const [teamMode, setTeamMode] = useState(false);
  const [joinCode, setJoinCode] = useState('');
  const [rooms, setRooms] = useState<Room[]>([]);
  const [ss, setSs] = useState<State | null>(null);
  const [botLevel, setBotLevel] = useState('NORMAL');
  const [remaining, setRemaining] = useState(0);
  const [disp, setDisp] = useState<number[]>([]);
  const [rolling, setRolling] = useState(false);
  const [tumble, setTumble] = useState<[number, number]>([1, 1]);
  const [charging, setCharging] = useState(false);
  const [gauge, setGauge] = useState(0);
  const [cardSeen, setCardSeen] = useState(false);

  const roomRef = useRef<string | null>(null); roomRef.current = roomCode;
  const offsetRef = useRef(0);
  const ssRef = useRef<State | null>(null); ssRef.current = ss;
  const dispRef = useRef<number[]>([]); dispRef.current = disp;
  const rollingRef = useRef(false); rollingRef.current = rolling;
  const lastPosRef = useRef<Record<number, number>>({});
  const gaugeRef = useRef(0); const dirRef = useRef(1); const chargeIdRef = useRef<number | null>(null);

  useEffect(() => { id.current = cid(); try { setNick(localStorage.getItem('arcade_nick') || ''); } catch {} }, []);

  const loadRooms = useCallback(async () => { try { setRooms(await api(`/api/v1/monopoly/rooms`)); } catch {} }, []);
  useEffect(() => { if (screen === 'entry') { loadRooms(); const t = setInterval(loadRooms, 3000); return () => clearInterval(t); } }, [screen, loadRooms]);

  // 폴링
  useEffect(() => {
    if (screen === 'entry' || !roomCode) return;
    let alive = true;
    const poll = async () => {
      try {
        const s = await api<State>(`/api/v1/monopoly/me?roomCode=${roomCode}&clientId=${id.current}`);
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

  // 말 한 칸씩 이동 애니메이션
  useEffect(() => {
    const t = setInterval(() => {
      const s = ssRef.current; if (!s) return;
      if (rollingRef.current) return; // 주사위 굴러가는 동안엔 말 이동 대기
      setDisp((cur) => {
        if (cur.length !== s.players.length) return s.players.map((p) => p.pos);
        let changed = false;
        const nx = cur.map((c, seat) => {
          const target = s.players[seat]?.pos ?? c;
          if (c === target) return c;
          changed = true;
          const fwd = (target - c + N) % N;
          return fwd > 0 && fwd <= 12 ? (c + 1) % N : target;
        });
        return changed ? nx : cur;
      });
    }, 260);
    return () => clearInterval(t);
  }, []);
  useEffect(() => { if (ss && disp.length !== ss.players.length) setDisp(ss.players.map((p) => p.pos)); }, [ss?.players.length]);

  // 카드 모달 노출 여부(내 카드가 새로 뜨면 리셋)
  const myCardKey = ss?.pending?.type === 'CARD' && ss.pending.card ? ss.pending.card.title : '';
  useEffect(() => { setCardSeen(false); }, [myCardKey]);

  const saveNick = (n: string) => { try { localStorage.setItem('arcade_nick', n); } catch {} };
  const create = async () => {
    const n = nick.trim(); if (!n) return; saveNick(n);
    try {
      const res = await api<{ roomCode: string; state: State }>(`/api/v1/monopoly/new?clientId=${id.current}`, { method: 'POST', body: JSON.stringify({ nick: n, teamMode }) });
      setRoomCode(res.roomCode); setSs(res.state); setScreen('lobby');
    } catch (e: any) { alert(e?.message || '방 생성 실패'); }
  };
  const join = async (code: string, nickOverride?: string) => {
    const n = (nickOverride ?? nick).trim(); if (!n || !code) return; setNick(n); saveNick(n);
    try {
      const s = await api<State>(`/api/v1/monopoly/join?roomCode=${code}&clientId=${id.current}`, { method: 'POST', body: JSON.stringify({ nick: n }) });
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
    try { window.history.replaceState({}, '', '/monopoly'); } catch {}
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, []);

  const addBot = async () => { try { setSs(await api(`/api/v1/monopoly/add-bot?roomCode=${roomCode}&clientId=${id.current}&level=${botLevel}`, { method: 'POST', body: '{}' })); } catch (e: any) { alert(e?.message); } };
  const startMatch = async () => { try { setSs(await api(`/api/v1/monopoly/start?roomCode=${roomCode}&clientId=${id.current}`, { method: 'POST', body: '{}' })); setScreen('game'); } catch (e: any) { alert(e?.message); } };
  const leave = async () => { const rc = roomRef.current; if (rc) { try { await api(`/api/v1/monopoly/leave?roomCode=${rc}&clientId=${id.current}`, { method: 'POST', body: '{}' }); } catch {} } setScreen('entry'); setRoomCode(null); setSs(null); };

  const rollApi = async (power: number) => { try { setSs(await api(`/api/v1/monopoly/roll?roomCode=${roomCode}&clientId=${id.current}&power=${Math.round(power)}`, { method: 'POST', body: '{}' })); } catch (e: any) { alert(e?.message); } };
  const decide = async (action: string, arg = -1) => { try { setSs(await api(`/api/v1/monopoly/decide?roomCode=${roomCode}&clientId=${id.current}&action=${action}&arg=${arg}`, { method: 'POST', body: '{}' })); } catch (e: any) { alert(e?.message); } };

  // 새 주사위 이동 감지 → 주사위 텀블(560ms) 먼저 재생하고, 그동안 말 이동은 대기시킴
  useEffect(() => {
    if (!ss || ss.turnSeat < 0) return;
    const seat = ss.turnSeat;
    const pos = ss.players[seat]?.pos ?? 0;
    const prev = lastPosRef.current[seat];
    lastPosRef.current[seat] = pos;
    if (prev === undefined || prev === pos) return;
    const fwd = (pos - prev + N) % N;
    if (fwd === ss.dice[0] + ss.dice[1]) { // 주사위 눈금 합과 일치 = 굴려서 이동(순간이동/카드 아님)
      setRolling(true);
      window.setTimeout(() => setRolling(false), 560);
    }
  }, [ss]);
  // 굴러가는 동안 주사위 눈 빠르게 바뀌는 텀블 효과
  useEffect(() => {
    if (!rolling) return;
    const t = setInterval(() => setTumble([1 + Math.floor(Math.random() * 6), 1 + Math.floor(Math.random() * 6)]), 70);
    return () => clearInterval(t);
  }, [rolling]);
  // 차징 게이지
  const startCharge = () => {
    if (rolling || charging || !ss?.myTurn || ss.step !== 'ROLL') return;
    setCharging(true); gaugeRef.current = 0; dirRef.current = 1; setGauge(0);
    chargeIdRef.current = window.setInterval(() => {
      let ng = gaugeRef.current + dirRef.current * 3.4;
      if (ng >= 100) { ng = 100; dirRef.current = -1; } else if (ng <= 0) { ng = 0; dirRef.current = 1; }
      gaugeRef.current = ng; setGauge(ng);
    }, 16);
  };
  const releaseCharge = () => {
    if (!charging) return;
    setCharging(false);
    if (chargeIdRef.current) { clearInterval(chargeIdRef.current); chargeIdRef.current = null; }
    rollApi(gaugeRef.current * 1.2);
  };

  const me = ss?.players.find((p) => p.me);
  const p = ss?.pending;
  const displayDice: [number, number] = rolling ? tumble : (ss?.dice ?? [1, 1]);
  // 현재 차례 말이 애니메이션상 목적지에 도착했는지(도착 전엔 액션/카드 숨김)
  const turnArrived = !!ss && ss.turnSeat >= 0 && disp.length === ss.players.length
    ? disp[ss.turnSeat] === ss.players[ss.turnSeat]?.pos : true;

  // ── 렌더 ──
  return (
    <main className="min-h-screen flex flex-col items-center p-3 sm:p-5 max-w-6xl mx-auto w-full text-slate-800 dark:text-slate-100">
      <div className="w-full flex items-center gap-2 mb-3">
        <button onClick={leave} aria-label="나가기" className="text-lg leading-none text-slate-500 hover:text-slate-800 dark:hover:text-slate-100">🏠</button>
        <h1 className="text-xl sm:text-2xl font-extrabold">🏙️ 부루마블</h1>
        {ss && screen !== 'entry' && <span className="text-xs text-slate-400">방 {roomCode} · {ss.teamMode ? '팀전(2:2)' : '개인전'}</span>}
      </div>

      {/* ── 입장 ── */}
      {screen === 'entry' && (
        <div className="w-full max-w-md space-y-4">
          <div className="rounded-2xl border border-slate-200 dark:border-slate-700 p-4 space-y-3">
            <input value={nick} onChange={(e) => setNick(e.target.value)} maxLength={16} placeholder="닉네임"
              className="w-full border border-slate-300 dark:border-slate-600 bg-transparent rounded-lg px-3 py-2" />
            <div className="grid grid-cols-2 gap-2">
              <button onClick={() => setTeamMode(false)} className={`rounded-xl border-2 p-3 text-sm font-bold ${!teamMode ? 'border-indigo-500 bg-indigo-500/10' : 'border-slate-300 dark:border-slate-600'}`}>👤 개인전<br /><span className="text-[11px] font-normal text-slate-400">2~4인, 마지막 생존</span></button>
              <button onClick={() => setTeamMode(true)} className={`rounded-xl border-2 p-3 text-sm font-bold ${teamMode ? 'border-indigo-500 bg-indigo-500/10' : 'border-slate-300 dark:border-slate-600'}`}>👥 팀전 2:2<br /><span className="text-[11px] font-normal text-slate-400">4인, 팀원 통행료 면제</span></button>
            </div>
            <button onClick={create} disabled={!nick.trim()} className="w-full bg-indigo-600 text-white font-bold py-3 rounded-lg disabled:opacity-50">방 만들기</button>
          </div>
          <div className="rounded-2xl border border-slate-200 dark:border-slate-700 p-4 space-y-2">
            <p className="text-sm font-bold text-slate-600 dark:text-slate-300">코드로 참가</p>
            <div className="flex gap-2">
              <input value={joinCode} onChange={(e) => setJoinCode(e.target.value.toUpperCase())} maxLength={4} placeholder="코드"
                className="flex-1 border border-slate-300 dark:border-slate-600 bg-transparent rounded-lg px-3 py-2 uppercase tracking-widest font-bold" />
              <button onClick={() => join(joinCode)} disabled={!nick.trim() || joinCode.length < 4} className="px-5 bg-slate-700 text-white font-bold rounded-lg disabled:opacity-40">참가</button>
            </div>
            {rooms.length > 0 && (
              <div className="space-y-1 pt-1">
                {rooms.map((r) => (
                  <button key={r.code} onClick={() => join(r.code)} disabled={!nick.trim()}
                    className="w-full flex items-center justify-between text-sm px-3 py-2 rounded-lg border border-slate-200 dark:border-slate-700 hover:border-indigo-400 disabled:opacity-40">
                    <span className="font-bold">{r.code}</span>
                    <span className="text-slate-400">{r.host} · {r.playerCount}명 · {r.status === 'WAITING' ? '모집중' : r.status === 'PLAYING' ? '진행중' : '종료'}</span>
                  </button>
                ))}
              </div>
            )}
          </div>
        </div>
      )}

      {/* ── 로비 ── */}
      {screen === 'lobby' && ss && (
        <div className="w-full max-w-md space-y-3">
          <div className="rounded-2xl border border-slate-200 dark:border-slate-700 p-4">
            <p className="text-sm font-bold text-slate-600 dark:text-slate-300 mb-2">참가자 ({ss.players.length}/4) · {ss.teamMode ? '팀전 2:2' : '개인전'}</p>
            <div className="space-y-1.5">
              {ss.players.map((pl, i) => (
                <div key={pl.seat} className="flex items-center gap-2 text-sm px-2 py-1.5 rounded-lg bg-slate-50 dark:bg-slate-800">
                  <span className="w-3.5 h-3.5 rounded-full" style={{ background: PCOL[pl.seat % 4] }} />
                  <span className="font-bold">{pl.name}</span>
                  {pl.bot && <span className="text-[10px] px-1.5 rounded bg-slate-200 dark:bg-slate-700">봇</span>}
                  {pl.host && <span className="text-[10px] text-indigo-500">방장</span>}
                  {ss.teamMode && <span className="ml-auto text-[11px] font-bold" style={{ color: TEAMCOL[i % 2] }}>{i % 2 === 0 ? 'A팀' : 'B팀'}</span>}
                </div>
              ))}
            </div>
            {ss.teamMode && <p className="text-[11px] text-slate-400 mt-2">팀전은 4명 필요 · 자리 순서대로 A/B팀으로 나뉘어요</p>}
          </div>
          {ss.isHost && (
            <div className="rounded-2xl border border-slate-200 dark:border-slate-700 p-4 space-y-2">
              <div className="flex gap-2">
                <select value={botLevel} onChange={(e) => setBotLevel(e.target.value)} className="flex-1 border border-slate-300 dark:border-slate-600 bg-transparent rounded-lg px-3 py-2">
                  <option value="EASY">봇 쉬움</option><option value="NORMAL">봇 보통</option><option value="HARD">봇 어려움</option>
                </select>
                <button onClick={addBot} disabled={ss.players.length >= 4} className="px-4 rounded-lg border border-slate-300 dark:border-slate-600 font-bold disabled:opacity-40">봇 추가</button>
              </div>
              <button onClick={startMatch} className="w-full bg-indigo-600 text-white font-extrabold py-3 rounded-lg">게임 시작</button>
            </div>
          )}
          {!ss.isHost && <p className="text-center text-sm text-slate-400">방장이 시작하기를 기다리는 중…</p>}
          <button onClick={leave} className="w-full text-sm text-slate-400 py-2">나가기</button>
        </div>
      )}

      {/* ── 게임 ── */}
      {screen === 'game' && ss && (
        <div className="w-full flex flex-col lg:flex-row gap-4 justify-center items-start">
          {/* 보드 */}
          <div className="w-full max-w-[720px] mx-auto">
            <div className="relative grid grid-cols-9 grid-rows-9 gap-1 aspect-square bg-emerald-50 dark:bg-slate-800/60 rounded-2xl border border-slate-200 dark:border-slate-700 p-1 shadow">
              {ss.board.map((t) => {
                const i = t.index;
                const { r, c } = gridPos(i);
                const isCorner = CORNERS.has(i);
                const band = t.group ? GROUP[t.group] : undefined;
                const side = bandSide(i);
                const bStyle: React.CSSProperties = band
                  ? side === 'top' ? { borderTop: `5px solid ${band}` }
                    : side === 'bottom' ? { borderBottom: `5px solid ${band}` }
                      : side === 'left' ? { borderLeft: `5px solid ${band}` } : { borderRight: `5px solid ${band}` }
                  : {};
                const here = disp.map((d, seat) => (d === i ? seat : -1)).filter((x) => x >= 0);
                const travelPick = ss.myTurn && p?.type === 'TRAVEL' && turnArrived;
                const olympicPick = ss.myTurn && p?.type === 'OLYMPIC' && turnArrived && !!p?.travelOptions?.includes(i);
                const sellPick = ss.myTurn && p?.type === 'SETTLE' && turnArrived && !!p?.travelOptions?.includes(i);
                const isTurnTile = ss.turnSeat >= 0 && ss.players[ss.turnSeat] && disp[ss.turnSeat] === i;
                const ownCol = t.ownerSeat >= 0 ? PCOL[t.ownerSeat % 4] : null;
                return (
                  <div key={i} onClick={() => { if (travelPick) decide('travel', i); else if (olympicPick) decide('olympic', i); else if (sellPick) decide('sell', i); }}
                    style={{ gridRow: r, gridColumn: c, ...bStyle, ...(ownCol ? { backgroundColor: ownCol } : {}), ...(isTurnTile ? { boxShadow: `0 0 0 3px ${PCOL[ss.turnSeat % 4]}` } : {}) }}
                    className={`relative rounded-md flex flex-col items-center justify-center text-center overflow-hidden px-0.5 py-0.5 transition-all
                      ${isCorner ? 'bg-indigo-50 dark:bg-slate-700/70' : ownCol ? '' : 'bg-white dark:bg-slate-800'} border border-slate-200 dark:border-slate-700
                      ${travelPick ? 'cursor-pointer ring-1 ring-sky-400 hover:ring-2' : ''} ${olympicPick ? 'cursor-pointer ring-2 ring-amber-400 hover:ring-4' : ''} ${sellPick ? 'cursor-pointer ring-2 ring-rose-500 hover:ring-4' : ''} ${t.festival ? 'ring-2 ring-amber-400' : ''}`}>
                    {ownCol && (
                      <span className="absolute top-0.5 right-0.5 w-3.5 h-3.5 rounded-full bg-white/90 ring-1 ring-black/10 flex items-center justify-center text-[7px] font-black leading-none" style={{ color: ownCol }}>{ss.players[t.ownerSeat]?.name?.[0] ?? ''}</span>
                    )}
                    {isCorner ? (
                      <><span className="text-base sm:text-xl leading-none">{SPECIAL[t.type]?.emoji}</span><span className="text-[8px] sm:text-[10px] font-bold leading-tight mt-0.5">{t.name}</span></>
                    ) : t.type === 'CITY' ? (
                      <><span className={`text-[8px] sm:text-[11px] font-bold leading-tight ${ownCol ? 'text-white' : ''}`} style={ownCol ? { textShadow: '0 1px 2px rgba(0,0,0,.55)' } : undefined}>{t.name === '파주' ? '👑파주' : t.name}</span>
                        {t.tier ? <span className="text-[10px] sm:text-sm leading-none drop-shadow">{TIER[t.tier]}</span> : ownCol ? null : <span className="text-[7px] sm:text-[9px] text-slate-400 leading-none">{t.price}만</span>}</>
                    ) : (
                      <><span className="text-sm sm:text-lg leading-none">{SPECIAL[t.type]?.emoji}</span><span className="text-[7px] sm:text-[9px] font-semibold text-slate-500 leading-tight">{t.name}</span></>
                    )}
                    {here.length > 0 && (
                      <div className="absolute inset-x-0 bottom-0 flex flex-wrap justify-center gap-0.5 pb-0.5">
                        {here.map((seat) => (
                          <span key={seat} className={`w-4 h-4 sm:w-5 sm:h-5 rounded-full ring-2 ring-white dark:ring-slate-900 shadow-md flex items-center justify-center text-[8px] sm:text-[10px] font-black text-white leading-none ${ss.turnSeat === seat && ss.step === 'ROLL' ? 'animate-bounce' : ''}`}
                            style={{ background: PCOL[seat % 4] }}>{ss.players[seat]?.name?.[0] ?? '?'}</span>
                        ))}
                      </div>
                    )}
                  </div>
                );
              })}
              {/* 중앙 패널 */}
              <div style={{ gridRow: '2 / 9', gridColumn: '2 / 9' }} className="flex flex-col items-center justify-center gap-2 rounded-xl bg-white/70 dark:bg-slate-900/50 border border-slate-200 dark:border-slate-700 m-1 p-2">
                <div className="text-xl sm:text-3xl font-black tracking-tight text-indigo-600 dark:text-indigo-300 -rotate-6">부루마블</div>
                <div className="flex items-center gap-2">
                  <DiceFace n={displayDice[0]} rolling={rolling} /><DiceFace n={displayDice[1]} rolling={rolling} />
                  {!rolling && <span className="text-lg font-extrabold">= {displayDice[0] + displayDice[1]}</span>}
                </div>
                {ss.lastDouble && !rolling && <div className="text-sm font-black text-pink-500 animate-pulse">✨ 더블!</div>}
                <div className="text-xs sm:text-sm font-bold flex items-center gap-1.5">
                  <span className="w-3 h-3 rounded-full" style={{ background: PCOL[ss.turnSeat % 4] }} />
                  {ss.phase === 'ENDED' ? (ss.winnerLabel ?? '종료') : `${ss.turnName ?? ''} 차례${remaining > 0 ? ` · ${remaining}s` : ''}`}
                </div>
                <div className="text-[11px] text-slate-500 dark:text-slate-400">기금 {won(ss.pot)}</div>
                {ss.lastAction && turnArrived && <div className="text-[10px] text-slate-400 text-center max-w-[90%] truncate">{ss.lastAction}</div>}
              </div>
            </div>
          </div>

          {/* 사이드 */}
          <div className="w-full lg:w-80 shrink-0 space-y-3">
            {/* 컨트롤 */}
            <div className="rounded-xl border border-slate-200 dark:border-slate-700 p-3 space-y-2">
              {ss.phase === 'ENDED' ? (
                <div className="text-center">
                  <p className="text-lg font-extrabold">🏆 {ss.winnerLabel}</p>
                  <button onClick={leave} className="mt-2 w-full bg-indigo-600 text-white font-bold py-2 rounded-lg">나가기</button>
                </div>
              ) : !ss.myTurn ? (
                <p className="text-center text-sm text-slate-500 py-2">
                  <span className="font-bold" style={{ color: PCOL[ss.turnSeat % 4] }}>{ss.turnName}</span> 님의 차례…
                </p>
              ) : ss.step === 'ROLL' ? (
                <>
                  <div className="h-4 w-full rounded-full bg-slate-200 dark:bg-slate-700 overflow-hidden relative">
                    <div className="h-full rounded-full transition-[width] duration-75" style={{ width: `${gauge}%`, background: gauge > 82 ? '#ef4444' : gauge > 45 ? '#22c55e' : '#f59e0b' }} />
                    <span className="absolute inset-0 flex items-center justify-center text-[10px] font-bold text-slate-600 dark:text-slate-200">{charging ? '놓으면 발사!' : rolling ? '' : '누르고 있어 파워 조절'}</span>
                  </div>
                  <button onPointerDown={startCharge} onPointerUp={releaseCharge} onPointerLeave={releaseCharge} disabled={rolling}
                    className="w-full bg-indigo-600 text-white font-extrabold py-3 rounded-lg disabled:opacity-50 text-lg select-none touch-none active:scale-[0.98]">
                    {rolling ? '🎲 굴리는 중…' : charging ? '⚡ 차징 중…' : me?.inIsland ? '🏝️ 무인도 탈출 시도 (누르기)' : '🎲 주사위 굴리기 (누르기)'}
                  </button>
                  {me?.inIsland && <p className="text-[11px] text-slate-400 text-center">더블이 나오면 탈출해요</p>}
                </>
              ) : p && !turnArrived ? (
                <p className="text-center text-sm text-slate-500 py-3">🚶 말 이동 중…</p>
              ) : p ? (
                <div className="space-y-2">
                  {p.type === 'BUY' && (<>
                    <p className="text-sm font-bold text-center">{ss.board[p.tile].name} 구매? ({won(p.buyPrice)})</p>
                    <div className="grid grid-cols-2 gap-2">
                      <button onClick={() => decide('buy')} className="bg-emerald-600 text-white font-bold py-2.5 rounded-lg">💰 구매</button>
                      <button onClick={() => decide('pass')} className="border border-slate-300 dark:border-slate-600 font-bold py-2.5 rounded-lg">패스</button>
                    </div></>)}
                  {p.type === 'UPGRADE' && (<>
                    <p className="text-sm font-bold text-center">{ss.board[p.tile].name} 건설? ({won(p.upgradeCost)})</p>
                    <div className="grid grid-cols-2 gap-2">
                      <button onClick={() => decide('build')} className="bg-indigo-600 text-white font-bold py-2.5 rounded-lg">🏗️ {TIER_NAME[Math.min(4, ss.board[p.tile].tier + 1)]} 건설</button>
                      <button onClick={() => decide('skip')} className="border border-slate-300 dark:border-slate-600 font-bold py-2.5 rounded-lg">건너뛰기</button>
                    </div></>)}
                  {p.type === 'TOLL' && (<>
                    <p className="text-sm font-bold text-center">{ss.board[p.tile].name} · 통행료 {won(p.toll)}</p>
                    <button onClick={() => decide('pay')} className="w-full bg-rose-600 text-white font-bold py-2.5 rounded-lg">💸 통행료 내기</button>
                    {(me?.cash ?? 0) >= p.takeoverCost && (
                      <button onClick={() => decide('takeover')} className="w-full border-2 border-amber-400 text-amber-600 dark:text-amber-300 font-bold py-2.5 rounded-lg">🤝 인수하기 ({won(p.takeoverCost)})</button>
                    )}
                  </>)}
                  {p.type === 'TRAVEL' && <p className="text-sm font-bold text-center text-sky-600 dark:text-sky-300 py-2">✈️ 이동할 칸을 보드에서 클릭!</p>}
                  {p.type === 'OLYMPIC' && <p className="text-sm font-bold text-center text-amber-600 dark:text-amber-300 py-2">🏅 축제(통행료 2배) 개최할 <b>내 도시</b>를 클릭!</p>}
                  {p.type === 'SETTLE' && (<>
                    <p className="text-sm font-bold text-center text-rose-600 dark:text-rose-300">💸 통행료 {won(p.toll)} 부족!<br /><span className="text-[11px] font-normal text-slate-500">보유 현금 {won(me?.cash ?? 0)} · 팔 땅(빨강 테두리)을 클릭해 마련</span></p>
                    <button onClick={() => decide('bankrupt')} className="w-full border-2 border-rose-500 text-rose-600 dark:text-rose-300 font-bold py-2.5 rounded-lg">🏳️ 파산 선언 (탈락)</button>
                  </>)}
                  {p.type === 'CARD' && !p.card && <p className="text-sm text-center text-slate-400 py-2">카드 확인 중…</p>}
                </div>
              ) : null}
            </div>

            {/* 플레이어 */}
            <div className="rounded-xl border border-slate-200 dark:border-slate-700 p-3">
              <p className="text-sm font-bold text-slate-600 dark:text-slate-300 mb-2">플레이어</p>
              <div className="space-y-1.5">
                {ss.players.map((pl) => (
                  <div key={pl.seat} className={`flex items-center gap-2 text-sm px-2 py-1.5 rounded-lg ${ss.turnSeat === pl.seat ? 'bg-indigo-50 dark:bg-indigo-500/10 ring-1 ring-indigo-300' : ''} ${!pl.alive ? 'opacity-40 line-through' : ''}`}>
                    <span className="w-3.5 h-3.5 rounded-full shrink-0" style={{ background: PCOL[pl.seat % 4] }} />
                    <span className="font-bold">{pl.name}</span>
                    {ss.teamMode && <span className="text-[10px] font-bold" style={{ color: TEAMCOL[pl.team] }}>{pl.team === 0 ? 'A' : 'B'}</span>}
                    {pl.inIsland && <span className="text-[10px]">🏝️</span>}
                    <span className="text-[11px] text-slate-400">🏠{pl.props}</span>
                    <span className="ml-auto font-mono font-bold text-emerald-600 dark:text-emerald-400">{won(pl.cash)}</span>
                  </div>
                ))}
              </div>
            </div>

            {/* 로그 */}
            <div className="rounded-xl border border-slate-200 dark:border-slate-700 p-3">
              <p className="text-sm font-bold text-slate-600 dark:text-slate-300 mb-2">진행 로그</p>
              <div className="space-y-0.5 text-xs text-slate-500 dark:text-slate-400 max-h-40 overflow-y-auto">
                {[...(ss.log ?? [])].reverse().map((l, i) => <div key={i} className={i === 0 ? 'font-bold text-slate-700 dark:text-slate-200' : ''}>{l}</div>)}
              </div>
            </div>
          </div>
        </div>
      )}

      {/* 황금열쇠 카드 모달(뽑은 본인만) */}
      {ss?.pending?.type === 'CARD' && ss.pending.card && !cardSeen && turnArrived && (
        <div className="fixed inset-0 z-50 flex items-center justify-center bg-black/55 p-4">
          <div className="w-64 rounded-2xl shadow-2xl overflow-hidden" style={{ animation: 'monoFlip .5s cubic-bezier(.2,.8,.2,1)' }}>
            <div className="p-5 text-center text-white bg-amber-500">
              <div className="text-[11px] font-bold opacity-90 tracking-[0.25em]">🔑 황금열쇠</div>
              <div className="text-[11px] font-bold mt-1 inline-flex items-center gap-1 bg-black/25 rounded-full px-2 py-0.5">나만 보임</div>
              <div className="text-6xl my-3 drop-shadow">{ss.pending.card.icon}</div>
              <div className="text-2xl font-black">{ss.pending.card.title}</div>
            </div>
            <div className="p-4 bg-white dark:bg-slate-800 text-center">
              <p className="text-sm text-slate-600 dark:text-slate-300">{ss.pending.card.desc}</p>
              <p className="text-[11px] text-slate-400 mt-1">다른 참가자에겐 내용이 보이지 않아요</p>
              <button onClick={() => { setCardSeen(true); decide('ack'); }} className="mt-3 w-full bg-indigo-600 text-white font-bold py-2 rounded-lg">확인</button>
            </div>
          </div>
          <style jsx>{`@keyframes monoFlip{0%{transform:perspective(700px) rotateY(90deg) scale(.85);opacity:0}60%{transform:perspective(700px) rotateY(-12deg) scale(1.02);opacity:1}100%{transform:perspective(700px) rotateY(0) scale(1);opacity:1}}`}</style>
        </div>
      )}

      {roomCode && screen !== 'entry' && <RoomChat game="monopoly" roomCode={roomCode} clientId={id.current} nick={nick} />}
    </main>
  );
}
