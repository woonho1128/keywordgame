'use client';

import { useCallback, useEffect, useRef, useState } from 'react';
import { api } from '@/lib/api';
import RoomChat from '@/components/RoomChat';
import DrawCanvas, { DrawCanvasHandle } from '@/components/DrawCanvas';

type PlayerView = { seat: number; nick: string; submitted: boolean };
type StepView = { type: string; content: string; authorNick: string };
type AlbumView = { ownerNick: string; steps: StepView[] };
type GuessView = { nick: string; text: string; correct: boolean };
type ScoreView = { seat: number; nick: string; score: number };
type RoomSummary = { code: string; status: string; playerCount: number; host: string };

type GState = {
  status: 'NOT_STARTED' | 'LOBBY' | 'PLAYING' | 'REVEAL';
  mode: string; topicMode: string; serverNow: number;
  isHost: boolean; joined: boolean; seat: number; nick: string | null;
  players: PlayerView[];
  round: number; totalRounds: number;
  taskType: string | null; promptText: string | null; promptImage: string | null;
  mySubmitted: boolean; submittedCount: number; deadline: number;
  albums: AlbumView[]; playerCount: number; version: number;
  // 캐치마인드
  drawerSeat: number; amDrawer: boolean; myWord: string | null; snapshot: string | null;
  guesses: GuessView[]; scores: ScoreView[]; lastAnswer: string | null; iGuessedCorrect: boolean;
};

const CLIENT_ID_KEY = 'gartic_client_id';
const NICK_KEY = 'gartic_nick';
const ROOM_KEY = 'gartic_room';

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

export default function GarticPage() {
  const [clientId, setClientId] = useState('');
  const [st, setSt] = useState<GState | null>(null);
  const [error, setError] = useState<string | null>(null);
  const [busy, setBusy] = useState(false);

  const [roomCode, setRoomCode] = useState<string | null>(null);
  const [rooms, setRooms] = useState<RoomSummary[]>([]);
  const roomRef = useRef<string | null>(null);

  const [nick, setNick] = useState('');
  const [showCreate, setShowCreate] = useState(false);
  const [mode, setMode] = useState('GARTIC');
  const [topicMode, setTopicMode] = useState('FREE');
  const [showAdv, setShowAdv] = useState(false);
  const [roundSec, setRoundSec] = useState(60);
  const [writeSec, setWriteSec] = useState(60);
  const [drawSec, setDrawSec] = useState(150);
  const [showRules, setShowRules] = useState(false);
  const [showAdmin, setShowAdmin] = useState(false);
  const [adminInput, setAdminInput] = useState('');

  const [textInput, setTextInput] = useState('');
  const [revealIdx, setRevealIdx] = useState(0);
  const [, setNowTick] = useState(0);
  const canvasRef = useRef<DrawCanvasHandle>(null);
  const chatRef = useRef<HTMLDivElement>(null);
  const cidRef = useRef('');
  const clockOffset = useRef(0);
  const taskKeyRef = useRef('');

  const changeRoom = useCallback((code: string | null) => {
    roomRef.current = code;
    setRoomCode(code);
    try { if (code) localStorage.setItem(ROOM_KEY, code); else localStorage.removeItem(ROOM_KEY); } catch {}
  }, []);

  const poll = useCallback(async () => {
    const code = roomRef.current;
    const c = cidRef.current;
    if (!code) { try { setRooms(await api<RoomSummary[]>('/api/v1/drawgame/rooms')); } catch {} return; }
    try {
      const res = await api<GState>(`/api/v1/drawgame/me?roomCode=${code}&clientId=${encodeURIComponent(c)}`);
      if (res.status === 'NOT_STARTED') { changeRoom(null); setSt(null); }
      else { clockOffset.current = res.serverNow - Date.now(); setSt(res); }
    } catch { /* ignore */ }
  }, [changeRoom]);

  useEffect(() => {
    const id = getClientId(); setClientId(id); cidRef.current = id;
    try {
      setNick(localStorage.getItem(NICK_KEY) || '');
      const saved = localStorage.getItem(ROOM_KEY);
      if (saved) { roomRef.current = saved; setRoomCode(saved); }
    } catch {}
    poll();
    const t = setInterval(poll, 1500);
    const tk = setInterval(() => setNowTick((n) => n + 1), 500);
    return () => { clearInterval(t); clearInterval(tk); };
  }, [poll]);

  // 새 과제로 넘어가면 입력/캔버스 초기화
  useEffect(() => {
    if (!st) return;
    const key = `${st.status}-${st.round}`;
    if (key !== taskKeyRef.current) {
      taskKeyRef.current = key;
      setTextInput('');
      setTimeout(() => canvasRef.current?.clear(), 0);
    }
  }, [st]);


  const cid = () => encodeURIComponent(clientId);
  const rp = () => `roomCode=${roomCode}&clientId=${cid()}`;
  const saveNick = (n: string) => { try { localStorage.setItem(NICK_KEY, n); } catch {} };

  const post = useCallback(async (path: string, body?: unknown) => {
    setBusy(true); setError(null);
    try { return await api<GState>(path, { method: 'POST', body: body ? JSON.stringify(body) : undefined }); }
    catch (e) { setError(e instanceof Error ? e.message : '오류가 발생했습니다'); return null; }
    finally { setBusy(false); }
  }, []);

  const handleCreate = async () => {
    const n = nick.trim();
    if (!n) return setError('닉네임을 입력하세요');
    saveNick(n);
    setBusy(true); setError(null);
    try {
      const res = await api<{ roomCode: string; state: GState }>(
        `/api/v1/drawgame/new?clientId=${cid()}`,
        { method: 'POST', body: JSON.stringify({ nick: n, mode, topicMode, roundSec, writeSec, drawSec }) });
      changeRoom(res.roomCode); setSt(res.state); setShowCreate(false);
    } catch (e) { setError(e instanceof Error ? e.message : '방 생성 실패'); } finally { setBusy(false); }
  };
  const handleJoin = async () => {
    const n = nick.trim();
    if (!n) return setError('닉네임을 입력하세요');
    saveNick(n);
    const r = await post(`/api/v1/drawgame/join?${rp()}`, { nick: n });
    if (r) setSt(r);
  };
  const handleStart = async () => { const r = await post(`/api/v1/drawgame/start?${rp()}`); if (r) setSt(r); };
  const submit = async (type: string, content: string) => {
    const r = await post(`/api/v1/drawgame/submit?${rp()}`, { type, content });
    if (r) setSt(r);
  };
  const handleSubmitText = () => { if (!textInput.trim()) return setError('내용을 입력하세요'); submit('TEXT', textInput.trim()); };
  const handleSubmitDraw = () => { const url = canvasRef.current?.toDataURL(); if (url) submit('IMAGE', url); };

  // 캐치마인드: 그리는 사람이 스냅샷 push
  const pushSnapshot = useCallback(async () => {
    const code = roomRef.current; if (!code) return;
    const url = canvasRef.current?.toDataURL(); if (!url) return;
    try { await api(`/api/v1/drawgame/snapshot?roomCode=${code}&clientId=${cid()}`, { method: 'POST', body: JSON.stringify({ type: 'IMAGE', content: url }) }); } catch {}
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [clientId]);
  const handleGuess = async () => {
    const t = textInput.trim(); if (!t) return;
    setTextInput('');
    const r = await post(`/api/v1/drawgame/guess?${rp()}`, { type: 'TEXT', content: t });
    if (r) setSt(r);
  };

  // 캐치마인드: 그리는 사람이면 스냅샷 주기적 push(획 종료 + 2초 안전망)
  useEffect(() => {
    if (st?.status !== 'PLAYING' || st?.mode !== 'CATCHMIND' || !st?.amDrawer) return;
    const iv = setInterval(() => pushSnapshot(), 2000);
    return () => clearInterval(iv);
  }, [st?.status, st?.mode, st?.amDrawer, pushSnapshot]);

  // 채팅 새 글 오면 자동으로 맨 아래로
  useEffect(() => {
    const el = chatRef.current;
    if (el) el.scrollTop = el.scrollHeight;
  }, [st?.guesses?.length]);

  const handleAdminReset = async () => {
    const code = adminInput.trim(); if (!code) return;
    const res = await post(`/api/v1/drawgame/reset?code=${encodeURIComponent(code)}`);
    if (res) { setShowAdmin(false); setAdminInput(''); changeRoom(null); setSt(null); }
  };
  const handleCloseRoom = async (rc: string) => {
    const code = adminInput.trim();
    if (!code) { setError('관리자 코드를 먼저 입력하세요'); return; }
    if (!confirm(`${rc} 방을 삭제할까요?`)) return;
    try {
      await api<boolean>(`/api/v1/drawgame/close-room?code=${encodeURIComponent(code)}&roomCode=${rc}`, { method: 'POST' });
      setRooms((cur) => cur.filter((r) => r.code !== rc));
    } catch (e) { setError(e instanceof Error ? e.message : '방 삭제 실패'); }
  };

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
      ) : <button onClick={() => setShowAdmin(true)} className="text-xs text-gray-300 hover:text-gray-500">🔒 관리자</button>}
    </div>
  );

  const rulesHelp = (
    <div className="w-full rounded-xl border border-gray-200 p-4 text-sm space-y-2 text-left mt-3">
      <div className="flex items-center justify-between"><p className="font-bold">게임 방법 (갈틱폰)</p>
        <button onClick={() => setShowRules(false)} className="text-xs text-gray-400">닫기 ✕</button></div>
      <p className="text-gray-600">✍️ 시작 문장(또는 랜덤 제시어)에서 출발해요.</p>
      <p className="text-gray-600">🎨 받은 문장을 <b>그림</b>으로, 받은 그림을 <b>문장</b>으로 번갈아 표현하며 옆으로 넘겨요.</p>
      <p className="text-gray-600">🔁 인원수만큼 돌면 <b>결과 공개</b> — 원래 문장이 얼마나 엉뚱하게 바뀌었는지 감상!</p>
      <p className="text-[12px] text-gray-400">※ 각자 폰으로 접속해서 함께 하세요. 3~10명.</p>
    </div>
  );

  // ----- 방 목록 -----
  if (!roomCode) {
    return (
      <main className="min-h-screen flex flex-col items-center p-6 max-w-lg mx-auto w-full">
        <h1 className="text-2xl font-bold mb-6 self-start">🎨 그림 텔레폰</h1>
        {showCreate ? (
          <div className="w-full space-y-4">
            <input value={nick} onChange={(e) => setNick(e.target.value)} maxLength={16} placeholder="내 닉네임"
              className="w-full border border-gray-300 rounded-lg px-3 py-2 focus:outline-none focus:border-hit" />
            <div>
              <p className="text-sm font-bold text-gray-600 mb-1">모드</p>
              <div className="grid grid-cols-2 gap-2">
                <button onClick={() => setMode('GARTIC')} className={`py-3 rounded-lg border-2 text-sm font-bold ${mode === 'GARTIC' ? 'border-hit bg-hit/5 text-hit' : 'border-gray-200 text-gray-500'}`}>
                  🎨 갈틱폰<br /><span className="text-[11px] font-normal">그림 텔레폰</span>
                </button>
                <button onClick={() => setMode('CATCHMIND')} className={`py-3 rounded-lg border-2 text-sm font-bold ${mode === 'CATCHMIND' ? 'border-hit bg-hit/5 text-hit' : 'border-gray-200 text-gray-500'}`}>
                  ✏️ 캐치마인드<br /><span className="text-[11px] font-normal">실시간 그림 맞히기</span>
                </button>
              </div>
            </div>
            {mode === 'GARTIC' && (
              <div>
                <p className="text-sm font-bold text-gray-600 mb-1">시작 주제</p>
                <div className="grid grid-cols-2 gap-2">
                  <button onClick={() => setTopicMode('FREE')} className={`py-2 rounded-lg border text-sm ${topicMode === 'FREE' ? 'border-hit bg-hit/5 text-hit font-bold' : 'border-gray-200 text-gray-500'}`}>자유 문장 입력</button>
                  <button onClick={() => setTopicMode('RANDOM')} className={`py-2 rounded-lg border text-sm ${topicMode === 'RANDOM' ? 'border-hit bg-hit/5 text-hit font-bold' : 'border-gray-200 text-gray-500'}`}>랜덤 제시어</button>
                </div>
              </div>
            )}
            <div>
              <button onClick={() => setShowAdv((v) => !v)} className="text-sm text-gray-400 underline">고급 설정 {showAdv ? '▲' : '▼'}</button>
              {showAdv && (
                <div className="mt-2 space-y-3 rounded-lg bg-gray-50 p-3">
                  {mode === 'CATCHMIND' ? (
                    <div>
                      <p className="text-xs font-bold text-gray-500 mb-1">⏱ 라운드 시간</p>
                      <div className="grid grid-cols-4 gap-1">
                        {[30, 45, 60, 90].map((s) => (
                          <button key={s} onClick={() => setRoundSec(s)} className={`py-2 rounded-lg text-sm border ${roundSec === s ? 'border-hit bg-hit/5 text-hit font-bold' : 'border-gray-200 text-gray-500'}`}>{s}초</button>
                        ))}
                      </div>
                    </div>
                  ) : (
                    <>
                      <div>
                        <p className="text-xs font-bold text-gray-500 mb-1">⏱ 문장 시간</p>
                        <div className="grid grid-cols-4 gap-1">
                          {[30, 45, 60, 90].map((s) => (
                            <button key={s} onClick={() => setWriteSec(s)} className={`py-2 rounded-lg text-sm border ${writeSec === s ? 'border-hit bg-hit/5 text-hit font-bold' : 'border-gray-200 text-gray-500'}`}>{s}초</button>
                          ))}
                        </div>
                      </div>
                      <div>
                        <p className="text-xs font-bold text-gray-500 mb-1">🎨 그림 시간</p>
                        <div className="grid grid-cols-4 gap-1">
                          {[90, 120, 150, 240].map((s) => (
                            <button key={s} onClick={() => setDrawSec(s)} className={`py-2 rounded-lg text-sm border ${drawSec === s ? 'border-hit bg-hit/5 text-hit font-bold' : 'border-gray-200 text-gray-500'}`}>{s}초</button>
                          ))}
                        </div>
                      </div>
                    </>
                  )}
                </div>
              )}
            </div>
            <div className="flex gap-2">
              <button onClick={() => setShowCreate(false)} className="flex-1 border border-gray-300 py-3 rounded-lg">취소</button>
              <button onClick={handleCreate} disabled={busy} className="flex-[2] bg-hit text-white font-bold py-3 rounded-lg disabled:opacity-40">
                방 만들기
              </button>
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
      <RoomChat game="gartic" roomCode="lobby" clientId={clientId} nick={nick} />
      </main>
    );
  }

  if (!st) return <main className="min-h-screen flex items-center justify-center"><p className="text-gray-400">불러오는 중...</p></main>;

  const remain = st.deadline > 0 ? Math.max(0, Math.ceil((st.deadline - (Date.now() + clockOffset.current)) / 1000)) : null;
  const scoreBoard = () => st.scores.length > 0 && (
    <div className="rounded-lg bg-gray-50 p-2 text-sm">
      <p className="text-xs text-gray-400 font-bold mb-1">🏆 점수</p>
      <div className="flex flex-wrap gap-2">
        {st.scores.map((s) => (
          <span key={s.seat} className={`px-2 py-0.5 rounded-full ${s.seat === st.seat ? 'bg-hit text-white' : 'bg-white border border-gray-200 text-gray-600'}`}>{s.nick} {s.score}</span>
        ))}
      </div>
    </div>
  );

  return (
    <main className="min-h-screen flex flex-col items-center p-4 max-w-lg mx-auto w-full">
      <div className="w-full flex items-center justify-between gap-2 flex-wrap mb-3">
        <div className="flex items-center gap-2 min-w-0">
          <h1 className="text-lg font-bold shrink-0">🎨 그림 텔레폰</h1>
          <span className="text-xs bg-gray-100 rounded px-2 py-1 tracking-wider font-bold shrink-0">{roomCode}</span>
          <button onClick={() => { api(`/api/v1/drawgame/leave?roomCode=${roomCode}&clientId=${cid()}`, { method: 'POST' }).catch(() => {}); changeRoom(null); setSt(null); }} className="text-xs text-gray-400 underline shrink-0">나가기</button>
        </div>
        {st.status === 'PLAYING' && st.mode === 'GARTIC' && <span className="text-xs text-gray-500 font-bold">{st.round === 0 ? '시작' : `${st.round}`}/{st.totalRounds - 1} 라운드{remain != null && ` · ⏱${remain}s`}</span>}
        {st.status === 'PLAYING' && st.mode === 'CATCHMIND' && remain != null && <span className="text-xs text-gray-500 font-bold">⏱{remain}s</span>}
      </div>

      {st.status === 'LOBBY' && (
        <div className="w-full space-y-4 mt-2">
          <div className="rounded-xl border border-gray-200 p-4">
            <p className="text-sm font-bold mb-2">참가자 ({st.playerCount}/10) · {st.topicMode === 'RANDOM' ? '랜덤 제시어' : '자유 문장'}</p>
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
            <button onClick={handleStart} disabled={busy || st.playerCount < 3} className="w-full bg-hit text-white font-bold py-3 rounded-lg disabled:opacity-40">
              {st.playerCount >= 3 ? '게임 시작' : '최소 3명 필요'}
            </button>
          ) : <p className="text-center text-gray-500 text-sm">방장이 시작하기를 기다리는 중...</p>}
        </div>
      )}

      {st.status === 'PLAYING' && st.mode === 'GARTIC' && (
        <div className="w-full space-y-3">
          {st.mySubmitted || !st.joined ? (
            <div className="text-center py-10 space-y-2">
              <p className="text-4xl">⏳</p>
              <p className="text-gray-600 font-medium">{st.joined ? '제출 완료! 다른 사람들을 기다리는 중...' : '관전 중입니다.'}</p>
              <p className="text-sm text-gray-400">{st.submittedCount}/{st.playerCount}명 제출</p>
            </div>
          ) : st.taskType === 'WRITE_INITIAL' ? (
            <div className="space-y-3">
              <p className="text-center font-bold text-gray-700">✍️ 시작 문장을 입력하세요</p>
              <p className="text-center text-xs text-gray-400">다음 사람이 이걸 그림으로 그려요. 엉뚱할수록 재밌어요!</p>
              <input value={textInput} onChange={(e) => setTextInput(e.target.value)} maxLength={100}
                onKeyDown={(e) => e.key === 'Enter' && handleSubmitText()} placeholder="예: 우주에서 춤추는 로봇"
                className="w-full border border-gray-300 rounded-lg px-3 py-3 focus:outline-none focus:border-hit" />
              <button onClick={handleSubmitText} disabled={busy} className="w-full bg-hit text-white font-bold py-3 rounded-lg disabled:opacity-50">제출</button>
            </div>
          ) : st.taskType === 'DRAW' ? (
            <div className="space-y-3">
              <p className="text-center font-bold text-gray-700">🎨 이 문장을 그림으로!</p>
              <p className="text-center text-lg font-extrabold text-hit bg-hit/5 rounded-lg py-2">“{st.promptText}”</p>
              <DrawCanvas ref={canvasRef} size={320} />
              <button onClick={handleSubmitDraw} disabled={busy} className="w-full bg-hit text-white font-bold py-3 rounded-lg disabled:opacity-50">그림 제출</button>
            </div>
          ) : st.taskType === 'WRITE' ? (
            <div className="space-y-3">
              <p className="text-center font-bold text-gray-700">💬 이 그림을 문장으로!</p>
              {st.promptImage && <img src={st.promptImage} alt="prompt" className="w-72 h-72 max-w-full mx-auto rounded-xl border-2 border-gray-200 bg-white" />}
              <input value={textInput} onChange={(e) => setTextInput(e.target.value)} maxLength={100}
                onKeyDown={(e) => e.key === 'Enter' && handleSubmitText()} placeholder="무엇을 그린 걸까요?"
                className="w-full border border-gray-300 rounded-lg px-3 py-3 focus:outline-none focus:border-hit" />
              <button onClick={handleSubmitText} disabled={busy} className="w-full bg-hit text-white font-bold py-3 rounded-lg disabled:opacity-50">제출</button>
            </div>
          ) : null}
          {error && <p className="text-red-500 text-sm text-center">{error}</p>}
        </div>
      )}

      {st.status === 'PLAYING' && st.mode === 'CATCHMIND' && (
        <div className="w-full space-y-3">
          <p className="text-center text-sm text-gray-500">🎨 <b>{st.players[st.drawerSeat - 1]?.nick}</b>님이 그리는 중 · {st.round}/{st.totalRounds}라운드</p>
          {st.amDrawer ? (
            <div className="space-y-2">
              <p className="text-center text-lg font-extrabold text-hit bg-hit/5 rounded-lg py-2">제시어: {st.myWord}</p>
              <DrawCanvas ref={canvasRef} size={320} onStrokeEnd={pushSnapshot} />
              <p className="text-center text-xs text-gray-400">그림은 자동 공유돼요. 글씨·힌트는 금지!</p>
            </div>
          ) : (
            <div className="space-y-2">
              <div className="w-72 h-72 max-w-full mx-auto rounded-xl border-2 border-gray-200 bg-white flex items-center justify-center overflow-hidden">
                {st.snapshot ? <img src={st.snapshot} alt="drawing" className="w-full h-full object-contain" /> : <span className="text-gray-300 text-sm">그리는 중...</span>}
              </div>
              {st.iGuessedCorrect ? (
                <p className="text-center text-green-600 font-bold">✅ 정답! “{st.myWord}”</p>
              ) : (
                <div className="flex gap-2">
                  <input value={textInput} onChange={(e) => setTextInput(e.target.value)} maxLength={40}
                    onKeyDown={(e) => e.key === 'Enter' && handleGuess()} placeholder="정답 입력!"
                    className="flex-1 border border-gray-300 rounded-lg px-3 py-2 focus:outline-none focus:border-hit" />
                  <button onClick={handleGuess} disabled={busy} className="bg-hit text-white font-bold px-5 rounded-lg disabled:opacity-50">보내기</button>
                </div>
              )}
            </div>
          )}
          <div ref={chatRef} className="rounded-lg border border-gray-200 p-2 h-44 sm:h-64 overflow-y-auto text-sm space-y-0.5">
            {st.guesses.length === 0 && <p className="text-gray-300 text-center text-xs py-2">아직 추측이 없어요</p>}
            {st.guesses.map((gg, i) => (
              <p key={i} className={gg.correct ? 'text-green-600 font-bold' : 'text-gray-600'}><b>{gg.nick}</b>: {gg.text}</p>
            ))}
          </div>
          {scoreBoard()}
          {error && <p className="text-red-500 text-sm text-center">{error}</p>}
        </div>
      )}

      {st.status === 'REVEAL' && st.mode === 'CATCHMIND' && (
        <div className="w-full space-y-3 text-center">
          <p className="text-xl font-extrabold text-hit">🏁 게임 종료!</p>
          <div className="rounded-xl border border-gray-200 p-4 space-y-2">
            {st.scores.map((s, i) => (
              <div key={s.seat} className="flex items-center justify-between">
                <span className="font-bold">{i === 0 ? '🥇' : i === 1 ? '🥈' : i === 2 ? '🥉' : `${i + 1}.`} {s.nick}{s.seat === st.seat && ' (나)'}</span>
                <span className="text-hit font-bold">{s.score}점</span>
              </div>
            ))}
          </div>
          <button onClick={() => { changeRoom(null); setSt(null); setShowCreate(true); }} className="w-full bg-hit text-white font-bold py-3 rounded-lg">🔄 새 방 만들기</button>
        </div>
      )}

      {st.status === 'REVEAL' && st.mode === 'GARTIC' && st.albums.length > 0 && (
        <div className="w-full space-y-3">
          <p className="text-center text-xl font-extrabold text-hit">🎉 결과 공개!</p>
          <div className="flex gap-1 flex-wrap justify-center">
            {st.albums.map((a, i) => (
              <button key={i} onClick={() => setRevealIdx(i)}
                className={`text-xs px-2 py-1 rounded-full border ${revealIdx === i ? 'bg-hit text-white border-hit' : 'border-gray-300 text-gray-500'}`}>
                {a.ownerNick}
              </button>
            ))}
          </div>
          <div className="rounded-xl border border-gray-200 p-3 space-y-3">
            <p className="text-sm font-bold text-gray-500 text-center">📖 {st.albums[revealIdx]?.ownerNick}님의 앨범</p>
            {st.albums[revealIdx]?.steps.map((s, i) => (
              <div key={i} className="border-t border-gray-100 pt-3 first:border-0 first:pt-0">
                <p className="text-[11px] text-gray-400 mb-1">{s.authorNick}</p>
                {s.type === 'IMAGE'
                  ? <img src={s.content} alt="step" className="w-60 h-60 max-w-full mx-auto rounded-lg border border-gray-200 bg-white" />
                  : <p className="text-center text-base font-medium text-gray-800">“{s.content}”</p>}
              </div>
            ))}
          </div>
          <button onClick={() => { changeRoom(null); setSt(null); setShowCreate(true); }} className="w-full bg-hit text-white font-bold py-3 rounded-lg">🔄 새 방 만들기</button>
        </div>
      )}

      {adminFooter}
      <RoomChat game="gartic" roomCode={roomCode} clientId={clientId} nick={nick} />
    </main>
  );
}
