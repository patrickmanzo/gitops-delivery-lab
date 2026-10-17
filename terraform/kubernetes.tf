
resource "kubernetes_secret_v1" "jenkins_credentials" {
  for_each = local.jenkins_credentials
  metadata {
    name      = each.key
    namespace = "jenkins"
    labels    = { "jenkins.io/credentials-type" = each.value.type }
  }
  data = each.value.data
}

resource "kubernetes_secret_v1" "discord" {
  count = var.discord_webhook == "" ? 0 : 1
  metadata {
    name      = "discord-webhook"
    namespace = "jenkins"
    labels    = { "jenkins.io/credentials-type" = "secretText" }
  }
  data = { text = var.discord_webhook }
}

resource "kubernetes_secret_v1" "harbor_dockerconfig" {
  metadata {
    name      = "harbor-dockerconfig"
    namespace = "jenkins"
  }
  type = "kubernetes.io/dockerconfigjson"
  data = {
    # ":80" for crane (it only treats HTTP as insecure with an explicit port)
    ".dockerconfigjson" = jsonencode({ auths = {
      "harbor.localhost.com"    = { auth = local.harbor_auth }
      "harbor.localhost.com:80" = { auth = local.harbor_auth }
    } })
  }
}

resource "kubernetes_persistent_volume_claim_v1" "jenkins_artifacts" {
  metadata {
    name      = "jenkins-artifacts"
    namespace = "jenkins"
  }
  spec {
    access_modes = ["ReadWriteOnce"]
    resources {
      requests = { storage = "1Gi" }
    }
  }
  wait_until_bound = false
}

resource "kubernetes_service_account_v1" "jenkins_ci" {
  metadata {
    name      = "jenkins-ci"
    namespace = "jenkins"
  }
}

# cluster-admin for the pipeline SA: lab only
resource "kubernetes_cluster_role_binding_v1" "jenkins_ci" {
  metadata {
    name = "jenkins-ci"
  }
  role_ref {
    api_group = "rbac.authorization.k8s.io"
    kind      = "ClusterRole"
    name      = "cluster-admin"
  }
  subject {
    kind      = "ServiceAccount"
    name      = kubernetes_service_account_v1.jenkins_ci.metadata[0].name
    namespace = "jenkins"
  }
}

# ArgoCD reads helm-applications from Gitea with the same key as jenkins
resource "kubernetes_secret_v1" "argocd_helm_apps_repo" {
  metadata {
    name      = "gitea-helm-applications"
    namespace = "argocd"
    labels    = { "argocd.argoproj.io/secret-type" = "repository" }
  }
  data = {
    type          = "git"
    url           = local.helm_apps_repo
    sshPrivateKey = tls_private_key.jenkins.private_key_openssh
    insecure      = "true"
  }
}
