# Fast-Bet — root developer Makefile.
#
# Wraps `docker compose` and friends so contributors get a single, consistent
# entry point for the local environment. All targets are phony.

SHELL := /bin/bash
.SHELLFLAGS := -eu -o pipefail -c
.ONESHELL:
.DEFAULT_GOAL := help

# --- Configuration ---------------------------------------------------------

# docker compose v2 is required.
COMPOSE       ?= docker compose
COMPOSE_FILE  ?= infra/docker-compose.yaml
ENV_FILE      ?= infra/.env
PROJECT_NAME  ?= fastbet
DC            := $(COMPOSE) --project-name $(PROJECT_NAME) --env-file $(ENV_FILE) -f $(COMPOSE_FILE)

# --- Phony declarations ----------------------------------------------------

.PHONY: help up down logs ps psql kafka-topics redis-cli smoke clean check-env

help: ## Show this help.
	@echo "Fast-Bet local infra targets:"
	@echo
	@grep -E '^[a-zA-Z_-]+:.*?## .*$$' $(MAKEFILE_LIST) \
	  | awk 'BEGIN{FS=":.*?## "}{printf "  \033[36m%-16s\033[0m %s\n", $$1, $$2}'

# --- Env guard -------------------------------------------------------------

check-env:
	@if [ ! -f "$(ENV_FILE)" ]; then \
	  echo "error: $(ENV_FILE) not found."; \
	  echo "       cp infra/.env.example $(ENV_FILE) and fill the passwords."; \
	  exit 1; \
	fi

# --- Lifecycle -------------------------------------------------------------

up: check-env ## Start the compose stack and wait for healthchecks to be green.
	$(DC) up -d --wait

down: ## Stop the compose stack (keeps volumes).
	$(DC) down

logs: ## Tail logs from all services.
	$(DC) logs -f --tail=200

ps: ## Show running services and health.
	$(DC) ps

# --- Convenience shells ----------------------------------------------------

psql: check-env ## Open a psql shell as the Postgres superuser.
	$(DC) exec postgres psql -U "$${POSTGRES_SUPERUSER:-postgres}" -d postgres

kafka-topics: ## Run kafka-topics inside the broker container (pass ARGS=...).
	$(DC) exec kafka kafka-topics --bootstrap-server localhost:9092 $(ARGS)

redis-cli: ## Open redis-cli inside the redis container.
	$(DC) exec redis redis-cli

# --- Smoke verification ----------------------------------------------------

smoke: check-env ## Run smoke checks against postgres, kafka and redis.
	@bash infra/scripts/smoke.sh

# --- Destructive -----------------------------------------------------------

clean: ## Stop the stack AND remove named volumes (asks for confirmation).
	@printf "This will stop the stack and DELETE all data volumes (postgres, kafka, redis).\nType 'yes' to continue: "
	@read CONFIRM; \
	if [ "$$CONFIRM" != "yes" ]; then \
	  echo "aborted."; exit 1; \
	fi
	$(DC) down -v
	@echo "volumes removed."
