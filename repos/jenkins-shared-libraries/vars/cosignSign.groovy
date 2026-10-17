def call (body) {

  def settings = [:]
  body.resolveStrategy = Closure.DELEGATE_FIRST
  body.delegate = settings
  body()

  // promotion (crane tag) reuses the digest, so the signature holds in stg and prd
  container('cosign') {
    sh '''
      ENVIRONMENT=""
      if [ $(echo $GIT_BRANCH | grep ^develop$) ]; then
        ENVIRONMENT="dev"
      elif [ $(echo $GIT_BRANCH | grep -E "^hotfix-.*") ]; then
        ENVIRONMENT="stg"
      fi

      IMAGE="harbor.localhost.com/patrickmanzo/${JOB_NAME%/*}@$(cat ${ARTIFACTS}/${ENVIRONMENT}.digest)"

      cosign import-key-pair --key /cosign/cosign.pem --output-key-prefix /tmp/cosign >/dev/null
      # no Rekor: private registry
      cosign sign --yes --key /tmp/cosign.key --tlog-upload=false --allow-insecure-registry "${IMAGE}"
      echo "Assinada: ${IMAGE}"
    '''
  }

}
