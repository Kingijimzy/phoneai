"""Unrestricted in-process Python runner (bundled CPython): files, network, stdlib, installed pip packages.
Used when the Linux guest runtime cannot start, so python_exec always works."""
import contextlib
import io
import os
import sys
import threading
import traceback


def run_full(code, workdir, timeout):
    out = io.StringIO()
    done = threading.Event()
    box = {}

    def target():
        try:
            os.makedirs(workdir, exist_ok=True)
            os.chdir(workdir)
            g = {"__name__": "__main__", "WORKSPACE": workdir}
            with contextlib.redirect_stdout(out), contextlib.redirect_stderr(out):
                exec(compile(code, "orion_main.py", "exec"), g)
            box["ok"] = True
        except SystemExit:
            box["ok"] = True
        except BaseException:
            box["ok"] = False
            out.write(traceback.format_exc(limit=6))
        finally:
            done.set()

    t = threading.Thread(target=target, daemon=True)
    t.start()
    if not done.wait(timeout):
        return "exit=124 (timed out after %ds; the script keeps running in the background)\n%s" % (timeout, out.getvalue())
    return ("exit=0\n" if box.get("ok") else "exit=1\n") + out.getvalue()
