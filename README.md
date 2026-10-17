# Real-Time Payment Fraud Detection Engine

A streaming fraud detection engine for card, UPI and account-to-account payments. Every
payment flows through Kafka into a Spring Boot engine that writes it into a Neo4j graph,
runs graph rules that expose fraud rings and money-mule chains, asks a scikit-learn model
for a risk score, and pushes alerts live to a React dashboard over WebSockets.

![CI](https://github.com/rnikhil2002/payment-fraud-detection-engine/actions/workflows/ci.yml/badge.svg)

## Architecture

```
 ┌────────────┐   payments    ┌──────────────────────────────────────────┐  fraud-alerts
 │ Simulator  │ ─────────────▶│  Fraud Engine (Spring Boot)              │ ─────────────▶ Kafka
 │ (Python)   │    Kafka      │                                          │
 └────────────┘               │  1. write payment into Neo4j graph       │
                              │  2. pull graph signals (Cypher)          │──── REST ────▶ ML Scorer
                              │  3. run graph rules                      │ ◀─ probability (FastAPI +
                              │  4. blend with ML score -> decision      │                scikit-learn)
                              │  5. alert + flag account if risky        │
                              └───────────────┬──────────────────────────┘
                                              │ WebSocket /ws/alerts + REST /api
                                              ▼
                                   ┌────────────────────┐
                                   │ React dashboard    │  live alerts, reasons,
                                   │ (nginx)            │  interactive graph view
                                   └────────────────────┘
```

**Graph model (Neo4j)**

```
(:Account)-[:USED {firstSeen, lastSeen}]->(:Device)
(:Account)-[:PAID {amount, ts}]->(:Merchant)
(:Account)-[:SENT {amount, ts}]->(:Account)
```

## What it catches

| Pattern | How it shows up in the graph | Rule |
|---|---|---|
| Fraud ring | many accounts on one device | `SHARED_DEVICE`, `DEVICE_RING` |
| Money mule chain | A → B → C → D transfers within an hour, each forwarding most of what it received | `MULE_CHAIN`, `PASS_THROUGH` |
| Card testing | a burst of tiny payments from one account | `VELOCITY` |
| Account takeover | large payment from a brand new device, several devices in a day | `NEW_DEVICE_HIGH_AMOUNT`, `DEVICE_HOPPING` |
| Guilt by association | shares a device with an account that was already blocked | `FLAGGED_NEIGHBOR`, `RISKY_MERCHANT` |

Each rule adds a weight to a **graph score** (capped at 1.0). The ML model returns a
**probability**. The final risk is `max(graph, 0.5 * ml + 0.5 * graph)`, so a strong,
explainable graph signal is never diluted by the model. Risk ≥ 0.45 goes to **REVIEW**,
≥ 0.75 is **BLOCK**, and blocked accounts are flagged in the graph so their neighbours
become riskier too. If the scorer is down, the engine keeps working on graph rules alone.

## Components

| Folder | What | Stack |
|---|---|---|
| `engine/` | Kafka consumer, Neo4j graph rules, risk decision, REST + WebSocket API | Java 17, Spring Boot 3, Spring Kafka, Neo4j Java driver, Micrometer |
| `scorer/` | Risk model training and a `/score` endpoint | Python, scikit-learn (HistGradientBoosting), FastAPI |
| `ingest/` | Traffic simulator that mixes normal payments with the four fraud patterns | Python, kafka-python |
| `dashboard/` | Live alert feed, per-alert explanation, interactive account graph | React, WebSockets, react-force-graph |

## Run it

Requires Docker.

```bash
docker compose up --build
```

| URL | |
|---|---|
| http://localhost:3000 | Dashboard |
| http://localhost:8080/api/stats | Engine stats |
| http://localhost:8080/api/alerts | Latest alerts |
| http://localhost:8000/docs | Scorer API |
| http://localhost:7474 | Neo4j browser (neo4j / fraudpass123) |

The simulator starts streaming about 20 payments a second and injects a fraud pattern
every couple of seconds. Alerts appear on the dashboard within a few seconds.

Score a single payment by hand:

```bash
curl -s localhost:8080/api/transactions -H 'Content-Type: application/json' -d '{
  "txId": "tx-demo-1", "type": "TRANSFER", "fromAccount": "acc-a", "toAccount": "acc-b",
  "deviceId": "dev-1", "amount": 5000, "currency": "USD", "country": "US"
}' | jq
```

Explore fraud rings in the Neo4j browser:

```cypher
MATCH (d:Device)<-[:USED]-(a:Account)
WITH d, collect(a) AS accounts WHERE size(accounts) >= 5
RETURN d, accounts
```

## Tests

| Part | Command | What it covers |
|---|---|---|
| Engine | `cd engine && mvn verify` | rule and risk unit tests, service tests with mocks, integration tests against real Kafka and Neo4j via Testcontainers |
| Scorer | `cd scorer && pytest` | data generation, model quality, API |
| Simulator | `cd ingest && pytest` | each fraud pattern has the right shape |
| Dashboard | `cd dashboard && npm test` | formatting and alert stream helpers |

GitHub Actions runs all of the above plus an end-to-end job that brings up the whole stack
with `docker compose`, streams traffic for 90 seconds and checks that fraud was caught.

## Model

The model is a gradient-boosted tree classifier trained on synthetic data that mirrors the
simulator's patterns, with 1% label noise so it has to learn overlap rather than memorise
thresholds. On a held-out 20% split:

| Metric | Value |
|---|---|
| ROC AUC | 0.92 |
| Average precision | 0.87 |
| Precision @ 0.5 | 0.97 |
| Recall @ 0.5 | 0.85 |

Retrain with `python scorer/train.py --samples 100000`.

## Configuration

| Variable | Default | |
|---|---|---|
| `KAFKA_BOOTSTRAP_SERVERS` | `localhost:9092` | |
| `NEO4J_URI` / `NEO4J_PASSWORD` | `bolt://localhost:7687` / `fraudpass123` | |
| `SCORER_URL` | `http://localhost:8000` | |
| `FRAUD_THRESHOLDS_REVIEW` / `FRAUD_THRESHOLDS_BLOCK` | `0.45` / `0.75` | decision thresholds |
| `FRAUD_SCORER_ENABLED` | `true` | turn the ML model off and run on graph rules only |

## What I learned

- Graph queries make relationships that are invisible row by row (shared devices, chains
  of transfers) cheap to ask about at payment time.
- Keeping the decision explainable matters as much as the score; analysts need the
  "why" next to every alert.
- Stream processing needs graceful degradation: a slow model should never stall payments.
