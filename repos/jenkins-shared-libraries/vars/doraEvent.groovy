// Never fails the build: metrics must not block delivery
def call(String result) {
  try {
    send(result)
  } catch (org.jenkinsci.plugins.workflow.steps.FlowInterruptedException err) {
    throw err  // user aborts still abort
  } catch (err) {
    echo "aviso: evento DORA não registrado (${err})"
  }
}

private void send(String result) {
  def commitTime = sh(script: "git log -1 --format=%ct ${env.GIT_COMMIT}", returnStdout: true).trim()

  container('alpine') {
    withEnv(["DORA_RESULT=${result}", "DORA_COMMIT_TIME=${commitTime}"]) {
      sh '''
        apk add -q curl jq

        if [ "$GIT_BRANCH" = "develop" ]; then
          ENVIRONMENT="dev"
        elif echo "$GIT_BRANCH" | grep -qE "^(release|hotfix)-"; then
          ENVIRONMENT="stg"
        else
          ENVIRONMENT="prd"
        fi

        NOW=$(date +%s)
        FAILED_AT="${ARTIFACTS}/${ENVIRONMENT}.failed_at"
        RESTORE=""
        if [ "$DORA_RESULT" = "failure" ]; then
          [ -f "$FAILED_AT" ] || echo "$NOW" > "$FAILED_AT"
        elif [ -f "$FAILED_AT" ]; then
          RESTORE=$(( NOW - $(cat "$FAILED_AT") ))
          rm -f "$FAILED_AT"
        fi

        jq -n \
          --arg app "${JOB_NAME%/*}" --arg env "$ENVIRONMENT" --arg result "$DORA_RESULT" \
          --arg tag "$(cat ${ARTIFACTS}/${ENVIRONMENT}.artifact)" --arg commit "$GIT_COMMIT" \
          --arg build "$BUILD_URL" --arg ts "${NOW}000000000" \
          --argjson lead "$(( NOW - DORA_COMMIT_TIME ))" --arg restore "$RESTORE" \
          '{streams: [{stream: {source: "cicd", app: $app, environment: $env},
             values: [[$ts, ({event: "deploy", result: $result, tag: $tag, commit: $commit,
                              build: $build, lead_time_seconds: $lead}
                             + (if $restore == "" then {} else {restore_seconds: ($restore | tonumber)} end)
                             | tojson)]]}]}' > dora.json
        cat dora.json
        curl -sf -H "Content-Type: application/json" --data @dora.json \
          http://loki-gateway.loki.svc.cluster.local/loki/api/v1/push \
          || echo "aviso: evento DORA não enviado ao Loki"
      '''
    }
  }
}
