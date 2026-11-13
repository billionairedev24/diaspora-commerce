// Package tenanttest is the cross-tenant test kit of the Go services.
//
// Tenant isolation in production rests on four things only (OpenFGA is not deployed): RBAC permission checks, the
// signed JWT org claim, per-resource owner checks and org-filtered queries. This kit proves the last three from the
// outside, with real HTTP requests against a service's own mux:
//
//   - World mints two organisations, two customers, a staff member and a service principal as auth.Actor values behind
//     a fake auth.Verifier, so a service's real auth.Middleware runs in the test.
//   - Run and CrossTenant fire table-driven requests as a principal and assert on the status and the body.
//   - CheckRoutes fails when a route the service registers is missing from the service's embedded
//     tenant_isolation_matrix.txt (METHOD pattern | CLASS | enforcement | test), so a new route cannot ship without
//     an isolation decision and a test that names it.
package tenanttest
