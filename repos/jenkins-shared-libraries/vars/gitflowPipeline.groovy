// gitflowPipeline { language = 'python' }   (python | node)
//   feature-*        testes + sonar
//   develop          image build -> dev
//   release-vX.Y.Z   promote image dev -> stg -> approval creates the tag
//   hotfix-vX.Y.Z    image build -> stg -> approval creates the tag
//   vX.Y.Z (tag)     promote image stg -> prd -> Gitea release (changelog) -> merge into main and develop
def call(body) {

  def settings = [:]
  body.resolveStrategy = Closure.DELEGATE_FIRST
  body.delegate = settings
  body()

  def branch = env.BRANCH_NAME
  def stages = flowFor(branch)
  def app = env.JOB_NAME.tokenize('/')[0]
  def language = settings.language ?: 'python'
  env.ARTIFACTS = "/artifacts/${app}"
  env.SMOKE_VERSION_PATH = settings.versionPath ?: '/version'
  env.SMOKE_PATHS = (settings.smokePaths ?: []).join(' ')
  env.MIN_COVERAGE = (settings.minCoverage ?: 80).toString()

  podTemplate(yaml: readTrusted('jenkinsPod.yaml')) {
    node(POD_LABEL) {
      ansiColor('xterm') {
      try {
        currentBuild.description = stages ? null : 'sem stages para esta branch'
        if (branch ==~ /feature-.*/) {
          currentBuild.description = 'CI: testes + Sonar'
        }
        stage('Checkout') {
          def scmVars = checkout scm
          env.GIT_COMMIT = scmVars.GIT_COMMIT
          env.GIT_BRANCH = branch
        }
        for (name in stages) {
          try {
            stage(name) {
              runStage(name, app, language)
            }
            if (name == 'Smoke Test') {
              doraEvent('success')
            }
          } catch (err) {
            if (name in ['Argo CD Sync', 'Smoke Test']) {
              stage('Rollback') {
                withGiteaSsh { rollbackDeploy {} }
                doraEvent('failure')
                currentBuild.description = "${currentBuild.description ?: ''} · rollback para ${readFile('rollback.tag').trim()}"
                // version only: the extra paths may be what failed
                withEnv(["SMOKE_TAG=${readFile('rollback.tag').trim()}", 'SMOKE_PATHS=']) {
                  smokeTest {}
                }
              }
            }
            throw err
          }
        }
      } catch (org.jenkinsci.plugins.workflow.steps.FlowInterruptedException err) {
        currentBuild.result = 'ABORTED'
        throw err
      } catch (err) {
        currentBuild.result = 'FAILURE'
        throw err
      } finally {
        notifyDiscord()
      }
      }
    }
  }
}

private List flowFor(String branch) {
  if (branch ==~ /feature-.*/) {
    return ['Unit tests', 'Sonarqube Scan']
  }
  if (branch == 'develop') {
    return ['Unit tests', 'Sonarqube Scan', 'Build and Push', 'Harbor Security Scan', 'Sign Image (cosign)',
            'Infrastructure Tests on K8s', 'Deploy to Development', 'Argo CD Sync', 'Smoke Test']
  }
  if (branch ==~ /release-.*/) {
    return ['Unit tests', 'Sonarqube Scan', 'Artifact Promotion', 'Deploy to Staging', 'Argo CD Sync', 'Smoke Test',
            'Promote to Production (create tag)']
  }
  if (branch ==~ /hotfix-.*/) {
    return ['Unit tests', 'Sonarqube Scan', 'Build and Push', 'Harbor Security Scan', 'Sign Image (cosign)',
            'Infrastructure Tests on K8s', 'Deploy to Staging', 'Argo CD Sync', 'Smoke Test', 'Promote to Production (create tag)']
  }
  if (branch ==~ /v[0-9]+\.[0-9]+\.[0-9]+/) {
    return ['Artifact Promotion', 'Deploy to Production', 'Argo CD Sync', 'Smoke Test', 'Gitea Release (changelog)', 'Gitflow: merge back']
  }
  return []
}

private void runStage(String name, String app, String language) {
  switch (name) {
    case 'Unit tests':
      if (language == 'node') {
        nodeUnitTest {}
      } else {
        pythonUnitTest {}
      }
      break
    case 'Sonarqube Scan':
      withSonarQubeEnv('sonarqube') {
        withCredentials([string(credentialsId: 'sonar-scanner-cli', variable: 'SONAR_LOGIN')]) {
          sonarqubeScan {}
        }
      }
      break
    case 'Build and Push':
      kanikoBuildPush {}
      break
    case 'Harbor Security Scan':
      withCredentials([string(credentialsId: 'harbor-credentials', variable: 'HARBOR_CREDENTIALS')]) {
        harborSecurityScan {}
      }
      break
    case 'Sign Image (cosign)':
      cosignSign {}
      break
    case 'Artifact Promotion':
      artifactPromotionCrane {}
      break
    case 'Infrastructure Tests on K8s':
      withGiteaSsh {
        try {
          infraTestK8s {}
        } finally {
          container('helm') {
            sh "helm delete -n citest ${app}-ci || true"
          }
        }
      }
      break
    case 'Deploy to Development':
      withGiteaSsh { deployDev {} }
      describe('dev')
      break
    case 'Deploy to Staging':
      withGiteaSsh { deployStg {} }
      describe('stg')
      break
    case 'Promote to Production (create tag)':
      input message: 'Promover para produção? Cria a tag; o deploy em prd roda no job da tag.'
      withGiteaSsh { createTag {} }
      currentBuild.description += ' · tag criada'
      break
    case 'Deploy to Production':
      input message: 'Deploy em produção?'
      withGiteaSsh { deployPrd {} }
      describe('prd')
      break
    case 'Argo CD Sync':
      argocdWait {}
      break
    case 'Smoke Test':
      smokeTest {}
      break
    case 'Gitea Release (changelog)':
      withCredentials([usernamePassword(credentialsId: 'gitea-jenkins-api', usernameVariable: 'GITEA_USER', passwordVariable: 'GITEA_PASSWORD')]) {
        giteaRelease {}
      }
      break
    case 'Gitflow: merge back':
      withGiteaSsh { gitflowMergeBack {} }
      break
    default:
      error "Stage desconhecido: ${name}"
  }
}

private void describe(String environment) {
  container('alpine') {
    def tag = sh(script: "cat ${env.ARTIFACTS}/${environment}.artifact", returnStdout: true).trim()
    currentBuild.description = "${tag} → ${environment}"
  }
}

private void withGiteaSsh(Closure steps) {
  withCredentials([sshUserPrivateKey(credentialsId: 'jenkins-gitea', keyFileVariable: 'JENKINS_SSH_PRIVATE_KEY')]) {
    steps()
  }
}

private void notifyDiscord() {
  try {
    withCredentials([string(credentialsId: 'discord-webhook', variable: 'DISCORD_WEBHOOK')]) {
      discordSend description: "Jenkins Pipeline Build",
              footer: "${JOB_BASE_NAME} (build #${BUILD_NUMBER})",
              link: "${BUILD_URL}",
              result: currentBuild.currentResult,
              title: "${JOB_NAME}",
              webhookURL: "${DISCORD_WEBHOOK}"
    }
  } catch (err) {
    echo "discord-webhook não configurado, notificação ignorada"
  }
}
