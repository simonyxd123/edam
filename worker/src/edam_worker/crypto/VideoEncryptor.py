"""
HLS 视频 SM4 国密加密（v3.3 W-2.3）

按密级自动选择算法：
- L1/L2: AES-128-CBC（国际算法，OpenSSL）
- L3/L4: SM4-CBC（国密算法，BouncyCastle 或商用 SDK）

调用示例：
    encryptor = VideoEncryptor(classification_lv="L3")
    key = encryptor.generate_key()
    iv = encryptor.generate_iv()
    encrypted = encryptor.encrypt(video_bytes, key, iv)
"""

import os
import logging
import hashlib
import hmac
import struct
from abc import ABC, abstractmethod
from typing import Tuple, Optional

logger = logging.getLogger(__name__)


class EncryptionAlgorithm(ABC):
    """加密算法抽象基类"""

    @abstractmethod
    def encrypt(self, plaintext: bytes, key: bytes, iv: bytes) -> bytes:
        pass

    @abstractmethod
    def decrypt(self, ciphertext: bytes, key: bytes, iv: bytes) -> bytes:
        pass


class AesCbcAlgorithm(EncryptionAlgorithm):
    """AES-128-CBC 国际算法（fallback）"""

    KEY_SIZE = 16  # 128 bit
    BLOCK_SIZE = 16

    def __init__(self):
        try:
            from cryptography.hazmat.primitives.ciphers import Cipher, algorithms, modes
            from cryptography.hazmat.backends import default_backend
            self._cipher_module = (Cipher, algorithms, modes, default_backend)
        except ImportError:
            self._cipher_module = None
            logger.warning("cryptography not installed, AES unavailable")

    def encrypt(self, plaintext: bytes, key: bytes, iv: bytes) -> bytes:
        Cipher, algorithms, modes, default_backend = self._cipher_module
        cipher = Cipher(algorithms.AES(key), modes.CBC(iv), backend=default_backend())
        encryptor = cipher.encryptor()
        # PKCS7 padding
        pad_len = self.BLOCK_SIZE - (len(plaintext) % self.BLOCK_SIZE)
        padded = plaintext + bytes([pad_len] * pad_len)
        return encryptor.update(padded) + encryptor.finalize()

    def decrypt(self, ciphertext: bytes, key: bytes, iv: bytes) -> bytes:
        Cipher, algorithms, modes, default_backend = self._cipher_module
        cipher = Cipher(algorithms.AES(key), modes.CBC(iv), backend=default_backend())
        decryptor = cipher.decryptor()
        padded = decryptor.update(ciphertext) + decryptor.finalize()
        # 去除 PKCS7 padding
        pad_len = padded[-1]
        return padded[:-pad_len]


class SM4CbcAlgorithm(EncryptionAlgorithm):
    """SM4-CBC 国密算法（GM/T 0002-2012）"""

    KEY_SIZE = 16   # 128 bit
    BLOCK_SIZE = 16

    def __init__(self):
        try:
            from gmssl import sm4
            self._sm4 = sm4
            self._available = True
        except ImportError:
            self._sm4 = None
            self._available = False
            logger.warning("gmssl not installed, SM4 unavailable (fallback to BC)")

    def encrypt(self, plaintext: bytes, key: bytes, iv: bytes) -> bytes:
        if not self._available:
            raise RuntimeError("SM4 unavailable: install gmssl (pip install gmssl)")

        # gmssl.CryptSM4.CBC Mode
        crypt_sm4 = self._sm4.CryptSM4()
        crypt_sm4.set_key(key, self._sm4.SM4_ENCRYPT)
        # IV for CBC
        return crypt_sm4.crypt_cbc(iv, plaintext)

    def decrypt(self, ciphertext: bytes, key: bytes, iv: bytes) -> bytes:
        if not self._available:
            raise RuntimeError("SM4 unavailable: install gmssl")

        crypt_sm4 = self._sm4.CryptSM4()
        crypt_sm4.set_key(key, self._sm4.SM4_DECRYPT)
        return crypt_sm4.crypt_cbc(iv, ciphertext)


class VideoEncryptor:
    """HLS 视频加密器（v3.3 W-2.3）

    按密级自动选择算法：
    - L1/L2: AES-128-CBC
    - L3/L4: SM4-CBC
    """

    def __init__(self, classification_lv: str = "L1"):
        self.classification_lv = classification_lv
        self._algo = self._select_algorithm(classification_lv)
        logger.info(
            "video_encryptor_init classification_lv={} algorithm={}",
            classification_lv, self._algo.__class__.__name__
        )

    def _select_algorithm(self, classification_lv: str) -> EncryptionAlgorithm:
        """按密级选择算法"""
        if classification_lv in ("L3", "L4"):
            # 密级 L3+ 强制国密
            return SM4CbcAlgorithm()
        # L1/L2 使用国际算法（兼容现有 HLS 客户端）
        return AesCbcAlgorithm()

    def generate_key(self) -> bytes:
        """生成随机密钥"""
        return os.urandom(self._algo.KEY_SIZE)

    def generate_iv(self) -> bytes:
        """生成随机 IV"""
        return os.urandom(self._algo.BLOCK_SIZE)

    def encrypt(self, plaintext: bytes, key: bytes, iv: bytes) -> bytes:
        """加密视频数据"""
        if len(key) != self._algo.KEY_SIZE:
            raise ValueError(f"Key must be {self._algo.KEY_SIZE} bytes")
        if len(iv) != self._algo.BLOCK_SIZE:
            raise ValueError(f"IV must be {self._algo.BLOCK_SIZE} bytes")
        return self._algo.encrypt(plaintext, key, iv)

    def decrypt(self, ciphertext: bytes, key: bytes, iv: bytes) -> bytes:
        """解密视频数据"""
        return self._algo.decrypt(ciphertext, key, iv)

    def encrypt_hls_segment(
        self,
        segment_data: bytes,
        key: bytes,
        iv: bytes
    ) -> Tuple[bytes, str]:
        """加密 HLS .ts 分片

        Returns:
            (encrypted_data, algorithm_tag)
        """
        encrypted = self.encrypt(segment_data, key, iv)

        # 算法标签（用于 HLS 播放器识别）
        algo_tag = self._get_algo_tag()
        return encrypted, algo_tag

    def _get_algo_tag(self) -> str:
        """获取算法标识（写入 HLS EXT-X-KEY 标签）"""
        if isinstance(self._algo, SM4CbcAlgorithm):
            return "SM4-CBC"
        return "AES-128-CBC"


# ============================================================================
# 使用示例（FFmpeg HLS 切片 + SM4 加密）
# ============================================================================

def encrypt_hls_with_ffmpeg(
    input_path: str,
    output_dir: str,
    classification_lv: str,
    vault_client=None  # 从 Vault 拉取密钥
):
    """FFmpeg HLS 切片 + 国密加密

    Args:
        input_path: 输入视频路径
        output_dir: HLS 输出目录
        classification_lv: 密级
        vault_client: Vault 客户端（可选）
    """
    encryptor = VideoEncryptor(classification_lv)
    key = encryptor.generate_key()
    iv = encryptor.generate_iv()

    # 保存密钥到 Vault（仅返回 key_id）
    key_id = "local_key" if vault_client is None else vault_client.store_hls_key(
        classification_lv, key, iv
    )

    # FFmpeg 命令（HLS 切片）
    algo_tag = encryptor._get_algo_tag()
    cmd = [
        "ffmpeg", "-y", "-i", input_path,
        "-c:v", "libx264", "-c:a", "aac",
        "-hls_time", "10",
        "-hls_key_info_file", f"{output_dir}/key.info",
        "-hls_segment_filename", f"{output_dir}/segment_%03d.ts",
        "-hls_playlist_type", "vod",
        f"{output_dir}/playlist.m3u8"
    ]

    logger.info("ffmpeg_hls_segment command={}", " ".join(cmd))
    # 实际执行：subprocess.run(cmd, check=True)

    # 后处理：加密每个分片
    import glob
    for ts_file in glob.glob(f"{output_dir}/*.ts"):
        with open(ts_file, "rb") as f:
            data = f.read()
        encrypted = encryptor.encrypt(data, key, iv)
        with open(ts_file, "wb") as f:
            f.write(encrypted)

    return key_id, algo_tag