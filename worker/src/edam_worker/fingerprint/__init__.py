"""
帧指纹 / 泄露检测能力（v3.3 W-6.1 / W-6.3）

**这个包里曾经有两套互不兼容的 pHash 实现**：

- `processors/video.py` 用 imagehash 库（DCT + 缩放 + **高通滤波**），
  产出 `videos/{id}/fingerprint.json`（16 位 hex 串 = 8 字节）
- `PHashService` 自己手写 DCT（**没有高通滤波**，且中位数算法不同）

两边算出的 bit 串对不上 ⇒ 汉明距离比较毫无意义，匹配率 ≈ 0。
更糟的是没有任何报错 —— 看起来"跑通了"，只是永远匹配不上。

v3.4.1 起，**唯一实现**在 `fingerprint/phash.py`：
`processors/video.py` 与 `PHashService` 都调它，产出的字节**逐位相同**。
改动这个文件时请确保 `phash.py` 与 `processors/video.py` 的输出格式不变
（`fingerprint.json` 是已落盘的数据，改格式会让历史数据失效）。

本包其余模块的接线状态见各自的模块头注释。
"""
