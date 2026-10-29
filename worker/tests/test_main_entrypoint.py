"""
Worker 启动入口的回归测试

为什么单独一个文件：`edam_worker.main` 的模块体里有**导入期副作用**
（注册 Prometheus 指标、构造 app）。这些副作用一旦出错，worker 是
**启动即崩**，而且崩在 import 阶段 —— 错误信息长得像"环境问题"
（No module named / Duplicated timeseries），很容易查错方向。

2026-10-23 的"死代码清理"（commit ef09956）删掉了 main.py 里的
``import signal`` / ``import sys``，却留下了 ``signal.signal(...)``
和 ``sys.exit(...)`` 的调用 —— worker 从那天起就**一次都没起来过**，
直到 2026-10-29 才定位。

这些用例都在**子进程**里跑：既拿到了干净的 import 环境（不污染
pytest 自己的进程），又能真实复现"模块体被执行两次"这条路径。
"""
import os
import subprocess
import sys
from pathlib import Path

import pytest

WORKER_DIR = Path(__file__).resolve().parents[1]
SRC_DIR = WORKER_DIR / "src"


def _run_python(code: str) -> subprocess.CompletedProcess:
    """在子进程里跑一段 Python，返回结果。

    PYTHONPATH 显式指向 src/ —— 跟 pytest.ini 里的 pythonpath 一致，
    但子进程不继承 pytest 的 sys.path 注入，必须自己带。
    """
    env = dict(os.environ)
    env["PYTHONPATH"] = str(SRC_DIR) + os.pathsep + env.get("PYTHONPATH", "")
    return subprocess.run(
        [sys.executable, "-c", code],
        cwd=str(WORKER_DIR),
        env=env,
        capture_output=True,
        text=True,
        timeout=180,
    )


class TestWorkerStartup:
    """worker 必须能真的启动 —— 不许在 import 阶段就崩。"""

    def test_module_import_succeeds(self):
        """`import edam_worker.main` 不能抛 NameError 之类的错误。

        回归 ef09956：那次删了 import 却留下调用，import 即 NameError。
        """
        proc = _run_python("import edam_worker.main")
        assert proc.returncode == 0, (
            f"import edam_worker.main 失败：\n{proc.stdout}\n{proc.stderr}"
        )
        assert "NameError" not in proc.stderr

    def test_dunder_main_startup_succeeds(self):
        """`python -m edam_worker.main` 的模块体必须能跑通。

        runpy 以 __main__ 的身份执行一遍模块体 —— 这条路径和
        `uvicorn edam_worker.main:app` 不同，必须单独覆盖。
        """
        code = (
            "import runpy, uvicorn\n"
            "uvicorn.run = lambda *a, **k: None\n"   # 别真起服务
            "runpy.run_module('edam_worker.main', run_name='__main__')\n"
        )
        proc = _run_python(code)
        assert proc.returncode == 0, (
            f"`python -m edam_worker.main` 失败：\n{proc.stdout}\n{proc.stderr}"
        )

    def test_module_body_executed_twice_does_not_crash(self):
        """模块体被执行两次也不能崩（DuplicateTimeseries 回归）。

        `python -m edam_worker.main` 走 runpy 执行一遍模块体，
        uvicorn 若按字符串 "edam_worker.main:app" 再 import 一遍，
        模块体就是第二次 —— 指标注册两次 ⇒ DuplicateTimeseries。
        这里精确复现这条链路。
        """
        code = (
            "import runpy, uvicorn, importlib\n"
            "uvicorn.run = lambda *a, **k: None\n"
            "runpy.run_module('edam_worker.main', run_name='__main__')\n"
            "importlib.import_module('edam_worker.main')\n"
            "print('SECOND_IMPORT_OK')\n"
        )
        proc = _run_python(code)
        assert proc.returncode == 0, (
            f"模块体执行两次导致启动失败：\n{proc.stdout}\n{proc.stderr}"
        )
        assert "SECOND_IMPORT_OK" in proc.stdout
        assert "Duplicated timeseries" not in proc.stderr

    def test_metrics_module_is_idempotent(self):
        """指标模块重复导入只注册一次（同名指标注册两次会抛异常）。"""
        code = (
            "import importlib\n"
            "m1 = importlib.import_module('edam_worker.metrics')\n"
            "m2 = importlib.import_module('edam_worker.metrics')\n"
            "assert m1.TASKS_PROCESSED is m2.TASKS_PROCESSED\n"
            "print('METRICS_OK')\n"
        )
        proc = _run_python(code)
        assert proc.returncode == 0, (
            f"指标模块重复导入失败：\n{proc.stdout}\n{proc.stderr}"
        )
        assert "METRICS_OK" in proc.stdout

    def test_no_custom_signal_handler_installed(self):
        """不允许自己注册 SIGTERM handler 覆盖 uvicorn 的优雅关闭。

        自己注册会让 lifespan 的关闭段没机会执行 ⇒ RabbitMQ 连接不关、
        正在转码的任务被硬切 ⇒ MinIO 留半成品目录。
        """
        code = (
            "import signal\n"
            "before = signal.getsignal(signal.SIGTERM)\n"
            "import edam_worker.main\n"
            "after = signal.getsignal(signal.SIGTERM)\n"
            "assert before is after, 'import 阶段改了 SIGTERM handler'\n"
            "print('SIGNAL_OK')\n"
        )
        proc = _run_python(code)
        assert proc.returncode == 0, (
            f"import 阶段安装了自定义信号处理器：\n{proc.stdout}\n{proc.stderr}"
        )
        assert "SIGNAL_OK" in proc.stdout


class TestHealthEndpoints:
    """健康端点必须存在 —— 守护脚本和 K8s 探针都靠它们。"""

    @pytest.fixture(scope="class")
    def app_module(self):
        import importlib
        return importlib.import_module("edam_worker.main")

    def test_health_endpoints_registered(self, app_module):
        paths = {route.path for route in app_module.app.routes}
        assert "/health/live" in paths
        assert "/health/ready" in paths
        assert "/health/worker" in paths
