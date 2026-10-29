package httpapi

import (
	"net/http"
	"strconv"
)

func (h *handlersImpl) registerStaffTaxonomy(route func(pattern string, fn http.HandlerFunc)) {
	route("GET /v1/staff/taxonomy/tree", h.staffTaxonomyTree)
	route("POST /v1/staff/taxonomy/nodes", h.staffCreateTaxonomyNode)
	route("PATCH /v1/staff/taxonomy/nodes/{id}", h.staffRenameTaxonomyNode)
	route("POST /v1/staff/taxonomy/nodes/{id}/move", h.staffMoveTaxonomyNode)
	route("POST /v1/staff/taxonomy/nodes/{id}/retire", h.staffRetireTaxonomyNode)
	route("POST /v1/staff/taxonomy/nodes/{id}/restore", h.staffRestoreTaxonomyNode)
	route("POST /v1/staff/taxonomy/reorder", h.staffReorderTaxonomy)
	route("GET /v1/staff/taxonomy/product-types", h.staffTaxonomyProductTypes)
	route("POST /v1/staff/taxonomy/product-types", h.staffCreateTaxonomyProductType)
	route("GET /v1/staff/taxonomy/product-types/{code}", h.staffTaxonomyProductType)
	route("PATCH /v1/staff/taxonomy/product-types/{code}", h.staffUpdateTaxonomyProductType)
	route("GET /v1/staff/taxonomy/audit", h.staffTaxonomyAudit)
	route("POST /v1/staff/taxonomy/requests/{id}/approval-preview", h.staffTaxonomyApprovalPreview)
}

func (h *handlersImpl) staffTaxonomyTree(w http.ResponseWriter, r *http.Request) {
	v, err := h.s.Staff.Taxonomy.Tree(r.Context(), callerFrom(r.Context()))
	h.reply(w, r, http.StatusOK, v, err)
}

func (h *handlersImpl) staffCreateTaxonomyNode(w http.ResponseWriter, r *http.Request) {
	b, err := staffBody(r)
	if err != nil {
		h.reply(w, r, 0, nil, err)
		return
	}
	v, err := h.s.Staff.Taxonomy.CreateNode(r.Context(), callerFrom(r.Context()), b)
	h.reply(w, r, http.StatusCreated, v, err)
}

func (h *handlersImpl) staffRenameTaxonomyNode(w http.ResponseWriter, r *http.Request) {
	b, err := staffBody(r)
	if err != nil {
		h.reply(w, r, 0, nil, err)
		return
	}
	v, err := h.s.Staff.Taxonomy.RenameNode(r.Context(), callerFrom(r.Context()), r.PathValue("id"), r.Header.Get("If-Match"), b)
	h.reply(w, r, http.StatusOK, v, err)
}

func (h *handlersImpl) staffMoveTaxonomyNode(w http.ResponseWriter, r *http.Request) {
	b, err := staffBody(r)
	if err != nil {
		h.reply(w, r, 0, nil, err)
		return
	}
	v, err := h.s.Staff.Taxonomy.MoveNode(r.Context(), callerFrom(r.Context()), r.PathValue("id"), r.Header.Get("If-Match"), b)
	h.reply(w, r, http.StatusOK, v, err)
}

func (h *handlersImpl) staffRetireTaxonomyNode(w http.ResponseWriter, r *http.Request) {
	b, err := staffBody(r)
	if err != nil {
		h.reply(w, r, 0, nil, err)
		return
	}
	v, err := h.s.Staff.Taxonomy.RetireNode(r.Context(), callerFrom(r.Context()), r.PathValue("id"), r.Header.Get("If-Match"), b)
	h.reply(w, r, http.StatusOK, v, err)
}

func (h *handlersImpl) staffRestoreTaxonomyNode(w http.ResponseWriter, r *http.Request) {
	b, err := staffBody(r)
	if err != nil {
		h.reply(w, r, 0, nil, err)
		return
	}
	v, err := h.s.Staff.Taxonomy.RestoreNode(r.Context(), callerFrom(r.Context()), r.PathValue("id"), r.Header.Get("If-Match"), b)
	h.reply(w, r, http.StatusOK, v, err)
}

func (h *handlersImpl) staffReorderTaxonomy(w http.ResponseWriter, r *http.Request) {
	b, err := staffBody(r)
	if err != nil {
		h.reply(w, r, 0, nil, err)
		return
	}
	v, err := h.s.Staff.Taxonomy.Reorder(r.Context(), callerFrom(r.Context()), b)
	h.reply(w, r, http.StatusOK, v, err)
}

func (h *handlersImpl) staffTaxonomyProductTypes(w http.ResponseWriter, r *http.Request) {
	v, err := h.s.Staff.Taxonomy.ProductTypes(r.Context(), callerFrom(r.Context()))
	h.reply(w, r, http.StatusOK, v, err)
}

func (h *handlersImpl) staffCreateTaxonomyProductType(w http.ResponseWriter, r *http.Request) {
	b, err := staffBody(r)
	if err != nil {
		h.reply(w, r, 0, nil, err)
		return
	}
	v, err := h.s.Staff.Taxonomy.CreateProductType(r.Context(), callerFrom(r.Context()), b)
	h.reply(w, r, http.StatusCreated, v, err)
}

func (h *handlersImpl) staffTaxonomyProductType(w http.ResponseWriter, r *http.Request) {
	v, err := h.s.Staff.Taxonomy.ProductType(r.Context(), callerFrom(r.Context()), r.PathValue("code"))
	h.reply(w, r, http.StatusOK, v, err)
}

func (h *handlersImpl) staffUpdateTaxonomyProductType(w http.ResponseWriter, r *http.Request) {
	b, err := staffBody(r)
	if err != nil {
		h.reply(w, r, 0, nil, err)
		return
	}
	v, err := h.s.Staff.Taxonomy.UpdateProductType(r.Context(), callerFrom(r.Context()), r.PathValue("code"), r.Header.Get("If-Match"), b)
	h.reply(w, r, http.StatusOK, v, err)
}

func (h *handlersImpl) staffTaxonomyAudit(w http.ResponseWriter, r *http.Request) {
	limit, _ := strconv.Atoi(r.URL.Query().Get("limit"))
	v, err := h.s.Staff.Taxonomy.Audit(r.Context(), callerFrom(r.Context()), r.URL.Query().Get("subject"), limit)
	h.reply(w, r, http.StatusOK, v, err)
}

func (h *handlersImpl) staffTaxonomyApprovalPreview(w http.ResponseWriter, r *http.Request) {
	b, err := staffBody(r)
	if err != nil {
		h.reply(w, r, 0, nil, err)
		return
	}
	v, err := h.s.Staff.Taxonomy.PreviewApproval(r.Context(), callerFrom(r.Context()), r.PathValue("id"), b)
	h.reply(w, r, http.StatusOK, v, err)
}
