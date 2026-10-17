def call (body) {

  def settings = [:]
  body.resolveStrategy = Closure.DELEGATE_FIRST
  body.delegate = settings
  body()

  // reverts this build's deploy commit; writes the previous tag to rollback.tag
  container('alpine') {
    sh '''
      apk add openssh git

      mkdir -p $HOME/.ssh
      cp $JENKINS_SSH_PRIVATE_KEY $HOME/.ssh/id_rsa
      chmod 400 $HOME/.ssh/id_rsa
      ssh-keyscan gitea.localhost.com > $HOME/.ssh/known_hosts
      git config --global user.email "jenkins@localhost.com"
      git config --global user.name "Jenkins CI"

      if [ $(echo $GIT_BRANCH | grep ^develop$) ]; then
        ENVIRONMENT="dev"
      elif [ $(echo $GIT_BRANCH | grep -E "^(release|hotfix)-.*") ]; then
        ENVIRONMENT="stg"
      else
        ENVIRONMENT="prd"
      fi

      APP="${JOB_NAME%/*}"
      TAG="$(cat ${ARTIFACTS}/${ENVIRONMENT}.artifact)"

      cd helm-applications
      git pull --rebase
      COMMIT="$(git log -1 --format=%H --fixed-strings --grep "[${APP}|${ENVIRONMENT}] - deploy ${TAG}")"
      if [ -z "$COMMIT" ]; then
        echo "Commit de deploy de ${TAG} não encontrado: nada a reverter"
        exit 1
      fi

      git revert --no-edit "$COMMIT"
      git commit --amend -m "[${APP}|${ENVIRONMENT}] - rollback ${TAG} (verificação do deploy falhou)"
      git push

      sed -n -E 's/^  tag: *//p' ${APP}/values-${ENVIRONMENT}.yaml > ../rollback.tag
      echo "${ENVIRONMENT} volta para $(cat ../rollback.tag)"
    '''
  }

}
