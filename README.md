# VaiJunto 2.0 — Consórcio Distribuído de Caronas Compartilhadas

O **VaiJunto 2.0** é uma solução distribuída e descentralizada em consórcio *peer-to-peer* (P2P) para o compartilhamento de caronas intermunicipais[cite: 4]. O sistema permite a cooperação entre diferentes plataformas/empresas de caronas sem a necessidade de um servidor centralizador ou banco de dados compartilhado[cite: 4, 5].

Cada empresa opera sua própria aplicação de servidor[cite: 5], gerenciando exclusivamente a sua base de usuários e o estoque de vagas dos seus trechos[cite: 4, 5]. A montagem e a reserva de itinerários multi-empresas ocorrem por meio de rotas REST inter-servidores[cite: 1, 5] e de um protocolo de transação distribuída de duas fases (*Two-Phase Commit* — 2PC)[cite: 1, 2].

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

* **`vaijunto-client`**: Aplicação cliente (GUI JavaFX e simulador headless)[cite: 1]. Conecta-se exclusivamente ao servidor TCP da sua empresa de origem[cite: 1, 5].
* **`vaijunto-server`**: Nó autônomo do consórcio[cite: 1, 5]. Contém os serviços de negócio, barramento TCP local para clientes, API REST para *peers*, motor de busca em grafo, gerenciador de locks e log de persistência WAL[cite: 1, 2].

---

## 🛠️ Principais Recursos Distribuídos

1. **Busca Distribuída em Tempo Real (`Grafo.java`)**:
* Algoritmo DFS (Busca em Profundidade) que combina trechos de caronas locais com trechos descobertos via chamadas REST (`GET /api/caronas/origem-destino`) nos *peers* ativos[cite: 1].


2. **Atomicidade Distribuída — Protocolo 2PC (`ReservaService.java` & `ClienteConsorcio.java`)**:
* **Fase 1 (Prepare)**: Solicita a reserva temporária/bloqueio dos assentos aos servidores parceiros[cite: 1, 2].
* **Fase 2 (Commit / Rollback)**: Se todos aprovarem, efetiva a confirmação (`/confirmar`)[cite: 1, 2]. Caso qualquer parceiro recuse ou caia, dispara a mensagem de cancelamento (`/cancelar`) para todos os trechos previamente bloqueados[cite: 1, 2].


3. **Exclusão Mútua & Precedência (`CaronaService.java`)**:
* Controle estrito de concorrência local por travas (`ReentrantLock` / `synchronized`) no nó dono do trecho[cite: 1]. A ordenação de chegada no nó validador desempata disputas simultâneas em relógios desincronizados[cite: 1, 2].


4. **Prevenção de Deadlocks Distribuídos**:
* Ordenação determinística na requisição de recursos e uso de *timeouts* curtos de conexão HTTP em `ClienteConsorcio.java`[cite: 1, 6].


5. **Tolerância a Falhas & Monitoramento (`MonitorPeers.java`)**:
* *Heartbeat* periódico entre instâncias para detectar nós *down* e desviar rotas indisponíveis[cite: 1, 2].


6. **Persistência e Recuperação via WAL (`Wal.java`)**:
* Registro sequencial em disco (`wal.log`) de todas as etapas de transação (`PREPARAR`, `CONFIRMAR`, `CANCELAR`)[cite: 1, 2]. No *startup*, o servidor lê o log e limpa transações pendentes/órfãs deixando o estado consistente[cite: 1, 2].



---

## 📡 Especificação da API REST (Comunicação Inter-Empresas)

| Método | Endpoint | Descrição | Status Codes |
| --- | --- | --- | --- |
| **GET** | `/api/caronas/origem-destino` | Consulta trechos disponíveis entre cidades | `200 OK`, `400 Bad Request` |
| **POST** | `/api/reservas/preparar` | **Fase 1 2PC**: Solicita pré-reserva/bloqueio temporário de assento | `200 OK`, `409 Conflict`, `404 Not Found` |
| **POST** | `/api/reservas/confirmar` | **Fase 2 2PC**: Confirma reserva de forma definitiva (Commit) | `200 OK`, `400 Bad Request` |
| **POST** | `/api/reservas/cancelar` | **Fase 2 2PC**: Libera pré-reserva temporária (Rollback) | `200 OK`, `400 Bad Request` |
| **GET** | `/api/health` | Verificação de disponibilidade (*Heartbeat*) | `200 OK` |

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

No módulo `vaijunto-server`, os testes automatizados cobrem cenários de corrida e falha distribuída[cite: 1]:

```bash
cd vaijunto-server
mvn test

```

* **`ConcorrenciaTest.java`**: Simula múltiplas conexões paralelas disputando cadastros e assentos locais[cite: 1].
* **`ConsorcioTransacaoTest.java`**: Valida a atomicidade do protocolo 2PC e retentativas em caso de ausência de resposta REST[cite: 1, 2].

### Simulador Autônomo de Passageiros (Headless)

Para disparar carga em massa simulando usuários simultâneos no consórcio[cite: 1, 5]:

```bash
cd vaijunto-client
chmod +x rodar-simulador.sh
./rodar-simulador.sh

```

---

## 🧪 Injeção de Falhas e Demonstração do WAL

Para verificar a tolerância a falhas exigida na apresentação do projeto[cite: 2, 3]:

1. **Simular a Queda de uma Empresa**:
```bash
docker stop vaijunto-server-2

```


* *Comportamento observado*: O `MonitorPeers` dos outros servidores detectará o erro[cite: 1, 2]. As buscas de itinerário continuarão funcionando desviando dos trechos da Empresa 2[cite: 1, 2]. Transações 2PC em andamento contendo a Empresa 2 sofrerão *rollback* atômico[cite: 1, 2].


2. **Recuperação e Consistência (WAL)**:
```bash
docker start vaijunto-server-2

```


* *Comportamento observado*: O nó lerá o arquivo `wal.log` no boot[cite: 1, 2], desfarás pré-reservas não concluídas e reativará os serviços de forma consistente no consórcio[cite: 1, 2].
