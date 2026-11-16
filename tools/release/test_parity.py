#!/usr/bin/env python3
"""Tests of tools/release/parity.py and chart_env_sync.py on a small synthetic repository (no network, no build):
python3 tools/release/test_parity.py

The fixture is a monolith in miniature: one Java module `alpha` mounted in core, one Go module `beta` mounted in afrotransact-go, their
charts, secrets renderer, bootstrap SQL, workflows and contracts. The baseline must be clean; every test then breaks ONE thing the
way a change on main would and expects the matching gap (and, where there is one, that the documented fix makes it clean again).
"""
import json
import pathlib
import subprocess
import sys
import tempfile
import textwrap
import unittest

HERE = pathlib.Path(__file__).resolve().parent
sys.path.insert(0, str(HERE))
import chart_env_sync  # noqa: E402
import parity  # noqa: E402

PARITY_YAML = """
version: 1
unmounted: {java: {}, go: {}}
workflows:
  core.yml: {line: release-1, reason: x}
  java-alpha.yml: {line: per-service-only, reason: x}
  go-beta.yml: {line: per-service-only, reason: x}
  promote.yml: {line: manual, reason: x}
config:
  env_skip: {DB_URL: x, DB_USER: x, DB_PASSWORD: x, "SPRING_DATASOURCE_*": x}
  secret_skip: {DB_URL: x, DB_USER: x, DB_PASSWORD: x}
kafka: {group_pattern: '^[a-z]+(-[a-z]+)*-service(-[a-z-]+)?$'}
observability: {extra_service_values: [alloy]}
"""

FILES = {
    "tools/release/parity.yaml": PARITY_YAML,
    "infra/argocd/launch-set/release-1.yaml": """
deployables:
  afrotransact-core: {runtime: java, chart: java/apps/core/deploy/helm, modules: [alpha]}
  afrotransact-go: {runtime: go, chart: go/apps/afrotransact-go/deploy/helm, modules: [beta]}
""",
    "java/apps/core/src/main/java/com/afrotransact/core/CoreApplication.java":
        '@ModuleSpec(name = "alpha", service = "alpha", basePackage = "com.afrotransact.alpha", x = 1)\nclass CoreApplication {}\n',
    "java/apps/core/pom.xml": "<dependency><artifactId>alpha-service</artifactId></dependency>\n",
    "java/apps/core/src/main/resources/application.yml": "at:\n  modules:\n    alpha:\n      enabled: ${MODULE_ALPHA_ENABLED:true}\n",
    "java/apps/core/src/test/resources/http-edges.txt": "# edges\nHttpListed=alpha -> beta (Go service, stays HTTP)\n",
    "java/apps/core/deploy/helm/values.yaml": "modules:\n  alpha: {enabled: false, schema: alpha, pool: 3}\nextraEnv:\n  JAVA_TOOL_OPTIONS: \"-Xss512k\"\nnodeSelector: {}\n",
    "java/apps/core/deploy/helm/values-prod.yaml": "env: prod\n",
    "java/services/alpha/deploy/helm/values.yaml": textwrap.dedent("""\
        # Secret keys expected:
        #   - DB_URL
        #   - ALPHA_CLIENT_SECRET
        extraEnv:
          BETA_URL: http://beta:8080
          SPRING_DATASOURCE_HIKARI_MAXIMUM_POOL_SIZE: "4"
        """),
    "java/services/alpha/deploy/helm/values-prod.yaml": "extraEnv:\n  ALPHA_ZONE: America/Chicago\n",
    "java/services/alpha/Dockerfile": "FROM scratch\n",
    "java/services/alpha/src/main/resources/application.yml": "spring:\n  application:\n    name: alpha\n",
    "java/services/alpha/src/main/resources/db/migration/V1__init.sql": "create extension if not exists postgis;\ncreate table items (id uuid primary key);\n-- grant all on everything to nobody\nrevoke update on items from public;\n",
    "java/services/alpha/src/main/java/com/afrotransact/alpha/adapter/in/messaging/Listener.java": textwrap.dedent("""\
        class Listener {
            @KafkaListener(id = "alpha-x", topics = "${at.alpha.topic:at.alpha.events}",
                    groupId = "${spring.kafka.consumer.group-id:alpha-service}")
            void on(String m) {}
        }
        """),
    "java/services/alpha/src/main/java/com/afrotransact/alpha/adapter/out/http/HttpListed.java": "@Component\nclass HttpListed {}\n",
    "go/apps/afrotransact-go/internal/registry/registry.go": 'import beta "github.com/x/y/go/services/beta"\n',
    "go/apps/afrotransact-go/deploy/helm/values.yaml": "modules:\n  beta: {enabled: false}\nextraEnv: {}\n",
    "go/services/beta/deploy/helm/values.yaml": "# Secret keys expected:\n#   - BETA_CLIENT_SECRET\nextraEnv: {}\n",
    "go/services/beta/Dockerfile": "FROM scratch\n",
    "infra/ovh-vps/secrets.sh": '  alpha) out+=("ALPHA_CLIENT_SECRET=$(gen X)") ;;\n  beta) out+=("BETA_CLIENT_SECRET=$(gen Y)") ;;\n',
    "infra/ovh-vps/services.conf": "alpha java alpha 1\nbeta go beta 2\n",
    "infra/ops/postgres/release-1-bootstrap.sql": "\\set extensions 'postgis,btree_gist'\n\\set core_modules 'alpha:3'\n\\set go_modules 'beta:3'\n",
    "contracts/asyncapi/events.yaml": "channels:\n  at.alpha.events: {}\n",
    "contracts/openapi/alpha.yaml": "openapi: 3.1.0\npaths:\n  /v1/a:\n    get: {operationId: a}\n",
    "infra/observability/rules/r.yaml": 'expr: up{service="alpha"} == 0 or up{service=~"beta|afrotransact-core"} == 0\n',
    ".github/workflows/core.yml": "name: core\non:\n  push:\n    branches: [release-1]\n  pull_request:\n    branches: [release-1]\njobs: {}\n",
    ".github/workflows/java-alpha.yml": "name: a\non:\n  push:\n    branches: [main, develop]\n  workflow_dispatch:\njobs: {}\n",
    ".github/workflows/go-beta.yml": "name: b\non:\n  push:\n    branches: [main, develop]\n  workflow_dispatch:\njobs: {}\n",
    ".github/workflows/promote.yml": "name: p\non:\n  workflow_dispatch:\njobs: {}\n",
}


def build(root, extra=None, remove=()):
    files = dict(FILES)
    files.update(extra or {})
    for r in remove:
        files.pop(r, None)
    for rel, text in files.items():
        p = pathlib.Path(root) / rel
        p.parent.mkdir(parents=True, exist_ok=True)
        p.write_text(text)
    # the chart env block is generated, as the contributor would
    for _dep, path, wanted, _current, _c, _k in chart_env_sync.plan(pathlib.Path(root)):
        path.write_text(wanted)


class ParityTest(unittest.TestCase):
    def setUp(self):
        self._tmp = tempfile.TemporaryDirectory()
        self.root = pathlib.Path(self._tmp.name)

    def tearDown(self):
        self._tmp.cleanup()

    def gaps(self, only=None, **kw):
        ctx = parity.Ctx(self.root, **kw)
        return parity.run(ctx, only), ctx

    def subjects(self, check, **kw):
        return [g.subject + " | " + g.problem for g in self.gaps([check], **kw)[0]]

    def test_baseline_is_clean(self):
        build(self.root)
        gaps, _ = self.gaps()
        self.assertEqual([g.row() for g in gaps], [])

    # --- mounted modules and delivery files
    def test_new_java_service_not_mounted(self):
        build(self.root, {"java/services/gamma/Dockerfile": "x", "java/services/gamma/deploy/helm/values.yaml": "extraEnv: {}\n"})
        self.assertTrue(any("gamma" in s and "not mounted" in s for s in self.subjects("mounted-java")))
        d = self.subjects("delivery")
        self.assertTrue(any("gamma" in s and "no deployable equivalent" in s for s in d), d)
        self.assertTrue(any("services.conf: gamma" in s for s in d), d)

    def test_unmounted_with_a_reason_is_allowed(self):
        y = PARITY_YAML.replace("unmounted: {java: {}, go: {}}", "unmounted: {java: {gamma: 'sandbox tool, never in production'}, go: {}}")
        build(self.root, {"tools/release/parity.yaml": y, "java/services/gamma/Dockerfile": "x"})
        self.assertEqual(self.subjects("mounted-java"), [])
        self.assertEqual([s for s in self.subjects("delivery") if "gamma" in s], [])

    def test_unmounted_without_a_reason_is_not(self):
        y = PARITY_YAML.replace("unmounted: {java: {}, go: {}}", "unmounted: {java: {gamma: ''}, go: {}}")
        build(self.root, {"tools/release/parity.yaml": y, "java/services/gamma/Dockerfile": "x"})
        self.assertTrue(self.subjects("mounted-java"))

    def test_new_go_service_not_mounted(self):
        build(self.root, {"go/services/delta/Dockerfile": "x"})
        self.assertTrue(any("delta" in s for s in self.subjects("mounted-go")))

    def test_mounted_module_missing_from_core_pom(self):
        build(self.root, {"java/apps/core/pom.xml": "<project/>"})
        self.assertTrue(any("pom" in s for s in self.subjects("mounted-java")))

    def test_unclassified_and_misclassified_workflow(self):
        build(self.root, {".github/workflows/newthing.yml": "name: n\non:\n  push:\n    branches: [main]\njobs: {}\n",
                          ".github/workflows/core.yml": "name: core\non:\n  push:\n    branches: [main]\njobs: {}\n"})
        d = self.subjects("delivery")
        self.assertTrue(any("newthing.yml" in s and "not classified" in s for s in d), d)
        self.assertTrue(any("core.yml" in s and "classified 'release-1' but" in s for s in d), d)

    def test_pull_request_without_branches_counts_as_release_1(self):
        self.assertEqual(parity.trigger_line({"pull_request": {"paths": ["a"]}, "push": {"branches": ["main"]}}), "release-1")
        self.assertEqual(parity.trigger_line({"push": {"branches": ["main", "develop"]}, "workflow_dispatch": None}), "per-service-only")
        self.assertEqual(parity.trigger_line({"schedule": [{"cron": "0 0 * * *"}], "workflow_dispatch": None}), "manual")
        self.assertEqual(parity.trigger_line({"workflow_call": {}}), "reusable")

    # --- migrations
    def migration(self, sql):
        build(self.root, {"java/services/alpha/src/main/resources/db/migration/V2__x.sql": sql})
        return self.subjects("migrations")

    def test_migration_extension_not_precreated(self):
        self.assertTrue(any("pgcrypto" in s for s in self.migration("create extension if not exists pgcrypto;")))

    def test_migration_known_extension_is_fine(self):
        self.assertEqual(self.migration("create extension if not exists btree_gist;"), [])

    def test_migration_cross_schema_reference(self):
        self.assertTrue(any("'beta'" in s for s in self.migration("alter table items add foreign key (id) references beta.thing(id);")))

    def test_migration_privileged_statements(self):
        for sql in ("create schema other;", "create role x login;", "grant select on items to someone;", "set role postgres;", "alter system set x=1;"):
            self.assertTrue(self.migration(sql), sql)

    def test_migration_words_in_comments_and_strings_are_not_statements(self):
        self.assertEqual(self.migration("-- create role x; grant all\ninsert into items values ('create schema beta.x');\n"), [])

    # --- kafka
    def listener(self, annotation):
        build(self.root, {"java/services/alpha/src/main/java/com/afrotransact/alpha/adapter/in/messaging/Listener.java":
                          "class Listener {\n    " + annotation + "\n    void on(String m) {}\n}\n"})
        return self.subjects("kafka")

    def test_kafka_listener_without_group(self):
        self.assertTrue(any("without an explicit groupId" in s for s in self.listener('@KafkaListener(topics = "at.alpha.events")')))

    def test_kafka_group_must_be_module_service(self):
        self.assertTrue(any("alpha-service" in s for s in self.listener('@KafkaListener(topics = "at.alpha.events", groupId = "other-service")')))
        self.assertTrue(any("not <module>-service" in s for s in self.listener('@KafkaListener(topics = "at.alpha.events", groupId = "mygroup")')))

    def test_kafka_topic_must_be_in_asyncapi(self):
        s = self.listener('@KafkaListener(topics = "${at.alpha.t:at.alpha.new}", groupId = "${spring.kafka.consumer.group-id:alpha-service}")')
        self.assertTrue(any("at.alpha.new" in x for x in s), s)

    def test_kafka_spel_topic_default_is_read(self):
        s = self.listener('@KafkaListener(topics = "#{\'${at.alpha.t:at.alpha.events,at.alpha.more}\'.split(\',\')}", groupId = "alpha-service-audit")')
        self.assertEqual([x for x in s if "at.alpha.more" not in x], [], s)
        self.assertTrue(any("at.alpha.more" in x for x in s), s)

    def test_kafka_standalone_group_id_override(self):
        build(self.root, {"java/services/alpha/src/main/resources/application.yml": "spring:\n  kafka:\n    consumer:\n      group-id: custom\n"})
        self.assertTrue(any("custom" in s for s in self.subjects("kafka")))

    # --- config and secrets
    def test_new_service_env_reaches_the_chart_only_after_the_sync(self):
        build(self.root)
        svc = self.root / "java/services/alpha/deploy/helm/values.yaml"
        svc.write_text(svc.read_text() + "  NEW_TIMEOUT: 5s\n")
        s = self.subjects("config")
        self.assertTrue(any("NEW_TIMEOUT" in x for x in s), s)
        for _dep, path, wanted, _cur, _c, _k in chart_env_sync.plan(self.root):
            path.write_text(wanted)
        self.assertEqual(self.subjects("config"), [])
        self.assertIn('NEW_TIMEOUT: "5s"', (self.root / "java/apps/core/deploy/helm/values.yaml").read_text())

    def test_env_layering_only_writes_what_differs_in_the_env_file(self):
        build(self.root)
        prod = (self.root / "java/apps/core/deploy/helm/values-prod.yaml").read_text()
        self.assertIn('ALPHA_ZONE: "America/Chicago"', prod)
        self.assertNotIn("BETA_URL", prod)
        self.assertIn('BETA_URL: "http://beta:8080"', (self.root / "java/apps/core/deploy/helm/values.yaml").read_text())
        self.assertNotIn("SPRING_DATASOURCE", (self.root / "java/apps/core/deploy/helm/values.yaml").read_text())

    def test_sync_is_idempotent_and_keeps_hand_written_values(self):
        build(self.root)
        core = self.root / "java/apps/core/deploy/helm/values.yaml"
        before = core.read_text()
        for _dep, path, wanted, _cur, _c, _k in chart_env_sync.plan(self.root):
            self.assertEqual(wanted, _cur)
        core.write_text(before.replace("nodeSelector: {}", "nodeSelector: {}\n").replace("  JAVA_TOOL_OPTIONS", "  BETA_URL: \"http://override\"\n  JAVA_TOOL_OPTIONS"))
        for _dep, path, wanted, _cur, _c, kept in chart_env_sync.plan(self.root):
            path.write_text(wanted)
        text = core.read_text()
        self.assertEqual(text.count("BETA_URL:"), 1, text)
        self.assertIn("http://override", text)

    def test_conflicting_values_between_modules(self):
        build(self.root, {"java/services/alpha/deploy/helm/values.yaml": FILES["java/services/alpha/deploy/helm/values.yaml"] + "  SHARED: a\n"})
        (self.root / "java/services/zeta/deploy/helm").mkdir(parents=True)
        (self.root / "java/services/zeta/deploy/helm/values.yaml").write_text("extraEnv:\n  SHARED: b\n")
        plan = self.root / "infra/argocd/launch-set/release-1.yaml"
        plan.write_text(plan.read_text().replace("modules: [alpha]", "modules: [alpha, zeta]"))
        self.assertTrue(any("different values" in s for s in self.subjects("config")))

    def test_module_without_chart_entry_or_switch(self):
        build(self.root, {"java/apps/core/deploy/helm/values.yaml": "modules: {}\nextraEnv: {}\n", "java/apps/core/src/main/resources/application.yml": "x: 1\n"})
        s = self.subjects("config")
        self.assertTrue(any("no entry under modules" in x for x in s), s)
        self.assertTrue(any("MODULE_ALPHA_ENABLED" in x for x in s), s)

    def test_documented_secret_not_rendered(self):
        build(self.root, {"java/services/alpha/deploy/helm/values.yaml": FILES["java/services/alpha/deploy/helm/values.yaml"] + "#   - NEW_API_KEY\n"})
        self.assertTrue(any("NEW_API_KEY" in s for s in self.subjects("secrets")))

    # --- observability and http edges
    def test_unknown_service_label_in_a_rule_dashboard_or_scrape(self):
        build(self.root, {"infra/observability/grafana/d.json": '{"expr": "rate(x{service=\\"payments-v2\\"}[5m])"}',
                          "infra/k8s/platform/lean/alloy/config.alloy": 'rule { regex = "x" }\n// service="alloy"\n'})
        s = self.subjects("observability")
        self.assertTrue(any("payments-v2" in x for x in s), s)
        self.assertFalse(any('"alloy"' in x for x in s), s)

    def test_new_http_adapter_not_in_allow_list(self):
        base = "java/services/alpha/src/main/java/com/afrotransact/alpha/adapter/out/http/"
        build(self.root, {base + "HttpNewEdge.java": "@Component\nclass HttpNewEdge {}\n",
                          base + "HttpSwitched.java": "@Component\n@ConditionalOnModuleMode(caller = \"a\", callee = \"b\")\nclass HttpSwitched {}\n"})
        s = self.subjects("http-edges")
        self.assertEqual(len(s), 1, s)
        self.assertIn("HttpNewEdge", s[0])

    # --- routes (needs git: the diff since the last sync)
    def git(self, *args):
        subprocess.run(["git", "-C", str(self.root), "-c", "user.email=t@t", "-c", "user.name=t", *args], check=True, capture_output=True)

    def test_changed_route_is_checked_against_the_core_route_report(self):
        build(self.root)
        self.git("init", "-q", "-b", "rel")
        self.git("add", "-A")
        self.git("commit", "-q", "-m", "base")
        base = subprocess.run(["git", "-C", str(self.root), "rev-parse", "HEAD"], capture_output=True, text=True).stdout.strip()
        (self.root / "contracts/openapi/alpha.yaml").write_text(
            "openapi: 3.1.0\npaths:\n  /v1/a:\n    get: {operationId: a}\n  /v1/b/{id}:\n    post: {operationId: b}\n")
        self.git("add", "-A")
        self.git("commit", "-q", "-m", "main adds a route")
        report = self.root / "report.json"
        report.write_text(json.dumps({"missing": [{"module": "alpha", "method": "POST", "path": "/v1/b/{}"}]}))
        gaps, ctx = self.gaps(["routes"], main="HEAD", base=base, route_report=str(report))
        self.assertEqual([g.subject for g in gaps], ["POST /v1/b/{} (alpha)"])
        self.assertTrue(gaps[0].source)
        report.write_text(json.dumps({"missing": []}))
        self.assertEqual(self.gaps(["routes"], main="HEAD", base=base, route_report=str(report))[0], [])
        gaps, ctx = self.gaps(["routes"], main="HEAD", base=base)
        self.assertEqual(gaps, [])
        self.assertTrue(any("UNVERIFIED 1" in n for n in ctx.notes), ctx.notes)
        gaps, _ = self.gaps(["routes"], main="HEAD", base=base, require_route_report=True)
        self.assertEqual(len(gaps), 1)

    def test_changed_operation_of_an_existing_route_counts(self):
        build(self.root)
        self.git("init", "-q", "-b", "rel")
        self.git("add", "-A")
        self.git("commit", "-q", "-m", "base")
        base = subprocess.run(["git", "-C", str(self.root), "rev-parse", "HEAD"], capture_output=True, text=True).stdout.strip()
        (self.root / "contracts/openapi/alpha.yaml").write_text("openapi: 3.1.0\npaths:\n  /v1/a:\n    get: {operationId: a, parameters: [{name: q, in: query}]}\n")
        self.git("add", "-A")
        self.git("commit", "-q", "-m", "main changes a route")
        report = self.root / "report.json"
        report.write_text(json.dumps({"missing": [{"module": "alpha", "method": "GET", "path": "/v1/a"}]}))
        gaps, _ = self.gaps(["routes"], main="HEAD", base=base, route_report=str(report))
        self.assertEqual([g.subject for g in gaps], ["GET /v1/a (alpha)"])

    def test_cli_exit_codes_and_markdown(self):
        build(self.root)
        r = subprocess.run([sys.executable, str(HERE / "parity.py"), "--root", str(self.root)], capture_output=True, text=True)
        self.assertEqual(r.returncode, 0, r.stdout)
        (self.root / "go/services/delta").mkdir(parents=True)
        (self.root / "go/services/delta/Dockerfile").write_text("x")
        r = subprocess.run([sys.executable, str(HERE / "parity.py"), "--root", str(self.root), "--format", "markdown"], capture_output=True, text=True)
        self.assertEqual(r.returncode, 1)
        self.assertIn("| mounted-go | `go/services/delta` |", r.stdout)
        self.assertIn("FAILED", r.stdout)


if __name__ == "__main__":
    unittest.main()
