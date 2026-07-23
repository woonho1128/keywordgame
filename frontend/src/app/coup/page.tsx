'use client';

import { useCallback, useEffect, useRef, useState } from 'react';
import { api } from '@/lib/api';
import RoomChat from '@/components/RoomChat';

type PlayerView = { seat: number; nick: string; bot: boolean; coins: number; alive: boolean; influence: number; cards: (string | null)[]; current: boolean };
type PendingView = { step: string; actorSeat: number; action: string | null; targetSeat: number; claimChar: string | null; blockerSeat: number; blockChar: string | null; loserSeat: number; deadline: number };
type CoupState = {
  status: 'NULL_ROOM' | 'LOBBY' | 'PLAYING' | 'ENDED'; serverNow: number; reactionSec: number;
  isHost: boolean; joined: boolean; seat: number; nick: string | null;
  players: PlayerView[]; myCards: (string | null)[]; currentSeat: number; step: string; pending: PendingView | null;
  myTurn: boolean; canRespond: boolean; canChallenge: boolean; canBlock: boolean; blockOptions: string[];
  mustLose: boolean; mustExchange: boolean; exchangeOptions: string[]; exchangeKeep: number;
  log: string[]; winnerSeat: number; playerCount: number; version: number;
};
type RoomSummary = { code: string; status: string; playerCount: number; host: string };

const CID_KEY = 'coup_client_id', NICK_KEY = 'coup_nick', ROOM_KEY = 'coup_room';
const CHAR: Record<string, { label: string; emoji: string }> = {
  DUKE: { label: '공작', emoji: '🎩' }, ASSASSIN: { label: '암살자', emoji: '🗡️' },
  CAPTAIN: { label: '대장', emoji: '⚓' }, AMBASSADOR: { label: '대사', emoji: '🎭' }, CONTESSA: { label: '백작부인', emoji: '👒' },
};
const ACT: Record<string, string> = { INCOME: '소득', FOREIGN_AID: '해외원조', COUP: '쿠', TAX: '세금', ASSASSINATE: '암살', STEAL: '강탈', EXCHANGE: '교환' };
const cName = (c: string | null) => (c && CHAR[c] ? `${CHAR[c].emoji}${CHAR[c].label}` : '');

const CARD_THEME: Record<string, { bg: string; emoji: string; label: string }> = {
  DUKE: { bg: 'from-purple-400 to-purple-600', emoji: '🎩', label: '공작' },
  ASSASSIN: { bg: 'from-slate-600 to-slate-800', emoji: '🗡️', label: '암살자' },
  CAPTAIN: { bg: 'from-sky-400 to-blue-600', emoji: '⚓', label: '대장' },
  AMBASSADOR: { bg: 'from-emerald-400 to-green-600', emoji: '🎭', label: '대사' },
  CONTESSA: { bg: 'from-rose-400 to-pink-600', emoji: '👒', label: '백작부인' },
};

/** 진짜 카드처럼 보이는 쿠 카드. back=뒷면, lost=공개(상실)됨. */
function CoupCard({ char, back, lost, size = 'sm' }: { char?: string | null; back?: boolean; lost?: boolean; size?: 'sm' | 'lg' }) {
  const dim = size === 'lg' ? 'w-[70px] h-[98px]' : 'w-11 h-16';
  if (back) {
    return (
      <div className={`${dim} rounded-xl bg-gradient-to-br from-indigo-500 to-indigo-800 border border-indigo-900/30 shadow-md flex items-center justify-center ring-1 ring-white/20`}>
        <span className={`${size === 'lg' ? 'text-3xl' : 'text-lg'} opacity-40`}>🎴</span>
      </div>
    );
  }
  const t = char ? CARD_THEME[char] : null;
  return (
    <div className={`${dim} rounded-xl border border-black/10 shadow-md flex flex-col items-center justify-center gap-0.5 text-white relative bg-gradient-to-br ${t ? t.bg : 'from-gray-300 to-gray-400'} ${lost ? 'grayscale opacity-60' : ''}`}>
      <span className={size === 'lg' ? 'text-3xl drop-shadow' : 'text-xl'}>{t?.emoji}</span>
      {size === 'lg' && <span className="text-[11px] font-bold drop-shadow">{t?.label}</span>}
      {lost && (
        <span className="absolute inset-0 flex items-center justify-center">
          <span className={`text-red-600 font-black ${size === 'lg' ? 'text-base' : 'text-[9px]'} -rotate-12 bg-white/80 px-1 rounded`}>상실</span>
        </span>
      )}
    </div>
  );
}

function getClientId(): string {
  if (typeof window === 'undefined') return '';
  try {
    let id = localStorage.getItem(CID_KEY) || '';
    if (!id) { id = crypto?.randomUUID?.() ?? `c_${Date.now()}_${Math.random().toString(36).slice(2)}`; localStorage.setItem(CID_KEY, id); }
    return id;
  } catch { return `c_${Math.random().toString(36).slice(2)}`; }
}

export default function CoupPage() {
  const [clientId, setClientId] = useState('');
  const [st, setSt] = useState<CoupState | null>(null);
  const [rooms, setRooms] = useState<RoomSummary[]>([]);
  const [roomCode, setRoomCode] = useState<string | null>(null);
  const [error, setError] = useState<string | null>(null);
  const [busy, setBusy] = useState(false);
  const roomRef = useRef<string | null>(null);
  const cidRef = useRef('');
  if (typeof window !== 'undefined' && !cidRef.current) cidRef.current = getClientId(); // 동기 초기화(빈 clientId 전송 방지)
  const offsetRef = useRef(0);
  const deadlineRef = useRef(0);

  const [nick, setNick] = useState('');
  const [showCreate, setShowCreate] = useState(false);
  const [reactionSec, setReactionSec] = useState(15);
  const [remaining, setRemaining] = useState(0);
  const [targeting, setTargeting] = useState<string | null>(null); // action awaiting target
  const [exchSel, setExchSel] = useState<number[]>([]);
  const [showGuide, setShowGuide] = useState(false);

  const cid = () => encodeURIComponent(cidRef.current || clientId);
  const rp = () => `roomCode=${roomCode}&clientId=${cid()}`;

  const changeRoom = useCallback((code: string | null) => {
    roomRef.current = code; setRoomCode(code);
    try { if (code) localStorage.setItem(ROOM_KEY, code); else localStorage.removeItem(ROOM_KEY); } catch {}
  }, []);

  const poll = useCallback(async () => {
    const code = roomRef.current, c = cidRef.current;
    if (!code) { try { setRooms(await api<RoomSummary[]>('/api/v1/coup/rooms')); } catch {} return; }
    try {
      const res = await api<CoupState>(`/api/v1/coup/me?roomCode=${code}&clientId=${encodeURIComponent(c)}`);
      if (res.status === 'NULL_ROOM') { changeRoom(null); setSt(null); }
      else { offsetRef.current = res.serverNow - Date.now(); deadlineRef.current = res.pending?.deadline ?? 0; setSt(res); }
    } catch {}
  }, [changeRoom]);

  useEffect(() => {
    const id = getClientId(); setClientId(id); cidRef.current = id;
    try { setNick(localStorage.getItem(NICK_KEY) || ''); const s = localStorage.getItem(ROOM_KEY); if (s) { roomRef.current = s; setRoomCode(s); } } catch {}
    poll();
    const t = setInterval(poll, 1000);
    const tk = setInterval(() => {
      if (deadlineRef.current > 0) setRemaining(Math.max(0, Math.ceil((deadlineRef.current - (Date.now() + offsetRef.current)) / 1000)));
      else setRemaining(0);
    }, 250);
    return () => { clearInterval(t); clearInterval(tk); };
  }, [poll]);

  const post = useCallback(async (path: string, body?: unknown) => {
    setBusy(true); setError(null);
    try { const res = await api<CoupState>(path, { method: 'POST', body: body ? JSON.stringify(body) : undefined }); setSt(res); deadlineRef.current = res.pending?.deadline ?? 0; offsetRef.current = res.serverNow - Date.now(); return res; }
    catch (e) { setError(e instanceof Error ? e.message : '오류가 발생했습니다'); return null; }
    finally { setBusy(false); }
  }, []);

  const saveNick = (n: string) => { try { localStorage.setItem(NICK_KEY, n); } catch {} };
  const handleCreate = async () => {
    const n = nick.trim(); if (!n) return setError('닉네임을 입력하세요');
    saveNick(n); setBusy(true); setError(null);
    try {
      const res = await api<{ roomCode: string; state: CoupState }>(`/api/v1/coup/new?clientId=${cid()}`, { method: 'POST', body: JSON.stringify({ nick: n, reactionSec }) });
      changeRoom(res.roomCode); setSt(res.state); setShowCreate(false);
    } catch (e) { setError(e instanceof Error ? e.message : '방 생성 실패'); } finally { setBusy(false); }
  };
  const handleJoin = async (code: string) => { const n = nick.trim(); if (!n) return setError('닉네임을 입력하세요'); saveNick(n); changeRoom(code); await post(`/api/v1/coup/join?roomCode=${code}&clientId=${cid()}`, { nick: n }); };
  const handleAddBot = () => post(`/api/v1/coup/add-bot?${rp()}`);
  const handleStart = () => post(`/api/v1/coup/start?${rp()}`);
  const handleLeave = () => { api(`/api/v1/coup/leave?${rp()}`, { method: 'POST' }).catch(() => {}); changeRoom(null); setSt(null); };

  const sendAct = (action: string, target?: number) => { setTargeting(null); post(`/api/v1/coup/act?${rp()}`, { action, target: target ?? null }); };
  const clickAction = (action: string) => {
    if (action === 'COUP' || action === 'ASSASSINATE' || action === 'STEAL') setTargeting(action);
    else sendAct(action);
  };
  const clickTarget = (seat: number) => { if (targeting) sendAct(targeting, seat); };
  const respond = (decision: string, blockCard?: string) => post(`/api/v1/coup/respond?${rp()}`, { decision, blockCard: blockCard ?? null });
  const loseCard = (unrevealedIdx: number) => post(`/api/v1/coup/lose-card?${rp()}`, { cardIndex: unrevealedIdx });
  const doExchange = () => { post(`/api/v1/coup/exchange?${rp()}`, { keep: exchSel }); setExchSel([]); };

  const guidePanel = showGuide && (
    <div className="fixed inset-0 z-50 flex items-end sm:items-center justify-center bg-black/50" onClick={() => setShowGuide(false)}>
      <div onClick={(e) => e.stopPropagation()} className="w-full sm:max-w-md bg-white rounded-t-2xl sm:rounded-2xl max-h-[85vh] overflow-y-auto shadow-xl">
        <div className="sticky top-0 bg-white border-b px-5 py-3 flex justify-between items-center"><h2 className="text-lg font-extrabold">🎴 쿠 규칙</h2><button onClick={() => setShowGuide(false)} className="w-9 h-9 rounded-full bg-gray-100">✕</button></div>
        <div className="px-5 py-4 space-y-4 text-sm text-gray-700">
          <p>영향력 카드 2장·코인 2개로 시작. <b>마지막 생존자 승리</b>. 카드 2장 다 잃으면 탈락. <b>아무 카드나 있다고 우길 수 있어요(블러핑)</b>.</p>
          <div>
            <h3 className="font-bold mb-1">카드 & 능력</h3>
            <ul className="space-y-1">
              <li>🎩 <b>공작</b> — 세금(+3), 해외원조 차단</li>
              <li>🗡️ <b>암살자</b> — 암살(코인3, 대상 카드1)</li>
              <li>⚓ <b>대장</b> — 강탈(코인2 뺏기), 강탈 차단</li>
              <li>🎭 <b>대사</b> — 교환(카드 갈기), 강탈 차단</li>
              <li>👒 <b>백작부인</b> — 암살 차단</li>
            </ul>
          </div>
          <div>
            <h3 className="font-bold mb-1">일반 액션</h3>
            <ul className="space-y-1"><li><b>소득</b> +1 (안전)</li><li><b>해외원조</b> +2 (공작이 차단)</li><li><b>쿠</b> 코인7, 대상 카드1 제거 (막을 수 없음). 코인 10↑이면 강제</li></ul>
          </div>
          <div>
            <h3 className="font-bold mb-1">의심 & 차단</h3>
            <p><b>의심</b>: 주장이 뻥이면 주장자가, 진짜면 의심한 사람이 카드 1장 잃어요. <b>차단</b>도 의심당할 수 있어요.</p>
          </div>
        </div>
        <div className="px-5 pb-5"><button onClick={() => setShowGuide(false)} className="w-full bg-hit text-white font-bold py-3 rounded-xl">확인</button></div>
      </div>
    </div>
  );

  // ---------- 방 목록 ----------
  if (!roomCode) {
    return (
      <main className="min-h-screen flex flex-col items-center p-6 max-w-lg mx-auto w-full">
        <div className="w-full flex items-center justify-between mb-4"><h1 className="text-2xl font-bold">🎴 쿠 (Coup)</h1><button onClick={() => setShowGuide(true)} className="text-sm font-bold text-hit">📖 규칙</button></div>
        {showCreate ? (
          <div className="w-full space-y-4">
            <input value={nick} onChange={(e) => setNick(e.target.value)} maxLength={16} placeholder="내 닉네임" className="w-full border border-gray-300 rounded-lg px-3 py-2" />
            <div>
              <p className="text-sm font-bold text-gray-600 mb-1">의심/차단 반응 제한시간</p>
              <div className="grid grid-cols-3 gap-2">{[8, 15, 25].map((s) => <button key={s} onClick={() => setReactionSec(s)} className={`py-2 rounded-lg border-2 text-sm font-bold ${reactionSec === s ? 'border-hit bg-hit/5 text-hit' : 'border-gray-200 text-gray-500'}`}>{s}초</button>)}</div>
            </div>
            <div className="flex gap-2"><button onClick={() => setShowCreate(false)} className="flex-1 border py-3 rounded-lg">취소</button><button onClick={handleCreate} disabled={busy} className="flex-[2] bg-hit text-white font-bold py-3 rounded-lg disabled:opacity-40">방 만들기</button></div>
          </div>
        ) : (
          <div className="w-full space-y-3">
            <button onClick={() => { setShowCreate(true); setError(null); }} className="w-full bg-hit text-white font-bold py-3 rounded-lg">+ 새 방 만들기</button>
            <p className="text-sm font-bold text-gray-600">방 목록</p>
            {rooms.length === 0 && <p className="text-gray-400 text-sm text-center py-6">아직 만들어진 방이 없어요.</p>}
            {rooms.map((r) => {
              const badge = r.status === 'WAITING' ? '모집중' : r.status === 'PLAYING' ? '진행중' : '종료';
              const cls = r.status === 'WAITING' ? 'bg-green-100 text-green-700' : r.status === 'PLAYING' ? 'bg-yellow-100 text-yellow-700' : 'bg-gray-100 text-gray-400';
              return (
                <div key={r.code} className="flex items-center justify-between border border-gray-200 rounded-lg px-4 py-3">
                  <div><span className="font-bold tracking-wider">{r.code}</span><span className="text-xs text-gray-400 ml-2">{r.host} · {r.playerCount}명</span></div>
                  <div className="flex items-center gap-2"><span className={`text-xs px-2 py-1 rounded-full ${cls}`}>{badge}</span>{r.status === 'WAITING' ? <button onClick={() => handleJoin(r.code)} className="text-sm font-bold text-hit">참가</button> : <span className="text-sm text-gray-300">{badge}</span>}</div>
                </div>
              );
            })}
          </div>
        )}
        {error && <p className="text-red-500 text-sm mt-4 text-center">{error}</p>}
        {guidePanel}
        <RoomChat game="coup" roomCode="lobby" clientId={cidRef.current || clientId} nick={nick} />
      </main>
    );
  }

  if (!st) return <main className="min-h-screen flex items-center justify-center"><p className="text-gray-400">불러오는 중...</p></main>;
  const phase = st.status;
  const me = st.players.find((p) => p.seat === st.seat);
  const p = st.pending;
  const actorNick = p && p.actorSeat >= 0 ? st.players[p.actorSeat]?.nick : '';
  const targetNick = p && p.targetSeat >= 0 ? st.players[p.targetSeat]?.nick : '';
  const mustCoup = !!me && me.coins >= 10;

  // 반응 안내 문구
  const pendingDesc = () => {
    if (!p) return '';
    if (p.step === 'CHALLENGE_ACTION') return `${actorNick}이(가) ${cName(p.claimChar)} 주장${targetNick ? ` → ${targetNick}` : ''} · ${ACT[p.action || '']}`;
    if (p.step === 'BLOCK_ACTION') return `${actorNick}: ${ACT[p.action || '']}${targetNick ? ` → ${targetNick}` : ''} · 차단할까요?`;
    if (p.step === 'CHALLENGE_BLOCK') return `${st.players[p.blockerSeat]?.nick}이(가) ${cName(p.blockChar)} 주장으로 차단 · 의심할까요?`;
    return '';
  };

  return (
    <main className="min-h-screen flex flex-col items-center p-4 max-w-lg mx-auto w-full">
      <div className="w-full flex items-center justify-between gap-2 mb-3">
        <div className="flex items-center gap-2 min-w-0">
          <h1 className="text-lg font-bold shrink-0">🎴 쿠</h1>
          <span className="text-xs bg-gray-100 rounded px-2 py-1 tracking-wider font-bold shrink-0">{roomCode}</span>
          <button onClick={handleLeave} className="text-xs text-gray-400 underline shrink-0">나가기</button>
        </div>
        <div className="flex items-center gap-2 shrink-0">
          <button onClick={() => setShowGuide(true)} className="text-xs font-bold text-hit">📖 규칙</button>
          {me && <span className="text-sm font-bold text-amber-600">💰 {me.coins}</span>}
        </div>
      </div>

      {/* 플레이어 보드 */}
      <div className="w-full grid grid-cols-2 gap-2 mb-3">
        {st.players.map((pl) => {
          const isTargetable = targeting && pl.alive && pl.seat !== st.seat;
          return (
            <button key={pl.seat} onClick={isTargetable ? () => clickTarget(pl.seat) : undefined}
              className={`relative rounded-2xl border-2 p-2.5 text-left transition ${!pl.alive ? 'opacity-50 border-gray-200 bg-gray-50' : pl.current ? 'border-hit bg-hit/5 shadow-sm' : 'border-gray-200 bg-white'} ${isTargetable ? 'ring-2 ring-red-400 cursor-pointer' : ''}`}>
              <div className="flex items-center justify-between gap-1 mb-1.5">
                <span className="text-sm font-bold truncate flex items-center gap-0.5 min-w-0">
                  {pl.current && <span className="text-hit shrink-0">▶</span>}{pl.bot && '🤖'}<span className="truncate">{pl.nick}</span>{pl.seat === st.seat && <span className="text-hit shrink-0">(나)</span>}
                </span>
                <span className="shrink-0 inline-flex items-center gap-0.5 text-xs font-extrabold text-amber-700 bg-amber-100 rounded-full px-2 py-0.5">🪙{pl.coins}</span>
              </div>
              <div className="flex gap-1">
                {pl.cards.map((c, i) => <CoupCard key={i} char={c} back={!c} lost={!!c} size="sm" />)}
              </div>
              {!pl.alive && <span className="absolute inset-0 flex items-center justify-center text-sm font-extrabold text-gray-500">💀 탈락</span>}
              {isTargetable && <span className="absolute top-1.5 right-1.5 text-[10px] bg-red-500 text-white rounded-full px-1.5 py-0.5 font-bold animate-pulse">대상</span>}
            </button>
          );
        })}
      </div>

      {/* 대기방 */}
      {phase === 'LOBBY' && (
        <div className="w-full space-y-2">
          <p className="text-center text-xs text-gray-400">2~6인 · 반응 {st.reactionSec}초</p>
          {st.isHost ? (
            <>
              <button onClick={handleAddBot} disabled={busy} className="w-full border-2 border-gray-200 py-2 rounded-lg text-sm font-bold text-gray-600">🤖 봇 추가</button>
              <button onClick={handleStart} disabled={busy || st.players.length < 2} className="w-full bg-hit text-white font-bold py-3 rounded-lg disabled:opacity-40">{st.players.length < 2 ? '최소 2명 필요' : '게임 시작'}</button>
            </>
          ) : <p className="text-center text-gray-500 text-sm">방장이 시작하기를 기다리는 중...</p>}
        </div>
      )}

      {/* 진행 */}
      {phase === 'PLAYING' && (
        <div className="w-full space-y-3">
          {/* 내 손패 */}
          {me && (
            <div className="rounded-2xl bg-gradient-to-b from-indigo-50 to-indigo-100/40 border border-indigo-100 p-3">
              <p className="text-[11px] text-indigo-400 font-bold mb-2 text-center">🔒 내 정체 (나만 보임)</p>
              <div className="flex gap-3 justify-center">
                {st.myCards.map((c, i) => <CoupCard key={i} char={c || me.cards[i]} lost={!c} size="lg" />)}
              </div>
            </div>
          )}

          {/* 내 턴 액션바 */}
          {st.myTurn && (
            <div className="rounded-xl border border-gray-200 p-3 space-y-2">
              {targeting ? (
                <p className="text-center text-sm font-bold text-red-500">🎯 {ACT[targeting]} 대상을 고르세요 <button onClick={() => setTargeting(null)} className="text-xs text-gray-400 underline ml-1">취소</button></p>
              ) : (
                <>
                  <p className="text-center text-sm font-bold text-hit">내 차례!</p>
                  <div className="grid grid-cols-3 gap-1.5 text-sm">
                    <button onClick={() => clickAction('INCOME')} disabled={busy || mustCoup} className="py-2 rounded-lg bg-gray-100 font-bold disabled:opacity-30">소득 +1</button>
                    <button onClick={() => clickAction('FOREIGN_AID')} disabled={busy || mustCoup} className="py-2 rounded-lg bg-gray-100 font-bold disabled:opacity-30">해외원조 +2</button>
                    <button onClick={() => clickAction('COUP')} disabled={busy || (me?.coins ?? 0) < 7} className="py-2 rounded-lg bg-red-500 text-white font-bold disabled:opacity-30">쿠 (7)</button>
                    <button onClick={() => clickAction('TAX')} disabled={busy || mustCoup} className="py-2 rounded-lg bg-purple-100 text-purple-700 font-bold disabled:opacity-30">🎩세금 +3</button>
                    <button onClick={() => clickAction('ASSASSINATE')} disabled={busy || mustCoup || (me?.coins ?? 0) < 3} className="py-2 rounded-lg bg-rose-100 text-rose-700 font-bold disabled:opacity-30">🗡️암살 (3)</button>
                    <button onClick={() => clickAction('STEAL')} disabled={busy || mustCoup} className="py-2 rounded-lg bg-sky-100 text-sky-700 font-bold disabled:opacity-30">⚓강탈</button>
                    <button onClick={() => clickAction('EXCHANGE')} disabled={busy || mustCoup} className="col-span-3 py-2 rounded-lg bg-emerald-100 text-emerald-700 font-bold disabled:opacity-30">🎭교환</button>
                  </div>
                  {mustCoup && <p className="text-center text-[11px] text-red-500">코인 10개 이상 — 쿠만 가능</p>}
                  {!mustCoup && (me?.coins ?? 0) < 7 && (
                    <p className="text-center text-[11px] text-gray-400">💡 쿠(코인 7)·암살(코인 3)은 비용이 있어야 선언 가능 — 블러핑이어도 비용은 냅니다</p>
                  )}
                </>
              )}
            </div>
          )}

          {/* 반응바 */}
          {st.canRespond && p && (
            <div className="rounded-xl border-2 border-amber-300 bg-amber-50 p-3 space-y-2">
              <p className="text-center text-sm font-bold text-amber-800">{pendingDesc()} <span className="text-hit">({remaining}s)</span></p>
              <div className="flex flex-wrap gap-1.5 justify-center">
                {st.canChallenge && <button onClick={() => respond('CHALLENGE')} disabled={busy} className="px-4 py-2 rounded-lg bg-red-500 text-white font-bold text-sm">🤨 의심</button>}
                {st.canBlock && st.blockOptions.map((b) => <button key={b} onClick={() => respond('BLOCK', b)} disabled={busy} className="px-3 py-2 rounded-lg bg-indigo-500 text-white font-bold text-sm">🛡️ {cName(b)}로 차단</button>)}
                <button onClick={() => respond('PASS')} disabled={busy} className="px-4 py-2 rounded-lg bg-gray-200 font-bold text-sm">통과</button>
              </div>
            </div>
          )}

          {/* 대기 안내(내가 응답 대상 아님) */}
          {!st.myTurn && !st.canRespond && !st.mustLose && !st.mustExchange && p && (
            <p className="text-center text-xs text-gray-400">{pendingDesc() || `${actorNick}님 진행 중...`}</p>
          )}
          {!st.myTurn && !st.canRespond && !p && (
            <p className="text-center text-xs text-gray-400">{st.players[st.currentSeat]?.nick}님 차례...</p>
          )}

          {/* 로그 */}
          <div className="rounded-lg bg-gray-50 border border-gray-100 p-2 max-h-32 overflow-y-auto text-[11px] text-gray-500 space-y-0.5">
            {st.log.slice(-8).map((l, i) => <div key={i}>{l}</div>)}
          </div>
        </div>
      )}

      {phase === 'ENDED' && (
        <div className="w-full text-center space-y-3 mt-2">
          <p className="text-2xl font-extrabold text-hit">🏆 {st.players[st.winnerSeat]?.nick} 승리!</p>
          <button onClick={handleLeave} className="w-full bg-hit text-white font-bold py-3 rounded-lg">나가기</button>
        </div>
      )}

      {/* 카드 상실 모달 */}
      {st.mustLose && me && (
        <div className="fixed inset-0 z-50 flex items-center justify-center bg-black/50 p-4">
          <div className="bg-white rounded-2xl p-5 w-full max-w-xs space-y-3">
            <p className="font-bold text-center">잃을 영향력 카드를 선택하세요</p>
            <div className="flex gap-2 justify-center">
              {st.myCards.map((c, i, arr) => {
                if (!c) return null; // 이미 공개(상실)된 카드는 선택 불가
                const ui = arr.slice(0, i).filter((x) => x).length; // 안 공개된 카드 중 몇 번째인지
                return <button key={i} onClick={() => loseCard(ui)} disabled={busy} className="px-4 py-3 rounded-lg bg-white border-2 border-indigo-300 font-bold">{cName(c)}</button>;
              })}
            </div>
            <p className="text-[11px] text-gray-400 text-center">공개된 카드는 이후 모두에게 보입니다</p>
          </div>
        </div>
      )}

      {/* 교환 모달 */}
      {st.mustExchange && (
        <div className="fixed inset-0 z-50 flex items-center justify-center bg-black/50 p-4">
          <div className="bg-white rounded-2xl p-5 w-full max-w-sm space-y-3">
            <p className="font-bold text-center">교환: 남길 카드 {st.exchangeKeep}장을 고르세요</p>
            <div className="flex flex-wrap gap-2 justify-center">
              {st.exchangeOptions.map((c, i) => {
                const on = exchSel.includes(i);
                return <button key={i} onClick={() => setExchSel((cur) => on ? cur.filter((x) => x !== i) : cur.length < st.exchangeKeep ? [...cur, i] : cur)}
                  className={`px-3 py-3 rounded-lg border-2 font-bold text-sm ${on ? 'border-hit bg-hit/10 text-hit' : 'border-gray-200'}`}>{cName(c)}</button>;
              })}
            </div>
            <button onClick={doExchange} disabled={busy || exchSel.length !== st.exchangeKeep} className="w-full bg-hit text-white font-bold py-2.5 rounded-lg disabled:opacity-40">확정 ({exchSel.length}/{st.exchangeKeep})</button>
          </div>
        </div>
      )}

      {error && <p className="text-red-500 text-sm mt-2 text-center">{error}</p>}
      {guidePanel}
      <RoomChat game="coup" roomCode={roomCode} clientId={cidRef.current || clientId} nick={nick} />
    </main>
  );
}
