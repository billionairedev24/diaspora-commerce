#!/usr/bin/env python3
"""Kafka topic and ACL model of the repository, derived from the code and compared with every place that lists topics.

Source of truth, in order:
  topics        contracts/asyncapi/events.yaml (generated; every channel address)
  producers     contracts/asyncapi/fragments/<service>.yaml, operations with action: send
  consumers     the union of fragments (action: receive) and the code (Java @KafkaListener topics, Go config topics),
                because the contract prose lags the code in places and an ACL must cover what really runs
  group ids     java/services/*/src/main/resources/application.yml (group-id) and the @KafkaListener groupId defaults,
                go/services/*/config/*.yaml (group) and notifications' ConsumerGroup constant

    topics_model.py            check; exits 1 and prints every difference
    topics_model.py --write    regenerate infra/tofu/kafka-topics.json (the file the Terraform envs read)
    topics_model.py --groups   print the consumer group table (Markdown)

Checked against the derived model: infra/tofu/kafka-topics.json (topics, producers, consumers, group prefixes, DLQ
writers), the services of every infra/tofu/envs/*/main.tf, infra/local/kafka/topics.txt (exact set), and the DLQ list
of infra/k8s/platform/lean/kafka.yaml (exact set).
"""
import argparse
import glob
import json
import os
import re
import sys
from collections import defaultdict

ROOT = os.path.abspath(os.path.join(os.path.dirname(__file__), "..", ".."))
TOPIC_RE = re.compile(r"at\.[a-z0-9-]+\.events")
GO_DLQ_TOPICS = ("at.indexer.dlq.events", "at.authz-sync.dlq.events")
DLQ_RETENTION_MS = 1209600000


def read(rel):
    with open(os.path.join(ROOT, rel), encoding="utf-8") as f:
        return f.read()


def asyncapi_topics():
    return sorted(set(re.findall(r"^\s+address:\s+(at\.\S+)\s*$", read("contracts/asyncapi/events.yaml"), re.M)))


def fragments():
    """address by channel name, and producers/consumers by address, from the per-service fragments."""
    addr, send, recv = {}, defaultdict(set), defaultdict(set)
    files = sorted(glob.glob(os.path.join(ROOT, "contracts/asyncapi/fragments/*.yaml")))
    for f in files:
        for m in re.finditer(r"^  (\w+):\n    address: (\S+)", open(f, encoding="utf-8").read(), re.M):
            addr[m.group(1)] = m.group(2)
    op = re.compile(r"^  \w+:\n    action: (send|receive)\n    channel:\s*\{?\s*\$ref:\s*'#/channels/(\w+)'", re.M)
    for f in files:
        svc = os.path.basename(f)[:-5]
        text = open(f, encoding="utf-8").read()
        if "\noperations:\n" not in text:
            continue
        for m in op.finditer(text.split("\noperations:\n", 1)[1]):
            (send if m.group(1) == "send" else recv)[addr[m.group(2)]].add(svc)
    return send, recv


def java_listeners():
    """consumers by topic, and group ids by service, from the Java services."""
    consumers, groups = defaultdict(set), defaultdict(set)
    for svc_dir in sorted(glob.glob(os.path.join(ROOT, "java/services/*"))):
        svc = os.path.basename(svc_dir)
        yml = os.path.join(svc_dir, "src/main/resources/application.yml")
        app_name, root_group = svc + "-service", None
        if os.path.exists(yml):
            y = open(yml, encoding="utf-8").read()
            m = re.search(r"^\s+name:\s+(\S+)", y, re.M)
            if m and "spring:" in y:
                app_name = m.group(1)
            g = re.search(r"group-id:\s*(?:\$\{KAFKA_GROUP:([^}]+)\}|(\S+))", y)
            if g:
                root_group = g.group(1) or g.group(2)
        for f in glob.glob(os.path.join(svc_dir, "src/main/java/**/*.java"), recursive=True):
            s = open(f, encoding="utf-8").read()
            for m in re.finditer(r"@KafkaListener\((.*?)\)\s*\n\s*(?:public )?(?:void|boolean)", s, re.S):
                blk = m.group(1)
                t = re.search(r"topics\s*=\s*(.*?)(?:,\s*\w+\s*=|$)", blk, re.S)
                found = TOPIC_RE.findall(t.group(1)) if t else []
                if t and not found:
                    c = re.search(r"\b([A-Z][A-Z_]+)\b", t.group(1))
                    cm = re.search(re.escape(c.group(1)) + r' = "(at\.[a-z0-9-]+\.events)"', s) if c else None
                    found = [cm.group(1)] if cm else []
                if t and not found:
                    sys.exit(f"cannot resolve the topics of a @KafkaListener in {os.path.relpath(f, ROOT)}")
                for topic in found:
                    consumers[topic].add(svc)
                gid = re.search(r'groupId\s*=\s*"([^"]+)"', blk)
                if gid:
                    raw = gid.group(1)
                    raw = raw.replace("${spring.application.name}", app_name)
                    raw = re.sub(r"\$\{spring\.kafka\.consumer\.group-id:([^}]+)\}", r"\1", raw)
                    groups[svc].add(raw)
        if groups[svc] and root_group is None:
            root_group = app_name
        if root_group:
            groups[svc].add(root_group)
    return consumers, {k: v for k, v in groups.items() if v}


def go_consumers():
    consumers, groups = defaultdict(set), defaultdict(set)
    for svc_dir in sorted(glob.glob(os.path.join(ROOT, "go/services/*"))):
        svc = os.path.basename(svc_dir)
        cfgs = glob.glob(os.path.join(svc_dir, "config/*.yaml"))
        for cfg in cfgs:
            text = open(cfg, encoding="utf-8").read()
            if re.search(r"^\s+topics:", text, re.M):
                for t in TOPIC_RE.findall(text):
                    consumers[t].add(svc)
            g = re.search(r"^\s+group:\s*(\S+)", text, re.M)
            if g:
                groups[svc].add(g.group(1))
        rules = os.path.join(svc_dir, "internal/app/rules.go")
        if os.path.exists(rules):
            g = re.search(r'ConsumerGroup\s*=\s*"([^"]+)"', open(rules, encoding="utf-8").read())
            if g:
                groups[svc].add(g.group(1))
    return consumers, groups


def derive():
    topics = asyncapi_topics()
    send, recv = fragments()
    jc, jg = java_listeners()
    gc, gg = go_consumers()
    model_topics = {}
    for t in topics:
        consumers = set(recv.get(t, set())) | set(jc.get(t, set())) | set(gc.get(t, set()))
        entry = {"producers": sorted(send.get(t, set())), "consumers": sorted(consumers)}
        if t in GO_DLQ_TOPICS:
            entry["retention_ms"] = DLQ_RETENTION_MS
        model_topics[t] = entry
    roots = {}
    for svc, ids in {**jg, **gg}.items():
        # the shortest id is the service's own prefix; every other id of the service must extend it
        root = min(ids, key=len)
        roots[svc] = root
    group_ids = {**jg, **gg}
    java_services = sorted(os.path.basename(p) for p in glob.glob(os.path.join(ROOT, "java/services/*")))
    consuming = {c for t in model_topics.values() for c in t["consumers"]}
    dlq_writers = sorted(s for s in consuming if s in java_services)
    consumer_groups = {s: [roots[s]] for s in sorted(roots)}
    return {"topics": model_topics, "consumer_groups": consumer_groups, "dlq_writers": dlq_writers}, group_ids


def env_services():
    out = {}
    for f in sorted(glob.glob(os.path.join(ROOT, "infra/tofu/envs/*/main.tf"))):
        text = open(f, encoding="utf-8").read()
        block = re.search(r"\n  services = \{\n(.*?)\n  \}\n", text, re.S)
        out[os.path.relpath(f, ROOT)] = set(re.findall(r"^    ([a-z][a-z0-9-]*)\s+= \{ db", block.group(1), re.M)) if block else set()
    return out


def aggregates(topic):
    return topic[len("at."):-len(".events")]


def check():
    errors = []
    model, group_ids = derive()
    topics = sorted(model["topics"])
    event_topics = [t for t in topics if ".dlq." not in t]

    path = "infra/tofu/kafka-topics.json"
    try:
        tf = json.loads(read(path))
    except FileNotFoundError:
        sys.exit(f"{path} is missing; run tools/ops/topics_model.py --write")

    def diff(label, expected, actual):
        for x in sorted(set(expected) - set(actual)):
            errors.append(f"{label}: missing {x}")
        for x in sorted(set(actual) - set(expected)):
            errors.append(f"{label}: unexpected {x} (not in the derived model)")

    diff(f"{path} topics vs contracts/asyncapi/events.yaml", model["topics"], tf.get("topics", {}))
    for t, want in model["topics"].items():
        have = tf.get("topics", {}).get(t)
        if have is None:
            continue
        for key in ("producers", "consumers"):
            if sorted(have.get(key, [])) != want[key]:
                errors.append(f"{path} {t}.{key}: have {sorted(have.get(key, []))}, derived {want[key]}")
        if have.get("retention_ms") != want.get("retention_ms"):
            errors.append(f"{path} {t}.retention_ms: have {have.get('retention_ms')}, derived {want.get('retention_ms')}")
    if tf.get("consumer_groups") != model["consumer_groups"]:
        errors.append(f"{path} consumer_groups: have {tf.get('consumer_groups')}, derived {model['consumer_groups']}")
    if sorted(tf.get("dlq_writers", [])) != model["dlq_writers"]:
        errors.append(f"{path} dlq_writers: have {tf.get('dlq_writers')}, derived {model['dlq_writers']}")

    # every group id used in code is covered by the prefix the ACL grants
    for svc, ids in group_ids.items():
        prefixes = tf.get("consumer_groups", {}).get(svc, [])
        for gid in sorted(ids):
            if not any(gid.startswith(p) for p in prefixes):
                errors.append(f"consumer group '{gid}' of {svc} is not covered by an ACL prefix ({prefixes})")

    # the Terraform services exist in every env
    wanted = set()
    for t in tf.get("topics", {}).values():
        wanted |= set(t.get("producers", [])) | set(t.get("consumers", []))
    wanted |= set(tf.get("consumer_groups", {}))
    for env, have in env_services().items():
        for s in sorted(wanted - have):
            errors.append(f"{env}: service '{s}' in {path} has no entry in local.services")

    # infra/local/kafka/topics.txt: exact set
    local = set()
    for line in read("infra/local/kafka/topics.txt").splitlines():
        line = line.split("#", 1)[0].strip()
        if not line:
            continue
        name = line.split()[0]
        local.add(name[1:] if name.startswith("=") else f"at.{name}.events")
    diff("infra/local/kafka/topics.txt", event_topics + [t for t in topics if ".dlq." in t], local)

    # lean (OVH) Kafka: the DLQ topic Job lists every event topic's DLQ plus the Go dead-letter topics, exactly
    lean_text = read("infra/k8s/platform/lean/kafka.yaml")
    job = re.search(r"- name: TOPICS\n\s+value: >-\n(.*?)\n\s+resources:", lean_text, re.S)
    lean = set(re.findall(r"at\.[a-z0-9.-]+", job.group(1))) if job else set()
    diff("infra/k8s/platform/lean/kafka.yaml TOPICS",
         [f"{t}.dlq" for t in event_topics] + [t for t in topics if ".dlq." in t], lean)
    return errors, event_topics


def groups_table():
    model, group_ids = derive()
    lines = ["| Service | Runtime | Consumer group ids in code | ACL prefix | Topics read |", "|---|---|---|---|---|"]
    java = {os.path.basename(p) for p in glob.glob(os.path.join(ROOT, "java/services/*"))}
    for svc in sorted(model["consumer_groups"]):
        read_topics = sorted(t for t, v in model["topics"].items() if svc in v["consumers"])
        lines.append("| {} | {} | {} | `{}*` | {} |".format(
            svc, "Java" if svc in java else "Go",
            ", ".join(f"`{g}`" for g in sorted(group_ids[svc])), model["consumer_groups"][svc][0],
            ", ".join(f"`{t}`" for t in read_topics)))
    return "\n".join(lines)


def main():
    p = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    p.add_argument("--write", action="store_true")
    p.add_argument("--groups", action="store_true")
    a = p.parse_args()
    if a.groups:
        print(groups_table())
        return 0
    if a.write:
        model, _ = derive()
        out = {"_comment": "GENERATED by tools/ops/topics_model.py --write from contracts/asyncapi (topics, producers) and "
                           "the code (consumers, consumer groups). Read by infra/tofu/envs/*/main.tf. Do not edit; "
                           "tools/ops/topics-check.sh fails CI when it drifts.", **model}
        with open(os.path.join(ROOT, "infra/tofu/kafka-topics.json"), "w", encoding="utf-8") as f:
            json.dump(out, f, indent=2)
            f.write("\n")
        print("wrote infra/tofu/kafka-topics.json")
        return 0
    errors, event_topics = check()
    if errors:
        print("Kafka topic configuration differs:", *errors, sep="\n  ", file=sys.stderr)
        return 1
    print(f"Kafka topics consistent: {len(event_topics)} event topics in asyncapi, Terraform, local, lean (with DLQs)")
    return 0


if __name__ == "__main__":
    sys.exit(main())
