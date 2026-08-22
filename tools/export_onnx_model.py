import torch
import torch.nn as nn
import torch.nn.functional as F
import numpy as np
import os
import onnxruntime as ort

class DuolingoChessPieceNet(nn.Module):
    """
    Leichtgewichtiges Netz zur Figurenerkennung auf dem Gerät (DuolingoChessPieceNet)
    Eingabe: die zentrale ROI der 64 Felder (64, 1, 32, 32)
    Ausgabe:
      1. is_empty_logits (64, 2)
      2. piece_class_logits (64, 6) -> [P, R, N, B, Q, K]
      3. is_white_logits (64, 2)
    """
    def __init__(self):
        super(DuolingoChessPieceNet, self).__init__()
        
        # Faltungsschichten zur Merkmalsextraktion
        self.conv1 = nn.Conv2d(1, 16, kernel_size=3, padding=1)
        self.pool1 = nn.MaxPool2d(2, 2) # 16x16
        self.conv2 = nn.Conv2d(16, 32, kernel_size=3, padding=1)
        self.pool2 = nn.MaxPool2d(2, 2) # 8x8
        
        self.fc_shared = nn.Linear(32 * 8 * 8, 128)
        
        # 3 Ausgabeköpfe
        self.head_empty = nn.Linear(128, 2)
        self.head_class = nn.Linear(128, 6) # P, R, N, B, Q, K
        self.head_color = nn.Linear(128, 2) # black, white

    def forward(self, x):
        # x: (N, 1, 32, 32)
        feat = F.relu(self.conv1(x))
        feat = self.pool1(feat)
        feat = F.relu(self.conv2(feat))
        feat = self.pool2(feat)
        feat = feat.view(feat.size(0), -1)
        shared = F.relu(self.fc_shared(feat))
        
        out_empty = self.head_empty(shared)
        out_class = self.head_class(shared)
        out_color = self.head_color(shared)
        
        return out_empty, out_class, out_color

# Netz anlegen und Gewichte initialisieren
os.makedirs("models", exist_ok=True)
model = DuolingoChessPieceNet()
model.eval()

# ONNX-Modell exportieren
dummy_input = torch.randn(64, 1, 32, 32, dtype=torch.float32)
onnx_path = "models/duolingo_chess.onnx"

torch.onnx.export(
    model,
    dummy_input,
    onnx_path,
    input_names=["cell_images"],
    output_names=["out_empty", "out_class", "out_color"],
    dynamic_axes={
        "cell_images": {0: "batch_size"},
        "out_empty": {0: "batch_size"},
        "out_class": {0: "batch_size"},
        "out_color": {0: "batch_size"}
    },
    opset_version=14
)

print(f"ONNX-Modell exportiert nach: {onnx_path}")

# Laden und Inferenz mit onnxruntime prüfen
session = ort.InferenceSession(onnx_path)
test_in = np.random.randn(64, 1, 32, 32).astype(np.float32)
outputs = session.run(None, {"cell_images": test_in})

print(f"Prüfung mit ONNXRuntime erfolgreich: Anzahl der Ausgaben = {len(outputs)}")
print(f"out_empty shape: {outputs[0].shape}, out_class shape: {outputs[1].shape}, out_color shape: {outputs[2].shape}")
