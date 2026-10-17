locals {
  apps           = ["restapi-flask", "restapi-front"] # repos with a pipeline (multibranch job in Jenkins)
  repos          = concat(local.apps, ["jenkins-shared-libraries", "helm-applications"])
  helm_apps_repo = "ssh://git@gitea.localhost.com/${var.org}/helm-applications.git"
}

locals {
  harbor_auth = base64encode("${harbor_robot_account.jenkins.full_name}:${harbor_robot_account.jenkins.secret}")
  jenkins_credentials = {
    "jenkins-gitea" = {
      type = "basicSSHUserPrivateKey"
      data = { username = "jenkins", privateKey = tls_private_key.jenkins.private_key_openssh }
    }
    "sonar-scanner-cli" = {
      type = "secretText"
      data = { text = sonarqube_user_token.jenkins.token }
    }
    "gitea-jenkins-api" = {
      type = "usernamePassword"
      data = { username = gitea_user.jenkins.username, password = local.admin_password }
    }
    # used as the "Authorization: Basic" header for the Harbor API
    "harbor-credentials" = {
      type = "secretText"
      data = { text = local.harbor_auth }
    }
  }
}
