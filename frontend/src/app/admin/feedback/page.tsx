'use client';

import { useState } from 'react';
import { api } from '@/lib/api';

type Item = {
  id: number; category: string; nickname: string | null; contact: string | null;
  message: string; page: string | null; mailSent: boolean; createdAt: string;
};

const LABEL: Record<string, string> = {
  BUG: '🐞 버그', IDEA: '💡 건의', GAME: '🎮 새 게임', ETC: '💬 기타',
};

export default function AdminFeedbackPage() {
  const [code, setCode] = useState('');
  const [items, setItems] = useState<Item[] | null>(null);
  const [err, setErr] = useState('');
  const [loading, setLoading] = useState(false);

  const load = async (c?: string) => {
    const key = (c ?? code).trim();
    if (!key) return;
    setLoading(true); setErr('');
    try {
      setItems(await api<Item[]>(`/api/v1/feedback?code=${encodeURIComponent(key)}`));
    } catch (e) {
      setErr(e instanceof Error ? e.message : '조회 실패');
      setItems(null);
    } finally {
      setLoading(false);
    }
  };

  return (
    <main className="min-h-screen max-w-3xl mx-auto p-4 sm:p-6">
      <h1 className="text-2xl font-extrabold mb-4">✉️ 건의사항</h1>

      <div className="flex gap-2 mb-4">
        <input
          type="password"
          value={code}
          onChange={(e) => setCode(e.target.value)}
          onKeyDown={(e) => e.key === 'Enter' && load()}
          placeholder="관리자 코드"
          className="flex-1 border-2 border-gray-200 rounded-xl px-3 py-2.5 text-sm focus:outline-none focus:border-hit"
        />
        <button onClick={() => load()} disabled={loading || !code.trim()}
          className="px-5 bg-gray-800 text-white font-bold rounded-xl text-sm disabled:opacity-40">
          {loading ? '…' : '조회'}
        </button>
      </div>
      {err && <p className="text-red-500 text-sm mb-3">{err}</p>}

      {items && (
        <>
          <p className="text-sm text-gray-400 mb-2">최근 {items.length}건</p>
          <div className="space-y-2">
            {items.map((it) => (
              <div key={it.id} className="border-2 border-gray-200 rounded-xl p-3">
                <div className="flex items-center justify-between text-xs text-gray-400 mb-1.5">
                  <span className="font-bold text-gray-600">
                    {LABEL[it.category] ?? it.category}
                    {it.nickname ? ` · ${it.nickname}` : ' · 익명'}
                  </span>
                  <span>{it.createdAt}{it.mailSent ? ' · 📧' : ' · ✉️❌'}</span>
                </div>
                <p className="text-sm whitespace-pre-wrap">{it.message}</p>
                <div className="flex gap-3 mt-2 text-[11px] text-gray-400">
                  {it.page && <span>화면 {it.page}</span>}
                  {it.contact && <span>연락처 {it.contact}</span>}
                </div>
              </div>
            ))}
            {items.length === 0 && <p className="text-gray-400 text-center py-10">아직 접수된 건의가 없어요.</p>}
          </div>
        </>
      )}
    </main>
  );
}
