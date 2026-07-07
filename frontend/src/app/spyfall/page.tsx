'use client';

import { useCallback, useEffect, useState } from 'react';
import { api } from '@/lib/api';

type SpyfallState = {
  status: 'OK' | 'NOT_STARTED' | 'NOT_JOINED' | 'FULL';
  round: number;
  seat: number;
  isSpy: boolean;
  location: string | null;
  role: string | null;
  playerCount: number;
  spyCount: number;
  joinedCount: number;
};

const CLIENT_ID_KEY = 'spyfall_client_id';

function getClientId(): string {
  if (typeof window === 'undefined') return '';
  let id = '';
  try {
    id = localStorage.getItem(CLIENT_ID_KEY) || '';
    if (!id) {
      id =
        typeof crypto !== 'undefined' && 'randomUUID' in crypto
          ? crypto.randomUUID()
          : `c_${Date.now()}_${Math.random().toString(36).slice(2)}`;
      localStorage.setItem(CLIENT_ID_KEY, id);
    }
  } catch {
    id = `c_${Math.random().toString(36).slice(2)}`;
  }
  return id;
}

export default function SpyfallPage() {
  const [clientId, setClientId] = useState('');
  const [state, setState] = useState<SpyfallState | null>(null);
  const [revealed, setRevealed] = useState(false);
  const [loading, setLoading] = useState(true);
  const [busy, setBusy] = useState(false);
  const [error, setError] = useState<string | null>(null);

  // 새로고침(새 판) 설정
  const [showSetup, setShowSetup] = useState(false);
  const [playerCount, setPlayerCount] = useState(6);
  const [spyCount, setSpyCount] = useState(1);

  // 관리자(전체 초기화)
  const [adminVerified, setAdminVerified] = useState(false);
  const [showAdmin, setShowAdmin] = useState(false);
  const [adminInput, setAdminInput] = useState('');

  useEffect(() => {
    const id = getClientId();
    setClientId(id);
    api<SpyfallState>(`/api/v1/spyfall/me?clientId=${encodeURIComponent(id)}`)
      .then(setState)
      .catch((e) => setError(e instanceof Error ? e.message : '불러오기 실패'))
      .finally(() => setLoading(false));

    // 저장된 관리자 코드가 있으면 검증해서 초기화 버튼 유지
    try {
      const saved = localStorage.getItem('spyfall_admin_code');
      if (saved) {
        api<boolean>(`/api/v1/spyfall/admin/verify?code=${encodeURIComponent(saved)}`)
          .then((ok) => setAdminVerified(ok))
          .catch(() => {});
      }
    } catch {}
  }, []);

  const handleVerifyAdmin = useCallback(async () => {
    const code = adminInput.trim();
    if (!code) return;
    setError(null);
    try {
      const ok = await api<boolean>(
        `/api/v1/spyfall/admin/verify?code=${encodeURIComponent(code)}`
      );
      if (ok) {
        try {
          localStorage.setItem('spyfall_admin_code', code);
        } catch {}
        setAdminVerified(true);
        setShowAdmin(false);
        setAdminInput('');
      } else {
        setError('관리자 코드가 올바르지 않습니다');
      }
    } catch (e) {
      setError(e instanceof Error ? e.message : '확인 실패');
    }
  }, [adminInput]);

  const handleReset = useCallback(async () => {
    if (!window.confirm('전체 초기화할까요? 모든 사람의 판이 사라집니다.')) return;
    setBusy(true);
    setError(null);
    let code = '';
    try {
      code = localStorage.getItem('spyfall_admin_code') || '';
    } catch {}
    try {
      const res = await api<SpyfallState>(
        `/api/v1/spyfall/reset?code=${encodeURIComponent(code)}`,
        { method: 'POST' }
      );
      setState(res);
      setRevealed(false);
    } catch (e) {
      setError(e instanceof Error ? e.message : '초기화 실패');
    } finally {
      setBusy(false);
    }
  }, []);

  // 버튼: 숨김이면 최신 역할을 받아서 공개, 공개 중이면 숨김.
  const handleReveal = useCallback(async () => {
    if (revealed) {
      setRevealed(false);
      return;
    }
    setBusy(true);
    setError(null);
    try {
      const res = await api<SpyfallState>(
        `/api/v1/spyfall/claim?clientId=${encodeURIComponent(clientId)}`,
        { method: 'POST' }
      );
      setState(res);
      if (res.status === 'OK') setRevealed(true);
    } catch (e) {
      setError(e instanceof Error ? e.message : '오류가 발생했습니다');
    } finally {
      setBusy(false);
    }
  }, [revealed, clientId]);

  const handleNewGame = useCallback(async () => {
    if (spyCount >= playerCount) {
      setError('스파이 수는 인원수보다 적어야 합니다');
      return;
    }
    setBusy(true);
    setError(null);
    try {
      await api<SpyfallState>('/api/v1/spyfall/new', {
        method: 'POST',
        body: JSON.stringify({ playerCount, spyCount }),
      });
      // 방장도 좌석 하나 배정받기
      const mine = await api<SpyfallState>(
        `/api/v1/spyfall/claim?clientId=${encodeURIComponent(clientId)}`,
        { method: 'POST' }
      );
      setState(mine);
      setRevealed(false);
      setShowSetup(false);
    } catch (e) {
      setError(e instanceof Error ? e.message : '새 판 생성 실패');
    } finally {
      setBusy(false);
    }
  }, [playerCount, spyCount, clientId]);

  const started = state && state.status !== 'NOT_STARTED';

  return (
    <main className="min-h-screen flex flex-col items-center p-6 max-w-md mx-auto w-full">
      {/* 상단 바: 새로고침 버튼 */}
      <div className="w-full flex items-center justify-between mb-8">
        <h1 className="text-2xl font-bold">🕵️ 스파이폴</h1>
        <button
          onClick={() => {
            setShowSetup((v) => !v);
            setError(null);
          }}
          className="flex items-center gap-1 border border-gray-300 rounded-lg px-3 py-2 text-sm font-medium hover:bg-gray-50 active:scale-95 transition"
          title="새 판 시작 (모두의 역할이 새로 배정됩니다)"
        >
          🔄 새로고침
        </button>
      </div>

      {/* 새 판 설정 패널 */}
      {showSetup && (
        <div className="w-full border border-gray-200 rounded-xl p-5 mb-8 bg-gray-50 space-y-5">
          <p className="text-sm text-gray-500">
            새 판을 시작하면 접속한 모두의 역할이 새로 배정됩니다.
          </p>
          <div>
            <label className="block text-sm font-medium mb-2">
              인원수: <span className="font-bold">{playerCount}명</span>
            </label>
            <input
              type="range"
              min={3}
              max={12}
              value={playerCount}
              onChange={(e) => {
                const n = Number(e.target.value);
                setPlayerCount(n);
                if (spyCount >= n) setSpyCount(n - 1);
              }}
              className="w-full"
            />
            <div className="flex justify-between text-xs text-gray-400 mt-1">
              <span>3</span>
              <span>12</span>
            </div>
          </div>
          <div>
            <label className="block text-sm font-medium mb-2">
              스파이 수: <span className="font-bold">{spyCount}명</span>
            </label>
            <div className="flex gap-2">
              {Array.from({ length: Math.min(playerCount - 1, 6) }, (_, i) => i + 1).map((n) => (
                <button
                  key={n}
                  onClick={() => setSpyCount(n)}
                  className={`w-10 h-10 rounded-lg border font-bold ${
                    spyCount === n
                      ? 'border-hit bg-hit text-white'
                      : 'border-gray-300 bg-white text-gray-600'
                  }`}
                >
                  {n}
                </button>
              ))}
            </div>
          </div>
          <button
            onClick={handleNewGame}
            disabled={busy}
            className="w-full bg-hit text-white font-bold py-3 rounded-lg hover:opacity-90 disabled:opacity-50"
          >
            {busy ? '배정 중...' : '새 판 시작'}
          </button>
        </div>
      )}

      {/* 본문 */}
      <div className="flex-1 w-full flex flex-col items-center justify-center">
        {loading ? (
          <p className="text-gray-400">불러오는 중...</p>
        ) : !started ? (
          <div className="text-center text-gray-500 space-y-3">
            <p className="text-lg">아직 시작된 판이 없어요.</p>
            <p className="text-sm">
              위의 <span className="font-bold">🔄 새로고침</span> 을 눌러 인원수를 정하고 새 판을 시작하세요.
            </p>
          </div>
        ) : state?.status === 'FULL' && !revealed && state.seat === 0 ? (
          <div className="text-center text-gray-500 space-y-2">
            <p className="text-lg">자리가 꽉 찼어요 😅</p>
            <p className="text-sm">
              {state.playerCount}명 정원이 모두 찼습니다. 인원수를 늘리려면 새로고침 하세요.
            </p>
          </div>
        ) : (
          <>
            {/* 큰 카드 버튼 */}
            <button
              onClick={handleReveal}
              disabled={busy}
              className={`w-full aspect-[3/4] max-w-xs rounded-3xl border-2 flex flex-col items-center justify-center text-center p-6 transition active:scale-[0.98] disabled:opacity-60 ${
                revealed
                  ? state?.isSpy
                    ? 'border-red-400 bg-red-50'
                    : 'border-hit bg-green-50'
                  : 'border-gray-300 bg-white hover:border-gray-400'
              }`}
            >
              {!revealed ? (
                <>
                  <span className="text-6xl mb-4">🎴</span>
                  <span className="text-xl font-bold text-gray-700">
                    {busy ? '확인 중...' : '역할 확인하기'}
                  </span>
                  <span className="text-sm text-gray-400 mt-2">탭하면 내 역할이 보여요</span>
                </>
              ) : state?.isSpy ? (
                <>
                  <span className="text-6xl mb-4">🕵️</span>
                  <span className="text-3xl font-extrabold text-red-500">당신은 스파이!</span>
                  <span className="text-sm text-gray-500 mt-3">
                    장소를 모릅니다. 들키지 말고 장소를 추리하세요.
                  </span>
                </>
              ) : (
                <>
                  <span className="text-sm text-gray-400 mb-1">장소</span>
                  <span className="text-3xl font-extrabold text-gray-800 mb-5">
                    {state?.location}
                  </span>
                  <span className="text-sm text-gray-400 mb-1">내 직업</span>
                  <span className="text-2xl font-bold text-hit">{state?.role}</span>
                </>
              )}
            </button>

            {revealed && (
              <p className="text-xs text-gray-400 mt-4">
                다시 탭하면 숨겨집니다 · 남에게 보이지 않게 조심하세요
              </p>
            )}

            {/* 하단 판 정보 */}
            {state?.status === 'OK' && (
              <p className="text-xs text-gray-400 mt-6">
                {state.seat}번 · 총 {state.playerCount}명 (스파이 {state.spyCount}명) · 접속{' '}
                {state.joinedCount}명
              </p>
            )}
          </>
        )}

        {error && <p className="text-red-500 text-sm mt-4">{error}</p>}
      </div>

      {/* 관리자: 전체 초기화 */}
      <div className="w-full mt-8 pt-4 border-t border-gray-100 flex flex-col items-center">
        {adminVerified ? (
          <div className="flex flex-col items-center gap-2">
            <button
              onClick={handleReset}
              disabled={busy}
              className="border border-red-300 text-red-500 font-bold px-4 py-2 rounded-lg text-sm hover:bg-red-50 active:scale-95 transition disabled:opacity-50"
            >
              ⚠️ 전체 초기화
            </button>
            <button
              onClick={() => {
                try {
                  localStorage.removeItem('spyfall_admin_code');
                } catch {}
                setAdminVerified(false);
              }}
              className="text-xs text-gray-400 underline"
            >
              관리자 해제
            </button>
          </div>
        ) : showAdmin ? (
          <div className="flex items-center gap-2">
            <input
              type="password"
              value={adminInput}
              onChange={(e) => setAdminInput(e.target.value)}
              onKeyDown={(e) => e.key === 'Enter' && handleVerifyAdmin()}
              placeholder="관리자 코드"
              className="border border-gray-300 rounded-lg px-3 py-2 text-sm w-40 focus:outline-none focus:border-hit"
            />
            <button
              onClick={handleVerifyAdmin}
              className="bg-gray-700 text-white text-sm font-medium px-3 py-2 rounded-lg"
            >
              확인
            </button>
          </div>
        ) : (
          <button
            onClick={() => {
              setShowAdmin(true);
              setError(null);
            }}
            className="text-xs text-gray-300 hover:text-gray-500"
          >
            🔒 관리자
          </button>
        )}
      </div>
    </main>
  );
}
