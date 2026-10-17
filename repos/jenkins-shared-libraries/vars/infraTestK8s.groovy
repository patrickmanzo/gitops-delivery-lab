def call (body) {

  def settings = [:]
  body.resolveStrategy = Closure.DELEGATE_FIRST
  body.delegate = settings
  body()

  container('helm') {
    sh '''
      apk add openssh

      mkdir $HOME/.ssh
      cp $JENKINS_SSH_PRIVATE_KEY $HOME/.ssh/id_rsa
      chmod 400 $HOME/.ssh/id_rsa

      ssh-keyscan gitea.localhost.com > $HOME/.ssh/known_hosts
      git clone git@gitea.localhost.com:patrickmanzo/helm-applications.git
      
      if [ $(echo $GIT_BRANCH | grep ^develop$) ]; then
        ENVIRONMENT="dev"
      elif [ $(echo $GIT_BRANCH | grep -E "^hotfix-.*") ]; then
        ENVIRONMENT="stg"
      fi

      # Helm 4's watcher does not understand Rollout status
      RELEASE="${JOB_NAME%/*}-ci"
      cd helm-applications/${JOB_NAME%/*}
      helm dependency build
      helm upgrade --install \
        --values values-ci.yaml \
        --namespace citest \
        --create-namespace \
        --set image.tag="$(cat ${ARTIFACTS}/${ENVIRONMENT}.artifact)" \
        --wait=legacy \
        ${RELEASE} .

      apk add kubectl
      for R in $(kubectl -n citest get rollouts.argoproj.io -l app.kubernetes.io/instance=${RELEASE} -o name); do
        kubectl -n citest wait ${R} --for=jsonpath='{.status.phase}'=Healthy --timeout=180s
      done

      helm test ${RELEASE} --namespace citest --logs
    '''
  }

}