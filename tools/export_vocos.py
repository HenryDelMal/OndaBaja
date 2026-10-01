"""Export the official Vocos EnCodec-24k checkpoint for the native Android backend.

Usage: python tools/export_vocos.py CHECKPOINT OUTPUT.bin
Requires torch and numpy only. The checkpoint is charactr/vocos-encodec-24khz.
"""
import argparse
import hashlib
import struct
from pathlib import Path
import numpy as np
import torch


def export(checkpoint, output):
    state = torch.load(checkpoint, map_location="cpu", weights_only=True)
    tensors = {}
    for name, value in state.items():
        if name.startswith("backbone."):
            name = name.removeprefix("backbone.").replace("convnext.", "blocks.")
            name = name.replace("final_layer_norm.", "final_norm.")
        elif name.startswith("head.out."):
            name = name.replace("head.out.", "head.")
        elif name == "feature_extractor.codebook_weights":
            name = "rvq"
            value = value.reshape(16, 1024, 128)
        else:
            continue
        tensors[name] = value.detach().numpy().astype("<f4")
    assert tensors["rvq"].shape == (16, 1024, 128)
    assert tensors["head.weight"].shape == (1282, 384)
    assert len(tensors) == 81, f"Unexpected checkpoint tensors: {len(tensors)}"
    with Path(output).open("xb") as stream:
        stream.write(b"VOCOSN1\0")
        stream.write(struct.pack("<12I", 1, 24000, 1, 320, 1280, 128, 384, 1152, 8, 16, 1024, 0))
        stream.write(struct.pack("<I", len(tensors)))
        for name, value in sorted(tensors.items()):
            assert np.isfinite(value).all()
            key = name.encode()
            stream.write(struct.pack("<I", len(key)))
            stream.write(key)
            stream.write(struct.pack("<I", value.ndim))
            stream.write(struct.pack("<" + "I" * value.ndim, *value.shape))
            stream.write(value.tobytes(order="C"))
    print("Checkpoint SHA256:", hashlib.sha256(Path(checkpoint).read_bytes()).hexdigest())
    print("Exported:", output)


if __name__ == "__main__":
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("checkpoint", type=Path)
    parser.add_argument("output", type=Path)
    args = parser.parse_args()
    export(args.checkpoint, args.output)
