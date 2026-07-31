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
        setSs(s);
        if (s.phase !== 'LOBBY' && screen === 'lobby') setScreen('game');
        if (s.phase === 'LOBBY' && screen === 'game') setScreen('lobby');
      } catch {}
    };
    const t = setInterval(poll, 1000); poll();
    return () => { alive = false; clearInterval(t); };
  }, [screen, roomCode]);

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

  const home = <Link href="/" aria-label="홈으로" className="text-lg leading-none text-slate-500 hover:text-slate-800">🏠</Link>;
  const ended = ss?.phase === 'ENDED';
  const remainSec = ss ? Math.max(0, Math.ceil((ss.deadline - ss.serverNow) / 1000)) : 0;
  const activePlayers = ss ? ss.players.filter((p) => !p.left) : [];

  // 다리 렌더: 위치별 말 목록 (0=출발점)
  const pawnsAt = (pos: number) => activePlayers.filter((p) => !p.eliminated && p.bridgePos === pos && (pos > 0 || p.pawnsLeft > 0));

  return (
    <main className="min-h-screen flex flex-col items-center p-3 max-w-3xl mx-auto w-full text-slate-800">
      <div className="w-full flex items-center justify-between mb-3">
        <div className="flex items-center gap-2">{home}<h1 className="text-xl font-bold">🌉 차오차오</h1></div>
        {roomCode && <button onClick={leave} className="text-xs px-2 py-1 rounded bg-slate-700 text-slate-100">나가기</button>}
      </div>

      {/* 입장 화면 */}
      {screen === 'entry' && (
        <div className="w-full max-w-md space-y-4">
          <input value={nick} onChange={(e) => setNick(e.target.value)} maxLength={16} placeholder="닉네임"
            className="w-full border border-slate-300 rounded-lg px-3 py-2.5" />
          <div className="rounded-xl border border-slate-200 p-3 space-y-2">
            <p className="text-sm font-bold">🆕 방 만들기</p>
            <label className="flex items-center justify-between text-sm">
              <span>의심 대기 시간</span>
              <select value={challengeSec} onChange={(e) => setChallengeSec(Number(e.target.value))} className="border border-slate-300 rounded-lg px-2 py-1.5">
                <option value={5}>5초 (스피드)</option>
                <option value={8}>8초 (기본)</option>
                <option value={12}>12초 (여유)</option>
                <option value={20}>20초 (느긋)</option>
              </select>
            </label>
            <button onClick={create} disabled={!nick.trim()} className="w-full bg-amber-600 text-white font-bold py-2.5 rounded-lg disabled:opacity-40">방 만들기</button>
          </div>
          <div className="flex gap-2">
            <input value={joinCode} onChange={(e) => setJoinCode(e.target.value.toUpperCase())} maxLength={4} placeholder="코드"
              className="w-24 border border-slate-300 rounded-lg px-3 py-2 uppercase tracking-widest font-bold" />
            <button onClick={() => join(joinCode)} disabled={!nick.trim() || joinCode.length < 4} className="flex-1 bg-slate-700 text-white font-bold rounded-lg disabled:opacity-40">코드로 참가</button>
          </div>
          {rooms.length > 0 && (
            <div className="rounded-xl border border-slate-200 p-3 space-y-1.5">
              <p className="text-sm font-bold">🏠 열린 방</p>
              {rooms.map((r) => (
                <button key={r.code} onClick={() => join(r.code)} disabled={!nick.trim() || r.status !== 'WAITING'}
                  className="w-full flex justify-between items-center text-sm px-2 py-1.5 rounded-lg border border-slate-200 disabled:opacity-40 hover:border-amber-500">
                  <span className="font-bold">{r.code} <span className="font-normal text-slate-500">· {r.host}</span></span>
                  <span className="text-slate-400">{r.status === 'WAITING' ? '모집중' : r.status === 'PLAYING' ? '진행중' : '종료'} · {r.playerCount}명</span>
                </button>
              ))}
            </div>
          )}
          <details className="rounded-xl border border-slate-200 p-3 text-sm text-slate-600">
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
        <div className="w-full space-y-4">
          <div className="text-center">
            <p className="text-sm text-slate-400">방 코드</p>
            <p className="text-3xl font-extrabold tracking-widest text-amber-600">{roomCode}</p>
            <p className="text-xs text-slate-400">의심 대기 {ss.challengeSec}초 · {ss.players.length}/10명</p>
          </div>
          <div className="rounded-xl border border-slate-200 p-3 space-y-1">
            {ss.players.map((p, i) => (
              <div key={i} className="flex justify-between text-sm px-1 py-0.5">
                <span className="font-bold"><span style={{ color: SEAT_COLORS[i % SEAT_COLORS.length] }}>●</span> {p.host ? '👑 ' : ''}{p.bot ? '🤖 ' : ''}{p.name}{p.me ? ' (나)' : ''}</span>
              </div>
            ))}
          </div>
          <p className="text-center text-xs text-slate-400">
            {(() => { const n = ss.players.length; const pw = n <= 4 ? 7 : n <= 6 ? 6 : n <= 8 ? 5 : 4; const gl = n <= 6 ? 3 : 2; return `현재 ${n}인 기준 → 말 ${pw}개 · ${gl}개 건너면 승리`; })()}
          </p>
          {ss.isHost ? (
            <div className="space-y-2">
              <div className="flex gap-2">
                <select value={botLevel} onChange={(e) => setBotLevel(e.target.value)} className="flex-1 border border-slate-300 bg-transparent rounded-lg px-3 py-2">
                  <option value="EASY">봇 초급</option><option value="NORMAL">봇 중급</option><option value="HARD">봇 고급</option>
                </select>
                <button onClick={addBot} className="px-4 bg-slate-700 text-white font-bold rounded-lg">봇 추가</button>
              </div>
              <button onClick={startMatch} disabled={ss.players.length < 2} className="w-full bg-amber-600 text-white font-bold py-3 rounded-lg disabled:opacity-40">시작하기</button>
            </div>
          ) : <p className="text-center text-sm text-slate-400">방장이 시작하기를 기다리는 중…</p>}
        </div>
      )}

      {/* 게임 */}
      {screen === 'game' && ss && (
        <div className="w-full space-y-3">
          <div className="flex items-center justify-between text-sm">
            <span className="text-slate-400">말 {ss.pawnsPer}개 · {ss.goal}개 건너면 승리</span>
            <span className="font-bold text-amber-600">
              {ended ? '게임 종료'
                : ss.myTurn
                  ? (ss.turnPhase === 'ROLL' ? '주사위를 굴리세요!' : ss.turnPhase === 'DECLARE' ? '숫자를 선언하세요!' : `의심 대기… ${remainSec}초`)
                  : ss.turnPhase === 'CHALLENGE' ? `의심 기회! ${remainSec}초` : `${ss.turnName ?? ''}님 차례…`}
            </span>
          </div>

          {ended && (
            <div className="text-center rounded-xl border-2 border-amber-500 bg-amber-500/10 px-6 py-3">
              <p className="text-lg font-bold">🏆 {ss.winnerLabel} 승리!</p>
            </div>
          )}

          {/* 다리 */}
          <div className="rounded-xl border border-slate-200 p-2 overflow-x-auto">
            <div className="flex items-stretch gap-1 min-w-[560px]">
              {[0, ...Array.from({ length: ss.bridgeLen }, (_, i) => i + 1)].map((pos) => (
                <div key={pos} className={`flex-1 min-w-[42px] rounded-lg border-2 px-1 pb-1 ${pos === 0 ? 'border-emerald-300 bg-emerald-50' : 'border-amber-200 bg-amber-50'}`}>
                  <p className="text-center text-[10px] text-slate-400 leading-4">{pos === 0 ? '출발' : pos}</p>
                  <div className="flex flex-wrap justify-center gap-0.5">
                    {pawnsAt(pos).map((p) => (
                      <span key={p.seat} title={p.name}
                        className={`w-5 h-5 rounded-full text-[10px] font-bold text-white flex items-center justify-center ${p.seat === ss.turnSeat && !ended ? 'ring-2 ring-slate-800' : ''}`}
                        style={{ background: SEAT_COLORS[p.seat % SEAT_COLORS.length] }}>
                        {p.name.slice(0, 1)}
                      </span>
                    ))}
                  </div>
                </div>
              ))}
              <div className="flex-1 min-w-[42px] rounded-lg border-2 border-sky-300 bg-sky-50 px-1 pb-1">
                <p className="text-center text-[10px] text-slate-400 leading-4">도착🏁</p>
              </div>
            </div>
          </div>

          {/* 의심 판정 공개 */}
          {ss.lastReveal && !ended && (
            <div className={`text-center text-sm rounded-lg px-3 py-1.5 font-bold ${ss.lastReveal.lie ? 'bg-rose-100 text-rose-700' : 'bg-emerald-100 text-emerald-700'}`}>
              직전 판정: 선언 「{ss.lastReveal.declared}」 → 주사위 「{ss.lastReveal.actual === 0 ? 'X' : ss.lastReveal.actual}」 = {ss.lastReveal.lie ? '거짓말! 💦' : '진실!'}
            </div>
          )}

          {/* 턴 액션 */}
          {!ended && ss.myTurn && ss.turnPhase === 'ROLL' && (
            <button onClick={roll} className="w-full bg-amber-600 text-white font-extrabold text-lg py-4 rounded-xl active:scale-95">🎲 통 속에 주사위 굴리기</button>
          )}
          {!ended && ss.myTurn && ss.turnPhase === 'DECLARE' && (
            <div className="rounded-xl border-2 border-amber-400 p-3 space-y-2">
              <p className="text-center font-bold">
                {ss.myRoll === 0 ? '🤫 나만 보임: ❌ X — 반드시 거짓말!' : `🤫 나만 보임: 「${ss.myRoll}」`}
              </p>
              <p className="text-center text-xs text-slate-400">{ss.myRoll === 0 ? '1~4 중 아무 숫자나 선언하세요 (전부 거짓말)' : '진실을 말해도, 거짓말을 해도 됩니다'}</p>
              <div className="grid grid-cols-4 gap-2">
                {[1, 2, 3, 4].map((v) => (
                  <button key={v} onClick={() => declareVal(v)}
                    className={`py-3 rounded-lg font-extrabold text-xl border-2 active:scale-95 ${v === ss.myRoll ? 'border-emerald-400 bg-emerald-50' : 'border-slate-300'}`}>
                    {v}{v === ss.myRoll ? <span className="block text-[9px] font-bold text-emerald-600 leading-none">진실</span> : null}
                  </button>
                ))}
              </div>
            </div>
          )}
          {!ended && ss.turnPhase === 'CHALLENGE' && !ss.myTurn && (
            <div className="rounded-xl border-2 border-rose-300 p-3 space-y-2 text-center">
              <p className="font-bold text-lg">🎲 {ss.turnName} ▸ 「{ss.declared}」 선언!</p>
              <div className="h-1.5 bg-slate-200 rounded-full overflow-hidden">
                <div className="h-full bg-rose-500 transition-all" style={{ width: `${Math.min(100, (remainSec / ss.challengeSec) * 100)}%` }} />
              </div>
              {ss.canChallenge
                ? <button onClick={challenge} className="w-full bg-rose-600 text-white font-extrabold py-3 rounded-xl active:scale-95">🔍 의심하기! ({remainSec}초)</button>
                : <p className="text-sm text-slate-400">의심 창 진행 중… ({remainSec}초)</p>}
              <p className="text-[11px] text-slate-400">의심 성공: 상대 말 추락 + 내가 {ss.declared}칸 전진 · 실패: 내 말 추락</p>
            </div>
          )}

          {/* 플레이어 현황 */}
          <div className="rounded-xl border border-slate-200 divide-y divide-slate-100">
            {activePlayers.map((p) => (
              <div key={p.seat} className={`flex items-center justify-between px-3 py-1.5 text-sm ${p.seat === ss.turnSeat && !ended ? 'bg-amber-50' : ''} ${p.eliminated ? 'opacity-40' : ''}`}>
                <span className="font-bold">
                  <span style={{ color: SEAT_COLORS[p.seat % SEAT_COLORS.length] }}>●</span> {p.bot ? '🤖 ' : ''}{p.name}{p.me ? ' (나)' : ''}
                  {p.eliminated ? ' ☠️' : p.seat === ss.turnSeat && !ended ? ' ◀' : ''}
                </span>
                <span className="text-slate-500">🧍×{p.pawnsLeft} · 🏁 {p.crossed}/{ss.goal}</span>
              </div>
            ))}
          </div>

          {/* 로그 */}
          {ss.log.length > 0 && (
            <div className="rounded-xl border border-slate-200 p-2 max-h-40 overflow-y-auto text-xs text-slate-500 space-y-0.5">
              {[...ss.log].reverse().map((l, i) => <p key={ss.log.length - i}>{l}</p>)}
            </div>
          )}
        </div>
      )}

      {roomCode && <RoomChat game="ciao" roomCode={roomCode} clientId={id.current} nick={nick} />}
    </main>
  );
}
