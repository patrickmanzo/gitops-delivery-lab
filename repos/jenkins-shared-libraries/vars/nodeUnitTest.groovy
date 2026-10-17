def call (body) {

  def settings = [:]
  body.resolveStrategy = Closure.DELEGATE_FIRST
  body.delegate = settings
  body()

  container('node') {
    sh '''
      npm ci
      npm run lint
      npm run test:ci
    '''
  }

  junit 'reports/junit.xml'
  recordCoverage(tools: [[parser: 'COBERTURA', pattern: 'coverage/cobertura-coverage.xml']],
    qualityGates: [[threshold: env.MIN_COVERAGE as double, metric: 'LINE', baseline: 'PROJECT', criticality: 'UNSTABLE']])

}
