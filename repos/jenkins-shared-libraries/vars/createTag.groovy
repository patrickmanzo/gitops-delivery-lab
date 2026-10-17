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

      RELEASE_VERSION="$(cat ${ARTIFACTS}/stg.artifact | cut -d - -f 1)"

      git config \
        --global \
        --add safe.directory \
        $WORKSPACE
      git fetch --all
      git tag -a $RELEASE_VERSION -m "production release: $RELEASE_VERSION"
      git push origin $RELEASE_VERSION
    '''
  }

}