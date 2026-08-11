# Shape Detector — Deployment Spec (portable)

Everything needed to run `best.onnx` on any machine / cloud / language without breaking.
If detections come out as garbage, it's almost always **preprocessing** (§4) or **class order** (§3).

---

## 1. Files to copy
| File | Purpose | Required |
|---|---|---|
| `best.onnx` | the detector (weights + graph) | **yes** |
| `dataset_yolo/classes.json` | class index → name | **yes** (or hard-code §3) |
| `onnx_infer.py` | reference implementation of §4–§6 | reference only |

Nothing else is needed at runtime. `best.pt` is the PyTorch original — keep it for re-export, not for deployment.

### Integrity (verify after transfer)
```
best.onnx                 12,247,959 bytes   sha256 starts 1acdfd896331d8d0
best.pt                    6,267,306 bytes   sha256 starts 1b5a082dfa15a972
dataset_yolo/classes.json        142 bytes   sha256 starts e7fd31381fab33a5
```

---

## 2. Model I/O
- **Input**  name `images`, shape `[1, 3, 640, 640]`, dtype **float32** (fixed — batch 1, 640×640).
- **Output** name `output0`, shape `[1, 17, 8400]`.
  - `17 = 4 box params + 13 class scores`
  - `8400 = candidate detections`
- **opset 12**, ir_version 7. Exported with ultralytics 8.4.117.
- Built/verified with **onnxruntime 1.20.1** (CPU). Any ORT ≥ 1.16 with opset 12 works.

---

## 3. Class list (index order — DO NOT re-sort)
Output columns 4–16 are these 13 class scores, in this exact order:
```
0  circle        7  ring
1  clover        8  ring_bumpy
2  flower        9  scallop
3  flower_2     10  square
4  heart        11  square_hole
5  heart_star   12  star
6  octagon
```
This order is baked into `best.onnx` and matches `dataset_yolo/classes.json`. If you re-train and classes change, re-dump this list.

---

## 4. Preprocessing (must match training exactly)
1. **Letterbox to 640×640**: `scale = min(640/w, 640/h)`, resize keeping aspect ratio, paste centered onto a **gray (114,114,114)** 640×640 canvas. Remember `scale`, `pad_x`, `pad_y`.
2. **Color order: RGB** (swap if your loader gives BGR, e.g. OpenCV).
3. **Normalize: divide by 255.0** → range 0–1. **No mean/std subtraction.**
4. **Layout**: HWC → CHW, add batch → `[1,3,640,640]`, **float32**, contiguous.

---

## 5. Postprocessing (decode)
1. Run model → `output0` `[1,17,8400]`.
2. **Transpose** to `[8400, 17]`.
3. Per row: `cx,cy,w,h = cols[0:4]` (in **640-input pixels**, center form); `class_scores = cols[4:17]`.
4. `confidence = max(class_scores)`, `class_id = argmax(class_scores)`. **Scores are already 0–1 — do NOT sigmoid.**
5. Keep rows with `confidence >= 0.25`.
6. Convert `cxcywh → xyxy`.
7. **Un-letterbox** back to original pixels:
   `x = (x - pad_x) / scale`,  `y = (y - pad_y) / scale`.
8. **NMS**, class-agnostic, IoU threshold **0.45**.

Result per detection: `(class_name, confidence, [x0,y0,x1,y1])` in original image pixels.

---

## 6. Thresholds (tuned)
| Param | Value | Meaning |
|---|---|---|
| `CONF` | **0.25** | min class score to keep |
| `IOU`  | **0.45** | NMS overlap; class-agnostic |
| `SIZE` | **640** | fixed input |

Class-agnostic NMS matters: it stops one shape being kept as two different classes.

---

## 7. A → B match rule (solve / accuracy logic)
The model sees **one image at a time**. Run it on A (reference strip) and on B (grid) **separately**, then compare:

1. Sort A **left-to-right** (`by cx`). Sort B **top-row then left-to-right** (`by (round(cy/40), cx)`).
2. Build a count of B's detected classes.
3. Walk A in order. For each A shape: if B still has an **unused** instance of that class → **found** (decrement it); else → **MISSING**.
4. **"Found everything" = every A shape matched** → that's the solvable/correct condition.
5. **Leftover B shapes are distractors — normal, not errors.** B has more shapes than A asks for; do not penalize extras.
6. A **MISSING** + a leftover of a *similar* class = a misclassification (e.g. `circle↔octagon`, `ring_bumpy↔heart_star`).

---

## 8. Runtime dependencies
- **onnxruntime** ≥ 1.16 (CPU is fine; `onnxruntime-gpu`/`-directml` optional).
- An image lib for load + letterbox (PIL, OpenCV, stb_image, etc.).
- Array math for the transpose/NMS (numpy, or hand-rolled — see `onnx_infer.py`).
- **No ultralytics or torch needed at inference** — that's the point of ONNX.

Quick smoke test on this machine:
```
python onnx_infer.py            # runs a sample, writes onnx_out.png
```

---

## 9. Current accuracy (baseline to compare against after moving)
From `eval_log.csv` at export time: **~91% captcha-level** (found every shape), **~97% shape-level**.
Main error: `heart_star` confusion. Re-run `python eval_stats.py` for the live number.

## 10. Re-export (if you retrain)
```
python -c "from ultralytics import YOLO; YOLO('best.pt').export(format='onnx', imgsz=640, opset=12)"
```
Then re-dump §1 hashes and §3 class list. For dynamic input sizes add `dynamic=True` (then §2 shapes become symbolic and §4 letterbox target can vary).
