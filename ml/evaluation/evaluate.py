"""
Evaluation and model selection suite for LLMSysMon.
Evaluates GRU and LSTM models on unseen chronological test data,
calculates MAE and RMSE in real physical units (percentages),
saves evaluation metrics to JSON and CSV, generates visual plots,
and selects the best performing model.
"""

import argparse
import json
import os
import sys
from typing import Any, Dict, List, Optional, Tuple

import matplotlib
matplotlib.use("Agg")  # Non-interactive backend for headless execution
import matplotlib.pyplot as plt
import numpy as np
import pandas as pd
import torch

from ml.models.gru import GRUModel
from ml.models.lstm import LSTMModel
from ml.preprocessing.dataset import (
    FEATURES,
    prepare_dataset,
)


def load_model(checkpoint_path: str, device: torch.device) -> Tuple[torch.nn.Module, Dict[str, Any]]:
    """Loads a model from a checkpoint dictionary."""
    if not os.path.exists(checkpoint_path):
        raise FileNotFoundError(f"Checkpoint not found at: {checkpoint_path}")

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


def evaluate_model(
    model: torch.nn.Module,
    x_test: np.ndarray,
    y_test: np.ndarray,
    scaler,
    device: torch.device,
) -> Tuple[np.ndarray, np.ndarray, Dict[str, float]]:
    """
    Evaluates model on test sequences and computes MAE and RMSE
    in ORIGINAL percentage units (unscaled).
    """
    with torch.no_grad():
        x_tensor = torch.from_numpy(x_test).to(device)
        scaled_preds = model(x_tensor).cpu().numpy()

    # Invert scaling to obtain real percentages
    real_preds = scaler.inverse_transform(scaled_preds)
    real_actuals = scaler.inverse_transform(y_test)

    # Clip to physical system limits [0, 100]
    real_preds = np.clip(real_preds, 0.0, 100.0)

    # Feature indices: 0=CPU, 1=Memory, 2=Swap
    cpu_pred, cpu_act = real_preds[:, 0], real_actuals[:, 0]
    mem_pred, mem_act = real_preds[:, 1], real_actuals[:, 1]
    swap_pred, swap_act = real_preds[:, 2], real_actuals[:, 2]

    metrics = {
        "cpu_mae": float(np.mean(np.abs(cpu_pred - cpu_act))),
        "cpu_rmse": float(np.sqrt(np.mean((cpu_pred - cpu_act) ** 2))),
        "ram_mae": float(np.mean(np.abs(mem_pred - mem_act))),
        "ram_rmse": float(np.sqrt(np.mean((mem_pred - mem_act) ** 2))),
        "swap_mae": float(np.mean(np.abs(swap_pred - swap_act))),
        "swap_rmse": float(np.sqrt(np.mean((swap_pred - swap_act) ** 2))),
    }

    metrics["avg_mae"] = float(
        (metrics["cpu_mae"] + metrics["ram_mae"] + metrics["swap_mae"]) / 3.0
    )
    metrics["avg_rmse"] = float(
        (metrics["cpu_rmse"] + metrics["ram_rmse"] + metrics["swap_rmse"]) / 3.0
    )

    return real_preds, real_actuals, metrics


def generate_plots(
    results: Dict[str, Dict[str, Any]],
    real_actuals: np.ndarray,
    output_dir: str,
) -> None:
    """Generates comparison visualizations and saves them to disk."""
    os.makedirs(output_dir, exist_ok=True)
    time_steps = np.arange(len(real_actuals))

    metric_names = [("CPU Usage (%)", 0, "actual_vs_pred_cpu.png"),
                    ("Memory Usage (%)", 1, "actual_vs_pred_memory.png"),
                    ("Swap Usage (%)", 2, "actual_vs_pred_swap.png")]

    # 1. Actual vs Predicted plots for each metric
    for title, col_idx, filename in metric_names:
        plt.figure(figsize=(10, 4.5), dpi=150)
        plt.plot(time_steps, real_actuals[:, col_idx], label="Actual", color="#90CAF9", linewidth=2.0)

        colors = {"gru": "#FFB74D", "lstm": "#81C784"}
        for model_name, data in results.items():
            if "predictions" in data:
                preds = data["predictions"]
                plt.plot(
                    time_steps,
                    preds[:, col_idx],
                    label=f"Predicted ({model_name.upper()})",
                    color=colors.get(model_name.lower(), "#FF8A65"),
                    linestyle="--",
                    linewidth=1.8,
                )

        plt.title(f"Unseen Test Set: {title} (Horizon: ~5s)", fontsize=13, fontweight="bold")
        plt.xlabel("Test Sequence Index (Chronological)", fontsize=11)
        plt.ylabel("Usage (%)", fontsize=11)
        plt.grid(True, linestyle=":", alpha=0.6)
        plt.legend(frameon=True, facecolor="#F5F5F5")
        plt.tight_layout()

        save_path = os.path.join(output_dir, filename)
        plt.savefig(save_path)
        plt.close()
        print(f"Saved plot: {save_path}")

    # 2. Overall Model Comparison Bar Chart
    if len(results) >= 2:
        plt.figure(figsize=(9, 4.5), dpi=150)
        model_keys = list(results.keys())
        x = np.arange(3)
        width = 0.35

        metrics_cpu = [results[m]["metrics"]["cpu_rmse"] for m in model_keys]
        metrics_ram = [results[m]["metrics"]["ram_rmse"] for m in model_keys]
        metrics_swap = [results[m]["metrics"]["swap_rmse"] for m in model_keys]

        fig, ax = plt.subplots(figsize=(8, 4.5), dpi=150)
        ax.bar(x - width/2, [results[model_keys[0]]["metrics"]["cpu_rmse"],
                             results[model_keys[0]]["metrics"]["ram_rmse"],
                             results[model_keys[0]]["metrics"]["swap_rmse"]],
               width, label=f"{model_keys[0].upper()} RMSE", color="#FFB74D")
        ax.bar(x + width/2, [results[model_keys[1]]["metrics"]["cpu_rmse"],
                             results[model_keys[1]]["metrics"]["ram_rmse"],
                             results[model_keys[1]]["metrics"]["swap_rmse"]],
               width, label=f"{model_keys[1].upper()} RMSE", color="#81C784")

        ax.set_title("Model Error Comparison (Test Set RMSE)", fontsize=13, fontweight="bold")
        ax.set_ylabel("RMSE (% points)", fontsize=11)
        ax.set_xticks(x)
        ax.set_xticklabels(["CPU", "RAM", "Swap"])
        ax.legend()
        ax.grid(True, linestyle=":", alpha=0.6)
        plt.tight_layout()

        comp_path = os.path.join(output_dir, "model_comparison.png")
        plt.savefig(comp_path)
        plt.close()
        print(f"Saved plot: {comp_path}")


def main():
    parser = argparse.ArgumentParser(description="Evaluate GRU and LSTM models")
    parser.add_argument(
        "--input",
        default="ml/data/system_metrics.csv",
        help="Path to telemetry CSV file",
    )
    parser.add_argument(
        "--models-dir",
        default="ml/saved_models",
        help="Directory containing saved .pt models",
    )
    parser.add_argument(
        "--output-dir",
        default="ml/evaluation",
        help="Directory to save evaluation results and plots",
    )
    parser.add_argument(
        "--sequence_length",
        type=int,
        default=30,
        help="Sequence length",
    )
    parser.add_argument(
        "--prediction_horizon",
        type=int,
        default=5,
        help="Prediction horizon",
    )

    args = parser.parse_args()

    print("=" * 70)
    print("LLMSysMon Model Evaluation & Selection Pipeline")
    print("=" * 70)

    device = torch.device("cuda" if torch.cuda.is_available() else "cpu")
    print(f"Evaluation Hardware: {device}")

    try:
        x_train, y_train, x_test, y_test, scaler, _, _ = prepare_dataset(
            path=args.input,
            sequence_length=args.sequence_length,
            prediction_horizon=args.prediction_horizon,
        )
    except Exception as e:
        print(f"Failed to prepare evaluation dataset: {e}", file=sys.stderr)
        sys.exit(1)

    print(f"Unseen test evaluation sequences: {len(x_test)}")
    if len(x_test) == 0:
        print("Error: No test sequences available for evaluation.", file=sys.stderr)
        sys.exit(1)

    model_names = ["gru", "lstm"]
    eval_results: Dict[str, Dict[str, Any]] = {}
    last_actuals: Optional[np.ndarray] = None

    for name in model_names:
        pt_path = os.path.join(args.models_dir, f"{name}.pt")
        if not os.path.exists(pt_path):
            print(f"Notice: Model checkpoint {pt_path} not found, skipping {name.upper()}.")
            continue

        model, checkpoint = load_model(pt_path, device)
        preds, actuals, metrics = evaluate_model(model, x_test, y_test, scaler, device)
        last_actuals = actuals

        eval_results[name] = {
            "checkpoint": pt_path,
            "metrics": metrics,
            "predictions": preds,
        }

    if not eval_results:
        print("Error: No trained models found in", args.models_dir, file=sys.stderr)
        print("Please train models first: python -m ml.training.train --model gru", file=sys.stderr)
        sys.exit(1)

    # Print clean comparison table
    print("\n" + "=" * 90)
    print(f"{'Model':<8} {'CPU MAE':<10} {'RAM MAE':<10} {'SWAP MAE':<10} {'CPU RMSE':<10} {'RAM RMSE':<10} {'SWAP RMSE':<10} {'AVG RMSE':<10}")
    print("-" * 90)

    table_rows = []
    for name, data in eval_results.items():
        m = data["metrics"]
        print(
            f"{name.upper():<8} "
            f"{m['cpu_mae']:>7.2f}%   "
            f"{m['ram_mae']:>7.2f}%   "
            f"{m['swap_mae']:>7.2f}%   "
            f"{m['cpu_rmse']:>7.2f}%   "
            f"{m['ram_rmse']:>7.2f}%   "
            f"{m['swap_rmse']:>7.2f}%   "
            f"{m['avg_rmse']:>7.2f}%"
        )
        table_rows.append({
            "model": name.upper(),
            "cpu_mae": round(m["cpu_mae"], 4),
            "ram_mae": round(m["ram_mae"], 4),
            "swap_mae": round(m["swap_mae"], 4),
            "cpu_rmse": round(m["cpu_rmse"], 4),
            "ram_rmse": round(m["ram_rmse"], 4),
            "swap_rmse": round(m["swap_rmse"], 4),
            "avg_mae": round(m["avg_mae"], 4),
            "avg_rmse": round(m["avg_rmse"], 4),
        })
    print("=" * 90)

    # Select the better model based on lowest overall test error
    best_model_name = min(eval_results.keys(), key=lambda k: eval_results[k]["metrics"]["avg_rmse"])
    best_metrics = eval_results[best_model_name]["metrics"]

    comp_details = [f"{k.upper()}: {v['metrics']['avg_rmse']:.2f}%" for k, v in eval_results.items() if k != best_model_name]
    comp_str = ", ".join(comp_details) if comp_details else "N/A"
    selection_reason = (
        f"{best_model_name.upper()} achieved the lower average test RMSE "
        f"({best_metrics['avg_rmse']:.2f}% vs {comp_str}) "
        f"across unseen chronological telemetry."
    )

    print(f"\n>>> Model Selection Decision: {best_model_name.upper()} <<<")
    print(f"Reason: {selection_reason}\n")

    # Save results.json and results.csv
    results_json_path = os.path.join(args.output_dir, "results.json")
    results_csv_path = os.path.join(args.output_dir, "results.csv")
    plots_dir = os.path.join(args.output_dir, "results")
    os.makedirs(args.output_dir, exist_ok=True)

    json_payload = {
        "selected_model": best_model_name,
        "selection_reason": selection_reason,
        "models": {k: v["metrics"] for k, v in eval_results.items()},
        "test_samples": len(x_test),
        "sequence_length": args.sequence_length,
        "prediction_horizon": args.prediction_horizon,
    }

    with open(results_json_path, "w", encoding="utf-8") as f:
        json.dump(json_payload, f, indent=2)
    print(f"Saved evaluation metrics to: {results_json_path}")

    pd.DataFrame(table_rows).to_csv(results_csv_path, index=False)
    print(f"Saved comparison table to:  {results_csv_path}")

    # Save selected_model pointer in saved_models
    selected_pointer_path = os.path.join(args.models_dir, "selected_model.json")
    with open(selected_pointer_path, "w", encoding="utf-8") as f:
        json.dump({
            "selected_model": best_model_name,
            "model_path": os.path.join(args.models_dir, f"{best_model_name}.pt"),
            "reason": selection_reason,
            "metrics": best_metrics,
        }, f, indent=2)
    print(f"Saved model selection configuration to: {selected_pointer_path}")

    # Generate comparison plots
    if last_actuals is not None:
        generate_plots(eval_results, last_actuals, plots_dir)

    print("=" * 70)


if __name__ == "__main__":
    main()
