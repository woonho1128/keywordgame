'use client';

import Link from 'next/link';
import { useCallback, useEffect, useRef, useState } from 'react';
import { api } from '@/lib/api';
import RoomChat from '@/components/RoomChat';

type Dest = { cell: string; label: string; caught: boolean; finish: boolean };
type Move = { value: number; name: string; tokenIndex: number; dests: Dest[] };
type ThrowResult = { name: string; value: number; extra: boolean };
type PlayerView = { seat: number; name: string; bot: boolean; host: boolean; me: boolean; team: number; color: number; tokens: string[]; doneCount: number; left: boolean; hasAbility: boolean };
type State = {
  phase: string; teamMode: boolean; backDo: boolean; isHost: boolean; joined: boolean;
  players: PlayerView[]; turnSeat: number; turnName: string | null; myTurn: boolean; mySeat: number; myTeam: number;
  throwsOwed: number; pending: ThrowResult[]; moves: Move[];
  lastAction: string | null; winnerTeam: number; winnerLabel: string | null;
  abilitiesOn: boolean; myAbility: string | null; myAbilityName: string | null; myAbilityDesc: string | null; myAbilityUsed: boolean;
  log: string[];
  deadline: number; serverNow: number;
};
type Room = { code: string; status: string; playerCount: number; host: string };

function cid(): string {
  if (typeof window === 'undefined') return '';
  let id = localStorage.getItem('yut_client_id');
  if (!id) { id = Math.random().toString(36).slice(2) + Date.now().toString(36); localStorage.setItem('yut_client_id', id); }
  return id;
}

// 말판 좌표(목업과 동일)
const COORD: Record<string, [number, number]> = {
  o0: [92, 92], o1: [92, 75.2], o2: [92, 58.4], o3: [92, 41.6], o4: [92, 24.8], o5: [92, 8],
  o6: [75.2, 8], o7: [58.4, 8], o8: [41.6, 8], o9: [24.8, 8], o10: [8, 8],
  o11: [8, 24.8], o12: [8, 41.6], o13: [8, 58.4], o14: [8, 75.2], o15: [8, 92],
  o16: [24.8, 92], o17: [41.6, 92], o18: [58.4, 92], o19: [75.2, 92],
  a1: [78, 22], a2: [64, 36], ct: [50, 50], a3: [36, 64], a4: [22, 78],
  b1: [22, 22], b2: [36, 36], b3: [64, 64], b4: [78, 78],
};
const OUTER = Array.from({ length: 20 }, (_, i) => 'o' + i);
const BIG = new Set(['o0', 'o5', 'o10', 'o15', 'ct']);
const DIAG_A = ['o5', 'a1', 'a2', 'ct', 'a3', 'a4', 'o15'];
const DIAG_B = ['o10', 'b1', 'b2', 'ct', 'b3', 'b4', 'o0'];
const PCOL = ['#3a7fc4', '#cf4436', '#e0a021', '#3f9a70'];
const PNAME = ['청', '홍', '황', '녹'];

export default function YutPage() {
  const id = useRef('');
  const [nick, setNick] = useState('');
  const [screen, setScreen] = useState<'entry' | 'lobby' | 'game'>('entry');
  const [roomCode, setRoomCode] = useState<string | null>(null);
  const [teamMode, setTeamMode] = useState(false);
  const [backDo, setBackDo] = useState(true);
  const [abilitiesOn, setAbilitiesOn] = useState(true);
  const [abilityMode, setAbilityMode] = useState(false);
  const [aStep, setAStep] = useState<string | null>(null); // myToken|myToken2|oppToken|cell|choice
  const [aMyToken, setAMyToken] = useState(-1);
  const [joinCode, setJoinCode] = useState('');
  const [rooms, setRooms] = useState<Room[]>([]);
  const [ss, setSs] = useState<State | null>(null);
  const [botLevel, setBotLevel] = useState('NORMAL');
  const [remaining, setRemaining] = useState(0);
  const [selValue, setSelValue] = useState<number | null>(null);
  const [selToken, setSelToken] = useState<number | null>(null); // 도착 선택 대기 중인 말
  const [charging, setCharging] = useState(false);
  const [power, setPower] = useState(0);
  const [sound, setSound] = useState(true);
  const lastSpokenRef = useRef<string | null>(null);
  const roomRef = useRef<string | null>(null); roomRef.current = roomCode;
  const offsetRef = useRef(0);
  const chargingRef = useRef(false);
  const startRef = useRef(0);
  const rafRef = useRef<number | null>(null);
  const CHARGE_MS = 1500; // 이 시간에 파워 120

  useEffect(() => { id.current = cid(); try { setNick(localStorage.getItem('arcade_nick') || ''); setSound(localStorage.getItem('yut_sound') !== '0'); } catch {} }, []);

  // 윷 결과·이벤트 음성(브라우저 TTS)
  const speak = (text: string) => {
    try {
      const synth = window.speechSynthesis; if (!synth) return;
      const u = new SpeechSynthesisUtterance(text);
      u.lang = 'ko-KR'; u.rate = 1.05;
      synth.cancel(); synth.speak(u);
    } catch {}
  };
  const phraseFor = (la: string): string | null => {
    if (la.includes('잡기')) return '잡았다!';
    if (la.includes('도착')) return '도착!';
    if (la.includes('이동') || la.includes('출발') || la.includes('사용') || la.includes('넘김')) return null;
    if (la.includes('낙')) return '낙!';
    if (la.includes('백도')) return '백도';
    if (la.includes('모')) return '모';
    if (la.includes('윷')) return '윷';
    if (la.includes('걸')) return '걸';
    if (la.includes('개')) return '개';
    if (la.includes('도')) return '도';
    return null;
  };
  useEffect(() => {
    const la = ss?.lastAction;
    if (!la || la === lastSpokenRef.current) return;
    lastSpokenRef.current = la;
    if (!sound || screen !== 'game') return;
    const ph = phraseFor(la);
    if (ph) speak(ph);
  }, [ss?.lastAction, sound, screen]);
  const toggleSound = () => setSound((v) => { const nv = !v; try { localStorage.setItem('yut_sound', nv ? '1' : '0'); } catch {} if (!nv) try { window.speechSynthesis?.cancel(); } catch {} return nv; });

  const loadRooms = useCallback(async () => { try { setRooms(await api(`/api/v1/yut/rooms`)); } catch {} }, []);
  useEffect(() => { if (screen === 'entry') { loadRooms(); const t = setInterval(loadRooms, 3000); return () => clearInterval(t); } }, [screen, loadRooms]);

  useEffect(() => {
    if (screen === 'entry' || !roomCode) return;
    let alive = true;
    const poll = async () => {
      try {
        const s = await api<State>(`/api/v1/yut/me?roomCode=${roomCode}&clientId=${id.current}`);
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

  // 이동 후보의 안정적 시그니처(폴링으로 배열 참조만 바뀌어도 재실행되지 않도록)
  const movesSig = ss?.myTurn ? ss.moves.map((m) => `${m.value}:${m.tokenIndex}`).join('|') : '';
  // 내 차례 값 자동 선택 + 유효하지 않은 선택만 정리(폴링 시 선택 유지)
  useEffect(() => {
    if (!ss?.myTurn) { setSelValue(null); setSelToken(null); setAbilityMode(false); setAStep(null); setAMyToken(-1); return; }
    const vals = [...new Set(ss.moves.map((m) => m.value))];
    setSelValue((cur) => (cur != null && vals.includes(cur) ? cur : vals[0] ?? null));
    setSelToken((cur) => (cur != null && ss.moves.some((m) => m.tokenIndex === cur) ? cur : null));
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [movesSig, ss?.myTurn]);

  const saveNick = (n: string) => { try { localStorage.setItem('arcade_nick', n); } catch {} };
  const create = async () => {
    const n = nick.trim(); if (!n) return; saveNick(n);
    try {
      const res = await api<{ roomCode: string; state: State }>(`/api/v1/yut/new?clientId=${id.current}`, { method: 'POST', body: JSON.stringify({ nick: n, teamMode, backDo, abilities: abilitiesOn }) });
      setRoomCode(res.roomCode); setSs(res.state); setScreen('lobby');
    } catch (e: any) { alert(e?.message || '방 생성 실패'); }
  };
  const join = async (code: string, nickOverride?: string) => {
    const n = (nickOverride ?? nick).trim(); if (!n || !code) return; setNick(n); saveNick(n);
    try {
      const s = await api<State>(`/api/v1/yut/join?roomCode=${code}&clientId=${id.current}`, { method: 'POST', body: JSON.stringify({ nick: n }) });
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
    try { window.history.replaceState({}, '', '/yut'); } catch {}
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, []);

  const addBot = async () => { try { setSs(await api(`/api/v1/yut/add-bot?roomCode=${roomCode}&clientId=${id.current}&level=${botLevel}`, { method: 'POST', body: '{}' })); } catch (e: any) { alert(e?.message); } };
  const startMatch = async () => { try { setSs(await api(`/api/v1/yut/start?roomCode=${roomCode}&clientId=${id.current}`, { method: 'POST', body: '{}' })); setScreen('game'); } catch (e: any) { alert(e?.message); } };
  const throwYut = async (pw: number) => { try { setSs(await api(`/api/v1/yut/throw?roomCode=${roomCode}&clientId=${id.current}&power=${Math.round(pw)}`, { method: 'POST', body: '{}' })); } catch (e: any) { alert(e?.message); } };
  const tickCharge = () => {
    if (!chargingRef.current) return;
    const p = Math.min(120, ((performance.now() - startRef.current) / CHARGE_MS) * 120);
    setPower(p);
    rafRef.current = requestAnimationFrame(tickCharge);
  };
  const startCharge = () => {
    if (!ss?.myTurn || ss.throwsOwed <= 0 || chargingRef.current) return;
    chargingRef.current = true; setCharging(true); startRef.current = performance.now(); setPower(0);
    rafRef.current = requestAnimationFrame(tickCharge);
  };
  const releaseCharge = () => {
    if (!chargingRef.current) return;
    chargingRef.current = false; setCharging(false);
    if (rafRef.current) cancelAnimationFrame(rafRef.current);
    const p = Math.min(120, ((performance.now() - startRef.current) / CHARGE_MS) * 120);
    setPower(0);
    throwYut(p);
  };
  const doMove = async (value: number, tokenIndex: number, dest: string) => {
    try { setSs(await api(`/api/v1/yut/move?roomCode=${roomCode}&clientId=${id.current}&value=${value}&tokenIndex=${tokenIndex}&dest=${dest}`, { method: 'POST', body: '{}' })); setSelToken(null); }
    catch (e: any) { alert(e?.message); }
  };
  const leave = async () => { const rc = roomRef.current; if (rc) { try { await api(`/api/v1/yut/leave?roomCode=${rc}&clientId=${id.current}`, { method: 'POST', body: '{}' }); } catch {} } setScreen('entry'); setRoomCode(null); setSs(null); };

  // ── 특수능력 ──
  const resetAbility = () => { setAbilityMode(false); setAStep(null); setAMyToken(-1); };
  const callAbility = async (p: { tokenIndex?: number; cell?: string; oppSeat?: number; oppToken?: number; choice?: string }) => {
    const qs = `tokenIndex=${p.tokenIndex ?? -1}&cell=${p.cell ?? ''}&oppSeat=${p.oppSeat ?? -1}&oppToken=${p.oppToken ?? -1}&choice=${p.choice ?? ''}`;
    try { setSs(await api(`/api/v1/yut/ability?roomCode=${roomCode}&clientId=${id.current}&${qs}`, { method: 'POST', body: '{}' })); } catch (e: any) { alert(e?.message); }
    resetAbility();
  };
  const startAbility = () => {
    const a = ss?.myAbility; if (!a) return;
    if (a === 'EXTRA') { callAbility({}); return; }
    setAbilityMode(true); setAMyToken(-1);
    setAStep(a === 'MO_OR_DO' ? 'choice' : a === 'SEND_HOME' ? 'oppToken' : 'myToken');
  };
  const abilityClickToken = (seat: number, tokenIndex: number) => {
    if (!abilityMode || !ss) return;
    const a = ss.myAbility; const isMine = seat === ss.mySeat;
    if (aStep === 'myToken') {
      if (!isMine) return;
      if (a === 'PLACE') { setAMyToken(tokenIndex); setAStep('cell'); }
      else if (a === 'SWAP') { setAMyToken(tokenIndex); setAStep('oppToken'); }
      else if (a === 'RALLY') { setAMyToken(tokenIndex); setAStep('myToken2'); }
    } else if (aStep === 'myToken2') {
      if (!isMine || tokenIndex === aMyToken) return;
      callAbility({ tokenIndex: aMyToken, oppToken: tokenIndex });
    } else if (aStep === 'oppToken') {
      if (isMine) return;
      if (a === 'SEND_HOME') callAbility({ oppSeat: seat, oppToken: tokenIndex });
      else if (a === 'SWAP') callAbility({ tokenIndex: aMyToken, oppSeat: seat, oppToken: tokenIndex });
    }
  };
  const abilityClickCell = (cell: string) => { if (abilityMode && aStep === 'cell') callAbility({ tokenIndex: aMyToken, cell }); };
  const isPlaceCell = (c: string) => { const n = Number(c.replace('o', '')); return c.startsWith('o') && n >= 1 && n <= 10; };

  const home = <Link href="/" aria-label="홈으로" className="text-lg leading-none text-slate-500 hover:text-slate-800 dark:hover:text-slate-100">🏠</Link>;
  const ended = ss?.phase === 'ENDED';
  const players = ss?.players ?? [];

  // 이동 헬퍼
  const movesForVal = ss && selValue != null ? ss.moves.filter((m) => m.value === selValue) : [];
  const movableIdx = new Set(movesForVal.map((m) => m.tokenIndex));
  // 셀 → 이동 가능한 내 말 index(업힌 말은 대표 1개만 move에 있으므로, 스택 어디를 눌러도 잡히도록)
  const cellMoveTi: Record<string, number> = {};
  if (ss?.myTurn) {
    const myTokens = players.find((p) => p.seat === ss.mySeat)?.tokens ?? [];
    movesForVal.forEach((m) => { const cell = myTokens[m.tokenIndex]; if (cell) cellMoveTi[cell] = m.tokenIndex; });
  }
  const destHighlights: Record<string, { value: number; token: number; dest: Dest }> = {};
  if (selToken != null) {
    const m = movesForVal.find((mm) => mm.tokenIndex === selToken);
    if (m) m.dests.forEach((d) => { destHighlights[d.cell] = { value: m.value, token: selToken, dest: d }; });
  }
  const tapToken = (tokenIndex: number) => {
    if (!ss?.myTurn || selValue == null) return;
    const m = movesForVal.find((mm) => mm.tokenIndex === tokenIndex);
    if (!m) return;
    if (m.dests.length === 1) doMove(m.value, tokenIndex, m.dests[0].cell);
    else setSelToken((cur) => (cur === tokenIndex ? null : tokenIndex));
  };

  // 판 위 말(개별 정체성 → 이동 애니메이션 유지)
  const boardTokens: { seat: number; ti: number; color: number; cell: string }[] = [];
  players.forEach((p) => p.tokens.forEach((c, ti) => {
    if (c === 'wait' || c === 'done') return;
    boardTokens.push({ seat: p.seat, ti, color: p.color, cell: c });
  }));
  const cellCount: Record<string, number> = {};
  boardTokens.forEach((t) => { cellCount[t.cell] = (cellCount[t.cell] || 0) + 1; });
  const badgeAt: Record<string, string> = {};
  boardTokens.forEach((t) => { if (!(t.cell in badgeAt)) badgeAt[t.cell] = `${t.seat}-${t.ti}`; });
  const teamOf = (seat: number) => players.find((p) => p.seat === seat)?.team;

  return (
    <main className="min-h-screen flex flex-col items-center p-3 sm:p-5 max-w-3xl mx-auto w-full text-slate-800 dark:text-slate-100">
      <div className="w-full flex items-center justify-between mb-3">
        <div className="flex items-center gap-2">{home}<h1 className="text-xl sm:text-2xl font-extrabold"><span className="text-blue-600">윷</span><span className="text-red-600">놀이</span></h1></div>
        <div className="flex items-center gap-2">
          <button onClick={toggleSound} title="윷 결과 음성" className="text-lg leading-none">{sound ? '🔊' : '🔇'}</button>
          {roomCode && <button onClick={leave} className="text-xs px-2 py-1 rounded bg-slate-200 dark:bg-slate-700 text-slate-600 dark:text-slate-100">나가기</button>}
        </div>
      </div>

      {/* ── 입장 ── */}
      {screen === 'entry' && (
        <div className="w-full max-w-md mx-auto space-y-4">
          <input value={nick} onChange={(e) => setNick(e.target.value)} maxLength={16} placeholder="닉네임 (필수)"
            className={`w-full border bg-transparent rounded-lg px-3 py-2 focus:outline-none focus:border-blue-500 ${nick.trim() ? 'border-slate-300 dark:border-slate-600' : 'border-blue-400'}`} />
          <div className="grid grid-cols-2 gap-2">
            <button onClick={() => setTeamMode(false)} className={`rounded-xl border-2 p-3 text-left ${!teamMode ? 'border-blue-500 bg-blue-500/10' : 'border-slate-300 dark:border-slate-600'}`}>
              <p className="font-bold">👤 개인전</p><p className="text-xs text-slate-500 dark:text-slate-400">2~4인 각자</p>
            </button>
            <button onClick={() => setTeamMode(true)} className={`rounded-xl border-2 p-3 text-left ${teamMode ? 'border-blue-500 bg-blue-500/10' : 'border-slate-300 dark:border-slate-600'}`}>
              <p className="font-bold">👥 팀전 2:2</p><p className="text-xs text-slate-500 dark:text-slate-400">4인 필요</p>
            </button>
          </div>
          <label className="flex items-center gap-2 text-sm rounded-lg border border-slate-200 dark:border-slate-700 px-3 py-2">
            <input type="checkbox" checked={backDo} onChange={(e) => setBackDo(e.target.checked)} /> 백도(뒤로 1칸) 사용
          </label>
          <label className="flex items-center gap-2 text-sm rounded-lg border border-slate-200 dark:border-slate-700 px-3 py-2">
            <input type="checkbox" checked={abilitiesOn} onChange={(e) => setAbilitiesOn(e.target.checked)} /> ✨ 랜덤 특수능력 (각자 1회·비공개)
          </label>
          <button onClick={create} disabled={!nick.trim()} className="w-full bg-blue-600 text-white font-bold py-3 rounded-lg disabled:opacity-40">방 만들기</button>
          <div className="flex gap-2">
            <input value={joinCode} onChange={(e) => setJoinCode(e.target.value.toUpperCase())} maxLength={4} placeholder="코드" className="flex-1 border border-slate-300 dark:border-slate-600 bg-transparent rounded-lg px-3 py-2 uppercase" />
            <button onClick={() => join(joinCode)} disabled={!nick.trim() || !joinCode} className="px-5 bg-slate-700 text-white font-bold rounded-lg disabled:opacity-40">참가</button>
          </div>
          <details className="rounded-xl border border-slate-200 dark:border-slate-700 p-3 text-sm text-slate-500 dark:text-slate-400">
            <summary className="font-bold cursor-pointer select-none">📖 규칙</summary>
            <div className="mt-2 space-y-1">
              <p>· 윷 던지기: 도1·개2·걸3·<b>윷4(한 번 더)</b>·<b>모5(한 번 더)</b>·백도(-1).</p>
              <p>· 말 4개를 판을 돌려 먼저 다 빼내면 승리. <b>큰 원에 정확히 멈추면 지름길</b> 선택 가능.</p>
              <p>· 상대 말을 잡으면 원점으로 보내고 <b>한 번 더</b>. 내 말끼리는 <b>업어서</b> 함께 이동.</p>
            </div>
          </details>
          <div className="rounded-xl border border-slate-200 dark:border-slate-700 p-3">
            <p className="text-sm font-bold text-slate-600 dark:text-slate-300 mb-2">🎮 열린 방</p>
            {rooms.length === 0 ? <p className="text-slate-400 text-sm text-center py-2">방이 없어요. 만들어보세요!</p> : rooms.map((r) => (
              <button key={r.code} onClick={() => join(r.code)} disabled={!nick.trim()} className="w-full flex justify-between text-sm py-1.5 px-2 rounded hover:bg-blue-500/10 disabled:opacity-40">
                <span className="font-bold">{r.code} · {r.host}</span>
                <span className="text-slate-500 dark:text-slate-400">{r.status === 'WAITING' ? '모집중' : r.status === 'PLAYING' ? '진행중' : '종료'} · {r.playerCount}명</span>
              </button>
            ))}
          </div>
        </div>
      )}

      {/* ── 로비 ── */}
      {screen === 'lobby' && ss && (
        <div className="w-full max-w-md mx-auto space-y-4">
          <div className="text-center">
            <p className="text-sm text-slate-500 dark:text-slate-400">방 코드</p>
            <p className="text-3xl font-extrabold tracking-widest text-blue-600 dark:text-blue-400">{roomCode}</p>
            <p className="text-xs text-slate-500 dark:text-slate-400">{players.filter((p) => !p.left).length}/4명 · {ss.teamMode ? '팀전 2:2(4명)' : '개인전'} {ss.backDo && '· 백도'}</p>
          </div>
          <div className="rounded-xl border border-slate-200 dark:border-slate-700 p-3 space-y-1">
            {players.filter((p) => !p.left).map((p, i) => (
              <div key={p.seat} className="flex items-center gap-2 text-sm px-1 py-0.5">
                <span className="w-3.5 h-3.5 rounded-full" style={{ background: PCOL[i] }} />
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
              <button onClick={startMatch} disabled={players.filter((p) => !p.left).length < 2} className="w-full bg-blue-600 text-white font-bold py-3 rounded-lg disabled:opacity-40">시작하기</button>
            </div>
          ) : <p className="text-center text-sm text-slate-500 dark:text-slate-400">방장이 시작하기를 기다리는 중…</p>}
        </div>
      )}

      {/* ── 게임 ── */}
      {screen === 'game' && ss && (
        <div className="w-full grid lg:grid-cols-[minmax(0,1fr)_300px] gap-4 items-start">
          {/* 말판 */}
          <div className="rounded-2xl p-3 sm:p-4" style={{ background: 'linear-gradient(160deg,#3c332a,#322a22)', border: '1px solid rgba(201,162,75,.35)' }}>
            <svg viewBox="-4 -4 108 108" className="w-full h-auto block">
              {/* 외곽선 */}
              {OUTER.map((c, i) => { const a = COORD[c], b = COORD[OUTER[(i + 1) % 20]]; return <line key={'e' + i} x1={a[0]} y1={a[1]} x2={b[0]} y2={b[1]} stroke="#6f6047" strokeWidth={1.1} strokeLinecap="round" />; })}
              {[DIAG_A, DIAG_B].map((dg, gi) => dg.slice(0, -1).map((c, i) => { const a = COORD[c], b = COORD[dg[i + 1]]; return <line key={'d' + gi + i} x1={a[0]} y1={a[1]} x2={b[0]} y2={b[1]} stroke="#a5843c" strokeWidth={0.9} strokeDasharray="2 2.2" />; }))}
              {/* 셀 */}
              {Object.entries(COORD).map(([c, [x, y]]) => {
                const big = BIG.has(c);
                const hi = !!destHighlights[c];
                const aCell = abilityMode && aStep === 'cell' && isPlaceCell(c);
                const clickable = hi || aCell;
                return (
                  <g key={c} onClick={clickable ? () => { if (hi) { const h = destHighlights[c]; doMove(h.value, h.token, h.dest.cell); } else abilityClickCell(c); } : undefined} style={{ cursor: clickable ? 'pointer' : 'default' }}>
                    {clickable && <circle cx={x} cy={y} r={8} fill="transparent" />}
                    {hi && <circle cx={x} cy={y} r={big ? 7.4 : 6} fill="none" stroke="#facc15" strokeWidth={1.6}><animate attributeName="opacity" values="1;.35;1" dur="1s" repeatCount="indefinite" /></circle>}
                    {aCell && <circle cx={x} cy={y} r={big ? 7.4 : 6} fill="none" stroke="#d946ef" strokeWidth={1.6}><animate attributeName="opacity" values="1;.35;1" dur="1s" repeatCount="indefinite" /></circle>}
                    <circle cx={x} cy={y} r={big ? 4.6 : 2.7} fill={big ? '#4a3f2d' : '#3c332a'} stroke={big ? '#c9a24b' : '#a8987a'} strokeWidth={big ? 1.6 : 1.2} />
                    {hi && destHighlights[c].dest.caught && <text x={x} y={y - 6.5} textAnchor="middle" fill="#fca5a5" fontSize={3.6} fontWeight={800}>잡기!</text>}
                  </g>
                );
              })}
              {/* 출발 표시 */}
              <text x={COORD.o0[0]} y={COORD.o0[1] + 9.5} textAnchor="middle" fill="#c9a24b" fontSize={3.1} fontWeight={700}>출발·도착</text>
              {/* 말(개별 렌더 → transform 트랜지션으로 미끄러지듯 이동) */}
              {boardTokens.map((t) => {
                const [x, y] = COORD[t.cell];
                const idKey = `${t.seat}-${t.ti}`;
                const isMine = t.seat === ss.mySeat;
                let onClk: (() => void) | undefined;
                let ring: string | null = null;
                if (abilityMode) {
                  if ((aStep === 'myToken' || aStep === 'myToken2') && isMine) { onClk = () => abilityClickToken(ss.mySeat, t.ti); ring = '#d946ef'; }
                  else if (aStep === 'oppToken' && teamOf(t.seat) !== ss.myTeam) { onClk = () => abilityClickToken(t.seat, t.ti); ring = '#d946ef'; }
                } else if (ss.myTurn && isMine && cellMoveTi[t.cell] !== undefined) {
                  const repTi = cellMoveTi[t.cell]; onClk = () => tapToken(repTi); ring = '#fff'; // 스택 어느 말을 눌러도 대표 이동 실행
                }
                const showBadge = cellCount[t.cell] > 1 && badgeAt[t.cell] === idKey;
                return (
                  <g key={idKey} onClick={onClk} style={{ cursor: onClk ? 'pointer' : 'default', transform: `translate(${x}px,${y}px)`, transition: 'transform .45s cubic-bezier(.4,1,.5,1)' }}>
                    {onClk && <circle r={7} fill="transparent" />}
                    {ring && <circle r={4.8} fill="none" stroke={ring} strokeWidth={1.1} opacity={0.95} />}
                    <circle r={3.4} fill={PCOL[t.color]} stroke="rgba(0,0,0,.3)" strokeWidth={0.8} />
                    {showBadge && <><rect x={1.2} y={-4.6} width={5.6} height={3.6} rx={1.8} fill="rgba(20,14,8,.9)" /><text x={4} y={-2} textAnchor="middle" fill="#fff" fontSize={3} fontWeight={800}>×{cellCount[t.cell]}</text></>}
                  </g>
                );
              })}
            </svg>
          </div>

          {/* 우측: 차례/던지기/말 */}
          <div className="space-y-3">
            {ended ? (
              <div className="rounded-xl border-2 border-blue-500 bg-blue-50 dark:bg-blue-500/10 p-3 text-center">
                <p className="text-lg font-bold">🏆 {ss.winnerLabel} 승리!</p>
                <button onClick={leave} className="mt-2 px-5 py-2 rounded-lg bg-blue-600 text-white font-bold">나가기</button>
              </div>
            ) : (
              <div className="rounded-xl border border-slate-200 dark:border-slate-700 p-3">
                <div className="flex items-center gap-2 mb-2">
                  <span className="w-4 h-4 rounded-full" style={{ background: PCOL[players[ss.turnSeat]?.color ?? 0] }} />
                  <b className={ss.myTurn ? 'text-blue-600 dark:text-blue-400' : ''}>{ss.myTurn ? '내 차례' : `${players[ss.turnSeat]?.bot ? '🤖 ' : ''}${ss.turnName} 차례`}</b>
                  {remaining > 0 && <span className="ml-auto text-xs text-slate-400">{remaining}s</span>}
                </div>

                {/* 던진 결과 칩 */}
                {ss.myTurn && ss.pending.length > 0 && (
                  <div className="flex flex-wrap gap-1.5 mb-2">
                    {[...new Set(ss.pending.map((p) => p.value))].map((v) => {
                      const nm = ss.pending.find((p) => p.value === v)!.name;
                      const cnt = ss.pending.filter((p) => p.value === v).length;
                      const usable = ss.moves.some((m) => m.value === v);
                      return (
                        <button key={v} onClick={() => usable && (setSelValue(v), setSelToken(null))} disabled={!usable}
                          className={`px-2.5 py-1 rounded-lg text-sm font-bold border-2 ${selValue === v ? 'border-blue-500 bg-blue-500/10' : 'border-slate-300 dark:border-slate-600'} ${!usable ? 'opacity-35' : ''}`}>
                          {nm}{cnt > 1 ? `×${cnt}` : ''}
                        </button>
                      );
                    })}
                  </div>
                )}

                {ss.myTurn && ss.throwsOwed > 0 ? (
                  <div className="select-none">
                    {/* 파워 게이지: 초록 구간(굿존)에서 떼면 성공률↑, 결과 자체는 랜덤 */}
                    <div className="relative h-6 rounded-full bg-slate-200 dark:bg-slate-700 overflow-hidden mb-2">
                      <div className="absolute inset-y-0 bg-emerald-500/35 border-x border-emerald-500/60" style={{ left: `${(28 / 120) * 100}%`, width: `${((98 - 28) / 120) * 100}%` }} />
                      <div className="absolute inset-y-0 left-0 rounded-r bg-blue-500/70" style={{ width: `${(power / 120) * 100}%` }} />
                      <div className="absolute inset-y-0 w-[3px] bg-slate-900 dark:bg-white" style={{ left: `calc(${(power / 120) * 100}% - 1.5px)`, opacity: power > 0 ? 1 : 0 }} />
                      <span className="absolute inset-0 flex items-center justify-center text-[10px] font-bold text-slate-600 dark:text-slate-200 pointer-events-none">초록 구간에서 떼기</span>
                    </div>
                    <button
                      onPointerDown={(e) => { e.preventDefault(); startCharge(); }}
                      onPointerUp={releaseCharge} onPointerLeave={releaseCharge} onPointerCancel={releaseCharge}
                      className={`w-full py-4 rounded-lg text-white font-extrabold text-lg active:scale-[.99] touch-none transition ${charging ? 'animate-pulse' : ''}`}
                      style={{ background: charging ? 'linear-gradient(180deg,#8f2b24,#5f1b16)' : 'linear-gradient(180deg,#b6382f,#8f2b24)' }}>
                      🎋 {charging ? '지금 떼세요!' : '꾹 눌렀다 떼서 던지기'} {ss.throwsOwed > 1 ? `(${ss.throwsOwed})` : ''}
                    </button>
                    <p className="text-[11px] text-center text-slate-500 dark:text-slate-400 mt-1">초록에서 벗어나면 <b className="text-red-500">낙!</b> 확률↑ · 도개걸윷모는 랜덤</p>
                  </div>
                ) : ss.myTurn && ss.moves.length > 0 ? (
                  <>
                    <p className="text-xs text-center text-slate-500 dark:text-slate-400">
                      {selToken != null ? '도착 방향을 고르세요' : '움직일 말(흰 테두리)을 누르세요'}
                    </p>
                    {/* 갈림길: 선택한 말의 도착 후보를 큰 버튼으로(확실한 선택) */}
                    {(() => {
                      const m = movesForVal.find((mm) => mm.tokenIndex === selToken);
                      if (!m || m.dests.length < 2) return null;
                      return (
                        <div className="flex gap-2 mt-2">
                          {m.dests.map((d, i) => (
                            <button key={d.cell} onClick={() => doMove(m.value, selToken!, d.cell)}
                              className="flex-1 py-2.5 rounded-lg border-2 border-blue-500 bg-blue-500/10 text-sm font-bold hover:bg-blue-500/20">
                              {i === 0 ? '⤳ 지름길' : '→ 직진'}{d.caught ? ' · 잡기!' : ''}{d.finish ? ' · 도착!' : ''}
                            </button>
                          ))}
                        </div>
                      );
                    })()}
                  </>
                ) : null}

                {ss.lastAction && <p className={`text-xs text-center mt-2 font-bold ${ss.lastAction.includes('잡') || ss.lastAction.includes('도착') || ss.lastAction.includes('사용') ? 'text-emerald-600 dark:text-emerald-400' : 'text-slate-500 dark:text-slate-400'}`}>{ss.lastAction}</p>}
              </div>
            )}

            {/* 내 특수능력 */}
            {!ended && ss.abilitiesOn && ss.myAbility && (
              <div className="rounded-xl border-2 border-fuchsia-400 bg-fuchsia-500/5 p-3">
                <p className="text-sm font-bold text-fuchsia-600 dark:text-fuchsia-400">✨ 내 능력: {ss.myAbilityName}{ss.myAbilityUsed ? ' (사용함)' : ''}</p>
                <p className="text-[11px] text-slate-500 dark:text-slate-400 mt-0.5">{ss.myAbilityDesc}</p>
                {!ss.myAbilityUsed && ss.myTurn && !abilityMode && (
                  <button onClick={startAbility} className="w-full mt-2 py-2 rounded-lg bg-fuchsia-600 text-white font-bold text-sm">능력 사용</button>
                )}
                {abilityMode && (
                  <div className="mt-2">
                    <p className="text-xs text-center font-bold text-fuchsia-600 dark:text-fuchsia-400">
                      {aStep === 'choice' ? '모 / 도 선택' : aStep === 'myToken' ? '내 말을 누르세요' : aStep === 'myToken2' ? '모을 대상(내 다른 말) 누르기' : aStep === 'oppToken' ? '상대 말을 누르세요' : aStep === 'cell' ? '놓을 칸(앞쪽 1~10) 누르기' : ''}
                    </p>
                    {aStep === 'choice' && (
                      <div className="flex gap-2 mt-2">
                        <button onClick={() => callAbility({ choice: 'MO' })} className="flex-1 py-2 rounded-lg border-2 border-fuchsia-500 bg-fuchsia-500/10 font-bold text-sm">모 (5·한번더)</button>
                        <button onClick={() => callAbility({ choice: 'DO' })} className="flex-1 py-2 rounded-lg border-2 border-fuchsia-500 bg-fuchsia-500/10 font-bold text-sm">도 (1)</button>
                      </div>
                    )}
                    <button onClick={resetAbility} className="w-full mt-2 py-1.5 rounded-lg bg-slate-200 dark:bg-slate-700 text-xs font-bold">취소</button>
                  </div>
                )}
              </div>
            )}

            {/* 플레이어 말 현황 */}
            <div className="rounded-xl border border-slate-200 dark:border-slate-700 divide-y divide-slate-100 dark:divide-slate-800">
              {players.filter((p) => !p.left).map((p) => {
                const waitCount = p.tokens.filter((t) => t === 'wait').length;
                return (
                  <div key={p.seat} className={`p-2 ${p.seat === ss.turnSeat && !ended ? 'bg-blue-50 dark:bg-blue-500/10' : ''}`}>
                    <div className="flex items-center gap-2 text-sm">
                      <span className="w-3.5 h-3.5 rounded-full shrink-0" style={{ background: PCOL[p.color] }} />
                      <span className="font-bold">{p.bot ? '🤖' : ''}{p.name}{p.me ? '*' : ''}</span>
                      {ss.teamMode && <span className="text-[10px] text-slate-400">팀{p.team + 1}</span>}
                      {p.hasAbility && !p.me && <span className="text-[11px]" title="미사용 능력 보유(비공개)">✨</span>}
                      <span className="ml-auto text-xs">🏁 {p.doneCount}/4</span>
                    </div>
                    {/* 대기 말: 이동/능력 대상 클릭 */}
                    <div className="flex gap-1 mt-1">
                      {p.tokens.map((t, ti) => {
                        if (t !== 'wait') return null;
                        const cMove = p.me && !abilityMode && ss.myTurn && movableIdx.has(ti);
                        const cAbil = p.me && abilityMode && aStep === 'myToken' && ss.myAbility === 'PLACE';
                        const clickable = cMove || cAbil;
                        return <button key={ti} onClick={cMove ? () => tapToken(ti) : cAbil ? () => abilityClickToken(ss.mySeat, ti) : undefined} disabled={!clickable}
                          className={`w-4 h-4 rounded-full ${clickable ? `ring-2 ring-offset-1 ${cAbil ? 'ring-fuchsia-500' : 'ring-slate-800 dark:ring-white'}` : ''}`}
                          style={{ background: PCOL[p.color], opacity: clickable ? 1 : 0.55 }} />;
                      })}
                      {waitCount === 0 && p.doneCount < 4 && <span className="text-[10px] text-slate-400">전원 출발</span>}
                    </div>
                  </div>
                );
              })}
            </div>

            {/* 이력 */}
            <details className="rounded-xl border border-slate-200 dark:border-slate-700 p-2" open>
              <summary className="text-xs font-bold text-slate-600 dark:text-slate-300 cursor-pointer select-none">📜 이력</summary>
              <div className="mt-1 max-h-40 overflow-y-auto space-y-0.5">
                {(ss.log ?? []).length === 0 ? <p className="text-[11px] text-slate-400">아직 없음</p> :
                  [...ss.log].reverse().map((l, i) => (
                    <p key={i} className={`text-[11px] ${i === 0 ? 'font-bold text-slate-700 dark:text-slate-200' : 'text-slate-500 dark:text-slate-400'}`}>{l}</p>
                  ))}
              </div>
            </details>
          </div>
        </div>
      )}

      {roomCode && <RoomChat game="yut" roomCode={roomCode} clientId={id.current} nick={nick} />}
    </main>
  );
}
