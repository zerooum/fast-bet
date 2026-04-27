## Why

Fast-Bet é um sistema de apostas esportivas (estudo/portfólio) que precisa nascer com decisões de arquitetura registradas para evitar retrabalho ao longo das fases verticais subsequentes. Esta change inicial captura os princípios da Fase 1 do produto e entrega a fundação de infraestrutura (Fase 0) sobre a qual todas as fatias verticais seguintes irão construir.

## What Changes

- Define a arquitetura de microsserviços da Fase 1: API Gateway em Rust, serviços de negócio em Quarkus, Frontend Angular + Ionic.
- Estabelece princípios de arquitetura comuns a todos os serviços: database-per-service sobre uma única instância Postgres, Kafka como mensageria com padrão Outbox, port hexagonal para gateways de pagamento, ledger append-only para a carteira, snapshot de odds em apostas.
- Estabelece o roadmap em fatias verticais: cada caso de uso ponta-a-ponta vira uma change futura (`add-user-signup`, `add-staff-management`, `add-event-catalog`, `add-wallet-deposit`, `add-betting`, `add-wallet-withdrawal`).
- Declara escopo da Fase 1 do produto: cadastro de usuários, gestão de funcionários (scheduler, odd-maker), catálogo de eventos/mercados/seleções, depósito, aposta e saque.
- Declara fora-de-escopo da Fase 1: liquidação de apostas (apostas confirmadas ficam pendentes), integração com gateway de pagamento real (apenas adapter `Fake`), 2FA, recuperação de senha, e-mail de confirmação, push de odds em tempo real.
- Entrega a Fase 0 (infra base): `docker-compose` com 1 instância Postgres (4 databases isolados: `identity`, `catalog`, `wallet`, `bets`), Kafka, Redis, scripts de inicialização, healthchecks e documentação para subir o ambiente local.

## Capabilities

### New Capabilities

- `gateway`: Princípios do API Gateway em Rust (entry-point único, validação de JWT, rate-limit, roteamento por role). Endpoints concretos serão definidos pelas changes verticais.
- `identity`: Princípios da identidade e autorização (roles `USER`, `SCHEDULER`, `ODD_MAKER`, `ADMIN`, emissão de JWT, política de criação de funcionários por admin). Endpoints concretos serão definidos por `add-user-signup` e `add-staff-management`.
- `catalog`: Princípios do catálogo (modelo Event → Market → Selection com odd, ciclos de vida, suspensão durante edição, ausência de liquidação na Fase 1). Endpoints concretos serão definidos por `add-event-catalog`.
- `wallet`: Princípios da carteira (ledger append-only, saldo derivado, port hexagonal `PaymentGateway` com adapter `Fake` assíncrono, depósito e saque sempre assíncronos com estados `PENDING → COMPLETED|FAILED`). Endpoints concretos serão definidos por `add-wallet-deposit` e `add-wallet-withdrawal`.
- `betting`: Princípios das apostas (snapshot de odd e nome da seleção no momento da criação, saga `RESERVED → CONFIRMED|REJECTED` via Kafka entre Bets e Wallet, sem liquidação na Fase 1). Endpoints concretos serão definidos por `add-betting`.

### Modified Capabilities

(Nenhuma — repositório greenfield.)

## Impact

- **Repositório**: estrutura de diretórios criada do zero (`infra/`, `services/`, `gateway/`, `frontend/`).
- **Infra local**: `docker-compose.yaml` com Postgres, Kafka (com Zookeeper ou KRaft), Redis e scripts de bootstrap.
- **Stacks introduzidas**: Rust (gateway), Java/Quarkus (4 serviços), Angular + Ionic (frontend), PostgreSQL 16, Apache Kafka, Redis.
- **Convenções**: cada serviço de negócio terá seu próprio database, seu próprio usuário Postgres com permissões isoladas, e suas próprias migrations (Flyway). Comunicação cross-service apenas via HTTP ou Kafka — nunca via banco.
- **Changes futuras dependem desta**: todas as changes verticais subsequentes assumem os princípios e a infra-base aqui estabelecidos.
