"""
Real-time inference module for LLMSysMon.
Loads the trained/selected recurrent model (GRU or LSTM) and scaler,
constructs the recent 30-second context window from telemetry,
and forecasts system resource utilization ~5 seconds into the future.
Outputs either human-readable text or structured JSON for Java integration.
"""

import argparse
import json
import os
import sys
from typing import Any, Dict, Optional, Tuple

import numpy as np
import pandas as pd
import torch

from ml.models.gru import GRUModel
from ml.models.lstm import LSTMModel
from ml.preprocessing.dataset import (
    FEATURES,
    load_data,
    load_scaler,
)


def resolve_model_path(
    model_name: Optional[str] = None, models_dir: str = "ml/saved_models"
) -> Tuple[str, str]:
    """
    Resolves which model checkpoint to load.
    If model_name is not explicitly passed, attempts to read selected_model.json.
    """
    if model_name:
        path = os.path.join(models_dir, f"{model_name.lower()}.pt")
        if os.path.exists(path):
            return path, model_name.lower()
        raise FileNotFoundError(f"Requested model not found: {path}")

    selected_json = os.path.join(models_dir, "selected_model.json")
    if os.path.exists(selected_json):
        try:
            with open(selected_json, "r", encoding="utf-8") as f:
                info = json.load(f)
            chosen = info.get("selected_model", "gru")
            path = os.path.join(models_dir, f"{chosen}.pt")
            if os.path.exists(path):
                return path, chosen
        except Exception:
            pass

    # Fallbacks
    for fallback in ["gru", "lstm"]:
        path = os.path.join(models_dir, f"{fallback}.pt")
        if os.path.exists(path):
            return path, fallback

    raise FileNotFoundError(
        f"No trained models found in {models_dir}. Please run training first: "
        f"python -m ml.training.train --model gru"
    )


def load_model_for_inference(
    checkpoint_path: str, device: torch.device
) -> Tuple[torch.nn.Module, Dict[str, Any]]:
    """Loads a PyTorch model into evaluation mode."""
    checkpoint = torch.load(checkpoint_path, map_location=device, weights_only=False)
    model_type = checkpoint.get("model_type", "gru")
    input_size = checkpoint.get("input_size", 3)
    hidden_size = checkpoint.get("hidden_size", 64)
    num_layers = checkpoint.get("num_layers", 2)
    output_size = checkpoint.get("output_size", 3)

    if model_type == "gru":
        model = GRUModel(
            input_size=input_size,
            hidden_size=hidden_size,
            num_layers=num_layers,
            output_size=output_size,
        )
    else:
        model = LSTMModel(
            input_size=input_size,
            hidden_size=hidden_size,
            num_layers=num_layers,
            output_size=output_size,
        )

    model.load_state_dict(checkpoint["model_state_dict"])
    model.to(device)
    model.eval()

    return model, checkpoint


def predict_next(
    input_path: str,
    model_name: Optional[str] = None,
    models_dir: str = "ml/saved_models",
    scaler_path: Optional[str] = None,
) -> Dict[str, Any]:
    """
    Executes end-to-end inference:
      1. Resolves and loads selected model.
      2. Loads scaler configuration.
      3. Reads and validates the most recent telemetry samples.
      4. Builds normalized input sequence (last 30 samples).
      5. Runs forward pass.
      6. Inverts scaling to real percentage units.
    """
    device = torch.device("cuda" if torch.cuda.is_available() else "cpu")

    checkpoint_path, resolved_name = resolve_model_path(model_name, models_dir)
    model, checkpoint = load_model_for_inference(checkpoint_path, device)

    seq_len = checkpoint.get("sequence_length", 30)
    pred_horizon = checkpoint.get("prediction_horizon", 5)

    # Load scaler: either from dedicated scaler.json or reconstructed from checkpoint
    if scaler_path is None:
        scaler_path = os.path.join(models_dir, "scaler.json")

    if os.path.exists(scaler_path):
        scaler = load_scaler(scaler_path)
    elif "data_min" in checkpoint and "data_max" in checkpoint:
        from sklearn.preprocessing import MinMaxScaler
        scaler = MinMaxScaler()
        scaler.data_min_ = np.array(checkpoint["data_min"], dtype=np.float32)
        scaler.data_max_ = np.array(checkpoint["data_max"], dtype=np.float32)
        scaler.data_range_ = scaler.data_max_ - scaler.data_min_
        scaler.data_range_[scaler.data_range_ == 0.0] = 1.0
        scaler.scale_ = 1.0 / scaler.data_range_
        scaler.min_ = -scaler.data_min_ * scaler.scale_
        scaler.n_features_in_ = len(FEATURES)
    else:
        raise ValueError("No scaler metadata available in checkpoint or scaler.json")

    # Load telemetry data
    df = load_data(input_path)
    if len(df) < seq_len:
        raise ValueError(
            f"Need at least {seq_len} telemetry samples for inference, but got {len(df)}."
        )

    recent_df = df.iloc[-seq_len:].copy()
    raw_values = recent_df[FEATURES].to_numpy(dtype=np.float32)

    # Most recent actual system metrics
    latest_actual = raw_values[-1]
    latest_timestamp = str(recent_df["timestamp"].iloc[-1])

    # Transform using training scaler
    scaled_seq = scaler.transform(raw_values)
    input_tensor = torch.from_numpy(scaled_seq).unsqueeze(0).to(device)  # shape: (1, 30, 3)

    with torch.no_grad():
        scaled_pred = model(input_tensor).cpu().numpy()

    # Invert scaling to physical percentage units
    real_pred = scaler.inverse_transform(scaled_pred)[0]
    real_pred = np.clip(real_pred, 0.0, 100.0)

    return {
        "status": "success",
        "model": resolved_name,
        "prediction_horizon_seconds": pred_horizon,
        "timestamp": latest_timestamp,
        "current": {
            "cpu": round(float(latest_actual[0]), 2),
            "memory": round(float(latest_actual[1]), 2),
            "swap": round(float(latest_actual[2]), 2),
        },
        "predicted": {
            "cpu": round(float(real_pred[0]), 2),
            "memory": round(float(real_pred[1]), 2),
            "swap": round(float(real_pred[2]), 2),
        },
    }


def main():
    parser = argparse.ArgumentParser(description="Run inference on recent telemetry")
    parser.add_argument(
        "--input",
        default="ml/data/system_metrics.csv",
        help="Path to telemetry CSV",
    )
    parser.add_argument(
        "--model",
        choices=["gru", "lstm"],
        default=None,
        help="Specific model to run (defaults to best selected model)",
    )
    parser.add_argument(
        "--models-dir",
        default="ml/saved_models",
        help="Directory containing trained models",
    )
    parser.add_argument(
        "--json",
        action="store_true",
        help="Output results in machine-readable JSON format",
    )

    args = parser.parse_args()

    try:
        result = predict_next(
            input_path=args.input,
            model_name=args.model,
            models_dir=args.models_dir,
        )

        if args.json:
            print(json.dumps(result, indent=2))
        else:
            print(f"Model used: {result['model'].upper()} (Horizon: ~{result['prediction_horizon_seconds']}s)")
            print(f"Telemetry timestamp: {result['timestamp']}")
            print("-" * 35)
            print("Current:")
            print(f"  CPU:  {result['current']['cpu']:>5.1f}%")
            print(f"  RAM:  {result['current']['memory']:>5.1f}%")
            print(f"  Swap: {result['current']['swap']:>5.1f}%")
            print(f"\nPredicted (~{result['prediction_horizon_seconds']} seconds):")
            print(f"  CPU:  {result['predicted']['cpu']:>5.1f}%")
            print(f"  RAM:  {result['predicted']['memory']:>5.1f}%")
            print(f"  Swap: {result['predicted']['swap']:>5.1f}%")

    except Exception as e:
        if args.json:
            err_payload = {
                "status": "error",
                "message": str(e),
            }
            print(json.dumps(err_payload, indent=2))
        else:
            print(f"Inference Error: {e}", file=sys.stderr)
        sys.exit(1)


if __name__ == "__main__":
    main()
