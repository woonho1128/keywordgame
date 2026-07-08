import type { Metadata } from 'next';
import './globals.css';

export const metadata: Metadata = {
  title: 'gg — 파티·보드게임',
  description: '친구들과 모여 하는 파티·보드게임 (마피아, 아발론, 코드네임, 스파이폴 등)',
};

export default function RootLayout({
  children,
}: {
  children: React.ReactNode;
}) {
  return (
    <html lang="ko">
      <body>{children}</body>
    </html>
  );
}
