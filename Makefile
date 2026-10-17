###############################################################################
# Makefile – Kind CI/CD stack via Argo CD (app of apps)
# Flow: make up  ->  cluster -> Argo CD -> apps (Argo) -> hosts -> config (Terraform)
###############################################################################

.ONESHELL:
SHELL := /bin/bash
.SHELLFLAGS := -eu -o pipefail -c

GREEN := \033[1;32m
CYAN  := \033[1;36m
RESET := \033[0m

CLUSTER_NAME   ?= cicd
CLUSTER_CONFIG ?= cluster/kind-cluster-config.yaml
ARGOCD_NS      ?= argocd
# Release name must match the self-managed Application (see argocd-application.yaml)
ARGOCD_RELEASE := argocd-self-managed-application
ARGOCD_APP     := argocd/argocd-apps/argocd-application.yaml
ARGOCD_CHART_VER := $(shell sed -n 's/.*targetRevision: "\(.*\)"/\1/p' $(ARGOCD_APP) | head -1)
ARGOCD_VALUES  ?= values/argocd/argocd-values.yaml
GIT_REPO_URL   ?= https://github.com/patrickmanzo/gitops-delivery-lab.git
GITHUB_USER    ?= patrickmanzo

LB_IP      := 172.18.0.50
SUBDOMAINS := argocd jenkins gitea harbor sonarqube traefik grafana prometheus alertmanager pyrra openbao api api-dev api-stg app app-dev app-stg
PASSWORD   := DevOps@123456

TF_DIR := terraform

.DEFAULT_GOAL := help

.PHONY: help
help: ## List targets
	@grep -hE '^[a-z-]+:.*## ' $(MAKEFILE_LIST) | awk -F':.*## ' '{printf "  $(CYAN)%-18s$(RESET) %s\n", $$1, $$2}'

.PHONY: check-deps
check-deps: ## Check required binaries
	@for bin in docker kind kubectl helm jq terraform openssl; do \
		command -v $$bin >/dev/null || { echo "faltando: $$bin"; exit 1; }; \
	done
	@# kind shares the host kernel: with default inotify limits pods fail with
	@# "failed to create fsnotify watcher: too many open files"
	@if [ -r /proc/sys/fs/inotify/max_user_instances ] && \
		{ [ $$(cat /proc/sys/fs/inotify/max_user_instances) -lt 512 ] || [ $$(cat /proc/sys/fs/inotify/max_user_watches) -lt 524288 ]; }; then \
		echo "inotify baixo ($$(cat /proc/sys/fs/inotify/max_user_instances) instances / $$(cat /proc/sys/fs/inotify/max_user_watches) watches). Rode:"; \
		echo "  sudo sysctl -w fs.inotify.max_user_instances=512 fs.inotify.max_user_watches=524288"; \
		echo "  printf 'fs.inotify.max_user_instances = 512\\nfs.inotify.max_user_watches = 524288\\n' | sudo tee /etc/sysctl.d/99-kind.conf"; \
		exit 1; \
	fi
	@echo -e "$(GREEN)✔ dependências ok$(RESET)"

# ---------------------------------------------------------------------------
# Cluster
# ---------------------------------------------------------------------------
.PHONY: create-cluster delete-cluster stop start
create-cluster: ## Create the kind cluster (idempotent)
	@if kind get clusters | grep -qx $(CLUSTER_NAME); then echo "cluster $(CLUSTER_NAME) já existe"; else \
		sed "s#__ROOT__#$(CURDIR)#" $(CLUSTER_CONFIG) | kind create cluster --name $(CLUSTER_NAME) --config -; fi
	kubectl config use-context kind-$(CLUSTER_NAME)
	kubectl wait --for=condition=Ready nodes --all --timeout=180s
	# nodes come back with docker after a reboot
	docker update --restart=unless-stopped $$(kind get nodes --name $(CLUSTER_NAME)) >/dev/null

delete-cluster: ## Delete the kind cluster
	kind delete cluster --name $(CLUSTER_NAME)

stop: ## Stop the nodes (frees resources, keeps state)
	docker stop $$(kind get nodes --name $(CLUSTER_NAME))

start: ## Start the nodes again
	docker start $$(kind get nodes --name $(CLUSTER_NAME))
	kubectl config use-context kind-$(CLUSTER_NAME)

# ---------------------------------------------------------------------------
# Argo CD (bootstrapped via Helm, then self-managed by its Application)
# ---------------------------------------------------------------------------
.PHONY: setup-argocd repo-secret bootstrap wait-apps
setup-argocd: ## Install Argo CD via Helm
	helm upgrade --install $(ARGOCD_RELEASE) argo-cd \
		--repo https://argoproj.github.io/argo-helm --version $(ARGOCD_CHART_VER) \
		--namespace $(ARGOCD_NS) --create-namespace \
		--values $(ARGOCD_VALUES) --wait --timeout 5m

repo-secret: ## GitHub repo credential for a private repo (GH_TOKEN or gh auth token)
	token="$${GH_TOKEN:-$$(gh auth token 2>/dev/null || true)}"
	# a public repo needs no credential
	[ -n "$$token" ] || { echo "sem GH_TOKEN: Argo CD lê o repo sem credencial"; exit 0; }
	kubectl -n $(ARGOCD_NS) create secret generic argocd-repo-creds \
		--from-literal=type=git --from-literal=url=$(GIT_REPO_URL) \
		--from-literal=username=$(GITHUB_USER) --from-literal=password="$$token" \
		--dry-run=client -o yaml \
	| kubectl label --local -f - argocd.argoproj.io/secret-type=repository -o yaml \
	| kubectl apply -f -

# OpenBao auto-unseal key (static seal): created once, before Argo deploys OpenBao.
# Losing the key means losing the vault (a new cluster gets a new key and fresh data).
openbao-unseal-key: ## Create the OpenBao unseal key (idempotent)
	@kubectl get ns openbao >/dev/null 2>&1 || kubectl create ns openbao
	@kubectl -n openbao get secret openbao-unseal >/dev/null 2>&1 && echo "chave do OpenBao já existe" || \
		openssl rand 32 | kubectl -n openbao create secret generic openbao-unseal --from-file=key=/dev/stdin

# Harbor internal secrets and token service key, created once before Argo deploys Harbor.
# tokenKey must be PKCS#1 ("RSA PRIVATE KEY"): Harbor rejects PKCS#8.
harbor-secrets: ## Create the Harbor internal secrets and token key (idempotent)
	@kubectl get ns harbor >/dev/null 2>&1 || kubectl create ns harbor
	@kubectl -n harbor get secret harbor-internal >/dev/null 2>&1 && echo "segredos do Harbor já existem" || { \
		tmp=$$(mktemp -d); trap "rm -rf $$tmp" EXIT; \
		openssl genrsa -traditional -out $$tmp/tls.key 4096 2>/dev/null; \
		openssl req -x509 -new -key $$tmp/tls.key -subj /CN=harbor-token-ca -days 3650 -out $$tmp/tls.crt; \
		kubectl -n harbor create secret generic harbor-token --from-file=$$tmp/tls.key --from-file=$$tmp/tls.crt; \
		kubectl -n harbor create secret generic harbor-internal \
			--from-literal=secret=$$(openssl rand -hex 8) --from-literal=CSRF_KEY=$$(openssl rand -hex 16) \
			--from-literal=JOBSERVICE_SECRET=$$(openssl rand -hex 8) --from-literal=REGISTRY_HTTP_SECRET=$$(openssl rand -hex 8); \
	}

# Seed written once to OpenBao; afterwards the vault is the source (changing PASSWORD has no effect)
platform-admin-seed: ## Create the admin password seed (PASSWORD) for OpenBao (idempotent)
	@kubectl get ns external-secrets >/dev/null 2>&1 || kubectl create ns external-secrets
	@kubectl -n external-secrets get secret platform-admin-seed >/dev/null 2>&1 && echo "semente da senha já existe" || \
		kubectl -n external-secrets create secret generic platform-admin-seed --from-literal=password='$(PASSWORD)'

bootstrap: ## Apply the root apps (projects + app of apps)
	kubectl apply -f argocd/root/

wait-apps: ## Wait for platform Applications to be Healthy (ignores pipeline apps)
	@echo "aguardando plataforma (primeiro boot ~15min)..."
	# every app in argocd-apps/ must exist: between sync waves the created ones are all Healthy
	expected=$$(sed -n 's/^  name: //p' argocd/argocd-apps/*.yaml | jq -R . | jq -sc .)
	for i in $$(seq 180); do \
		pending=$$(kubectl -n $(ARGOCD_NS) get applications -o json | jq -r --argjson e "$$expected" \
			'(.items | map(select(.status.health.status == "Healthy") | .metadata.name)) as $$ok | $$e - $$ok | join(" ")'); \
		[ -z "$$pending" ] && break; \
		echo "  pendentes: $$pending"; sleep 10; \
	done
	kubectl -n $(ARGOCD_NS) get applications
	[ -z "$$pending" ] || { echo "timeout: $$pending"; exit 1; }

# ---------------------------------------------------------------------------
# Host
# ---------------------------------------------------------------------------
.PHONY: hosts
hosts: ## Point *.localhost.com to Traefik in /etc/hosts
	@for h in $(SUBDOMAINS); do \
		grep -qE "^$(LB_IP)\s.*\b$$h\.localhost\.com\b" /etc/hosts && continue; \
		sudo sed -i "/\s$$h\.localhost\.com\b/d" /etc/hosts; \
		echo "$(LB_IP) $$h.localhost.com" | sudo tee -a /etc/hosts >/dev/null; echo "  + $$h.localhost.com"; \
	done

# ---------------------------------------------------------------------------
# Service configuration (Terraform)
# ---------------------------------------------------------------------------
.PHONY: config-plan config-apply config-destroy
# gitea/vault providers reject *.localhost.com -> port-forward during the command
TF = kubectl -n gitea port-forward deploy/gitea 3000:3000 >/dev/null & pf=$$!; \
	kubectl -n openbao port-forward svc/openbao 8200:8200 >/dev/null & pfb=$$!; trap "kill $$pf $$pfb" EXIT; sleep 3; \
	export TF_VAR_openbao_jwt=$$(kubectl -n openbao create token openbao-admin --duration=15m); \
	terraform -chdir=$(TF_DIR) init -input=false -upgrade >/dev/null && terraform -chdir=$(TF_DIR)

config-plan: ## Show what will be created in Gitea/Harbor/Sonar/Jenkins/OpenBao
	$(TF) plan

config-apply: ## Create orgs, repos, tokens, credentials
	$(TF) apply -auto-approve

config-destroy: ## Remove what Terraform created
	$(TF) destroy -auto-approve

# Pushes repos/<name> to Gitea only while the remote repo is still empty;
# tracked files only (cp -r would carry ignored build output such as charts/*.tgz)
.PHONY: seed-repos
seed-repos: ## Seed Gitea with apps, shared-libs and helm-applications
	@auth="Authorization: Basic $$(printf 'gitea_admin:%s' '$(PASSWORD)' | base64 -w0)"
	for r in repos/*/; do r=$$(basename $$r); \
		empty=$$(curl -sf -H "$$auth" http://gitea.localhost.com/api/v1/repos/patrickmanzo/$$r | jq .empty); \
		[ "$$empty" = "true" ] || { echo "$$r já populado"; continue; }; \
		tmp=$$(mktemp -d); (cd repos/$$r && git ls-files -z | xargs -0 cp --parents -t $$tmp); \
		git -C $$tmp init -q -b main && git -C $$tmp add -A && \
		git -C $$tmp -c user.name="Patrick Manzo" -c user.email="patrickmanzo15@outlook.com" commit -qm "seed inicial"; \
		[ -f repos/$$r/Jenkinsfile ] && git -C $$tmp branch develop; \
		git -C $$tmp -c http.extraHeader="$$auth" push -q http://gitea.localhost.com/patrickmanzo/$$r.git main; \
		git -C $$tmp -c http.extraHeader="$$auth" push -q --all http://gitea.localhost.com/patrickmanzo/$$r.git; \
		rm -rf $$tmp; echo "  + $$r"; \
	done
	# Argo cached the error from before the Gitea repo existed
	kubectl -n $(ARGOCD_NS) get applications -o json \
	| jq -r '.items[] | select(.spec.project == "application-project") | .metadata.name' \
	| xargs -r -I{} kubectl -n $(ARGOCD_NS) annotate application {} argocd.argoproj.io/refresh=hard --overwrite >/dev/null

# ---------------------------------------------------------------------------
# Composite
# ---------------------------------------------------------------------------
.PHONY: up down passwords
up: check-deps hosts create-cluster setup-argocd repo-secret openbao-unseal-key harbor-secrets platform-admin-seed bootstrap wait-apps config-apply seed-repos passwords ## Bring everything up from scratch

down: delete-cluster ## Remove everything (cluster + Terraform state)
	rm -f $(TF_DIR)/terraform.tfstate*

.PHONY: traffic-on traffic-off
# dev k6 always runs; these targets control the stg one
traffic-on: ## Start the k6 traffic generator in stg
	kubectl -n loadgen scale deploy/k6-stg --replicas=1

traffic-off: ## Stop the k6 traffic generator in stg
	kubectl -n loadgen scale deploy/k6-stg --replicas=0

passwords: ## Access URLs
	@echo "Senha padrão (todos): $(PASSWORD)"
	@for h in argocd:admin jenkins:admin gitea:gitea_admin harbor:admin sonarqube:admin grafana:admin openbao:admin pyrra:-; do \
		echo "  http://$${h%%:*}.localhost.com  user=$${h#*:}"; done
