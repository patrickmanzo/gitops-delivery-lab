
output "harbor_robot" {
  value = harbor_robot_account.jenkins.full_name
}

output "harbor_project" {
  value = harbor_project.this.name
}

output "gitea_repos_ssh" {
  value = { for name, repo in gitea_repository.repos : name => repo.ssh_url }
}

output "jenkins_ssh_public_key" {
  value = trimspace(tls_private_key.jenkins.public_key_openssh)
}

output "jenkins_credential_secrets" {
  description = "Secrets lidos pelo kubernetes-credentials-provider"
  value       = concat(keys(kubernetes_secret_v1.jenkins_credentials), [kubernetes_secret_v1.harbor_dockerconfig.metadata[0].name])
}

output "cosign_public_key" {
  value = tls_private_key.cosign.public_key_pem
}
