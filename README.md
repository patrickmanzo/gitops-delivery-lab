# Cloud Native CI/CD Lab

[Português](README.pt-BR.md)

This lab simulates a production CI/CD and platform stack on one computer.
It uses the same patterns as large Kubernetes platforms.
It runs on [kind](https://kind.sigs.k8s.io/) (Kubernetes in Docker) and uses `*.localhost.com` names.

One command creates the full lab from zero.
All components are code: Argo CD, Helm values, Terraform and Jenkins Configuration as Code.
There are no manual steps in the user interfaces.

## Objective

- Show a complete delivery flow: commit, test, scan, sign, deploy, promote, observe.
- Use standard cloud native tools and patterns.
- Recreate the same lab each time (`make down && make up`).

## What the lab includes

| Area | Tools | What it does |
|---|---|---|
| GitOps | Argo CD (app of apps, ApplicationSet, sync waves) | Applies all platform and app manifests from Git |
| Source control | Gitea | Hosts the app repos, the Helm charts and the Jenkins shared library |
| CI | Jenkins (JCasC, Kubernetes agents, shared library) | Runs a gitflow pipeline for each branch type |
| Code quality | SonarQube, unit tests, coverage | Stops the pipeline when the quality gate fails |
| Images | Kaniko, Harbor | Builds the image once and promotes it between environments (no rebuild) |
| Supply chain | Harbor scan (Trivy), SBOM, cosign, Kyverno | Scans and signs each image; Kyverno blocks unsigned images |
| Policy | Kyverno (CEL policies) | Requires resources, non-root, approved registries, no `latest` tag |
| Secrets | OpenBao, External Secrets Operator | Keeps passwords in a vault; isolates each environment |
| Delivery | Argo Rollouts, AnalysisTemplate | Canary release; Prometheus analysis promotes or aborts it |
| Scaling | metrics-server, HPA | Scales the API on CPU |
| Observability | Prometheus, Grafana, Loki, Alloy, Tempo, OpenTelemetry | Metrics, logs and traces with links between them |
| Reliability | Pyrra (SLI/SLO), Alertmanager, DORA metrics | Error budgets, burn rate alerts and delivery metrics |
| Load | k6 | Sends controlled traffic to dev all the time (stg on request) |
| Infrastructure as code | Terraform | Configures Gitea, Harbor, SonarQube, Jenkins credentials and OpenBao |
| Network | MetalLB, Traefik, CoreDNS | One fixed IP for all `*.localhost.com` names |

## Architecture

```mermaid
flowchart LR
  dev[Developer] -->|git push| gitea[Gitea]
  gitea -->|webhook| jenkins[Jenkins]
  jenkins -->|tests + quality gate| sonar[SonarQube]
  jenkins -->|build, scan, SBOM| harbor[Harbor]
  jenkins -->|sign| harbor
  jenkins -->|commit image tag| helm[(helm-applications repo)]
  helm --> argocd[Argo CD]
  argocd --> k8s[kind cluster: dev / stg / prd]
  kyverno[Kyverno] -->|admission| k8s
  openbao[OpenBao + ESO] -->|secrets| k8s
  k8s -->|metrics, logs, traces| obs[Prometheus / Loki / Tempo / Grafana]
  obs -->|canary analysis| rollouts[Argo Rollouts]
  k6[k6] -->|traffic| k8s
```

## Pipeline flow (gitflow)

The shared library (`gitflowPipeline`) runs only the stages for each branch type.

| Branch | Stages | Environment |
|---|---|---|
| `feature-*` | Tests, SonarQube | None |
| `develop` | Tests, SonarQube, build, Harbor scan, cosign signature, infrastructure test, deploy, smoke test | dev |
| `release-vX.Y.Z` | Tests, SonarQube, image promotion, deploy, approval creates the tag | stg |
| `hotfix-vX.Y.Z` | Tests, SonarQube, build, scan, signature, infrastructure test, deploy, approval creates the tag | stg |
| tag `vX.Y.Z` | Image promotion, approval, deploy, Gitea release, merge into `main` and `develop` | prd |

A deploy is a Git commit of the image tag in the `helm-applications` repo.
Argo CD applies the change.
If the smoke test fails, the pipeline reverts the commit and records a DORA failure event.

## Screenshots

| | |
|---|---|
| ![app-dev.png](docs/images/app-dev.png) Front end in dev: environment badge and the versions of the front end and the API. | ![app-prd.png](docs/images/app-prd.png) Front end in prd with the release `v1.0.0`. |
| ![gitea-repos.png](docs/images/gitea-repos.png) Gitea: app repos, Helm charts and shared library, created by Terraform. | ![jenkins-jobs.png](docs/images/jenkins-jobs.png) Jenkins: one multibranch job for each app. |
| ![jenkins-branches.png](docs/images/jenkins-branches.png) Jenkins: gitflow branches with coverage. | ![jenkins-develop.png](docs/images/jenkins-develop.png) Jenkins: `develop` pipeline from tests to smoke test. |
| ![jenkins-prd.png](docs/images/jenkins-prd.png) Jenkins: tag pipeline, promotion to prd, Gitea release and merge back. | ![sonarqube.png](docs/images/sonarqube.png) SonarQube: quality gate and coverage for each branch. |
| ![harbor-repos.png](docs/images/harbor-repos.png) Harbor: project with the app images. | ![harbor-artifact.png](docs/images/harbor-artifact.png) Harbor: one image for dev, stg and prd, with scan, cosign signature and SBOM. |
| ![argocd-apps.png](docs/images/argocd-apps.png) Argo CD: all applications are Synced and Healthy. | ![argo-rollouts.png](docs/images/argo-rollouts.png) Argo Rollouts: canary steps and analysis in the Argo CD extension. |
| ![openbao-secrets.png](docs/images/openbao-secrets.png) OpenBao: secrets for each environment. | ![traefik.png](docs/images/traefik.png) Traefik: routers and services, with OTLP tracing and access logs. |
| ![grafana-red.png](docs/images/grafana-red.png) Grafana: RED dashboard with exemplars, MongoDB, service graph and traces. | ![tempo-trace.png](docs/images/tempo-trace.png) Tempo: one request from Traefik to the API and MongoDB. |
| ![grafana-logs.png](docs/images/grafana-logs.png) Grafana: logs of each service in Loki. | ![prometheus.png](docs/images/prometheus.png) Prometheus: cluster metrics. |
| ![alertmanager.png](docs/images/alertmanager.png) Alertmanager: only the Watchdog alert is active. | ![pyrra-slos.png](docs/images/pyrra-slos.png) Pyrra: availability and latency SLOs for each environment. |
| ![pyrra-latency.png](docs/images/pyrra-latency.png) Pyrra: latency SLO and error budget. | ![grafana-dora.png](docs/images/grafana-dora.png) Grafana: DORA metrics from the pipeline events. |

## Requirements

| Item | Value |
|---|---|
| Operating system | Linux with Docker (tested on Ubuntu) |
| Memory | 16 GB free RAM minimum (the lab uses approximately 13 GB) |
| CPU | 8 cores recommended |
| Disk | 40 GB free |
| Internet | Necessary (images, Helm charts, Jenkins plugins) |
| Tools | `docker`, `kind`, `kubectl`, `helm`, `jq`, `terraform`, `openssl` (`gh` optional) |
| Access | `sudo` for `/etc/hosts` and the inotify limits |

## How to run

### 1. Set the kernel limits

kind shares the kernel of the host.
Low inotify limits cause the error `too many open files` in pods.

```bash
sudo sysctl -w fs.inotify.max_user_instances=512 fs.inotify.max_user_watches=524288
printf 'fs.inotify.max_user_instances = 512\nfs.inotify.max_user_watches = 524288\n' | sudo tee /etc/sysctl.d/99-kind.conf
```

### 2. Check the Docker network

The lab gives Traefik the fixed IP `172.18.0.50`.
This IP must be in the subnet of the Docker network `kind`.

```bash
docker network inspect kind -f '{{(index .IPAM.Config 0).Subnet}}'
```

If the result is not `172.18.0.0/16`, change the IP in these files:

- `Makefile` (`LB_IP`)
- `manifests/metallb-pool.yaml` (address pool)
- `manifests/setup-hosts.yaml` (node hosts file)
- `values/traefik/traefik-values.yaml` (`metallb.io/loadBalancerIPs`)

If the network does not exist yet, run `make create-cluster` first and do the check again.

### 3. Set the Git repository

Argo CD reads this repository from GitHub.
If you use a fork, change `GIT_REPO_URL` and `GITHUB_USER` in the `Makefile`.
Also change the `repoURL` fields in `argocd/`.
Argo CD applies only the changes that you push to the `main` branch.

A public repository needs no credential.
For a private repository, Argo CD needs a GitHub token with read access to the repository.
Use a fine-grained personal access token with the permission `Contents: Read-only`.

```bash
export GH_TOKEN=<token>
```

If `GH_TOKEN` is not set, `make up` uses `gh auth token` (when the GitHub CLI is logged in).
The token goes into the Kubernetes Secret `argocd-repo-creds` only. It is not written to a file.

### 4. Start the lab

```bash
make up
```

This command does these steps:

1. Checks the tools and the inotify limits.
2. Adds the `*.localhost.com` names to `/etc/hosts` (asks for `sudo`).
3. Creates the kind cluster (1 control plane, 2 workers).
4. Installs Argo CD and applies the root applications.
5. Waits for the platform (first start: approximately 15 to 20 minutes).
6. Runs Terraform (Gitea, Harbor, SonarQube, Jenkins credentials, OpenBao).
7. Pushes the app repos to Gitea. The first pipelines start.

dev gets the first images from the `develop` pipelines (approximately 10 minutes).
stg and prd stay without pods until the first release.
To make a release, create the branch `release-v1.0.0` from `develop` in Gitea and approve the pipeline in Jenkins.

### 5. Open the tools

```bash
make passwords
```

The default password for all tools is `DevOps@123456`.
To use a different password, run `make up PASSWORD=<value>`.
OpenBao keeps the password after the first start.
Argo CD is the exception: it uses a fixed bcrypt hash in `values/argocd/argocd-values.yaml`.

| URL | Tool |
|---|---|
| http://argocd.localhost.com | Argo CD |
| http://jenkins.localhost.com | Jenkins |
| http://gitea.localhost.com | Gitea |
| http://harbor.localhost.com | Harbor |
| http://sonarqube.localhost.com | SonarQube |
| http://grafana.localhost.com | Grafana |
| http://prometheus.localhost.com | Prometheus |
| http://alertmanager.localhost.com | Alertmanager |
| http://pyrra.localhost.com | Pyrra (SLO) |
| http://openbao.localhost.com | OpenBao (method: Username) |
| http://app-dev.localhost.com, app-stg, app | Front end (dev, stg, prd) |
| http://api-dev.localhost.com, api-stg, api | API (dev, stg, prd) |

### 6. Daily commands

| Command | Result |
|---|---|
| `make stop` / `make start` | Stops or starts the nodes. The state stays. |
| `make traffic-on` / `make traffic-off` | Starts or stops the k6 load in stg |
| `make config-plan` | Shows the Terraform changes |
| `make down` | Deletes the cluster and the Terraform state |
| `make help` | Shows all targets |

## Repository structure

| Directory | Content |
|---|---|
| `cluster/` | kind configuration |
| `argocd/root/` | Root applications (applied once by `make bootstrap`) |
| `argocd/argocd-apps/` | Platform applications (order by sync wave) |
| `argocd/applications/` | ApplicationSet for the pipeline apps (app x environment) |
| `argocd/argocd-appprojects/` | AppProjects |
| `values/<tool>/` | Helm value overrides for each chart |
| `manifests/` | MetalLB pool, CoreDNS rewrite, node hosts and Harbor registry setup |
| `monitoring/` | Dashboards, ServiceMonitors and alerts as code |
| `policies/` | Kyverno policies |
| `pyrra/` | SLO definitions |
| `secrets/` | ClusterSecretStore and ExternalSecrets for the platform |
| `loadgen/` | k6 script and deployments |
| `terraform/` | Configuration of the tools after the deploy |
| `repos/` | Initial content of the Gitea repos (API, front end, shared library, Helm charts) |

## Limits of the lab

The lab simulates production patterns. It is not a production platform.

| Topic | In the lab | In production |
|---|---|---|
| Cluster | One kind cluster on one computer | Managed cluster in many zones |
| Availability | One replica for most components | High availability for each component |
| TLS | HTTP only; Harbor is an insecure registry | TLS on all endpoints (for example cert-manager) |
| DNS | `/etc/hosts` and a CoreDNS rewrite | Real DNS zone |
| Vault unseal | Static key in a Kubernetes Secret | Cloud KMS or HSM |
| Terraform state | Local file with secrets in plain text | Remote, encrypted backend |
| Terraform secrets | SSH key, cosign key, Harbor robot and Sonar token are Kubernetes Secrets | Secrets in the vault |
| Signatures | cosign key pair, no transparency log (Rekor) | Keyless signature or KMS key with Rekor |
| Alerts | Visible in Alertmanager and Grafana; no notifications | Routed to on-call tools |
| SLO window | 7 days | 28 or 30 days |
| Backup | None | Backup and restore tests (for example Velero) |
| Pipeline access | Pipeline ServiceAccount is cluster-admin | Least privilege |
| Jenkins plugins | Downloaded from the internet on the first start | Image with the plugins inside |

## Possible next steps

- Chaos Mesh: inject failures and check the probes, canary, SLOs and alerts.
- Renovate: automatic pull requests for charts, images and plugins.
- KEDA or prometheus-adapter: scale on requests per second.
- cert-manager with the OpenBao PKI: HTTPS on all endpoints.
- Terraform secrets in OpenBao.
