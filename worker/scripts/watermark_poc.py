"""
频域盲水印鲁棒性 POC 脚本
========================

对比三种视频水印/指纹方案在 5 种攻击场景下的鲁棒性：
1. DCT 频域水印（基于 blind-watermark 库）
2. DWT 频域水印（自研实现）
3. pHash 帧指纹（基于感知哈希，方案书 4.4 节主推）

测试矩阵：10 视频 × 3 算法 × 5 攻击场景 = 150 个测试用例

使用方法：
    python watermark_poc.py --videos-dir test_videos/ --output-dir poc_output/
    python watermark_poc.py --videos-dir test_videos/ --algorithm dct --attack compress

对应方案书 4.4 节「视频帧指纹与频域水印（终极防线）」
"""

import argparse
import json
import os
import sys
import time
from dataclasses import dataclass, asdict, field
from pathlib import Path
from typing import Optional

import cv2
import numpy as np
from PIL import Image

log_level = os.environ.get("LOG_LEVEL", "INFO")


@dataclass
class WatermarkMethod:
    """水印算法元信息"""

    name: str
    description: str
    robustness_known: str  # 已知鲁棒性（来自文献）


@dataclass
class AttackScenario:
    """攻击场景"""

    name: str
    description: str
    apply_fn: str  # 函数名（运行时绑定）


@dataclass
class TestSample:
    """测试样本"""

    video_id: str
    video_path: str
    codec: str  # H.264 / H.265
    bitrate_kbps: int
    resolution: str  # e.g. "1920x1080"
    duration_sec: float
    fps: float
    has_text_overlay: bool


@dataclass
class PocResult:
    """单次测试结果"""

    sample_id: str
    algorithm: str
    attack: str
    extract_success: bool
    extracted_text: Optional[str]
    match_score: float  # 0-1，与原水印相似度
    confidence: float  # 算法自身报告的置信度
    cost_ms: float
    notes: str = ""


# ========== 三种水印算法实现 ==========

def embed_dct(video_path: str, wm_text: str, output_dir: str, frame_interval: int = 10) -> str:
    """
    DCT 频域水印（图片维度的 DCT 嵌入到关键帧）

    原理：在视频关键帧的 DCT 系数中嵌入文本水印
    库：blind-watermark (0.4.1)

    frame_interval：每隔多少帧取一关键帧嵌入（默认 10）
    """
    from blind_watermark import WaterMark

    out_dir = Path(output_dir)
    out_dir.mkdir(parents=True, exist_ok=True)

    bwm = WaterMark(password_wm=1, password_img=1)
    # blind-watermark 0.4.1 API：mode in ('img','str','bit')
    # bytes 模式已被弃用，改用 bit 模式
    wm_bits = [int(b) for byte in wm_text.encode("utf-8") for b in format(byte, "08b")]

    cap = cv2.VideoCapture(video_path)
    fps = cap.get(cv2.CAP_PROP_FPS)
    total_frames = int(cap.get(cv2.CAP_PROP_FRAME_COUNT))

    # 提取关键帧（限制最多 5 帧，控制总耗时）
    key_frames = []
    step = max(frame_interval, total_frames // 5)
    for i in range(0, total_frames, step):
        cap.set(cv2.CAP_PROP_POS_FRAMES, i)
        ret, frame = cap.read()
        if ret:
            key_frames.append((i, frame))
            if len(key_frames) >= 5:
                break
    cap.release()

    # 嵌入到每个关键帧
    embedded_dir = out_dir / "dct_embedded_frames"
    embedded_dir.mkdir(exist_ok=True)
    for idx, frame in key_frames:
        temp_in = embedded_dir / f"frame_{idx}_in.png"
        temp_out = embedded_dir / f"frame_{idx}_out.png"
        cv2.imwrite(str(temp_in), frame)
        bwm.read_img(str(temp_in))
        bwm.read_wm(wm_bits, mode="bit")
        bwm.embed(str(temp_out))

    # 保存 wm_bits 长度供 extract 使用（extract 需要 wm_shape 参数）
    import json as _json
    (embedded_dir / "meta.json").write_text(
        _json.dumps({"wm_bits_len": len(wm_bits)}, ensure_ascii=False)
    )

    return str(embedded_dir)


def extract_dct(embedded_dir: str) -> tuple[bool, str, float]:
    """从 DCT 水印帧中提取"""
    from blind_watermark import WaterMark
    import json as _json

    bwm = WaterMark(password_wm=1, password_img=1)
    out_dir = Path(embedded_dir)

    # 取第一个帧做提取测试（实际生产应取所有帧投票）
    pngs = sorted(out_dir.glob("frame_*_out.png"))
    if not pngs:
        return False, "", 0.0

    meta_path = out_dir / "meta.json"
    wm_bits_len = 88  # 默认 USER_00001 = 11 字节 = 88 bit
    if meta_path.exists():
        try:
            wm_bits_len = _json.loads(meta_path.read_text()).get("wm_bits_len", wm_bits_len)
        except Exception:
            pass

    try:
        # blind-watermark 0.4.1 返回 numpy bool array，需要转 0/1
        wm = bwm.extract(str(pngs[0]), mode="bit", wm_shape=(wm_bits_len,))
        if hasattr(wm, '__len__') and len(wm) > 0:
            # numpy bool array -> list of int
            bits = [1 if x else 0 for x in wm]
            text_bytes = bytearray()
            for i in range(0, len(bits) - 7, 8):
                byte_bits = bits[i:i+8]
                if len(byte_bits) < 8:
                    break
                text_bytes.append(int("".join(str(b) for b in byte_bits), 2))
            try:
                text = text_bytes.decode("utf-8", errors="ignore")
                return True, text, 1.0
            except Exception:
                return False, "", 0.0
        return False, "", 0.0
    except Exception as e:
        return False, "", 0.0


def embed_dwt(video_path: str, wm_text: str, output_dir: str, frame_interval: int = 10) -> str:
    """
    DWT 频域水印（自研实现）

    原理：在视频关键帧做 DWT 分解，在低频子带嵌入水印
    使用 PyWavelets 库
    """
    try:
        import pywt
    except ImportError:
        raise ImportError("PyWavelets not installed. Run: pip install pywavelets")

    out_dir = Path(output_dir)
    out_dir.mkdir(parents=True, exist_ok=True)

    cap = cv2.VideoCapture(video_path)
    total_frames = int(cap.get(cv2.CAP_PROP_FRAME_COUNT))

    embedded_dir = out_dir / "dwt_embedded_frames"
    embedded_dir.mkdir(exist_ok=True)

    # 水印文本 -> bit 数组
    bits = []
    for b in wm_text.encode("utf-8"):
        bits.extend([int(x) for x in format(b, "08b")])
    bits = np.array(bits, dtype=np.float32)

    bit_idx = 0
    for i in range(0, total_frames, frame_interval):
        cap.set(cv2.CAP_PROP_POS_FRAMES, i)
        ret, frame = cap.read()
        if not ret:
            continue
        gray = cv2.cvtColor(frame, cv2.COLOR_BGR2GRAY)

        # DWT 分解
        coeffs = pywt.dwt2(gray, "haar")
        cA, (cH, cV, cD) = coeffs

        # 在低频子带 cA 嵌入
        if bit_idx < len(bits):
            # 简单 LSB 替换（仅做 POC）
            # 注意：cA 是 float32，bitwise_and 需要 int 类型
            cA_flat = np.floor(cA).astype(np.int32).flatten()
            for j in range(min(len(cA_flat), len(bits) - bit_idx)):
                cA_flat[j] = (int(cA_flat[j]) & ~1) | int(bits[bit_idx + j])
                if bit_idx + j >= len(bits) - 1:
                    break
            bit_idx += len(cA_flat)
            cA_new = cA_flat.astype(np.float32).reshape(cA.shape)

            # DWT 重构
            reconstructed = pywt.idwt2((cA_new, (cH, cV, cD)), "haar")
            reconstructed = np.clip(reconstructed, 0, 255).astype(np.uint8)
            out_frame = cv2.cvtColor(reconstructed, cv2.COLOR_GRAY2BGR)
            cv2.imwrite(str(embedded_dir / f"frame_{i}_dwt.png"), out_frame)

    cap.release()
    return str(embedded_dir)


def extract_dwt(embedded_dir: str, original_video: str, wm_text: str) -> tuple[bool, str, float]:
    """从 DWT 水印帧中提取"""
    try:
        import pywt
    except ImportError:
        return False, "", 0.0

    out_dir = Path(embedded_dir)
    embedded = sorted(out_dir.glob("frame_*_dwt.png"))
    if not embedded:
        return False, "", 0.0

    # 简化：提取第一个帧的 bit
    img = cv2.imread(str(embedded[0]), cv2.IMREAD_GRAYSCALE)
    coeffs = pywt.dwt2(img, "haar")
    cA, _ = coeffs
    cA_flat = np.floor(cA).astype(np.int32).flatten()

    bits = []
    for v in cA_flat[: len(wm_text) * 8]:
        bits.append(int(v) & 1)

    # bits -> bytes
    if len(bits) < len(wm_text) * 8:
        return False, "", 0.0

    text_bytes = bytearray()
    for i in range(0, len(bits), 8):
        byte_bits = bits[i : i + 8]
        if len(byte_bits) < 8:
            break
        text_bytes.append(int("".join(str(b) for b in byte_bits), 2))

    try:
        text = text_bytes.decode("utf-8", errors="ignore")
        return True, text, 0.5  # DWT 置信度本就低于 DCT
    except Exception:
        return False, "", 0.0


def embed_phash(video_path: str, wm_text: str, output_dir: str, frame_interval: int = 30) -> str:
    """
    pHash 帧指纹（方案书 4.4 节主推）

    原理：对每个视频帧计算感知哈希（pHash），存入指纹库；
    溯源时对泄露视频逐帧计算 pHash，在库中查找最近邻匹配，定位观看者
    """
    out_dir = Path(output_dir)
    out_dir.mkdir(parents=True, exist_ok=True)

    cap = cv2.VideoCapture(video_path)
    total_frames = int(cap.get(cv2.CAP_PROP_FRAME_COUNT))

    fingerprints = []
    for i in range(0, total_frames, frame_interval):
        cap.set(cv2.CAP_PROP_POS_FRAMES, i)
        ret, frame = cap.read()
        if not ret:
            continue
        # pHash 计算：缩放到 32x32 → DCT → 取左上 8x8 → 中位数为阈值
        gray = cv2.cvtColor(frame, cv2.COLOR_BGR2GRAY)
        resized = cv2.resize(gray, (32, 32))
        dct = cv2.dct(np.float32(resized))
        dct_low = dct[:8, :8]
        median = np.median(dct_low)
        hash_bits = (dct_low > median).flatten().astype(np.uint8)
        fingerprints.append({"frame_idx": i, "phash": hash_bits.tolist(), "user": wm_text})

    cap.release()

    out_file = out_dir / "phash_fingerprints.json"
    out_file.write_text(json.dumps(fingerprints, ensure_ascii=False))
    return str(out_file)


def extract_phash(embedded_file: str, leaked_video: str) -> tuple[bool, str, float]:
    """从泄露视频提取 pHash 并与库匹配"""
    fingerprints = json.loads(Path(embedded_file).read_text(encoding="utf-8"))
    if not fingerprints:
        return False, "", 0.0

    cap = cv2.VideoCapture(leaked_video)
    total_frames = int(cap.get(cv2.CAP_PROP_FRAME_COUNT))

    leaked_hashes = []
    for i in range(0, total_frames, 30):
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

    # 汉明距离找最近邻
    best_match = None
    best_distance = 64  # 最大 64 bit
    for fp in fingerprints:
        ref = np.array(fp["phash"])
        for leaked in leaked_hashes:
            dist = np.sum(ref != leaked)
            if dist < best_distance:
                best_distance = dist
                best_match = fp["user"]

    # 阈值：汉明距离 ≤ 10 视为匹配（≈ 85% 相似度）
    if best_distance <= 10 and best_match:
        confidence = 1.0 - best_distance / 64
        return True, best_match, confidence

    return False, "", 0.0


# ========== 5 种攻击场景 ==========

def attack_crop(embedded_dir: str, crop_ratio: float = 0.1) -> str:
    """攻击 1：裁剪（移除图片边缘）"""
    out_dir = Path(embedded_dir).parent / "attacked_crop"
    out_dir.mkdir(exist_ok=True)
    for f in Path(embedded_dir).glob("*.png"):
        img = Image.open(f)
        w, h = img.size
        left = int(w * crop_ratio)
        top = int(h * crop_ratio)
        right = w - left
        bottom = h - top
        cropped = img.crop((left, top, right, bottom))
        cropped.save(out_dir / f.name.name)
    return str(out_dir)


def attack_compress(video_path: str, crf: int = 35) -> str:
    """攻击 2：H.264 重压缩（模拟录屏/转发）

    优先用 FFmpeg，缺失时用 OpenCV 重编码（mp4v）。
    """
    out_path = Path(video_path).parent / f"attacked_compress_{Path(video_path).stem}.mp4"

    # 尝试 FFmpeg
    import subprocess
    cmd = [
        "ffmpeg", "-y", "-i", str(video_path),
        "-c:v", "libx264", "-crf", str(crf),
        "-c:a", "copy",
        str(out_path),
    ]
    try:
        subprocess.run(cmd, check=True, capture_output=True, timeout=60)
        return str(out_path)
    except (subprocess.CalledProcessError, FileNotFoundError, subprocess.TimeoutExpired):
        # fallback：OpenCV 重编码（mp4v 是 MPEG-4 Visual，对 DCT 也有破坏效果）
        pass

    # OpenCV fallback
    cap = cv2.VideoCapture(str(video_path))
    fps = cap.get(cv2.CAP_PROP_FPS)
    w = int(cap.get(cv2.CAP_PROP_FRAME_WIDTH))
    h = int(cap.get(cv2.CAP_PROP_FRAME_HEIGHT))

    # 提高压缩：缩小到 50% 再编码
    fourcc = cv2.VideoWriter_fourcc(*"mp4v")
    writer = cv2.VideoWriter(str(out_path), fourcc, fps, (w, h))
    while True:
        ret, frame = cap.read()
        if not ret:
            break
        # 模拟重压缩：降低质量（resize 一下）
        small = cv2.resize(frame, (w // 2, h // 2))
        restored = cv2.resize(small, (w, h))
        writer.write(restored)
    cap.release()
    writer.release()
    return str(out_path)


def attack_screenshot(embedded_dir: str) -> str:
    """攻击 3：截图（取单帧当作图片泄露）"""
    out_dir = Path(embedded_dir).parent / "attacked_screenshot"
    out_dir.mkdir(exist_ok=True)
    frames = sorted(Path(embedded_dir).glob("*.png"))
    if frames:
        img = Image.open(frames[0])
        img.save(out_dir / frames[0].name)
    return str(out_dir)


def attack_format_convert(embedded_dir: str, target_format: str = "JPEG") -> str:
    """攻击 4：格式转换（PNG → JPEG，有损重编码）"""
    out_dir = Path(embedded_dir).parent / "attacked_format"
    out_dir.mkdir(exist_ok=True)
    for f in Path(embedded_dir).glob("*.png"):
        img = Image.open(f).convert("RGB")
        out_name = f.stem + ".jpg"
        img.save(out_dir / out_name, format=target_format, quality=85)
    return str(out_dir)


def attack_recording(embedded_dir: str) -> str:
    """攻击 5：录屏攻击（手机/电脑录屏后重新拍照）

    模拟：对每帧做轻微旋转（±2°）+ 亮度调整 + 添加椒盐噪声
    """
    out_dir = Path(embedded_dir).parent / "attacked_recording"
    out_dir.mkdir(exist_ok=True)
    for f in Path(embedded_dir).glob("*.png"):
        img = cv2.imread(str(f))
        # 旋转
        h, w = img.shape[:2]
        M = cv2.getRotationMatrix2D((w / 2, h / 2), np.random.uniform(-2, 2), 1.0)
        img = cv2.warpAffine(img, M, (w, h))
        # 亮度
        img = np.clip(img.astype(np.float32) * np.random.uniform(0.9, 1.1), 0, 255).astype(np.uint8)
        # 噪声
        noise = np.random.randint(-5, 5, img.shape, dtype=np.int8)
        img = np.clip(img.astype(np.int16) + noise, 0, 255).astype(np.uint8)
        cv2.imwrite(str(out_dir / f.name), img)
    return str(out_dir)


# ========== POC 主流程 ==========

ATTACK_FUNCTIONS = {
    "crop": attack_crop,
    "compress": attack_compress,
    "screenshot": attack_screenshot,
    "format_convert": attack_format_convert,
    "recording": attack_recording,
}

ALGORITHM_FUNCTIONS = {
    "dct": ("DCT 频域水印 (blind-watermark)", embed_dct, extract_dct),
    "dwt": ("DWT 频域水印 (自研)", embed_dwt, extract_dwt),
    "phash": ("pHash 帧指纹 (方案书主推)", embed_phash, extract_phash),
}


def extract_with_args(algo_name: str, embedded: str, original_video: str = None):
    """统一 extract 调用，处理 phash 等需要额外参数的算法"""
    _, _, extract_fn = ALGORITHM_FUNCTIONS[algo_name]
    if algo_name == "phash":
        # pHash 需要原始视频做比对
        return extract_fn(embedded, original_video or str(Path(embedded).parent / "original.mp4"))
    return extract_fn(embedded)


def run_poc(
    videos_dir: str,
    output_dir: str,
    wm_text: str = "USER_00001",
    algorithms: list = None,
    attacks: list = None,
) -> list[PocResult]:
    """
    运行 POC 测试

    Args:
        videos_dir: 测试视频目录
        output_dir: 输出目录
        wm_text: 嵌入的水印文本（模拟用户 ID）
        algorithms: 要测试的算法列表，默认全部
        attacks: 要测试的攻击场景列表，默认全部
    """
    if algorithms is None:
        algorithms = list(ALGORITHM_FUNCTIONS.keys())
    if attacks is None:
        attacks = list(ATTACK_FUNCTIONS.keys())

    output_path = Path(output_dir)
    output_path.mkdir(parents=True, exist_ok=True)

    videos = sorted(Path(videos_dir).glob("*.mp4"))
    if not videos:
        print(f"❌ 在 {videos_dir} 下未找到 .mp4 视频")
        print("提示：可使用 ffmpeg 生成测试视频：")
        print("  ffmpeg -f lavfi -i testsrc=size=1920x1080:rate=30:duration=10 -c:v libx264 test_01.mp4")
        return []

    print(f"📹 找到 {len(videos)} 个测试视频")
    print(f"🔬 测试算法: {algorithms}")
    print(f"⚔️  攻击场景: {attacks}")
    print(f"📝 水印文本: {wm_text!r}")
    print()

    results = []
    total = len(videos) * len(algorithms) * (1 + len(attacks))
    current = 0

    for video in videos:
        sample_id = video.stem
        for algo_name in algorithms:
            # 嵌入
            algo_label, embed_fn, extract_fn = ALGORITHM_FUNCTIONS[algo_name]
            print(f"▶ [{algo_name}] 嵌入 {sample_id}...")
            try:
                embedded = embed_fn(str(video), wm_text, str(output_path / "embedded"))
                # pHash 需要原视频路径用于提取比对
                if algo_name == "phash":
                    import shutil
                    orig_link = output_path / "embedded" / "original.mp4"
                    if not orig_link.exists():
                        shutil.copy(str(video), str(orig_link))
                print(f"  ✓ 嵌入完成: {embedded}")
            except Exception as e:
                print(f"  ✗ 嵌入失败: {e}")
                continue

            # 无攻击基线
            current += 1
            t0 = time.time()
            ok, text, conf = extract_with_args(algo_name, embedded, str(video))
            cost = (time.time() - t0) * 1000
            results.append(PocResult(
                sample_id=sample_id,
                algorithm=algo_name,
                attack="none",
                extract_success=ok,
                extracted_text=text,
                match_score=1.0 if ok and text == wm_text else 0.0,
                confidence=conf,
                cost_ms=cost,
            ))

            # 各攻击场景
            for attack_name in attacks:
                current += 1
                attack_fn = ATTACK_FUNCTIONS[attack_name]
                print(f"  ⚔  [{current}/{total}] 攻击: {attack_name}")
                try:
                    if attack_name == "compress":
                        attacked = attack_fn(str(video))
                    else:
                        attacked = attack_fn(embedded)
                    t0 = time.time()
                    ok, text, conf = extract_with_args(algo_name, attacked, str(video))
                    cost = (time.time() - t0) * 1000
                    results.append(PocResult(
                        sample_id=sample_id,
                        algorithm=algo_name,
                        attack=attack_name,
                        extract_success=ok,
                        extracted_text=text,
                        match_score=1.0 if ok and text == wm_text else 0.0,
                        confidence=conf,
                        cost_ms=cost,
                    ))
                except Exception as e:
                    print(f"    ✗ 攻击/提取失败: {e}")
                    results.append(PocResult(
                        sample_id=sample_id,
                        algorithm=algo_name,
                        attack=attack_name,
                        extract_success=False,
                        extracted_text=None,
                        match_score=0.0,
                        confidence=0.0,
                        cost_ms=0.0,
                        notes=str(e),
                    ))

    return results


def save_results(results: list[PocResult], output_dir: str):
    """保存结果到 JSON 与 Markdown"""
    output_path = Path(output_dir)
    output_path.mkdir(parents=True, exist_ok=True)

    # JSON
    json_path = output_path / "poc_results.json"
    json_path.write_text(
        json.dumps([asdict(r) for r in results], ensure_ascii=False, indent=2),
        encoding="utf-8",
    )

    # Markdown 汇总
    md_path = output_path / "poc_results.md"
    lines = ["# POC 实测结果汇总\n"]
    lines.append(f"- 测试视频数: {len(set(r.sample_id for r in results))}\n")
    lines.append(f"- 测试算法: {set(r.algorithm for r in results)}\n")
    lines.append(f"- 攻击场景: {set(r.attack for r in results)}\n")
    lines.append(f"- 总测试数: {len(results)}\n\n")

    # 按算法+攻击统计成功率
    from collections import defaultdict

    success_rate = defaultdict(lambda: [0, 0])
    for r in results:
        key = (r.algorithm, r.attack)
        success_rate[key][1] += 1
        if r.extract_success:
            success_rate[key][0] += 1

    lines.append("## 成功率矩阵\n\n")
    lines.append("| 算法 | 攻击场景 | 成功率 |\n")
    lines.append("| --- | --- | --- |\n")
    for (algo, attack), (ok, total) in sorted(success_rate.items()):
        pct = ok / total * 100 if total else 0
        lines.append(f"| {algo} | {attack} | {ok}/{total} ({pct:.0f}%) |\n")

    md_path.write_text("".join(lines), encoding="utf-8")
    print(f"📄 结果已保存至 {json_path} 与 {md_path}")


def main():
    parser = argparse.ArgumentParser(description="频域盲水印鲁棒性 POC")
    parser.add_argument("--videos-dir", required=True, help="测试视频目录")
    parser.add_argument("--output-dir", default="poc_output", help="输出目录")
    parser.add_argument("--wm-text", default="USER_00001", help="水印文本")
    parser.add_argument("--algorithm", choices=list(ALGORITHM_FUNCTIONS.keys()), help="单个算法")
    parser.add_argument("--attack", choices=list(ATTACK_FUNCTIONS.keys()), help="单个攻击")
    args = parser.parse_args()

    algorithms = [args.algorithm] if args.algorithm else None
    attacks = [args.attack] if args.attack else None

    results = run_poc(
        videos_dir=args.videos_dir,
        output_dir=args.output_dir,
        wm_text=args.wm_text,
        algorithms=algorithms,
        attacks=attacks,
    )

    if results:
        save_results(results, args.output_dir)
        print(f"\n✅ POC 完成，共 {len(results)} 个测试用例")


if __name__ == "__main__":
    main()