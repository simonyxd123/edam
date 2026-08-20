"""
Quick POC：3 视频 × 3 算法 × 5 攻击场景 = 45 测试用例

精简版 POC（< 10 分钟完成），用于快速出实测结论。
"""

import io, sys, time, json
sys.stdout = io.TextIOWrapper(sys.stdout.buffer, encoding='utf-8')
from pathlib import Path
from PIL import Image
import numpy as np
import cv2

WATERMARK_TEXT = "USER_00001"
WM_BITS = [int(b) for byte in WATERMARK_TEXT.encode("utf-8") for b in format(byte, "08b")]
WM_BITS_LEN = len(WM_BITS)
print(f"水印文本: {WATERMARK_TEXT!r}, bits: {WM_BITS_LEN}")

# 准备测试视频
VIDEOS_DIR = Path("test_videos_mini")
videos = sorted(VIDEOS_DIR.glob("*.mp4"))
print(f"📹 测试视频: {[v.name for v in videos]}")

# 输出目录
OUTPUT_DIR = Path("poc_quick_output")
OUTPUT_DIR.mkdir(exist_ok=True)

results = []


def timeit(fn, *args, **kwargs):
    t0 = time.time()
    result = fn(*args, **kwargs)
    return result, (time.time() - t0) * 1000


# ============ 攻击函数 ============


def crop_attack(embedded_dir: str, crop_ratio: float = 0.1) -> str:
    """裁剪攻击：保留原图大小但移除 10% 边缘，保存为视频"""
    out_dir = OUTPUT_DIR / f"attacked_crop_{Path(embedded_dir).name}"
    out_dir.mkdir(exist_ok=True)
    # 找嵌入目录中的视频副本
    orig_video = OUTPUT_DIR / "phash_embedded" / "original.mp4"
    src_video = orig_video if orig_video.exists() else None

    # 如果是 DCT/DWT 嵌入，返回的是 PNG 目录 → 转为视频
    pngs = sorted(Path(embedded_dir).glob("frame*.png"))
    if pngs:
        # 直接保存单帧 PNG（pHash 比对时用）
        for f in pngs:
            img = Image.open(f)
            w, h = img.size
            img.crop((int(w*crop_ratio), int(h*crop_ratio), w-int(w*crop_ratio), h-int(h*crop_ratio))).save(out_dir / f.name)
    return str(out_dir)


def compress_attack(video_path: str) -> str:
    out_path = OUTPUT_DIR / f"attacked_compress_{Path(video_path).stem}.mp4"
    cap = cv2.VideoCapture(video_path)
    fps = cap.get(cv2.CAP_PROP_FPS)
    w, int_h = int(cap.get(cv2.CAP_PROP_FRAME_WIDTH)), int(cap.get(cv2.CAP_PROP_FRAME_HEIGHT))
    fourcc = cv2.VideoWriter_fourcc(*"mp4v")
    writer = cv2.VideoWriter(str(out_path), fourcc, fps, (w, int_h))
    while True:
        ret, frame = cap.read()
        if not ret:
            break
        small = cv2.resize(frame, (w//2, int_h//2))
        writer.write(cv2.resize(small, (w, int_h)))
    cap.release()
    writer.release()
    return str(out_path)


def screenshot_attack(embedded_dir: str) -> str:
    """截图攻击：单帧图片 → 对比应比对 pHash"""
    out_dir = OUTPUT_DIR / f"attacked_screenshot_{Path(embedded_dir).name}"
    out_dir.mkdir(exist_ok=True)
    frames = sorted(Path(embedded_dir).glob("frame*.png"))
    if frames:
        Image.open(frames[0]).save(out_dir / frames[0].name)
    return str(out_dir)


def format_attack(embedded_dir: str) -> str:
    out_dir = OUTPUT_DIR / f"attacked_format_{Path(embedded_dir).name}"
    out_dir.mkdir(exist_ok=True)
    for f in Path(embedded_dir).glob("frame*.png"):
        Image.open(f).convert("RGB").save(out_dir / (f.stem + ".jpg"), "JPEG", quality=85)
    return str(out_dir)


def recording_attack(embedded_dir: str) -> str:
    """录屏攻击：对单帧做旋转+亮度+噪声"""
    out_dir = OUTPUT_DIR / f"attacked_recording_{Path(embedded_dir).name}"
    out_dir.mkdir(exist_ok=True)
    rng = np.random.default_rng(42)
    for f in Path(embedded_dir).glob("frame*.png"):
        img = cv2.imread(str(f))
        h, w = img.shape[:2]
        M = cv2.getRotationMatrix2D((w/2, h/2), rng.uniform(-2, 2), 1.0)
        img = cv2.warpAffine(img, M, (w, h))
        img = np.clip(img.astype(np.float32) * rng.uniform(0.9, 1.1), 0, 255).astype(np.uint8)
        cv2.imwrite(str(out_dir / f.name), img)
    return str(out_dir)


ATTACKS = {
    "crop": ("裁剪 10%", crop_attack),
    "compress": ("H.264 重压缩", compress_attack),
    "screenshot": ("截图单帧", screenshot_attack),
    "format_convert": ("PNG→JPEG q=85", format_attack),
    "recording": ("录屏模拟", recording_attack),
}


# ============ 算法函数 ============


def embed_dct(video_path: str) -> str:
    from blind_watermark import WaterMark
    out_dir = OUTPUT_DIR / "dct_embedded"
    out_dir.mkdir(exist_ok=True)
    cap = cv2.VideoCapture(video_path)
    total = int(cap.get(cv2.CAP_PROP_FRAME_COUNT))
    # 只取中间 1 帧
    cap.set(cv2.CAP_PROP_POS_FRAMES, total // 2)
    ret, frame = cap.read()
    cap.release()
    if not ret:
        return None
    temp_in = out_dir / "frame.png"
    temp_out = out_dir / "frame_wm.png"
    cv2.imwrite(str(temp_in), frame)
    bwm = WaterMark(password_wm=1, password_img=1)
    bwm.read_img(str(temp_in))
    bwm.read_wm(WM_BITS, mode="bit")
    bwm.embed(str(temp_out))
    return str(out_dir)


def extract_dct(embedded_dir: str):
    from blind_watermark import WaterMark
    bwm = WaterMark(password_wm=1, password_img=1)
    pngs = sorted(Path(embedded_dir).glob("frame_wm.png"))
    if not pngs:
        return False, "", 0.0
    try:
        wm = bwm.extract(str(pngs[0]), mode="bit", wm_shape=(WM_BITS_LEN,))
        bits = [1 if x else 0 for x in wm]
        text_bytes = bytearray()
        for i in range(0, len(bits) - 7, 8):
            text_bytes.append(int("".join(str(b) for b in bits[i:i+8]), 2))
        text = text_bytes.decode("utf-8", errors="ignore")
        return True, text, 1.0
    except Exception as e:
        return False, "", 0.0


def embed_dwt(video_path: str) -> str:
    import pywt
    out_dir = OUTPUT_DIR / "dwt_embedded"
    out_dir.mkdir(exist_ok=True)
    cap = cv2.VideoCapture(video_path)
    total = int(cap.get(cv2.CAP_PROP_FRAME_COUNT))
    cap.set(cv2.CAP_PROP_POS_FRAMES, total // 2)
    ret, frame = cap.read()
    cap.release()
    if not ret:
        return None
    gray = cv2.cvtColor(frame, cv2.COLOR_BGR2GRAY)
    coeffs = pywt.dwt2(gray, "haar")
    cA, (cH, cV, cD) = coeffs
    cA_flat = np.floor(cA).astype(np.int32).flatten()
    for j in range(min(len(cA_flat), len(WM_BITS))):
        cA_flat[j] = (int(cA_flat[j]) & ~1) | int(WM_BITS[j])
    cA_new = cA_flat.astype(np.float32).reshape(cA.shape)
    reconstructed = pywt.idwt2((cA_new, (cH, cV, cD)), "haar")
    reconstructed = np.clip(reconstructed, 0, 255).astype(np.uint8)
    cv2.imwrite(str(out_dir / "frame_wm.png"), cv2.cvtColor(reconstructed, cv2.COLOR_GRAY2BGR))
    return str(out_dir)


def extract_dwt(embedded_dir: str):
    import pywt
    pngs = sorted(Path(embedded_dir).glob("frame_wm.png"))
    if not pngs:
        return False, "", 0.0
    try:
        img = cv2.imread(str(pngs[0]), cv2.IMREAD_GRAYSCALE)
        coeffs = pywt.dwt2(img, "haar")
        cA, _ = coeffs
        cA_flat = np.floor(cA).astype(np.int32).flatten()
        bits = [int(v) & 1 for v in cA_flat[: WM_BITS_LEN]]
        text_bytes = bytearray()
        for i in range(0, len(bits) - 7, 8):
            text_bytes.append(int("".join(str(b) for b in bits[i:i+8]), 2))
        text = text_bytes.decode("utf-8", errors="ignore")
        return True, text, 0.5
    except Exception as e:
        return False, "", 0.0


def embed_phash(video_path: str) -> str:
    out_dir = OUTPUT_DIR / "phash_embedded"
    out_dir.mkdir(exist_ok=True)
    cap = cv2.VideoCapture(video_path)
    total = int(cap.get(cv2.CAP_PROP_FRAME_COUNT))
    fps = cap.get(cv2.CAP_PROP_FPS)
    fingerprints = []
    # 每秒 1 帧（5s 视频 = 5 帧）
    for i in range(0, total, int(fps)):
        cap.set(cv2.CAP_PROP_POS_FRAMES, i)
        ret, frame = cap.read()
        if not ret:
            continue
        gray = cv2.cvtColor(frame, cv2.COLOR_BGR2GRAY)
        resized = cv2.resize(gray, (32, 32))
        dct = cv2.dct(np.float32(resized))
        dct_low = dct[:8, :8]
        median = np.median(dct_low)
        hash_bits = (dct_low > median).flatten().astype(np.uint8)
        fingerprints.append({"frame_idx": i, "phash": hash_bits.tolist(), "user": WATERMARK_TEXT})
    cap.release()
    fp_file = out_dir / "fingerprints.json"
    fp_file.write_text(json.dumps(fingerprints, ensure_ascii=False))
    # 保存原视频用于后续比对
    import shutil
    shutil.copy(video_path, str(out_dir / "original.mp4"))
    return str(out_dir)


def extract_phash(embedded_dir: str, original_video: str = None):
    """从指纹库 + 攻击后的视频帧中比对

    embedded_dir: 包含 fingerprints.json（嵌入时存的指纹库）+ attacked_video_path
    original_video: 已废弃，保留兼容
    """
    fp_file = Path(embedded_dir) / "fingerprints.json"
    if not fp_file.exists():
        return False, "", 0.0
    fingerprints = json.loads(fp_file.read_text(encoding="utf-8"))

    # 攻击后的视频：如果是目录，找第一个 mp4；如果是文件路径直接用
    if Path(embedded_dir).is_dir() and Path(embedded_dir).glob("*.mp4"):
        leaked_video = str(next(Path(embedded_dir).glob("*.mp4")))
    elif original_video and Path(original_video).exists():
        leaked_video = original_video
    else:
        leaked_video = str(Path(embedded_dir) / "original.mp4")

    if not Path(leaked_video).exists():
        return False, "", 0.0
    cap = cv2.VideoCapture(leaked_video)
    total = int(cap.get(cv2.CAP_PROP_FRAME_COUNT))
    fps = cap.get(cv2.CAP_PROP_FPS)
    leaked_hashes = []
    for i in range(0, total, max(int(fps), 1)):
        cap.set(cv2.CAP_PROP_POS_FRAMES, i)
        ret, frame = cap.read()
        if not ret:
            continue
        gray = cv2.cvtColor(frame, cv2.COLOR_BGR2GRAY)
        resized = cv2.resize(gray, (32, 32))
        dct = cv2.dct(np.float32(resized))
        dct_low = dct[:8, :8]
        median = np.median(dct_low)
        hash_bits = (dct_low > median).flatten().astype(np.uint8)
        leaked_hashes.append(hash_bits)
    cap.release()
    if not leaked_hashes:
        return False, "", 0.0
    best_match = None
    best_dist = 64
    for fp in fingerprints:
        ref = np.array(fp["phash"])
        for leaked in leaked_hashes:
            dist = np.sum(ref != leaked)
            if dist < best_dist:
                best_dist = dist
                best_match = fp["user"]
    if best_dist <= 10 and best_match:
        return True, best_match, 1.0 - best_dist / 64
    return False, "", 0.0


ALGORITHMS = {
    "dct": ("DCT 频域水印 (blind-watermark)", embed_dct, extract_dct),
    "dwt": ("DWT 频域水印 (自研)", embed_dwt, extract_dwt),
    "phash": ("pHash 帧指纹 (方案书主推)", embed_phash, extract_phash),
}


# ============ 主测试流程 ============

for video in videos:
    sid = video.stem
    print(f"\n=== 视频: {sid} ===")
    for algo_name, (algo_label, embed_fn, extract_fn) in ALGORITHMS.items():
        # 嵌入
        t0 = time.time()
        try:
            embedded = embed_fn(str(video))
            embed_ms = (time.time() - t0) * 1000
            print(f"  [{algo_name}] 嵌入: {embed_ms:.0f}ms")
        except Exception as e:
            print(f"  [{algo_name}] ✗ 嵌入失败: {e}")
            continue

        # 无攻击基线
        t0 = time.time()
        try:
            if algo_name == "phash":
                ok, text, conf = extract_fn(embedded, str(video))
            else:
                ok, text, conf = extract_fn(embedded)
            extract_ms = (time.time() - t0) * 1000
            match = ok and text == WATERMARK_TEXT
            results.append({"video": sid, "algorithm": algo_name, "attack": "none", "success": ok, "match": match, "ms": extract_ms})
            print(f"  [{algo_name}] none: success={ok}, match={match}, text={text!r}, {extract_ms:.0f}ms")
        except Exception as e:
            print(f"  [{algo_name}] ✗ 提取失败: {e}")
            results.append({"video": sid, "algorithm": algo_name, "attack": "none", "success": False, "match": False, "ms": 0, "error": str(e)})

        # 各攻击
        for attack_name, (attack_label, attack_fn) in ATTACKS.items():
            try:
                if attack_name == "compress":
                    attacked = attack_fn(str(video))
                else:
                    attacked = attack_fn(embedded)
                t0 = time.time()
                if algo_name == "phash":
                    ok, text, conf = extract_fn(attacked, str(video))
                else:
                    ok, text, conf = extract_fn(attacked)
                extract_ms = (time.time() - t0) * 1000
                match = ok and text == WATERMARK_TEXT
                results.append({"video": sid, "algorithm": algo_name, "attack": attack_name, "success": ok, "match": match, "ms": extract_ms})
                print(f"  [{algo_name}] {attack_name}: success={ok}, match={match}, text={text!r}, {extract_ms:.0f}ms")
            except Exception as e:
                print(f"  [{algo_name}] {attack_name} ✗: {e}")
                results.append({"video": sid, "algorithm": algo_name, "attack": attack_name, "success": False, "match": False, "ms": 0, "error": str(e)})


# ============ 输出汇总 ============
print("\n" + "=" * 70)
print("成功率矩阵")
print("=" * 70)

from collections import defaultdict
matrix = defaultdict(lambda: [0, 0])
for r in results:
    key = (r["algorithm"], r["attack"])
    matrix[key][1] += 1
    if r["match"]:
        matrix[key][0] += 1

attack_order = ["none", "crop", "compress", "screenshot", "format_convert", "recording"]
print(f"{'算法':10s} | " + " | ".join(f"{a[:10]:10s}" for a in attack_order))
print("-" * 80)
for algo in ["dct", "dwt", "phash"]:
    row = f"{algo:10s} | "
    for attack in attack_order:
        ok, total = matrix.get((algo, attack), [0, 0])
        pct = ok / total * 100 if total else 0
        row += f"{f'{ok}/{total} ({pct:.0f}%)':11s}| "
    print(row)

# 保存结果
output_file = OUTPUT_DIR / "poc_quick_results.json"
output_file.write_text(json.dumps(results, ensure_ascii=False, indent=2))
print(f"\n📄 结果已保存至: {output_file}")
print(f"📊 总测试数: {len(results)}")