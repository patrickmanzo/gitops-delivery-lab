
resource "tls_private_key" "jenkins" {
  algorithm = "ED25519"
}

resource "gitea_org" "this" {
  name       = var.org
  full_name  = var.org
  visibility = "public"
}

resource "gitea_repository" "repos" {
  for_each       = toset(local.repos)
  username       = gitea_org.this.name
  name           = each.key
  private        = true
  auto_init      = false
  default_branch = "main"
  description    = "Gerenciado por Terraform"

  # provider defaults that Gitea stores differently (mirror interval without a
  # mirror, whitespace conflicts): perpetual diff otherwise
  lifecycle {
    ignore_changes = [migration_mirror_interval, ignore_whitespace_conflicts]
  }
}

resource "gitea_user" "jenkins" {
  username             = "jenkins"
  login_name           = "jenkins"
  email                = "jenkins@localhost.com"
  password             = local.admin_password
  must_change_password = false
  send_notification    = false
}

resource "gitea_public_key" "jenkins" {
  username = gitea_user.jenkins.username
  title    = "jenkins"
  key      = tls_private_key.jenkins.public_key_openssh
}

resource "gitea_team" "devops" {
  organisation = gitea_org.this.name
  name         = "DevOps"
  description  = "Time de CI/CD"
  permission   = "write"
  # provider 0.8 breaks when updating the team
  include_all_repositories = true

  # Gitea reports permission "none" when permissions are per unit (units_map = write);
  # on update the provider resends "none" and Gitea rejects it ("permission mode invalid")
  lifecycle {
    ignore_changes = [permission, units]
  }
}

resource "gitea_team_members" "devops" {
  team_id = gitea_team.devops.id
  members = [gitea_user.jenkins.username]
}

# token = app name (each job's ComputedFolderWebHookTrigger in JCasC)
resource "gitea_repository_webhook" "jenkins" {
  for_each      = toset(local.apps)
  username      = gitea_org.this.name
  name          = gitea_repository.repos[each.key].name
  type          = "gitea"
  url           = "http://jenkins.jenkins.svc.cluster.local:8080/multibranch-webhook-trigger/invoke?token=${each.key}"
  content_type  = "json"
  events        = ["push"]
  branch_filter = "*"
  active        = true

  # the provider reads url/content_type back inside config
  lifecycle {
    ignore_changes = [config]
  }
}

moved {
  from = gitea_repository_webhook.jenkins
  to   = gitea_repository_webhook.jenkins["restapi-flask"]
}

# a push to helm-applications syncs Argo CD without waiting for polling
resource "gitea_repository_webhook" "argocd" {
  username      = gitea_org.this.name
  name          = gitea_repository.repos["helm-applications"].name
  type          = "gitea"
  url           = "http://argocd-server.argocd.svc.cluster.local/api/webhook"
  content_type  = "json"
  events        = ["push"]
  branch_filter = "*"
  active        = true

  lifecycle {
    ignore_changes = [config]
  }
}
