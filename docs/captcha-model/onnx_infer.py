#!/usr/bin/env python3
"""
Reference inference for best.onnx using onnxruntime + hand-written decode/NMS.
Deliberately explicit (no ultralytics) so it's easy to port to C#, C++, Rust, JS, etc.

  python onnx_infer.py                 # runs on a sample frame from dataset_yolo/
  python onnx_infer.py path/to.png     # runs on your image, writes onnx_out.png

The 6 steps any port must reproduce are numbered below.
"""
import sys, json
from pathlib import Path
import numpy as np
import onnxruntime as ort
from PIL import Image, ImageDraw

MODEL = "best.onnx"
SIZE = 640            # model's fixed input (see best.onnx input: [1,3,640,640])
CONF = 0.25           # keep detections with class score >= this
IOU = 0.45            # NMS overlap threshold (matches agnostic_nms in the tool)
PAD = 114             # letterbox gray

_cj = next((p for p in [Path("classes.json"), Path("dataset_yolo/classes.json")] if p.exists()), None)
NAMES = json.loads(_cj.read_text()) if _cj else [str(i) for i in range(13)]


# --- STEP 1: letterbox (resize keeping aspect ratio, pad to SIZE x SIZE) -----------------
def letterbox(im):
    w0, h0 = im.size
    r = min(SIZE / w0, SIZE / h0)
    nw, nh = round(w0 * r), round(h0 * r)
    canvas = Image.new("RGB", (SIZE, SIZE), (PAD, PAD, PAD))
    px, py = (SIZE - nw) // 2, (SIZE - nh) // 2
    canvas.paste(im.resize((nw, nh)), (px, py))
    return canvas, r, px, py            # r = scale, (px,py) = pad offset


# --- STEP 2: preprocess -> float NCHW in [0,1], RGB ---------------------------------------
def preprocess(im):
    canvas, r, px, py = letterbox(im)
    x = np.asarray(canvas, dtype=np.float32) / 255.0     # HWC, 0..1
    x = x.transpose(2, 0, 1)[None]                       # -> 1,C,H,W
    return np.ascontiguousarray(x), r, px, py


# --- STEP 5b: batched IoU of one box vs many (xyxy) ---------------------------------------
def iou_batch(box, boxes):
    x1 = np.maximum(box[0], boxes[:, 0]); y1 = np.maximum(box[1], boxes[:, 1])
    x2 = np.minimum(box[2], boxes[:, 2]); y2 = np.minimum(box[3], boxes[:, 3])
    inter = np.clip(x2 - x1, 0, None) * np.clip(y2 - y1, 0, None)
    a = (box[2] - box[0]) * (box[3] - box[1])
    b = (boxes[:, 2] - boxes[:, 0]) * (boxes[:, 3] - boxes[:, 1])
    return inter / (a + b - inter + 1e-9)


# --- STEP 5: class-agnostic NMS -----------------------------------------------------------
def nms(boxes, scores, iou_thr):
    order = scores.argsort()[::-1]
    keep = []
    while len(order):
        i = order[0]; keep.append(i)
        if len(order) == 1:
            break
        rest = order[1:]
        order = rest[iou_batch(boxes[i], boxes[rest]) < iou_thr]
    return keep


# --- STEPS 3-6: run + decode --------------------------------------------------------------
def detect(sess, im):
    x, r, px, py = preprocess(im)
    # STEP 3: run the model
    out = sess.run(None, {sess.get_inputs()[0].name: x})[0]   # shape [1, 4+nc, 8400]
    # STEP 4: reshape to [8400, 4+nc]; first 4 = cx,cy,w,h (input px), rest = class scores (0..1)
    p = out[0].T
    boxes = p[:, :4]
    scores_all = p[:, 4:]
    cls = scores_all.argmax(1)
    conf = scores_all.max(1)
    m = conf >= CONF
    boxes, cls, conf = boxes[m], cls[m], conf[m]
    if len(boxes) == 0:
        return []
    # cxcywh -> xyxy (still in letterboxed input pixels)
    xy = np.empty_like(boxes)
    xy[:, 0] = boxes[:, 0] - boxes[:, 2] / 2
    xy[:, 1] = boxes[:, 1] - boxes[:, 3] / 2
    xy[:, 2] = boxes[:, 0] + boxes[:, 2] / 2
    xy[:, 3] = boxes[:, 1] + boxes[:, 3] / 2
    # STEP 6: undo letterbox -> map back to ORIGINAL image pixels
    xy[:, [0, 2]] = (xy[:, [0, 2]] - px) / r
    xy[:, [1, 3]] = (xy[:, [1, 3]] - py) / r
    keep = nms(xy, conf, IOU)                                  # class-agnostic
    return [(NAMES[cls[i]] if cls[i] < len(NAMES) else str(cls[i]),
             float(conf[i]), xy[i].tolist()) for i in keep]


def main():
    img_path = sys.argv[1] if len(sys.argv) > 1 else None
    if img_path is None:
        s = sorted(Path("dataset_yolo/images").glob("*_B.png")) or sorted(Path("dataset_yolo/images").glob("*.png"))
        if not s:
            raise SystemExit("no sample image — pass one: python onnx_infer.py img.png")
        img_path = str(s[0])
    im = Image.open(img_path).convert("RGB")
    sess = ort.InferenceSession(MODEL, providers=["CPUExecutionProvider"])
    dets = detect(sess, im)
    print(f"{img_path}: {len(dets)} detections")
    d = ImageDraw.Draw(im)
    for name, conf, (x0, y0, x1, y1) in dets:
        print(f"  {name:12s} {conf:.2f}  [{x0:.0f},{y0:.0f},{x1:.0f},{y1:.0f}]")
        d.rectangle([x0, y0, x1, y1], outline=(0, 255, 120), width=2)
        d.text((x0 + 1, max(0, y0 - 10)), f"{name} {conf:.2f}", fill=(0, 255, 120))
    im.save("onnx_out.png")
    print("drew boxes -> onnx_out.png")


if __name__ == "__main__":
    main()
