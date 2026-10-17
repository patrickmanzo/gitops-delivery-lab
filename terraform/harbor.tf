
resource "harbor_project" "this" {
  name                   = var.org
  public                 = true
  vulnerability_scanning = true
  auto_sbom_generation   = true
  force_destroy          = true
}

resource "harbor_robot_account" "jenkins" {
  name  = "jenkins"
  level = "project"

  permissions {
    kind      = "project"
    namespace = harbor_project.this.name
    access {
      action   = "push"
      resource = "repository"
    }
    access {
      action   = "pull"
      resource = "repository"
    }
    access {
      action   = "read"
      resource = "artifact"
    }
    access {
      action   = "list"
      resource = "artifact"
    }
    access {
      action   = "create"
      resource = "tag"
    }
  }
}
