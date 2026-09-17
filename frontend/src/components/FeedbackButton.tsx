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

type Shot = { id: number; filename: string; dataUrl: string; content: string; bytes: number };

const MAX_IMAGES = 3;
const MIN_MESSAGE = 2;

/**
 * 이미지를 캔버스로 줄여 JPEG로 다시 굽는다.
 * 원본 스크린샷을 그대로 올리면 프록시(Nginx 기본 1MB)에서 막히므로,
 * 목표 용량 이하가 될 때까지 크기·품질을 단계적으로 낮춘다.
 */
async function compress(file: File, targetBytes: number): Promise<{ dataUrl: string; bytes: number }> {
  const bitmap = await createImageBitmap(file);
  let maxSide = 1600;
  let quality = 0.82;
  for (let attempt = 0; attempt < 6; attempt++) {
    const scale = Math.min(1, maxSide / Math.max(bitmap.width, bitmap.height));
    const w = Math.max(1, Math.round(bitmap.width * scale));
    const h = Math.max(1, Math.round(bitmap.height * scale));
    const canvas = document.createElement('canvas');
    canvas.width = w; canvas.height = h;
    const ctx = canvas.getContext('2d');
    if (!ctx) break;
    ctx.fillStyle = '#fff';           // 투명 배경이 검게 나오지 않도록
    ctx.fillRect(0, 0, w, h);
    ctx.drawImage(bitmap, 0, 0, w, h);
    const dataUrl = canvas.toDataURL('image/jpeg', quality);
    const bytes = Math.round((dataUrl.length - dataUrl.indexOf(',') - 1) * 0.75);
    if (bytes <= targetBytes || attempt === 5) return { dataUrl, bytes };
    if (quality > 0.5) quality -= 0.12; else maxSide = Math.round(maxSide * 0.75);
  }
  const dataUrl = URL.createObjectURL(file);
  return { dataUrl, bytes: file.size };
}

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
  const [shots, setShots] = useState<Shot[]>([]);
  const [dragging, setDragging] = useState(false);
  const downOnBackdrop = useRef(false);
  const fileRef = useRef<HTMLInputElement>(null);
  const shotId = useRef(0);

  const addFiles = async (files: File[]) => {
    const imgs = files.filter((f) => f.type.startsWith('image/'));
    if (!imgs.length) return;
    setErr('');
    const room = MAX_IMAGES - shots.length;
    if (room <= 0) { setErr(`이미지는 최대 ${MAX_IMAGES}장까지 첨부할 수 있어요`); return; }
    // 전체 요청이 프록시 제한에 걸리지 않도록 장당 예산을 나눠 압축한다.
    const budget = Math.floor(600 * 1024 / Math.min(MAX_IMAGES, shots.length + imgs.length));
    for (const file of imgs.slice(0, room)) {
      try {
        const { dataUrl, bytes } = await compress(file, budget);
        setShots((prev) => prev.length >= MAX_IMAGES ? prev : [...prev, {
          id: ++shotId.current,
          filename: file.name || `capture${shotId.current}.jpg`,
          dataUrl,
          content: dataUrl.slice(dataUrl.indexOf(',') + 1),
          bytes,
        }]);
      } catch {
        setErr('이미지를 읽을 수 없어요');
      }
    }
  };

  // 닉네임은 비워 둔다(게임 닉네임을 자동으로 넣으면 익명으로 쓰고 싶은 사람이 곤란).
  useEffect(() => {
    if (!open) return;
    setDone(false); setErr('');
  }, [open]);

  // 모달이 열려 있는 동안 어디서 Ctrl+V 해도 스크린샷이 붙도록 문서 전체에서 받는다.
  useEffect(() => {
    if (!open || done) return;
    const onPaste = (e: ClipboardEvent) => {
      const files: File[] = [];
      for (const item of Array.from(e.clipboardData?.items ?? [])) {
        if (item.kind === 'file') {
          const f = item.getAsFile();
          if (f && f.type.startsWith('image/')) files.push(f);
        }
      }
      if (files.length) { e.preventDefault(); addFiles(files); }
    };
    document.addEventListener('paste', onPaste);
    return () => document.removeEventListener('paste', onPaste);
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [open, done, shots.length]);

  // 게임 중 실수로 닫히지 않도록 ESC만 허용
  useEffect(() => {
    if (!open) return;
    const onKey = (e: KeyboardEvent) => { if (e.key === 'Escape') setOpen(false); };
    window.addEventListener('keydown', onKey);
    return () => window.removeEventListener('keydown', onKey);
  }, [open]);

  const submit = async () => {
    const msg = message.trim();
    if (msg.length < MIN_MESSAGE) { setErr(`내용을 ${MIN_MESSAGE}자 이상 적어주세요`); return; }
    setSending(true); setErr('');
    try {
      await api('/api/v1/feedback', {
        method: 'POST',
        body: JSON.stringify({
          category, nickname: nickname.trim(), contact: contact.trim(), message: msg, page: pathname,
          images: shots.map((s) => ({ filename: s.filename, content: s.content })),
        }),
      });
      setDone(true); setMessage(''); setShots([]);
    } catch (e) {
      const m = e instanceof Error ? e.message : '';
      // 프록시가 큰 요청을 막으면 JSON이 아닌 응답이 와서 파싱 단계에서 실패한다.
      setErr(shots.length && (!m || /JSON|Unexpected|Failed to fetch/i.test(m))
        ? '이미지 전송에 실패했어요. 장수를 줄이거나 이미지 없이 보내주세요'
        : m || '전송에 실패했어요. 잠시 후 다시 시도해주세요');
    } finally {
      setSending(false);
    }
  };

  const hint = CATEGORIES.find((c) => c.key === category)?.hint ?? '';
  const tooShort = message.trim().length < MIN_MESSAGE;

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
                  {/* 버튼이 왜 안 눌리는지 바로 보이게 부족한 글자 수를 알려준다. */}
                  {tooShort ? (
                    <span className="text-[11px] font-bold text-amber-600">
                      {MIN_MESSAGE}자 이상 입력해주세요
                    </span>
                  ) : (
                    <span className="text-[11px] text-gray-400">현재 화면: {pathname}</span>
                  )}
                  <span className={`text-[11px] ${tooShort ? 'text-amber-600 font-bold' : 'text-gray-400'}`}>
                    {message.trim().length}/2000
                  </span>
                </div>

                {/* 스크린샷 첨부: 붙여넣기 · 파일 선택 · 드래그&드롭 */}
                <div
                  onDragOver={(e) => { e.preventDefault(); setDragging(true); }}
                  onDragLeave={() => setDragging(false)}
                  onDrop={(e) => { e.preventDefault(); setDragging(false); addFiles(Array.from(e.dataTransfer.files)); }}
                  className={`rounded-xl border-2 border-dashed p-2.5 transition ${dragging ? 'border-hit bg-hit/5' : 'border-gray-200'}`}
                >
                  <div className="flex items-center justify-between gap-2">
                    <span className="text-[11px] text-gray-400">
                      📎 화면 캡처 첨부 — <b>Ctrl+V로 붙여넣기</b>, 끌어다 놓기, 파일 선택 (최대 {MAX_IMAGES}장)
                    </span>
                    <button
                      onClick={() => fileRef.current?.click()}
                      disabled={shots.length >= MAX_IMAGES}
                      className="shrink-0 text-[11px] font-bold border-2 border-gray-200 rounded-lg px-2 py-1 text-gray-500 disabled:opacity-40 hover:border-hit"
                    >
                      파일 선택
                    </button>
                  </div>
                  <input
                    ref={fileRef}
                    type="file"
                    accept="image/*"
                    multiple
                    hidden
                    onChange={(e) => { addFiles(Array.from(e.target.files ?? [])); e.target.value = ''; }}
                  />
                  {shots.length > 0 && (
                    <div className="flex flex-wrap gap-2 mt-2">
                      {shots.map((s) => (
                        <div key={s.id} className="relative">
                          {/* eslint-disable-next-line @next/next/no-img-element */}
                          <img src={s.dataUrl} alt={s.filename} className="w-20 h-20 object-cover rounded-lg border-2 border-gray-200" />
                          <button
                            onClick={() => setShots((prev) => prev.filter((x) => x.id !== s.id))}
                            aria-label="이미지 삭제"
                            className="absolute -top-1.5 -right-1.5 w-5 h-5 rounded-full bg-gray-800 text-white text-xs leading-none shadow"
                          >
                            ×
                          </button>
                          <span className="absolute bottom-0 inset-x-0 bg-black/50 text-white text-[9px] text-center rounded-b-lg">
                            {Math.max(1, Math.round(s.bytes / 1024))}KB
                          </span>
                        </div>
                      ))}
                    </div>
                  )}
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
                  disabled={sending || tooShort}
                  className="w-full bg-hit text-white font-extrabold py-3 rounded-xl disabled:opacity-40 active:scale-[0.98] transition"
                >
                  {sending ? '보내는 중…' : tooShort ? `내용을 ${MIN_MESSAGE}자 이상 적어주세요` : '보내기'}
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
