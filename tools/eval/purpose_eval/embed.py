"""Mirror of memory/WordPieceTokenizer.kt + OnnxEmbedder.kt for the offline scoreboard (Phase 2). Needs the optional
packages `onnxruntime` and `numpy` and the model files the app ships (app/src/main/assets/embedding/). Not part of the APK."""
import re
import unicodedata
from pathlib import Path

DEFAULT_DIR = Path(__file__).resolve().parents[3] / "app" / "src" / "main" / "assets" / "embedding"
MAX_LEN, MAX_WORD, MAX_CHARS = 128, 100, 1500


def _punct(c: str) -> bool:
    o = ord(c)
    return 33 <= o <= 47 or 58 <= o <= 64 or 91 <= o <= 96 or 123 <= o <= 126 or unicodedata.category(c).startswith("P")


class WordPiece:
    def __init__(self, vocab_lines, max_len=MAX_LEN):
        self.v = {}
        for i, w in enumerate(vocab_lines):
            self.v.setdefault(w.strip(), i)
        self.cls, self.sep, self.unk, self.max_len = self.v["[CLS]"], self.v["[SEP]"], self.v["[UNK]"], max_len

    def basic(self, text):
        t = unicodedata.normalize("NFD", text.lower())
        t = "".join(c for c in t if unicodedata.category(c) != "Mn" and not (unicodedata.category(c) == "Cc" and not c.isspace()))
        out, cur = [], []
        for c in t:
            if c.isspace():
                if cur: out.append("".join(cur)); cur = []
            elif _punct(c):
                if cur: out.append("".join(cur)); cur = []
                out.append(c)
            else:
                cur.append(c)
        if cur: out.append("".join(cur))
        return out

    def pieces(self, w):
        if len(w) > MAX_WORD:
            return [self.unk]
        out, s = [], 0
        while s < len(w):
            e, f = len(w), None
            while s < e:
                x = ("##" if s else "") + w[s:e]
                if x in self.v:
                    f = self.v[x]; break
                e -= 1
            if f is None:
                return [self.unk]
            out.append(f); s = e
        return out

    def encode(self, text):
        ids = [self.cls]
        for w in self.basic(text):
            for i in self.pieces(w):
                if len(ids) >= self.max_len - 1:
                    return ids + [self.sep]
                ids.append(i)
        return ids + [self.sep]


class Embedder:
    id = "all-MiniLM-L6-v2-onnx-v1"

    def __init__(self, model_dir=None):
        import numpy as np
        import onnxruntime as ort
        d = Path(model_dir) if model_dir else DEFAULT_DIR
        self.np = np
        self.tok = WordPiece(d.joinpath("vocab.txt").read_text(encoding="utf-8").splitlines())
        self.s = ort.InferenceSession(str(d / "model.onnx"))
        self.types = "token_type_ids" in [i.name for i in self.s.get_inputs()]

    def embed(self, text):
        np = self.np
        ids = np.array([self.tok.encode(text[:MAX_CHARS])], dtype=np.int64)
        feed = {"input_ids": ids, "attention_mask": np.ones_like(ids)}
        if self.types:
            feed["token_type_ids"] = np.zeros_like(ids)
        v = self.s.run(None, feed)[0][0].mean(0)
        n = float(np.linalg.norm(v))
        return v / n if n else v
