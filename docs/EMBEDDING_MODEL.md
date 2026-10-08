# On-device embedding model (Phase 2)

Retrieval merges full-text search with meaning-based search. The model runs on the phone through ONNX Runtime; nothing leaves the device.

## Files to add (the app works without them, full-text only)
Put these two files in `app/src/main/assets/embedding/`:

| File | Source |
|---|---|
| `model.onnx` | `sentence-transformers/all-MiniLM-L6-v2`, ONNX export (the quantized `onnx/model_qint8_arm64.onnx` or `onnx/model_quint8_avx2.onnx` keeps the APK small; the fp32 `onnx/model.onnx` is about 90 MB) |
| `vocab.txt` | same repo, the BERT uncased vocabulary |

Licence: Apache-2.0 (copy it to `licenses/MiniLM-Apache-2.0.txt` when the files are added). ONNX Runtime is MIT.

Output must be token embeddings `[1, tokens, 384]`; the app does mean pooling and L2 normalisation. If the model has no `token_type_ids` input that is handled. If you change the model, change `OnnxEmbedder.id` so old vectors are recomputed.

## How it is used
- `EmbeddingIndex.sync()` embeds archive search documents in the background (after reflection, letters, gardening, launch) into the encrypted `embedding_row` table.
- `SearchIndex.relevant()` embeds the message, takes the nearest documents (cosine at least 0.45), and merges them with the full-text matches by reciprocal rank fusion, then reranks by recency.
- Embeddings only find and rank. They never write memory text or change a confidence.
