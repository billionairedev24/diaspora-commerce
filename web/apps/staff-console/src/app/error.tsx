'use client';

export default function GlobalError({ reset }: { error: Error; reset: () => void }) {
  return (
    <main id="main" className="mx-auto flex w-[min(560px,100%)] flex-col items-center gap-3 px-4 py-20 text-center">
      <h1 className="m-0 text-2xl font-bold">Something went wrong</h1>
      <p className="m-0 text-muted">Please try again.</p>
      <button type="button" onClick={reset} className="rounded-pill bg-primary px-5 py-2 font-bold text-on-primary">
        Try again
      </button>
    </main>
  );
}
