import Link from 'next/link';

export default function NotFound() {
  return (
    <main id="main" className="mx-auto flex w-[min(560px,100%)] flex-col items-center gap-3 px-4 py-20 text-center">
      <h1 className="m-0 text-2xl font-bold">Page not found</h1>
      <p className="m-0 text-muted">That page isn’t part of Seller Central.</p>
      <Link href="/">Back to your dashboard</Link>
    </main>
  );
}
