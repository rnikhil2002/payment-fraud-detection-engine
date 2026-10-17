"""Payment traffic simulator.

Publishes card, UPI and transfer events to the Kafka "payments" topic. Most traffic is
ordinary customers; every few seconds it injects one of four fraud patterns so the
engine has something to catch:

  * device ring      many new accounts paying from the same device
  * mule chain       money hopping A -> B -> C -> D within minutes
  * card testing     a burst of tiny payments from one account
  * account takeover a big payment from a brand new device at an odd hour

    python simulator.py --bootstrap localhost:9092 --rate 20
    python simulator.py --dry-run --duration 5     # print events instead of sending
"""

from __future__ import annotations

import argparse
import json
import logging
import random
import signal
import sys
import time
import uuid
from dataclasses import asdict, dataclass, field
from typing import Iterator

log = logging.getLogger("simulator")

MERCHANTS = [
    "m-grocery-01", "m-grocery-02", "m-coffee-01", "m-fuel-01", "m-pharmacy-01",
    "m-electronics-01", "m-fashion-01", "m-travel-01", "m-streaming-01", "m-restaurant-01",
    "m-wallet-topup", "m-giftcards-01",
]
COUNTRIES = ["US", "US", "US", "IN", "IN", "GB"]


@dataclass
class Payment:
    txId: str
    type: str
    fromAccount: str
    deviceId: str
    amount: float
    currency: str
    country: str
    timestamp: int
    toAccount: str | None = None
    merchantId: str | None = None
    ipAddress: str | None = None
    scenario: str = field(default="normal")

    def to_json(self) -> str:
        return json.dumps(asdict(self))


@dataclass
class Customer:
    account: str
    devices: list[str]
    country: str


class TrafficGenerator:
    """Deterministic when given a seed, which keeps the tests stable."""

    def __init__(self, customers: int = 400, seed: int | None = None, fraud_every: int = 40):
        self.rng = random.Random(seed)
        self.fraud_every = fraud_every
        self.counter = 0
        self.customers = [self._new_customer(i) for i in range(customers)]
        self.pending: list[Payment] = []

    # ---- helpers -------------------------------------------------------------------------

    def _new_customer(self, i: int) -> Customer:
        n_devices = self.rng.choice([1, 1, 1, 2])
        devices = [f"dev-{i:05d}-{d}" for d in range(n_devices)]
        return Customer(f"acc-{i:05d}", devices, self.rng.choice(COUNTRIES))

    def _now(self) -> int:
        return int(time.time() * 1000)

    def _ip(self) -> str:
        return f"{self.rng.randint(11, 220)}.{self.rng.randint(0, 255)}.{self.rng.randint(0, 255)}.{self.rng.randint(1, 254)}"

    def _tx_id(self) -> str:
        return "tx-" + uuid.UUID(int=self.rng.getrandbits(128)).hex[:16]

    @staticmethod
    def _currency(country: str) -> str:
        return {"US": "USD", "IN": "INR", "GB": "GBP"}.get(country, "USD")

    # ---- normal traffic ------------------------------------------------------------------

    def normal_payment(self) -> Payment:
        c = self.rng.choice(self.customers)
        device = self.rng.choice(c.devices)
        roll = self.rng.random()
        if roll < 0.15:
            other = self.rng.choice(self.customers)
            return Payment(self._tx_id(), "TRANSFER", c.account, device,
                           round(self.rng.uniform(10, 400), 2), self._currency(c.country), c.country,
                           self._now(), toAccount=other.account, ipAddress=self._ip())
        kind = "UPI" if c.country == "IN" and roll < 0.6 else "CARD"
        amount = round(min(self.rng.lognormvariate(3.4, 1.0), 1500), 2)
        return Payment(self._tx_id(), kind, c.account, device, amount, self._currency(c.country),
                       c.country, self._now(), merchantId=self.rng.choice(MERCHANTS[:10]),
                       ipAddress=self._ip())

    # ---- fraud patterns ------------------------------------------------------------------

    def device_ring(self) -> list[Payment]:
        device = f"dev-ring-{uuid.UUID(int=self.rng.getrandbits(128)).hex[:8]}"
        ip = self._ip()
        size = self.rng.randint(6, 10)
        merchant = self.rng.choice(["m-giftcards-01", "m-electronics-01", "m-wallet-topup"])
        out = []
        for _ in range(size):
            account = f"acc-ring-{uuid.UUID(int=self.rng.getrandbits(128)).hex[:8]}"
            out.append(Payment(self._tx_id(), "CARD", account, device,
                               round(self.rng.uniform(300, 1200), 2), "USD", "US", self._now(),
                               merchantId=merchant, ipAddress=ip, scenario="device_ring"))
        return out

    def mule_chain(self) -> list[Payment]:
        hops = self.rng.randint(4, 6)
        accounts = [f"acc-mule-{uuid.UUID(int=self.rng.getrandbits(128)).hex[:8]}" for _ in range(hops)]
        amount = round(self.rng.uniform(3000, 9000), 2)
        out = []
        for i in range(hops - 1):
            out.append(Payment(self._tx_id(), "TRANSFER", accounts[i], f"dev-{accounts[i]}", amount,
                               "USD", "US", self._now(), toAccount=accounts[i + 1],
                               ipAddress=self._ip(), scenario="mule_chain"))
            amount = round(amount * self.rng.uniform(0.95, 0.99), 2)
        return out

    def card_testing(self) -> list[Payment]:
        c = self.rng.choice(self.customers)
        device = f"dev-bot-{uuid.UUID(int=self.rng.getrandbits(128)).hex[:8]}"
        merchant = self.rng.choice(MERCHANTS)
        return [Payment(self._tx_id(), "CARD", c.account, device, round(self.rng.uniform(0.5, 4.99), 2),
                        self._currency(c.country), c.country, self._now(), merchantId=merchant,
                        ipAddress=self._ip(), scenario="card_testing")
                for _ in range(self.rng.randint(11, 16))]

    def account_takeover(self) -> list[Payment]:
        c = self.rng.choice(self.customers)
        out = []
        for _ in range(3):  # attacker tries a few new devices before the big payment
            device = f"dev-ato-{uuid.UUID(int=self.rng.getrandbits(128)).hex[:8]}"
            out.append(Payment(self._tx_id(), "CARD", c.account, device, round(self.rng.uniform(1, 20), 2),
                               self._currency(c.country), c.country, self._now(),
                               merchantId="m-streaming-01", ipAddress=self._ip(),
                               scenario="account_takeover"))
        out.append(Payment(self._tx_id(), "TRANSFER", c.account, out[-1].deviceId,
                           round(self.rng.uniform(2500, 8000), 2), self._currency(c.country), c.country,
                           self._now(), toAccount=f"acc-drop-{uuid.UUID(int=self.rng.getrandbits(128)).hex[:8]}",
                           ipAddress=out[-1].ipAddress, scenario="account_takeover"))
        return out

    def fraud_burst(self) -> list[Payment]:
        pattern = self.rng.choice([self.device_ring, self.mule_chain, self.card_testing, self.account_takeover])
        return pattern()

    def __iter__(self) -> Iterator[Payment]:
        return self

    def __next__(self) -> Payment:
        if self.pending:
            return self.pending.pop(0)
        self.counter += 1
        if self.fraud_every and self.counter % self.fraud_every == 0:
            self.pending = self.fraud_burst()
            return self.pending.pop(0)
        return self.normal_payment()


def run(bootstrap: str, topic: str, rate: float, duration: float | None, dry_run: bool, seed: int | None) -> int:
    generator = TrafficGenerator(seed=seed)
    producer = None
    if not dry_run:
        from kafka import KafkaProducer  # imported lazily so --dry-run and tests need no Kafka

        for attempt in range(1, 31):
            try:
                producer = KafkaProducer(bootstrap_servers=bootstrap, linger_ms=20, acks=1,
                                         key_serializer=str.encode, value_serializer=str.encode)
                break
            except Exception as exc:  # noqa: BLE001 - kafka raises a few different types on startup
                log.warning("Kafka not ready (attempt %d/30): %s", attempt, exc)
                time.sleep(2)
        if producer is None:
            log.error("Could not connect to Kafka at %s", bootstrap)
            return 1

    stop = {"flag": False}
    signal.signal(signal.SIGTERM, lambda *_: stop.update(flag=True))
    signal.signal(signal.SIGINT, lambda *_: stop.update(flag=True))

    interval = 1.0 / rate if rate > 0 else 0
    started = time.time()
    sent = 0
    log.info("Sending payments to %s at ~%.0f/s%s", topic, rate, " (dry run)" if dry_run else "")
    for payment in generator:
        if stop["flag"] or (duration and time.time() - started >= duration):
            break
        payment.timestamp = int(time.time() * 1000)
        if dry_run:
            print(payment.to_json())
        else:
            producer.send(topic, key=payment.fromAccount, value=payment.to_json())
        sent += 1
        if payment.scenario != "normal" and not dry_run:
            log.info("injected %s tx=%s account=%s", payment.scenario, payment.txId, payment.fromAccount)
        if interval:
            time.sleep(interval)

    if producer:
        producer.flush()
        producer.close()
    log.info("Done, sent %d payments", sent)
    return 0


def main() -> None:
    parser = argparse.ArgumentParser(description="Payment traffic simulator")
    parser.add_argument("--bootstrap", default="localhost:9092")
    parser.add_argument("--topic", default="payments")
    parser.add_argument("--rate", type=float, default=20.0, help="payments per second")
    parser.add_argument("--duration", type=float, default=None, help="seconds to run (default forever)")
    parser.add_argument("--seed", type=int, default=None)
    parser.add_argument("--dry-run", action="store_true")
    args = parser.parse_args()

    logging.basicConfig(level=logging.INFO, format="%(asctime)s %(levelname)s %(message)s")
    sys.exit(run(args.bootstrap, args.topic, args.rate, args.duration, args.dry_run, args.seed))


if __name__ == "__main__":
    main()
