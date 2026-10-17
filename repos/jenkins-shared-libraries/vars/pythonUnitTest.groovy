def call (body) {

  def settings = [:]
  body.resolveStrategy = Closure.DELEGATE_FIRST
  body.delegate = settings
  body()

  container('python') {
    sh '''
      pip install -r requirements-dev.txt
      bandit -r . -x '/.venv/','/tests/'
      black .
      flake8 . --exclude .venv
      pytest -v --disable-warnings --cov=application --cov-report=xml --cov-report=term --junitxml=reports/junit.xml
    '''
  }

  junit 'reports/junit.xml'
  recordCoverage(tools: [[parser: 'COBERTURA', pattern: 'coverage.xml']],
    qualityGates: [[threshold: env.MIN_COVERAGE as double, metric: 'LINE', baseline: 'PROJECT', criticality: 'UNSTABLE']])

}
