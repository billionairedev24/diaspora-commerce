# tflint config for every module and env (tofu.yml runs: tflint --recursive --config "$PWD/.tflint.hcl").
config {
  call_module_type = "local"
}

plugin "terraform" {
  enabled = true
  preset  = "recommended"
}

# The cloud module sets share ONE interface (modules/_interface); a cloud may legitimately ignore an input
# (e.g. storage_gb on Aurora, secret_store on AWS postgres, network on AWS buckets).
rule "terraform_unused_declarations" {
  enabled = false
}

# Providers are pinned once in the env roots; modules declare ranges.
rule "terraform_required_providers" {
  enabled = true
}
