"""Training, saving and loading the fraud risk model."""

from __future__ import annotations

import json
import logging
from dataclasses import dataclass
from datetime import datetime, timezone
from pathlib import Path

import joblib
import numpy as np
from sklearn.ensemble import HistGradientBoostingClassifier
from sklearn.metrics import average_precision_score, precision_score, recall_score, roc_auc_score
from sklearn.model_selection import train_test_split

from .datagen import generate
from .features import FEATURES, to_row

log = logging.getLogger(__name__)

MODEL_DIR = Path(__file__).resolve().parent.parent / "models"
MODEL_PATH = MODEL_DIR / "fraud_model.joblib"
METRICS_PATH = MODEL_DIR / "metrics.json"


@dataclass
class LoadedModel:
    estimator: HistGradientBoostingClassifier
    version: str
    metrics: dict

    def predict_proba(self, features: dict) -> float:
        row = np.array([to_row(features)])
        return float(self.estimator.predict_proba(row)[0, 1])


def train(n_samples: int = 40_000, seed: int = 7) -> tuple[HistGradientBoostingClassifier, dict]:
    df = generate(n=n_samples, seed=seed)
    x = df[FEATURES].to_numpy(dtype=float)
    y = df["label"].to_numpy()
    x_train, x_test, y_train, y_test = train_test_split(
        x, y, test_size=0.2, stratify=y, random_state=seed)

    model = HistGradientBoostingClassifier(
        max_iter=250, learning_rate=0.08, max_leaf_nodes=31, l2_regularization=1.0,
        class_weight="balanced", random_state=seed)
    model.fit(x_train, y_train)

    proba = model.predict_proba(x_test)[:, 1]
    pred = (proba >= 0.5).astype(int)
    metrics = {
        "roc_auc": round(float(roc_auc_score(y_test, proba)), 4),
        "average_precision": round(float(average_precision_score(y_test, proba)), 4),
        "precision_at_0_5": round(float(precision_score(y_test, pred)), 4),
        "recall_at_0_5": round(float(recall_score(y_test, pred)), 4),
        "train_rows": int(len(x_train)),
        "test_rows": int(len(x_test)),
        "fraud_rate": round(float(y.mean()), 4),
    }
    return model, metrics


def save(model: HistGradientBoostingClassifier, metrics: dict) -> str:
    MODEL_DIR.mkdir(parents=True, exist_ok=True)
    version = datetime.now(timezone.utc).strftime("hgb-%Y%m%d%H%M%S")
    joblib.dump({"model": model, "version": version, "features": FEATURES}, MODEL_PATH)
    METRICS_PATH.write_text(json.dumps({"version": version, **metrics}, indent=2))
    return version


def load_or_train() -> LoadedModel:
    if MODEL_PATH.exists():
        bundle = joblib.load(MODEL_PATH)
        if bundle.get("features") == FEATURES:
            metrics = json.loads(METRICS_PATH.read_text()) if METRICS_PATH.exists() else {}
            log.info("Loaded model %s", bundle["version"])
            return LoadedModel(bundle["model"], bundle["version"], metrics)
        log.warning("Saved model has a different feature list, retraining")

    log.info("No saved model found, training a new one")
    model, metrics = train()
    version = save(model, metrics)
    return LoadedModel(model, version, {"version": version, **metrics})
