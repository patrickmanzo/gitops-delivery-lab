def call (body) {

  def settings = [:]
  body.resolveStrategy = Closure.DELEGATE_FIRST
  body.delegate = settings
  body()

  container('alpine') {
    sh '''
      apk add openssh git

      mkdir $HOME/.ssh
      cp $JENKINS_SSH_PRIVATE_KEY $HOME/.ssh/id_rsa
      chmod 400 $HOME/.ssh/id_rsa

      ssh-keyscan gitea.localhost.com > $HOME/.ssh/known_hosts

      git config --global user.email "jenkins@localhost.com"
      git config --global user.name "Jenkins CI"

      if [ ! -d helm-applications ]; then
        git clone git@gitea.localhost.com:patrickmanzo/helm-applications.git
      fi
      cd helm-applications/${JOB_NAME%/*}
      # another app may have deployed since the clone
      git pull --rebase -q

      IMAGE_TAG="$(cat ${ARTIFACTS}/prd.artifact)"

      sed -i -E "s/^(  tag: ).*/\\1${IMAGE_TAG}/" values-prd.yaml
      git add values-prd.yaml
      git commit -m "[${JOB_NAME%/*}|prd] - deploy ${IMAGE_TAG}" --allow-empty
      git push
      git rev-parse HEAD > ${WORKSPACE}/deploy.sha
    '''
  }

}