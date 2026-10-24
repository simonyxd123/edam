# worker 测试从"从没跑过"到 11 个真的在跑 + 修配置被冻结的 bug

- 日期：2026-10-24
- 范围：worker/pytest.ini、worker/tests/、worker/requirements-dev.txt、
  worker/src/edam_worker/config.py、.gitlab-ci.yml

---

## 一、worker 的测试从来没执行过

### 现状

```
worker/pytest.ini  →  里面装的是 **Python 测试代码**（225 行 .py 内容）
worker/tests/      →  空目录
```

两个问题叠加：

1. **文件名骗了 pytest**：`pytest.ini` 是 pytest 的**配置文件名**，
   pytest 会用 configparser 解析它 → 里面是 Python 代码 → 解析失败
2. **pytest 只收集 `test_*.py`** → 那些代码**从不被收集**

结果：13 个测试用例写了但一次都没跑，CI 的 `worker:test` job
跑 `pytest --cov=.` 会以 **exit code 5（no tests collected）** 失败。

### 修复

| 文件 | 变化 |
|---|---|
| `worker/pytest.ini` | 回归它本来的身份：真正的 INI 配置（testpaths / markers / addopts / **pythonpath = src**） |
| `worker/tests/test_worker.py` | 新增 —— 13 个测试代码移到 pytest 会收集的位置 |

**`pythonpath = src` 是必需的**：包在 `worker/src/edam_worker/` 下，
不加这行 pytest 报 `ModuleNotFoundError: No module named 'edam_worker'`
（CI 里同理 —— 之前 `cd worker && pytest` 没有 PYTHONPATH=src）。

### 结果

```
11 passed, 2 skipped
（2 个 skip：缺 PyPDF2 与 ffmpeg，CI 镜像里都有）
```

## 二、顺带发现并修掉一个真 bug：配置在类定义时就被冻结

worker 测试暴露出：

```
assert s.RABBITMQ_HOST == "test-host"
AssertionError: assert 'localhost' == 'test-host'
```

根因：`Settings` 是 dataclass，字段默认值写成 `os.getenv(...)`：

```python
@dataclass
class Settings:
    RABBITMQ_HOST: str = os.getenv("RABBITMQ_HOST", "localhost")
```

**dataclass 的字段默认值在类定义时求值一次** —— 也就是 `import config`
那一刻的环境变量快照。此后无论怎么改环境变量（monkeypatch、K8s secret
轮换、热更新）**全部无效**。

这不只是测试问题：`WORKER_TOKEN`、`OPS_TOKEN`、`OPS_ENABLED`、
`RABBITMQ_*`、`MINIO_*` 全都是这个模式 —— 意味着**运行时改这些环境变量
不会生效**，而运维往往就是靠改环境变量来轮换 token 的。

修复：改用 `field(default_factory=...)` + 一个带类型转换的 `_env()` 辅助
函数（顺带修正了原来 `int(os.getenv(...))` 在环境变量为空时会抛
`ValueError` 的问题 —— 现在空值走默认值）。

`settings` 单例的行为保持不变（仍在 import 时创建一次），但
**每次 `Settings()` 都重新读环境** → 测试可 monkeypatch，语义正确。

## 三、CI worker job 收紧

```diff
- - cd worker && pytest --cov=. --cov-report=xml --cov-report=term
+ - cd worker && pytest tests/ --cov=src --cov-report=xml --cov-report=term
```

`--cov=.` 会把 `tests/` 自己也算进覆盖率，数字虚高；`--cov=src`
才是真实源码覆盖率。

## 四、requirements-dev.txt 收敛

删掉 `httpx` / `fakeredis` / `aioresponses` —— 仓库里没有任何测试用到
这三个 mock 库。

## 五、验证

```
后端 compile              OK
后端测试 (25)             OK
worker 测试 (11)          OK   ← 从 0 到 11
DB 3 校验                 OK
OpenAPI 覆盖率            OK (100%)
前端 lint / vue-tsc       OK
前端 test (19)            OK
e2e (19)                  OK
helm × 3 overlay          OK
```
