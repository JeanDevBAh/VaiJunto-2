# VaiJunto 2.0: Consórcio Distribuído de Caronas Compartilhadas

O **VaiJunto 2.0** é uma solução distribuída e descentralizada em consórcio *peer-to-peer* (P2P) para o compartilhamento de caronas intermunicipais. O sistema permite a cooperação entre diferentes plataformas/empresas de caronas sem a necessidade de um servidor centralizador ou banco de dados compartilhado.

Cada empresa opera sua própria aplicação de servidor, gerenciando exclusivamente a sua base de usuários e o estoque de vagas dos seus trechos. A montagem e a reserva de itinerários multi-empresas ocorrem por meio de rotas REST inter-servidores e de um protocolo de transação distribuída de duas fases (*Two-Phase Commit*, 2PC).

---

## 🚀 Arquitetura do Sistema

```
+-------------------+           +-------------------+
|  Cliente (JavaFX) |           |  Cliente (JavaFX) |
+---------+---------+           +---------+---------+
          | Socket TCP                    | Socket TCP
          v                               v
+---------+---------+   REST API   +---------+---------+
| Servidor Empresa A | <=========> | Servidor Empresa B |
+---------+---------+  (HTTP/JSON) +---------+---------+
          | WAL                            | WAL
          v                                v
    [ wal.log ]                      [ wal.log ]

```

### Componentes Chave:

* **`vaijunto-client`**: Aplicação cliente (GUI JavaFX e simulador headless). Conecta-se exclusivamente ao servidor TCP da sua empresa de origem.
* **`vaijunto-server`**: Nó autônomo do consórcio. Contém os serviços de negócio, barramento TCP local para clientes, API REST para *peers*, motor de busca em grafo, gerenciador de locks e log de persistência WAL.

---

## 🛠️ Principais Recursos Distribuídos

1. **Busca Distribuída em Tempo Real (`Grafo.java`)**:
   * Algoritmo DFS (Busca em Profundidade) que combina trechos de caronas locais com trechos descobertos via chamadas REST (`GET /api/caronas/origem-destino`) nos *peers* ativos.

2. **Atomicidade Distribuída: Protocolo 2PC (`ReservaService.java` & `ClienteConsorcio.java`)**:
   * **Fase 1 (Prepare)**: Solicita a reserva temporária/bloqueio dos assentos aos servidores parceiros.
   * **Fase 2 (Commit / Rollback)**: Se todos aprovarem, efetiva a confirmação (`/confirmar`). Caso qualquer parceiro recuse ou caia, dispara a mensagem de cancelamento (`/cancelar`) para todos os trechos previamente bloqueados.

3. **Exclusão Mútua & Precedência (`CaronaService.java`)**:
   * Controle estrito de concorrência local por travas (`ReentrantLock` / `synchronized`) no nó dono do trecho. A ordenação de chegada no nó validador desempata disputas simultâneas em relógios desincronizados.

4. **Prevenção de Deadlocks Distribuídos**:
   * Ordenação determinística na requisição de recursos e uso de *timeouts* curtos de conexão HTTP em `ClienteConsorcio.java`.

5. **Tolerância a Falhas & Monitoramento (`MonitorPeers.java`)**:
   * *Heartbeat* periódico entre instâncias para detectar nós *down* e desviar rotas indisponíveis.

6. **Persistência e Recuperação via WAL (`Wal.java`)**:
   * Registro sequencial em disco (`wal.log`) de todas as etapas de transação (`PREPARAR`, `CONFIRMAR`, `CANCELAR`). No *startup*, o servidor lê o log e limpa transações pendentes/órfãs, deixando o estado consistente.

---

# 🌐 Especificação da API REST do Consórcio (VaiJunto 2.0)

A API REST do **VaiJunto 2.0** permite a comunicação inter-servidores (*peer-to-peer*) entre as empresas do consórcio. Ela é utilizada para a descoberta distribuída de itinerários, sincronização de dados e execução do protocolo transacional **Two-Phase Commit (2PC)**.

---

## 🛠️ Informações Gerais

- **Prefixo Base:** `/api/v1`
- **Formato de Dados:** JSON (`application/json; charset=utf-8`)
- **Autenticação Inter-Servidores:** As rotas transacionais exigem o cabeçalho HTTP de segurança:
  - `X-Consorcio-Token: <seu_token_de_consorcio>`
- **Porta HTTP Padrão:** Configurável por nó (ex.: `7070`, `7071`, `7072`).

---

## 📋 Resumo dos Endpoints

| Método | Endpoint | Descrição | Papel no Consórcio / 2PC | Status Codes |
| :---: | :--- | :--- | :--- | :--- |
| **`GET`** | `/api/v1/saude` | Health-check e identificação do nó | Monitoramento ativo (*Heartbeat*) | `200`, `405` |
| **`GET`** | `/api/v1/trechos` | Consulta trechos com vagas em uma data | Descoberta distribuída em Grafo P2P | `200`, `400`, `405` |
| **`POST`** | `/api/v1/reservas` | Solicita e confirma reserva direta | Execução de reserva remota | `200`, `400`, `409` |
| **`POST`** | `/api/v1/reservas/cancelar` | Cancela uma reserva existente de usuário | Liberação de assentos / Rollback | `200`, `400`, `405` |
| **`GET`** | `/api/v1/reservas/estado` | Consulta o status de uma reserva por ID | Auditoria de estado do nó | `200`, `400`, `405` |
| **`POST`** | `/api/v1/tx/{txId}/prepare` | **Fase 1 do 2PC**: Reserva temporária de assentos | Voto *Prepare* com retenção por TTL | `200`, `400`, `401`, `409` |
| **`POST`** | `/api/v1/tx/{txId}/commit` | **Fase 2 do 2PC**: Efetiva a reserva temporária | *Global Commit* (Efetivação atômica) | `200`, `401`, `409` |
| **`POST`** | `/api/v1/tx/{txId}/abort` | **Fase 2 do 2PC**: Cancela o bloqueio e libera vagas | *Global Abort* (Desfazimento) | `200`, `401`, `409` |
| **`GET`** | `/api/v1/tx/{txId}/estado` | Consulta o estado transacional local | Resolução de incertezas e reconciliação | `200`, `401` |

---

## 📖 Detalhamento das Rotas

### 1. Verificação de Saúde (*Heartbeat*)
Verifica a disponibilidade do servidor participante. Utilizado continuamente pelo componente `MonitorPeers`.

- **Endpoint:** `GET /api/v1/saude`
- **Cabeçalhos:** Nenhum obrigatório.
- **Resposta de Sucesso (`200 OK`):**
  ```json
  {
    "empresaId": "EMPRESA_A",
    "status": "OK"
  }
  ```

---

### 2. Consulta de Trechos Locais
Retorna a lista de trechos de caronas que possuem vagas disponíveis na data solicitada, permitindo a montagem do Grafo P2P em tempo real.

- **Endpoint:** `GET /api/v1/trechos?data={data}`
- **Parâmetro de Query:** `data` (obrigatório, formato `YYYY-MM-DD`).
- **Resposta de Sucesso (`200 OK`):**
  ```json
  {
    "empresaId": "EMPRESA_A",
    "data": "2026-11-04",
    "trechos": [
      {
        "caronaId": "550e8400-e29b-41d4-a716-446655440000",
        "trechoId": "550e8400-e29b-41d4-a716-446655440000_VALENTINE_RHODES",
        "empresaId": "EMPRESA_A",
        "origem": "VALENTINE",
        "destino": "RHODES",
        "data": "2026-11-04",
        "preco": 25.0,
        "vagas": 2
      }
    ]
  }
  ```
- **Resposta de Erro (`400 Bad Request`):**
  ```json
  {
    "erro": "Parâmetro obrigatório: data"
  }
  ```

---

### 3. Fase 1 do 2PC: Solicitação de Preparação (*Prepare*)
Solicita a retenção temporária de assentos para uma transação distribuída (`txId`). O nó valida a disponibilidade e decrementa atomicamente as vagas, mantendo um bloqueio temporário (*Hold*) com um tempo limite (*TTL*).

- **Endpoint:** `POST /api/v1/tx/{txId}/prepare`
- **Cabeçalho:** `X-Consorcio-Token: <token>`
- **Corpo da Requisição (JSON):**
  ```json
  {
    "login": "passageiro_123",
    "ttlMs": 10000,
    "trechos": [
      {
        "caronaId": "550e8400-e29b-41d4-a716-446655440000",
        "origem": "VALENTINE",
        "destino": "RHODES"
      }
    ]
  }
  ```
- **Resposta de Sucesso, VOTO_COMMIT (`200 OK`):**
  ```json
  {
    "txId": "7a3b8c9d-1234-5678-90ab-cdef12345678",
    "sucesso": true,
    "estado": "PREPARADA",
    "empresaId": "EMPRESA_A"
  }
  ```
- **Resposta de Conflito, VOTO_ABORT (`409 Conflict`):**
  ```json
  {
    "txId": "7a3b8c9d-1234-5678-90ab-cdef12345678",
    "sucesso": false,
    "estado": "ABORT",
    "empresaId": "EMPRESA_A"
  }
  ```

---

### 4. Fase 2 do 2PC: Confirmação Global (*Commit*)
Efetiva definitivamente a reserva do assento previamente bloqueado no estado `PREPARADA`.

- **Endpoint:** `POST /api/v1/tx/{txId}/commit`
- **Cabeçalho:** `X-Consorcio-Token: <token>`
- **Corpo da Requisição:** `{}` (JSON vazio).
- **Resposta de Sucesso (`200 OK`):**
  ```json
  {
    "txId": "7a3b8c9d-1234-5678-90ab-cdef12345678",
    "sucesso": true,
    "estado": "COMMIT",
    "empresaId": "EMPRESA_A"
  }
  ```

---

### 5. Fase 2 do 2PC: Cancelamento Global (*Abort / Rollback*)
Cancela a retenção temporária do assento em caso de desistência ou recusa de algum participante, devolvendo a vaga à carona.

- **Endpoint:** `POST /api/v1/tx/{txId}/abort`
- **Cabeçalho:** `X-Consorcio-Token: <token>`
- **Corpo da Requisição:** `{}` (JSON vazio).
- **Resposta de Sucesso (`200 OK`):**
  ```json
  {
    "txId": "7a3b8c9d-1234-5678-90ab-cdef12345678",
    "sucesso": true,
    "estado": "ABORT",
    "empresaId": "EMPRESA_A"
  }
  ```

---

### 6. Consulta do Estado Transacional
Permite a auditoria e a resolução de incertezas de outros nós em relação ao estado de uma transação.

- **Endpoint:** `GET /api/v1/tx/{txId}/estado`
- **Cabeçalho:** `X-Consorcio-Token: <token>`
- **Resposta de Sucesso (`200 OK`):**
  ```json
  {
    "txId": "7a3b8c9d-1234-5678-90ab-cdef12345678",
    "estado": "PREPARADA",
    "empresaId": "EMPRESA_A"
  }
  ```

---

### 7. Gestão Direta de Reservas Remotas
Usado para criação ou cancelamento direto de reservas entre nós do consórcio fora do fluxo de transação distribuída multi-trecho.

#### **Efetuar Reserva Direta:**
- **Endpoint:** `POST /api/v1/reservas`
- **Corpo da Requisição (JSON):**
  ```json
  {
    "passageiroLogin": "passageiro_123",
    "trechos": [
      {
        "caronaId": "550e8400-e29b-41d4-a716-446655440000",
        "origem": "VALENTINE",
        "destino": "RHODES"
      }
    ]
  }
  ```
- **Resposta de Sucesso (`200 OK`):**
  ```json
  {
    "sucesso": true,
    "idReserva": "reserva-uuid-456",
    "mensagem": "Reserva confirmada no peer."
  }
  ```

#### **Cancelar Reserva Remota:**
- **Endpoint:** `POST /api/v1/reservas/cancelar`
- **Corpo da Requisição (JSON):**
  ```json
  {
    "passageiroLogin": "passageiro_123",
    "idReserva": "reserva-uuid-456"
  }
  ```
- **Resposta de Sucesso (`200 OK`):**
  ```json
  {
    "sucesso": true,
    "mensagem": "Reserva cancelada no peer."
  }
  ```

---

## 🚦 Códigos de Status HTTP

| Código | Nome | Significado |
| :---: | :--- | :--- |
| **`200`** | `OK` | Requisição processada com sucesso. |
| **`400`** | `Bad Request` | Parâmetros obrigatórios ausentes ou payload JSON malformatado. |
| **`401`** | `Unauthorized` | Token de consórcio (`X-Consorcio-Token`) ausente ou incorreto. |
| **`404`** | `Not Found` | Rota desconhecida ou recurso não encontrado. |
| **`405`** | `Method Not Allowed` | Método HTTP incorreto utilizado na requisição (ex.: usar `POST` em rota `GET`). |
| **`409`** | `Conflict` | Conflito transacional (vagas insuficientes ou erro durante a fase de *prepare*). |

---

## 📂 Estrutura do Repositório

```text
VaiJunto-2/
├── vaijunto-client/                   # Módulo Cliente
│   ├── Dockerfile
│   ├── rodar-simulador.sh             # Script para iniciar simulação de carga
│   └── src/main/java/
│       ├── client/                    # Socket TCP e VaiJuntoClient
│       ├── com/vaijunto/ui/           # Telas JavaFX (Login, Passageiro, Motorista)
│       └── simulador/                 # SimuladorPassageiros (Headless multi-thread)
├── vaijunto-server/                   # Módulo Servidor
│   ├── Dockerfile
│   ├── docker-compose.lab.yml         # Compose para ambiente de testes de laboratório
│   ├── docker-compose.prod.yml        # Compose para ambiente distribuído em produção
│   ├── rodar-consorcio-docker.sh      # Subida automatizada de instâncias Docker
│   ├── rodar-consorcio-local.sh       # Subida de instâncias Java locais para debug
│   └── src/main/java/
│       ├── api/                       # Endpoints Spark/Javalin e Cliente HTTP REST
│       ├── config/                    # Parâmetros e variáveis de ambiente (Config.java)
│       ├── controller/                # Regras de Negócio, 2PC e Locks
│       ├── model/                     # Grafo, Carona, Itinerario, Trecho
│       ├── server/                    # Listener Sockets TCP e ClientHandler
│       └── wal/                       # Eventos, gravador e leitor de Log (WAL)
└── README.md

```

---

## 🛠️ Pré-requisitos

* **Java JDK 21** ou superior
* **Apache Maven 3.8+**
* **Docker & Docker Compose** (opcional, para execução isolada em contêineres)

---

## ⚡ Como Executar

### 1. Execução Local (Modo Desenvolvimento)

Para iniciar os servidores localmente sem Docker (cada um em uma porta diferente):

```bash
cd vaijunto-server
chmod +x rodar-consorcio-local.sh
./rodar-consorcio-local.sh

```

Em seguida, para rodar a aplicação gráfica de um cliente:

```bash
cd vaijunto-client
mvn compile exec:java -Dexec.mainClass="com.vaijunto.App"

```

### 2. Execução via Docker (Modo Consórcio / Emulação do Barema)

Para compilar e subir o ambiente do consórcio com empresas rodando em contêineres isolados:

```bash
cd vaijunto-server
chmod +x rodar-consorcio-docker.sh
./rodar-consorcio-docker.sh

```

Ou diretamente via Docker Compose:

```bash
cd vaijunto-server
docker-compose -f docker-compose.lab.yml up --build

```

---

## 🧪 Testes Automatizados e Simulador de Carga

### Testes Unitários e de Concorrência (JUnit)

No módulo `vaijunto-server`, os testes automatizados cobrem cenários de corrida e falha distribuída:

```bash
cd vaijunto-server
mvn test

```

* **`ConcorrenciaTest.java`**: Simula múltiplas conexões paralelas disputando cadastros e assentos locais.
* **`ConsorcioTransacaoTest.java`**: Valida a atomicidade do protocolo 2PC e retentativas em caso de ausência de resposta REST.

### Simulador Autônomo de Passageiros (Headless)

Para disparar carga em massa simulando usuários simultâneos no consórcio:

```bash
cd vaijunto-client
chmod +x rodar-simulador.sh
./rodar-simulador.sh

```

---

## 🧪 Injeção de Falhas e Demonstração do WAL

Para verificar a tolerância a falhas exigida na apresentação do projeto:

1. **Simular a Queda de uma Empresa**:
```bash
docker stop vaijunto-server-2

```

   * *Comportamento observado*: O `MonitorPeers` dos outros servidores detectará o erro. As buscas de itinerário continuarão funcionando desviando dos trechos da Empresa 2. Transações 2PC em andamento contendo a Empresa 2 sofrerão *rollback* atômico.

2. **Recuperação e Consistência (WAL)**:
```bash
docker start vaijunto-server-2

```

   * *Comportamento observado*: O nó lerá o arquivo `wal.log` no boot, desfará pré-reservas não concluídas e reativará os serviços de forma consistente no consórcio.

---
