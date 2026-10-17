def call (body) {

  def settings = [:]
  body.resolveStrategy = Closure.DELEGATE_FIRST
  body.delegate = settings
  body()

  container('alpine') {
    sh '''
      apk add openssh git

      mkdir -p $HOME/.ssh
      cp $JENKINS_SSH_PRIVATE_KEY $HOME/.ssh/id_rsa
      chmod 400 $HOME/.ssh/id_rsa
      ssh-keyscan gitea.localhost.com > $HOME/.ssh/known_hosts
      git config --global user.email "jenkins@localhost.com"
      git config --global user.name "Jenkins CI"

      rm -rf merge-back
      git clone git@gitea.localhost.com:patrickmanzo/${JOB_NAME%/*}.git merge-back
      cd merge-back

      for BRANCH in main develop; do
        git checkout $BRANCH
        if ! git merge --no-ff --no-edit -m "Merge tag ${GIT_BRANCH} into ${BRANCH}" "${GIT_BRANCH}"; then
          echo "Conflito ao fazer merge de ${GIT_BRANCH} em ${BRANCH}: resolver manualmente no Gitea"
          exit 1
        fi
      done

      git push origin main develop
    '''
  }

}
