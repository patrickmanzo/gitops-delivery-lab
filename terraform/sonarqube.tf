
resource "sonarqube_user_token" "jenkins" {
  name = "jenkins"
  type = "GLOBAL_ANALYSIS_TOKEN"
}
