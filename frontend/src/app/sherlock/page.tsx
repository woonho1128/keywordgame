'use client';

import Link from 'next/link';
import { useCallback, useEffect, useRef, useState } from 'react';
import { api } from '@/lib/api';
import RoomChat from '@/components/RoomChat';

type PlayerView = { seat: number; name: string; bot: boolean; host: boolean; me: boolean; alive: boolean; left: boolean; cardCount: number };
type CharView = { id: number; name: string; items: number[] };
type SeatCount = { seat: number; count: number };
type Clue = { askerSeat: number; askerName: string; target: number; item: number; results: SeatCount[] };
type State = {
  phase: string; turnSec: number; isHost: boolean; joined: boolean;
  players: PlayerView[]; deck: CharView[]; myCards: number[]; items: string[];
  turnSeat: number; turnName: string | null; myTurn: boolean; mySeat: number;
  clues: Clue[]; lastAction: string | null; log: string[];
  winnerSeat: number; winnerLabel: string | null; deadline: number; serverNow: number;
};
type Room = { code: string; status: string; playerCount: number; host: string };

const PCOL = ['#3b82f6', '#ef4444', '#22c55e', '#eab308', '#a855f7', '#ec4899', '#14b8a6', '#f97316', '#0ea5e9', '#84cc16'];
const emo = (label: string) => label.split(' ')[0];

function getClientId(): string {
  if (typeof window === 'undefined') return '';
  try {
    let id = localStorage.getItem('sherlock_client_id') || '';
    if (!id) { id = crypto?.randomUUID?.() ?? `s_${Date.now()}_${Math.random().toString(36).slice(2)}`; localStorage.setItem('sherlock_client_id', id); }
    return id;
  } catch { return `s_${Math.random().toString(36).slice(2)}`; }
}

export default function SherlockPage() {
  const cidRef = useRef('');
  if (typeof window !== 'undefined' && !cidRef.current) cidRef.current = getClientId();
  const [nick, setNick] = useState('');
  const [screen, setScreen] = useState<'entry' | 'lobby' | 'game'>('entry');
  const [roomCode, setRoomCode] = useState<string | null>(null);
  const [joinCode, setJoinCode] = useState('');
  const [rooms, setRooms] = useState<Room[]>([]);
  const [ss, setSs] = useState<State | null>(null);
  const [botLevel, setBotLevel] = useState('NORMAL');
  const [turnSec, setTurnSec] = useState(60);
  const [remaining, setRemaining] = useState(0);
  const [mode, setMode] = useState<'all' | 'one' | 'accuse' | null>(null);
  const [target, setTarget] = useState<number | null>(null);
  const [notes, setNotes] = useState<Record<number, 'x' | 'star'>>({});
  const [cellNotes, setCellNotes] = useState<Record<string, 'o' | 'x'>>({});
  const cycleCell = (charId: number, item: number) => setCellNotes((m) => { const k = `${charId}:${item}`; const cur = m[k]; const nn = { ...m }; if (cur === 'o') nn[k] = 'x'; else if (cur === 'x') delete nn[k]; else nn[k] = 'o'; return nn; });

  const roomRef = useRef<string | null>(null); roomRef.current = roomCode;
  const offsetRef = useRef(0);
  const cid = () => encodeURIComponent(cidRef.current);

  useEffect(() => { try { setNick(localStorage.getItem('arcade_nick') || ''); } catch {} }, []);

  const loadRooms = useCallback(async () => { try { setRooms(await api(`/api/v1/sherlock/rooms`)); } catch {} }, []);
  useEffect(() => { if (screen === 'entry') { loadRooms(); const t = setInterval(loadRooms, 3000); return () => clearInterval(t); } }, [screen, loadRooms]);

  useEffect(() => {
    if (screen === 'entry' || !roomCode) return;
    let alive = true;
    const poll = async () => {
      try {
        const s = await api<State>(`/api/v1/sherlock/me?roomCode=${roomCode}&clientId=${cid()}`);
        if (!alive) return;
        offsetRef.current = s.serverNow - Date.now(); setSs(s);
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

  // 내 카드는 범인 아님 → 자동 제외 표시
  useEffect(() => { if (ss?.myCards?.length) setNotes((prev) => { const n = { ...prev }; ss.myCards.forEach((c) => { if (!n[c]) n[c] = 'x'; }); return n; }); }, [ss?.myCards?.join(',')]);

  const saveNick = (n: string) => { try { localStorage.setItem('arcade_nick', n); } catch {} };
  const create = async () => {
    const n = nick.trim(); if (!n) return; saveNick(n);
    try { const res = await api<{ roomCode: string; state: State }>(`/api/v1/sherlock/new?clientId=${cid()}`, { method: 'POST', body: JSON.stringify({ nick: n, turnSec }) }); setRoomCode(res.roomCode); setSs(res.state); setScreen('lobby'); }
    catch (e: any) { alert(e?.message || '방 생성 실패'); }
  };
  const join = async (code: string, nickOverride?: string) => {
    const n = (nickOverride ?? nick).trim(); if (!n || !code) return; setNick(n); saveNick(n);
    try { const s = await api<State>(`/api/v1/sherlock/join?roomCode=${code}&clientId=${cid()}`, { method: 'POST', body: JSON.stringify({ nick: n }) }); setRoomCode(code.toUpperCase()); setSs(s); setScreen(s.phase === 'LOBBY' ? 'lobby' : 'game'); }
    catch (e: any) { alert(e?.message || '참가 실패'); }
  };
  useEffect(() => {
    const j = new URLSearchParams(window.location.search).get('join');
    if (!j) return;
    let n = ''; try { n = (localStorage.getItem('arcade_nick') || '').trim(); } catch {}
    if (!n) return;
    join(j.toUpperCase(), n);
    try { window.history.replaceState({}, '', '/sherlock'); } catch {}
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, []);

  const addBot = async () => { try { setSs(await api(`/api/v1/sherlock/add-bot?roomCode=${roomCode}&clientId=${cid()}&level=${botLevel}`, { method: 'POST', body: '{}' })); } catch (e: any) { alert(e?.message); } };
  const startMatch = async () => { try { setSs(await api(`/api/v1/sherlock/start?roomCode=${roomCode}&clientId=${cid()}`, { method: 'POST', body: '{}' })); setScreen('game'); } catch (e: any) { alert(e?.message); } };
  const leave = async () => { const rc = roomRef.current; if (rc) { try { await api(`/api/v1/sherlock/leave?roomCode=${rc}&clientId=${cid()}`, { method: 'POST', body: '{}' }); } catch {} } setScreen('entry'); setRoomCode(null); setSs(null); };

  const askAll = async (item: number) => { setMode(null); try { setSs(await api(`/api/v1/sherlock/ask-all?roomCode=${roomCode}&clientId=${cid()}&item=${item}`, { method: 'POST', body: '{}' })); } catch (e: any) { alert(e?.message); } };
  const askOne = async (t: number, item: number) => { setMode(null); setTarget(null); try { setSs(await api(`/api/v1/sherlock/ask-one?roomCode=${roomCode}&clientId=${cid()}&target=${t}&item=${item}`, { method: 'POST', body: '{}' })); } catch (e: any) { alert(e?.message); } };
  const accuse = async (charId: number) => { if (!confirm(`${ss?.deck.find((d) => d.id === charId)?.name}을(를) 범인으로 지목할까요? (틀리면 탈락)`)) return; setMode(null); try { setSs(await api(`/api/v1/sherlock/accuse?roomCode=${roomCode}&clientId=${cid()}&charId=${charId}`, { method: 'POST', body: '{}' })); } catch (e: any) { alert(e?.message); } };

  const cycleNote = (id: number) => setNotes((n) => { const cur = n[id]; const nn = { ...n }; if (cur === 'x') nn[id] = 'star'; else if (cur === 'star') delete nn[id]; else nn[id] = 'x'; return nn; });

  const me = ss?.players.find((p) => p.me);
  const nameOf = (seat: number) => ss?.players.find((p) => p.seat === seat)?.name ?? `#${seat}`;
  const ended = ss?.phase === 'ENDED';

  return (
    <main className="min-h-screen flex flex-col items-center p-3 sm:p-5 max-w-6xl mx-auto w-full text-slate-800 dark:text-slate-100">
      <div className="w-full flex items-center gap-2 mb-3">
        <button onClick={leave} aria-label="나가기" className="text-lg leading-none text-slate-500 hover:text-slate-800 dark:hover:text-slate-100">🏠</button>
        <h1 className="text-xl sm:text-2xl font-extrabold">🔎 셜록13</h1>
        {ss && screen === 'game' && <span className="text-xs text-slate-400">방 {roomCode} · {ss.deck.length}명 중 범인 1명</span>}
      </div>

      {/* 입장 */}
      {screen === 'entry' && (
        <div className="w-full max-w-md space-y-4">
          <div className="rounded-2xl border border-slate-200 dark:border-slate-700 p-4 space-y-3">
            <input value={nick} onChange={(e) => setNick(e.target.value)} maxLength={16} placeholder="닉네임"
              className="w-full border border-slate-300 dark:border-slate-600 bg-transparent rounded-lg px-3 py-2" />
            <label className="flex items-center justify-between gap-2 text-sm">
              <span className="font-bold text-slate-600 dark:text-slate-300">턴 제한시간</span>
              <select value={turnSec} onChange={(e) => setTurnSec(Number(e.target.value))} className="border border-slate-300 dark:border-slate-600 bg-transparent rounded-lg px-3 py-1.5">
                {[20, 30, 45, 60, 90, 120].map((s) => <option key={s} value={s}>{s}초</option>)}
              </select>
            </label>
            <button onClick={create} disabled={!nick.trim()} className="w-full bg-indigo-600 text-white font-bold py-3 rounded-lg disabled:opacity-50">방 만들기 (2~10인)</button>
          </div>
          <div className="rounded-2xl border border-slate-200 dark:border-slate-700 p-4 space-y-2">
            <p className="text-sm font-bold text-slate-600 dark:text-slate-300">코드로 참가</p>
            <div className="flex gap-2">
              <input value={joinCode} onChange={(e) => setJoinCode(e.target.value.toUpperCase())} maxLength={4} placeholder="코드"
                className="flex-1 border border-slate-300 dark:border-slate-600 bg-transparent rounded-lg px-3 py-2 uppercase tracking-widest font-bold" />
              <button onClick={() => join(joinCode)} disabled={!nick.trim() || joinCode.length < 4} className="px-5 bg-slate-700 text-white font-bold rounded-lg disabled:opacity-40">참가</button>
            </div>
            {rooms.map((r) => (
              <button key={r.code} onClick={() => join(r.code)} disabled={!nick.trim()}
                className="w-full flex items-center justify-between text-sm px-3 py-2 rounded-lg border border-slate-200 dark:border-slate-700 hover:border-indigo-400 disabled:opacity-40">
                <span className="font-bold">{r.code}</span>
                <span className="text-slate-400">{r.host} · {r.playerCount}명 · {r.status === 'WAITING' ? '모집중' : r.status === 'PLAYING' ? '진행중' : '종료'}</span>
              </button>
            ))}
          </div>
        </div>
      )}

      {/* 로비 */}
      {screen === 'lobby' && ss && (
        <div className="w-full max-w-md space-y-3">
          <div className="rounded-2xl border border-slate-200 dark:border-slate-700 p-4">
            <p className="text-sm font-bold text-slate-600 dark:text-slate-300 mb-2">참가자 ({ss.players.length}/10)</p>
            <div className="space-y-1.5">
              {ss.players.map((pl) => (
                <div key={pl.seat} className="flex items-center gap-2 text-sm px-2 py-1.5 rounded-lg bg-slate-50 dark:bg-slate-800">
                  <span className="w-3.5 h-3.5 rounded-full" style={{ background: PCOL[pl.seat % PCOL.length] }} />
                  <span className="font-bold">{pl.name}</span>
                  {pl.bot && <span className="text-[10px] px-1.5 rounded bg-slate-200 dark:bg-slate-700">봇</span>}
                  {pl.host && <span className="text-[10px] text-indigo-500">방장</span>}
                </div>
              ))}
            </div>
            <p className="text-[11px] text-slate-400 mt-2">인원에 맞춰 캐릭터 {ss.players.length >= 2 ? 3 * ss.players.length + 1 : '?'}명(범인 1명) · 턴 제한 {ss.turnSec}초</p>
          </div>
          {ss.isHost ? (
            <div className="rounded-2xl border border-slate-200 dark:border-slate-700 p-4 space-y-2">
              <div className="flex gap-2">
                <select value={botLevel} onChange={(e) => setBotLevel(e.target.value)} className="flex-1 border border-slate-300 dark:border-slate-600 bg-transparent rounded-lg px-3 py-2">
                  <option value="EASY">봇 쉬움</option><option value="NORMAL">봇 보통</option><option value="HARD">봇 어려움</option>
                </select>
                <button onClick={addBot} disabled={ss.players.length >= 10} className="px-4 rounded-lg border border-slate-300 dark:border-slate-600 font-bold disabled:opacity-40">봇 추가</button>
              </div>
              <button onClick={startMatch} className="w-full bg-indigo-600 text-white font-extrabold py-3 rounded-lg">게임 시작</button>
            </div>
          ) : <p className="text-center text-sm text-slate-400">방장이 시작하기를 기다리는 중…</p>}
          <button onClick={leave} className="w-full text-sm text-slate-400 py-2">나가기</button>
        </div>
      )}

      {/* 게임 */}
      {screen === 'game' && ss && (
        <div className="w-full flex flex-col lg:flex-row gap-4">
          {/* 왼쪽: 추리판 */}
          <div className="flex-1 min-w-0 space-y-3">
            {/* 상단 상태 */}
            <div className="rounded-xl border border-slate-200 dark:border-slate-700 p-3 flex flex-wrap items-center gap-2">
              {ended ? (
                <span className="font-extrabold text-lg">🏆 {ss.winnerLabel} 승리!{ss.winnerSeat < 0 ? '' : ''}</span>
              ) : (
                <>
                  <span className="w-3.5 h-3.5 rounded-full" style={{ background: PCOL[ss.turnSeat % PCOL.length] }} />
                  <b className={ss.myTurn ? 'text-indigo-600 dark:text-indigo-300' : ''}>{ss.myTurn ? '내 차례 — 조사 또는 지목!' : `${ss.turnName} 차례`}</b>
                  {remaining > 0 && <span className="ml-auto text-xs text-slate-400">{remaining}s</span>}
                </>
              )}
            </div>

            {/* 아이템 범례 */}
            <div className="rounded-xl border border-slate-200 dark:border-slate-700 p-2.5 flex flex-wrap gap-x-3 gap-y-1 text-xs">
              {ss.items.map((it, i) => <span key={i} className="whitespace-nowrap">{it}</span>)}
            </div>

            {/* 내 손패 */}
            <div className="rounded-xl border border-slate-200 dark:border-slate-700 p-3">
              <p className="text-sm font-bold text-slate-600 dark:text-slate-300 mb-2">내 카드 (범인 아님)</p>
              <div className="flex flex-wrap gap-2">
                {ss.myCards.map((cid2) => { const c = ss.deck.find((d) => d.id === cid2); if (!c) return null; return (
                  <div key={cid2} className="rounded-lg border-2 border-emerald-400 bg-emerald-50 dark:bg-emerald-500/10 px-2.5 py-1.5 text-center">
                    <div className="text-sm font-extrabold">{c.name}</div>
                    <div className="text-base leading-none">{c.items.map((i) => emo(ss.items[i])).join('')}</div>
                  </div>
                ); })}
              </div>
            </div>

            {/* 추리 격자판: 행=인물 / 열=아이템, 칸 탭 ⭕→❌→해제, 이름 탭 제외/의심 */}
            <div className="rounded-xl border border-slate-200 dark:border-slate-700 p-2">
              <p className="text-sm font-bold text-slate-600 dark:text-slate-300 mb-1 px-1">추리판 · 칸 탭 ⭕/❌ · 이름 탭 제외(✗)/의심(⭐)</p>
              <div className="overflow-x-auto">
                <table className="border-collapse text-center select-none">
                  <thead>
                    <tr>
                      <th className="sticky left-0 z-10 bg-white dark:bg-slate-900 px-1 py-1 text-[10px] text-slate-400 text-left">용의자</th>
                      {ss.items.map((it, i) => <th key={i} className="w-7 sm:w-8 py-1 text-base" title={it}>{emo(it)}</th>)}
                    </tr>
                  </thead>
                  <tbody>
                    {ss.deck.map((c) => {
                      const nt = notes[c.id]; const mine = ss.myCards.includes(c.id);
                      return (
                        <tr key={c.id} className={nt === 'x' ? 'opacity-40' : ''}>
                          <td onClick={() => cycleNote(c.id)}
                            className={`sticky left-0 z-10 bg-white dark:bg-slate-900 text-left text-[11px] font-bold px-1 py-0.5 whitespace-nowrap cursor-pointer max-w-[92px] truncate ${nt === 'star' ? 'text-amber-500' : ''}`}>
                            {nt === 'star' ? '⭐' : nt === 'x' ? '✗' : mine ? '🟢' : ''}{c.name}
                          </td>
                          {ss.items.map((_, i) => {
                            const has = c.items.includes(i);
                            const cn = cellNotes[`${c.id}:${i}`];
                            return (
                              <td key={i} onClick={() => cycleCell(c.id, i)}
                                className={`w-7 h-7 sm:w-8 sm:h-8 border border-slate-200 dark:border-slate-700 cursor-pointer text-sm ${has ? 'bg-slate-100 dark:bg-slate-700/40' : ''}`}>
                                {cn === 'o' ? '⭕' : cn === 'x' ? '❌' : has ? <span className="opacity-40 text-[11px]">{emo(ss.items[i])}</span> : ''}
                              </td>
                            );
                          })}
                        </tr>
                      );
                    })}
                  </tbody>
                </table>
              </div>
              <p className="text-[11px] text-slate-400 mt-1 px-1">🟢=내 카드 · 옅은 칸=그 인물이 실제로 가진 아이템(참조)</p>
            </div>
          </div>

          {/* 오른쪽: 행동/플레이어/단서 */}
          <div className="w-full lg:w-80 shrink-0 space-y-3">
            {/* 행동 */}
            {!ended && ss.myTurn && (
              <div className="rounded-xl border border-slate-200 dark:border-slate-700 p-3 space-y-2">
                {mode === null && (
                  <div className="grid grid-cols-1 gap-2">
                    <button onClick={() => setMode('all')} className="bg-indigo-600 text-white font-bold py-2.5 rounded-lg">🔎 전체 조사</button>
                    <button onClick={() => { setMode('one'); setTarget(null); }} className="border border-slate-300 dark:border-slate-600 font-bold py-2.5 rounded-lg">🎯 한 명 조사</button>
                    <button onClick={() => setMode('accuse')} className="border-2 border-rose-500 text-rose-600 dark:text-rose-300 font-bold py-2.5 rounded-lg">⚖️ 범인 지목</button>
                  </div>
                )}
                {mode === 'all' && (<>
                  <p className="text-sm font-bold">전체에게 물을 아이템 선택</p>
                  <div className="grid grid-cols-2 gap-1.5">{ss.items.map((it, i) => <button key={i} onClick={() => askAll(i)} className="border border-slate-300 dark:border-slate-600 rounded-lg py-2 text-sm">{it}</button>)}</div>
                  <button onClick={() => setMode(null)} className="w-full text-xs text-slate-400 py-1">취소</button>
                </>)}
                {mode === 'one' && (<>
                  {target === null ? (<>
                    <p className="text-sm font-bold">조사할 상대 선택</p>
                    <div className="grid grid-cols-2 gap-1.5">{ss.players.filter((p) => !p.me && p.alive && !p.left).map((p) => <button key={p.seat} onClick={() => setTarget(p.seat)} className="border border-slate-300 dark:border-slate-600 rounded-lg py-2 text-sm font-bold">{p.name}</button>)}</div>
                  </>) : (<>
                    <p className="text-sm font-bold">{nameOf(target)}에게 물을 아이템</p>
                    <div className="grid grid-cols-2 gap-1.5">{ss.items.map((it, i) => <button key={i} onClick={() => askOne(target, i)} className="border border-slate-300 dark:border-slate-600 rounded-lg py-2 text-sm">{it}</button>)}</div>
                  </>)}
                  <button onClick={() => { setMode(null); setTarget(null); }} className="w-full text-xs text-slate-400 py-1">취소</button>
                </>)}
                {mode === 'accuse' && (<>
                  <p className="text-sm font-bold text-rose-600 dark:text-rose-300">범인으로 지목할 용의자 (틀리면 탈락!)</p>
                  <div className="grid grid-cols-2 gap-1.5 max-h-64 overflow-auto">
                    {ss.deck.filter((c) => !ss.myCards.includes(c.id)).map((c) => <button key={c.id} onClick={() => accuse(c.id)} className="border border-rose-300 rounded-lg py-1.5 text-sm font-bold">{c.name}</button>)}
                  </div>
                  <button onClick={() => setMode(null)} className="w-full text-xs text-slate-400 py-1">취소</button>
                </>)}
              </div>
            )}
            {ended && <button onClick={leave} className="w-full bg-indigo-600 text-white font-bold py-2.5 rounded-lg">나가기</button>}

            {/* 플레이어 */}
            <div className="rounded-xl border border-slate-200 dark:border-slate-700 p-3">
              <p className="text-sm font-bold text-slate-600 dark:text-slate-300 mb-2">플레이어</p>
              <div className="space-y-1">
                {ss.players.map((p) => (
                  <div key={p.seat} className={`flex items-center gap-2 text-sm px-2 py-1 rounded-lg ${ss.turnSeat === p.seat && !ended ? 'bg-indigo-50 dark:bg-indigo-500/10' : ''} ${!p.alive ? 'opacity-40 line-through' : ''}`}>
                    <span className="w-3 h-3 rounded-full" style={{ background: PCOL[p.seat % PCOL.length] }} />
                    <span className="font-bold">{p.bot ? '🤖' : ''}{p.name}{p.me ? '*' : ''}</span>
                    <span className="ml-auto text-xs text-slate-400">🂠{p.cardCount}</span>
                  </div>
                ))}
              </div>
            </div>

            {/* 단서 로그 */}
            <div className="rounded-xl border border-slate-200 dark:border-slate-700 p-3">
              <p className="text-sm font-bold text-slate-600 dark:text-slate-300 mb-2">🔍 단서</p>
              <div className="space-y-1 text-xs max-h-72 overflow-y-auto">
                {[...ss.clues].reverse().map((c, i) => (
                  <div key={i} className="border-b border-slate-100 dark:border-slate-800 pb-1">
                    <span className="font-bold" style={{ color: PCOL[c.askerSeat % PCOL.length] }}>{c.askerName}</span>
                    <span className="text-slate-400"> · {emo(ss.items[c.item])}{ss.items[c.item].split(' ')[1]} {c.target < 0 ? '전체' : `→ ${nameOf(c.target)}`}</span>
                    <div className="flex flex-wrap gap-x-2">{c.results.map((r) => <span key={r.seat}><b style={{ color: PCOL[r.seat % PCOL.length] }}>{nameOf(r.seat)}</b> {r.count}</span>)}</div>
                  </div>
                ))}
                {ss.clues.length === 0 && <p className="text-slate-400">아직 조사 없음</p>}
              </div>
            </div>
          </div>
        </div>
      )}

      {roomCode && screen !== 'entry' && <RoomChat game="sherlock" roomCode={roomCode} clientId={cidRef.current} nick={nick} />}
    </main>
  );
}
