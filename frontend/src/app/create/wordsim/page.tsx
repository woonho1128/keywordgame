'use client';

import { useState } from 'react';
import { useRouter } from 'next/navigation';
import { api, GameType } from '@/lib/api';

type CreateResp = {
  gameId: string;
  shareUrl: string;
  title: string;
  creatorNick: string;
  gameType: GameType;
  wordLength: number;
};

export default function CreateWordSimPage() {
  const router = useRouter();
  const [title, setTitle] = useState('');
  const [answerWord, setAnswerWord] = useState('');
  const [hintText, setHintText] = useState('');
  const [creatorNick, setCreatorNick] = useState('');
  const [submitting, setSubmitting] = useState(false);
  const [error, setError] = useState<string | null>(null);

  const handleSubmit = async (e: React.FormEvent) => {
    e.preventDefault();
    setError(null);

    if (!title.trim()) return setError('게임 제목을 입력해주세요');
    if (!creatorNick.trim()) return setError('출제자 닉네임을 입력해주세요');

    const normalized = answerWord.trim();
    if (!normalized) return setError('정답을 입력해주세요');
    if (normalized.length > 20) return setError('정답은 20자 이하여야 합니다');

    setSubmitting(true);
    try {
      const data = await api<CreateResp>('/api/v1/games', {
        method: 'POST',
        body: JSON.stringify({
          gameType: 'WORDSIM',
          title: title.trim(),
          answerWord: normalized,
          hintText: hintText.trim() || null,
          creatorNick: creatorNick.trim(),
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
      <h1 className="text-3xl font-bold mb-2">🔤 WordSim 출제</h1>
      <p className="text-gray-500 mb-8">
        정답 단어와 추측 단어의 의미 유사도를 점수로 표시합니다. 정답은 사전에 포함된 한국어 명사여야 합니다.
      </p>

      <form onSubmit={handleSubmit} className="space-y-6">
        <div>
          <label className="block text-sm font-medium mb-1">게임 제목 *</label>
          <input
            type="text"
            value={title}
            onChange={(e) => setTitle(e.target.value)}
            placeholder="예: 점심 내기 1차"
            maxLength={60}
            className="w-full border border-gray-300 rounded-lg px-3 py-2 focus:outline-none focus:border-move"
          />
          <p className="text-xs text-gray-400 mt-1">공유 시 어떤 게임인지 구분하는 이름입니다</p>
        </div>

        <div>
          <label className="block text-sm font-medium mb-1">정답 단어 *</label>
          <input
            type="text"
            value={answerWord}
            onChange={(e) => setAnswerWord(e.target.value)}
            placeholder="예: 사과"
            maxLength={20}
            className="w-full border border-gray-300 rounded-lg px-3 py-2 focus:outline-none focus:border-move"
          />
          <p className="text-xs text-gray-400 mt-1">사전에 등재된 일반 명사 (없으면 거부됩니다)</p>
        </div>

        <div>
          <label className="block text-sm font-medium mb-1">힌트 (선택)</label>
          <textarea
            value={hintText}
            onChange={(e) => setHintText(e.target.value)}
            placeholder="과일이에요"
            maxLength={500}
            rows={3}
            className="w-full border border-gray-300 rounded-lg px-3 py-2 focus:outline-none focus:border-move"
          />
        </div>

        <div>
          <label className="block text-sm font-medium mb-1">출제자 닉네임 *</label>
          <input
            type="text"
            value={creatorNick}
            onChange={(e) => setCreatorNick(e.target.value)}
            placeholder="우노"
            maxLength={50}
            className="w-full border border-gray-300 rounded-lg px-3 py-2 focus:outline-none focus:border-move"
          />
        </div>

        {error && <p className="text-red-500 text-sm">{error}</p>}

        <button
          type="submit"
          disabled={submitting}
          className="w-full bg-move text-white font-bold py-3 rounded-lg hover:opacity-90 disabled:opacity-50"
        >
          {submitting ? '생성 중...' : '게임 만들기'}
        </button>
      </form>
    </main>
  );
}
