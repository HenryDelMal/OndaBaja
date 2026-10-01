"""Compare native Android Vocos code against the official PyTorch backbone/head.

Usage: python tools/check_vocos_parity.py CHECKPOINT MODEL FIXTURE_EXECUTABLE
Requires torch, numpy and the official vocos package. No checkpoint downloads.
"""
import argparse
import subprocess
import tempfile
from pathlib import Path
import numpy as np
import torch
from vocos.models import VocosBackbone
from vocos.heads import ISTFTHead

parser = argparse.ArgumentParser(description=__doc__)
parser.add_argument("checkpoint", type=Path)
parser.add_argument("model", type=Path)
parser.add_argument("fixture", type=Path)
args = parser.parse_args()
torch.set_num_threads(1)
state = torch.load(args.checkpoint, map_location="cpu", weights_only=True)
backbone = VocosBackbone(128, 384, 1152, 8, adanorm_num_embeddings=4).eval()
head = ISTFTHead(384, 1280, 320, padding="same").eval()
backbone.load_state_dict({k.removeprefix("backbone."): v for k,v in state.items() if k.startswith("backbone.")})
head.load_state_dict({k.removeprefix("head."): v for k,v in state.items() if k.startswith("head.")})
table = state["feature_extractor.codebook_weights"].reshape(16, 1024, 128)
rng = np.random.default_rng(412)

with tempfile.TemporaryDirectory() as directory:
    directory = Path(directory)
    def native(tokens):
        frames, books = tokens.shape
        tokens.astype("<u2").tofile(directory / "tokens")
        subprocess.run([str(args.fixture.resolve()), str(args.model.resolve()),
            str(directory / "tokens"), str(frames), str(books), str(directory / "pcm")], check=True)
        return np.fromfile(directory / "pcm", dtype="<f4")

    for bandwidth_id, books in enumerate([2,4,8,16]):
        for frames in [1, 337]:
            tokens = rng.integers(0,1024,(frames,books),dtype=np.uint16)
            with torch.inference_mode():
                features = sum(table[q, torch.from_numpy(tokens[:,q].astype(np.int64))] for q in range(books))
                reference = head(backbone(features.T.unsqueeze(0), bandwidth_id=torch.tensor([bandwidth_id]))).squeeze(0).numpy()
            actual = native(tokens)
            assert actual.shape == reference.shape == (frames*320,)
            assert np.isfinite(actual).all()
            error = np.max(np.abs(actual-reference))
            relative = np.linalg.norm(actual-reference) / max(np.linalg.norm(reference),1e-9)
            print(f"books={books} frames={frames} max_abs={error:.7g} relative_l2={relative:.7g}", flush=True)
            assert error < 3e-4 and relative < 3e-3
            if frames == 337:
                # 300 output hops followed by 32 future hops: compare samples
                # away from true file boundaries to full-file inference.
                chunk = native(tokens[:332])[:300*320]
                assert np.max(np.abs(chunk - actual[:300*320])) < 3e-4
                tail = native(tokens[225:])[75*320:]
                assert np.max(np.abs(tail - actual[300*320:])) < 3e-4
    print("Official checkpoint parity and chunk-context checks passed.")
