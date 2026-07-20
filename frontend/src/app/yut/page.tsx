'use client';

import Link from 'next/link';
import { useCallback, useEffect, useRef, useState } from 'react';
import { api } from '@/lib/api';
import RoomChat from '@/components/RoomChat';

type Dest = { cell: string; label: string; caught: boolean; finish: boolean };
type Move = { value: number; name: string; tokenIndex: number; dests: Dest[] };
type ThrowResult = { name: string; value: number; extra: boolean };
type PlayerView = { seat: number; name: string; bot: boolean; host: boolean; me: boolean; team: number; color: number; tokens: string[]; doneCount: number; left: boolean };
type State = {
  phase: string; teamMode: boolean; backDo: boolean; isHost: boolean; joined: boolean;
  players: PlayerView[]; turnSeat: number; turnName: string | null; myTurn: boolean; mySeat: number; myTeam: number;
  throwsOwed: number; pending: ThrowResult[]; moves: Move[];
  lastAction: string | null; winnerTeam: number; winnerLabel: string | null; deadline: number; serverNow: number;
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
  const [joinCode, setJoinCode] = useState('');
  const [rooms, setRooms] = useState<Room[]>([]);
  const [ss, setSs] = useState<State | null>(null);
  const [botLevel, setBotLevel] = useState('NORMAL');
  const [remaining, setRemaining] = useState(0);
  const [selValue, setSelValue] = useState<number | null>(null);
  const [selToken, setSelToken] = useState<number | null>(null); // 도착 선택 대기 중인 말
  const roomRef = useRef<string | null>(null); roomRef.current = roomCode;
  const offsetRef = useRef(0);

  useEffect(() => { id.current = cid(); try { setNick(localStorage.getItem('arcade_nick') || ''); } catch {} }, []);

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

  // 내 차례 값 자동 선택 + 말 선택 초기화
  useEffect(() => {
    if (!ss?.myTurn) { setSelValue(null); setSelToken(null); return; }
    const vals = [...new Set(ss.moves.map((m) => m.value))];
    setSelValue((cur) => (cur != null && vals.includes(cur) ? cur : vals[0] ?? null));
    setSelToken(null);
  }, [ss?.moves, ss?.myTurn, ss?.turnSeat, ss?.throwsOwed]);

  const saveNick = (n: string) => { try { localStorage.setItem('arcade_nick', n); } catch {} };
  const create = async () => {
    const n = nick.trim(); if (!n) return; saveNick(n);
    try {
      const res = await api<{ roomCode: string; state: State }>(`/api/v1/yut/new?clientId=${id.current}`, { method: 'POST', body: JSON.stringify({ nick: n, teamMode, backDo }) });
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
  const throwYut = async () => { try { setSs(await api(`/api/v1/yut/throw?roomCode=${roomCode}&clientId=${id.current}`, { method: 'POST', body: '{}' })); } catch (e: any) { alert(e?.message); } };
  const doMove = async (value: number, tokenIndex: number, dest: string) => {
    try { setSs(await api(`/api/v1/yut/move?roomCode=${roomCode}&clientId=${id.current}&value=${value}&tokenIndex=${tokenIndex}&dest=${dest}`, { method: 'POST', body: '{}' })); setSelToken(null); }
    catch (e: any) { alert(e?.message); }
  };
  const leave = async () => { const rc = roomRef.current; if (rc) { try { await api(`/api/v1/yut/leave?roomCode=${rc}&clientId=${id.current}`, { method: 'POST', body: '{}' }); } catch {} } setScreen('entry'); setRoomCode(null); setSs(null); };

  const home = <Link href="/" aria-label="홈으로" className="text-lg leading-none text-slate-500 hover:text-slate-800 dark:hover:text-slate-100">🏠</Link>;
  const ended = ss?.phase === 'ENDED';
  const players = ss?.players ?? [];

  // 이동 헬퍼
  const movesForVal = ss && selValue != null ? ss.moves.filter((m) => m.value === selValue) : [];
  const movableIdx = new Set(movesForVal.map((m) => m.tokenIndex));
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

  // 판 위 말 그룹핑
  const tokensAt: Record<string, { color: number; seat: number }[]> = {};
  players.forEach((p) => p.tokens.forEach((c, ti) => {
    if (c === 'wait' || c === 'done') return;
    (tokensAt[c] ||= []).push({ color: p.color, seat: p.seat });
  }));

  return (
    <main className="min-h-screen flex flex-col items-center p-3 sm:p-5 max-w-3xl mx-auto w-full text-slate-800 dark:text-slate-100">
      <div className="w-full flex items-center justify-between mb-3">
        <div className="flex items-center gap-2">{home}<h1 className="text-xl sm:text-2xl font-extrabold"><span className="text-blue-600">윷</span><span className="text-red-600">놀이</span></h1></div>
        {roomCode && <button onClick={leave} className="text-xs px-2 py-1 rounded bg-slate-200 dark:bg-slate-700 text-slate-600 dark:text-slate-100">나가기</button>}
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
                return (
                  <g key={c} onClick={hi ? () => { const h = destHighlights[c]; doMove(h.value, h.token, h.dest.cell); } : undefined} style={{ cursor: hi ? 'pointer' : 'default' }}>
                    {hi && <circle cx={x} cy={y} r={big ? 7.4 : 6} fill="none" stroke="#facc15" strokeWidth={1.6}><animate attributeName="opacity" values="1;.35;1" dur="1s" repeatCount="indefinite" /></circle>}
                    <circle cx={x} cy={y} r={big ? 4.6 : 2.7} fill={big ? '#4a3f2d' : '#3c332a'} stroke={big ? '#c9a24b' : '#a8987a'} strokeWidth={big ? 1.6 : 1.2} />
                    {hi && destHighlights[c].dest.caught && <text x={x} y={y - 6.5} textAnchor="middle" fill="#fca5a5" fontSize={3.6} fontWeight={800}>잡기!</text>}
                  </g>
                );
              })}
              {/* 출발 표시 */}
              <text x={COORD.o0[0]} y={COORD.o0[1] + 9.5} textAnchor="middle" fill="#c9a24b" fontSize={3.1} fontWeight={700}>출발·도착</text>
              {/* 말 */}
              {Object.entries(tokensAt).map(([c, arr]) => {
                const [x, y] = COORD[c];
                const first = arr[0];
                const mine = players[ss.mySeat]?.color === first.color;
                const myMovable = ss.myTurn && mine && [...movableIdx].some((ti) => players[ss.mySeat].tokens[ti] === c);
                const clickToken = myMovable ? players[ss.mySeat].tokens.findIndex((tc) => tc === c) : -1;
                return (
                  <g key={'t' + c} onClick={clickToken >= 0 ? () => tapToken(clickToken) : undefined} style={{ cursor: clickToken >= 0 ? 'pointer' : 'default' }}>
                    {myMovable && <circle cx={x} cy={y} r={4.6} fill="none" stroke="#fff" strokeWidth={1} opacity={0.9} />}
                    <circle cx={x} cy={y} r={3.4} fill={PCOL[first.color]} stroke="rgba(0,0,0,.3)" strokeWidth={0.8} />
                    {arr.length > 1 && <><circle cx={x + 2.6} cy={y - 2.6} r={2.3} fill="rgba(20,14,8,.85)" /><text x={x + 2.6} y={y - 1.4} textAnchor="middle" fill="#fff" fontSize={3.4} fontWeight={800}>{arr.length}</text></>}
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
                  <button onClick={throwYut} className="w-full py-3 rounded-lg text-white font-extrabold" style={{ background: 'linear-gradient(180deg,#b6382f,#8f2b24)' }}>
                    🎋 윷 던지기 {ss.throwsOwed > 1 ? `(${ss.throwsOwed})` : ''}
                  </button>
                ) : ss.myTurn && ss.moves.length > 0 ? (
                  <p className="text-xs text-center text-slate-500 dark:text-slate-400">
                    {selToken != null ? '도착 지점을 선택하세요' : '움직일 말(흰 테두리)을 누르세요'}
                  </p>
                ) : null}

                {ss.lastAction && <p className={`text-xs text-center mt-2 font-bold ${ss.lastAction.includes('잡') || ss.lastAction.includes('도착') ? 'text-emerald-600 dark:text-emerald-400' : 'text-slate-500 dark:text-slate-400'}`}>{ss.lastAction}</p>}
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
                      <span className="ml-auto text-xs">🏁 {p.doneCount}/4</span>
                    </div>
                    {/* 대기 말: 내 것이고 움직일 수 있으면 클릭 */}
                    <div className="flex gap-1 mt-1">
                      {p.tokens.map((t, ti) => {
                        if (t !== 'wait') return null;
                        const clickable = p.me && ss.myTurn && movableIdx.has(ti);
                        return <button key={ti} onClick={clickable ? () => tapToken(ti) : undefined} disabled={!clickable}
                          className={`w-4 h-4 rounded-full ${clickable ? 'ring-2 ring-offset-1 ring-slate-800 dark:ring-white' : ''}`}
                          style={{ background: PCOL[p.color], opacity: clickable ? 1 : 0.55 }} />;
                      })}
                      {waitCount === 0 && p.doneCount < 4 && <span className="text-[10px] text-slate-400">전원 출발</span>}
                    </div>
                  </div>
                );
              })}
            </div>
          </div>
        </div>
      )}

      {roomCode && <RoomChat game="yut" roomCode={roomCode} clientId={id.current} nick={nick} />}
    </main>
  );
}
