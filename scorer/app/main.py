"""FastAPI service that returns a fraud probability for one payment."""

from __future__ import annotations

import logging
from contextlib import asynccontextmanager

from fastapi import FastAPI
from pydantic import BaseModel, Field

from .features import FEATURES
from .model import LoadedModel, load_or_train

logging.basicConfig(level=logging.INFO, format="%(asctime)s %(levelname)s %(name)s - %(message)s")

state: dict[str, LoadedModel] = {}


@asynccontextmanager
async def lifespan(_: FastAPI):
    state["model"] = load_or_train()
    yield
    state.clear()


app = FastAPI(title="Fraud risk scorer", version="1.0.0", lifespan=lifespan)


class ScoreRequest(BaseModel):
    features: dict[str, float] = Field(default_factory=dict)


class ScoreResponse(BaseModel):
    probability: float
    model_version: str


@app.get("/health")
def health() -> dict:
    model = state.get("model")
    return {"status": "UP" if model else "STARTING", "model_version": model.version if model else None}


@app.get("/model")
def model_info() -> dict:
    model = state["model"]
    return {"version": model.version, "features": FEATURES, "metrics": model.metrics}


@app.post("/score", response_model=ScoreResponse)
def score(request: ScoreRequest) -> ScoreResponse:
    model = state["model"]
    probability = model.predict_proba(request.features)
    return ScoreResponse(probability=round(probability, 4), model_version=model.version)
