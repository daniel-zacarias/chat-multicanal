# Chat em tempo real multicanal

Clone simplificado do Slack construído com microsserviços independentes, comunicação via WebSocket e fan-out de mensagens com Redis pub/sub.

---

## Visão geral

```
Cliente → API Gateway → Chat Service → Redis pub/sub → Chat Service(s) → Clientes
                                    ↘ History Service (async)
```

| Serviço           | Responsabilidade                                 | Porta | Banco       |
|-------------------|--------------------------------------------------|-------|-------------|
| `auth-service`    | Cadastro, login, emissão de JWT                  | 3001  | PostgreSQL  |
| `chat-service`    | Conexões WebSocket, salas, entrega de mensagens  | 3002  | Redis       |
| `presence-service`| Presença online via heartbeat + TTL              | 3003  | Redis       |
| `history-service` | Persistência e paginação de mensagens antigas    | 3004  | PostgreSQL  |
| `gateway`         | Ponto de entrada único, validação de JWT         | 8080  | —           |

---

## Pré-requisitos

- [Docker](https://www.docker.com/) e Docker Compose v2+
- Node.js 20+ (para desenvolvimento local sem Docker)

---

## Estrutura do repositório

```
chat-multicanal/
├── docker-compose.yml
├── gateway/
│   ├── Dockerfile
│   └── src/
├── services/
│   ├── auth-service/
│   │   ├── Dockerfile
│   │   ├── package.json
│   │   └── src/
│   ├── chat-service/
│   │   ├── Dockerfile
│   │   ├── package.json
│   │   └── src/
│   ├── presence-service/
│   │   ├── Dockerfile
│   │   ├── package.json
│   │   └── src/
│   └── history-service/
│       ├── Dockerfile
│       ├── package.json
│       └── src/
└── packages/
    └── shared/           # tipos e schemas compartilhados
```

---

## Como rodar

### 1. Clone o repositório

```bash
git clone https://github.com/daniel-zacarias/chat-multicanal.git
cd chat-multicanal
```

### 2. Configure as variáveis de ambiente

Copie o arquivo de exemplo e preencha os valores:

```bash
cp .env.example .env
```

Variáveis necessárias em `.env`:

```env
# JWT
JWT_SECRET=sua_chave_secreta_aqui

# PostgreSQL (auth-service)
AUTH_DB_URL=postgresql://postgres:postgres@auth-db:5432/auth

# PostgreSQL (history-service)
HISTORY_DB_URL=postgresql://postgres:postgres@history-db:5432/history

# Redis
REDIS_URL=redis://redis:6379
```

### 3. Suba todos os serviços

```bash
docker compose up --build
```

O gateway ficará disponível em `http://localhost:8080`.

---

## API

### Auth Service

| Método | Endpoint           | Descrição                        |
|--------|--------------------|----------------------------------|
| POST   | `/auth/register`   | Cria uma nova conta              |
| POST   | `/auth/login`      | Autentica e retorna JWT          |
| GET    | `/auth/me`         | Retorna dados do usuário logado  |

**Exemplo — criar conta:**

```bash
curl -X POST http://localhost:8080/auth/register \
  -H "Content-Type: application/json" \
  -d '{"username": "alice", "email": "alice@example.com", "password": "senha123"}'
```

### Chat Service

| Método | Endpoint                  | Descrição                        |
|--------|---------------------------|----------------------------------|
| WS     | `/ws?token=JWT`           | Abre conexão WebSocket           |
| POST   | `/rooms`                  | Cria uma sala                    |
| POST   | `/rooms/:id/join`         | Entra em uma sala                |
| POST   | `/rooms/:id/message`      | Envia uma mensagem               |

**Exemplo — enviar mensagem via WebSocket:**

```json
{
  "type": "message",
  "room": "geral",
  "text": "Olá, mundo!"
}
```

### Presence Service

| Método | Endpoint                  | Descrição                              |
|--------|---------------------------|----------------------------------------|
| POST   | `/presence/heartbeat`     | Atualiza presença (chamar a cada 30s)  |
| GET    | `/presence/room/:id`      | Lista usuários online em uma sala      |
| GET    | `/presence/user/:id`      | Verifica se um usuário está online     |

### History Service

| Método | Endpoint                       | Descrição                             |
|--------|--------------------------------|---------------------------------------|
| GET    | `/history/room/:id?page=1`     | Busca mensagens antigas paginadas     |
| GET    | `/history/dm/:userId`          | Busca histórico de mensagens diretas  |

---

## Fluxo principal: enviar uma mensagem

1. Cliente envia `{"type":"message","room":"geral","text":"..."}` pelo WebSocket
2. Chat Service valida o JWT e verifica se o usuário é membro da sala
3. Publica a mensagem no canal Redis `room:geral`
4. Todas as instâncias do Chat Service que assinam esse canal recebem e entregam via WebSocket aos seus clientes conectados
5. Em paralelo, persiste a mensagem no History Service via HTTP assíncrono

## Fluxo de presença online

1. Cliente envia `POST /presence/heartbeat` a cada **30 segundos**
2. Presence Service grava `presence:{userId}` no Redis com TTL de **60 segundos**
3. Se o cliente desconectar sem aviso, a chave expira automaticamente
4. Ao entrar em uma sala, o frontend consulta `GET /presence/room/:id`

---

## Desenvolvimento local (sem Docker)

Para rodar um serviço isolado durante o desenvolvimento:

```bash
cd services/auth-service
npm install
npm run dev
```

Certifique-se de ter PostgreSQL e Redis rodando localmente ou use:

```bash
docker compose up postgres-auth redis
```

---

## Ordem de implementação sugerida

Se você está construindo do zero, siga essa sequência para evitar dependências bloqueantes:

1. **Infraestrutura** — suba PostgreSQL e Redis com Docker Compose antes de qualquer código
2. **Auth Service** — você vai precisar de JWT válidos para testar o resto
3. **Chat Service** — comece com um único canal antes de múltiplas salas
4. **Presence Service** — independente e simples, bom ganho de confiança
5. **History Service** — o sistema funciona sem ele, então não bloqueie cedo
6. **Gateway** — adicione por último, depois que os serviços já estiverem funcionando diretamente

---

## Conceito-chave: fan-out via Redis

O Redis pub/sub é o coração do sistema. Sem ele, usuários conectados em instâncias diferentes do Chat Service nunca se veriam.

```
Chat Service A  →  PUBLISH room:geral msg
                         ↓
                      Redis
                    /    |    \
                SUB    SUB    SUB
                  ↓      ↓      ↓
              Chat A  Chat B  Chat C
                ↓       ↓       ↓
            Cliente  Cliente  Cliente
```

Adicionar uma nova instância do Chat Service significa apenas mais um `SUBSCRIBE` — o Redis distribui as mensagens automaticamente.

---

## Bônus (extensões opcionais)

- **Busca full-text** — indexar mensagens no Elasticsearch para busca histórica
- **Menções** — detectar `@usuario` e publicar evento em fila separada para notificação
- **Circuit breaker** — proteger chamadas entre serviços com `opossum` ou similar
- **Health checks** — endpoint `/health` em cada serviço para o Docker Compose verificar dependências

---

## Licença

MIT