import torch
import torch.nn as nn

class LSTMModel(nn.Module):
    def __init__(self, input_size = 3, hidden_size = 64, num_layers = 2, output_size = 3):
        super().__init__()
        
        self.lstm = nn.LSTM(
            input_size = input_size,
            hidden_size = hidden_size,
            num_layers = num_layers,
            batch_first = True
        )

        self.output = nn.Linear(hidden_size, output_size)

    def forward(self, x):
        output, _ = self.lstm(x)
        last_output = output[:, -1, :]
        return self.output(last_output)
