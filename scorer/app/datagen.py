"""Synthetic labelled data for training the risk model.

Real fraud labels are hard to come by, so we simulate the same patterns the transaction
simulator produces: normal customers, device-sharing rings, money mule chains, card
testing bursts and account takeovers. Labels are noisy on purpose so the model has to
learn the overlap instead of memorising thresholds.
"""

from __future__ import annotations

import numpy as np
import pandas as pd

from .features import FEATURES


def _normal(rng: np.random.Generator, n: int) -> pd.DataFrame:
    return pd.DataFrame({
        "amount": rng.lognormal(mean=3.6, sigma=1.1, size=n).round(2),
        "hour_of_day": rng.choice(24, size=n, p=_daytime_hours()),
        "is_transfer": rng.binomial(1, 0.2, n),
        "new_device": rng.binomial(1, 0.03, n),
        "accounts_on_device": rng.choice([1, 1, 1, 1, 2, 2, 3], size=n),
        "devices_for_account_24h": rng.choice([1, 1, 1, 2, 2, 3], size=n),
        "tx_last_10m": rng.poisson(1.2, n) + 1,
        "device_ring_size": rng.choice([0, 0, 0, 1, 1, 2], size=n),
        "flagged_neighbors": rng.binomial(1, 0.01, n),
        "mule_chain_depth": rng.choice([0, 0, 0, 0, 1], size=n),
        "pass_through_ratio": rng.beta(1.2, 6, n).round(3),
        "label": 0,
    })


def _device_ring(rng: np.random.Generator, n: int) -> pd.DataFrame:
    accounts = rng.integers(5, 14, n)
    return pd.DataFrame({
        "amount": rng.lognormal(mean=5.5, sigma=0.8, size=n).round(2),
        "hour_of_day": rng.integers(0, 24, n),
        "is_transfer": rng.binomial(1, 0.3, n),
        "new_device": rng.binomial(1, 0.6, n),
        "accounts_on_device": accounts,
        "devices_for_account_24h": rng.choice([1, 2, 3], size=n),
        "tx_last_10m": rng.poisson(3, n) + 1,
        "device_ring_size": accounts + rng.integers(0, 6, n),
        "flagged_neighbors": rng.poisson(0.8, n),
        "mule_chain_depth": rng.choice([0, 0, 1], size=n),
        "pass_through_ratio": rng.beta(2, 4, n).round(3),
        "label": 1,
    })


def _mule_chain(rng: np.random.Generator, n: int) -> pd.DataFrame:
    return pd.DataFrame({
        "amount": rng.uniform(1500, 9500, n).round(2),
        "hour_of_day": rng.integers(0, 24, n),
        "is_transfer": 1,
        "new_device": rng.binomial(1, 0.4, n),
        "accounts_on_device": rng.choice([1, 1, 2, 3], size=n),
        "devices_for_account_24h": rng.choice([1, 1, 2], size=n),
        "tx_last_10m": rng.poisson(1.5, n) + 1,
        "device_ring_size": rng.choice([0, 1, 2, 4], size=n),
        "flagged_neighbors": rng.binomial(1, 0.15, n),
        "mule_chain_depth": rng.integers(1, 5, n),
        "pass_through_ratio": rng.uniform(0.75, 1.0, n).round(3),
        "label": 1,
    })


def _card_testing(rng: np.random.Generator, n: int) -> pd.DataFrame:
    return pd.DataFrame({
        "amount": rng.uniform(0.5, 9.99, n).round(2),
        "hour_of_day": rng.integers(0, 24, n),
        "is_transfer": 0,
        "new_device": rng.binomial(1, 0.5, n),
        "accounts_on_device": rng.choice([1, 2, 4], size=n),
        "devices_for_account_24h": rng.choice([1, 2], size=n),
        "tx_last_10m": rng.integers(8, 30, n),
        "device_ring_size": rng.choice([0, 1, 3], size=n),
        "flagged_neighbors": rng.binomial(1, 0.1, n),
        "mule_chain_depth": 0,
        "pass_through_ratio": 0.0,
        "label": 1,
    })


def _account_takeover(rng: np.random.Generator, n: int) -> pd.DataFrame:
    return pd.DataFrame({
        "amount": rng.uniform(1200, 8000, n).round(2),
        "hour_of_day": rng.choice([0, 1, 2, 3, 4, 5, 23], size=n),
        "is_transfer": rng.binomial(1, 0.6, n),
        "new_device": 1,
        "accounts_on_device": rng.choice([1, 1, 2], size=n),
        "devices_for_account_24h": rng.integers(3, 7, n),
        "tx_last_10m": rng.poisson(2, n) + 1,
        "device_ring_size": rng.choice([0, 1], size=n),
        "flagged_neighbors": 0,
        "mule_chain_depth": rng.choice([0, 1], size=n),
        "pass_through_ratio": rng.beta(1, 5, n).round(3),
        "label": 1,
    })


def _daytime_hours() -> np.ndarray:
    weights = np.array([1, 1, 1, 1, 1, 2, 3, 5, 7, 8, 8, 9, 10, 9, 8, 8, 8, 9, 10, 9, 7, 5, 3, 2],
                       dtype=float)
    return weights / weights.sum()


def generate(n: int = 40_000, fraud_rate: float = 0.06, seed: int = 7) -> pd.DataFrame:
    """Return a shuffled dataframe with FEATURES plus a binary `label` column."""
    rng = np.random.default_rng(seed)
    n_fraud = int(n * fraud_rate)
    n_normal = n - n_fraud
    per_pattern = n_fraud // 4

    frames = [
        _normal(rng, n_normal),
        _device_ring(rng, per_pattern),
        _mule_chain(rng, per_pattern),
        _card_testing(rng, per_pattern),
        _account_takeover(rng, n_fraud - 3 * per_pattern),
    ]
    df = pd.concat(frames, ignore_index=True)

    # Label noise: a few "fraud" rows that look clean and a few clean rows that look odd.
    flip = rng.random(len(df)) < 0.01
    df.loc[flip, "label"] = 1 - df.loc[flip, "label"]

    df = df.sample(frac=1.0, random_state=seed).reset_index(drop=True)
    return df[FEATURES + ["label"]]
