'use client';

import { useEffect, useState } from 'react';
import { useParams, useRouter } from 'next/navigation';
import { api, GameType, setSessionKey } from '@/lib/api';
import { SyllableResult } from '@/lib/hangul';
import { HangulBoard } from '@/components/wordguess/HangulBoard';
import { ShareButton } from '@/components/common/ShareButton';

type GameInfo = {
  gameId: string;
  gameType: GameType;
  wordLength: number;
  jamoCount: number | null;       // WordGuess만 채워짐
  hintText: string | null;
  creatorNick: string | null;
  playCount: number;
  solvedCount: number;
};

type StartResp = {
  recordId: number;
  sessionKey: string;
  gameType: GameType;
  wordLength: number;
  hintText: string | null;
  attemptCount: number;
  status: 'IN_PROGRESS' | 'SOLVED' | 'GAVE_UP';
};

type GuessResp = {
  guessWord: string;
  guessOrder: number;
  isCorrect: boolean;
  letterResult: SyllableResult[] | null;
};

type GuessHistoryItem = {
  guessWord: string;
  letterResult: SyllableResult[];
  isCorrect: boolean;
};

export default function PlayPage() {
  const params = useParams<{ gameId: string }>();
  const router = useRouter();

  const [game, setGame] = useState<GameInfo | null>(null);
  const [nick, setNick] = useState('');
  const [started, setStarted] = useState(false);
  const [status, setStatus] = useState<'IN_PROGRESS' | 'SOLVED' | 'GAVE_UP'>('IN_PROGRESS');
  const [guessInput, setGuessInput] = useState('');
  const [history, setHistory] = useState<GuessHistoryItem[]>([]);
  const [error, setError] = useState<string | null>(null);
  const [revealedAnswer, setRevealedAnswer] = useState<string | null>(null);

  // 1) 게임 메타 로드
  useEffect(() => {
    api<GameInfo>(`/api/v1/games/${params.gameId}`)
      .then(setGame)
      .catch((e) => setError(e.message));
  }, [params.gameId]);

  // 게임 시작
  const handleStart = async () => {
    setError(null);
    if (!nick.trim()) return setError('닉네임을 입력해주세요');
    try {
      const res = await api<StartResp>(`/api/v1/games/${params.gameId}/start`, {
        method: 'POST',
        body: JSON.stringify({ playerNick: nick.trim() }),
      });
      // sessionKey를 localStorage에 저장하여 이후 호출에 헤더로 첨부
      setSessionKey(params.gameId, res.sessionKey);
      setStarted(true);
      setStatus(res.status);
    } catch (e) {
      setError(e instanceof Error ? e.message : '게임 시작 실패');
    }
  };

  // 단어 추측
  const handleGuess = async (e: React.FormEvent) => {
    e.preventDefault();
    setError(null);
    const word = guessInput.trim();
    if (!word) return;

    try {
      const res = await api<GuessResp>(`/api/v1/games/${params.gameId}/guess`, {
        method: 'POST',
        body: JSON.stringify({ guessWord: word }),
        gameId: params.gameId,
      });
      if (res.letterResult) {
        setHistory((h) => [...h, {
          guessWord: res.guessWord,
          letterResult: res.letterResult!,
          isCorrect: res.isCorrect,
        }]);
      }
      if (res.isCorrect) setStatus('SOLVED');
      setGuessInput('');
    } catch (e) {
      setError(e instanceof Error ? e.message : '추측 실패');
    }
  };

  // 포기
  const handleGiveUp = async () => {
    if (!confirm('정말 포기하시겠습니까?')) return;
    try {
      const res = await api<{ answerWord: string; totalAttempts: number }>(
        `/api/v1/games/${params.gameId}/giveup`,
        { method: 'POST', gameId: params.gameId }
      );
      setStatus('GAVE_UP');
      setRevealedAnswer(res.answerWord);
    } catch (e) {
      setError(e instanceof Error ? e.message : '포기 실패');
    }
  };

  if (error && !game) {
    return <main className="p-8 text-red-500">{error}</main>;
  }

  if (!game) {
    return <main className="p-8 text-gray-400">불러오는 중...</main>;
  }

  // 현재 페이지 URL (브라우저 환경에서만 정확)
  const shareUrl = typeof window !== 'undefined' ? window.location.href : '';

  // ----- 닉네임 입력 화면 -----
  if (!started) {
    return (
      <main className="min-h-screen p-8 max-w-xl mx-auto">
        <div className="flex items-baseline justify-between mb-4">
          <h1 className="text-2xl font-bold">
            {game.gameType === 'WORDGUESS' ? '🟩 WordGuess' : '🔤 WordSim'}
          </h1>
          <div className="flex gap-4 items-baseline text-sm text-gray-500">
            <button
              onClick={() => router.push('/')}
              className="underline hover:text-hit"
              title="홈으로 (다른 게임 만들기 / 다른 게임 찾기)"
            >
              🏠 홈
            </button>
            <button
              onClick={() => router.push(`/g/${params.gameId}/board`)}
              className="underline hover:text-hit"
            >
              🏆 리더보드 →
            </button>
          </div>
        </div>
        <div className="bg-gray-50 rounded-lg p-4 mb-4">
          <p className="text-sm text-gray-500">출제자: {game.creatorNick || '익명'}</p>
          {game.gameType === 'WORDGUESS' && game.jamoCount != null && (
            <p className="text-sm text-gray-500">정답 자모수: {game.jamoCount}개</p>
          )}
          <p className="text-sm text-gray-500">
            플레이 {game.playCount}회 · 정답자 {game.solvedCount}명
          </p>
          {game.hintText && (
            <p className="mt-2"><span className="font-bold">힌트:</span> {game.hintText}</p>
          )}
        </div>

        {/* 공유 URL 영역 */}
        <div className="mb-6 border border-gray-200 rounded-lg p-4 bg-yellow-50/40">
          <p className="text-xs text-gray-500 mb-2">📨 친구에게 공유</p>
          <p className="text-xs text-gray-700 mb-3 break-all font-mono bg-white rounded p-2 border">
            {shareUrl}
          </p>
          <ShareButton url={shareUrl} />
        </div>

        <label className="block text-sm font-medium mb-1">닉네임</label>
        <input
          type="text"
          value={nick}
          onChange={(e) => setNick(e.target.value)}
          placeholder="재진"
          maxLength={50}
          className="w-full border border-gray-300 rounded-lg px-3 py-2 mb-4"
        />
        {error && <p className="text-red-500 text-sm mb-2">{error}</p>}
        <button
          onClick={handleStart}
          className="w-full bg-hit text-white font-bold py-3 rounded-lg hover:opacity-90"
        >
          게임 시작
        </button>
      </main>
    );
  }

  // ----- 플레이 화면 -----
  return (
    <main className="min-h-screen p-8 max-w-2xl mx-auto">
      <div className="flex items-baseline justify-between mb-6">
        <h1 className="text-2xl font-bold">{game.gameType === 'WORDGUESS' ? '🟩 WordGuess' : '🔤 WordSim'}</h1>
        <div className="flex gap-4 items-baseline text-sm text-gray-500">
          <button
            onClick={() => router.push('/')}
            className="underline hover:text-hit"
            title="홈으로"
          >
            🏠 홈
          </button>
          <button
            onClick={async () => {
              try {
                if (navigator.clipboard && window.isSecureContext) {
                  await navigator.clipboard.writeText(shareUrl);
                } else {
                  window.prompt('URL을 복사하세요:', shareUrl);
                }
              } catch {}
            }}
            className="underline hover:text-hit"
            title="이 게임 URL 복사"
          >
            🔗 공유
          </button>
          <button
            onClick={() => router.push(`/g/${params.gameId}/board`)}
            className="underline hover:text-hit"
          >
            🏆 리더보드 →
          </button>
        </div>
      </div>

      {game.hintText && (
        <div className="bg-yellow-50 rounded-lg p-3 mb-6 text-sm">
          💡 {game.hintText}
        </div>
      )}

      <HangulBoard history={history} />

      {status === 'IN_PROGRESS' && (
        <form onSubmit={handleGuess} className="mt-8 flex gap-2">
          <input
            type="text"
            value={guessInput}
            onChange={(e) => setGuessInput(e.target.value)}
            placeholder="한글로 추측"
            maxLength={20}
            className="flex-1 border border-gray-300 rounded-lg px-3 py-2"
          />
          <button type="submit" className="bg-hit text-white font-bold px-6 rounded-lg">
            추측
          </button>
          <button type="button" onClick={handleGiveUp} className="border border-gray-300 px-4 rounded-lg text-gray-500">
            포기
          </button>
        </form>
      )}

      {status === 'SOLVED' && (
        <div className="mt-8 bg-green-50 rounded-lg p-4 text-center">
          🎉 <span className="font-bold">정답입니다!</span> {history.length}번 시도
        </div>
      )}

      {status === 'GAVE_UP' && (
        <div className="mt-8 bg-gray-50 rounded-lg p-4 text-center">
          정답은 <span className="font-bold text-hit">{revealedAnswer}</span> 였습니다.
        </div>
      )}

      {error && <p className="text-red-500 text-sm mt-4">{error}</p>}
    </main>
  );
}
