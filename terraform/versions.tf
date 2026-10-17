
terraform {
  required_version = ">= 1.8"
  required_providers {
    gitea = {
      source  = "go-gitea/gitea"
      version = "~> 0.8"
    }
    harbor = {
      source  = "goharbor/harbor"
      version = "~> 3.12"
    }
    sonarqube = {
      source  = "jdamata/sonarqube"
      version = "~> 0.16"
    }
    kubernetes = {
      source  = "hashicorp/kubernetes"
      version = "~> 3.3"
    }
    vault = {
      source  = "hashicorp/vault"
      version = "~> 5.13"
    }
    tls = {
      source  = "hashicorp/tls"
      version = "~> 4.4"
    }
  }
}
