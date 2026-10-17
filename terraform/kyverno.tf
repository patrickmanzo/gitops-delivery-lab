# Here rather than in policies/: the public key changes with every new cluster (cosign.tf).

resource "kubernetes_manifest" "verify_image_signatures" {
  for_each = toset(["dev", "stg", "prd"])
  manifest = {
    apiVersion = "policies.kyverno.io/v1"
    kind       = "ImageValidatingPolicy"
    metadata = {
      name = "verify-image-signatures-${each.key}"
      annotations = {
        "policies.kyverno.io/description" = "Imagens dos apps precisam da assinatura cosign do pipeline (Jenkins)."
      }
    }
    spec = {
      validationActions = ["Deny"]
      failurePolicy     = "Ignore"
      matchConstraints = {
        resourceRules = [{
          apiGroups   = [""]
          apiVersions = ["v1"]
          operations  = ["CREATE", "UPDATE"]
          resources   = ["pods"]
        }]
        namespaceSelector = {
          matchExpressions = [{
            key      = "kubernetes.io/metadata.name"
            operator = "In"
            values   = [each.key]
          }]
        }
      }
      matchImageReferences = [{ glob = "harbor.localhost.com/${var.org}/*" }]
      credentials          = { allowInsecureRegistry = true }
      # no tag-to-digest mutation: Argo would see drift
      validationConfigurations = { mutateDigest = false, verifyDigest = false, required = true }
      attestors = [{
        name = "jenkins"
        cosign = {
          key   = { data = tls_private_key.cosign.public_key_pem }
          ctlog = { insecureIgnoreTlog = true, insecureIgnoreSCT = true }
        }
      }]
      validations = [{
        expression = "images.containers.map(image, verifyImageSignatures(image, [attestors.jenkins])).all(e, e > 0)"
        message    = "Imagem sem assinatura válida do pipeline (cosign)."
      }]
    }
  }
}
