"""
Training pipeline for GRU and LSTM models in LLMSysMon.
Trains models with leak-free chronological splitting, MSE loss, Adam optimizer,
and saves model weights along with complete metadata for reproducible inference.
"""

import argparse
import os
import sys
from typing import Dict, List, Tuple

import torch
import torch.nn as nn
from torch.utils.data import DataLoader, TensorDataset

from ml.models.gru import GRUModel
from ml.models.lstm import LSTMModel
from ml.preprocessing.dataset import (
    FEATURES,
    prepare_dataset,
    save_scaler,
    validate_data,
)


def train_model(
    model: nn.Module,
    train_loader: DataLoader,
    val_loader: DataLoader,
    epochs: int,
    learning_rate: float,
    device: torch.device,
) -> Tuple[List[float], List[float]]:
    """Trains the model and returns training and validation loss history."""
    criterion = nn.MSELoss()
    optimizer = torch.optim.Adam(model.parameters(), lr=learning_rate)

    model.to(device)

    train_losses: List[float] = []
    val_losses: List[float] = []

    for epoch in range(epochs):
        model.train()
        total_train_loss = 0.0

        for x_batch, y_batch in train_loader:
            x_batch = x_batch.to(device)
            y_batch = y_batch.to(device)

            optimizer.zero_grad()
            predictions = model(x_batch)
            loss = criterion(predictions, y_batch)
            loss.backward()
            optimizer.step()

            total_train_loss += loss.item()

        avg_train_loss = total_train_loss / max(len(train_loader), 1)
        train_losses.append(avg_train_loss)

        # Validation loss on the chronological test/val set
        model.eval()
        total_val_loss = 0.0
        with torch.no_grad():
            for x_val, y_val in val_loader:
                x_val = x_val.to(device)
                y_val = y_val.to(device)
                val_preds = model(x_val)
                val_loss = criterion(val_preds, y_val)
                total_val_loss += val_loss.item()

        avg_val_loss = total_val_loss / max(len(val_loader), 1)
        val_losses.append(avg_val_loss)

        if (epoch + 1) % 5 == 0 or epoch == 0 or epoch == epochs - 1:
            print(
                f"Epoch {epoch + 1:03d}/{epochs:03d} | "
                f"Train Loss (MSE): {avg_train_loss:.6f} | "
                f"Val Loss (MSE): {avg_val_loss:.6f}"
            )

    return train_losses, val_losses


def main():
    parser = argparse.ArgumentParser(description="Train GRU or LSTM for LLMSysMon")
    parser.add_argument(
        "--model",
        choices=["gru", "lstm"],
        required=True,
        help="Model architecture to train",
    )
    parser.add_argument(
        "--input",
        default="ml/data/system_metrics.csv",
        help="Path to telemetry CSV file",
    )
    parser.add_argument("--epochs", type=int, default=30, help="Number of training epochs")
    parser.add_argument("--batch_size", type=int, default=64, help="Batch size")
    parser.add_argument("--lr", type=float, default=0.001, help="Learning rate")
    parser.add_argument(
        "--sequence_length",
        type=int,
        default=30,
        help="Historical sequence length (timesteps)",
    )
    parser.add_argument(
        "--prediction_horizon",
        type=int,
        default=5,
        help="Prediction horizon into future (timesteps)",
    )
    parser.add_argument(
        "--split_ratio",
        type=float,
        default=0.8,
        help="Chronological train/test split ratio",
    )
    parser.add_argument(
        "--save_dir",
        default="ml/saved_models",
        help="Directory to save model checkpoint and scaler",
    )

    args = parser.parse_args()

    print("=" * 60)
    print(f"LLMSysMon Model Training: {args.model.upper()}")
    print("=" * 60)

    try:
        x_train, y_train, x_test, y_test, scaler, train_df, test_df = prepare_dataset(
            path=args.input,
            sequence_length=args.sequence_length,
            prediction_horizon=args.prediction_horizon,
            split_ratio=args.split_ratio,
        )
    except Exception as e:
        print(f"Error during dataset preparation: {e}", file=sys.stderr)
        sys.exit(1)

    print(f"Raw train samples: {len(train_df)}")
    print(f"Raw test samples:  {len(test_df)}")
    print(f"Train sequences:   {x_train.shape[0]}")
    print(f"Test sequences:    {x_test.shape[0]}")

    if len(x_train) == 0:
        print("Error: No training sequences generated. Insufficient data.", file=sys.stderr)
        sys.exit(1)

    train_dataset = TensorDataset(torch.from_numpy(x_train), torch.from_numpy(y_train))
    val_dataset = TensorDataset(torch.from_numpy(x_test), torch.from_numpy(y_test))

    train_loader = DataLoader(
        train_dataset, batch_size=args.batch_size, shuffle=False
    )
    val_loader = DataLoader(
        val_dataset, batch_size=args.batch_size, shuffle=False
    )

    device = torch.device("cuda" if torch.cuda.is_available() else "cpu")
    print(f"Hardware Accelerator: {device} ({torch.cuda.get_device_name(0) if torch.cuda.is_available() else 'CPU'})")

    input_size = len(FEATURES)
    hidden_size = 64
    num_layers = 2
    output_size = len(FEATURES)

    if args.model == "gru":
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

    print(f"Architecture: {model.__class__.__name__} (layers={num_layers}, hidden={hidden_size})")
    print(f"Hyperparameters: epochs={args.epochs}, lr={args.lr}, batch_size={args.batch_size}")
    print("-" * 60)

    train_losses, val_losses = train_model(
        model=model,
        train_loader=train_loader,
        val_loader=val_loader,
        epochs=args.epochs,
        learning_rate=args.lr,
        device=device,
    )

    os.makedirs(args.save_dir, exist_ok=True)
    output_path = os.path.join(args.save_dir, f"{args.model}.pt")
    scaler_path = os.path.join(args.save_dir, "scaler.json")

    # Save scaler metadata
    save_scaler(scaler, scaler_path)

    # Save comprehensive checkpoint for inference and evaluation
    checkpoint = {
        "model_state_dict": model.state_dict(),
        "model_type": args.model,
        "input_size": input_size,
        "hidden_size": hidden_size,
        "num_layers": num_layers,
        "output_size": output_size,
        "sequence_length": args.sequence_length,
        "prediction_horizon": args.prediction_horizon,
        "features": FEATURES,
        "train_loss": train_losses[-1],
        "val_loss": val_losses[-1],
        "train_losses": train_losses,
        "val_losses": val_losses,
        "epochs": args.epochs,
        "learning_rate": args.lr,
        "batch_size": args.batch_size,
        "data_min": scaler.data_min_.tolist(),
        "data_max": scaler.data_max_.tolist(),
    }

    torch.save(checkpoint, output_path)

    print("-" * 60)
    print(f"Model saved to:  {output_path}")
    print(f"Scaler saved to: {scaler_path}")
    print(f"Final Train Loss (MSE): {train_losses[-1]:.6f}")
    print(f"Final Val Loss (MSE):   {val_losses[-1]:.6f}")
    print("=" * 60)


if __name__ == "__main__":
    main()
