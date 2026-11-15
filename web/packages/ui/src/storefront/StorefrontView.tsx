'use client';

import { type ResolvedTheme, themeCssVars } from '@afrotransact/tokens';
import { type CSSProperties, type ReactNode, useCallback, useEffect, useId, useMemo, useRef, useState } from 'react';

import { cx } from '../cx';
import { MagnifyingGlass, MapPin, SealCheck, ShoppingCart, User, X } from '../icons';
import styles from './Storefront.module.css';
import { ProductMeta, StoreProductTile } from './StoreProductTile';
import {
  defaultStorefrontLabels,
  EMPTY_QUERY,
  type StorefrontCategoryModel,
  type StorefrontCommerce,
  type StorefrontLabels,
  type StorefrontModel,
  type StorefrontPageModel,
  type StorefrontProductModel,
  type StorefrontQueryState,
  type StorefrontSort,
  type StoreSectionId
} from './types';

export interface StorefrontViewProps {
  model: StorefrontModel;
  theme: ResolvedTheme;
  commerce: StorefrontCommerce;
  labels?: StorefrontLabels;
  /** BCP 47 tag for prices and counts. */
  locale?: string;
  /** Loads a page (1-based) of the All products grid. The first page for `initialQuery` is `model.products`. */
  loadProducts: (q: StorefrontQueryState, page: number) => Promise<StorefrontPageModel>;
  initialQuery?: StorefrontQueryState;
  /** Called when the shopper changes the search, category, price or sort (the app mirrors it into the URL). */
  onQueryChange?: (q: StorefrontQueryState) => void;
  /** Preview inside Seller Center: buttons are inert, the header is not sticky. */
  preview?: boolean;
  /** Extra content at the very end (nothing in live). */
  children?: ReactNode;
}

const same = (a: StorefrontQueryState, b: StorefrontQueryState) => a.q === b.q && a.category === b.category && a.price === b.price && a.sort === b.sort;
const paragraphs = (text: string) => text.split(/\n{2,}/).map((p) => p.trim()).filter(Boolean);

/** Plain text with line breaks: paragraphs on blank lines, single breaks kept. Never HTML. */
function PlainText({ text, className }: { text: string; className?: string }) {
  return (
    <>
      {paragraphs(text).map((p, i) => (
        <p key={i} className={cx(styles.prose, className)}>
          {p.split('\n').map((line, j, all) => (
            <span key={j}>
              {line}
              {j < all.length - 1 ? <br /> : null}
            </span>
          ))}
        </p>
      ))}
    </>
  );
}

function Anchor({ as: Link, href, className, children, label }: { as: StorefrontCommerce['linkAs']; href: string; className?: string; children: ReactNode; label?: string }) {
  return Link ? (
    <Link href={href} className={className} aria-label={label}>
      {children}
    </Link>
  ) : (
    <a href={href} className={className} aria-label={label}>
      {children}
    </a>
  );
}

function useGrid(model: StorefrontModel, initial: StorefrontQueryState, load: StorefrontViewProps['loadProducts']) {
  const [query, setQuery] = useState<StorefrontQueryState>(initial);
  const [items, setItems] = useState<StorefrontProductModel[]>(model.products.hits);
  const [meta, setMeta] = useState({ page: model.products.page, pageCount: model.products.pageCount, total: model.products.total });
  const [status, setStatus] = useState<'idle' | 'loading' | 'more' | 'error'>('idle');
  const firstRun = useRef(true);
  const token = useRef(0);

  // The model changes (preview edits, a new page of data): start again from it.
  useEffect(() => {
    token.current++;
    setItems(model.products.hits);
    setMeta({ page: model.products.page, pageCount: model.products.pageCount, total: model.products.total });
    setStatus('idle');
    firstRun.current = same(query, initial);
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [model.products]);

  const run = useCallback(
    async (q: StorefrontQueryState, page: number) => {
      const mine = ++token.current;
      setStatus(page === 1 ? 'loading' : 'more');
      try {
        const res = await load(q, page);
        if (mine !== token.current) return;
        setItems((prev) => (page === 1 ? res.hits : [...prev, ...res.hits.filter((h) => !prev.some((p) => p.atin === h.atin))]));
        setMeta({ page: res.page, pageCount: res.pageCount, total: res.total });
        setStatus('idle');
      } catch {
        if (mine === token.current) setStatus('error');
      }
    },
    [load]
  );

  useEffect(() => {
    if (firstRun.current) {
      firstRun.current = false;
      return;
    }
    const t = setTimeout(() => void run(query, 1), query.q === initial.q ? 0 : 280);
    return () => clearTimeout(t);
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [query]);

  return { query, setQuery, items, meta, status, more: () => void run(query, meta.page + 1), retry: () => void run(query, 1) };
}

function QuickView({ product, labels, commerce, locale, onClose, inert }: { product: StorefrontProductModel; labels: StorefrontLabels; commerce: StorefrontCommerce; locale: string; onClose: () => void; inert: boolean }) {
  const ref = useRef<HTMLDialogElement>(null);
  const titleId = useId();
  useEffect(() => {
    const el = ref.current;
    if (!el) return;
    if (typeof el.showModal === 'function') el.showModal();
    else el.setAttribute('open', '');
    return () => {
      if (typeof el.close === 'function' && el.open) el.close();
    };
  }, []);
  const Link = commerce.linkAs;
  const detail = commerce.productHref(product.atin);
  const inCart = commerce.quantityInCart(product.offerId);
  return (
    // Clicking the backdrop closes the modal (a mouse convenience: Escape and the Close button are the keyboard routes).
    // eslint-disable-next-line jsx-a11y/click-events-have-key-events, jsx-a11y/no-noninteractive-element-interactions
    <dialog ref={ref} className={styles.quick} aria-labelledby={titleId} onClose={onClose} onClick={(e) => e.target === ref.current && ref.current?.close()}>
      <div className={styles.quickBody}>
        <button type="button" className={styles.quickClose} onClick={() => ref.current?.close()} aria-label={labels.closeLabel}>
          <X size={20} aria-hidden />
        </button>
        <div className={styles.quickMedia}>{product.imageUrl ? <img src={product.imageUrl} alt={product.title} className={styles.quickImg} /> : null}</div>
        <div className={styles.quickInfo}>
          {product.brand ? <span className={styles.eyebrow}>{product.brand}</span> : null}
          <h2 id={titleId} className={styles.quickTitle}>
            {product.title}
          </h2>
          <ProductMeta product={product} labels={labels} locale={locale} large />
          <div className={styles.quickActions}>
            <button type="button" className={styles.primary} disabled={inert || commerce.busy} onClick={() => commerce.onAdd(product)}>
              {inCart > 0 ? labels.inCart(inCart) : labels.addToCart}
            </button>
            <button type="button" className={styles.secondary} disabled={inert || commerce.busy} onClick={() => commerce.onBuyNow(product)}>
              {labels.buyNow}
            </button>
          </div>
          {Link ? (
            <Link href={detail} className={styles.textLink}>
              {labels.viewDetails}
            </Link>
          ) : (
            <a href={detail} className={styles.textLink}>
              {labels.viewDetails}
            </a>
          )}
        </div>
      </div>
    </dialog>
  );
}

function Hero({ model, labels, locale }: { model: StorefrontModel; labels: StorefrontLabels; locale: string }) {
  const { hero } = model.config;
  const banner = hero.bannerUrl;
  // A hero that asks for an image with none uploaded gets the colour treatment: never an empty frame.
  const style = hero.style === 'image' && !banner ? 'colour' : hero.style === 'split' && !banner ? 'colour' : hero.style;
  const tagline = hero.tagline.trim();
  const since = model.sellingSinceYear;
  const facts = (
    <ul className={styles.facts}>
      {model.verified ? (
        <li className={styles.fact}>
          <SealCheck size={16} weight="fill" aria-hidden /> {labels.verified}
        </li>
      ) : null}
      {since ? <li className={styles.fact}>{labels.sellingSince(since)}</li> : null}
      {model.reviews ? (
        <li className={styles.fact}>
          {labels.reviewsSummary(model.reviews.average.toLocaleString(locale, { minimumFractionDigits: 1, maximumFractionDigits: 1 }), model.reviews.count.toLocaleString(locale))}
        </li>
      ) : null}
    </ul>
  );
  const identity = (
    <div className={styles.heroText}>
      {model.logoUrl ? <img src={model.logoUrl} alt="" className={styles.heroLogo} width={88} height={88} /> : null}
      <h1 className={styles.heroName}>{model.name}</h1>
      {tagline ? <p className={styles.heroTagline}>{tagline}</p> : null}
      {facts}
      <a href="#sf-products" className={styles.heroCta}>
        {labels.shopAll}
      </a>
    </div>
  );
  if (style === 'split' && banner) {
    return (
      <section className={cx(styles.hero, styles.heroSplit)} aria-label={model.name}>
        <div className={styles.splitText}>{identity}</div>
        <div className={styles.splitMedia}>
          <img src={banner} alt="" className={styles.heroImg} style={{ objectPosition: `${hero.focalX}% ${hero.focalY}%` }} fetchPriority="high" />
        </div>
      </section>
    );
  }
  if (style === 'image' && banner) {
    return (
      <section className={cx(styles.hero, styles.heroImage)} aria-label={model.name}>
        <img src={banner} alt="" className={styles.heroImg} style={{ objectPosition: `${hero.focalX}% ${hero.focalY}%` }} fetchPriority="high" />
        <div className={styles.heroScrim} aria-hidden />
        <div className={styles.heroOverlay}>{identity}</div>
      </section>
    );
  }
  return (
    <section className={cx(styles.hero, styles.heroColour)} aria-label={model.name}>
      {identity}
    </section>
  );
}

function CategoryTiles({ categories, labels, onPick }: { categories: StorefrontCategoryModel[]; labels: StorefrontLabels; onPick: (name: string) => void }) {
  return (
    <section className={styles.section} aria-labelledby="sf-cats">
      <h2 id="sf-cats" className={styles.h2}>
        {labels.shopByCategory}
      </h2>
      <ul className={styles.catTiles}>
        {categories.map((c) => (
          <li key={c.name}>
            <button type="button" className={styles.catTile} onClick={() => onPick(c.name)}>
              <span className={styles.catName}>{c.name}</span>
              <span className={styles.catCount}>{labels.categoryCount(c.count)}</span>
            </button>
          </li>
        ))}
      </ul>
    </section>
  );
}

export function StorefrontView({ model, theme, commerce, labels = defaultStorefrontLabels, locale = 'en-US', loadProducts, initialQuery = EMPTY_QUERY, onQueryChange, preview = false, children }: StorefrontViewProps) {
  const cfg = model.config;
  const grid = useGrid(model, initialQuery, loadProducts);
  const [quick, setQuick] = useState<StorefrontProductModel | null>(null);
  const vars = useMemo(() => themeCssVars(theme) as CSSProperties, [theme]);
  const productsRef = useRef<HTMLElement>(null);
  const Link = commerce.linkAs;
  const searchId = useId();

  const update = (patch: Partial<StorefrontQueryState>) => {
    const next = { ...grid.query, ...patch };
    grid.setQuery(next);
    onQueryChange?.(next);
  };
  const pickCategory = (name: string) => {
    update({ category: name });
    productsRef.current?.scrollIntoView({ behavior: window.matchMedia?.('(prefers-reduced-motion: reduce)').matches ? 'auto' : 'smooth', block: 'start' });
  };

  const filtered = grid.query.q !== '' || grid.query.category !== '' || grid.query.price !== '';
  const announcement = cfg.sections.find((s) => s.id === 'announcement');
  const showAnnouncement = !!announcement?.enabled && cfg.announcement.text.trim() !== '';
  const tabs = (cfg.categories.mode === 'chosen' && cfg.categories.chosen.length
    ? model.categories.filter((c) => cfg.categories.chosen.includes(c.name))
    : model.categories
  ).slice(0, 8);

  const tile = (p: StorefrontProductModel, i: number, eager = false) => (
    <StoreProductTile key={p.atin} product={p} labels={labels} locale={locale} commerce={commerce} inert={preview} eager={eager && i < 4} onQuickView={() => setQuick(p)} />
  );

  const sectionNodes: Record<Exclude<StoreSectionId, 'announcement'>, ReactNode> = {
    featured:
      model.featured.length > 0 ? (
        <section key="featured" className={styles.section} aria-labelledby="sf-featured">
          <h2 id="sf-featured" className={styles.h2}>
            {labels.featured}
          </h2>
          <ul className={cx(styles.grid, styles.featuredGrid)}>{model.featured.slice(0, 4).map((p, i) => <li key={p.atin}>{tile(p, i)}</li>)}</ul>
        </section>
      ) : null,
    categories: tabs.length > 1 ? <CategoryTiles key="categories" categories={tabs} labels={labels} onPick={pickCategory} /> : null,
    about:
      cfg.about.body.trim() || cfg.about.photoUrl ? (
        <section key="about" className={cx(styles.section, styles.about, cfg.about.photoUrl && styles.aboutWithPhoto)} aria-labelledby="sf-about">
          {cfg.about.photoUrl ? <img src={cfg.about.photoUrl} alt="" className={styles.aboutImg} loading="lazy" decoding="async" /> : null}
          <div className={styles.aboutText}>
            <h2 id="sf-about" className={styles.h2}>
              {cfg.about.heading.trim() || labels.about}
            </h2>
            <PlainText text={cfg.about.body.trim() || model.description || ''} />
          </div>
        </section>
      ) : null,
    policies:
      cfg.policies.shipping.trim() || cfg.policies.returns.trim() || model.pickup ? (
        <section key="policies" className={styles.section} aria-labelledby="sf-policies">
          <h2 id="sf-policies" className={styles.h2}>
            {labels.policies}
          </h2>
          <div className={styles.policyCols}>
            {cfg.policies.shipping.trim() ? (
              <div>
                <h3 className={styles.h3}>{labels.shipping}</h3>
                <PlainText text={cfg.policies.shipping} />
              </div>
            ) : null}
            {cfg.policies.returns.trim() ? (
              <div>
                <h3 className={styles.h3}>{labels.returns}</h3>
                <PlainText text={cfg.policies.returns} />
              </div>
            ) : null}
            {model.pickup ? (
              <p className={styles.pickup}>
                <MapPin size={18} weight="fill" aria-hidden /> {labels.pickup(model.pickup.zip)}
              </p>
            ) : null}
          </div>
        </section>
      ) : null,
    reviews: model.reviews ? (
      <section key="reviews" className={cx(styles.section, styles.reviews)} aria-labelledby="sf-reviews">
        <h2 id="sf-reviews" className={styles.h2}>
          {labels.reviewsTitle}
        </h2>
        <p className={styles.reviewLine}>
          <strong className={styles.reviewScore}>{model.reviews.average.toLocaleString(locale, { minimumFractionDigits: 1, maximumFractionDigits: 1 })}</strong>
          <span>{labels.reviewsSummary(model.reviews.average.toLocaleString(locale, { minimumFractionDigits: 1, maximumFractionDigits: 1 }), model.reviews.count.toLocaleString(locale))}</span>
        </p>
      </section>
    ) : null
  };

  const productsBlock = (
    <section key="products" id="sf-products" ref={productsRef} className={cx(styles.section, styles.productsSection)} aria-labelledby="sf-all" tabIndex={-1}>
      <div className={styles.productsHead}>
        <h2 id="sf-all" className={styles.h2}>
          {grid.query.category || labels.allProducts}
        </h2>
        <p className={styles.note}>{labels.inStockNote}</p>
      </div>
      <div className={styles.toolbar} role="group" aria-label={labels.allProducts}>
        <label className={styles.field}>
          <span className={styles.fieldLabel}>{labels.category}</span>
          <select value={grid.query.category} onChange={(e) => update({ category: e.target.value })} className={styles.select}>
            <option value="">{labels.allCategories}</option>
            {model.products.categories.concat(model.categories.filter((c) => !model.products.categories.some((x) => x.name === c.name))).map((c) => (
              <option key={c.name} value={c.name}>
                {c.name}
              </option>
            ))}
          </select>
        </label>
        <label className={styles.field}>
          <span className={styles.fieldLabel}>{labels.price}</span>
          <select value={grid.query.price} onChange={(e) => update({ price: e.target.value })} className={styles.select}>
            <option value="">{labels.priceAny}</option>
            {model.products.prices.map((p) => (
              <option key={p.value} value={p.value}>
                {p.label}
              </option>
            ))}
          </select>
        </label>
        <label className={styles.field}>
          <span className={styles.fieldLabel}>{labels.sort}</span>
          <select value={grid.query.sort} onChange={(e) => update({ sort: e.target.value as StorefrontSort })} className={styles.select}>
            {(Object.keys(labels.sortOptions) as StorefrontSort[]).map((k) => (
              <option key={k} value={k}>
                {labels.sortOptions[k]}
              </option>
            ))}
          </select>
        </label>
        {filtered ? (
          <button type="button" className={styles.clear} onClick={() => update({ q: '', category: '', price: '' })}>
            {labels.clear}
          </button>
        ) : null}
      </div>
      <p className={styles.count} aria-live="polite" role="status">
        {grid.status === 'loading' ? labels.loading : labels.results(grid.items.length, grid.meta.total)}
      </p>
      {grid.status === 'loading' ? (
        <ul className={styles.grid} aria-hidden>
          {Array.from({ length: cfg.grid.columns * 2 }, (_, i) => (
            <li key={i} className={styles.skelTile}>
              <span className={styles.skelImg} />
              <span className={styles.skelLine} />
              <span className={cx(styles.skelLine, styles.skelShort)} />
            </li>
          ))}
        </ul>
      ) : grid.status === 'error' && grid.items.length === 0 ? (
        <p className={styles.empty} role="alert">
          {labels.error}{' '}
          <button type="button" className={styles.textLink} onClick={grid.retry}>
            {labels.retry}
          </button>
        </p>
      ) : grid.items.length === 0 ? (
        <p className={styles.empty}>{filtered ? labels.noResults : labels.noProducts}</p>
      ) : (
        <ul className={styles.grid} data-columns={cfg.grid.columns}>
          {grid.items.map((p, i) => (
            <li key={p.atin}>{tile(p, i, true)}</li>
          ))}
        </ul>
      )}
      {grid.meta.page < grid.meta.pageCount && grid.status !== 'loading' ? (
        <div className={styles.more}>
          <button type="button" className={styles.secondary} onClick={grid.more} disabled={grid.status === 'more'}>
            {grid.status === 'more' ? labels.loading : labels.loadMore}
          </button>
        </div>
      ) : null}
    </section>
  );

  // The seller orders the optional sections; All products sits after the leading featured and categories rows.
  const ordered = cfg.sections.filter((s): s is { id: Exclude<StoreSectionId, 'announcement'>; enabled: boolean } => s.id !== 'announcement' && s.enabled);
  const body: ReactNode[] = [];
  let placed = false;
  for (const s of ordered) {
    if (!placed && s.id !== 'featured' && s.id !== 'categories') {
      body.push(productsBlock);
      placed = true;
    }
    body.push(sectionNodes[s.id]);
  }
  if (!placed) body.push(productsBlock);

  const social = cfg.social.filter((s) => s.url);
  const hasContact = (cfg.contact.showEmail && model.contactEmail) || cfg.contact.phone || cfg.contact.hours;
  return (
    <div className={cx(styles.root, preview && styles.previewRoot)} style={vars} data-testid="storefront-root" data-preset={theme.preset} data-store-slug={model.slug}>
      <a href="#sf-products" className={styles.skip}>
        {labels.skip}
      </a>
      {showAnnouncement ? (
        <div className={styles.announce} role="region" aria-label={labels.announcement}>
          {cfg.announcement.href ? (
            <a href={cfg.announcement.href} className={styles.announceLink}>
              {cfg.announcement.text}
            </a>
          ) : (
            <span>{cfg.announcement.text}</span>
          )}
        </div>
      ) : null}
      <header className={styles.header} data-testid="storefront-header">
        <div className={styles.headerRow}>
          <a href="#main" className={styles.brand} aria-label={model.name} onClick={(e) => { e.preventDefault(); update({ q: '', category: '', price: '' }); window.scrollTo?.({ top: 0 }); }}>
            {model.logoUrl ? <img src={model.logoUrl} alt="" className={styles.brandLogo} width={40} height={40} /> : null}
            <span className={styles.brandName}>{model.name}</span>
          </a>
          <form
            role="search"
            className={styles.search}
            onSubmit={(e) => {
              e.preventDefault();
              productsRef.current?.scrollIntoView?.({ block: 'start' });
            }}
          >
            <label htmlFor={searchId} className="at-visually-hidden">
              {labels.searchLabel}
            </label>
            <input id={searchId} type="search" value={grid.query.q} onChange={(e) => update({ q: e.target.value })} placeholder={labels.searchPlaceholder(model.name)} className={styles.searchInput} autoComplete="off" enterKeyHint="search" />
            <button type="submit" className={styles.searchBtn} aria-label={labels.searchSubmit}>
              <MagnifyingGlass size={20} aria-hidden />
            </button>
          </form>
          <nav className={styles.actions} aria-label={model.name}>
            <Anchor as={Link} href={commerce.accountHref} className={styles.iconLink} label={labels.account}>
              <User size={22} aria-hidden />
              <span className={styles.iconText}>{labels.account}</span>
            </Anchor>
            <Anchor as={Link} href={commerce.cartHref} className={styles.iconLink} label={labels.cart(commerce.cartCount)}>
              <span className={styles.cartIcon}>
                <ShoppingCart size={22} aria-hidden />
                {commerce.cartCount > 0 ? <span className={styles.cartCount} aria-hidden>{commerce.cartCount}</span> : null}
              </span>
            </Anchor>
          </nav>
        </div>
        {tabs.length > 1 ? (
          <nav className={styles.tabs} aria-label={labels.storeNav}>
            <button type="button" className={styles.tab} aria-current={grid.query.category === '' ? 'true' : undefined} onClick={() => update({ category: '' })}>
              {labels.allProducts}
            </button>
            {tabs.map((c) => (
              <button key={c.name} type="button" className={styles.tab} aria-current={grid.query.category === c.name ? 'true' : undefined} onClick={() => pickCategory(c.name)}>
                {c.name}
              </button>
            ))}
          </nav>
        ) : null}
      </header>
      <main className={styles.main} id="main" tabIndex={-1}>
        <Hero model={model} labels={labels} locale={locale} />
        <div className={styles.page}>{body}</div>
      </main>
      <footer className={styles.footer} data-testid="storefront-footer">
        <div className={styles.footerInner}>
          <div className={styles.footerCol}>
            <strong className={styles.footerName}>{model.name}</strong>
            {cfg.policies.shipping.trim() || cfg.policies.returns.trim() ? (
              <p className={styles.footerLine}>
                <a href="#sf-policies" className={styles.footerLink}>
                  {labels.policies}
                </a>
              </p>
            ) : null}
          </div>
          {hasContact ? (
            <div className={styles.footerCol}>
              <strong className={styles.footerHead}>{labels.contact}</strong>
              {cfg.contact.showEmail && model.contactEmail ? (
                <a className={styles.footerLink} href={`mailto:${model.contactEmail}`}>
                  {model.contactEmail}
                </a>
              ) : null}
              {cfg.contact.phone ? (
                <a className={styles.footerLink} href={`tel:${cfg.contact.phone.replace(/[^+0-9]/g, '')}`}>
                  {cfg.contact.phone}
                </a>
              ) : null}
              {cfg.contact.hours ? (
                <span className={styles.footerLine}>
                  {labels.hours}: {cfg.contact.hours}
                </span>
              ) : null}
            </div>
          ) : null}
          {social.length > 0 ? (
            <div className={styles.footerCol}>
              <strong className={styles.footerHead}>{labels.follow}</strong>
              <ul className={styles.social}>
                {social.map((s) => (
                  <li key={s.network + s.url}>
                    <a className={styles.footerLink} href={s.url} target="_blank" rel="noopener noreferrer nofollow">
                      {labels.networks[s.network] ?? s.network}
                      <span className="at-visually-hidden"> {labels.opensNewTab}</span>
                    </a>
                  </li>
                ))}
              </ul>
            </div>
          ) : null}
        </div>
        <p className={styles.sold}>
          <Anchor as={Link} href={commerce.homeHref} className={styles.footerLink}>
            {labels.soldOn}
          </Anchor>
        </p>
      </footer>
      {quick ? <QuickView product={quick} labels={labels} commerce={commerce} locale={locale} inert={preview} onClose={() => setQuick(null)} /> : null}
      {children}
    </div>
  );
}
