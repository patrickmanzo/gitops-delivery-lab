def call (body) {

  def settings = [:]
  body.resolveStrategy = Closure.DELEGATE_FIRST
  body.delegate = settings
  body()

  container('alpine') {
    sh '''
      apk add curl

      if [ $(echo $GIT_BRANCH | grep ^develop$) ]; then
        ENVIRONMENT="dev"
      elif [ $(echo $GIT_BRANCH | grep -E "^(release|hotfix)-.*") ]; then
        ENVIRONMENT="stg"
      else
        ENVIRONMENT="prd"
      fi

      TAG="${SMOKE_TAG:-$(cat ${ARTIFACTS}/${ENVIRONMENT}.artifact)}"
      HOST="$(sed -n -E 's/^  host: *//p' helm-applications/${JOB_NAME%/*}/values-${ENVIRONMENT}.yaml)"
      URL="http://${HOST}${SMOKE_VERSION_PATH}"

      echo "Aguardando ${URL} servir ${TAG}"
      for i in $(seq 60); do
        if curl -sf "$URL" | grep -q "\\"${TAG}\\""; then
          echo "OK: ${ENVIRONMENT} servindo ${TAG}"
          break
        fi
        if [ $i -eq 60 ]; then
          echo "ERRO: ${URL} não serviu ${TAG} em 5 min. Resposta atual:"
          curl -s "$URL"
          exit 1
        fi
        sleep 5
      done

      for P in $SMOKE_PATHS; do
        # up to 5 min: on a new lab /api/health waits for the first API deploy
        curl -sf --retry 30 --retry-all-errors --retry-delay 10 \\
          -o /dev/null -w "http://${HOST}${P} -> %{http_code}\\n" "http://${HOST}${P}"
      done
    '''
  }

}
