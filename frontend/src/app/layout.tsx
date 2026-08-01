import type { Metadata } from 'next';
import Script from 'next/script';
import './globals.css';
import HomeButton from '@/components/HomeButton';
import FeedbackButton from '@/components/FeedbackButton';

export const metadata: Metadata = {
  title: 'gg — 파티·보드게임',
  description: '친구들과 모여 하는 파티·보드게임 (마피아, 아발론, 코드네임, 스파이폴 등)',
};

// GTM 컨테이너 ID. 기본값으로 실제 컨테이너를 쓰고, 환경변수로 덮어쓸 수 있음.
const GTM_ID = process.env.NEXT_PUBLIC_GTM_ID || 'GTM-5QMQ5XWZ';

export default function RootLayout({
  children,
}: {
  children: React.ReactNode;
}) {
  return (
    <html lang="ko">
      <head>
        {GTM_ID && (
          <Script id="gtm-base" strategy="afterInteractive">
            {`(function(w,d,s,l,i){w[l]=w[l]||[];w[l].push({'gtm.start':new Date().getTime(),event:'gtm.js'});var f=d.getElementsByTagName(s)[0],j=d.createElement(s),dl=l!='dataLayer'?'&l='+l:'';j.async=true;j.src='https://www.googletagmanager.com/gtm.js?id='+i+dl;f.parentNode.insertBefore(j,f);})(window,document,'script','dataLayer','${GTM_ID}');`}
          </Script>
        )}
      </head>
      <body>
        {GTM_ID && (
          <noscript>
            <iframe
              src={`https://www.googletagmanager.com/ns.html?id=${GTM_ID}`}
              height="0"
              width="0"
              style={{ display: 'none', visibility: 'hidden' }}
            />
          </noscript>
        )}
        {children}
        <HomeButton />
        <FeedbackButton />
      </body>
    </html>
  );
}
