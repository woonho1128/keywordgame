'use client';

import { useEffect, useRef, useState } from 'react';
import { usePathname } from 'next/navigation';
import { api } from '@/lib/api';

type Category = 'BUG' | 'IDEA' | 'GAME' | 'ETC';

const CATEGORIES: { key: Category; label: string; hint: string }[] = [
  { key: 'BUG', label: '🐞 버그 제보', hint: '이상하게 동작하는 부분을 알려주세요' },
  { key: 'IDEA', label: '💡 건의', hint: '이렇게 바뀌면 좋겠어요' },
  { key: 'GAME', label: '🎮 새 게임', hint: '추가했으면 하는 게임이 있나요?' },
  { key: 'ETC', label: '💬 기타', hint: '무엇이든 편하게 적어주세요' },
];

/** 모든 페이지 우하단(채팅 버튼과 겹치지 않게 좌측)에 뜨는 건의하기 버튼 + 모달. */
export default function FeedbackButton() {
  const pathname = usePathname();
  const [open, setOpen] = useState(false);
  const [category, setCategory] = useState<Category>('IDEA');
  const [nickname, setNickname] = useState('');
  const [contact, setContact] = useState('');
  const [message, setMessage] = useState('');
  const [sending, setSending] = useState(false);
  const [done, setDone] = useState(false);
  const [err, setErr] = useState('');
  const downOnBackdrop = useRef(false);

  // 닉네임은 비워 둔다(게임 닉네임을 자동으로 넣으면 익명으로 쓰고 싶은 사람이 곤란).
  useEffect(() => {
    if (!open) return;
    setDone(false); setErr('');
  }, [open]);

  // 게임 중 실수로 닫히지 않도록 ESC만 허용
  useEffect(() => {
    if (!open) return;
    const onKey = (e: KeyboardEvent) => { if (e.key === 'Escape') setOpen(false); };
    window.addEventListener('keydown', onKey);
    return () => window.removeEventListener('keydown', onKey);
  }, [open]);

  const submit = async () => {
    const msg = message.trim();
    if (msg.length < 5) { setErr('내용을 조금 더 자세히 적어주세요'); return; }
    setSending(true); setErr('');
    try {
      await api('/api/v1/feedback', {
        method: 'POST',
        body: JSON.stringify({ category, nickname: nickname.trim(), contact: contact.trim(), message: msg, page: pathname }),
      });
      setDone(true); setMessage('');
    } catch (e) {
      setErr(e instanceof Error ? e.message : '전송에 실패했어요. 잠시 후 다시 시도해주세요');
    } finally {
      setSending(false);
    }
  };

  const hint = CATEGORIES.find((c) => c.key === category)?.hint ?? '';

  return (
    <>
      <button
        onClick={() => setOpen(true)}
        aria-label="건의하기"
        className="fixed bottom-3 left-1/2 -translate-x-1/2 z-40 flex items-center gap-1 rounded-full border border-gray-200 bg-white/90 px-3 py-2 text-sm font-bold text-gray-600 shadow-md backdrop-blur hover:bg-white hover:text-gray-900 active:scale-95"
      >
        ✉️ 건의하기
      </button>

      {open && (
        <div
          className="fixed inset-0 z-[60] flex items-end sm:items-center justify-center bg-black/40 p-0 sm:p-4"
          // 모달 안에서 텍스트를 드래그하다 바깥에서 손을 떼면 click이 배경에서 발생해
          // 창이 닫히던 문제가 있었다. 누른 곳과 뗀 곳이 '모두' 배경일 때만 닫는다.
          onMouseDown={(e) => { downOnBackdrop.current = e.target === e.currentTarget; }}
          onClick={(e) => { if (e.target === e.currentTarget && downOnBackdrop.current) setOpen(false); }}
        >
          <div
            className="w-full sm:max-w-lg bg-white rounded-t-3xl sm:rounded-3xl shadow-2xl max-h-[90vh] overflow-y-auto"
            onClick={(e) => e.stopPropagation()}
          >
            <div className="flex items-center justify-between px-5 pt-5 pb-3">
              <h2 className="text-lg font-extrabold">✉️ 개발자에게 한마디</h2>
              <button onClick={() => setOpen(false)} aria-label="닫기" className="text-2xl leading-none text-gray-400 hover:text-gray-700">×</button>
            </div>

            {done ? (
              <div className="px-5 pb-6 text-center">
                <p className="text-4xl mb-2">🙏</p>
                <p className="font-bold">보내주셔서 감사합니다!</p>
                <p className="text-sm text-gray-500 mt-1">잘 읽어보고 반영할게요.</p>
                <div className="flex gap-2 mt-5">
                  <button onClick={() => setDone(false)} className="flex-1 border-2 border-gray-200 font-bold py-2.5 rounded-xl">하나 더 쓰기</button>
                  <button onClick={() => setOpen(false)} className="flex-1 bg-gray-800 text-white font-bold py-2.5 rounded-xl">닫기</button>
                </div>
              </div>
            ) : (
              <div className="px-5 pb-5 space-y-3">
                <div className="grid grid-cols-2 gap-2">
                  {CATEGORIES.map((c) => (
                    <button
                      key={c.key}
                      onClick={() => setCategory(c.key)}
                      className={`rounded-xl border-2 py-2.5 text-sm font-bold transition ${
                        category === c.key ? 'border-hit bg-hit/10 text-hit' : 'border-gray-200 text-gray-500 hover:border-hit'
                      }`}
                    >
                      {c.label}
                    </button>
                  ))}
                </div>

                <textarea
                  value={message}
                  onChange={(e) => { setMessage(e.target.value); setErr(''); }}
                  maxLength={2000}
                  rows={6}
                  placeholder={hint}
                  className="w-full border-2 border-gray-200 rounded-xl px-3 py-2.5 text-sm resize-none focus:outline-none focus:border-hit"
                />
                <div className="flex justify-between items-center -mt-2">
                  <span className="text-[11px] text-gray-400">현재 화면: {pathname}</span>
                  <span className="text-[11px] text-gray-400">{message.length}/2000</span>
                </div>

                <div className="flex gap-2">
                  <input
                    value={nickname}
                    onChange={(e) => setNickname(e.target.value)}
                    maxLength={32}
                    placeholder="닉네임 (선택)"
                    className="flex-1 min-w-0 border-2 border-gray-200 rounded-xl px-3 py-2.5 text-sm focus:outline-none focus:border-hit"
                  />
                  <input
                    value={contact}
                    onChange={(e) => setContact(e.target.value)}
                    maxLength={120}
                    placeholder="답장받을 연락처 (선택)"
                    className="flex-1 min-w-0 border-2 border-gray-200 rounded-xl px-3 py-2.5 text-sm focus:outline-none focus:border-hit"
                  />
                </div>

                {err && <p className="text-red-500 text-xs">{err}</p>}

                <button
                  onClick={submit}
                  disabled={sending || message.trim().length < 5}
                  className="w-full bg-hit text-white font-extrabold py-3 rounded-xl disabled:opacity-40 active:scale-[0.98] transition"
                >
                  {sending ? '보내는 중…' : '보내기'}
                </button>
                <p className="text-[11px] text-gray-400 text-center">
                  버그 제보는 어떤 게임에서 무엇을 하다 생겼는지 적어주시면 큰 도움이 돼요.
                </p>
              </div>
            )}
          </div>
        </div>
      )}
    </>
  );
}
