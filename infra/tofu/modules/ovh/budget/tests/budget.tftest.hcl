mock_provider "ovh" {
  mock_data "ovh_me" {
    defaults = { email = "owner@example.com", ovh_subsidiary = "FR" }
  }
}

variables {
  context = {
    name        = "afrotransact-prod"
    environment = "prod"
    region      = "GRA11"
    scope       = "project123"
    tags        = { env = "prod", owner = "platform", "cost-center" = "engineering" }
  }
  monthly_limit = 200
}

run "alerts_at_50_80_100_percent_of_the_limit" {
  command = plan
  assert {
    condition     = length(ovh_cloud_project_alerting.this) == 3 && toset([for a in ovh_cloud_project_alerting.this : a.monthly_threshold]) == toset([100, 160, 200])
    error_message = "one alert per actual threshold, to the account email when none is given"
  }
}
