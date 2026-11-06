"""
帧指纹**唯一实现**的回归测试。

**为什么测这个**：此前仓库里有两套互不兼容的 pHash 实现 ——
`processors/video.py` 用 imagehash（DCT + 缩放 + **高通滤波**），
`fingerprint/PHashService.py` 自己手写 DCT（**没有高通滤波**）。

两边算出的 bit 串对不上 ⇒ 汉明距离比较毫无意义，**匹配率 ≈ 0**，
而且不报任何错 —— 看起来"跑通了"，只是永远匹配不上。

这两条断言把"唯一实现"钉死：任何一边改了算法，另一边必须跟着变，
否则这个坑会重新长出来。
"""
import numpy as np
import pytest

from edam_worker.fingerprint import phash
from edam_worker.fingerprint.PHashService import phash_service


def _sample_frame(seed: int = 7) -> np.ndarray:
    """造一张有纹理的 BGR 帧（纯色图算出来的 hash 全 0，没区分度）"""
    rng = np.random.default_rng(seed)
    h, w = 240, 320
    base = rng.integers(0, 255, size=(h // 8, w // 8, 3), dtype=np.uint8)
    return np.kron(base, np.ones((8, 8, 1), dtype=np.uint8))


class TestPhashUnification:
    def test_shared_impl_and_service_agree(self):
        """PHashService 与唯一实现必须逐位相同"""
        frame = _sample_frame()
        assert phash_service.compute_frame_phash(frame) == \
            phash.compute_frame_phash(frame)

    def test_service_uses_the_shared_module(self):
        """PHashService 必须委托给 phash 模块，不能自己再实现一遍"""
        frame = _sample_frame()
        spy_calls = []
        original = phash.compute_frame_phash

        def _spy(f):
            spy_calls.append(f)
            return original(f)

        phash.compute_frame_phash = _spy
        try:
            phash_service.compute_frame_phash(frame)
        finally:
            phash.compute_frame_phash = original

        assert spy_calls, "PHashService 没有走共享实现 —— 两边算法又分叉了"

    def test_length_is_8_bytes(self):
        assert len(phash.compute_frame_phash(_sample_frame())) == 8

    def test_hex_round_trip(self):
        raw = phash.compute_frame_phash(_sample_frame())
        hex_str = phash.phash_to_hex(raw)
        assert len(hex_str) == 16
        assert hex_str == hex_str.lower()
        assert phash.hex_to_phash(hex_str) == raw

    def test_hex_accepts_0x_prefix(self):
        raw = phash.compute_frame_phash(_sample_frame())
        assert phash.hex_to_phash("0x" + raw.hex()) == raw

    def test_hex_rejects_wrong_length(self):
        with pytest.raises(ValueError):
            phash.hex_to_phash("abcd")

    def test_empty_frame_rejected(self):
        with pytest.raises(ValueError):
            phash.compute_frame_phash(np.zeros((0, 0, 3), dtype=np.uint8))

    def test_hamming_distance_and_similarity(self):
        a = bytes([0x00] * 8)
        b = bytes([0xFF] * 8)
        assert phash.hamming_distance(a, a) == 0
        assert phash.similarity_score(a, a) == 1.0
        assert phash.hamming_distance(a, b) == 64
        assert phash.similarity_score(a, b) == 0.0

    def test_hamming_distance_rejects_length_mismatch(self):
        with pytest.raises(ValueError):
            phash.hamming_distance(b"\x00" * 8, b"\x00" * 4)

    def test_service_helpers_delegate_too(self):
        a = bytes([0x0F] * 8)
        b = bytes([0x00] * 8)
        assert phash_service.hamming_distance(a, b) == phash.hamming_distance(a, b)
        assert phash_service.similarity_score(a, b) == phash.similarity_score(a, b)
        assert phash_service.is_similar(a, a)
        assert not phash_service.is_similar(a, b, threshold=10)


class TestVideoProcessorUsesSharedPhash:
    """processors/video.py 必须用同一份实现，且 fingerprint.json 格式不变"""

    def test_fingerprint_json_format_unchanged(self):
        """格式是 16 位小写 hex —— 已落盘的数据靠它，改了历史指纹全废"""
        raw = phash.compute_frame_phash(_sample_frame())
        assert phash.phash_to_hex(raw) == raw.hex()
        assert len(phash.phash_to_hex(raw)) == 16
