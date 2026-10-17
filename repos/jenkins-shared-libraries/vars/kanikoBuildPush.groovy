def call (body) {

  def settings = [:]
  body.resolveStrategy = Closure.DELEGATE_FIRST
  body.delegate = settings
  body()

  container('kaniko') {
    sh '''
      REGISTRY="harbor.localhost.com/patrickmanzo"
      REPOSITORY=${JOB_NAME%/*}
      TAG=""
      ENVIRONMENT=""

      if [ $(echo $GIT_BRANCH | grep ^develop$) ]; then
        TAG="dev-${GIT_COMMIT:0:10}"
        ENVIRONMENT="dev"
      elif [ $(echo $GIT_BRANCH | grep -E "^hotfix-.*") ]; then
        TAG="${GIT_BRANCH#*-}-${GIT_COMMIT:0:10}"
        ENVIRONMENT="stg"
      fi

      DESTINATION="${REGISTRY}/${REPOSITORY}:${TAG}"
      mkdir -p ${ARTIFACTS}

      /kaniko/executor \
        --insecure \
        --ignore-path=/product_uuid \
        --destination "${DESTINATION}" \
        --label maintainer="Patrick Manzo" \
        --label org.opencontainers.image.authors="Patrick Manzo" \
        --label org.opencontainers.image.vendor="patrickmanzo" \
        --label org.opencontainers.image.url="http://gitea.localhost.com/patrickmanzo/${REPOSITORY}" \
        --label org.opencontainers.image.documentation="http://gitea.localhost.com/patrickmanzo/${REPOSITORY}" \
        --label org.opencontainers.image.title="${REPOSITORY}" \
        --label org.opencontainers.image.version="${TAG}" \
        --label version="${TAG}" \
        --label org.opencontainers.image.description="${REPOSITORY}" \
        --label org.opencontainers.image.revision="${GIT_COMMIT}" \
        --label org.opencontainers.image.source="http://gitea.localhost.com/patrickmanzo/${REPOSITORY}" \
        --label org.opencontainers.image.created="$(date -u +%Y-%m-%dT%H:%M:%SZ)" \
        --digest-file "${ARTIFACTS}/${ENVIRONMENT}.digest" \
        --context $(pwd)

      echo "${TAG}" > ${ARTIFACTS}/${ENVIRONMENT}.artifact
    '''
  }

}