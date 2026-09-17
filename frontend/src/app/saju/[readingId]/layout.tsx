import type { Metadata } from 'next';

/**
 * 사주 결과 Open Graph 메타태그.
 * 카카오톡 공유 시 미리보기 카드에 총평이 보이도록 서버에서 미리 읽어온다.
 * page.tsx가 클라이언트 컴포넌트라 여기(서버 컴포넌트)에서 처리한다.
 */
export async function generateMetadata(
  { params }: { params: { readingId: string } }
): Promise<Metadata> {
  // 서버 사이드 fetch는 Next rewrites를 안 타므로 백엔드 절대주소 사용
  const apiBase = process.env.API_BASE || 'http://localhost:8090';
  try {
    const res = await fetch(`${apiBase}/api/v1/saju/${params.readingId}`, {
      cache: 'no-store',
    });
    const json = await res.json();
    const reading = json?.data;
    if (!reading) return {};

    const title = `${reading.typeEmoji} ${reading.nickname ? `${reading.nickname}님의 ` : ''}${reading.typeLabel}`;
    const description = reading.result?.headline || reading.result?.summary || 'AI가 풀어주는 사주';

    return {
      title: `${title} | WordPlay`,
      description,
      openGraph: {
        title,
        description,
        siteName: 'WordPlay',
        locale: 'ko_KR',
        type: 'website',
      },
    };
  } catch {
    return {};
  }
}

export default function SajuResultLayout({ children }: { children: React.ReactNode }) {
  return <>{children}</>;
}
