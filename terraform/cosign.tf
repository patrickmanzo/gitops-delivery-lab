
resource "tls_private_key" "cosign" {
  algorithm   = "ECDSA"
  ecdsa_curve = "P256"
}

# cosignSign (shared lib) converts the PEM to cosign's format inside the pod
resource "kubernetes_secret_v1" "cosign_key" {
  metadata {
    name      = "cosign-key"
    namespace = "jenkins"
  }
  data = { "cosign.pem" = tls_private_key.cosign.private_key_pem_pkcs8 }
}
