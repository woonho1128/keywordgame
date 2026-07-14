'use client';

import Link from 'next/link';
import { useCallback, useEffect, useRef, useState } from 'react';
import { api } from '@/lib/api';
import RoomChat from '@/components/RoomChat';

type RowCard = { card: number; bulls: number };
type PlayerView = { name: string; bot: boolean; botLevel: string | null; host: boolean; me: boolean; penalty: number; selected: boolean; lastTook: number; left: boolean };
type Event = { card: number; seat: number; row: number; took: number };
type State = {
  phase: string; endMode: string; targetHands: number; handIndex: number;
  isHost: boolean; joined: boolean;
  rows: RowCard[][]; players: PlayerView[]; myHand: RowCard[];
  myTurnToPlay: boolean; iAmChooser: boolean; chooserName: string | null;
  events: Event[]; winner: string | null; deadline: number; serverNow: number;
};
type Room = { code: string; status: string; playerCount: number; host: string };

function cid(): string {
  if (typeof window === 'undefined') return '';
  let id = localStorage.getItem('sixnimmt_client_id');
  if (!id) { id = Math.random().toString(36).slice(2) + Date.now().toString(36); localStorage.setItem('sixnimmt_client_id', id); }
  return id;
}

// 벌점별 색
function bullColor(b: number): string {
  return b >= 7 ? '#c026d3' : b >= 5 ? '#f43f5e' : b >= 3 ? '#f97316' : b >= 2 ? '#f59e0b' : '#64748b';
}
function Card({ c, sel, onClick, dim }: { c: RowCard; sel?: boolean; onClick?: () => void; dim?: boolean }) {
  return (
    <button onClick={onClick} disabled={!onClick}
      className={`relative shrink-0 rounded-md border-2 flex flex-col items-center justify-center font-bold transition ${sel ? 'border-fuchsia-500 -translate-y-2' : 'border-slate-600'} ${dim ? 'opacity-40' : ''} ${onClick ? 'active:scale-95' : ''}`}
      style={{ width: 40, height: 56, background: '#1e293b' }}>
      <span className="text-base leading-none text-slate-100">{c.card}</span>
      <span className="mt-0.5 text-[10px] leading-none font-bold" style={{ color: bullColor(c.bulls) }}>🐮{c.bulls}</span>
    </button>
  );
}

export default function SixNimmtPage() {
  const id = useRef('');
  const [nick, setNick] = useState('');
  const [screen, setScreen] = useState<'entry' | 'lobby' | 'game'>('entry');
  const [roomCode, setRoomCode] = useState<string | null>(null);
  const [endMode, setEndMode] = useState<'POINTS' | 'HANDS'>('POINTS');
  const [targetHands, setTargetHands] = useState(4);
  const [joinCode, setJoinCode] = useState('');
  const [rooms, setRooms] = useState<Room[]>([]);
  const [ss, setSs] = useState<State | null>(null);
  const [botLevel, setBotLevel] = useState('NORMAL');
  const [sel, setSel] = useState<number | null>(null);
  const roomRef = useRef<string | null>(null); roomRef.current = roomCode;

  useEffect(() => { id.current = cid(); try { setNick(localStorage.getItem('arcade_nick') || ''); } catch {} }, []);

  const loadRooms = useCallback(async () => { try { setRooms(await api(`/api/v1/sixnimmt/rooms`)); } catch {} }, []);
  useEffect(() => { if (screen === 'entry') { loadRooms(); const t = setInterval(loadRooms, 3000); return () => clearInterval(t); } }, [screen, loadRooms]);

  // 폴링
  useEffect(() => {
    if (screen === 'entry' || !roomCode) return;
    let alive = true;
    const poll = async () => {
      try {
        const s = await api<State>(`/api/v1/sixnimmt/me?roomCode=${roomCode}&clientId=${id.current}`);
        if (!alive) return;
        setSs(s);
        if (s.phase !== 'LOBBY' && screen === 'lobby') setScreen('game');
        if (s.phase === 'LOBBY' && screen === 'game') setScreen('lobby');
      } catch {}
    };
    const t = setInterval(poll, 1000); poll();
    return () => { alive = false; clearInterval(t); };
  }, [screen, roomCode]);

  // 새 트릭 시작되면 선택 초기화
  useEffect(() => { if (ss?.myTurnToPlay) setSel(null); }, [ss?.handIndex, ss?.myTurnToPlay]);

  const create = async () => {
    const n = nick.trim(); if (!n) return; try { localStorage.setItem('arcade_nick', n); } catch {}
    try {
      const res = await api<{ roomCode: string; state: State }>(`/api/v1/sixnimmt/new?clientId=${id.current}`, { method: 'POST', body: JSON.stringify({ nick: n, endMode, targetHands }) });
      setRoomCode(res.roomCode); setSs(res.state); setScreen('lobby');
    } catch (e: any) { alert(e?.message || '방 생성 실패'); }
  };
  const join = async (code: string) => {
    const n = nick.trim(); if (!n || !code) return; try { localStorage.setItem('arcade_nick', n); } catch {}
    try {
      const s = await api<State>(`/api/v1/sixnimmt/join?roomCode=${code}&clientId=${id.current}`, { method: 'POST', body: JSON.stringify({ nick: n }) });
      setRoomCode(code.toUpperCase()); setSs(s); setScreen('lobby');
    } catch (e: any) { alert(e?.message || '참가 실패'); }
  };
  const addBot = async () => { try { setSs(await api(`/api/v1/sixnimmt/add-bot?roomCode=${roomCode}&clientId=${id.current}&level=${botLevel}`, { method: 'POST', body: '{}' })); } catch (e: any) { alert(e?.message); } };
  const startMatch = async () => { try { setSs(await api(`/api/v1/sixnimmt/start?roomCode=${roomCode}&clientId=${id.current}`, { method: 'POST', body: '{}' })); setScreen('game'); } catch (e: any) { alert(e?.message); } };
  const playCard = async () => { if (sel == null) return; try { setSs(await api(`/api/v1/sixnimmt/play?roomCode=${roomCode}&clientId=${id.current}&card=${sel}`, { method: 'POST', body: '{}' })); setSel(null); } catch (e: any) { alert(e?.message); } };
  const takeRow = async (row: number) => { try { setSs(await api(`/api/v1/sixnimmt/take-row?roomCode=${roomCode}&clientId=${id.current}&row=${row}`, { method: 'POST', body: '{}' })); } catch (e: any) { alert(e?.message); } };
  const leave = async () => { const rc = roomRef.current; if (rc) { try { await api(`/api/v1/sixnimmt/leave?roomCode=${rc}&clientId=${id.current}`, { method: 'POST', body: '{}' }); } catch {} } setScreen('entry'); setRoomCode(null); setSs(null); };

  const home = <Link href="/" aria-label="홈으로" className="text-lg leading-none text-slate-500 hover:text-slate-800 dark:hover:text-slate-100">🏠</Link>;
  const ended = ss?.phase === 'ENDED';
  const sortedPlayers = ss ? [...ss.players].filter((p) => !p.left).sort((a, b) => a.penalty - b.penalty) : [];

  return (
    <main className="min-h-screen flex flex-col items-center p-3 max-w-2xl mx-auto w-full text-slate-800 dark:text-slate-100">
      <div className="w-full flex items-center justify-between mb-3">
        <div className="flex items-center gap-2">{home}<h1 className="text-xl font-bold">🐮 젝스님트</h1></div>
        {roomCode && <button onClick={leave} className="text-xs px-2 py-1 rounded bg-slate-700 text-slate-100">나가기</button>}
      </div>

      {/* 입장 */}
      {screen === 'entry' && (
        <div className="w-full space-y-4">
          <input value={nick} onChange={(e) => setNick(e.target.value)} maxLength={16} placeholder="닉네임 (필수)"
            className={`w-full border bg-transparent rounded-lg px-3 py-2 focus:outline-none focus:border-fuchsia-500 ${nick.trim() ? 'border-slate-300 dark:border-slate-600' : 'border-fuchsia-400'}`} />
          <div className="grid grid-cols-2 gap-2">
            {(['POINTS', 'HANDS'] as const).map((m) => (
              <button key={m} onClick={() => setEndMode(m)} className={`rounded-xl border-2 p-3 text-left ${endMode === m ? 'border-fuchsia-500 bg-fuchsia-500/10' : 'border-slate-300 dark:border-slate-600'}`}>
                <p className="font-bold">{m === 'POINTS' ? '🎯 66점' : '🔢 판 수'}</p>
                <p className="text-xs text-slate-400">{m === 'POINTS' ? '누가 66점 넘으면 종료' : 'N판만 하고 종료'}</p>
              </button>
            ))}
          </div>
          {endMode === 'HANDS' && (
            <div className="flex items-center gap-2 text-sm"><span>판 수</span>
              <input type="number" min={1} max={20} value={targetHands} onChange={(e) => setTargetHands(Math.max(1, Math.min(20, Number(e.target.value) || 1)))} className="w-20 border border-slate-300 dark:border-slate-600 bg-transparent rounded px-2 py-1" /></div>
          )}
          <button onClick={create} disabled={!nick.trim()} className="w-full bg-fuchsia-600 text-white font-bold py-3 rounded-lg disabled:opacity-40">방 만들기</button>
          <div className="flex gap-2">
            <input value={joinCode} onChange={(e) => setJoinCode(e.target.value.toUpperCase())} maxLength={4} placeholder="코드" className="flex-1 border border-slate-300 dark:border-slate-600 bg-transparent rounded-lg px-3 py-2 uppercase" />
            <button onClick={() => join(joinCode)} disabled={!nick.trim() || !joinCode} className="px-5 bg-slate-700 text-white font-bold rounded-lg disabled:opacity-40">참가</button>
          </div>
          <div className="rounded-xl border border-slate-200 dark:border-slate-700 p-3">
            <p className="text-sm font-bold text-slate-500 mb-2">🎮 열린 방</p>
            {rooms.length === 0 ? <p className="text-slate-400 text-sm text-center py-2">방이 없어요</p> : rooms.map((r) => (
              <button key={r.code} onClick={() => join(r.code)} disabled={!nick.trim()} className="w-full flex justify-between text-sm py-1.5 px-2 rounded hover:bg-fuchsia-500/10 disabled:opacity-40">
                <span className="font-bold">{r.code} · {r.host}</span>
                <span className="text-slate-400">{r.status === 'WAITING' ? '모집중' : r.status === 'PLAYING' ? '진행중' : '종료'} · {r.playerCount}명</span>
              </button>
            ))}
          </div>
          <details className="rounded-xl border border-slate-200 dark:border-slate-700 p-3 text-sm text-slate-500 dark:text-slate-300">
            <summary className="font-bold cursor-pointer">📖 규칙</summary>
            <div className="mt-2 space-y-1">
              <p>· 모두 동시에 카드 1장을 내면, <b>낮은 숫자부터</b> 4줄 중 자기보다 작은 끝에 가장 가까운 줄에 붙어요.</p>
              <p>· 줄의 <b>6번째</b>가 되면 그 줄 5장을 <b>벌점(🐮)으로 회수</b>하고 내 카드가 새 시작이 돼요.</p>
              <p>· 모든 줄보다 낮은 카드를 내면 <b>줄 하나를 골라</b> 가져가요(보통 벌점 적은 줄).</p>
              <p>· 🐮 벌점: 55=7, 11의배수=5, 10의배수=3, 끝자리5=2, 그외=1.</p>
              <p>· <b>벌점을 적게</b> 먹는 사람이 승리!</p>
            </div>
          </details>
        </div>
      )}

      {/* 로비 */}
      {screen === 'lobby' && ss && (
        <div className="w-full space-y-4">
          <div className="text-center">
            <p className="text-sm text-slate-400">방 코드</p>
            <p className="text-3xl font-extrabold tracking-widest text-fuchsia-500">{roomCode}</p>
            <p className="text-xs text-slate-400">{ss.endMode === 'POINTS' ? '66점 종료' : `${ss.targetHands}판`} · {ss.players.length}/10명</p>
          </div>
          <div className="rounded-xl border border-slate-200 dark:border-slate-700 p-3 space-y-1">
            {ss.players.map((p, i) => (
              <div key={i} className="flex justify-between text-sm px-1 py-0.5">
                <span className="font-bold">{p.host ? '👑 ' : ''}{p.bot ? '🤖 ' : ''}{p.name}{p.me ? ' (나)' : ''}</span>
                <span className="text-slate-400">{p.bot ? (p.botLevel === 'EASY' ? '초급' : p.botLevel === 'HARD' ? '고급' : '중급') : ''}</span>
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
              <button onClick={startMatch} disabled={ss.players.length < 2} className="w-full bg-fuchsia-600 text-white font-bold py-3 rounded-lg disabled:opacity-40">시작하기</button>
            </div>
          ) : <p className="text-center text-sm text-slate-400">방장이 시작하기를 기다리는 중…</p>}
        </div>
      )}

      {/* 게임 */}
      {screen === 'game' && ss && (
        <div className="w-full space-y-3">
          {/* 상단 상태 */}
          <div className="flex items-center justify-between text-sm">
            <span className="text-slate-400">{ss.endMode === 'POINTS' ? '66점 종료' : `${ss.handIndex + 1}/${ss.targetHands}판`}</span>
            <span className="font-bold text-fuchsia-500">
              {ended ? '게임 종료' : ss.iAmChooser ? '줄을 고르세요!' : ss.myTurnToPlay ? '카드를 내세요' : ss.chooserName ? `${ss.chooserName}님이 줄 고르는 중` : '상대 대기 중…'}
            </span>
          </div>

          {ended && (
            <div className="text-center rounded-xl border-2 border-fuchsia-500 bg-fuchsia-500/10 px-6 py-3">
              <p className="text-lg font-bold">🏆 {ss.winner} 승리!</p>
              <p className="text-sm text-slate-300">최소 벌점 달성</p>
            </div>
          )}

          {/* 4줄 */}
          <div className="space-y-1.5">
            {ss.rows.map((row, ri) => {
              const canTake = ss.iAmChooser;
              return (
                <button key={ri} onClick={canTake ? () => takeRow(ri) : undefined} disabled={!canTake}
                  className={`w-full flex items-center gap-1 p-1.5 rounded-lg border ${canTake ? 'border-fuchsia-500 bg-fuchsia-500/5 active:scale-[0.99]' : 'border-slate-200 dark:border-slate-700'}`}>
                  <span className="text-[10px] text-slate-400 w-4 shrink-0">{ri + 1}</span>
                  <div className="flex gap-1 overflow-x-auto">
                    {row.map((c, ci) => <Card key={ci} c={c} />)}
                    {Array.from({ length: 5 - row.length }).map((_, k) => <div key={`e${k}`} className="shrink-0 rounded-md border-2 border-dashed border-slate-700/50" style={{ width: 40, height: 56 }} />)}
                  </div>
                  <span className="ml-auto text-xs font-bold shrink-0" style={{ color: bullColor(3) }}>🐮{row.reduce((a, c) => a + c.bulls, 0)}</span>
                </button>
              );
            })}
          </div>
          {ss.iAmChooser && <p className="text-xs text-center text-fuchsia-500">낼 카드가 모든 줄보다 낮아요 — 가져갈 줄을 탭하세요(벌점 적은 줄 추천).</p>}

          {/* 점수판 */}
          <div className="rounded-xl border border-slate-200 dark:border-slate-700 p-2 grid grid-cols-2 gap-x-3 gap-y-0.5 text-sm">
            {sortedPlayers.map((p, i) => (
              <div key={i} className="flex justify-between px-1">
                <span className={`truncate ${p.me ? 'font-bold text-fuchsia-500' : ''}`}>{p.bot ? '🤖' : ''}{p.name}{p.selected && !ended ? ' ✅' : ''}</span>
                <span className="font-bold">{p.penalty}{p.lastTook > 0 ? <span className="text-red-400 text-xs"> +{p.lastTook}</span> : null}</span>
              </div>
            ))}
          </div>

          {/* 내 손패 */}
          {!ended && (
            <div>
              <p className="text-xs text-slate-400 mb-1">내 손패 {ss.myTurnToPlay ? '(카드를 골라 내기)' : ss.myHand.length === 0 ? '' : '(제출 완료 — 대기 중)'}</p>
              <div className="flex gap-1 overflow-x-auto pb-1">
                {ss.myHand.map((c) => (
                  <Card key={c.card} c={c} sel={sel === c.card} onClick={ss.myTurnToPlay ? () => setSel(c.card) : undefined} dim={!ss.myTurnToPlay} />
                ))}
              </div>
              {ss.myTurnToPlay && (
                <button onClick={playCard} disabled={sel == null} className="mt-2 w-full bg-fuchsia-600 text-white font-bold py-2.5 rounded-lg disabled:opacity-40">
                  {sel == null ? '카드를 선택하세요' : `${sel} 내기`}
                </button>
              )}
            </div>
          )}
        </div>
      )}

      {roomCode && <RoomChat game="sixnimmt" roomCode={roomCode} clientId={id.current} nick={nick} />}
    </main>
  );
}
