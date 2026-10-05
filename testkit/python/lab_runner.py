"""Runs Build Lab Python tests.

usage: python -I -B lab_runner.py --src DIR --tests DIR [--sandbox] [Suite#method ...]

Output (parsed by the lab server):
  PASS|Suite|method|name|millis
  FAIL|Suite|method|name|millis|message
  RESULT|passed|failed|total
  COMPILE|message        (syntax/import errors; nothing else is printed)
"""

import os
import sys
import threading
import time
import traceback


def main(argv):
    src = tests = None
    sandbox = False
    selectors = []
    i = 0
    while i < len(argv):
        a = argv[i]
        if a == "--src":
            src = argv[i + 1]
            i += 2
        elif a == "--tests":
            tests = argv[i + 1]
            i += 2
        elif a == "--sandbox":
            sandbox = True
            i += 1
        else:
            selectors.append(a)
            i += 1

    kit = os.path.dirname(os.path.abspath(__file__))
    sys.path[:0] = [src, tests, kit]

    if sandbox:
        import lab_sandbox  # noqa: E402 - must run before any submitted code is imported
        lab_sandbox.lock_down()

    import labtest  # noqa: E402

    # import every *_test.py module; that imports the code under test too
    modules = []
    for name in sorted(os.listdir(tests)):
        if name.endswith("_test.py"):
            try:
                modules.append(__import__(name[:-3]))
            except BaseException:  # noqa: BLE001 - report syntax/import errors as a compile phase
                report_compile_error(src, tests)
                return 3

    wanted = {}
    for s in selectors:
        suite, _, method = s.partition("#")
        wanted.setdefault(suite, set()).add(method)

    passed = failed = 0
    for module in modules:
        for suite_name in sorted(vars(module)):
            suite = getattr(module, suite_name)
            if not (isinstance(suite, type) and suite_name.endswith("Test") and suite.__module__ == module.__name__):
                continue
            if selectors and suite_name not in wanted:
                continue
            methods = [m for m in sorted(vars(suite)) if getattr(getattr(suite, m), "_lab_test", False)]
            for method in methods:
                if selectors and method not in wanted[suite_name]:
                    continue
                fn = getattr(suite, method)
                name = fn._lab_name.replace("|", "/")
                start = time.perf_counter()
                error = run_one(suite, method, fn._lab_timeout, src, kit)
                ms = int((time.perf_counter() - start) * 1000)
                if error is None:
                    passed += 1
                    print(f"PASS|{suite_name}|{method}|{name}|{ms}", flush=True)
                else:
                    failed += 1
                    msg = error.replace("\n", " ").replace("\r", " ")
                    print(f"FAIL|{suite_name}|{method}|{name}|{ms}|{msg}", flush=True)

    print(f"RESULT|{passed}|{failed}|{passed + failed}", flush=True)
    return 0 if failed == 0 else 1


def run_one(suite, method, timeout, src, kit):
    outcome = {}

    def target():
        try:
            getattr(suite(), method)()
            outcome["ok"] = True
        except BaseException as e:  # noqa: BLE001
            outcome["error"] = describe(e, src, kit)

    t = threading.Thread(target=target, name="test-" + method, daemon=True)
    t.start()
    t.join(timeout)
    if t.is_alive():
        return f"timed out after {timeout:g}s (deadlock or infinite loop?)"
    return outcome.get("error")


def describe(e, src, kit):
    import labtest

    if isinstance(e, labtest.AssertionFailure):
        return str(e)
    text = f"{type(e).__name__}: {e}" if str(e) else type(e).__name__
    # point at the deepest frame in the code under test, if any
    frames = traceback.extract_tb(e.__traceback__)
    for frame in reversed(frames):
        if frame.filename.startswith(os.path.abspath(src)):
            text += f" at {os.path.relpath(frame.filename, src)}:{frame.lineno}"
            break
    return text


def report_compile_error(src, tests):
    etype, e, tb = sys.exc_info()
    if isinstance(e, SyntaxError):
        where = os.path.relpath(e.filename, src) if e.filename and e.filename.startswith(os.path.abspath(src)) else e.filename
        line = f"{where}:{e.lineno}: {type(e).__name__}: {e.msg}"
        if e.text:
            line += "\\n    " + e.text.rstrip()
        print("COMPILE|" + line, flush=True)
        return
    print("COMPILE|" + "".join(traceback.format_exception_only(etype, e)).strip().replace("\n", "\\n"), flush=True)
    for frame in traceback.extract_tb(tb):
        if frame.filename.startswith(os.path.abspath(src)):
            print(f"COMPILE|  at {os.path.relpath(frame.filename, src)}:{frame.lineno}", flush=True)


if __name__ == "__main__":
    code = main(sys.argv[1:])
    sys.stdout.flush()
    os._exit(code)  # don't wait for threads left running by the code under test
