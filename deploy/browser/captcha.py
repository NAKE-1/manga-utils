"""
MangaFire shape-captcha solver (Python port of the JVM CaptchaSolver / onnx_infer.py — SAME math:
letterbox 640 / gray 114 / RGB / ÷255, conf>=0.25, class-agnostic NMS IoU 0.45, then the §7 A->B match:
A left-to-right = click order; for each A shape consume an unused B instance of the same class -> its box
centre is the click point (in B's ORIGINAL pixel space)).
"""
import base64
import io
import json
import os

import numpy as np
import onnxruntime as ort
from PIL import Image

SIZE = 640
CONF = 0.25
IOU = 0.45
PAD = 114

_here = os.path.dirname(__file__)
NAMES = json.loads(open(os.path.join(_here, "classes.json")).read())
_sess = ort.InferenceSession(os.path.join(_here, "best.onnx"), providers=["CPUExecutionProvider"])
_input = _sess.get_inputs()[0].name


def decode_data_uri(uri: str) -> Image.Image:
    """data:image/...;base64,xxx (or bare base64) -> RGB. A (the order strip) is a transparent PNG the
    model was trained on composited on BLACK, so flatten alpha onto black; B (JPEG) has no alpha."""
    raw = base64.b64decode(uri.split(",", 1)[-1])
    im = Image.open(io.BytesIO(raw))
    if im.mode in ("RGBA", "LA") or (im.mode == "P" and "transparency" in im.info):
        rgba = im.convert("RGBA")
        bg = Image.new("RGB", rgba.size, (0, 0, 0))
        bg.paste(rgba, (0, 0), rgba)
        return bg
    return im.convert("RGB")


def _letterbox(im):
    w0, h0 = im.size
    r = min(SIZE / w0, SIZE / h0)
    nw, nh = round(w0 * r), round(h0 * r)
    canvas = Image.new("RGB", (SIZE, SIZE), (PAD, PAD, PAD))
    px, py = (SIZE - nw) // 2, (SIZE - nh) // 2
    canvas.paste(im.resize((nw, nh)), (px, py))
    return canvas, r, px, py


def _iou_batch(box, boxes):
    x1 = np.maximum(box[0], boxes[:, 0]); y1 = np.maximum(box[1], boxes[:, 1])
    x2 = np.minimum(box[2], boxes[:, 2]); y2 = np.minimum(box[3], boxes[:, 3])
    inter = np.clip(x2 - x1, 0, None) * np.clip(y2 - y1, 0, None)
    a = (box[2] - box[0]) * (box[3] - box[1])
    b = (boxes[:, 2] - boxes[:, 0]) * (boxes[:, 3] - boxes[:, 1])
    return inter / (a + b - inter + 1e-9)


def _nms(boxes, scores, iou_thr):
    order = scores.argsort()[::-1]
    keep = []
    while len(order):
        i = order[0]; keep.append(i)
        if len(order) == 1:
            break
        rest = order[1:]
        order = rest[_iou_batch(boxes[i], boxes[rest]) < iou_thr]
    return keep


def _detect(im):
    """-> list of (name, cx, cy) in the ORIGINAL image's pixel space, with full boxes kept for sorting."""
    canvas, r, px, py = _letterbox(im)
    x = (np.asarray(canvas, dtype=np.float32) / 255.0).transpose(2, 0, 1)[None]
    out = _sess.run(None, {_input: np.ascontiguousarray(x)})[0]
    p = out[0].T
    boxes = p[:, :4]
    scores_all = p[:, 4:]
    cls = scores_all.argmax(1)
    conf = scores_all.max(1)
    m = conf >= CONF
    boxes, cls = boxes[m], cls[m]
    conf = conf[m]
    if len(boxes) == 0:
        return []
    xy = np.empty_like(boxes)
    xy[:, 0] = boxes[:, 0] - boxes[:, 2] / 2
    xy[:, 1] = boxes[:, 1] - boxes[:, 3] / 2
    xy[:, 2] = boxes[:, 0] + boxes[:, 2] / 2
    xy[:, 3] = boxes[:, 1] + boxes[:, 3] / 2
    xy[:, [0, 2]] = (xy[:, [0, 2]] - px) / r
    xy[:, [1, 3]] = (xy[:, [1, 3]] - py) / r
    keep = _nms(xy, conf, IOU)
    return [(NAMES[cls[i]] if cls[i] < len(NAMES) else str(cls[i]), xy[i]) for i in keep]


def solve(a_img, b_img):
    """Returns (clicks, missing): clicks = ordered (cx, cy) in B pixel space; missing = unmatched A shapes."""
    a = _detect(a_img)
    b = _detect(b_img)
    a_sorted = sorted(a, key=lambda d: (d[1][0] + d[1][2]) / 2)                                  # A left-to-right = order
    b_sorted = sorted(b, key=lambda d: (int(((d[1][1] + d[1][3]) / 2) / 40 + 0.5), (d[1][0] + d[1][2]) / 2))  # top-row then left
    used = [False] * len(b_sorted)
    clicks, missing = [], []
    for name, _ in a_sorted:
        idx = next((i for i in range(len(b_sorted)) if not used[i] and b_sorted[i][0] == name), None)
        if idx is None:
            missing.append(name)
            continue
        used[idx] = True
        bx = b_sorted[idx][1]
        clicks.append(((bx[0] + bx[2]) / 2.0, (bx[1] + bx[3]) / 2.0))
    return clicks, missing
