package com.nikhilr.fraud.graph;

import com.nikhilr.fraud.model.GraphSignals;
import com.nikhilr.fraud.model.PaymentEvent;
import org.neo4j.driver.Driver;
import org.neo4j.driver.Record;
import org.neo4j.driver.Session;
import org.neo4j.driver.TransactionContext;
import org.neo4j.driver.types.Node;
import org.neo4j.driver.types.Path;
import org.neo4j.driver.types.Relationship;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Repository;

import java.time.Duration;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Stores payments as a graph and answers the questions the rules need.
 * <pre>
 * (:Account)-[:USED]->(:Device)
 * (:Account)-[:PAID {amount, ts}]->(:Merchant)
 * (:Account)-[:SENT {amount, ts}]->(:Account)
 * </pre>
 */
@Repository
public class GraphRepository {

    private static final Logger log = LoggerFactory.getLogger(GraphRepository.class);

    private final Driver driver;

    public GraphRepository(Driver driver) {
        this.driver = driver;
    }

    public void ensureSchema() {
        List<String> statements = List.of(
                "CREATE CONSTRAINT account_id IF NOT EXISTS FOR (a:Account) REQUIRE a.id IS UNIQUE",
                "CREATE CONSTRAINT device_id IF NOT EXISTS FOR (d:Device) REQUIRE d.id IS UNIQUE",
                "CREATE CONSTRAINT merchant_id IF NOT EXISTS FOR (m:Merchant) REQUIRE m.id IS UNIQUE");
        try (Session session = driver.session()) {
            for (String statement : statements) {
                session.run(statement).consume();
            }
        }
        log.info("Neo4j constraints are in place");
    }

    /**
     * Writes the payment into the graph.
     *
     * @return true if this was the first time the account used this device
     */
    public boolean recordPayment(PaymentEvent e) {
        Map<String, Object> p = new HashMap<>();
        p.put("txId", e.txId());
        p.put("from", e.fromAccount());
        p.put("to", e.toAccount());
        p.put("merchant", e.merchantId());
        p.put("device", e.deviceId());
        p.put("ip", e.ipAddress());
        p.put("amount", e.amount());
        p.put("type", e.type().name());
        p.put("ts", e.timestamp());

        try (Session session = driver.session()) {
            return session.executeWrite(tx -> {
                boolean newDevice = tx.run("""
                        MERGE (a:Account {id: $from})
                          ON CREATE SET a.firstSeen = $ts, a.flagged = false
                        MERGE (d:Device {id: $device})
                        WITH a, d
                        OPTIONAL MATCH (a)-[existing:USED]->(d)
                        WITH a, d, existing IS NULL AS isNew
                        MERGE (a)-[u:USED]->(d)
                          ON CREATE SET u.firstSeen = $ts
                        SET u.lastSeen = $ts, u.lastIp = $ip
                        RETURN isNew
                        """, p).single().get("isNew").asBoolean();

                if (e.hasMerchant()) {
                    tx.run("""
                            MATCH (a:Account {id: $from})
                            MERGE (m:Merchant {id: $merchant})
                            CREATE (a)-[:PAID {txId: $txId, amount: $amount, ts: $ts, type: $type}]->(m)
                            """, p).consume();
                }
                if (e.isTransfer()) {
                    tx.run("""
                            MATCH (a:Account {id: $from})
                            MERGE (b:Account {id: $to})
                              ON CREATE SET b.firstSeen = $ts, b.flagged = false
                            CREATE (a)-[:SENT {txId: $txId, amount: $amount, ts: $ts}]->(b)
                            """, p).consume();
                }
                return newDevice;
            });
        }
    }

    public GraphSignals computeSignals(PaymentEvent e, boolean newDevice) {
        long now = e.timestamp();
        Map<String, Object> p = new HashMap<>();
        p.put("from", e.fromAccount());
        p.put("device", e.deviceId());
        p.put("merchant", e.merchantId());
        p.put("since10m", now - Duration.ofMinutes(10).toMillis());
        p.put("since1h", now - Duration.ofHours(1).toMillis());
        p.put("since24h", now - Duration.ofHours(24).toMillis());

        try (Session session = driver.session()) {
            return session.executeRead(tx -> {
                int accountsOnDevice = intQuery(tx, """
                        MATCH (:Device {id: $device})<-[:USED]-(a:Account)
                        RETURN count(DISTINCT a)
                        """, p);
                int devices24h = intQuery(tx, """
                        MATCH (:Account {id: $from})-[u:USED]->(d:Device)
                        WHERE u.lastSeen >= $since24h
                        RETURN count(DISTINCT d)
                        """, p);
                int tx10m = intQuery(tx, """
                        MATCH (:Account {id: $from})-[r:PAID|SENT]->()
                        WHERE r.ts >= $since10m
                        RETURN count(r)
                        """, p);
                int ringSize = intQuery(tx, """
                        MATCH (a:Account {id: $from})-[:USED]->(:Device)<-[:USED]-(b:Account)
                        WHERE b <> a
                        RETURN count(DISTINCT b)
                        """, p);
                int flagged = intQuery(tx, """
                        MATCH (a:Account {id: $from})-[:USED]->(:Device)<-[:USED]-(b:Account)
                        WHERE b <> a AND b.flagged = true
                        RETURN count(DISTINCT b)
                        """, p);
                int chainDepth = intQuery(tx, """
                        MATCH path = (:Account)-[:SENT*1..4]->(:Account {id: $from})
                        WHERE all(r IN relationships(path) WHERE r.ts >= $since1h)
                        RETURN coalesce(max(length(path)), 0)
                        """, p);
                double inbound = tx.run("""
                        MATCH (:Account)-[r:SENT]->(:Account {id: $from})
                        WHERE r.ts >= $since1h
                        RETURN coalesce(sum(r.amount), 0.0) AS total
                        """, p).single().get("total").asDouble();
                int merchantFlagged = e.hasMerchant() ? intQuery(tx, """
                        MATCH (:Merchant {id: $merchant})<-[:PAID]-(b:Account)
                        WHERE b.flagged = true
                        RETURN count(DISTINCT b)
                        """, p) : 0;

                return new GraphSignals(accountsOnDevice, devices24h, tx10m, ringSize, flagged,
                        chainDepth, inbound, merchantFlagged, newDevice);
            });
        }
    }

    public void flagAccount(String accountId, String reason, long ts) {
        try (Session session = driver.session()) {
            session.executeWrite(tx -> {
                tx.run("""
                        MATCH (a:Account {id: $id})
                        SET a.flagged = true, a.flaggedAt = $ts, a.flagReason = $reason
                        """, Map.of("id", accountId, "ts", ts, "reason", reason)).consume();
                return null;
            });
        }
    }

    /** Two-hop neighbourhood of an account, shaped for the dashboard graph view. */
    public GraphView neighborhood(String accountId, int limit) {
        Map<String, GraphView.GraphNode> nodes = new LinkedHashMap<>();
        Map<String, GraphView.GraphLink> links = new LinkedHashMap<>();
        try (Session session = driver.session()) {
            List<Record> records = session.executeRead(tx -> tx.run("""
                    MATCH (a:Account {id: $id})
                    OPTIONAL MATCH path = (a)-[*1..2]-()
                    RETURN a, path
                    LIMIT $limit
                    """, Map.of("id", accountId, "limit", limit)).list());

            for (Record record : records) {
                if (!record.get("a").isNull()) {
                    addNode(nodes, record.get("a").asNode());
                }
                if (record.get("path").isNull()) {
                    continue;
                }
                Path path = record.get("path").asPath();
                for (Node node : path.nodes()) {
                    addNode(nodes, node);
                }
                for (Relationship rel : path.relationships()) {
                    links.putIfAbsent(rel.elementId(), new GraphView.GraphLink(
                            rel.startNodeElementId(),
                            rel.endNodeElementId(),
                            rel.type(),
                            rel.containsKey("amount") ? rel.get("amount").asDouble() : null));
                }
            }
        }
        return new GraphView(accountId, List.copyOf(nodes.values()), List.copyOf(links.values()));
    }

    private static void addNode(Map<String, GraphView.GraphNode> nodes, Node node) {
        nodes.computeIfAbsent(node.elementId(), key -> new GraphView.GraphNode(
                key,
                node.labels().iterator().hasNext() ? node.labels().iterator().next() : "Node",
                node.containsKey("id") ? node.get("id").asString() : key,
                node.containsKey("flagged") && node.get("flagged").asBoolean()));
    }

    private static int intQuery(TransactionContext tx, String cypher, Map<String, Object> params) {
        return tx.run(cypher, params).single().get(0).asInt();
    }
}
