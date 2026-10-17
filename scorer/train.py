"""Train the fraud risk model and save it to models/.

    python train.py --samples 40000
"""

import argparse
import json

from app.model import save, train


def main() -> None:
    parser = argparse.ArgumentParser(description="Train the fraud risk model")
    parser.add_argument("--samples", type=int, default=40_000)
    parser.add_argument("--seed", type=int, default=7)
    args = parser.parse_args()

    model, metrics = train(n_samples=args.samples, seed=args.seed)
    version = save(model, metrics)
    print(json.dumps({"version": version, **metrics}, indent=2))


if __name__ == "__main__":
    main()
