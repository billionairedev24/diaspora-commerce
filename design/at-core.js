/* AfroTransact prototype core: real TanStack Table (table-core) + TanStack Query (query-core),
   a latency-simulating in-memory API, and CSV export. Production uses @tanstack/react-table and
   @tanstack/react-query with the same options shown here. */
(function () {
  const AT = (window.AT = window.AT || {});
  if (AT.__loaded) return;
  AT.__loaded = true;

  const wait = (ms) => new Promise((r) => setTimeout(r, ms));
  const db = {};
  AT.seed = (resource, rows) => { if (!db[resource]) db[resource] = rows.map((r) => ({ ...r })); };
  AT.api = {
    async list(resource) { await wait(450 + Math.random() * 350); return (db[resource] || []).map((r) => ({ ...r })); },
    async update(resource, id, patch, opts) {
      await wait(500 + Math.random() * 300);
      if (opts && opts.fail) throw new Error('409 Conflict — record changed by another reviewer');
      const row = (db[resource] || []).find((r) => r.id === id);
      if (!row) throw new Error('404');
      Object.assign(row, patch, { version: (row.version || 1) + 1 });
      return { ...row };
    },
    async create(resource, row) { await wait(450); db[resource] = db[resource] || []; db[resource].unshift({ ...row }); return { ...row }; },
    async bulk(resource, ids, patch) {
      await wait(600);
      (db[resource] || []).forEach((r) => { if (ids.includes(r.id)) Object.assign(r, patch); });
      return ids.length;
    }
  };

  AT.ready = Promise.all([
    import('https://esm.sh/@tanstack/table-core@8.21.3'),
    import('https://esm.sh/@tanstack/query-core@5')
  ]).then(([tc, tq]) => {
    AT.tc = tc; AT.tq = tq;
    AT.qc = new tq.QueryClient({ defaultOptions: { queries: { staleTime: 30000, gcTime: 300000, retry: 2, refetchOnWindowFocus: true } } });
    AT.qc.mount();
  }).catch((e) => { AT.loadError = String(e); })
    .finally(() => { AT.isReady = true; window.dispatchEvent(new Event('at:ready')); });

  AT.onReady = (fn) => { if (AT.isReady) fn(); else window.addEventListener('at:ready', fn, { once: true }); };

  /* useQuery equivalent: subscribe a QueryObserver; returns unsubscribe. */
  AT.observe = (queryKey, queryFn, onResult, extra) => {
    if (!AT.qc) { queryFn().then((data) => onResult({ status: 'success', data, isFetching: false, dataUpdatedAt: Date.now(), refetch: () => {} })); return () => {}; }
    const obs = new AT.tq.QueryObserver(AT.qc, { queryKey, queryFn, ...(extra || {}) });
    onResult(obs.getCurrentResult());
    return obs.subscribe(onResult);
  };

  /* useMutation equivalent with optimistic update + rollback + invalidation. */
  AT.mutate = ({ mutationFn, queryKey, optimistic, variables }) => {
    if (!AT.qc) return mutationFn(variables);
    const mo = new AT.tq.MutationObserver(AT.qc, {
      mutationFn,
      onMutate: async (vars) => {
        await AT.qc.cancelQueries({ queryKey });
        const previous = AT.qc.getQueryData(queryKey);
        if (optimistic) AT.qc.setQueryData(queryKey, (old) => optimistic(old || [], vars));
        return { previous };
      },
      onError: (_e, _v, ctx) => { if (ctx) AT.qc.setQueryData(queryKey, ctx.previous); },
      onSettled: () => AT.qc.invalidateQueries({ queryKey })
    });
    return mo.mutate(variables);
  };

  const cmp = (a, b) => (a == null ? -1 : b == null ? 1 : typeof a === 'number' ? a - b : String(a).localeCompare(String(b)));

  /* Builds a TanStack table and flattens it for templates.
     cfg: { data, columns:[{id, header, accessor, sortable, format(v,row)->{text,cls}}], state, set(patch) } */
  AT.table = (cfg) => {
    const st = cfg.state;
    const fmt = (col, v, row) => (col.format ? col.format(v, row) : { text: v == null ? '—' : String(v), cls: '' });
    const icon = (s) => (s === 'asc' ? 'ph ph-sort-ascending' : s === 'desc' ? 'ph ph-sort-descending' : 'ph ph-arrows-down-up');
    const colById = Object.fromEntries(cfg.columns.map((c) => [c.id, c]));
    const visibleCols = cfg.columns.filter((c) => (st.columnVisibility || {})[c.id] !== false);
    const columnsMenu = cfg.columns.filter((c) => c.hideable !== false).map((c) => ({ id: c.id, label: c.header, visible: (st.columnVisibility || {})[c.id] !== false,
      toggle: () => cfg.set({ columnVisibility: { ...(st.columnVisibility || {}), [c.id]: (st.columnVisibility || {})[c.id] === false } }) }));

    let model;
    if (AT.tc) {
      const tc = AT.tc;
      const upd = (key) => (u) => cfg.set({ [key]: typeof u === 'function' ? u(st[key]) : u });
      const t = tc.createTable({
        data: cfg.data,
        columns: cfg.columns.map((c) => ({ id: c.id, header: c.header, accessorFn: c.accessor, enableSorting: c.sortable !== false, filterFn: c.filterFn || 'includesString', enableHiding: c.hideable !== false })),
        getRowId: (r) => r.id,
        state: {}, onStateChange: () => {}, renderFallbackValue: null,
        enableRowSelection: true, globalFilterFn: 'includesString', autoResetPageIndex: false,
        getCoreRowModel: tc.getCoreRowModel(), getSortedRowModel: tc.getSortedRowModel(),
        getFilteredRowModel: tc.getFilteredRowModel(), getPaginationRowModel: tc.getPaginationRowModel(),
        getFacetedRowModel: tc.getFacetedRowModel(), getFacetedUniqueValues: tc.getFacetedUniqueValues(),
        onSortingChange: upd('sorting'), onGlobalFilterChange: upd('globalFilter'), onPaginationChange: upd('pagination'),
        onRowSelectionChange: upd('rowSelection'), onColumnVisibilityChange: upd('columnVisibility'), onColumnFiltersChange: upd('columnFilters')
      });
      t.setOptions((p) => ({ ...p, state: { ...t.initialState, ...st } }));
      const pre = t.getPrePaginationRowModel().rows;
      model = {
        engine: 'TanStack Table v8',
        headers: t.getHeaderGroups()[0].headers.map((h) => ({ id: h.column.id, label: h.column.columnDef.header, canSort: h.column.getCanSort(), icon: icon(h.column.getIsSorted()), toggle: h.column.getToggleSortingHandler() || (() => {}), active: !!h.column.getIsSorted() })),
        rows: t.getRowModel().rows.map((r) => ({ id: r.id, original: r.original, selected: r.getIsSelected(), toggle: r.getToggleSelectedHandler(),
          cells: r.getVisibleCells().map((c) => ({ id: c.column.id, ...fmt(colById[c.column.id], c.getValue(), r.original) })) })),
        filtered: pre.length, total: cfg.data.length, pageIndex: t.getState().pagination.pageIndex, pageCount: Math.max(1, t.getPageCount()),
        canPrev: t.getCanPreviousPage(), canNext: t.getCanNextPage(), prev: () => t.previousPage(), next: () => t.nextPage(),
        allPageSelected: t.getIsAllPageRowsSelected(), toggleAllPage: t.getToggleAllPageRowsSelectedHandler(),
        selectedIds: t.getSelectedRowModel().rows.map((r) => r.id),
        facet: (id) => [...t.getColumn(id).getFacetedUniqueValues().entries()].map(([value, count]) => ({ value, count })),
        exportRows: () => (t.getSelectedRowModel().rows.length ? t.getSelectedRowModel().rows : pre).map((r) => r.original)
      };
    } else {
      // Fallback when the CDN is unreachable — same semantics, no library.
      const q = String(st.globalFilter || '').toLowerCase();
      let rows = cfg.data.filter((r) => !q || cfg.columns.some((c) => String(c.accessor(r) ?? '').toLowerCase().includes(q)));
      (st.columnFilters || []).forEach((f) => { rows = rows.filter((r) => String(colById[f.id].accessor(r)).toLowerCase().includes(String(f.value).toLowerCase())); });
      const s = (st.sorting || [])[0];
      if (s) rows = [...rows].sort((a, b) => cmp(colById[s.id].accessor(a), colById[s.id].accessor(b)) * (s.desc ? -1 : 1));
      const { pageIndex, pageSize } = st.pagination;
      const pageCount = Math.max(1, Math.ceil(rows.length / pageSize));
      const sel = st.rowSelection || {};
      const page = rows.slice(pageIndex * pageSize, pageIndex * pageSize + pageSize);
      model = {
        engine: 'fallback (TanStack unavailable)',
        headers: cfg.columns.filter((c) => visibleCols.includes(c)).map((c) => { const on = s && s.id === c.id ? (s.desc ? 'desc' : 'asc') : false; return { id: c.id, label: c.header, canSort: c.sortable !== false, icon: icon(on), active: !!on,
          toggle: () => cfg.set({ sorting: !on ? [{ id: c.id, desc: false }] : on === 'asc' ? [{ id: c.id, desc: true }] : [] }) }; }),
        rows: page.map((r) => ({ id: r.id, original: r, selected: !!sel[r.id], toggle: () => cfg.set({ rowSelection: { ...sel, [r.id]: !sel[r.id] } }),
          cells: visibleCols.map((c) => ({ id: c.id, ...fmt(c, c.accessor(r), r) })) })),
        filtered: rows.length, total: cfg.data.length, pageIndex, pageCount, canPrev: pageIndex > 0, canNext: pageIndex < pageCount - 1,
        prev: () => cfg.set({ pagination: { pageIndex: pageIndex - 1, pageSize } }), next: () => cfg.set({ pagination: { pageIndex: pageIndex + 1, pageSize } }),
        allPageSelected: page.length > 0 && page.every((r) => sel[r.id]),
        toggleAllPage: () => { const on = !page.every((r) => sel[r.id]); const n = { ...sel }; page.forEach((r) => { n[r.id] = on; }); cfg.set({ rowSelection: n }); },
        selectedIds: Object.keys(sel).filter((k) => sel[k]),
        facet: (id) => { const m = new Map(); cfg.data.forEach((r) => { const v = colById[id].accessor(r); m.set(v, (m.get(v) || 0) + 1); }); return [...m.entries()].map(([value, count]) => ({ value, count })); },
        exportRows: () => { const ids = Object.keys(sel).filter((k) => sel[k]); return ids.length ? cfg.data.filter((r) => ids.includes(r.id)) : rows; }
      };
    }
    model.columnsMenu = columnsMenu;
    model.selectedCount = model.selectedIds.length;
    model.pageLabel = `Page ${model.pageIndex + 1} of ${model.pageCount}`;
    model.countLabel = model.filtered === model.total ? `${model.total} rows` : `${model.filtered} of ${model.total} rows`;
    model.exportCsv = (name) => {
      const cols = visibleCols.filter((c) => c.exportable !== false);
      const esc = (v) => { const s = v == null ? '' : String(v); return /[",\n]/.test(s) ? '"' + s.replace(/"/g, '""') + '"' : s; };
      const lines = [cols.map((c) => esc(c.header)).join(',')].concat(model.exportRows().map((r) => cols.map((c) => esc(c.accessor(r))).join(',')));
      const url = URL.createObjectURL(new Blob([lines.join('\n')], { type: 'text/csv' }));
      const a = document.createElement('a'); a.href = url; a.download = `${name}-${new Date().toISOString().slice(0, 10)}.csv`; a.click();
      setTimeout(() => URL.revokeObjectURL(url), 1000);
    };
    return model;
  };

  AT.ago = (ts) => { if (!ts) return 'never'; const s = Math.round((Date.now() - ts) / 1000); return s < 5 ? 'just now' : s < 60 ? `${s}s ago` : `${Math.round(s / 60)}m ago`; };
})();
