'use client';

import Link from 'next/link';
import { useCallback, useEffect, useRef, useState } from 'react';
import { api } from '@/lib/api';
import RoomChat from '@/components/RoomChat';

type CardView = { id: number; spice: number; number: number };
type PlayerView = { seat: number; name: string; bot: boolean; host: boolean; me: boolean; left: boolean; handCount: number; won: number; trophies: number; score: number };
type Reveal = { challengerSeat: number; accusedSeat: number; kind: string; declaredSpice: number; declaredNumber: number; actualSpice: number; actualNumber: number; success: boolean; pileTaken: number };
type State = {
  phase: string; turnPhase: string | null; challengeSec: number; handPenalty: boolean;
  deckSize: number; trophyTotal: number; isHost: boolean; joined: boolean;
  players: PlayerView[]; myHand: CardView[];
  turnSeat: number; turnName: string | null; myTurn: boolean; mySeat: number;
  pileSize: number; declaredSpice: number; declaredNumber: number; lastPlayerSeat: number;
  playableNumbers: number[]; playableSpices: number[];
  canChallenge: boolean; lastReveal: Reveal | null; drawLeft: number; trophyLeft: number;
  lastAction: string | null; log: string[]; winnerSeat: number; winnerLabel: string | null;
  deadline: number; serverNow: number;
};
type Room = { code: string; status: string; playerCount: number; host: string };

const ALL = -1, NONE = -2;

// 스파이스별 테마 — 고추(빨강) · 와사비(초록) · 후추(파랑)
const SPICE = [
  { name: '고추', emoji: '🌶️', ink: '#c0392b', soft: '#fde8e4', edge: '#e8a598' },
  { name: '와사비', emoji: '🥬', ink: '#3d8b40', soft: '#e6f2e0', edge: '#a3c99a' },
  { name: '후추', emoji: '🧂', ink: '#2b6cb0', soft: '#e3edf8', edge: '#9dbde0' },
];
// 실물 카드처럼 크림색 종이 바탕 + 굵은 먹선 테두리
const PAPER = '#f7f2e6', INK = '#1a1a1a';
const SEAT_COLORS = ['#f43f5e', '#3b82f6', '#10b981', '#f59e0b', '#8b5cf6', '#ec4899', '#14b8a6', '#f97316', '#6366f1', '#84cc16'];

function cid(): string {
  if (typeof window === 'undefined') return '';
  let id = localStorage.getItem('spicy_client_id');
  if (!id) { id = Math.random().toString(36).slice(2) + Date.now().toString(36); localStorage.setItem('spicy_client_id', id); }
  return id;
}

/**
 * 스파이시 카드.
 * 실물 카드의 조형을 따랐다 — 크림색 종이 바탕, 굵은 먹선 이중 테두리,
 * 좌상단/우하단(180° 회전)에 원형 뱃지, 가운데 큰 숫자.
 * '모든 숫자' 카드는 흑백 줄무늬에 1-10 뱃지, '모든 스파이스' 카드는 삼색 불꽃 바탕이다.
 */
function SpicyCard({ c, size = 'md', selected, onClick, dim }: {
  c: CardView; size?: 'sm' | 'md' | 'lg'; selected?: boolean; onClick?: () => void; dim?: boolean;
}) {
  const dims = {
    sm: { w: 'w-12 sm:w-14', h: 'h-[68px] sm:h-20', num: 'text-xl sm:text-2xl', emo: 'text-sm sm:text-base', badge: 'w-4 h-4 text-[7px]', label: 'text-[6px] sm:text-[7px]' },
    md: { w: 'w-16 sm:w-20 lg:w-[92px]', h: 'h-24 sm:h-28 lg:h-[132px]', num: 'text-3xl sm:text-4xl lg:text-5xl', emo: 'text-lg sm:text-xl lg:text-2xl', badge: 'w-5 h-5 lg:w-6 lg:h-6 text-[8px] lg:text-[9px]', label: 'text-[8px] lg:text-[9px]' },
    lg: { w: 'w-24 lg:w-28', h: 'h-36 lg:h-[168px]', num: 'text-5xl lg:text-6xl', emo: 'text-2xl lg:text-3xl', badge: 'w-6 h-6 lg:w-7 lg:h-7 text-[9px] lg:text-[10px]', label: 'text-[10px] lg:text-xs' },
  }[size];

  const allSpice = c.spice === ALL;
  const allNumber = c.number === ALL;
  const th = !allSpice && !allNumber && c.spice >= 0 ? SPICE[c.spice] : null;

  // 배경: 일반 카드는 종이결, 와일드는 실물처럼 삼색 불꽃 / 흑백 줄무늬
  const bg = allSpice
    ? `repeating-linear-gradient(115deg, ${SPICE[0].ink} 0 10px, ${SPICE[1].ink} 10px 20px, ${SPICE[2].ink} 20px 30px, #e8c33a 30px 40px)`
    : allNumber
      ? `repeating-linear-gradient(125deg, ${INK} 0 7px, ${PAPER} 7px 15px)`
      : PAPER;

  const badge = (rotated?: boolean) => (
    <span
      className={`absolute ${rotated ? 'bottom-1 right-1 rotate-180' : 'top-1 left-1'} ${dims.badge}
        rounded-full flex items-center justify-center font-black leading-none shadow-sm`}
      style={{ background: PAPER, border: `2px solid ${INK}`, color: th ? th.ink : INK }}
    >
      {allNumber ? '1-10' : allSpice ? '🌶' : c.number}
    </span>
  );

  return (
    <button
      onClick={onClick}
      disabled={!onClick}
      className={`relative shrink-0 rounded-lg overflow-hidden transition ${dims.w} ${dims.h}
        ${selected ? '-translate-y-3 shadow-2xl' : 'shadow-md'} ${dim ? 'opacity-40' : ''}
        ${onClick ? 'cursor-pointer hover:-translate-y-1.5 active:scale-95' : ''}`}
      style={{
        background: bg,
        border: `3px solid ${INK}`,
        ...(selected ? { boxShadow: `0 0 0 4px ${th ? th.ink : '#e8c33a'}` } : {}),
      }}
    >
      {/* 안쪽 먹선 한 겹 더(실물 카드의 이중 프레임) */}
      <span className="absolute inset-[3px] rounded-[5px] pointer-events-none" style={{ border: `1.5px solid ${INK}` }} />

      {/* 가운데 흰 종이 패널 — 와일드도 숫자/글자가 읽히게 */}
      <span className="absolute inset-[7px] rounded-[3px] flex flex-col items-center justify-center gap-0.5"
        style={{ background: allSpice || allNumber ? 'rgba(247,242,230,0.92)' : 'transparent' }}>
        {allSpice ? (
          <>
            <span className={dims.emo}>🌶️🥬🧂</span>
            <span className={`${dims.label} font-black leading-tight text-center`} style={{ color: INK }}>모든<br />스파이스</span>
          </>
        ) : allNumber ? (
          <>
            <span className={`${dims.num} font-black leading-none`} style={{ color: INK }}>1-10</span>
            <span className={`${dims.label} font-black leading-tight`} style={{ color: INK }}>모든 숫자</span>
          </>
        ) : (
          <>
            <span className={dims.emo}>{th?.emoji}</span>
            <span className={`${dims.num} font-black leading-none tracking-tight`} style={{ color: th?.ink }}>{c.number}</span>
            <span className={`${dims.label} font-black`} style={{ color: th?.ink }}>{th?.name}</span>
          </>
        )}
      </span>

      {badge()}
      {badge(true)}
    </button>
  );
}

/** 트로피(+10) 카드 — 실물처럼 '+10'을 크게 박은 카드. */
function TrophyCard({ size = 'xs' }: { size?: 'xs' | 'sm' }) {
  const d = size === 'xs'
    ? { w: 'w-6 h-[34px] rounded', num: 'text-[8px]', pad: 'inset-[2px]' }
    : { w: 'w-12 h-[68px] rounded-lg', num: 'text-base', pad: 'inset-[3px]' };
  return (
    <span className={`relative inline-flex items-center justify-center shadow-sm ${d.w}`}
      style={{ background: PAPER, border: `2px solid ${INK}` }}>
      <span className={`absolute ${d.pad} rounded-sm pointer-events-none`} style={{ border: `1px solid ${INK}` }} />
      <span className={`${d.num} font-black leading-none`} style={{ color: INK }}>+10</span>
    </span>
  );
}

/** 뒷면(더미에 깔린 카드) — 박스 아트처럼 짙은 카키 바탕에 먹선 프레임. */
function CardBack({ size = 'md', style }: { size?: 'sm' | 'md' | 'lg'; style?: React.CSSProperties }) {
  const dims = { sm: 'w-12 sm:w-14 h-[68px] sm:h-20', md: 'w-16 sm:w-20 lg:w-[92px] h-24 sm:h-28 lg:h-[132px]', lg: 'w-24 lg:w-28 h-36 lg:h-[168px]' }[size];
  return (
    <div className={`absolute rounded-lg shadow-md flex items-center justify-center ${dims}`}
      style={{ background: '#6b6134', border: `3px solid ${INK}`, ...style }}>
      <span className="absolute inset-[3px] rounded-[5px] pointer-events-none" style={{ border: `1.5px solid ${PAPER}` }} />
      <span className="text-xl lg:text-3xl">🐯</span>
    </div>
  );
}

export default function SpicyPage() {
  const id = useRef('');
  const [nick, setNick] = useState('');
  const [screen, setScreen] = useState<'entry' | 'lobby' | 'game'>('entry');
  const [roomCode, setRoomCode] = useState<string | null>(null);
  const [challengeSec, setChallengeSec] = useState(8);
  const [handPenalty, setHandPenalty] = useState(true);
  const [joinCode, setJoinCode] = useState('');
  const [rooms, setRooms] = useState<Room[]>([]);
  const [ss, setSs] = useState<State | null>(null);
  const [botLevel, setBotLevel] = useState('NORMAL');
  const [sel, setSel] = useState<number | null>(null);       // 고른 손패 카드 id
  const [decSpice, setDecSpice] = useState<number | null>(null);
  const [decNumber, setDecNumber] = useState<number | null>(null);
  const roomRef = useRef<string | null>(null); roomRef.current = roomCode;
  const syncRef = useRef({ serverNow: 0, at: 0 });
  const [, forceTick] = useState(0);

  useEffect(() => { id.current = cid(); try { setNick(localStorage.getItem('arcade_nick') || ''); } catch {} }, []);

  const loadRooms = useCallback(async () => { try { setRooms(await api(`/api/v1/spicy/rooms`)); } catch {} }, []);
  useEffect(() => { if (screen === 'entry') { loadRooms(); const t = setInterval(loadRooms, 3000); return () => clearInterval(t); } }, [screen, loadRooms]);

  useEffect(() => {
    if (screen === 'entry' || !roomCode) return;
    let alive = true;
    const poll = async () => {
      try {
        const s = await api<State>(`/api/v1/spicy/me?roomCode=${roomCode}&clientId=${id.current}`);
        if (!alive) return;
        syncRef.current = { serverNow: s.serverNow, at: Date.now() };
        setSs(s);
        if (s.phase !== 'LOBBY' && screen === 'lobby') setScreen('game');
        if (s.phase === 'LOBBY' && screen === 'game') setScreen('lobby');
      } catch {}
    };
    const t = setInterval(poll, 1000); poll();
    return () => { alive = false; clearInterval(t); };
  }, [screen, roomCode]);

  useEffect(() => {
    if (screen !== 'game') return;
    const t = setInterval(() => forceTick((x) => x + 1), 250);
    return () => clearInterval(t);
  }, [screen]);

  // 내 차례가 시작되면 선택 초기화 + 선언값 기본 세팅
  useEffect(() => {
    if (!ss?.myTurn) { setSel(null); return; }
    setSel(null);
    setDecSpice(ss.playableSpices[0] ?? null);
    setDecNumber(ss.playableNumbers[0] ?? null);
  }, [ss?.myTurn, ss?.declaredNumber, ss?.declaredSpice]);

  const create = async () => {
    const n = nick.trim(); if (!n) return; try { localStorage.setItem('arcade_nick', n); } catch {}
    try {
      const res = await api<{ roomCode: string; state: State }>(`/api/v1/spicy/new?clientId=${id.current}`, {
        method: 'POST', body: JSON.stringify({ nick: n, challengeSec, handPenalty }),
      });
      setRoomCode(res.roomCode); setSs(res.state); setScreen('lobby');
    } catch (e: any) { alert(e?.message || '방 생성 실패'); }
  };
  const join = async (code: string, nickOverride?: string) => {
    const n = (nickOverride ?? nick).trim(); if (!n || !code) return; setNick(n); try { localStorage.setItem('arcade_nick', n); } catch {}
    try {
      const s = await api<State>(`/api/v1/spicy/join?roomCode=${code}&clientId=${id.current}`, { method: 'POST', body: JSON.stringify({ nick: n }) });
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
    try { window.history.replaceState({}, '', '/spicy'); } catch {}
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, []);

  const addBot = async () => { try { setSs(await api(`/api/v1/spicy/add-bot?roomCode=${roomCode}&clientId=${id.current}&level=${botLevel}`, { method: 'POST', body: '{}' })); } catch (e: any) { alert(e?.message); } };
  const startMatch = async () => { try { setSs(await api(`/api/v1/spicy/start?roomCode=${roomCode}&clientId=${id.current}`, { method: 'POST', body: '{}' })); setScreen('game'); } catch (e: any) { alert(e?.message); } };
  const playCard = async () => {
    if (sel == null || decSpice == null || decNumber == null) return;
    try { setSs(await api(`/api/v1/spicy/play?roomCode=${roomCode}&clientId=${id.current}&cardId=${sel}&spice=${decSpice}&number=${decNumber}`, { method: 'POST', body: '{}' })); setSel(null); }
    catch (e: any) { alert(e?.message); }
  };
  const passTurn = async () => { try { setSs(await api(`/api/v1/spicy/pass?roomCode=${roomCode}&clientId=${id.current}`, { method: 'POST', body: '{}' })); } catch (e: any) { alert(e?.message); } };
  const challenge = async (kind: 'NUMBER' | 'SPICE') => { try { setSs(await api(`/api/v1/spicy/challenge?roomCode=${roomCode}&clientId=${id.current}&kind=${kind}`, { method: 'POST', body: '{}' })); } catch (e: any) { alert(e?.message); } };
  const leave = async () => { const rc = roomRef.current; if (rc) { try { await api(`/api/v1/spicy/leave?roomCode=${rc}&clientId=${id.current}`, { method: 'POST', body: '{}' }); } catch {} } setScreen('entry'); setRoomCode(null); setSs(null); };

  const beaconLeave = () => {
    const rc = roomRef.current;
    if (!rc || !id.current) return;
    try { navigator.sendBeacon(`/api/v1/spicy/leave?roomCode=${rc}&clientId=${encodeURIComponent(id.current)}`); } catch {}
  };
  const home = <Link href="/" aria-label="홈으로" onClick={beaconLeave} className="text-xl leading-none text-slate-500 hover:text-slate-800">🏠</Link>;

  const ended = ss?.phase === 'ENDED';
  const localNow = syncRef.current.at ? syncRef.current.serverNow + (Date.now() - syncRef.current.at) : (ss?.serverNow ?? 0);
  const remainMs = ss && ss.deadline > 0 ? Math.max(0, ss.deadline - localNow) : 0;
  const remainSec = Math.ceil(remainMs / 1000);
  const challengePct = ss && ss.turnPhase === 'CHALLENGE' ? Math.min(100, (remainMs / (ss.challengeSec * 1000)) * 100) : 0;
  const seatName = (s: number) => ss?.players.find((p) => p.seat === s)?.name ?? '?';
  const ranked = ss ? [...ss.players].filter((p) => !p.left).sort((a, b) => b.score - a.score) : [];

  return (
    <main className="min-h-screen flex flex-col items-center p-3 sm:p-5 max-w-6xl xl:max-w-[1500px] 2xl:max-w-[1760px] mx-auto w-full text-slate-800">
      <style>{`
        @keyframes spicy-pop { 0% { transform: scale(.85); opacity: 0 } 100% { transform: scale(1); opacity: 1 } }
        @keyframes spicy-float { 0%,100% { transform: translateY(0) } 50% { transform: translateY(-6px) } }
      `}</style>

      <div className="w-full flex items-center justify-between mb-3 sm:mb-4">
        <div className="flex items-center gap-2 sm:gap-3">
          {home}
          <div>
            <h1 className="text-xl sm:text-3xl font-extrabold tracking-tight">🌶️ 스파이시</h1>
            <p className="hidden sm:block text-xs text-slate-400 -mt-0.5">엎어 내고 뻥치는 카드 블러핑 — 숫자냐 스파이스냐, 콕 집어 도전!</p>
          </div>
        </div>
        {roomCode && (
          <div className="flex items-center gap-2">
            <span className="hidden sm:inline text-xs font-bold text-slate-400">방 {roomCode}</span>
            <button onClick={leave} className="text-xs px-3 py-1.5 rounded-lg bg-slate-700 text-slate-100 font-bold">나가기</button>
          </div>
        )}
      </div>

      {/* 입장 */}
      {screen === 'entry' && (
        <div className="w-full max-w-md space-y-4">
          <div className="rounded-2xl overflow-hidden border-2 border-rose-200">
            <div className="bg-gradient-to-br from-rose-100 via-amber-50 to-emerald-100 px-4 py-5 text-center">
              <div className="flex justify-center gap-2 mb-2">
                <SpicyCard c={{ id: -1, spice: 0, number: 7 }} size="sm" />
                <SpicyCard c={{ id: -2, spice: 1, number: 3 }} size="sm" />
                <SpicyCard c={{ id: -3, spice: ALL, number: NONE }} size="sm" />
              </div>
              <p className="text-sm font-bold text-slate-600">진짜일까 뻥일까 — 숫자냐 스파이스냐, 콕 집어 도전!</p>
            </div>
          </div>
          <input value={nick} onChange={(e) => setNick(e.target.value)} maxLength={16} placeholder="닉네임"
            className="w-full border-2 border-slate-200 rounded-xl px-4 py-3 focus:outline-none focus:border-rose-500" />
          <div className="rounded-2xl border-2 border-slate-200 p-4 space-y-3">
            <p className="text-sm font-bold">🆕 방 만들기</p>
            <label className="flex items-center justify-between text-sm">
              <span className="text-slate-500">도전 대기 시간</span>
              <select value={challengeSec} onChange={(e) => setChallengeSec(Number(e.target.value))} className="border-2 border-slate-200 rounded-lg px-2 py-1.5 font-bold">
                <option value={5}>5초 (스피드)</option><option value={8}>8초 (기본)</option>
                <option value={12}>12초 (여유)</option><option value={20}>20초 (느긋)</option>
              </select>
            </label>
            <label className="flex items-center justify-between text-sm cursor-pointer">
              <span className="text-slate-500">손패 감점 <span className="text-[11px] text-slate-400">(원작 룰 · 남은 카드 1장당 -1점)</span></span>
              <input type="checkbox" checked={handPenalty} onChange={(e) => setHandPenalty(e.target.checked)} className="w-5 h-5 accent-rose-600" />
            </label>
            <button onClick={create} disabled={!nick.trim()}
              className="w-full bg-gradient-to-b from-rose-500 to-rose-600 text-white font-extrabold py-3 rounded-xl shadow-md disabled:opacity-40 active:scale-[0.98] transition">방 만들기</button>
          </div>
          <div className="flex gap-2">
            <input value={joinCode} onChange={(e) => setJoinCode(e.target.value.toUpperCase())} maxLength={4} placeholder="코드"
              className="w-28 border-2 border-slate-200 rounded-xl px-3 py-2.5 uppercase tracking-widest font-extrabold text-center focus:outline-none focus:border-rose-500" />
            <button onClick={() => join(joinCode)} disabled={!nick.trim() || joinCode.length < 4}
              className="flex-1 bg-slate-700 text-white font-bold rounded-xl disabled:opacity-40">코드로 참가</button>
          </div>
          {rooms.length > 0 && (
            <div className="rounded-2xl border-2 border-slate-200 p-3 space-y-1.5">
              <p className="text-sm font-bold px-1">🏠 열린 방</p>
              {rooms.map((r) => (
                <button key={r.code} onClick={() => join(r.code)} disabled={!nick.trim() || r.status !== 'WAITING'}
                  className="w-full flex justify-between items-center text-sm px-3 py-2 rounded-xl border-2 border-slate-100 disabled:opacity-40 hover:border-rose-400 transition">
                  <span className="font-extrabold">{r.code} <span className="font-normal text-slate-500">· {r.host}</span></span>
                  <span className="text-slate-400">{r.status === 'WAITING' ? '모집중' : r.status === 'PLAYING' ? '진행중' : '종료'} · {r.playerCount}명</span>
                </button>
              ))}
            </div>
          )}
          <details className="rounded-2xl border-2 border-slate-200 p-4 text-sm text-slate-600">
            <summary className="font-bold cursor-pointer">📖 게임 방법 (2~10인)</summary>
            <div className="mt-2 space-y-1">
              <p>· 카드를 <b>뒷면으로</b> 내면서 「스파이스 + 숫자」를 말해요. <b>거짓말해도 됩니다!</b></p>
              <p>· 첫 장은 <b>1~3</b>, 다음부터는 <b>같은 스파이스의 더 큰 숫자</b>를 말해야 해요.</p>
              <p>· <b>10에 도달하면</b> 다음 사람부터 아무 스파이스나 1~3으로 다시 시작!</p>
              <p>· 낼 카드가 없으면 <b>패스</b>하고 덱에서 1장 가져가요.</p>
              <p>· 도전은 <b>「숫자 도전」인지 「스파이스 도전」인지 콕 집어야</b> 해요.</p>
              <p>· 지목한 쪽이 실제와 다르면 도전 성공! <b>같으면 도전 실패</b> — 거짓말이어도 엉뚱한 쪽을 찍으면 도전자가 집니다.</p>
              <p>· 승자가 더미를 몽땅 가져가 점수로 쌓고, 패자는 2장을 더 받아요.</p>
              <p>· 🌈 <b>모든 스파이스 카드</b>는 스파이스 도전에 무조건 이기고 숫자 도전엔 무조건 져요(✨모든 숫자 카드는 반대).</p>
              <p>· 손패를 다 내려놓으면 <b>트로피(10점)</b> 획득 후 6장 재충전. <b>트로피 2개면 즉시 승리!</b></p>
              <p>· 인원이 늘면 카드도 같은 비율로 늘어나요(6인 100장 / 8인 134장 / 10인 166장).</p>
            </div>
          </details>
        </div>
      )}

      {/* 로비 */}
      {screen === 'lobby' && ss && (
        <div className="w-full max-w-md mx-auto space-y-4">
          <div className="text-center rounded-2xl border-2 border-rose-200 bg-gradient-to-b from-rose-50 to-white py-4">
            <p className="text-sm text-slate-400">방 코드</p>
            <p className="text-4xl font-extrabold tracking-[0.3em] text-rose-600 pl-2">{roomCode}</p>
            <p className="text-xs text-slate-400 mt-1">도전 대기 {ss.challengeSec}초 · {ss.handPenalty ? '손패 감점 있음' : '손패 감점 없음'} · {ss.players.length}/10명</p>
          </div>
          <div className="rounded-2xl border-2 border-slate-200 p-3 space-y-1">
            {ss.players.map((p, i) => (
              <div key={i} className="flex items-center gap-2 text-sm px-2 py-1.5 rounded-lg hover:bg-slate-50">
                <span className="w-3.5 h-3.5 rounded-full border border-white shadow" style={{ background: SEAT_COLORS[i % SEAT_COLORS.length] }} />
                <span className="font-bold">{p.host ? '👑 ' : ''}{p.bot ? '🤖 ' : ''}{p.name}{p.me ? ' (나)' : ''}</span>
              </div>
            ))}
          </div>
          <p className="text-center text-xs font-bold text-slate-500 bg-slate-50 rounded-xl py-2">
            {(() => { const n = ss.players.length; const sets = n <= 6 ? 3 : n <= 8 ? 4 : 5; const w = n <= 6 ? 5 : n <= 8 ? 7 : 8; const t = n <= 6 ? 3 : n <= 8 ? 4 : 5; return `현재 ${n}인 → 🃏 카드 ${90 / 3 * sets + w * 2}장 · 🏆 트로피 ${t}개`; })()}
          </p>
          {ss.isHost ? (
            <div className="space-y-2">
              <div className="flex gap-2">
                <select value={botLevel} onChange={(e) => setBotLevel(e.target.value)} className="flex-1 border-2 border-slate-200 bg-transparent rounded-xl px-3 py-2 font-bold">
                  <option value="EASY">봇 초급</option><option value="NORMAL">봇 중급</option><option value="HARD">봇 고급</option>
                </select>
                <button onClick={addBot} className="px-4 bg-slate-700 text-white font-bold rounded-xl">봇 추가</button>
              </div>
              <button onClick={startMatch} disabled={ss.players.length < 2}
                className="w-full bg-gradient-to-b from-rose-500 to-rose-600 text-white font-extrabold py-3.5 rounded-xl shadow-md disabled:opacity-40 active:scale-[0.98] transition">시작하기</button>
            </div>
          ) : <p className="text-center text-sm text-slate-400">방장이 시작하기를 기다리는 중…</p>}
        </div>
      )}

      {/* 게임 */}
      {screen === 'game' && ss && (
        <div className="w-full grid grid-cols-1 lg:grid-cols-[1fr_320px] xl:grid-cols-[1fr_380px] gap-3 sm:gap-4 lg:gap-5 items-start lg:items-stretch lg:min-h-[calc(100vh-150px)]">
          {/* 왼쪽: 테이블 */}
          <div className="flex flex-col gap-3 sm:gap-4 min-w-0">
            <div className="flex items-center justify-between text-xs sm:text-sm lg:text-base flex-none">
              <span className="px-3 py-1.5 lg:px-4 lg:py-2 rounded-full bg-rose-100 text-rose-800 font-bold">
                🃏 덱 {ss.drawLeft} · 🏆 트로피 {ss.trophyLeft}/{ss.trophyTotal}
              </span>
              <span className="font-extrabold text-rose-600">
                {ended ? '게임 종료'
                  : ss.myTurn ? '내 차례 — 카드를 내세요!'
                  : ss.turnPhase === 'CHALLENGE' ? `🔍 도전 기회! ${remainSec}초`
                  : `${ss.turnName ?? ''}님 차례…`}
              </span>
            </div>

            {/* 테이블(더미) */}
            <div className="relative flex-1 lg:min-h-[260px] lg:max-h-[340px] rounded-3xl border-2 border-emerald-200 shadow-lg overflow-hidden flex flex-col items-center justify-center p-4">
              {/* 테이블 펠트 느낌 */}
              <div className="absolute inset-0 bg-gradient-to-b from-emerald-50 via-white to-amber-50" />
              <div className="absolute inset-0 opacity-[0.05]"
                style={{ backgroundImage: 'radial-gradient(circle at 1px 1px,#065f46 1px,transparent 0)', backgroundSize: '14px 14px' }} />
              <div className="relative flex flex-col sm:flex-row items-center justify-center gap-4 lg:gap-10">
                {ss.pileSize > 0 ? (
                  <>
                    <div className="relative shrink-0" style={{ width: 150, height: 175 }}>
                      {/* 쌓인 느낌으로 뒷면을 겹쳐 표시 */}
                      {Array.from({ length: Math.min(5, ss.pileSize) }).map((_, i) => (
                        <CardBack key={i} size="lg" style={{ left: i * 6, top: i * 5, transform: `rotate(${(i - 2) * 3}deg)` }} />
                      ))}
                      <span className="absolute -bottom-1 -right-1 z-10 bg-slate-800 text-white text-xs lg:text-sm font-extrabold rounded-full px-2.5 py-1 shadow">
                        {ss.pileSize}장
                      </span>
                    </div>
                    <div className="text-center">
                      <p className="text-xs lg:text-sm text-slate-400">{seatName(ss.lastPlayerSeat)}님의 선언</p>
                      <p className="text-3xl lg:text-6xl font-black leading-tight" style={{ color: ss.declaredSpice >= 0 ? SPICE[ss.declaredSpice].ink : '#334155' }}>
                        {ss.declaredSpice >= 0 ? `${SPICE[ss.declaredSpice].emoji} ${SPICE[ss.declaredSpice].name}` : ''} {ss.declaredNumber}
                      </p>
                      <p className="text-xs lg:text-sm text-slate-400 mt-1">
                        {ss.declaredNumber >= 10 ? '10 도달 — 다음은 아무 스파이스나 1~3!' : `다음 사람은 ${SPICE[ss.declaredSpice]?.name ?? ''} ${ss.declaredNumber + 1} 이상`}
                      </p>
                    </div>
                  </>
                ) : (
                  <div className="text-center text-slate-400">
                    <p className="text-4xl lg:text-6xl mb-2">🍽️</p>
                    <p className="text-sm lg:text-lg font-bold">새 스파이시 게임 — 아무 스파이스나 1~3으로 시작!</p>
                  </div>
                )}
              </div>
            </div>

            {/* 도전 판정 공개 */}
            {ss.lastReveal && !ended && (
              <div key={`${ss.lastReveal.challengerSeat}-${ss.lastReveal.pileTaken}-${ss.lastReveal.actualNumber}`}
                className={`rounded-2xl border-2 px-4 py-3 ${ss.lastReveal.success ? 'border-emerald-300 bg-emerald-50' : 'border-rose-300 bg-rose-50'}`}
                style={{ animation: 'spicy-pop .3s ease' }}>
                <div className="flex flex-wrap items-center justify-center gap-3 lg:gap-5">
                  <span className="font-bold text-sm lg:text-base">
                    🔍 {seatName(ss.lastReveal.challengerSeat)} ▸ {seatName(ss.lastReveal.accusedSeat)}
                    <span className="ml-1 text-xs lg:text-sm px-2 py-0.5 rounded-full bg-white border">
                      {ss.lastReveal.kind === 'NUMBER' ? '숫자 도전' : '스파이스 도전'}
                    </span>
                  </span>
                  <span className="flex items-center gap-2 lg:gap-3 text-sm">
                    <span className="text-slate-400">선언</span>
                    <SpicyCard c={{ id: -10, spice: ss.lastReveal.declaredSpice, number: ss.lastReveal.declaredNumber }} size="sm" />
                    <span className="text-slate-400">실제</span>
                    <SpicyCard c={{ id: -11, spice: ss.lastReveal.actualSpice, number: ss.lastReveal.actualNumber }} size="sm" />
                  </span>
                  <span className={`font-extrabold text-base lg:text-xl ${ss.lastReveal.success ? 'text-emerald-700' : 'text-rose-700'}`}>
                    {ss.lastReveal.success ? '도전 성공!' : '도전 실패…'} {ss.lastReveal.pileTaken}장
                  </span>
                </div>
              </div>
            )}

            {/* 종료 */}
            {ended && (
              <div className="text-center rounded-3xl border-2 border-rose-400 bg-gradient-to-b from-rose-50 to-amber-50 px-6 py-6 shadow" style={{ animation: 'spicy-pop .35s ease' }}>
                <p className="text-3xl mb-1">🎉🏆🎉</p>
                <p className="text-xl sm:text-2xl font-extrabold">{ss.winnerLabel} 승리!</p>
                <div className="mt-3 flex flex-wrap justify-center gap-2 text-sm">
                  {ranked.map((p, i) => (
                    <span key={p.seat} className={`px-3 py-1 rounded-full font-bold ${i === 0 ? 'bg-amber-400 text-amber-950' : 'bg-white border'}`}>
                      {i + 1}. {p.name} {p.score}점
                    </span>
                  ))}
                </div>
              </div>
            )}

            {/* 도전 버튼 */}
            {!ended && ss.turnPhase === 'CHALLENGE' && ss.canChallenge && (
              <div className="rounded-2xl border-2 border-amber-300 bg-gradient-to-b from-amber-50/60 to-white p-4 lg:p-5 space-y-3 shadow" style={{ animation: 'spicy-pop .25s ease' }}>
                <p className="text-center font-extrabold lg:text-lg">
                  「{ss.declaredSpice >= 0 ? SPICE[ss.declaredSpice].name : ''} {ss.declaredNumber}」 — 어느 쪽이 거짓일까요?
                </p>
                <div className="h-2 lg:h-3 bg-slate-100 rounded-full overflow-hidden">
                  <div className="h-full bg-amber-500 transition-[width] duration-200 ease-linear rounded-full" style={{ width: `${challengePct}%` }} />
                </div>
                <div className="grid grid-cols-2 gap-3">
                  <button onClick={() => challenge('NUMBER')}
                    className="bg-gradient-to-b from-violet-500 to-violet-600 text-white font-extrabold py-3 lg:py-4 rounded-2xl shadow active:scale-95 transition lg:text-lg">
                    🔢 숫자 도전
                  </button>
                  <button onClick={() => challenge('SPICE')}
                    className="bg-gradient-to-b from-rose-500 to-rose-600 text-white font-extrabold py-3 lg:py-4 rounded-2xl shadow active:scale-95 transition lg:text-lg">
                    🌶️ 스파이스 도전
                  </button>
                </div>
                <p className="text-center text-[11px] lg:text-xs text-slate-400">지목한 쪽이 실제와 다르면 성공! 같으면 내가 더미를 뒤집어씁니다 ({remainSec}초)</p>
              </div>
            )}
            {!ended && ss.turnPhase === 'CHALLENGE' && !ss.canChallenge && (
              <div className="rounded-2xl border-2 border-slate-200 bg-white p-4 text-center text-sm lg:text-base text-slate-400 font-bold">
                도전 대기 중… ({remainSec}초)
              </div>
            )}

            {/* 내 차례: 선언 */}
            {!ended && ss.myTurn && (
              <div className="rounded-2xl border-2 border-rose-400 bg-gradient-to-b from-rose-50/60 to-white p-4 lg:p-5 space-y-3 shadow">
                <div className="flex flex-wrap items-center justify-center gap-2 lg:gap-3">
                  <span className="text-sm lg:text-base font-bold text-slate-500">이렇게 선언:</span>
                  {ss.playableSpices.map((s) => (
                    <button key={s} onClick={() => setDecSpice(s)}
                      className={`px-3 py-1.5 lg:px-4 lg:py-2 rounded-xl border-2 font-extrabold text-sm lg:text-base transition ${decSpice === s ? 'text-white' : 'bg-white'}`}
                      style={decSpice === s ? { background: SPICE[s].ink, borderColor: SPICE[s].ink } : { borderColor: SPICE[s].edge, color: SPICE[s].ink }}>
                      {SPICE[s].emoji} {SPICE[s].name}
                    </button>
                  ))}
                  <select value={decNumber ?? ''} onChange={(e) => setDecNumber(Number(e.target.value))}
                    className="border-2 border-slate-300 rounded-xl px-3 py-1.5 lg:py-2 font-extrabold text-sm lg:text-base">
                    {ss.playableNumbers.map((n) => <option key={n} value={n}>{n}</option>)}
                  </select>
                </div>
                <div className="flex gap-2 lg:gap-3">
                  <button onClick={playCard} disabled={sel == null}
                    className="flex-1 bg-gradient-to-b from-rose-500 to-rose-600 text-white font-extrabold py-3 lg:py-4 rounded-2xl shadow disabled:opacity-40 active:scale-[0.98] transition lg:text-lg">
                    {sel == null ? '아래에서 낼 카드를 고르세요' : '🃏 이 카드로 선언!'}
                  </button>
                  <button onClick={passTurn}
                    className="px-5 lg:px-8 border-2 border-slate-300 font-bold rounded-2xl text-slate-500 hover:border-slate-400 lg:text-lg">
                    패스 <span className="text-xs">(+1장)</span>
                  </button>
                </div>
              </div>
            )}
            {!ended && !ss.myTurn && ss.turnPhase === 'PLAY' && (
              <div className="rounded-2xl border-2 border-slate-200 bg-white p-4 text-center text-sm lg:text-base text-slate-400 font-bold">
                🃏 {ss.turnName}님이 카드를 고르는 중…
              </div>
            )}

            {/* 내 손패 */}
            <div className="rounded-2xl border-2 border-slate-200 bg-white p-3 lg:p-4">
              <p className="text-xs lg:text-sm font-extrabold text-slate-400 mb-2">
                내 손패 {ss.myHand.length}장 {ss.handPenalty && <span className="font-normal">· 남으면 1장당 -1점</span>}
              </p>
              <div className="flex flex-wrap gap-2 lg:gap-3 justify-center pt-3">
                {ss.myHand.map((c) => (
                  <SpicyCard key={c.id} c={c} size="md" selected={sel === c.id}
                    onClick={ss.myTurn && !ended ? () => setSel(sel === c.id ? null : c.id) : undefined} />
                ))}
                {ss.myHand.length === 0 && <p className="text-sm text-slate-400 py-6">손패를 모두 내려놨어요!</p>}
              </div>
            </div>
          </div>

          {/* 오른쪽: 점수판 + 로그 */}
          <div className="flex flex-col gap-3 sm:gap-4 min-h-0">
            <div className="rounded-2xl border-2 border-slate-200 overflow-hidden bg-white flex-none">
              <p className="px-3 py-2 lg:py-2.5 text-xs lg:text-sm font-extrabold text-slate-400 bg-slate-50 border-b border-slate-100">점수판</p>
              {ranked.map((p) => (
                <div key={p.seat} className={`px-3 py-2 lg:py-2.5 border-b border-slate-50 last:border-b-0 ${p.seat === ss.turnSeat && !ended ? 'bg-rose-50' : ''}`}>
                  <div className="flex items-center justify-between">
                    <span className="font-bold text-sm lg:text-base flex items-center gap-1.5">
                      <span className="w-3 h-3 rounded-full border border-white shadow flex-none" style={{ background: SEAT_COLORS[p.seat % SEAT_COLORS.length] }} />
                      {p.bot ? '🤖 ' : ''}{p.name}{p.me ? ' (나)' : ''}
                    </span>
                    <span className="font-extrabold text-sm lg:text-base">{p.score}점</span>
                  </div>
                  <div className="flex items-center justify-between mt-1 text-[11px] lg:text-xs text-slate-400">
                    <span>🃏 손패 {p.handCount} · 획득 {p.won}</span>
                    <span className="flex items-center gap-0.5">
                      {p.trophies > 0 ? Array.from({ length: p.trophies }).map((_, i) => <TrophyCard key={i} size="sm" />) : '—'}
                    </span>
                  </div>
                </div>
              ))}
            </div>

            {ss.log.length > 0 && (
              <div className="rounded-2xl border-2 border-slate-200 bg-white overflow-hidden flex flex-col lg:flex-1 lg:min-h-0">
                <p className="px-3 py-2 lg:py-2.5 text-xs lg:text-sm font-extrabold text-slate-400 bg-slate-50 border-b border-slate-100 flex-none">진행 로그</p>
                <div className="p-2.5 lg:p-3 max-h-48 lg:max-h-none lg:flex-1 overflow-y-auto text-xs lg:text-sm text-slate-500 space-y-1 lg:space-y-1.5">
                  {[...ss.log].reverse().map((l, i) => <p key={ss.log.length - i}>{l}</p>)}
                </div>
              </div>
            )}
          </div>
        </div>
      )}

      {roomCode && <RoomChat game="spicy" roomCode={roomCode} clientId={id.current} nick={nick} />}
    </main>
  );
}
