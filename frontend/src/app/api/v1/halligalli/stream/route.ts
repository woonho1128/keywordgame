// 할리갈리 SSE 전용 스트리밍 프록시.
// next.config의 rewrites는 스트림을 버퍼링할 수 있어서, 이 라우트 핸들러로
// 백엔드 SSE를 명시적으로 스트리밍해 프록시/CDN 버퍼링을 우회한다.
export const dynamic = 'force-dynamic';
export const runtime = 'nodejs';
export const fetchCache = 'force-no-store';

export async function GET(req: Request) {
  const url = new URL(req.url);
  const apiBase = process.env.API_BASE || 'http://localhost:8090';
  const upstream = await fetch(`${apiBase}/api/v1/halligalli/stream${url.search}`, {
    headers: { Accept: 'text/event-stream' },
    cache: 'no-store',
    signal: req.signal, // 클라이언트가 끊으면 백엔드 연결도 정리
  });

  return new Response(upstream.body, {
    status: upstream.status,
    headers: {
      'Content-Type': 'text/event-stream; charset=utf-8',
      'Cache-Control': 'no-cache, no-transform',
      Connection: 'keep-alive',
      'X-Accel-Buffering': 'no',
    },
  });
}
