'use client';

import { useCallback, useEffect, useRef, useState } from 'react';
import { api } from '@/lib/api';

type PlayerView = { seat: number; nick: string; down: number; up: number; topFruit: string | null; topCount: number; alive: boolean };
type RoomSummary = { code: string; status: string; playerCount: number; host: string };

type HgState = {
  status: 'NOT_STARTED' | 'LOBBY' | 'PLAYING' | 'ENDED';
  serverNow: number;
  isHost: boolean;
  joined: boolean;
  seat: number;
  nick: string | null;
  players: PlayerView[];
  currentSeat: number;
  isMyTurn: boolean;
  winnerSeat: number;
  winnerNick: string | null;
  lastAction: string | null;
  playerCount: number;
  version: number;
};

const CLIENT_ID_KEY = 'halligalli_client_id';
const NICK_KEY = 'halligalli_nick';
const ROOM_KEY = 'halligalli_room';

const FRUIT: Record<string, { emoji: string; bg: string }> = {
  BANANA: { emoji: '🍌', bg: 'bg-yellow-100' },
  STRAWBERRY: { emoji: '🍓', bg: 'bg-red-100' },
  LIME: { emoji: '🍋', bg: 'bg-lime-100' },
  PLUM: { emoji: '🍇', bg: 'bg-purple-100' },
};

function getClientId(): string {
  if (typeof window === 'undefined') return '';
  try {
    let id = localStorage.getItem(CLIENT_ID_KEY) || '';
    if (!id) {
      id = typeof crypto !== 'undefined' && 'randomUUID' in crypto
        ? crypto.randomUUID() : `c_${Date.now()}_${Math.random().toString(36).slice(2)}`;
      localStorage.setItem(CLIENT_ID_KEY, id);
    }
    return id;
  } catch { return `c_${Math.random().toString(36).slice(2)}`; }
}

export default function HalliGalliPage() {
  const [clientId, setClientId] = useState('');
  const [st, setSt] = useState<HgState | null>(null);
  const [error, setError] = useState<string | null>(null);
  const [busy, setBusy] = useState(false);
  const [flash, setFlash] = useState<string | null>(null);

  const [roomCode, setRoomCode] = useState<string | null>(null);
  const [rooms, setRooms] = useState<RoomSummary[]>([]);
  const roomRef = useRef<string | null>(null);

  const [nick, setNick] = useState('');
  const [showCreate, setShowCreate] = useState(false);
  const [showRules, setShowRules] = useState(false);
  const [showAdmin, setShowAdmin] = useState(false);
  const [adminInput, setAdminInput] = useState('');

  const cidRef = useRef('');
  const verRef = useRef(-1);
  const esRef = useRef<EventSource | null>(null);
  const stRef = useRef<HgState | null>(null);
  useEffect(() => { stRef.current = st; }, [st]);

  const changeRoom = useCallback((code: string | null) => {
    roomRef.current = code;
    verRef.current = -1;
    setRoomCode(code);
    try { if (code) localStorage.setItem(ROOM_KEY, code); else localStorage.removeItem(ROOM_KEY); } catch {}
  }, []);

  // 서버 상태 적용(버전 역행 방지)
  const applyState = useCallback((s: HgState) => {
    if (s.status === 'NOT_STARTED') { changeRoom(null); setSt(null); return; }
    if (s.version >= verRef.current) { verRef.current = s.version; setSt(s); }
  }, [changeRoom]);

  // 방 목록 폴링(방 밖일 때) + SSE 백업 폴링(방 안일 때)
  const poll = useCallback(async () => {
    const code = roomRef.current;
    const c = cidRef.current;
    if (!code) {
      try { setRooms(await api<RoomSummary[]>('/api/v1/halligalli/rooms')); } catch {}
      return;
    }
    try {
      const res = await api<HgState>(`/api/v1/halligalli/me?roomCode=${code}&clientId=${encodeURIComponent(c)}`);
      applyState(res);
    } catch { /* ignore */ }
  }, [applyState]);

  useEffect(() => {
    const id = getClientId();
    setClientId(id); cidRef.current = id;
    try {
      setNick(localStorage.getItem(NICK_KEY) || '');
      const saved = localStorage.getItem(ROOM_KEY);
      if (saved) { roomRef.current = saved; setRoomCode(saved); }
    } catch {}
    // 자가 스케줄 폴링: 게임 중엔 빠르게(600ms), 대기/목록은 느리게. SSE가 막혀도 체감 지연 최소화.
    let stop = false;
    let timer: ReturnType<typeof setTimeout>;
    const tick = async () => {
      await poll();
      if (stop) return;
      const delay = !roomRef.current ? 2500 : (stRef.current?.status === 'PLAYING' ? 600 : 1500);
      timer = setTimeout(tick, delay);
    };
    tick();
    return () => { stop = true; clearTimeout(timer); };
  }, [poll]);

  // SSE 연결(방 안일 때만)
  useEffect(() => {
    if (!roomCode || !clientId) { esRef.current?.close(); esRef.current = null; return; }
    const es = new EventSource(`/api/v1/halligalli/stream?roomCode=${roomCode}&clientId=${encodeURIComponent(clientId)}`);
    esRef.current = es;
    es.addEventListener('state', (e) => {
      try { applyState(JSON.parse((e as MessageEvent).data)); } catch {}
    });
    es.onerror = () => { /* EventSource가 자동 재연결 */ };
    return () => { es.close(); if (esRef.current === es) esRef.current = null; };
  }, [roomCode, clientId, applyState]);

  const cid = () => encodeURIComponent(clientId);
  const rp = () => `roomCode=${roomCode}&clientId=${cid()}`;
  const saveNick = (n: string) => { try { localStorage.setItem(NICK_KEY, n); } catch {} };

  const post = useCallback(async (path: string, body?: unknown) => {
    setBusy(true); setError(null);
    try {
      const res = await api<HgState>(path, { method: 'POST', body: body ? JSON.stringify(body) : undefined });
      return res;
    } catch (e) {
      setError(e instanceof Error ? e.message : '오류가 발생했습니다');
      return null;
    } finally { setBusy(false); }
  }, []);

  const handleCreate = async () => {
    const n = nick.trim();
    if (!n) return setError('닉네임을 입력하세요');
    saveNick(n);
    setBusy(true); setError(null);
    try {
      const res = await api<{ roomCode: string; state: HgState }>(
        `/api/v1/halligalli/new?clientId=${cid()}`, { method: 'POST', body: JSON.stringify({ nick: n }) });
      changeRoom(res.roomCode); verRef.current = res.state.version; setSt(res.state); setShowCreate(false);
    } catch (e) { setError(e instanceof Error ? e.message : '방 생성 실패'); } finally { setBusy(false); }
  };
  const handleJoin = async () => {
    const n = nick.trim();
    if (!n) return setError('닉네임을 입력하세요');
    saveNick(n);
    const res = await post(`/api/v1/halligalli/join?${rp()}`, { nick: n });
    if (res) applyState(res);
  };
  const handleStart = async () => { const r = await post(`/api/v1/halligalli/start?${rp()}`); if (r) applyState(r); };
  const handleAddAi = async (level: string) => { const r = await post(`/api/v1/halligalli/add-ai?${rp()}&level=${level}`); if (r) applyState(r); };
  const handleRemoveAi = async () => { const r = await post(`/api/v1/halligalli/remove-ai?${rp()}`); if (r) applyState(r); };
  const handleFlip = async () => { const r = await post(`/api/v1/halligalli/flip?${rp()}`); if (r) applyState(r); };
  const handleRing = useCallback(async () => {
    const code = roomRef.current;
    if (!code) return;
    try {
      const r = await api<HgState>(`/api/v1/halligalli/ring?roomCode=${code}&clientId=${cid()}`, { method: 'POST' });
      applyState(r);
    } catch (e) { setError(e instanceof Error ? e.message : '오류'); }
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [applyState, clientId]);

  const handleAdminReset = async () => {
    const code = adminInput.trim();
    if (!code) return;
    const res = await post(`/api/v1/halligalli/reset?code=${encodeURIComponent(code)}`);
    if (res) { setShowAdmin(false); setAdminInput(''); changeRoom(null); setSt(null); }
  };
  const handleCloseRoom = async (rc: string) => {
    const code = adminInput.trim();
    if (!code) { setError('관리자 코드를 먼저 입력하세요'); return; }
    if (!confirm(`${rc} 방을 삭제할까요?`)) return;
    try {
      await api<boolean>(`/api/v1/halligalli/close-room?code=${encodeURIComponent(code)}&roomCode=${rc}`, { method: 'POST' });
      setRooms((cur) => cur.filter((r) => r.code !== rc));
    } catch (e) { setError(e instanceof Error ? e.message : '방 삭제 실패'); }
  };

  // 스페이스바 = 종
  useEffect(() => {
    const canRing = st?.status === 'PLAYING' && st.joined && st.players.find((p) => p.seat === st.seat)?.alive
      && st.players.some((p) => p.up > 0); // 공개된 카드가 있어야 종 가능
    const onKey = (e: KeyboardEvent) => {
      if (e.code === 'Space' && canRing) {
        e.preventDefault();
        handleRing();
        setFlash('🔔');
        setTimeout(() => setFlash(null), 200);
      }
    };
    window.addEventListener('keydown', onKey);
    return () => window.removeEventListener('keydown', onKey);
  }, [st, handleRing]);

  const adminFooter = (
    <div className="w-full mt-6 pt-4 border-t border-gray-100 flex flex-col items-center gap-2">
      {showAdmin ? (
        <>
          <div className="flex items-center gap-2">
            <input type="password" value={adminInput} onChange={(e) => setAdminInput(e.target.value)}
              onKeyDown={(e) => e.key === 'Enter' && handleAdminReset()} placeholder="관리자 코드"
              className="border border-gray-300 rounded-lg px-3 py-2 text-sm w-32 focus:outline-none focus:border-red-400" />
            <button onClick={handleAdminReset} className="bg-gray-700 text-white text-sm px-3 py-2 rounded-lg">전체 초기화</button>
          </div>
          <p className="text-[11px] text-gray-400">코드 입력 후 방 목록의 🗑 로 개별 방을 삭제할 수 있어요</p>
        </>
      ) : (
        <button onClick={() => setShowAdmin(true)} className="text-xs text-gray-300 hover:text-gray-500">🔒 관리자</button>
      )}
    </div>
  );

  const rulesHelp = (
    <div className="w-full rounded-xl border border-gray-200 p-4 text-sm space-y-2 text-left mt-3">
      <div className="flex items-center justify-between"><p className="font-bold">게임 방법</p>
        <button onClick={() => setShowRules(false)} className="text-xs text-gray-400">닫기 ✕</button></div>
      <p className="text-gray-600">🃏 차례가 오면 <b>내 카드 넘기기</b>로 맨 위 카드를 공개해요.</p>
      <p className="text-gray-600">🔔 공개된 카드 중 <b>같은 과일이 정확히 5개</b>가 되면 재빨리 <b>종</b>(스페이스바 또는 종 버튼)! 먼저 친 사람이 모든 공개카드를 가져가요.</p>
      <p className="text-gray-600">❌ 5개가 아닌데 종을 치면 <b>벌칙</b>: 다른 사람들에게 카드를 1장씩 줍니다.</p>
      <p className="text-gray-600">🏆 카드를 다 잃으면 탈락, <b>마지막까지 남으면 승리!</b></p>
      <p className="text-[12px] text-gray-400">※ 실시간 게임이라 각자 폰으로 접속해서 같이 하세요.</p>
    </div>
  );

  // ----- 방 목록 -----
  if (!roomCode) {
    return (
      <main className="min-h-screen flex flex-col items-center p-6 max-w-lg mx-auto w-full">
        <h1 className="text-2xl font-bold mb-6 self-start">🔔 할리갈리</h1>
        {showCreate ? (
          <div className="w-full space-y-4">
            <input value={nick} onChange={(e) => setNick(e.target.value)} maxLength={16} placeholder="내 닉네임"
              className="w-full border border-gray-300 rounded-lg px-3 py-2 focus:outline-none focus:border-hit" />
            <div className="flex gap-2">
              <button onClick={() => setShowCreate(false)} className="flex-1 border border-gray-300 py-3 rounded-lg">취소</button>
              <button onClick={handleCreate} disabled={busy} className="flex-[2] bg-hit text-white font-bold py-3 rounded-lg disabled:opacity-50">방 만들기</button>
            </div>
          </div>
        ) : (
          <div className="w-full space-y-4">
            <button onClick={() => { setShowCreate(true); setError(null); }} className="w-full bg-hit text-white font-bold py-3 rounded-lg hover:opacity-90">+ 새 방 만들기</button>
            <div className="text-center"><button onClick={() => setShowRules((v) => !v)} className="text-sm text-gray-400 underline">게임 방법 보기</button></div>
            {showRules && rulesHelp}
            <p className="text-sm font-bold text-gray-600">방 목록</p>
            {rooms.length === 0 && <p className="text-gray-400 text-sm text-center py-6">아직 만들어진 방이 없어요.</p>}
            {rooms.map((r) => {
              const badge = r.status === 'WAITING' ? '모집중' : r.status === 'PLAYING' ? '진행중' : '종료';
              const cls = r.status === 'WAITING' ? 'bg-green-100 text-green-700' : r.status === 'PLAYING' ? 'bg-yellow-100 text-yellow-700' : 'bg-gray-100 text-gray-400';
              return (
                <div key={r.code} className="flex items-center justify-between border border-gray-200 rounded-lg px-4 py-3">
                  <div><span className="font-bold tracking-wider">{r.code}</span><span className="text-xs text-gray-400 ml-2">{r.host} · {r.playerCount}명</span></div>
                  <div className="flex items-center gap-2">
                    <span className={`text-xs px-2 py-1 rounded-full ${cls}`}>{badge}</span>
                    {r.status === 'ENDED'
                      ? <span className="text-sm text-gray-300">종료</span>
                      : <button onClick={() => { changeRoom(r.code); setSt(null); }} className="text-sm font-bold text-hit">{r.status === 'WAITING' ? '참가' : '이어하기'}</button>}
                    {showAdmin && <button onClick={() => handleCloseRoom(r.code)} title="방 삭제" className="text-sm text-red-500 hover:text-red-600">🗑</button>}
                  </div>
                </div>
              );
            })}
          </div>
        )}
        {error && <p className="text-red-500 text-sm mt-4 text-center">{error}</p>}
        {adminFooter}
      </main>
    );
  }

  if (!st) return <main className="min-h-screen flex items-center justify-center"><p className="text-gray-400">불러오는 중...</p></main>;

  const meView = st.players.find((p) => p.seat === st.seat);
  const iAmAlive = !!meView?.alive;
  const anyFaceUp = st.players.some((p) => p.up > 0); // 공개된 카드가 있어야 종 가능

  return (
    <main className="min-h-screen flex flex-col items-center p-4 max-w-2xl mx-auto w-full">
      <div className="w-full flex items-center justify-between gap-2 flex-wrap mb-3">
        <div className="flex items-center gap-2 min-w-0">
          <h1 className="text-lg sm:text-xl font-bold shrink-0">🔔 할리갈리</h1>
          <span className="text-xs bg-gray-100 rounded px-2 py-1 tracking-wider font-bold shrink-0">{roomCode}</span>
          <button onClick={() => { api(`/api/v1/halligalli/leave?roomCode=${roomCode}&clientId=${encodeURIComponent(clientId)}`, { method: 'POST' }).catch(() => {}); changeRoom(null); setSt(null); }} className="text-xs text-gray-400 underline shrink-0">나가기</button>
        </div>
      </div>

      {st.status === 'LOBBY' && (
        <div className="w-full space-y-4 mt-2">
          <div className="rounded-xl border border-gray-200 p-4">
            <p className="text-sm font-bold mb-2">참가자 ({st.playerCount}/6)</p>
            <div className="flex flex-wrap gap-2">
              {st.players.map((p) => (
                <span key={p.seat} className={`bg-gray-100 rounded-full px-3 py-1 text-sm ${p.seat === st.seat ? 'ring-2 ring-hit font-bold' : ''}`}>{p.nick}{p.seat === st.seat && ' (나)'}</span>
              ))}
            </div>
          </div>
          <div className="text-center"><button onClick={() => setShowRules((v) => !v)} className="text-sm text-gray-400 underline">게임 방법 보기</button></div>
          {showRules && rulesHelp}
          {!st.joined ? (
            <div className="flex gap-2">
              <input value={nick} onChange={(e) => setNick(e.target.value)} maxLength={16} placeholder="닉네임"
                className="flex-1 border border-gray-300 rounded-lg px-3 py-2 focus:outline-none focus:border-hit" />
              <button onClick={handleJoin} disabled={busy} className="bg-hit text-white font-bold px-5 rounded-lg disabled:opacity-50">참가</button>
            </div>
          ) : st.isHost ? (
            <div className="space-y-3">
              <div className="rounded-xl border border-amber-200 bg-amber-50/60 p-3">
                <div className="flex items-center justify-between mb-2">
                  <p className="text-sm font-bold text-amber-800">🤖 AI 봇 추가</p>
                  <button onClick={handleRemoveAi} disabled={busy || !st.players.some((p) => p.nick.startsWith('🤖'))}
                    className="text-xs text-gray-500 underline disabled:opacity-30">AI 제거</button>
                </div>
                <div className="grid grid-cols-4 gap-2">
                  {[['EASY', '초급', 'bg-emerald-400'], ['NORMAL', '중급', 'bg-amber-400'], ['HARD', '고급', 'bg-rose-400'], ['EXTREME', '초월', 'bg-purple-600']].map(([lv, label, cls]) => (
                    <button key={lv} onClick={() => handleAddAi(lv)} disabled={busy || st.playerCount >= 6}
                      className={`${cls} text-white font-bold py-2 rounded-lg text-sm shadow-[0_2px_0_rgba(0,0,0,0.15)] active:translate-y-0.5 disabled:opacity-40`}>
                      {label}
                    </button>
                  ))}
                </div>
                <p className="text-[11px] text-gray-400 mt-2 text-center">혼자서도 봇과 연습 · 초월은 사람이 이기기 매우 어려워요</p>
              </div>
              <button onClick={handleStart} disabled={busy || st.playerCount < 2} className="w-full bg-hit text-white font-bold py-3 rounded-lg disabled:opacity-40">
                {st.playerCount >= 2 ? '게임 시작' : '최소 2명 필요 (AI 추가 가능)'}
              </button>
            </div>
          ) : <p className="text-center text-gray-500 text-sm">방장이 시작하기를 기다리는 중...</p>}
        </div>
      )}

      {(st.status === 'PLAYING' || st.status === 'ENDED') && (
        <div className="w-full space-y-3">
          {st.lastAction && <p className="text-sm text-center font-medium text-gray-600">{st.lastAction}</p>}

          {/* 플레이어들의 공개 카드 */}
          <div className="grid grid-cols-2 sm:grid-cols-3 gap-3">
            {st.players.map((p) => {
              const isTurn = p.seat === st.currentSeat && st.status === 'PLAYING';
              const f = p.topFruit ? FRUIT[p.topFruit] : null;
              return (
                <div key={p.seat} className={`rounded-2xl border-2 p-3 flex flex-col items-center gap-2 ${isTurn ? 'border-hit bg-hit/5' : 'border-gray-200'} ${!p.alive ? 'opacity-40' : ''}`}>
                  <div className="flex items-center gap-1 text-sm font-bold">
                    <span className={p.seat === st.seat ? 'text-hit' : 'text-gray-700'}>{p.nick}{p.seat === st.seat && '(나)'}</span>
                    {isTurn && <span className="text-[10px] bg-hit text-white rounded-full px-1.5">차례</span>}
                    {!p.alive && <span className="text-[10px] text-gray-400">탈락</span>}
                  </div>
                  <div className={`w-full h-20 rounded-xl flex items-center justify-center flex-wrap gap-0.5 ${f ? f.bg : 'bg-gray-50'}`}>
                    {f
                      ? Array.from({ length: p.topCount }).map((_, i) => <span key={i} className="text-2xl leading-none">{f.emoji}</span>)
                      : <span className="text-gray-300 text-xs">공개 전</span>}
                  </div>
                  <div className="text-[11px] text-gray-400">더미 {p.down} · 공개 {p.up}</div>
                </div>
              );
            })}
          </div>

          {/* 종료 */}
          {st.status === 'ENDED' && (
            <div className="text-center space-y-3 py-2">
              <p className="text-2xl font-extrabold text-hit">🎉 {st.winnerNick} 승리!</p>
              <button onClick={() => { setShowCreate(true); changeRoom(null); setSt(null); }} className="w-full bg-hit text-white font-bold py-3 rounded-lg">🔄 새 방 만들기</button>
            </div>
          )}

          {/* 액션 */}
          {st.status === 'PLAYING' && (
            <div className="space-y-3 pt-1">
              <button
                onClick={handleFlip}
                disabled={busy || !st.isMyTurn || !iAmAlive}
                className="w-full py-4 rounded-2xl font-bold text-lg border-2 border-gray-300 bg-white disabled:opacity-40 active:translate-y-0.5">
                {st.isMyTurn ? '🃏 내 카드 넘기기' : `${st.players[st.currentSeat - 1]?.nick ?? ''}님 차례`}
              </button>
              <button
                onClick={() => { if (!anyFaceUp) return; handleRing(); setFlash('🔔'); setTimeout(() => setFlash(null), 200); }}
                disabled={!iAmAlive || !anyFaceUp}
                className="w-full py-8 rounded-full font-extrabold text-2xl text-white bg-gradient-to-b from-amber-400 to-amber-600 shadow-[0_5px_0_rgba(180,120,0,0.6)] active:translate-y-1 active:shadow-[0_2px_0_rgba(180,120,0,0.6)] disabled:opacity-40 select-none">
                {flash ? '🔔🔔🔔' : '🔔 종 치기 (스페이스바)'}
              </button>
              {!iAmAlive && <p className="text-center text-gray-400 text-sm">탈락했어요. 다음 게임을 기다려주세요.</p>}
            </div>
          )}
          {error && <p className="text-red-500 text-sm text-center">{error}</p>}
        </div>
      )}

      {adminFooter}
    </main>
  );
}
