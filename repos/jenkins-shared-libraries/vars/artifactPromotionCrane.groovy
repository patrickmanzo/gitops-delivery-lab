def call (body) {

  def settings = [:]
  body.resolveStrategy = Closure.DELEGATE_FIRST
  body.delegate = settings
  body()

  container('crane') {
    sh '''
      # without an explicit port crane rejects Harbor's HTTP realm even with --insecure
      REGISTRY="harbor.localhost.com:80/patrickmanzo"
      REPOSITORY=${JOB_NAME%/*}

      OLD_TAG=""
      TAG=""
      ENVIRONMENT=""

      if [ $(echo $GIT_BRANCH | grep -E "^release-.*") ]; then
        OLD_TAG="$(cat ${ARTIFACTS}/dev.artifact)"
        ENVIRONMENT="stg"
        TAG="${GIT_BRANCH#*-}-$(echo ${OLD_TAG} | cut -d - -f 2)"
      elif [ $(echo $GIT_BRANCH | grep -E "v[0-9]\\.[0-9]{1,2}\\.[0-9]{1,3}$") ]; then
        OLD_TAG="$(cat ${ARTIFACTS}/stg.artifact)"
        ENVIRONMENT="prd"
        TAG="$(echo ${OLD_TAG} | cut -d - -f 1)"
      fi

      OLD_DESTINATION="${REGISTRY}/${REPOSITORY}:${OLD_TAG}"

      crane tag ${OLD_DESTINATION} ${TAG} --insecure

      mkdir -p ${ARTIFACTS}
      echo "${TAG}" > ${ARTIFACTS}/${ENVIRONMENT}.artifact
    '''
  }

}