# LLMSysMon: Machine Learning-Powered System Telemetry & Resource Forecasting

LLMSysMon is a local, lightweight operating system telemetry monitor and time-series resource forecasting application. It pairs real-time hardware metrics collection with recurrent deep learning models (GRU / LSTM) to anticipate resource pressure and recommend proactive workload adjustments.

---

## 1. System Architecture

```
                                  OS KERNEL & HARDWARE
                      (CentralProcessor, GlobalMemory, VirtualMemory, GPU)
                                           │
                                           ▼
                                    OSHI / Native APIs
                                           │
                                           ▼
                                    MetricCollectors
                               (Cpu, Memory, Gpu Collectors)
                                           │
                                           ▼
                                     MetricService
                                           │
                                           ▼
                           MetricScheduler (1Hz periodic task)
                                           │
                     ┌─────────────────────┴─────────────────────┐
                     ▼                                           ▼
             SystemSnapshot                              JavaFX Dashboard
                     │                                (Live MD3 Metric Cards &
                     ▼                                 Rolling 60s Line Charts)
             MetricRepository                                    │
                     │                                           │ (Every 5 seconds)
                     ▼                                           ▼
           SQLite DB (llmsysmon.db)                     PredictionService
                     │                                           │
                     ▼                                           ▼
              DatasetExporter                            Subprocess Pipeline
                     │                                 (python -m ml.inference.predict)
                     ▼                                           │
           ml/data/system_metrics.csv                            ▼
                     │                                 Inference Engine & Model
                     │                                   (Selected: LSTM / GRU)
                     ▼                                           │
           Time-Series Preprocessing                             ▼
          (MinMaxScaler - Leak-Free)                     RecommendationEngine
                     │                                           │
                     ▼                                           ▼
           GRU / LSTM Deep Models                        AI Insights & Advice
             (PyTorch / CUDA)                               (On Dashboard)
```

---

## 2. Core Features

- **Real-Time Telemetry:** Collects CPU utilization, clock frequency, core topology, physical RAM, swap space, and GPU/VRAM telemetry at 1 Hz.
- **Leak-Free ML Pipeline:** Splits data chronologically *before* fitting feature scalers, preventing data leakage across temporal boundaries.
- **Sliding-Window Recurrent Modeling:** Uses 30 seconds of historical observations (`sequence_length = 30`) to forecast resource states 5 seconds into the future (`prediction_horizon = 5`).
- **Objective Model Selection:** Simultaneously evaluates GRU and LSTM architectures across unseen chronological test sets using real percentage MAE/RMSE metrics to select the superior model.
- **Automated Recommendation Engine:** Translates raw percentage forecasts into actionable, non-destructive system optimization advice.
- **Fault-Tolerant Java ↔ Python Integration:** Background subprocess communication ensures the JavaFX monitoring interface operates smoothly even if Python or model weights are temporarily unavailable.

---

## 3. Technology Stack & Requirements

### System Requirements
- **Java:** JDK 21+
- **Build Tool:** Gradle (wrapper included)
- **Python:** Python 3.10+
- **Hardware Acceleration:** NVIDIA GPU with CUDA (optional; automatically falls back to CPU)

### Python Dependencies
- `torch` (PyTorch >= 2.5)
- `pandas` (>= 2.2)
- `numpy` (>= 2.0)
- `scikit-learn` (>= 1.5)
- `matplotlib` (>= 3.9)

---

## 4. Python Environment Setup

From the project root:

```bash
# 1. Create a virtual environment
python -m venv .venv

# 2. Activate the virtual environment
# Windows PowerShell:
.\.venv\Scripts\Activate.ps1
# Linux/macOS:
source .venv/bin/activate

# 3. Install required dependencies
pip install -r requirements.txt
```

---

## 5. End-to-End Execution Guide

All Python scripts are executable from the project root using module syntax:

### Step 1: Data Preprocessing & Validation
Verify and inspect the sliding-window dataset:
```bash
python -m ml.preprocessing.dataset --input ml/data/system_metrics.csv
```

### Step 2: Model Training
Train both models independently (uses CUDA if available):
```bash
# Train GRU
python -m ml.training.train --model gru --epochs 30

# Train LSTM
python -m ml.training.train --model lstm --epochs 30
```
Trained weights and normalization metadata are stored in `ml/saved_models/`.

### Step 3: Model Evaluation & Benchmark
Evaluate both models on the unseen chronological test set in real physical percentage units (`%`):
```bash
python -m ml.evaluation.evaluate
```
This command:
1. Calculates MAE and RMSE separately for CPU, RAM, and Swap.
2. Formats a comparison table.
3. Automatically identifies and selects the better model based on lowest test RMSE.
4. Generates comparison plots under `ml/evaluation/results/`:
   - `actual_vs_pred_cpu.png`
   - `actual_vs_pred_memory.png`
   - `actual_vs_pred_swap.png`
   - `model_comparison.png`

### Step 4: Standalone Inference
Run real-time inference using the selected model:
```bash
# Human-readable output:
python -m ml.inference.predict

# Machine-readable JSON output (used by Java):
python -m ml.inference.predict --json
```

### Step 5: Launch the Java Application
Run the JavaFX dashboard with integrated AI forecasting:
```bash
# Using Gradle
gradle run
```

---

## 6. Machine Learning Architecture: GRU vs. LSTM

| Attribute | Gated Recurrent Unit (GRU) | Long Short-Term Memory (LSTM) |
| :--- | :--- | :--- |
| **Gating Mechanism** | Reset Gate, Update Gate | Input Gate, Forget Gate, Output Gate |
| **State Vectors** | Hidden State ($h_t$) | Hidden State ($h_t$) and Cell State ($c_t$) |
| **Parameters** | Fewer parameters (~3x per unit) | More parameters (~4x per unit) |
| **Convergence Speed** | Faster training per epoch | Marginally slower per epoch |
| **Long-Term Memory** | Effective for short/medium dependencies | Superior at isolating long-term temporal drifts |
| **Observed Accuracy** | 7.68% Test RMSE | **7.22% Test RMSE (Selected Winner)** |

---

## 7. Data Requirements & Workload Guidelines

For optimal time-series training and generalizability, telemetry datasets should capture diverse operating states:
- **Idle State:** Minimal CPU load, stable memory.
- **Burst Workloads:** Short spikes (opening browser, file unzip, app startup).
- **Sustained Compute:** High CPU utilization across multiple cores (video encoding, code compilation).
- **Memory Pressure:** Gradual memory allocation to trigger virtual memory paging and swap activity.
- **Cooldown:** Transition from heavy load back to baseline idle.

*Minimum samples:* The pipeline requires at least 45 samples (`sequence_length (30) + prediction_horizon (5) + 10`). Training on 500–2,000+ continuous samples is recommended for production-level stability.

---

## 8. Known Limitations & Prototype Scope

1. **Subprocess Overhead:** Telemetry is passed via CSV export and standard subprocess calls. While lightweight (~100ms latency) and robust for a college prototype, a production deployment could use an IPC socket or embedded ONNX Runtime.
2. **Local Machine Scope:** Designed for single-host telemetry monitoring; distributed cluster collection is out of scope.
3. **Non-Destructive Recommendations:** All engine guidance is informational. The application never kills processes or alters OS scheduling policies.
