import type { JsonLdObject } from '@/lib/seo/jsonld';

/** Server-rendered structured data: one <script type="application/ld+json"> per entity, in the first HTML response
    (never injected by client code). "<" is escaped so no value can close the script element. Entities that could not be built (undefined) render nothing. */
export function JsonLd({ data }: { data: readonly (JsonLdObject | undefined)[] }) {
  return (
    <>
      {data.map((d, i) =>
        d ? <script key={i} type="application/ld+json" dangerouslySetInnerHTML={{ __html: JSON.stringify(d).replace(/</g, '\\u003c') }} /> : null,
      )}
    </>
  );
}
