"""Minimal test kit for Build Lab's Python projects (standard library only).

Tests are methods of classes whose names end in ``Test``, marked with ``@test("readable name")``.
A fresh instance of the class is created for every test.
"""

import threading


class AssertionFailure(AssertionError):
    """Raised by the assert_* helpers."""


def test(name=None, timeout=10.0):
    """Marks a method as a test. ``timeout`` is in seconds."""

    def mark(fn):
        fn._lab_test = True
        fn._lab_name = name or fn.__name__
        fn._lab_timeout = timeout
        return fn

    return mark


def fail(message):
    raise AssertionFailure(message)


def assert_true(condition, message="expected True but was False"):
    if not condition:
        fail(message)


def assert_false(condition, message="expected False but was True"):
    if condition:
        fail(message)


def assert_equal(expected, actual, message="values differ"):
    if expected != actual:
        fail(f"{message} ==> expected <{expected!r}> but was <{actual!r}>")


def assert_not_equal(unexpected, actual, message="values should differ"):
    if unexpected == actual:
        fail(f"{message} ==> did not expect <{actual!r}>")


def assert_near(expected, actual, tolerance, message="values differ"):
    if abs(expected - actual) > tolerance:
        fail(f"{message} ==> expected <{expected}> +/- {tolerance} but was <{actual}>")


def assert_is_none(value, message="expected None"):
    if value is not None:
        fail(f"{message} ==> but was <{value!r}>")


def assert_is_not_none(value, message="expected a value but was None"):
    if value is None:
        fail(message)


def assert_raises(exc_type, fn, message="wrong exception"):
    """Calls ``fn()`` and returns the exception it raised, which must be an ``exc_type``."""
    try:
        fn()
    except exc_type as e:
        return e
    except BaseException as e:  # noqa: BLE001 - we report whatever was raised
        fail(f"{message} ==> expected {exc_type.__name__} but got {type(e).__name__}: {e}")
    fail(f"{message} ==> expected {exc_type.__name__} but nothing was raised")


def run_concurrently(threads, task):
    """Starts ``threads`` workers behind a shared barrier and calls ``task(index)`` in each.

    Re-raises the first exception from any worker.
    """
    barrier = threading.Barrier(threads)
    errors = []

    def worker(i):
        try:
            barrier.wait()
            task(i)
        except BaseException as e:  # noqa: BLE001
            errors.append(e)

    workers = [threading.Thread(target=worker, args=(i,), daemon=True) for i in range(threads)]
    for w in workers:
        w.start()
    for w in workers:
        w.join(30)
    if errors:
        raise errors[0]
