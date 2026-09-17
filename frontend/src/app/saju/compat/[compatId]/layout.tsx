import type { Metadata } from 'next';

/**
 * 궁합 결과 Open Graph 메타태그.
 * 카카오톡 공유 시 두 사람 이름과 점수가 미리보기에 보이도록 서버에서 미리 읽어온다.
 */
export async function generateMetadata(
  { params }: { params: { compatId: string } }
): Promise<Metadata> {
  const apiBase = process.env.API_BASE || 'http://localhost:8090';
  try {
    const res = await fetch(`${apiBase}/api/v1/saju/compat/${params.compatId}`, {
      cache: 'no-store',
    });
    const json = await res.json();
    const reading = json?.data;
    if (!reading) return {};

    const analysis = reading.analysis;
    const title = `${reading.typeEmoji} ${analysis?.aName ?? ''} 💞 ${analysis?.bName ?? ''} ${analysis?.score ?? ''}점`;
    const description = reading.result?.headline || reading.result?.summary || 'AI가 풀어주는 궁합';

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

export default function CompatResultLayout({ children }: { children: React.ReactNode }) {
  return <>{children}</>;
}
