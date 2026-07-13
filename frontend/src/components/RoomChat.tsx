'use client';

import { useEffect, useRef, useState } from 'react';
import { api } from '@/lib/api';

type Msg = { seq: number; nick: string; text: string; ts: number; mine: boolean };

/**
 * 방 단위 실시간 채팅 위젯(게임 공통). 우측 하단 플로팅 버튼 → 패널.
 * (game, roomCode)로 채널이 분리되어 방마다 채팅이 따로다.
 */
export default function RoomChat({ game, roomCode, clientId, nick }: {
  game: string; roomCode: string | null; clientId: string; nick: string;
}) {
  const [open, setOpen] = useState(false);
  const [msgs, setMsgs] = useState<Msg[]>([]);
  const [text, setText] = useState('');
  const [unread, setUnread] = useState(0);
  const sinceRef = useRef(0);
  const openRef = useRef(false);
  const listRef = useRef<HTMLDivElement>(null);

  useEffect(() => { openRef.current = open; if (open) setUnread(0); }, [open]);

  // 방이 바뀌면 초기화
  useEffect(() => { sinceRef.current = 0; setMsgs([]); setUnread(0); }, [game, roomCode]);

  useEffect(() => {
    if (!roomCode || !clientId) return;
    let alive = true;
    const poll = async () => {
      try {
        const res = await api<Msg[]>(`/api/v1/chat/messages?game=${encodeURIComponent(game)}&roomCode=${encodeURIComponent(roomCode || "")}&clientId=${encodeURIComponent(clientId)}&since=${sinceRef.current}`);
        if (!alive || !res.length) return;
        sinceRef.current = res[res.length - 1].seq;
        setMsgs((m) => [...m, ...res].slice(-200));
        if (!openRef.current) setUnread((u) => u + res.filter((x) => !x.mine).length);
      } catch {}
    };
    poll();
    const t = setInterval(poll, 2000);
    return () => { alive = false; clearInterval(t); };
  }, [game, roomCode, clientId]);

  useEffect(() => { if (open && listRef.current) listRef.current.scrollTop = listRef.current.scrollHeight; }, [msgs, open]);

  const send = async () => {
    const t = text.trim();
    if (!t) return;
    setText('');
    try {
      await api(`/api/v1/chat/send?game=${encodeURIComponent(game)}&roomCode=${encodeURIComponent(roomCode || "")}&clientId=${encodeURIComponent(clientId)}`,
        { method: 'POST', body: JSON.stringify({ nick: nick || '익명', text: t }) });
    } catch {}
  };

  if (!roomCode || !clientId) return null;

  return (
    <>
      {!open && (
        <button onClick={() => setOpen(true)} aria-label="채팅 열기"
          className="fixed bottom-4 right-4 z-40 w-14 h-14 rounded-full bg-hit text-white shadow-lg flex items-center justify-center text-2xl active:scale-95">
          💬
          {unread > 0 && <span className="absolute -top-1 -right-1 bg-red-500 text-white text-[11px] font-bold rounded-full min-w-5 h-5 px-1 flex items-center justify-center">{unread > 99 ? '99+' : unread}</span>}
        </button>
      )}

      {open && (
        <div className="fixed inset-0 z-40 sm:inset-auto sm:bottom-4 sm:right-4 flex items-end sm:items-stretch justify-center sm:justify-end bg-black/30 sm:bg-transparent" onClick={() => setOpen(false)}>
          <div onClick={(e) => e.stopPropagation()}
            className="w-full sm:w-80 h-[70vh] sm:h-[28rem] bg-white rounded-t-2xl sm:rounded-2xl shadow-xl flex flex-col overflow-hidden">
            <div className="px-4 py-2.5 border-b border-gray-100 flex items-center justify-between shrink-0">
              <span className="font-bold text-sm">💬 채팅 <span className="text-[11px] text-gray-400 font-normal">이 방 전용</span></span>
              <button onClick={() => setOpen(false)} className="w-8 h-8 rounded-full bg-gray-100 text-gray-500 flex items-center justify-center">✕</button>
            </div>
            <div ref={listRef} className="flex-1 overflow-y-auto px-3 py-2 space-y-1.5">
              {msgs.length === 0 && <p className="text-center text-gray-300 text-xs py-6">아직 메시지가 없어요. 먼저 인사해보세요!</p>}
              {msgs.map((m) => (
                <div key={m.seq} className={`flex flex-col ${m.mine ? 'items-end' : 'items-start'}`}>
                  {!m.mine && <span className="text-[10px] text-gray-400 px-1">{m.nick}</span>}
                  <span className={`inline-block max-w-[85%] px-3 py-1.5 rounded-2xl text-sm break-words ${m.mine ? 'bg-hit text-white rounded-br-sm' : 'bg-gray-100 text-gray-800 rounded-bl-sm'}`}>{m.text}</span>
                </div>
              ))}
            </div>
            <div className="p-2 border-t border-gray-100 flex gap-2 shrink-0">
              <input value={text} onChange={(e) => setText(e.target.value)} onKeyDown={(e) => { if (e.key === 'Enter') send(); }}
                maxLength={300} placeholder="메시지 입력..." className="flex-1 border border-gray-300 rounded-full px-3 py-2 text-sm focus:outline-none focus:border-hit" />
              <button onClick={send} className="bg-hit text-white font-bold px-4 rounded-full text-sm active:scale-95">전송</button>
            </div>
          </div>
        </div>
      )}
    </>
  );
}
