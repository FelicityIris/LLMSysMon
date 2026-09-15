"""
Time-series preprocessing pipeline for LLMSysMon.
Handles data loading, chronological splitting, leak-free scaling,
and sliding-window sequence generation for GRU/LSTM models.
"""

import argparse
import json
import os
from typing import Dict, List, Optional, Tuple

import numpy as np
import pandas as pd
from sklearn.preprocessing import MinMaxScaler

FEATURES: List[str] = [
    "cpu_usage",
    "memory_usage",
    "swap_usage",
    "gpu_usage",
]

MIN_REQUIRED_SAMPLES: int = 45  # e.g., 30 sequence + 5 horizon + 10 buffer


def load_data(path: str) -> pd.DataFrame:
    """
    Loads telemetry CSV, cleans timestamps, sorts chronologically,
    and ensures numerical columns are valid.
    """
    if not os.path.exists(path):
        raise FileNotFoundError(f"Data file not found at: {path}")

    df = pd.read_csv(path)
    if df.empty:
        raise ValueError(f"Data file at {path} is empty.")

    if "gpu_usage" not in df.columns:
        df["gpu_usage"] = 0.0

    missing_cols = [col for col in ["timestamp"] + FEATURES if col not in df.columns]
    if missing_cols:
        raise ValueError(f"Missing expected columns in {path}: {missing_cols}")

    df["timestamp"] = pd.to_datetime(df["timestamp"], format="ISO8601", errors="coerce", utc=True)
    df = df.dropna(subset=["timestamp"]).reset_index(drop=True)
    df = df.sort_values("timestamp").reset_index(drop=True)
    df = df.drop_duplicates(subset=["timestamp"]).reset_index(drop=True)

    df[FEATURES] = df[FEATURES].apply(pd.to_numeric, errors="coerce").fillna(0.0)

    return df


def validate_data(df: pd.DataFrame, min_samples: int = MIN_REQUIRED_SAMPLES) -> None:
    """Validates that sufficient samples exist for time-series modeling."""
    if len(df) < min_samples:
        raise ValueError(
            f"Insufficient data for modeling: found {len(df)} samples, "
            f"but at least {min_samples} are required. "
            f"Please collect more telemetry data using LLMSysMon."
        )


def create_sequences(
    data: np.ndarray, sequence_length: int = 30, prediction_horizon: int = 5
) -> Tuple[np.ndarray, np.ndarray]:
    """
    Creates sliding-window sequences for training:
      X: shape (num_samples, sequence_length, num_features)
      Y: shape (num_samples, num_features) at (t + sequence_length + prediction_horizon - 1)

    One sample represents: given 30 seconds of historical observations,
    predict the system state 5 seconds into the future.
    """
    x: List[np.ndarray] = []
    y: List[np.ndarray] = []

    end = len(data) - sequence_length - prediction_horizon + 1

    for i in range(end):
        seq_end = i + sequence_length
        target_idx = seq_end + prediction_horizon - 1

        x.append(data[i:seq_end])
        y.append(data[target_idx])

    return np.array(x, dtype=np.float32), np.array(y, dtype=np.float32)


def save_scaler(scaler: MinMaxScaler, filepath: str) -> None:
    """Saves scaler min/max metadata to a JSON file."""
    os.makedirs(os.path.dirname(filepath), exist_ok=True)
    metadata = {
        "features": FEATURES,
        "data_min": scaler.data_min_.tolist(),
        "data_max": scaler.data_max_.tolist(),
        "data_range": scaler.data_range_.tolist(),
        "scale": scaler.scale_.tolist(),
        "min": scaler.min_.tolist(),
    }
    with open(filepath, "w", encoding="utf-8") as f:
        json.dump(metadata, f, indent=2)


def load_scaler(filepath: str) -> MinMaxScaler:
    """Loads a MinMaxScaler reconstructed from JSON metadata."""
    if not os.path.exists(filepath):
        raise FileNotFoundError(f"Scaler file not found at: {filepath}")

    with open(filepath, "r", encoding="utf-8") as f:
        metadata = json.load(f)

    scaler = MinMaxScaler()
    scaler.data_min_ = np.array(metadata["data_min"], dtype=np.float32)
    scaler.data_max_ = np.array(metadata["data_max"], dtype=np.float32)
    scaler.data_range_ = np.array(metadata["data_range"], dtype=np.float32)
    scaler.scale_ = np.array(metadata["scale"], dtype=np.float32)
    scaler.min_ = np.array(metadata["min"], dtype=np.float32)
    scaler.n_features_in_ = len(metadata["features"])

    return scaler


def prepare_dataset(
    path: str,
    sequence_length: int = 30,
    prediction_horizon: int = 5,
    split_ratio: float = 0.8,
) -> Tuple[
    np.ndarray,
    np.ndarray,
    np.ndarray,
    np.ndarray,
    MinMaxScaler,
    pd.DataFrame,
    pd.DataFrame,
]:
    """
    Complete leak-free pipeline:
      1. Load and clean chronologically ordered CSV.
      2. Split chronologically into Train (e.g. 80%) and Test (e.g. 20%).
         DO NOT randomly shuffle.
      3. Fit scaler ONLY on the training split.
      4. Transform train and test using the fitted scaler.
      5. Create training sequences strictly from scaled_train.
      6. Create test sequences using preceding training history as context
         so that test targets evaluate the test set seamlessly without leakage.
    """
    df = load_data(path)
    min_needed = sequence_length + prediction_horizon + 10
    validate_data(df, min_needed)

    num_samples = len(df)
    split_idx = int(num_samples * split_ratio)

    # Ensure train split has at least enough samples for sequences
    if split_idx < sequence_length + prediction_horizon:
        split_idx = sequence_length + prediction_horizon + 5

    train_df = df.iloc[:split_idx].copy()
    test_df = df.iloc[split_idx:].copy()

    train_values = train_df[FEATURES].to_numpy(dtype=np.float32)
    test_values = test_df[FEATURES].to_numpy(dtype=np.float32)

    # Fit scaler ONLY on the training portion (NO DATA LEAKAGE)
    scaler = MinMaxScaler(feature_range=(0, 1))
    scaled_train = scaler.fit_transform(train_values)
    scaled_test = scaler.transform(test_values)

    # Training sequences strictly from scaled_train
    x_train, y_train = create_sequences(
        scaled_train, sequence_length, prediction_horizon
    )

    # Test sequences:
    # To test predictions on the test set, we provide historical context
    # from the end of the training set immediately preceding the test window.
    context_needed = sequence_length + prediction_horizon - 1
    if len(scaled_train) >= context_needed:
        context = scaled_train[-context_needed:]
        test_with_context = np.vstack([context, scaled_test])
    else:
        test_with_context = scaled_test

    x_test, y_test = create_sequences(
        test_with_context, sequence_length, prediction_horizon
    )

    return x_train, y_train, x_test, y_test, scaler, train_df, test_df


def main():
    parser = argparse.ArgumentParser(
        description="Verify LLMSysMon dataset preprocessing"
    )
    parser.add_argument("--input", default="ml/data/system_metrics.csv")
    parser.add_argument("--sequence_length", type=int, default=30)
    parser.add_argument("--prediction_horizon", type=int, default=5)
    parser.add_argument("--split_ratio", type=float, default=0.8)

    args = parser.parse_args()

    x_train, y_train, x_test, y_test, scaler, train_df, test_df = prepare_dataset(
        args.input, args.sequence_length, args.prediction_horizon, args.split_ratio
    )

    print("=" * 60)
    print("LLMSysMon Preprocessing Pipeline (Leak-Free)")
    print("=" * 60)
    print(f"Total raw samples loaded: {len(train_df) + len(test_df)}")
    print(f"Train samples (raw):     {len(train_df)}")
    print(f"Test samples (raw):      {len(test_df)}")
    print(f"Features:                {FEATURES}")
    print(f"Sequence length:         {args.sequence_length} timesteps (~{args.sequence_length}s)")
    print(f"Prediction horizon:      {args.prediction_horizon} timesteps (~{args.prediction_horizon}s)")
    print(f"X_train shape:           {x_train.shape}")
    print(f"Y_train shape:           {y_train.shape}")
    print(f"X_test shape:            {x_test.shape}")
    print(f"Y_test shape:            {y_test.shape}")
    print(f"Scaler min:              {scaler.data_min_}")
    print(f"Scaler max:              {scaler.data_max_}")
    print("=" * 60)


if __name__ == "__main__":
    main()
