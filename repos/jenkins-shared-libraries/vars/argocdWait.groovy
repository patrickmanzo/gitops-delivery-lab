def call (body) {

  def settings = [:]
  body.resolveStrategy = Closure.DELEGATE_FIRST
  body.delegate = settings
  body()

  // alpine container: present in every pod, including old tags
  container('alpine') {
    sh '''
      apk add kubectl

      if [ $(echo $GIT_BRANCH | grep ^develop$) ]; then
        ENVIRONMENT="dev"
      elif [ $(echo $GIT_BRANCH | grep -E "^(release|hotfix)-.*") ]; then
        ENVIRONMENT="stg"
      else
        ENVIRONMENT="prd"
      fi

      APP="${JOB_NAME%/*}-${ENVIRONMENT}"
      SHA="$(cat deploy.sha)"

      status() {
        kubectl -n argocd get application ${APP} \
          -o jsonpath='sync={.status.sync.status} revision={.status.sync.revision} health={.status.health.status}{"\\n"}{range .status.resources[*]}  {.kind}/{.name}: {.health.status} {.health.message}{"\\n"}{end}'
      }

      # Argo records the revision before applying it: wait for Synced on it. Another app
      # can push to helm-applications right after this deploy, so a synced revision
      # that contains the deploy commit also counts (clone from the deploy step).
      synced() {
        set -- $(kubectl -n argocd get application ${APP} -o jsonpath='{.status.sync.revision} {.status.sync.status}')
        [ "$2" = Synced ] || return 1
        [ "$1" = "${SHA}" ] && return 0
        git -C helm-applications fetch -q origin && git -C helm-applications merge-base --is-ancestor "${SHA}" "$1"
      }

      echo "Aguardando ${APP} Synced em ${SHA}"
      for i in $(seq 90); do
        synced && break
        if [ $i -eq 90 ]; then echo "Timeout"; status; exit 1; fi
        sleep 2
      done

      rollouts() {
        kubectl -n argocd get application ${APP} -o jsonpath='{range .status.resources[?(@.kind=="Rollout")]}{.name}{"\\n"}{end}'
      }

      # Stable revision without available pods (e.g. the seed image on a new lab):
      # there is nothing to compare the canary with, so promote in full.
      promote_without_stable() {
        for R in $(rollouts); do
          set -- $(kubectl -n ${ENVIRONMENT} get rollout ${R} -o jsonpath='{.status.stableRS} {.status.currentPodHash}')
          [ "$1" = "$2" ] && continue
          AVAILABLE="$(kubectl -n ${ENVIRONMENT} get rs ${R}-$1 -o jsonpath='{.status.availableReplicas}' 2>/dev/null)"
          if [ "${AVAILABLE:-0}" = 0 ]; then
            echo "${R}: stable $1 sem pods disponíveis, promovendo direto"
            kubectl -n ${ENVIRONMENT} patch rollout ${R} --subresource=status --type merge -p '{"status":{"promoteFull":true}}'
          fi
        done
      }

      aborted() {
        for R in $(rollouts); do
          [ "$(kubectl -n ${ENVIRONMENT} get rollout ${R} -o jsonpath='{.status.abort}')" = true ] && return 0
        done
        return 1
      }

      # Canary aborted by the analysis: fail right away. Any other Degraded can be the
      # previous revision (e.g. a missing image on a new lab) until the new one is up.
      echo "Aguardando ${APP} Healthy"
      for i in $(seq 120); do
        HEALTH="$(kubectl -n argocd get application ${APP} -o jsonpath='{.status.health.status}')"
        [ "$HEALTH" = "Healthy" ] && break
        [ $i -eq 3 ] && promote_without_stable
        if aborted || [ $i -eq 120 ]; then echo "Falhou (${HEALTH})"; status; exit 1; fi
        sleep 5
      done

      status
    '''
  }

}
