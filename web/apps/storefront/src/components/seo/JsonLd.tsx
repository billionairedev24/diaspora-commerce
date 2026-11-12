import { type JsonLdObject, jsonLdScript } from '@/lib/seo/jsonld';

/** Server-rendered structured data: one <script type="application/ld+json"> per entity, in the first HTML response
    (never injected by client code). Entities that could not be built (undefined) render nothing. */
export function JsonLd({ data }: { data: readonly (JsonLdObject | undefined)[] }) {
  return (
    <>
      {data.map((d, i) => {
        const json = jsonLdScript(d);
        return json ? <script key={i} type="application/ld+json" dangerouslySetInnerHTML={{ __html: json }} /> : null;
      })}
    </>
  );
}
