'use client';

import Link from 'next/link';
import { usePathname } from 'next/navigation';

/** 홈(메인)을 제외한 모든 페이지 좌하단에 뜨는 홈 이동 버튼. */
export default function HomeButton() {
  const pathname = usePathname();
  if (pathname === '/') return null;
  return (
    <Link
      href="/"
      aria-label="메인으로"
      className="fixed bottom-3 left-3 z-50 flex items-center gap-1 rounded-full border border-gray-200 bg-white/90 px-3 py-2 text-sm font-bold text-gray-600 shadow-md backdrop-blur hover:bg-white hover:text-gray-900 active:scale-95"
    >
      🏠 홈
    </Link>
  );
}
