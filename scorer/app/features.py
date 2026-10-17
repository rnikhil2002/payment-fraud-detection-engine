"""Feature definitions shared by training and serving.

The names must match what the Spring Boot engine sends (MlScoringClient.features).
"""

FEATURES = [
    "amount",
    "hour_of_day",
    "is_transfer",
    "new_device",
    "accounts_on_device",
    "devices_for_account_24h",
    "tx_last_10m",
    "device_ring_size",
    "flagged_neighbors",
    "mule_chain_depth",
    "pass_through_ratio",
]

DEFAULTS = {
    "amount": 0.0,
    "hour_of_day": 12,
    "is_transfer": 0,
    "new_device": 0,
    "accounts_on_device": 1,
    "devices_for_account_24h": 1,
    "tx_last_10m": 1,
    "device_ring_size": 0,
    "flagged_neighbors": 0,
    "mule_chain_depth": 0,
    "pass_through_ratio": 0.0,
}


def to_row(features: dict) -> list[float]:
    """Order a feature dict into a model row, filling anything missing with a safe default."""
    row = []
    for name in FEATURES:
        value = features.get(name, DEFAULTS[name])
        try:
            row.append(float(value))
        except (TypeError, ValueError):
            row.append(float(DEFAULTS[name]))
    return row
