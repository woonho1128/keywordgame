'use client';

import { useState } from 'react';
import { useRouter } from 'next/navigation';
import { api, GameType } from '@/lib/api';
import { isAllHangulSyllables } from '@/lib/hangul';

type CreateResp = {
  gameId: string;
  shareUrl: string;
  gameType: GameType;
  wordLength: number;
};

export default function CreateWordGuessPage() {
  const router = useRouter();
  const [answerWord, setAnswerWord] = useState('');
  const [hintText, setHintText] = useState('');
  const [creatorNick, setCreatorNick] = useState('');
  const [submitting, setSubmitting] = useState(false);
  const [error, setError] = useState<string | null>(null);

  const handleSubmit = async (e: React.FormEvent) => {
    e.preventDefault();
    setError(null);

    const normalized = answerWord.trim();
    if (!normalized) return setError('정답을 입력해주세요');
    if (!isAllHangulSyllables(normalized)) {
      return setError('한글 음절만 입력 가능합니다 (예: 사과)');
    }
    if (normalized.length > 20) return setError('정답은 20자 이하여야 합니다');

    setSubmitting(true);
    try {
      const data = await api<CreateResp>('/api/v1/games', {
        method: 'POST',
        body: JSON.stringify({
          gameType: 'WORDGUESS',
          answerWord: normalized,
          hintText: hintText.trim() || null,
          creatorNick: creatorNick.trim() || null,
          isPublic: true,
        }),
      });
      router.push(`/g/${data.gameId}`);
    } catch (err) {
      setError(err instanceof Error ? err.message : '게임 생성 실패');
    } finally {
      setSubmitting(false);
    }
  };

  return (
    <main className="min-h-screen p-8 max-w-xl mx-auto">
      <h1 className="text-3xl font-bold mb-2">WordGuess 출제</h1>
      <p className="text-gray-500 mb-8">정답 단어와 힌트를 입력하세요. 친구에게 공유 URL을 전달하면 끝!</p>

      <form onSubmit={handleSubmit} className="space-y-6">
        <div>
          <label className="block text-sm font-medium mb-1">정답 단어 *</label>
          <input
            type="text"
            value={answerWord}
            onChange={(e) => setAnswerWord(e.target.value)}
            placeholder="예: 사과"
            maxLength={20}
            className="w-full border border-gray-300 rounded-lg px-3 py-2 focus:outline-none focus:border-hit"
          />
          <p className="text-xs text-gray-400 mt-1">한글 음절만 가능, 1~20자</p>
        </div>

        <div>
          <label className="block text-sm font-medium mb-1">힌트 (선택)</label>
          <textarea
            value={hintText}
            onChange={(e) => setHintText(e.target.value)}
            placeholder="빨갛고 둥근 과일"
            maxLength={500}
            rows={3}
            className="w-full border border-gray-300 rounded-lg px-3 py-2 focus:outline-none focus:border-hit"
          />
        </div>

        <div>
          <label className="block text-sm font-medium mb-1">출제자 닉네임 (선택)</label>
          <input
            type="text"
            value={creatorNick}
            onChange={(e) => setCreatorNick(e.target.value)}
            placeholder="우노"
            maxLength={50}
            className="w-full border border-gray-300 rounded-lg px-3 py-2 focus:outline-none focus:border-hit"
          />
        </div>

        {error && <p className="text-red-500 text-sm">{error}</p>}

        <button
          type="submit"
          disabled={submitting}
          className="w-full bg-hit text-white font-bold py-3 rounded-lg hover:opacity-90 disabled:opacity-50"
        >
          {submitting ? '생성 중...' : '게임 만들기'}
        </button>
      </form>
    </main>
  );
}
