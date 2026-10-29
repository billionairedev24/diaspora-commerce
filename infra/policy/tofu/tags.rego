# Every taggable resource carries env, owner and cost-center (AWS tags_all incl. default_tags,
# GCP terraform_labels incl. provider default_labels, Azure tags, Elastic Cloud tags).
package main

import rego.v1

required_tags := {"env", "owner", "cost-center"}

# Providers whose `tags`/`labels` are not key/value cost tags.
untagged_providers := {"registry.opentofu.org/cloudflare/cloudflare", "registry.terraform.io/cloudflare/cloudflare"}

# Effective tag map for a resource, or undefined when it is not taggable / not known yet.
effective_tags(rc) := tags if {
	"tags_all" in object.keys(after(rc))
	not after_unknown(rc).tags_all
	tags := object.get(after(rc), "tags_all", {})
} else := tags if {
	"terraform_labels" in object.keys(after(rc))
	not after_unknown(rc).terraform_labels
	tags := object.get(after(rc), "terraform_labels", {})
} else := tags if {
	"tags" in object.keys(after(rc))
	not after_unknown(rc).tags
	is_object(object.get(after(rc), "tags", {}))
	tags := object.get(after(rc), "tags", {})
}

deny contains msg if {
	some rc in changes
	not rc.provider_name in untagged_providers
	tags := effective_tags(rc)
	is_object(tags)
	missing := required_tags - {k | some k, _ in tags}
	count(missing) > 0
	msg := sprintf("%s: missing required tags %v", [rc.address, sort(missing)])
}

deny contains msg if {
	some rc in changes
	not rc.provider_name in untagged_providers
	tags := effective_tags(rc)
	is_object(tags)
	some k in ["env", "owner", "cost-center"]
	object.get(tags, k, "x") == ""
	msg := sprintf("%s: tag %q is empty", [rc.address, k])
}
