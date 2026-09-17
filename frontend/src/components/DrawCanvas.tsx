'use client';

import { forwardRef, useEffect, useImperativeHandle, useRef, useState } from 'react';

export type DrawCanvasHandle = { toDataURL: () => string; clear: () => void };

const COLORS = ['#111827', '#ef4444', '#f59e0b', '#22c55e', '#3b82f6', '#a855f7', '#78350f', '#ffffff'];
const WIDTHS = [3, 6, 12, 22];

/** 마우스·터치·펜 지원 간단 드로잉 캔버스. toDataURL로 PNG 추출. */
const DrawCanvas = forwardRef<DrawCanvasHandle, { size?: number; onStrokeEnd?: () => void }>(function DrawCanvas({ size = 320, onStrokeEnd }, ref) {
  const canvasRef = useRef<HTMLCanvasElement>(null);
  const drawing = useRef(false);
  const last = useRef<{ x: number; y: number } | null>(null);
  const [color, setColor] = useState('#111827');
  const [width, setWidth] = useState(6);
  const colorRef = useRef(color);
  const widthRef = useRef(width);
  colorRef.current = color;
  widthRef.current = width;

  const fillWhite = () => {
    const c = canvasRef.current!;
    const ctx = c.getContext('2d')!;
    ctx.fillStyle = '#ffffff';
    ctx.fillRect(0, 0, c.width, c.height);
    ctx.lineCap = 'round';
    ctx.lineJoin = 'round';
  };

  useEffect(() => { fillWhite(); }, []);

  useImperativeHandle(ref, () => ({
    toDataURL: () => canvasRef.current!.toDataURL('image/png'),
    clear: () => fillWhite(),
  }));

  const posOf = (e: React.PointerEvent) => {
    const c = canvasRef.current!;
    const r = c.getBoundingClientRect();
    return { x: (e.clientX - r.left) * (c.width / r.width), y: (e.clientY - r.top) * (c.height / r.height) };
  };

  const down = (e: React.PointerEvent) => {
    (e.target as Element).setPointerCapture(e.pointerId);
    drawing.current = true;
    last.current = posOf(e);
    stroke(e); // 점 찍기
  };
  const stroke = (e: React.PointerEvent) => {
    if (!drawing.current) return;
    const c = canvasRef.current!;
    const ctx = c.getContext('2d')!;
    const p = posOf(e);
    const l = last.current ?? p;
    ctx.strokeStyle = colorRef.current;
    ctx.lineWidth = widthRef.current;
    ctx.beginPath();
    ctx.moveTo(l.x, l.y);
    ctx.lineTo(p.x, p.y);
    ctx.stroke();
    last.current = p;
  };
  const up = () => { if (drawing.current) { drawing.current = false; last.current = null; onStrokeEnd?.(); } };

  return (
    <div className="flex flex-col items-center gap-2">
      <canvas
        ref={canvasRef}
        width={size}
        height={size}
        onPointerDown={down}
        onPointerMove={stroke}
        onPointerUp={up}
        onPointerLeave={up}
        className="rounded-xl border-2 border-gray-300 bg-white touch-none max-w-full"
        style={{ width: size, height: size, aspectRatio: '1 / 1' }}
      />
      <div className="flex items-center gap-3 flex-wrap justify-center">
        <div className="flex gap-1">
          {COLORS.map((c) => (
            <button key={c} onClick={() => setColor(c)}
              className={`w-6 h-6 rounded-full border ${color === c ? 'ring-2 ring-offset-1 ring-gray-700' : 'border-gray-300'}`}
              style={{ backgroundColor: c }} aria-label={c} />
          ))}
        </div>
        <div className="flex gap-1 items-center">
          {WIDTHS.map((w) => (
            <button key={w} onClick={() => setWidth(w)}
              className={`w-7 h-7 rounded-full flex items-center justify-center border ${width === w ? 'border-gray-700 bg-gray-100' : 'border-gray-200'}`}>
              <span className="rounded-full bg-gray-800" style={{ width: w, height: w }} />
            </button>
          ))}
        </div>
        <button onClick={() => setColor('#ffffff')}
          className={`text-xs px-2 py-1 rounded-lg border ${color === '#ffffff' ? 'border-gray-700 bg-gray-100' : 'border-gray-300'}`}>지우개</button>
        <button onClick={() => fillWhite()} className="text-xs px-2 py-1 rounded-lg border border-red-300 text-red-500">전체 지우기</button>
      </div>
    </div>
  );
});

export default DrawCanvas;
