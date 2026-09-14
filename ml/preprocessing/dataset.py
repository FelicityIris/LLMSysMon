import argparse
import numpy as np
import pandas as pd

from sklearn.preprocessing import MinMaxScaler

FEATURES = [
        "cpu_usage",
        "memory_usage",
        "swap_usage"
]

def load_data(path):
    df = pd.read_csv(path)

    df["timestamp"] = pd.to_datetime(df["timestamp"], utc=True)

    df = df.sort_values("timestamp")
    df = df.drop_duplicates("timestamp")

    df[FEATURES] = df[FEATURES].apply(pd.to_numeric, errors = "coerce")

    df = df.dropna(subset = FEATURES)

    return df

def create_sequences(data, sequence_length, prediction_horizon):
    x = []
    y = []

    end = len(data) - sequence_length - prediction_horizon + 1

    for i in range(end):
        sequence_end = i + sequence_length
        prediction_end = sequence_end + prediction_horizon

        x.append(data[i:sequence_end])
        y.append(data[prediction_end - 1])

    return np.array(x, dtype = np.float32), np.array(y, dtype = np.float32)

def prepare_dataset(path, sequence_length = 30, prediction_horizon = 5):
    df = load_data(path)

    values = df[FEATURES].to_numpy(dtype = np.float32)

    scaler = MinMaxScaler()
    scaled_values = scaler.fit_transform(values)

    x, y = create_sequences(scaled_values, sequence_length, prediction_horizon)

    return df, x, y, scaler

def main():
    parser = argparse.ArgumentParser()

    parser.add_argument("--input", default = "data/system_metrics.csv")
    parser.add_argument("--sequence_length", type = int, default = 30)
    parser.add_argument("--prediction_horizon", type = int, default = 5)

    args = parser.parse_args()

    df, x, y, scaler = prepare_dataset(args.input, args.sequence_length, args.prediction_horizon)

    print(f"Samples loaded: {len(df)}")
    print(f"Features: {FEATURES}")
    print(f"Sequence length: {args.sequence_length}")
    print(f"Prediction horizon: {args.prediction_horizon}")
    print(f"X shape: {x.shape}")
    print(f"Y shape: {y.shape}")

if __name__ == "__main__":
    main()
