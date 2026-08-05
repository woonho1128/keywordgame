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

  /*
   * 플로팅 버튼 위치 이동.
   * 홈·건의하기 버튼과 겹쳐 화면 아래쪽 내용을 가린다는 지적이 있어, 길게 눌러
   * 원하는 곳으로 옮길 수 있게 했다. 위치는 기기에 저장되어 다음에도 유지된다.
   */
  const LONG_PRESS_MS = 350;
  const [pos, setPos] = useState<{ x: number; y: number } | null>(null);
  const [dragging, setDragging] = useState(false);
  const pressTimer = useRef<ReturnType<typeof setTimeout> | null>(null);
  const movedRef = useRef(false);
  const dragRef = useRef(false);

  useEffect(() => {
    try {
      const raw = localStorage.getItem('room_chat_btn_pos');
      if (raw) setPos(JSON.parse(raw));
    } catch {}
  }, []);

  // 화면 밖으로 나가지 않게 가둔다(기기 회전·창 크기 변경 대비).
  const clamp = (x: number, y: number) => ({
    x: Math.min(Math.max(x, 8), Math.max(8, window.innerWidth - 64)),
    y: Math.min(Math.max(y, 8), Math.max(8, window.innerHeight - 64)),
  });

  useEffect(() => {
    if (!pos) return;
    const onResize = () => setPos((p) => (p ? clamp(p.x, p.y) : p));
    window.addEventListener('resize', onResize);
    return () => window.removeEventListener('resize', onResize);
  }, [pos]);

  const startPress = (clientX: number, clientY: number) => {
    movedRef.current = false;
    pressTimer.current = setTimeout(() => {
      dragRef.current = true;
      setDragging(true);
      setPos(clamp(clientX - 28, clientY - 28));
      if (navigator.vibrate) navigator.vibrate(30);   // 잡혔다는 신호
    }, LONG_PRESS_MS);
  };

  const movePress = (clientX: number, clientY: number) => {
    if (!dragRef.current) {
      // 길게 누르기 전에 움직였으면 스크롤 의도다 — 드래그로 오인하지 않는다.
      movedRef.current = true;
      if (pressTimer.current) { clearTimeout(pressTimer.current); pressTimer.current = null; }
      return;
    }
    setPos(clamp(clientX - 28, clientY - 28));
  };

  const endPress = () => {
    if (pressTimer.current) { clearTimeout(pressTimer.current); pressTimer.current = null; }
    if (dragRef.current) {
      dragRef.current = false;
      setDragging(false);
      setPos((p) => {
        if (p) { try { localStorage.setItem('room_chat_btn_pos', JSON.stringify(p)); } catch {} }
        return p;
      });
      return;
    }
    if (!movedRef.current) setOpen(true);   // 짧게 눌렀으면 평소대로 열기
  };

  useEffect(() => {
    if (!dragging) return;
    const move = (e: PointerEvent) => { e.preventDefault(); movePress(e.clientX, e.clientY); };
    const up = () => endPress();
    window.addEventListener('pointermove', move, { passive: false });
    window.addEventListener('pointerup', up);
    window.addEventListener('pointercancel', up);
    return () => {
      window.removeEventListener('pointermove', move);
      window.removeEventListener('pointerup', up);
      window.removeEventListener('pointercancel', up);
    };
  }, [dragging]);

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
        <button
          aria-label="채팅 열기 (길게 누르면 위치 이동)"
          title="길게 눌러 원하는 곳으로 옮길 수 있어요"
          onPointerDown={(e) => { e.preventDefault(); startPress(e.clientX, e.clientY); }}
          onPointerMove={(e) => { if (!dragging) movePress(e.clientX, e.clientY); }}
          onPointerUp={() => { if (!dragging) endPress(); }}
          onPointerLeave={() => { if (!dragging && pressTimer.current) { clearTimeout(pressTimer.current); pressTimer.current = null; } }}
          style={pos ? { left: pos.x, top: pos.y, right: 'auto', bottom: 'auto', touchAction: 'none' } : { touchAction: 'none' }}
          className={`fixed bottom-4 right-4 z-40 w-14 h-14 rounded-full bg-hit text-white shadow-lg flex items-center justify-center text-2xl
            ${dragging ? 'scale-110 ring-4 ring-hit/30 cursor-grabbing' : 'active:scale-95'}`}>
          💬
          {unread > 0 && !dragging && <span className="absolute -top-1 -right-1 bg-red-500 text-white text-[11px] font-bold rounded-full min-w-5 h-5 px-1 flex items-center justify-center">{unread > 99 ? '99+' : unread}</span>}
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
