def call (body) {

  def settings = [:]
  body.resolveStrategy = Closure.DELEGATE_FIRST
  body.delegate = settings
  body()

  container('alpine') {
    sh '''
      apk add curl jq

      API="http://gitea.localhost.com/api/v1/repos/patrickmanzo/${JOB_NAME%/*}"
      TAG="${GIT_BRANCH}"
      AUTH="${GITEA_USER}:${GITEA_PASSWORD}"

      PREV=$(curl -sf -u "$AUTH" "$API/tags?limit=100" | jq -r '.[].name' | sort -V | grep -B1 -x "$TAG" | grep -vx "$TAG" || true)

      if [ -n "$PREV" ]; then
        CHANGES=$(curl -sf -u "$AUTH" "$API/compare/${PREV}...${TAG}" \
          | jq -r '.commits[] | select(.parents | length < 2) | "- \\(.commit.message | split("\\n")[0]) (\\(.sha[0:7]))"')
        NOTES="Mudanças desde ${PREV}:"
      else
        CHANGES="- Primeira release"
        NOTES=""
      fi
      printf '%s\\n\\n%s\\n' "$NOTES" "$CHANGES"

      jq -n --arg tag "$TAG" --arg body "$(printf '%s\\n\\n%s' "$NOTES" "$CHANGES")" \
        '{tag_name: $tag, name: $tag, body: $body}' > release.json

      STATUS=$(curl -s -o /dev/null -w '%{http_code}' -u "$AUTH" -H 'Content-Type: application/json' \
        -X POST "$API/releases" -d @release.json)
      case "$STATUS" in
        201) echo "Release $TAG criada" ;;
        409) echo "Release $TAG já existe, mantida" ;;
        *) echo "Falha ao criar release $TAG (HTTP $STATUS)"; exit 1 ;;
      esac
    '''
  }

}
