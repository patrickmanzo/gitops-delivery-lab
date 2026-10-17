data "vault_kv_secret_v2" "platform_admin" {
  mount = "secret"
  name  = "platform/admin"
}

locals {
  admin_password = data.vault_kv_secret_v2.platform_admin.data["password"]
}

# OpenBao UI login
resource "vault_auth_backend" "userpass" {
  type = "userpass"
}

resource "vault_generic_endpoint" "ui_admin" {
  path                 = "auth/${vault_auth_backend.userpass.path}/users/admin"
  ignore_absent_fields = true
  # the password is write-only in the API: no read-back, no perpetual diff
  disable_read = true
  data_json = jsonencode({
    password       = local.admin_password
    token_policies = ["admin"]
    token_ttl      = "8h"
  })
}

# each app namespace only reaches secret/apps/<namespace>/*, with ServiceAccounts from that namespace
locals {
  app_namespaces = toset(["dev", "stg", "prd", "citest"])
}

resource "vault_policy" "apps" {
  for_each = local.app_namespaces
  name     = "apps-${each.key}"
  policy   = <<-EOT
    path "secret/data/apps/${each.key}/*" { capabilities = ["create", "read", "update"] }
    path "secret/metadata/apps/${each.key}/*" { capabilities = ["create", "read", "update", "list"] }
  EOT
}

resource "vault_kubernetes_auth_backend_role" "apps" {
  for_each                         = local.app_namespaces
  backend                          = "kubernetes"
  role_name                        = "apps-${each.key}"
  bound_service_account_names      = ["*"]
  bound_service_account_namespaces = [each.key]
  token_policies                   = [vault_policy.apps[each.key].name]
  token_ttl                        = 3600
}
