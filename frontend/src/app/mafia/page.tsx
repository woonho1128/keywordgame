'use client';

import { useCallback, useEffect, useRef, useState } from 'react';
import { api } from '@/lib/api';

type Phase =
  | 'NOT_STARTED' | 'LOBBY' | 'NIGHT' | 'MORNING'
  | 'DISCUSS' | 'VOTE' | 'DEFENSE' | 'FINAL_VOTE' | 'EXECUTE' | 'ENDED';

type PlayerView = { seat: number; nick: string; alive: boolean; role: string | null; copResult: string | null; bot: boolean };
type VoteView = { targetSeat: number; count: number };
type ChatView = { seat: number; nick: string; text: string; bot: boolean; round: number };

type MafiaState = {
  status: Phase;
  round: number;
  phaseEndsAt: number;
  serverNow: number;
  isHost: boolean;
  joined: boolean;
  seat: number;
  nick: string | null;
  myRole: string | null;
  myTeam: string | null;
  alive: boolean;
  players: PlayerView[];
  actionKind: string;
  selectable: number[];
  myTarget: number;
  fellowMafia: number[];
  copLog: string[];
  nightMessage: string | null;
  nightDeadSeat: number;
  executedSeat: number;
  voteTally: VoteView[];
  voteCasts: { voterSeat: number; targetSeat: number }[];
  accusedSeat: number;
  killVotes: number;
  spareVotes: number;
  myFinalVote: number;
  winner: string | null;
  aliveCount: number;
  totalMafia: number;
  playerCount: number;
  mafiaPickTally: { targetSeat: number; count: number }[];
  discussSkipCount: number;
  iSkippedDiscuss: boolean;
  history: string[];
  myHistory: string[];
  chat: ChatView[];
};

type RoomSummary = { code: string; status: string; playerCount: number; host: string };

const CLIENT_ID_KEY = 'mafia_client_id';
const NICK_KEY = 'mafia_nick';
const ROOM_KEY = 'mafia_room';

function getClientId(): string {
  if (typeof window === 'undefined') return '';
  try {
    let id = localStorage.getItem(CLIENT_ID_KEY) || '';
    if (!id) {
      id =
        typeof crypto !== 'undefined' && 'randomUUID' in crypto
          ? crypto.randomUUID()
          : `c_${Date.now()}_${Math.random().toString(36).slice(2)}`;
      localStorage.setItem(CLIENT_ID_KEY, id);
    }
    return id;
  } catch {
    return `c_${Math.random().toString(36).slice(2)}`;
  }
}

const ROLE_META: Record<string, { label: string; emoji: string; color: string }> = {
  MAFIA: { label: '마피아', emoji: '🔪', color: 'text-red-500' },
  POLICE: { label: '경찰', emoji: '👮', color: 'text-blue-500' },
  DOCTOR: { label: '의사', emoji: '🩺', color: 'text-green-600' },
  CITIZEN: { label: '시민', emoji: '🧑', color: 'text-gray-700' },
};

const PHASE_LABEL: Record<Phase, string> = {
  NOT_STARTED: '', LOBBY: '대기방', NIGHT: '🌙 밤', MORNING: '☀️ 아침',
  DISCUSS: '💬 토론', VOTE: '🗳️ 투표', DEFENSE: '🎤 최후변론', FINAL_VOTE: '⚖️ 사형투표', EXECUTE: '⚖️ 처형', ENDED: '🏁 종료',
};

const ACTION_LABEL: Record<string, string> = {
  MAFIA_KILL: '🔪 제거할 대상을 고르세요',
  POLICE_CHECK: '🔎 조사할 대상을 고르세요',
  DOCTOR_SAVE: '🩺 보호할 대상을 고르세요',
  CITIZEN_WATCH: '🌙 밤 - 지켜볼 사람을 한 명 고르세요',
  VOTE: '🗳️ 처형할 사람에게 투표하세요',
};

export default function MafiaPage() {
  const [clientId, setClientId] = useState('');
  const [st, setSt] = useState<MafiaState | null>(null);
  const [remaining, setRemaining] = useState(0);
  const [error, setError] = useState<string | null>(null);
  const [busy, setBusy] = useState(false);

  // 방(멀티룸)
  const [roomCode, setRoomCode] = useState<string | null>(null);
  const [rooms, setRooms] = useState<RoomSummary[]>([]);
  const roomRef = useRef<string | null>(null);

  // 방 만들기 폼
  const [nick, setNick] = useState('');
  const [showAdvanced, setShowAdvanced] = useState(false);
  const [nightSec, setNightSec] = useState(30);
  const [discussSec, setDiscussSec] = useState(90);
  const [voteSec, setVoteSec] = useState(30);
  const [defenseSec, setDefenseSec] = useState(20);
  const [finalVoteSec, setFinalVoteSec] = useState(20);
  const [showCreate, setShowCreate] = useState(false);

  // 관리자
  const [showAdmin, setShowAdmin] = useState(false);
  const [adminInput, setAdminInput] = useState('');

  // AI 봇 / 채팅
  const [aiAvailable, setAiAvailable] = useState(false);
  const [botAdminInput, setBotAdminInput] = useState('');
  const [issuedCode, setIssuedCode] = useState<string | null>(null);
  const [codeCopied, setCodeCopied] = useState(false);
  const [chatText, setChatText] = useState('');
  const chatRef = useRef<HTMLDivElement>(null);

  const cidRef = useRef('');
  const offsetRef = useRef(0); // serverNow - Date.now()
  const endsAtRef = useRef(0);
  const inflight = useRef(false);

  const changeRoom = useCallback((code: string | null) => {
    roomRef.current = code;
    setRoomCode(code);
    try {
      if (code) localStorage.setItem(ROOM_KEY, code);
      else localStorage.removeItem(ROOM_KEY);
    } catch {}
  }, []);

  const poll = useCallback(async () => {
    const cid = cidRef.current;
    const code = roomRef.current;
    if (!code) {
      try { setRooms(await api<RoomSummary[]>('/api/v1/mafia/rooms')); } catch {}
      return;
    }
    if (inflight.current) return;
    inflight.current = true;
    try {
      const res = await api<MafiaState>(`/api/v1/mafia/me?roomCode=${code}&clientId=${encodeURIComponent(cid)}`);
      if (res.status === 'NOT_STARTED') { changeRoom(null); setSt(null); }
      else { setSt(res); offsetRef.current = res.serverNow - Date.now(); endsAtRef.current = res.phaseEndsAt; }
    } catch {
      // 폴링 중 일시 오류 무시
    } finally {
      inflight.current = false;
    }
  }, [changeRoom]);

  useEffect(() => {
    const id = getClientId();
    setClientId(id);
    cidRef.current = id;
    try {
      setNick(localStorage.getItem(NICK_KEY) || '');
      const saved = localStorage.getItem(ROOM_KEY);
      if (saved) { roomRef.current = saved; setRoomCode(saved); }
    } catch {}
    api<boolean>('/api/v1/mafia/ai-available').then(setAiAvailable).catch(() => {});
    poll();
    const pollTimer = setInterval(poll, 1000);
    const ticker = setInterval(() => {
      if (endsAtRef.current > 0) {
        const now = Date.now() + offsetRef.current;
        setRemaining(Math.max(0, Math.ceil((endsAtRef.current - now) / 1000)));
      } else {
        setRemaining(0);
      }
    }, 250);
    return () => {
      clearInterval(pollTimer);
      clearInterval(ticker);
    };
  }, [poll]);

  const post = useCallback(
    async (path: string, body?: unknown) => {
      setBusy(true);
      setError(null);
      try {
        const res = await api<MafiaState>(path, {
          method: 'POST',
          body: body ? JSON.stringify(body) : undefined,
        });
        setSt(res);
        offsetRef.current = res.serverNow - Date.now();
        endsAtRef.current = res.phaseEndsAt;
        return res;
      } catch (e) {
        setError(e instanceof Error ? e.message : '오류가 발생했습니다');
        return null;
      } finally {
        setBusy(false);
      }
    },
    []
  );

  // 채팅이 늘면 맨 아래로 스크롤
  useEffect(() => {
    if (chatRef.current) chatRef.current.scrollTop = chatRef.current.scrollHeight;
  }, [st?.chat?.length]);

  const saveNick = (n: string) => {
    try {
      localStorage.setItem(NICK_KEY, n);
    } catch {}
  };

  const handleAddBots = async (count: number) => {
    const code = botAdminInput.trim();
    if (!code) return setError('봇 관리자 코드 또는 1회성 코드를 입력하세요');
    await post(`/api/v1/mafia/add-bots?roomCode=${roomCode}&clientId=${encodeURIComponent(clientId)}&code=${encodeURIComponent(code)}&count=${count}`);
  };

  const handleIssueBotCode = async () => {
    const code = botAdminInput.trim();
    if (!code) return setError('먼저 봇 관리자 코드를 입력하세요');
    setError(null);
    try {
      const c = await api<string>(`/api/v1/mafia/issue-bot-code?code=${encodeURIComponent(code)}`, { method: 'POST' });
      setIssuedCode(c);
      setCodeCopied(false);
    } catch (e) {
      setError(e instanceof Error ? e.message : '코드 발급에 실패했습니다');
    }
  };

  const copyIssuedCode = async () => {
    if (!issuedCode) return;
    try {
      await navigator.clipboard.writeText(issuedCode);
      setCodeCopied(true);
    } catch {
      setCodeCopied(false);
    }
  };

  const handleSendChat = async () => {
    const t = chatText.trim();
    if (!t) return;
    setChatText('');
    await post(`/api/v1/mafia/chat?roomCode=${roomCode}&clientId=${encodeURIComponent(clientId)}`, { text: t });
  };

  const handleCreate = async () => {
    const n = nick.trim();
    if (!n) return setError('닉네임을 입력하세요');
    saveNick(n);
    setBusy(true);
    setError(null);
    try {
      const res = await api<{ roomCode: string; state: MafiaState }>(
        `/api/v1/mafia/new?clientId=${encodeURIComponent(clientId)}`,
        { method: 'POST', body: JSON.stringify({ nick: n, nightSec, discussSec, voteSec, defenseSec, finalVoteSec }) }
      );
      changeRoom(res.roomCode);
      setSt(res.state);
      setShowCreate(false);
    } catch (e) {
      setError(e instanceof Error ? e.message : '방 생성 실패');
    } finally {
      setBusy(false);
    }
  };

  const handleJoin = () => {
    const n = nick.trim();
    if (!n) return setError('닉네임을 입력하세요');
    saveNick(n);
    post(`/api/v1/mafia/join?roomCode=${roomCode}&clientId=${encodeURIComponent(clientId)}`, { nick: n });
  };

  const handleStart = () => post(`/api/v1/mafia/start?roomCode=${roomCode}&clientId=${encodeURIComponent(clientId)}`);

  const handleAct = (target: number) =>
    post(`/api/v1/mafia/night-action?roomCode=${roomCode}&clientId=${encodeURIComponent(clientId)}`, { target });

  const handleVote = (target: number) =>
    post(`/api/v1/mafia/vote?roomCode=${roomCode}&clientId=${encodeURIComponent(clientId)}`, { target });
  const handleFinalVote = (execute: boolean) =>
    post(`/api/v1/mafia/final-vote?roomCode=${roomCode}&clientId=${encodeURIComponent(clientId)}&execute=${execute}`);

  const handleSkipDiscuss = () =>
    post(`/api/v1/mafia/skip-discuss?roomCode=${roomCode}&clientId=${encodeURIComponent(clientId)}`);

  const handleAdminReset = async () => {
    const code = adminInput.trim();
    if (!code) return;
    const res = await post(`/api/v1/mafia/reset?code=${encodeURIComponent(code)}`);
    if (res) {
      setShowAdmin(false);
      setAdminInput('');
      changeRoom(null);
      setSt(null);
    }
  };
  const handleCloseRoom = async (rc: string) => {
    const code = adminInput.trim();
    if (!code) { setError('관리자 코드를 먼저 입력하세요'); return; }
    if (!confirm(`${rc} 방을 삭제할까요?`)) return;
    try {
      await api<boolean>(`/api/v1/mafia/close-room?code=${encodeURIComponent(code)}&roomCode=${rc}`, { method: 'POST' });
      setRooms((cur) => cur.filter((r) => r.code !== rc));
    } catch (e) { setError(e instanceof Error ? e.message : '방 삭제에 실패했습니다'); }
  };

  const nickOf = (seat: number) => st?.players.find((p) => p.seat === seat)?.nick ?? `${seat}번`;

  // ---------- 렌더 ----------
  function adminFooter() {
    return (
      <div className="w-full mt-8 pt-4 border-t border-gray-100 flex justify-center">
        {showAdmin ? (
          <div className="flex items-center gap-2">
            <input type="password" value={adminInput} onChange={(e) => setAdminInput(e.target.value)}
              onKeyDown={(e) => e.key === 'Enter' && handleAdminReset()} placeholder="관리자 코드"
              className="border border-gray-300 rounded-lg px-3 py-2 text-sm w-32 focus:outline-none focus:border-red-400" />
            <button onClick={handleAdminReset} className="bg-gray-700 text-white text-sm px-3 py-2 rounded-lg">전체 초기화</button>
          </div>
        ) : (
          <button onClick={() => setShowAdmin(true)} className="text-xs text-gray-300 hover:text-gray-500">🔒 관리자</button>
        )}
      </div>
    );
  }

  function renderRoomList() {
    return (
      <div className="w-full space-y-4">
        <button onClick={() => { setShowCreate(true); setError(null); }} className="w-full bg-hit text-white font-bold py-3 rounded-lg hover:opacity-90">+ 새 방 만들기</button>
        <p className="text-sm font-bold text-gray-600">방 목록</p>
        {rooms.length === 0 && <p className="text-gray-400 text-sm text-center py-6">아직 만들어진 방이 없어요. 새 방을 만들어보세요!</p>}
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
    );
  }

  if (!roomCode) {
    return (
      <main className="min-h-screen flex flex-col items-center p-6 max-w-md mx-auto w-full">
        <h1 className="text-2xl font-bold mb-6 self-start">🎭 마피아</h1>
        {showCreate ? renderCreateForm() : renderRoomList()}
        {error && <p className="text-red-500 text-sm mt-4 text-center">{error}</p>}
        {adminFooter()}
      </main>
    );
  }

  if (!st) {
    return (
      <main className="min-h-screen flex items-center justify-center">
        <p className="text-gray-400">불러오는 중...</p>
      </main>
    );
  }

  const phase = st.status;

  return (
    <main className="min-h-screen flex flex-col items-center p-6 max-w-md mx-auto w-full">
      {/* 헤더 */}
      <div className="w-full flex items-center justify-between mb-4">
        <div className="flex items-center gap-2">
          <h1 className="text-2xl font-bold">🎭 마피아</h1>
          <span className="text-xs bg-gray-100 rounded px-2 py-1 tracking-wider font-bold">{roomCode}</span>
          <button onClick={() => { api(`/api/v1/mafia/leave?roomCode=${roomCode}&clientId=${encodeURIComponent(clientId)}`, { method: 'POST' }).catch(() => {}); changeRoom(null); setSt(null); }} className="text-xs text-gray-400 underline">나가기</button>
        </div>
        {phase !== 'NOT_STARTED' && phase !== 'LOBBY' && (
          <div className="text-right">
            <div className="text-sm font-bold text-gray-700">
              {st.round}일차 · {PHASE_LABEL[phase]}
            </div>
            {st.phaseEndsAt > 0 && (
              <div className={`text-lg font-extrabold tabular-nums transition-colors ${remaining <= 10 && remaining > 0 ? 'text-red-500 animate-pulse' : 'text-gray-800'}`}>{remaining}s</div>
            )}
          </div>
        )}
      </div>

      {/* 내 역할 배지 */}
      {st.joined && st.myRole && phase !== 'ENDED' && (
        <div className="w-full mb-4 rounded-xl border border-gray-200 p-3 flex items-center justify-between">
          <span className="text-sm text-gray-500">내 역할</span>
          <span className={`font-bold ${ROLE_META[st.myRole]?.color}`}>
            {ROLE_META[st.myRole]?.emoji} {ROLE_META[st.myRole]?.label}
            {!st.alive && <span className="ml-2 text-gray-400">(사망)</span>}
          </span>
        </div>
      )}

      {/* 본문 */}
      <div className="flex-1 w-full">
        {phase === 'NOT_STARTED' && renderNotStarted()}
        {phase === 'LOBBY' && renderLobby()}
        {phase === 'NIGHT' && renderNight()}
        {phase === 'MORNING' && renderMorning()}
        {phase === 'DISCUSS' && renderDiscuss()}
        {phase === 'VOTE' && renderVote()}
        {phase === 'DEFENSE' && renderDefense()}
        {phase === 'FINAL_VOTE' && renderFinalVote()}
        {phase === 'EXECUTE' && renderExecute()}
        {phase === 'ENDED' && renderEnded()}

        {error && <p className="text-red-500 text-sm mt-4 text-center">{error}</p>}
      </div>

      {/* 토론 채팅 */}
      {chatBox()}

      {/* 내 행동 기록(나만 봄) */}
      {st.myHistory && st.myHistory.length > 0 && (
        <details className="w-full mt-6 rounded-xl border border-hit/40 bg-hit/5">
          <summary className="cursor-pointer select-none px-4 py-3 text-sm font-bold text-hit">🔒 내 행동 기록 ({st.myHistory.length})</summary>
          <div className="px-4 pb-3 max-h-64 overflow-y-auto">
            {st.myHistory.map((h, i) => (
              <p key={i} className="text-sm text-gray-700 border-t border-hit/10 py-1.5">{h}</p>
            ))}
          </div>
        </details>
      )}

      {/* 진행 이력(공개) */}
      {st.history && st.history.length > 0 && phase !== 'LOBBY' && phase !== 'NOT_STARTED' && (
        <details className="w-full mt-3 rounded-xl border border-gray-200">
          <summary className="cursor-pointer select-none px-4 py-3 text-sm font-bold text-gray-600">📜 진행 이력 ({st.history.length})</summary>
          <div className="px-4 pb-3 max-h-64 overflow-y-auto">
            {st.history.map((h, i) => (
              <p key={i} className="text-sm text-gray-600 border-t border-gray-50 py-1.5">{h}</p>
            ))}
          </div>
        </details>
      )}

      {/* 관리자 초기화 */}
      <div className="w-full mt-8 pt-4 border-t border-gray-100 flex justify-center">
        {showAdmin ? (
          <div className="flex items-center gap-2">
            <input
              type="password"
              value={adminInput}
              onChange={(e) => setAdminInput(e.target.value)}
              onKeyDown={(e) => e.key === 'Enter' && handleAdminReset()}
              placeholder="관리자 코드"
              className="border border-gray-300 rounded-lg px-3 py-2 text-sm w-32 focus:outline-none focus:border-red-400"
            />
            <button onClick={handleAdminReset} className="bg-gray-700 text-white text-sm px-3 py-2 rounded-lg">
              초기화
            </button>
          </div>
        ) : (
          <button onClick={() => setShowAdmin(true)} className="text-xs text-gray-300 hover:text-gray-500">
            🔒 관리자
          </button>
        )}
      </div>
    </main>
  );

  // ===== 화면별 =====

  function renderNotStarted() {
    if (!showCreate) {
      return (
        <div className="text-center space-y-6 mt-6">
          <p className="text-gray-500">아직 방이 없어요. 새 방을 만들어 친구들을 초대하세요.</p>
          <button
            onClick={() => setShowCreate(true)}
            className="bg-hit text-white font-bold py-3 px-8 rounded-lg hover:opacity-90"
          >
            새 방 만들기
          </button>
          <p className="text-xs text-gray-400">4~12명 · 각자 자기 폰으로 접속</p>
        </div>
      );
    }
    return renderCreateForm();
  }

  function renderCreateForm() {
    return (
      <div className="space-y-5 mt-4">
        <div>
          <label className="block text-sm font-medium mb-1">내 닉네임 *</label>
          <input
            value={nick}
            onChange={(e) => setNick(e.target.value)}
            maxLength={16}
            placeholder="yono"
            className="w-full border border-gray-300 rounded-lg px-3 py-2 focus:outline-none focus:border-hit"
          />
        </div>
        <button
          onClick={() => setShowAdvanced((v) => !v)}
          className="text-sm text-gray-500 underline"
        >
          {showAdvanced ? '고급 설정 접기' : '고급 설정 (타이머)'}
        </button>
        {showAdvanced && (
          <div className="space-y-3 bg-gray-50 rounded-lg p-4">
            {[
              { label: '밤', v: nightSec, set: setNightSec, min: 15, max: 120 },
              { label: '토론', v: discussSec, set: setDiscussSec, min: 15, max: 300 },
              { label: '투표', v: voteSec, set: setVoteSec, min: 10, max: 120 },
              { label: '최후변론', v: defenseSec, set: setDefenseSec, min: 5, max: 120 },
              { label: '사형투표', v: finalVoteSec, set: setFinalVoteSec, min: 5, max: 120 },
            ].map((row) => (
              <div key={row.label} className="flex items-center justify-between text-sm">
                <span>{row.label} 시간</span>
                <span className="flex items-center gap-2">
                  <input
                    type="range" min={row.min} max={row.max} step={5} value={row.v}
                    onChange={(e) => row.set(Number(e.target.value))}
                  />
                  <span className="w-10 text-right font-bold">{row.v}s</span>
                </span>
              </div>
            ))}
          </div>
        )}
        <div className="flex gap-2">
          <button onClick={() => setShowCreate(false)} className="flex-1 border border-gray-300 py-3 rounded-lg">
            취소
          </button>
          <button
            onClick={handleCreate}
            disabled={busy}
            className="flex-[2] bg-hit text-white font-bold py-3 rounded-lg hover:opacity-90 disabled:opacity-50"
          >
            {busy ? '생성 중...' : '방 만들기'}
          </button>
        </div>
      </div>
    );
  }

  function renderLobby() {
    const canStart = st!.playerCount >= 4;
    return (
      <div className="space-y-5 mt-2">
        <div className="rounded-xl border border-gray-200 p-4">
          <p className="text-sm font-bold mb-2">참가자 ({st!.playerCount}/12)</p>
          <div className="flex flex-wrap gap-2">
            {st!.players.map((p) => {
              const me = p.seat === st!.seat;
              return (
                <span key={p.seat} className={`bg-gray-100 rounded-full px-3 py-1 text-sm ${me ? 'ring-2 ring-hit font-bold' : ''}`}>
                  {p.nick}{me && ' (나)'}
                </span>
              );
            })}
            {st!.players.length === 0 && <span className="text-gray-400 text-sm">아직 없음</span>}
          </div>
        </div>

        {st!.isHost && aiAvailable && (
          <div className="rounded-xl border border-purple-200 bg-purple-50/50 p-4 space-y-2">
            <p className="text-sm font-bold text-purple-700">
              🤖 AI 봇 추가 <span className="text-xs font-normal text-gray-400">(관리자)</span>
            </p>
            <p className="text-xs text-gray-500 leading-relaxed">
              혼자여도 봇과 즐길 수 있어요. 봇은 토론 채팅에 참여하고 투표해요. 최대 5명.
            </p>
            <input
              type="password"
              value={botAdminInput}
              onChange={(e) => setBotAdminInput(e.target.value)}
              placeholder="봇 관리자 코드 또는 1회성 코드"
              className="w-full border border-gray-300 rounded-lg px-3 py-2 text-sm focus:outline-none focus:border-purple-400"
            />
            <div className="grid grid-cols-5 gap-2">
              {[1, 2, 3, 4, 5].map((n) => (
                <button
                  key={n}
                  onClick={() => handleAddBots(n)}
                  disabled={busy || st!.playerCount >= 12}
                  className="bg-purple-500 text-white text-sm font-bold py-2 rounded-lg hover:opacity-90 disabled:opacity-40"
                >
                  봇 +{n}
                </button>
              ))}
            </div>

            {/* 1회성 코드 발급(마스터 코드 입력 시) */}
            <div className="pt-1 border-t border-purple-100">
              <button
                onClick={handleIssueBotCode}
                className="text-xs font-bold text-purple-600 hover:text-purple-800"
              >
                🎫 1회성 코드 발급받기
              </button>
              <p className="text-[11px] text-gray-400 mt-0.5">
                위에 마스터 코드를 넣고 누르면 발급돼요. 발급된 코드는 봇 추가 1회에만 쓰이고 24시간 뒤 만료돼요(친구에게 공유용).
              </p>
              {issuedCode && (
                <div className="mt-2 flex items-center justify-between gap-2 bg-white border border-purple-300 rounded-lg px-3 py-2">
                  <span className="font-mono font-bold tracking-widest text-purple-700 select-all">{issuedCode}</span>
                  <button
                    onClick={copyIssuedCode}
                    className="text-xs font-bold text-purple-600 border border-purple-300 rounded px-2 py-1 hover:bg-purple-50"
                  >
                    {codeCopied ? '복사됨 ✓' : '복사'}
                  </button>
                </div>
              )}
            </div>
          </div>
        )}

        {!st!.joined ? (
          <div className="space-y-2">
            <label className="block text-sm font-medium">닉네임으로 참가</label>
            <div className="flex gap-2">
              <input
                value={nick}
                onChange={(e) => setNick(e.target.value)}
                maxLength={16}
                placeholder="닉네임"
                className="flex-1 border border-gray-300 rounded-lg px-3 py-2 focus:outline-none focus:border-hit"
              />
              <button onClick={handleJoin} disabled={busy} className="bg-hit text-white font-bold px-5 rounded-lg disabled:opacity-50">
                참가
              </button>
            </div>
          </div>
        ) : st!.isHost ? (
          <button
            onClick={handleStart}
            disabled={busy || !canStart}
            className="w-full bg-red-500 text-white font-bold py-3 rounded-lg hover:opacity-90 disabled:opacity-40"
          >
            {canStart ? '게임 시작' : '최소 4명 필요'}
          </button>
        ) : (
          <p className="text-center text-gray-500 text-sm">방장이 시작하기를 기다리는 중...</p>
        )}
        <p className="text-center text-xs text-gray-400">
          같은 주소를 친구들에게 공유하세요. 각자 닉네임으로 참가하면 됩니다.
        </p>
      </div>
    );
  }

  function targetButtons(seats: number[], onPick: (seat: number) => void, current: number) {
    return (
      <div className="grid grid-cols-2 gap-2">
        {seats.map((seat) => (
          <button
            key={seat}
            onClick={() => onPick(seat)}
            disabled={busy}
            className={`rounded-lg border py-3 px-2 text-sm font-medium transition active:scale-95 ${
              current === seat
                ? 'border-hit bg-green-50 text-hit font-bold'
                : 'border-gray-300 hover:bg-gray-50'
            }`}
          >
            {nickOf(seat)}
          </button>
        ))}
      </div>
    );
  }

  function aliveBoard() {
    return (
      <div className="mt-6">
        <p className="text-xs text-gray-400 mb-2">생존 {st!.aliveCount}명 / 전체 {st!.playerCount}명</p>
        <div className="flex flex-wrap gap-2">
          {st!.players.map((p) => {
            const me = p.seat === st!.seat;
            return (
              <span
                key={p.seat}
                className={`rounded-full px-3 py-1 text-sm ${
                  p.alive ? 'bg-gray-100 text-gray-700' : 'bg-gray-50 text-gray-300 line-through'
                } ${me ? 'ring-2 ring-hit font-bold' : ''}`}
              >
                {p.nick}{me && ' (나)'}
                {p.role && <span className="ml-1">{ROLE_META[p.role]?.emoji}</span>}
                {p.copResult && (
                  <span className={`ml-1 font-bold ${p.copResult === 'MAFIA' ? 'text-red-500' : 'text-blue-500'}`}>
                    ({p.copResult === 'MAFIA' ? '마피아' : '시민'})
                  </span>
                )}
              </span>
            );
          })}
        </div>
      </div>
    );
  }

  function chatBox() {
    if (!st) return null;
    const dayPhase = phase === 'MORNING' || phase === 'DISCUSS' || phase === 'VOTE';
    const defense = phase === 'DEFENSE';
    if (!dayPhase && !defense) return null;
    // 최후변론은 재판대에 오른 사람만 말한다.
    const isAccused = st.seat === st.accusedSeat;
    const canSpeak = st.joined && st.alive && (!defense || isAccused);
    const timed = st.phaseEndsAt > 0;
    const urgent = timed && remaining > 0 && remaining <= 10;
    return (
      <div className={`w-full mt-4 rounded-xl border flex flex-col overflow-hidden transition-all ${urgent ? 'border-red-400 ring-2 ring-red-300' : 'border-gray-200'}`}>
        <div className={`px-3 py-2 border-b text-sm font-bold flex items-center justify-between transition-colors ${urgent ? 'border-red-100 bg-red-50 text-red-600' : 'border-gray-100 text-gray-600'}`}>
          <span>{defense ? '🎤 최후변론' : '💬 토론 채팅'}</span>
          {timed ? (
            <span className={`text-xs font-extrabold tabular-nums ${urgent ? 'text-red-500 animate-pulse' : 'text-gray-400'}`}>
              {urgent ? `⏰ ${remaining}초!` : `${remaining}s`}
            </span>
          ) : (
            <span className="text-[11px] font-normal text-gray-400">서로 의심하고 설득하세요</span>
          )}
        </div>
        <div ref={chatRef} className="overflow-y-auto px-3 py-2 space-y-1" style={{ height: '11rem' }}>
          {st.chat.length === 0 && (
            <p className="text-xs text-gray-300 text-center py-8">아직 대화가 없어요. 먼저 말을 걸어보세요!</p>
          )}
          {st.chat.map((c, i) => {
            const mine = c.seat === st.seat;
            return (
              <div key={i} className={`text-sm leading-snug ${mine ? 'text-right' : ''}`}>
                <span className={`font-bold ${c.bot ? 'text-purple-500' : mine ? 'text-hit' : 'text-gray-700'}`}>{c.nick}</span>
                <span className="text-gray-700"> {c.text}</span>
              </div>
            );
          })}
        </div>
        {canSpeak ? (
          <div className="flex gap-2 p-2 border-t border-gray-100">
            <input
              value={chatText}
              onChange={(e) => setChatText(e.target.value)}
              onKeyDown={(e) => e.key === 'Enter' && handleSendChat()}
              maxLength={200}
              placeholder={defense ? '변론하세요...' : '메시지 입력...'}
              className="flex-1 border border-gray-300 rounded-lg px-3 py-2 text-sm focus:outline-none focus:border-hit"
            />
            <button onClick={handleSendChat} disabled={busy} className="bg-hit text-white text-sm font-bold px-4 rounded-lg disabled:opacity-50">
              전송
            </button>
          </div>
        ) : (
          <div className="p-2 border-t border-gray-100 text-center text-xs text-gray-400">
            {defense && st.joined && st.alive
              ? `${nickOf(st.accusedSeat)}님의 변론을 듣는 중이에요`
              : st.joined ? '사망하여 관전 중 — 대화할 수 없어요' : '관전 중'}
          </div>
        )}
      </div>
    );
  }

  function renderNight() {
    if (!st!.joined || !st!.alive) {
      return (
        <div className="text-center mt-8 space-y-2">
          <p className="text-4xl">🌙</p>
          <p className="text-gray-500">{st!.joined ? '사망하여 관전 중입니다.' : '밤이 깊었습니다.'}</p>
          {aliveBoard()}
        </div>
      );
    }
    const kind = st!.actionKind;
    const isMafia = kind === 'MAFIA_KILL';
    return (
      <div className="mt-2 space-y-4">
        <p className="font-bold text-center">{ACTION_LABEL[kind] ?? '🌙 밤입니다'}</p>
        {isMafia && st!.fellowMafia.length > 1 && (
          <p className="text-center text-xs text-red-400">
            동료 마피아: {st!.fellowMafia.map(nickOf).join(', ')} · 실시간으로 지목이 공유됩니다
          </p>
        )}
        {targetButtons(st!.selectable, handleAct, st!.myTarget)}

        {/* 마피아: 동료들의 실시간 지목 현황(다수결로 최종 대상 결정) */}
        {isMafia && st!.mafiaPickTally.length > 0 && (
          <div className="bg-red-50 rounded-lg p-3 text-sm space-y-1">
            <p className="text-xs text-red-400 font-medium">동료 지목 현황 (다수결)</p>
            {st!.mafiaPickTally
              .slice()
              .sort((a, b) => b.count - a.count)
              .map((v) => (
                <div key={v.targetSeat} className="flex justify-between text-red-700">
                  <span>{nickOf(v.targetSeat)}</span>
                  <span className="font-bold">{v.count}표</span>
                </div>
              ))}
          </div>
        )}

        {kind === 'POLICE_CHECK' && st!.copLog.length > 0 && (
          <div className="bg-blue-50 rounded-lg p-3 text-sm text-blue-700 space-y-1">
            {st!.copLog.map((l, i) => (
              <div key={i}>{l}</div>
            ))}
          </div>
        )}
        {st!.myTarget > 0 && (
          <p className="text-center text-xs text-gray-400">
            내 선택: <b>{nickOf(st!.myTarget)}</b> · 시간 내 변경 가능
          </p>
        )}
        {aliveBoard()}
      </div>
    );
  }

  function renderMorning() {
    return (
      <div className="text-center mt-8 space-y-3">
        <p className="text-5xl">☀️</p>
        <p className="text-lg font-bold text-gray-800">{st!.nightMessage}</p>
        {aliveBoard()}
      </div>
    );
  }

  function renderDiscuss() {
    return (
      <div className="text-center mt-6 space-y-3">
        <p className="text-5xl">💬</p>
        <p className="text-gray-600">자유롭게 토론하세요. 곧 투표가 시작됩니다.</p>
        {st!.nightMessage && <p className="text-sm text-gray-400">{st!.nightMessage}</p>}
        {aliveBoard()}
        {st!.joined && st!.alive && (
          <div className="pt-2">
            <button
              onClick={handleSkipDiscuss}
              disabled={busy || st!.iSkippedDiscuss}
              className={`w-full rounded-lg py-3 font-bold text-sm border ${
                st!.iSkippedDiscuss
                  ? 'bg-gray-100 text-gray-400 border-gray-200'
                  : 'bg-white text-hit border-hit hover:bg-hit/5'
              } disabled:opacity-60`}
            >
              {st!.iSkippedDiscuss ? '✓ 토론 스킵 동의함' : '⏭️ 토론 스킵 (바로 투표)'}
              <span className="ml-1 text-gray-400 font-normal">{st!.discussSkipCount}/{st!.aliveCount}</span>
            </button>
            <p className="text-[11px] text-gray-400 mt-1">생존자 전원이 동의하면 바로 투표로 넘어가요</p>
          </div>
        )}
      </div>
    );
  }

  function renderVote() {
    const tally = st!.voteTally;
    return (
      <div className="mt-2 space-y-4">
        <p className="font-bold text-center">{ACTION_LABEL.VOTE}</p>
        {st!.joined && st!.alive ? (
          <>
            {targetButtons(st!.selectable, handleVote, st!.myTarget)}
            <button
              onClick={() => handleVote(-1)}
              disabled={busy}
              className={`w-full rounded-lg border py-2 text-sm ${
                st!.myTarget === -1 && st!.seat > 0 ? 'border-gray-500 bg-gray-100' : 'border-gray-300'
              }`}
            >
              기권
            </button>
          </>
        ) : (
          <p className="text-center text-gray-500 text-sm">관전 중 — 투표할 수 없습니다.</p>
        )}
        {tally.length > 0 && (
          <div className="space-y-1">
            <p className="text-xs text-gray-400">현재 득표</p>
            {tally
              .slice()
              .sort((a, b) => b.count - a.count)
              .map((v) => (
                <div key={v.targetSeat} className="flex justify-between text-sm">
                  <span>{nickOf(v.targetSeat)}</span>
                  <span className="font-bold">{v.count}표</span>
                </div>
              ))}
          </div>
        )}
        {/*
          공개 투표 — 누가 누구를 찍었는지 모두에게 보여준다.
          AI 봇은 이 기록을 근거로 추리하므로, 사람에게 숨기면 봇만 아는 정보가 된다.
        */}
        {st!.voteCasts && st!.voteCasts.length > 0 && (
          <div className="rounded-lg border border-gray-200 p-3 space-y-1">
            <p className="text-xs font-bold text-gray-400">누가 누구를 찍었나</p>
            {st!.voteCasts.map((c) => (
              <div key={c.voterSeat} className="flex items-center gap-1.5 text-sm">
                <span className={c.voterSeat === st!.seat ? 'font-bold text-hit' : ''}>{nickOf(c.voterSeat)}</span>
                <span className="text-gray-300">▸</span>
                <span className={c.targetSeat < 0 ? 'text-gray-400' : 'font-medium'}>
                  {c.targetSeat < 0 ? '기권' : nickOf(c.targetSeat)}
                </span>
              </div>
            ))}
          </div>
        )}
      </div>
    );
  }

  function renderDefense() {
    const accused = st!.accusedSeat;
    const isAccused = st!.seat === accused;
    return (
      <div className="text-center mt-6 space-y-3">
        <p className="text-4xl">🎤</p>
        <p className="text-lg font-bold text-gray-800">{nickOf(accused)}님의 최후변론</p>
        {isAccused
          ? <p className="text-sm text-hit font-bold">당신이 지목되었습니다. 채팅으로 변론하세요!</p>
          : <p className="text-sm text-gray-500">변론을 듣고, 곧 사형/생존 투표가 진행됩니다.</p>}
        {aliveBoard()}
      </div>
    );
  }

  function renderFinalVote() {
    const accused = st!.accusedSeat;
    const isAccused = st!.seat === accused;
    const mine = st!.myFinalVote;
    return (
      <div className="mt-2 space-y-4 text-center">
        <p className="font-bold">⚖️ {nickOf(accused)}님을 처형할까요?</p>
        {st!.joined && st!.alive && !isAccused ? (
          <div className="grid grid-cols-2 gap-2">
            <button onClick={() => handleFinalVote(true)} disabled={busy}
              className={`py-3 rounded-lg border-2 font-bold ${mine === 1 ? 'border-red-500 bg-red-50 text-red-600' : 'border-gray-200 text-gray-500'}`}>💀 사형</button>
            <button onClick={() => handleFinalVote(false)} disabled={busy}
              className={`py-3 rounded-lg border-2 font-bold ${mine === 0 ? 'border-green-500 bg-green-50 text-green-600' : 'border-gray-200 text-gray-500'}`}>🕊️ 생존</button>
          </div>
        ) : (
          <p className="text-sm text-gray-500">{isAccused ? '당신은 재판 당사자라 투표할 수 없습니다.' : '관전 중 — 투표할 수 없습니다.'}</p>
        )}
        <div className="flex justify-center gap-6 text-sm">
          <span className="text-red-500 font-bold">💀 사형 {st!.killVotes}</span>
          <span className="text-green-600 font-bold">🕊️ 생존 {st!.spareVotes}</span>
        </div>
        <p className="text-xs text-gray-400">사형 표가 더 많으면 처형됩니다(동수·생존 우세 시 생존).</p>
        {aliveBoard()}
      </div>
    );
  }

  function renderExecute() {
    const executed = st!.executedSeat;
    return (
      <div className="text-center mt-8 space-y-3">
        <p className="text-5xl">⚖️</p>
        {executed > 0 ? (
          <>
            <p className="text-lg font-bold text-gray-800">
              {nickOf(executed)}님이 처형되었습니다.
            </p>
            {(() => {
              const p = st!.players.find((x) => x.seat === executed);
              return p?.role ? (
                <p className={`font-bold ${ROLE_META[p.role]?.color}`}>
                  정체: {ROLE_META[p.role]?.emoji} {ROLE_META[p.role]?.label}
                </p>
              ) : null;
            })()}
          </>
        ) : (
          <p className="text-lg font-bold text-gray-600">동표로 아무도 처형되지 않았습니다.</p>
        )}
        {aliveBoard()}
      </div>
    );
  }

  /**
   * 끝난 뒤 전체 대화 다시보기.
   * 정체가 공개된 상태로 다시 읽으면 누가 언제 거짓말했는지가 보인다.
   */
  function chatReplay() {
    if (!st || st.chat.length === 0) return null;
    const roleOf = (seat: number) => st.players.find((p) => p.seat === seat)?.role;
    const rounds = [...new Set(st.chat.map((c) => c.round))].sort((a, b) => a - b);
    return (
      <details className="rounded-xl border border-gray-200 text-left">
        <summary className="px-4 py-3 text-sm font-bold cursor-pointer select-none">
          💬 전체 대화 다시보기 <span className="font-normal text-gray-400">({st.chat.length}줄)</span>
        </summary>
        <div className="px-4 pb-4 max-h-80 overflow-y-auto space-y-3">
          {rounds.map((r) => (
            <div key={r} className="space-y-1">
              <p className="text-[11px] font-bold text-gray-400 sticky top-0 bg-white py-1">{r}일차</p>
              {st.chat.filter((c) => c.round === r).map((c, i) => {
                const role = roleOf(c.seat);
                return (
                  <div key={i} className="text-sm leading-snug">
                    <span className={`font-bold ${role === 'MAFIA' ? 'text-red-500' : 'text-gray-700'}`}>
                      {c.nick}
                    </span>
                    {role && (
                      <span className={`ml-1 text-[10px] ${ROLE_META[role]?.color ?? ''}`}>
                        {ROLE_META[role]?.emoji}
                      </span>
                    )}
                    <span className="text-gray-700"> {c.text}</span>
                  </div>
                );
              })}
            </div>
          ))}
        </div>
      </details>
    );
  }

  function renderEnded() {
    const win = st!.winner === 'MAFIA';
    return (
      <div className="text-center mt-6 space-y-4">
        <p className="text-6xl">{win ? '🔪' : '🎉'}</p>
        <p className={`text-3xl font-extrabold ${win ? 'text-red-500' : 'text-hit'}`}>
          {win ? '마피아 승리!' : '시민 승리!'}
        </p>
        <div className="rounded-xl border border-gray-200 p-4 text-left">
          <p className="text-sm font-bold mb-2">전체 정체 공개</p>
          <div className="space-y-1">
            {st!.players.map((p) => {
              const me = p.seat === st!.seat;
              return (
                <div key={p.seat} className="flex justify-between text-sm">
                  <span className={`${p.alive ? '' : 'text-gray-400 line-through'} ${me ? 'font-bold' : ''}`}>
                    {p.nick}{me && ' (나)'}
                  </span>
                  <span className={`font-medium ${p.role ? ROLE_META[p.role]?.color : ''}`}>
                    {p.role ? `${ROLE_META[p.role]?.emoji} ${ROLE_META[p.role]?.label}` : '-'}
                  </span>
                </div>
              );
            })}
          </div>
        </div>
        {chatReplay()}

        <button
          onClick={() => {
            setShowCreate(true);
            setSt({ ...st!, status: 'NOT_STARTED' });
          }}
          className="w-full bg-hit text-white font-bold py-3 rounded-lg hover:opacity-90"
        >
          🔄 새 방 만들기
        </button>
      </div>
    );
  }
}
