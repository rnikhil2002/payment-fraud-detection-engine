from fastapi.testclient import TestClient

from app.datagen import generate
from app.features import FEATURES, to_row
from app.main import app
from app.model import train


def test_generated_data_has_every_feature_and_both_labels():
    df = generate(n=2000, seed=1)
    assert list(df.columns) == FEATURES + ["label"]
    assert set(df["label"].unique()) == {0, 1}


def test_to_row_fills_missing_and_bad_values():
    row = to_row({"amount": "12.5", "tx_last_10m": "oops"})
    assert len(row) == len(FEATURES)
    assert row[FEATURES.index("amount")] == 12.5
    assert row[FEATURES.index("tx_last_10m")] == 1.0


def test_model_separates_fraud_from_normal():
    _, metrics = train(n_samples=8000, seed=3)
    assert metrics["roc_auc"] > 0.9


def test_score_endpoint_ranks_a_mule_above_a_coffee():
    with TestClient(app) as client:
        assert client.get("/health").json()["status"] == "UP"

        coffee = client.post("/score", json={"features": {
            "amount": 4.75, "hour_of_day": 9, "is_transfer": 0, "new_device": 0,
            "accounts_on_device": 1, "devices_for_account_24h": 1, "tx_last_10m": 1,
            "device_ring_size": 0, "flagged_neighbors": 0, "mule_chain_depth": 0,
            "pass_through_ratio": 0.0}}).json()
        mule = client.post("/score", json={"features": {
            "amount": 4800, "hour_of_day": 3, "is_transfer": 1, "new_device": 1,
            "accounts_on_device": 2, "devices_for_account_24h": 2, "tx_last_10m": 2,
            "device_ring_size": 2, "flagged_neighbors": 1, "mule_chain_depth": 3,
            "pass_through_ratio": 0.97}}).json()

        assert 0.0 <= coffee["probability"] <= 1.0
        assert mule["probability"] > 0.8
        assert coffee["probability"] < 0.2
        assert mule["model_version"] == coffee["model_version"]


def test_model_endpoint_reports_metrics():
    with TestClient(app) as client:
        info = client.get("/model").json()
        assert info["features"] == FEATURES
        assert "roc_auc" in info["metrics"]
