'use client';

import Link from 'next/link';
import { useCallback, useEffect, useRef, useState } from 'react';
import { api } from '@/lib/api';
import RoomChat from '@/components/RoomChat';

type PlayerView = { seat: number; name: string; bot: boolean; host: boolean; me: boolean; left: boolean; eliminated: boolean; pawnsLeft: number; crossed: number; bridgePos: number };
type Reveal = { seat: number; challengerSeat: number; declared: number; actual: number; lie: boolean };
type State = {
  phase: string; turnPhase: string | null; bridgeLen: number; pawnsPer: number; goal: number; challengeSec: number;
  isHost: boolean; joined: boolean; players: PlayerView[];
  turnSeat: number; turnName: string | null; myTurn: boolean; mySeat: number;
  declared: number; myRoll: number; canChallenge: boolean; lastReveal: Reveal | null;
  lastAction: string | null; log: string[]; winnerSeat: number; winnerLabel: string | null;
  deadline: number; serverNow: number;
};
type Room = { code: string; status: string; playerCount: number; host: string };

function cid(): string {
  if (typeof window === 'undefined') return '';
  let id = localStorage.getItem('ciao_client_id');
  if (!id) { id = Math.random().toString(36).slice(2) + Date.now().toString(36); localStorage.setItem('ciao_client_id', id); }
  return id;
}

const SEAT_COLORS = ['#f43f5e', '#3b82f6', '#10b981', '#f59e0b', '#8b5cf6', '#ec4899', '#14b8a6', '#f97316', '#6366f1', '#84cc16'];

/** 주사위 면. v: 1~4 눈금, 0 = X(빨강). hidden이면 ? 표시. */
function DiceFace({ v, size = 48, hidden = false }: { v: number; size?: number; hidden?: boolean }) {
  const pips: Record<number, [number, number][]> = {
    1: [[50, 50]],
    2: [[28, 28], [72, 72]],
    3: [[26, 26], [50, 50], [74, 74]],
    4: [[28, 28], [72, 28], [28, 72], [72, 72]],
  };
  return (
    <span className="relative inline-block rounded-2xl border-2 border-slate-300 bg-white shadow-[inset_0_-3px_0_rgba(0,0,0,0.08),0_2px_6px_rgba(0,0,0,0.15)] align-middle"
      style={{ width: size, height: size }}>
      {hidden ? (
        <span className="absolute inset-0 flex items-center justify-center font-extrabold text-slate-300" style={{ fontSize: size * 0.52 }}>?</span>
      ) : v === 0 ? (
        <span className="absolute inset-0 flex items-center justify-center font-extrabold text-rose-500" style={{ fontSize: size * 0.5 }}>✕</span>
      ) : (
        (pips[v] || []).map(([x, y], i) => (
          <span key={i} className="absolute rounded-full bg-slate-800"
            style={{ width: size * 0.17, height: size * 0.17, left: `${x}%`, top: `${y}%`, transform: 'translate(-50%,-50%)' }} />
        ))
      )}
    </span>
  );
}

export default function CiaoPage() {
  const id = useRef('');
  const [nick, setNick] = useState('');
  const [screen, setScreen] = useState<'entry' | 'lobby' | 'game'>('entry');
  const [roomCode, setRoomCode] = useState<string | null>(null);
  const [challengeSec, setChallengeSec] = useState(8);
  const [joinCode, setJoinCode] = useState('');
  const [rooms, setRooms] = useState<Room[]>([]);
  const [ss, setSs] = useState<State | null>(null);
  const [botLevel, setBotLevel] = useState('NORMAL');
  const roomRef = useRef<string | null>(null); roomRef.current = roomCode;
  const syncRef = useRef({ serverNow: 0, at: 0 });
  const [, forceTick] = useState(0);

  useEffect(() => { id.current = cid(); try { setNick(localStorage.getItem('arcade_nick') || ''); } catch {} }, []);

  const loadRooms = useCallback(async () => { try { setRooms(await api(`/api/v1/ciao/rooms`)); } catch {} }, []);
  useEffect(() => { if (screen === 'entry') { loadRooms(); const t = setInterval(loadRooms, 3000); return () => clearInterval(t); } }, [screen, loadRooms]);

  // 폴링
  useEffect(() => {
    if (screen === 'entry' || !roomCode) return;
    let alive = true;
    const poll = async () => {
      try {
        const s = await api<State>(`/api/v1/ciao/me?roomCode=${roomCode}&clientId=${id.current}`);
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

  // 카운트다운을 부드럽게 (250ms 로컬 틱)
  useEffect(() => {
    if (screen !== 'game') return;
    const t = setInterval(() => forceTick((x) => x + 1), 250);
    return () => clearInterval(t);
  }, [screen]);

  const create = async () => {
    const n = nick.trim(); if (!n) return; try { localStorage.setItem('arcade_nick', n); } catch {}
    try {
      const res = await api<{ roomCode: string; state: State }>(`/api/v1/ciao/new?clientId=${id.current}`, { method: 'POST', body: JSON.stringify({ nick: n, challengeSec }) });
      setRoomCode(res.roomCode); setSs(res.state); setScreen('lobby');
    } catch (e: any) { alert(e?.message || '방 생성 실패'); }
  };
  const join = async (code: string, nickOverride?: string) => {
    const n = (nickOverride ?? nick).trim(); if (!n || !code) return; setNick(n); try { localStorage.setItem('arcade_nick', n); } catch {}
    try {
      const s = await api<State>(`/api/v1/ciao/join?roomCode=${code}&clientId=${id.current}`, { method: 'POST', body: JSON.stringify({ nick: n }) });
      setRoomCode(code.toUpperCase()); setSs(s); setScreen(s.phase === 'LOBBY' ? 'lobby' : 'game');
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
    try { window.history.replaceState({}, '', '/ciao'); } catch {}
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, []);

  const addBot = async () => { try { setSs(await api(`/api/v1/ciao/add-bot?roomCode=${roomCode}&clientId=${id.current}&level=${botLevel}`, { method: 'POST', body: '{}' })); } catch (e: any) { alert(e?.message); } };
  const startMatch = async () => { try { setSs(await api(`/api/v1/ciao/start?roomCode=${roomCode}&clientId=${id.current}`, { method: 'POST', body: '{}' })); setScreen('game'); } catch (e: any) { alert(e?.message); } };
  const roll = async () => { try { setSs(await api(`/api/v1/ciao/roll?roomCode=${roomCode}&clientId=${id.current}`, { method: 'POST', body: '{}' })); } catch (e: any) { alert(e?.message); } };
  const declareVal = async (v: number) => { try { setSs(await api(`/api/v1/ciao/declare?roomCode=${roomCode}&clientId=${id.current}&value=${v}`, { method: 'POST', body: '{}' })); } catch (e: any) { alert(e?.message); } };
  const challenge = async () => { try { setSs(await api(`/api/v1/ciao/challenge?roomCode=${roomCode}&clientId=${id.current}`, { method: 'POST', body: '{}' })); } catch (e: any) { alert(e?.message); } };
  const leave = async () => { const rc = roomRef.current; if (rc) { try { await api(`/api/v1/ciao/leave?roomCode=${rc}&clientId=${id.current}`, { method: 'POST', body: '{}' }); } catch {} } setScreen('entry'); setRoomCode(null); setSs(null); };

  const home = <Link href="/" aria-label="홈으로" className="text-xl leading-none text-slate-500 hover:text-slate-800">🏠</Link>;
  const ended = ss?.phase === 'ENDED';

  // 서버 시각 보간 → 부드러운 남은 시간
  const localNow = syncRef.current.at ? syncRef.current.serverNow + (Date.now() - syncRef.current.at) : (ss?.serverNow ?? 0);
  const remainMs = ss && ss.deadline > 0 ? Math.max(0, ss.deadline - localNow) : 0;
  const remainSec = Math.ceil(remainMs / 1000);
  const challengePct = ss && ss.turnPhase === 'CHALLENGE' ? Math.min(100, (remainMs / (ss.challengeSec * 1000)) * 100) : 0;

  const activePlayers = ss ? ss.players.filter((p) => !p.left) : [];
  const seatName = (seat: number) => activePlayers.find((p) => p.seat === seat)?.name ?? '?';
  const pawnsAt = (pos: number) => activePlayers.filter((p) => !p.eliminated && p.bridgePos === pos && (pos > 0 || p.pawnsLeft > 0));

  const Pawn = ({ p, big }: { p: PlayerView; big?: boolean }) => (
    <span title={p.name}
      className={`rounded-full font-extrabold text-white flex items-center justify-center border-2 border-white shadow-md ${big ? 'w-6 h-6 text-[11px] sm:w-8 sm:h-8 sm:text-sm' : 'w-5 h-5 text-[10px]'}`}
      style={{ background: SEAT_COLORS[p.seat % SEAT_COLORS.length], animation: !ended && p.seat === ss?.turnSeat ? 'ciao-float 1.1s ease-in-out infinite' : undefined }}>
      {p.name.slice(0, 1)}
    </span>
  );

  return (
    <main className="min-h-screen flex flex-col items-center p-3 sm:p-5 max-w-6xl mx-auto w-full text-slate-800">
      <style>{`
        @keyframes ciao-float { 0%,100% { transform: translateY(0) } 50% { transform: translateY(-5px) } }
        @keyframes ciao-pop { 0% { transform: scale(.85); opacity: 0 } 100% { transform: scale(1); opacity: 1 } }
        @keyframes ciao-shake { 0%,100% { transform: rotate(0) } 25% { transform: rotate(-14deg) } 75% { transform: rotate(14deg) } }
        @keyframes ciao-drift { 0% { transform: translateX(0) } 50% { transform: translateX(10px) } 100% { transform: translateX(0) } }
      `}</style>

      <div className="w-full flex items-center justify-between mb-3 sm:mb-4">
        <div className="flex items-center gap-2 sm:gap-3">
          {home}
          <div>
            <h1 className="text-xl sm:text-3xl font-extrabold tracking-tight">🤥 차오차오</h1>
            <p className="hidden sm:block text-xs text-slate-400 -mt-0.5">구름다리 블러핑 레이스 — 뻥일까, 진실일까?</p>
          </div>
        </div>
        {roomCode && (
          <div className="flex items-center gap-2">
            <span className="hidden sm:inline text-xs font-bold text-slate-400">방 {roomCode}</span>
            <button onClick={leave} className="text-xs px-3 py-1.5 rounded-lg bg-slate-700 text-slate-100 font-bold">나가기</button>
          </div>
        )}
      </div>

      {/* 입장 화면 */}
      {screen === 'entry' && (
        <div className="w-full max-w-md space-y-4">
          <div className="rounded-2xl overflow-hidden border-2 border-sky-200">
            <div className="relative bg-gradient-to-b from-sky-300 via-sky-100 to-emerald-100 px-4 py-5 text-center">
              <span className="absolute top-2 left-4 text-xl" style={{ animation: 'ciao-drift 5s ease-in-out infinite' }}>⛅</span>
              <span className="absolute top-3 right-6 text-sm" style={{ animation: 'ciao-drift 7s ease-in-out infinite' }}>☁️</span>
              <p className="text-3xl mb-1">🎲🤥🌉</p>
              <p className="text-sm font-bold text-slate-600">주사위를 숨겨 굴리고, 뻥으로 다리를 건너라!</p>
            </div>
          </div>
          <input value={nick} onChange={(e) => setNick(e.target.value)} maxLength={16} placeholder="닉네임"
            className="w-full border-2 border-slate-200 rounded-xl px-4 py-3 focus:outline-none focus:border-amber-500" />
          <div className="rounded-2xl border-2 border-slate-200 p-4 space-y-3">
            <p className="text-sm font-bold">🆕 방 만들기</p>
            <label className="flex items-center justify-between text-sm">
              <span className="text-slate-500">의심 대기 시간</span>
              <select value={challengeSec} onChange={(e) => setChallengeSec(Number(e.target.value))} className="border-2 border-slate-200 rounded-lg px-2 py-1.5 font-bold">
                <option value={5}>5초 (스피드)</option>
                <option value={8}>8초 (기본)</option>
                <option value={12}>12초 (여유)</option>
                <option value={20}>20초 (느긋)</option>
              </select>
            </label>
            <button onClick={create} disabled={!nick.trim()}
              className="w-full bg-gradient-to-b from-amber-500 to-amber-600 text-white font-extrabold py-3 rounded-xl shadow-md disabled:opacity-40 active:scale-[0.98] transition">방 만들기</button>
          </div>
          <div className="flex gap-2">
            <input value={joinCode} onChange={(e) => setJoinCode(e.target.value.toUpperCase())} maxLength={4} placeholder="코드"
              className="w-28 border-2 border-slate-200 rounded-xl px-3 py-2.5 uppercase tracking-widest font-extrabold text-center focus:outline-none focus:border-amber-500" />
            <button onClick={() => join(joinCode)} disabled={!nick.trim() || joinCode.length < 4}
              className="flex-1 bg-slate-700 text-white font-bold rounded-xl disabled:opacity-40">코드로 참가</button>
          </div>
          {rooms.length > 0 && (
            <div className="rounded-2xl border-2 border-slate-200 p-3 space-y-1.5">
              <p className="text-sm font-bold px-1">🏠 열린 방</p>
              {rooms.map((r) => (
                <button key={r.code} onClick={() => join(r.code)} disabled={!nick.trim() || r.status !== 'WAITING'}
                  className="w-full flex justify-between items-center text-sm px-3 py-2 rounded-xl border-2 border-slate-100 disabled:opacity-40 hover:border-amber-400 transition">
                  <span className="font-extrabold">{r.code} <span className="font-normal text-slate-500">· {r.host}</span></span>
                  <span className="text-slate-400">{r.status === 'WAITING' ? '모집중' : r.status === 'PLAYING' ? '진행중' : '종료'} · {r.playerCount}명</span>
                </button>
              ))}
            </div>
          )}
          <details className="rounded-2xl border-2 border-slate-200 p-4 text-sm text-slate-600">
            <summary className="font-bold cursor-pointer">📖 게임 방법 (2~10인)</summary>
            <div className="mt-2 space-y-1">
              <p>· 내 차례에 통 속에 주사위(1~4와 X 2면)를 굴려 <b>나만 확인</b>하고 숫자를 선언해요.</p>
              <p>· <b>거짓말해도 됩니다!</b> 단, X가 나오면 반드시 거짓말(1~4 중 하나)을 해야 해요.</p>
              <p>· 다른 사람은 선언을 <b>의심</b>할 수 있어요(선착 1명).</p>
              <p>· 거짓이면 → 선언자 말 추락 💦 + 의심자가 선언 값만큼 전진!</p>
              <p>· 진실이면 → 의심자 말 추락 💦 + 선언자가 실제 값만큼 전진!</p>
              <p>· 아무도 의심 안 하면 선언 값 그대로 전진 (뻥이 통한 것!).</p>
              <p>· 다리(10칸) 끝을 넘어가면 1개 건넘. <b>목표 수만큼 먼저 건너면 승리!</b></p>
              <p>· 말이 다 떨어지면 탈락. 인원에 따라 말·목표가 자동 조정돼요(2~4인은 원작: 말7·3건넘).</p>
            </div>
          </details>
        </div>
      )}

      {/* 로비 */}
      {screen === 'lobby' && ss && (
        <div className="w-full max-w-md mx-auto space-y-4">
          <div className="text-center rounded-2xl border-2 border-amber-200 bg-gradient-to-b from-amber-50 to-white py-4">
            <p className="text-sm text-slate-400">방 코드</p>
            <p className="text-4xl font-extrabold tracking-[0.3em] text-amber-600 pl-2">{roomCode}</p>
            <p className="text-xs text-slate-400 mt-1">의심 대기 {ss.challengeSec}초 · {ss.players.length}/10명</p>
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
            {(() => { const n = ss.players.length; const pw = n <= 4 ? 7 : n <= 6 ? 6 : n <= 8 ? 5 : 4; const gl = n <= 6 ? 3 : 2; return `현재 ${n}인 기준 → 🧍 말 ${pw}개 · 🏁 ${gl}개 건너면 승리`; })()}
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
                className="w-full bg-gradient-to-b from-amber-500 to-amber-600 text-white font-extrabold py-3.5 rounded-xl shadow-md disabled:opacity-40 active:scale-[0.98] transition">시작하기</button>
            </div>
          ) : <p className="text-center text-sm text-slate-400">방장이 시작하기를 기다리는 중…</p>}
        </div>
      )}

      {/* 게임 */}
      {screen === 'game' && ss && (
        <div className="w-full grid grid-cols-1 lg:grid-cols-[1fr_300px] gap-3 sm:gap-4 items-start">
          {/* ── 왼쪽: 다리 씬 + 액션 ── */}
          <div className="space-y-3 sm:space-y-4 min-w-0">
            <div className="flex items-center justify-between text-xs sm:text-sm">
              <span className="px-3 py-1.5 rounded-full bg-amber-100 text-amber-800 font-bold">🧍 말 {ss.pawnsPer}개 · 🏁 {ss.goal}개 건너면 승리</span>
              <span className="font-extrabold text-amber-600">
                {ended ? '게임 종료'
                  : ss.myTurn
                    ? (ss.turnPhase === 'ROLL' ? '내 차례! 주사위를 굴리세요' : ss.turnPhase === 'DECLARE' ? '숫자를 선언하세요!' : `의심 받는 중… ${remainSec}초`)
                    : ss.turnPhase === 'CHALLENGE' ? `🔍 의심 기회! ${remainSec}초` : `${ss.turnName ?? ''}님 차례…`}
              </span>
            </div>

            {/* 구름다리 씬 */}
            <div className="relative rounded-3xl overflow-hidden border-2 border-sky-200 shadow-lg">
              <div className="absolute inset-0 bg-gradient-to-b from-sky-300 via-sky-100 to-emerald-100" />
              <span className="absolute top-3 left-6 text-2xl select-none" style={{ animation: 'ciao-drift 6s ease-in-out infinite' }}>⛅</span>
              <span className="absolute top-8 right-10 text-lg select-none" style={{ animation: 'ciao-drift 8s ease-in-out infinite' }}>☁️</span>
              <span className="absolute top-2 right-1/3 text-sm select-none">🕊️</span>
              {/* 물 */}
              <div className="absolute bottom-0 inset-x-0 h-9 sm:h-12 bg-gradient-to-t from-sky-500/60 to-sky-300/0" />
              <div className="absolute bottom-1 inset-x-0 text-center text-sky-600/70 text-xs sm:text-sm tracking-[0.5em] select-none">〜〜〜〜〜〜〜〜</div>

              <div className="relative pt-8 sm:pt-10 pb-10 sm:pb-14 overflow-x-auto">
                <div className="flex items-end gap-1 sm:gap-1.5 min-w-[640px] px-3 sm:px-5">
                  {/* 출발 절벽 */}
                  <div className="min-w-[60px] sm:min-w-[72px] flex-none flex flex-col items-center justify-end gap-1.5">
                    <div className="flex flex-wrap justify-center gap-0.5 items-end min-h-[28px] sm:min-h-[36px]">
                      {pawnsAt(0).map((p) => <Pawn key={p.seat} p={p} big />)}
                    </div>
                    <div className="w-full rounded-t-xl border-b-0 bg-gradient-to-b from-emerald-400 to-emerald-600 text-center py-2.5 sm:py-4 shadow-md">
                      <span className="text-[11px] sm:text-sm font-extrabold text-emerald-950/70">🌿 출발</span>
                    </div>
                  </div>
                  {/* 다리 판자 */}
                  {Array.from({ length: ss.bridgeLen }, (_, i) => i + 1).map((pos) => (
                    <div key={pos} className="flex-1 min-w-[42px] sm:min-w-[50px] flex flex-col items-center justify-end gap-1.5">
                      <div className="flex flex-wrap justify-center gap-0.5 items-end min-h-[28px] sm:min-h-[36px]">
                        {pawnsAt(pos).map((p) => <Pawn key={p.seat} p={p} big />)}
                      </div>
                      <div className="w-full rounded-lg bg-gradient-to-b from-amber-400 to-amber-600 border-b-4 border-amber-800/60 text-center py-1.5 sm:py-2.5 shadow"
                        style={{ transform: pos % 2 ? 'rotate(-1.2deg)' : 'rotate(1.2deg)' }}>
                        <span className="text-[11px] sm:text-sm font-extrabold text-amber-950/60">{pos}</span>
                      </div>
                    </div>
                  ))}
                  {/* 도착 절벽 */}
                  <div className="min-w-[60px] sm:min-w-[72px] flex-none flex flex-col items-center justify-end gap-1.5">
                    <div className="flex flex-col items-center gap-0.5 min-h-[28px] sm:min-h-[36px] justify-end">
                      {activePlayers.filter((p) => p.crossed > 0).map((p) => (
                        <span key={p.seat} className="text-[10px] sm:text-xs font-extrabold text-white rounded-full px-1.5 sm:px-2 py-px shadow border border-white/60"
                          style={{ background: SEAT_COLORS[p.seat % SEAT_COLORS.length] }}>
                          {p.name.slice(0, 3)} ×{p.crossed}
                        </span>
                      ))}
                    </div>
                    <div className="w-full rounded-t-xl bg-gradient-to-b from-yellow-300 to-amber-500 text-center py-2.5 sm:py-4 shadow-md">
                      <span className="text-[11px] sm:text-sm font-extrabold text-amber-950/70">도착 🏁</span>
                    </div>
                  </div>
                </div>
              </div>
            </div>

            {/* 의심 판정 공개 */}
            {ss.lastReveal && !ended && (
              <div key={`${ss.lastReveal.seat}-${ss.lastReveal.challengerSeat}-${ss.lastReveal.declared}-${ss.lastReveal.actual}`}
                className={`flex flex-wrap items-center justify-center gap-x-3 gap-y-1.5 rounded-2xl border-2 px-4 py-2.5 font-bold text-sm sm:text-base ${ss.lastReveal.lie ? 'border-rose-300 bg-rose-50 text-rose-700' : 'border-emerald-300 bg-emerald-50 text-emerald-700'}`}
                style={{ animation: 'ciao-pop .3s ease' }}>
                <span>🔍 {seatName(ss.lastReveal.challengerSeat)} ▸ {seatName(ss.lastReveal.seat)}</span>
                <span className="flex items-center gap-1.5">선언 <DiceFace v={ss.lastReveal.declared} size={30} /> vs 실제 <DiceFace v={ss.lastReveal.actual} size={30} /></span>
                <span className="text-base sm:text-lg">{ss.lastReveal.lie ? '거짓말! 💦' : '진실!'}</span>
              </div>
            )}

            {/* 종료 배너 */}
            {ended && (
              <div className="text-center rounded-3xl border-2 border-amber-400 bg-gradient-to-b from-amber-50 to-yellow-50 px-6 py-6 shadow" style={{ animation: 'ciao-pop .35s ease' }}>
                <p className="text-3xl mb-1">🎉🏆🎉</p>
                <p className="text-xl sm:text-2xl font-extrabold">{ss.winnerLabel} 승리!</p>
                <p className="text-xs text-slate-400 mt-2">새 게임은 방을 새로 만들어주세요</p>
              </div>
            )}

            {/* 내 차례: 굴리기 */}
            {!ended && ss.myTurn && ss.turnPhase === 'ROLL' && (
              <button onClick={roll}
                className="w-full bg-gradient-to-b from-amber-500 to-amber-600 text-white font-extrabold text-lg sm:text-xl py-4 sm:py-5 rounded-2xl shadow-lg active:scale-[0.98] transition flex items-center justify-center gap-2">
                <span className="text-2xl inline-block" style={{ animation: 'ciao-shake 0.9s ease-in-out infinite' }}>🎲</span>
                통 속에 주사위 굴리기
              </button>
            )}

            {/* 내 차례: 선언 */}
            {!ended && ss.myTurn && ss.turnPhase === 'DECLARE' && (
              <div className="rounded-2xl border-2 border-amber-400 bg-gradient-to-b from-amber-50/70 to-white p-4 sm:p-5 space-y-3 shadow" style={{ animation: 'ciao-pop .25s ease' }}>
                <div className="flex items-center justify-center gap-3">
                  <DiceFace v={ss.myRoll} size={56} />
                  <div>
                    <p className="font-extrabold text-base sm:text-lg">{ss.myRoll === 0 ? 'X가 나왔어요 — 반드시 거짓말!' : `「${ss.myRoll}」 나왔어요`}</p>
                    <p className="text-xs text-slate-400">🤫 이 결과는 나만 볼 수 있어요</p>
                  </div>
                </div>
                <p className="text-center text-xs sm:text-sm text-slate-500 font-bold">
                  {ss.myRoll === 0 ? '1~4 중 아무 숫자나 선언하세요 (전부 거짓말!)' : '진실을 말해도, 뻥을 쳐도 됩니다 — 몇을 선언할까요?'}
                </p>
                <div className="grid grid-cols-4 gap-2 sm:gap-3">
                  {[1, 2, 3, 4].map((v) => (
                    <button key={v} onClick={() => declareVal(v)}
                      className={`flex flex-col items-center gap-1 py-3 sm:py-4 rounded-2xl border-2 bg-white active:scale-95 transition hover:-translate-y-0.5 hover:shadow-md ${v === ss.myRoll ? 'border-emerald-400' : 'border-slate-200 hover:border-amber-400'}`}>
                      <DiceFace v={v} size={40} />
                      <span className={`text-[10px] font-extrabold leading-none ${v === ss.myRoll ? 'text-emerald-600' : 'text-transparent'}`}>진실</span>
                    </button>
                  ))}
                </div>
              </div>
            )}

            {/* 내 차례: 의심 대기 */}
            {!ended && ss.myTurn && ss.turnPhase === 'CHALLENGE' && (
              <div className="rounded-2xl border-2 border-amber-300 bg-white p-4 text-center space-y-2 shadow">
                <p className="font-extrabold flex items-center justify-center gap-2">「{ss.declared}」 선언 완료 <DiceFace v={ss.declared} size={30} /></p>
                <div className="h-2 bg-slate-100 rounded-full overflow-hidden">
                  <div className="h-full bg-amber-500 transition-[width] duration-200 ease-linear rounded-full" style={{ width: `${challengePct}%` }} />
                </div>
                <p className="text-sm text-slate-400">아무도 의심 안 하면 {ss.declared}칸 전진! ({remainSec}초)</p>
              </div>
            )}

            {/* 남의 차례: 의심 기회 */}
            {!ended && ss.turnPhase === 'CHALLENGE' && !ss.myTurn && (
              <div className="rounded-2xl border-2 border-rose-300 bg-gradient-to-b from-rose-50/60 to-white p-4 sm:p-5 space-y-3 shadow" style={{ animation: 'ciao-pop .25s ease' }}>
                <div className="flex items-center justify-center gap-3">
                  <DiceFace v={0} size={48} hidden />
                  <p className="font-extrabold text-lg sm:text-xl">{ss.turnName} ▸ 「{ss.declared}」 선언!</p>
                  <DiceFace v={ss.declared} size={48} />
                </div>
                <div className="h-2 bg-slate-100 rounded-full overflow-hidden">
                  <div className="h-full bg-rose-500 transition-[width] duration-200 ease-linear rounded-full" style={{ width: `${challengePct}%` }} />
                </div>
                {ss.canChallenge ? (
                  <button onClick={challenge}
                    className="w-full bg-gradient-to-b from-rose-500 to-rose-600 text-white font-extrabold text-base sm:text-lg py-3.5 rounded-2xl shadow-lg active:scale-[0.98] transition animate-pulse">
                    🔍 의심하기! ({remainSec}초)
                  </button>
                ) : <p className="text-center text-sm text-slate-400">의심 창 진행 중… ({remainSec}초)</p>}
                <p className="text-center text-[11px] sm:text-xs text-slate-400">의심 성공: 상대 말 추락 💦 + 내가 {ss.declared}칸 전진 · 실패: 내 말 추락</p>
              </div>
            )}

            {/* 남의 차례: 대기 */}
            {!ended && !ss.myTurn && ss.turnPhase !== 'CHALLENGE' && (
              <div className="rounded-2xl border-2 border-slate-200 bg-white p-4 text-center text-sm text-slate-400 font-bold">
                <span className="inline-block mr-1" style={{ animation: 'ciao-shake 1.2s ease-in-out infinite' }}>🎲</span>
                {ss.turnName}님이 {ss.turnPhase === 'ROLL' ? '주사위를 굴리는 중' : '선언을 고민하는 중'}…
              </div>
            )}
          </div>

          {/* ── 오른쪽: 플레이어 + 로그 ── */}
          <div className="space-y-3 sm:space-y-4">
            <div className="rounded-2xl border-2 border-slate-200 overflow-hidden bg-white">
              <p className="px-3 py-2 text-xs font-extrabold text-slate-400 bg-slate-50 border-b border-slate-100">플레이어</p>
              {activePlayers.map((p) => (
                <div key={p.seat} className={`px-3 py-2 border-b border-slate-50 last:border-b-0 ${p.seat === ss.turnSeat && !ended ? 'bg-amber-50' : ''} ${p.eliminated ? 'opacity-40' : ''}`}>
                  <div className="flex items-center justify-between">
                    <span className="font-bold text-sm flex items-center gap-1.5">
                      <span className="w-3 h-3 rounded-full border border-white shadow flex-none" style={{ background: SEAT_COLORS[p.seat % SEAT_COLORS.length] }} />
                      {p.bot ? '🤖 ' : ''}{p.name}{p.me ? ' (나)' : ''}
                      {p.eliminated && ' ☠️'}
                    </span>
                    {p.seat === ss.turnSeat && !ended && <span className="text-[10px] font-extrabold text-amber-600 bg-amber-100 rounded-full px-2 py-0.5">차례</span>}
                  </div>
                  <div className="flex items-center justify-between mt-1">
                    <span className="flex gap-0.5 items-center">
                      {Array.from({ length: p.pawnsLeft }).map((_, i) => (
                        <span key={i} className="w-2 h-2 rounded-full" style={{ background: SEAT_COLORS[p.seat % SEAT_COLORS.length] }} />
                      ))}
                      {p.pawnsLeft === 0 && <span className="text-[10px] text-slate-300">말 없음</span>}
                    </span>
                    <span className="flex gap-0.5 text-[11px]">
                      {Array.from({ length: ss.goal }).map((_, i) => (
                        <span key={i} className={i < p.crossed ? '' : 'opacity-20 grayscale'}>🏁</span>
                      ))}
                    </span>
                  </div>
                </div>
              ))}
            </div>

            {ss.log.length > 0 && (
              <div className="rounded-2xl border-2 border-slate-200 bg-white overflow-hidden">
                <p className="px-3 py-2 text-xs font-extrabold text-slate-400 bg-slate-50 border-b border-slate-100">진행 로그</p>
                <div className="p-2.5 max-h-48 lg:max-h-72 overflow-y-auto text-xs text-slate-500 space-y-1">
                  {[...ss.log].reverse().map((l, i) => <p key={ss.log.length - i}>{l}</p>)}
                </div>
              </div>
            )}
          </div>
        </div>
      )}

      {roomCode && <RoomChat game="ciao" roomCode={roomCode} clientId={id.current} nick={nick} />}
    </main>
  );
}
