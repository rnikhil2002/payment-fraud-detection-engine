import json

from simulator import TrafficGenerator


def test_normal_payments_have_required_fields():
    gen = TrafficGenerator(seed=1, fraud_every=0)
    for _ in range(200):
        p = gen.normal_payment()
        data = json.loads(p.to_json())
        assert data["txId"].startswith("tx-")
        assert data["type"] in {"CARD", "UPI", "TRANSFER"}
        assert data["amount"] > 0
        if data["type"] == "TRANSFER":
            assert data["toAccount"]
        else:
            assert data["merchantId"]


def test_device_ring_shares_one_device_across_many_accounts():
    ring = TrafficGenerator(seed=2).device_ring()
    assert len(ring) >= 6
    assert len({p.deviceId for p in ring}) == 1
    assert len({p.fromAccount for p in ring}) == len(ring)


def test_mule_chain_forwards_money_hop_by_hop():
    chain = TrafficGenerator(seed=3).mule_chain()
    assert len(chain) >= 3
    for prev, nxt in zip(chain, chain[1:]):
        assert prev.toAccount == nxt.fromAccount
        assert nxt.amount < prev.amount
        assert nxt.amount / prev.amount >= 0.9


def test_card_testing_is_a_burst_of_tiny_payments():
    burst = TrafficGenerator(seed=4).card_testing()
    assert len(burst) >= 11
    assert len({p.fromAccount for p in burst}) == 1
    assert all(p.amount < 5 for p in burst)


def test_account_takeover_ends_with_large_transfer_from_new_device():
    ato = TrafficGenerator(seed=5).account_takeover()
    assert len({p.deviceId for p in ato}) == 3
    assert ato[-1].type == "TRANSFER"
    assert ato[-1].amount >= 2500


def test_generator_mixes_in_fraud():
    gen = TrafficGenerator(seed=6, fraud_every=10)
    scenarios = {next(gen).scenario for _ in range(400)}
    assert "normal" in scenarios
    assert len(scenarios) >= 3
