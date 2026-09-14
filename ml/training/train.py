import argparse
import os

import torch
import torch.nn as nn
from torch.utils.data import TensorDataset, DataLoader

from ml.preprocessing.dataset import prepare_dataset
from ml.models.gru import GRUModel
from ml.models.lstm import LSTMModel

def train_model(model, train_loader, epochs, learning_rate, device):
    criterion = nn.MSELoss()

    optimizer = torch.optim.Adam(model.parameters(), lr = learning_rate)

    model.to(device)

    for epoch in range(epochs):
        model.train()

        total_loss = 0.0

        for x_batch, y_batch in train_loader:
            x_batch = x_batch.to(device)
            y_batch = y_batch.to(device)

            optimizer.zero_grad()

            predictions = model(x_batch)

            loss = criterion(predictions, y_batch)
            loss.backward()
            
            optimizer.step()

            total_loss += loss.item()

        average_loss = total_loss / len(train_loader)

        print(f"Epoch {epoch + 1:03d}/{epochs} - Loss: {average_loss:.6f}")
    
    return model

def main():
    parser = argparse.ArgumentParser()

    parser.add_argument("--model", choices = ["gru", "lstm"], required = True)
    parser.add_argument("--input", default = "ml/data/system_metrics.csv")
    parser.add_argument("--epochs", type = int, default = 30)
    parser.add_argument("--batch_size", type = int, default = 64)
    parser.add_argument("--sequence_length", type = int, default = 30)
    parser.add_argument("--prediction_horizon", type = int, default = 5)

    args = parser.parse_args()

    _, x, y, _ = prepare_dataset(args.input, args.sequence_length, args.prediction_horizon)

    split = int(len(x) * 0.8)

    x_train = x[:split]
    y_train = y[:split]

    x_test = x[split:]
    y_test = y[split:]

    print(f"Training samples: {len(x_train)}")
    print(f"Testing samples: {len(x_test)}")

    train_dataset = TensorDataset(torch.tensor(x_train), torch.tensor(y_train))

    train_loader = DataLoader(train_dataset, batch_size = args.batch_size, shuffle = False)

    device = torch.device("cuda" if torch.cuda.is_available() else "cpu")

    print(f"Using device: {device}")

    if args.model == "gru":
        model = GRUModel()
    else:
        model = LSTMModel()

    model = train_model(model, train_loader, args.epochs, 0.001, device)

    os.makedirs("ml/saved_models", exist_ok = True)

    output_path = f"ml/saved_models/{args.model}.pt"

    torch.save(
        {
            "model_state_dict": model.state_dict(),
            "input_size": 3,
            "hidden_size": 64,
            "num_layers": 2,
            "output_size": 3,
            "sequence_length": args.sequence_length,
            "prediction_horizon": args.prediction_horizon
        },
        output_path
    )

    print(f"Model saved to {output_path}")

if __name__ == "__main__":
    main()
