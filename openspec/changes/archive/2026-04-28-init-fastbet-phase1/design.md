## Context

Fast-Bet é um projeto greenfield de estudo. O usuário escolheu microsserviços de cara como objetivo de aprendizado, com Quarkus para serviços de negócio, Rust como API Gateway, Angular + Ionic no frontend e Postgres + Kafka + Redis na infraestrutura. As fatias verticais subsequentes (cada caso de uso ponta-a-ponta) serão entregues como changes próprias e dependerão dos princípios consolidados aqui. Esta change também entrega a Fase 0 (infra-base) já operacional.

Restrições explícitas:
- 1 única instância Postgres no ambiente local, mas com isolamento forte por database (database-per-service).
- Dinheiro simulado, mas com porta de pagamento desenhada para receber adapters reais sem alteração no domínio.
- Liquidação de apostas fora da Fase 1 (apostas confirmadas ficam pendentes).
- Sem requisitos de produção (alta disponibilidade, replicação, anti-fraude); foco é aprendizado de arquitetura.

## Goals / Non-Goals

**Goals:**
- Estabelecer fronteiras claras entre microsserviços (Identity, Catalog, Wallet, Bets) com banco isolado por serviço.
- Definir o padrão Outbox + Kafka como única forma de propagação de eventos entre serviços.
- Definir a saga de aposta (Bets ↔ Wallet) e o modelo de ledger append-only para a carteira.
- Definir o port `PaymentGateway` que permite plugar/desplugar adapters de pagamento.
- Definir o esqueleto do API Gateway em Rust com responsabilidade única: terminação de auth (validação de JWT), rate-limit e roteamento por role.
- Entregar Fase 0: ambiente local subindo via `docker-compose` com Postgres (4 DBs), Kafka, Redis.

**Non-Goals:**
- Implementação de qualquer endpoint de negócio (cada fatia vertical fará isso).
- Definição detalhada de payloads REST e tópicos Kafka (princípios apenas; concretizado pelas fatias verticais).
- Liquidação de apostas, push de odds em tempo real, integração com gateway de pagamento real.
- Alta disponibilidade, replicação Postgres, multi-região, hardening de produção.
- KYC, AML, anti-fraude, compliance regulatório (dinheiro é simulado).

## Decisions

### D1. Microsserviços de cara, com 4 serviços de negócio + 1 gateway

**Decisão:** Serviços `identity`, `catalog`, `wallet`, `bets` (Quarkus) atrás de um `gateway` (Rust).

**Rationale:** Objetivo declarado pelo usuário é treinar a arquitetura de microsserviços; o domínio se decompõe naturalmente nesses 4 contextos.

**Alternativas consideradas:**
- *Monolito modular*: descartado — usuário quer treinar microsserviços explicitamente.
- *Modular monolith → microsserviços depois*: descartado pelo mesmo motivo.

### D2. Database-per-service em uma única instância Postgres

**Decisão:** Uma única instância Postgres expondo 4 databases isolados (`identity`, `catalog`, `wallet`, `bets`). Cada serviço tem seu próprio usuário com `GRANT` exclusivo no seu database. Migrations isoladas por serviço (Flyway).

**Rationale:** Mantém o benefício de isolamento lógico (sem JOINs cross-service possíveis a partir do código) com custo operacional baixo apropriado para ambiente de estudo. Migrar para instâncias físicas separadas no futuro é trivial — basta apontar a connection string.

**Alternativas consideradas:**
- *1 database, schemas separados*: descartado — risco de erosão da fronteira via JOINs cross-schema "rapidinhos".
- *4 instâncias Postgres físicas*: descartado pelo usuário (overhead operacional desnecessário em dev).
- *Bancos heterogêneos por serviço*: descartado — Postgres atende todos os requisitos da Fase 1 e reduz superfície de aprendizado.

### D3. Comunicação assíncrona via Kafka com padrão Outbox

**Decisão:** Toda propagação de evento entre serviços passa por Kafka. Todo serviço que publica um evento usa a tabela `outbox` no seu próprio Postgres e um relay separado (ex.: Debezium ou worker dedicado) lê e publica. Tópicos seguem convenção `<bounded-context>.<event-name>` (ex.: `wallet.funds-reserved`).

**Rationale:** Garante atomicidade entre escrita de estado e publicação de evento — sem Outbox, mensagens podem ser perdidas em falhas entre commit e publish. Kafka foi escolhido pelo usuário como objetivo de aprendizado.

**Alternativas consideradas:**
- *RabbitMQ*: mais simples, mas o usuário priorizou Kafka como aprendizado.
- *Publicar diretamente do código*: descartado — não é atômico com o commit do banco.
- *2PC*: descartado — complexidade operacional inaceitável.

### D4. Comunicação síncrona via HTTP somente para validações imediatas

**Decisão:** Bets pode chamar Catalog via HTTP no momento da criação da aposta para validar que a `selection` ainda está ativa e capturar o snapshot de odd. Frontend e Gateway usam HTTP. Todo o resto (efeitos colaterais que envolvem dois serviços) passa por Kafka.

**Rationale:** Validação imediata exige resposta síncrona; efeitos colaterais propagados a outros bounded contexts não.

### D5. Snapshot de odd no momento da aposta

**Decisão:** A entidade `bet` armazena `odd_value` e `selection_name` copiados no momento da criação. O serviço de Bets nunca consulta Catalog para resolver a odd posteriormente.

**Rationale:** Protege o usuário contra mudanças de odd feitas pelo odd-maker entre a aposta e a liquidação. Desacopla Bets de Catalog em runtime.

### D6. Carteira como ledger append-only

**Decisão:** A tabela `wallet_ledger` é append-only e armazena entradas com `type` (`DEPOSIT`, `WITHDRAWAL`, `BET_DEBIT`, `BET_CREDIT`, `BET_REFUND`), `amount` (com sinal, `numeric(19,4)`), `user_id` e `ref` (id da operação relacionada). Saldo é derivado por `SUM(amount)` ou materializado em projeção, com cuidado de concorrência via `SELECT ... FOR UPDATE` no usuário.

**Rationale:** Audit completo, reversões via lançamentos compensatórios, sem necessidade de UPDATE em registros financeiros.

**Alternativas consideradas:**
- *Coluna `balance` em users*: descartado — sem audit, sem reversão, perigoso.
- *Event Sourcing puro com EventStoreDB*: descartado — custo operacional alto, ganho marginal para a Fase 1.

### D7. Port hexagonal para gateway de pagamento

**Decisão:** O serviço Wallet define a interface `PaymentGateway` no domínio, com operações `charge`, `payout`, `status`. Adapters implementam a interface (Fase 1 só implementa `FakePaymentGateway`). Seleção via `@ConfigProperty` e CDI `@Alternative` no Quarkus.

**Rationale:** Permite plugar/desplugar provedores reais (Stripe, PIX, Pagar.me) sem mudar o use case. Dinheiro simulado na Fase 1, mas o desenho não muda quando virar real.

### D8. Depósito e saque assíncronos desde o adapter Fake

**Decisão:** Todas as operações de pagamento criam um registro `PENDING` que evolui para `COMPLETED` ou `FAILED` via callback (webhook em adapters reais; job interno simulado no Fake). Frontend faz polling no recurso (`GET /wallet/deposits/:id`) na Fase 1.

**Rationale:** Modelar o ciclo de vida assíncrono desde o início evita refatoração quando o adapter real entrar. Polling é simples e suficiente para a Fase 1.

### D9. Saga de aposta `RESERVED → CONFIRMED | REJECTED`

**Decisão:** A aposta nasce em `RESERVED` no Bets, que publica `bets.bet-reserved`. O Wallet consome, tenta debitar, e publica `wallet.funds-reserved` ou `wallet.funds-rejected`. O Bets consome a resposta e move para `CONFIRMED` ou `REJECTED`.

**Rationale:** Transação distribuída sem 2PC. A reserva temporária no Bets garante idempotência da operação.

### D10. API Gateway em Rust com responsabilidade única

**Decisão:** O Gateway valida JWT (chave pública compartilhada), aplica rate-limit (com Redis), e roteia para o serviço apropriado. Não agrega respostas, não compõe chamadas, não tem regras de negócio.

**Rationale:** Mantém o gateway simples e performático. Composição/agregação, se necessária, será BFF separado em fase futura.

### D11. Identidade emite JWT com claims de role

**Decisão:** Identity emite JWT (RS256) com claims `sub`, `roles`, `exp`. Demais serviços confiam na assinatura via chave pública distribuída. Gateway valida assinatura e expiração antes de rotear.

**Rationale:** Auth distribuída sem chamada round-trip ao Identity em cada request. Roles no token são suficientes para autorização nas rotas; mudanças sensíveis de role exigem novo login (aceitável na Fase 1).

### D12. Convenção de migrations e bootstrap de admin

**Decisão:** Cada serviço usa Flyway com migrations em `services/<svc>/src/main/resources/db/migration/`. O Identity tem uma migration que cria o primeiro `ADMIN` a partir de variáveis de ambiente (`FASTBET_BOOTSTRAP_ADMIN_EMAIL`, `FASTBET_BOOTSTRAP_ADMIN_PASSWORD`). Sem isso, não haveria como cadastrar funcionários.

### D13. Roadmap de fatias verticais

**Decisão:** Cada caso de uso ponta-a-ponta vira sua própria change OpenSpec, na ordem:
1. `add-user-signup` — cadastro/login de usuário comum.
2. `add-staff-management` — admin cadastra scheduler/odd-maker (introduz roles).
3. `add-event-catalog` — scheduler cria eventos; odd-maker ajusta seleções.
4. `add-wallet-deposit` — usuário deposita; primeiro produtor Kafka real.
5. `add-betting` — usuário aposta; primeira saga.
6. `add-wallet-withdrawal` — usuário saca.

**Rationale:** Vertical slices entregam valor demonstrável a cada fase, validam contratos no uso real, mantêm motivação. Cada slice é uma change pequena que referencia este design.

## Risks / Trade-offs

- **[Single point of failure: 1 instância Postgres]** → Aceitável em dev/estudo; documentar que produção exigiria HA.
- **[Operação de microsserviços é cara desde o dia 1]** → Fase 0 entrega `docker-compose` com healthchecks e Makefile; observabilidade completa (OpenTelemetry) é trabalho de fase futura.
- **[Saga de aposta tem janela de inconsistência (apostado antes de confirmado)]** → Aposta em `RESERVED` não é visível como confirmada para o usuário; UX deve refletir o estado.
- **[Padrão Outbox exige relay e processamento idempotente]** → Cada fatia vertical que produz eventos precisa implementar e testar idempotência; documentar na spec correspondente.
- **[Snapshot de odd pode divergir do catálogo se usuário aposta com cache desatualizado]** → Bets valida com Catalog via HTTP no momento da criação; se odd mudou, retorna erro `ODD_CHANGED` para o cliente decidir se tenta de novo.
- **[Bootstrap de admin via env var é fraco]** → Aceitável em estudo; produção usaria pipeline de seeding seguro.
- **[Roles no JWT só atualizam no próximo login]** → Aceitável na Fase 1; revogação de sessão é trabalho futuro.

## Migration Plan

Não aplicável — repositório greenfield. A "migração" desta change é apenas criar a estrutura de diretórios e a infra-base. Rollback: remover diretórios criados.
