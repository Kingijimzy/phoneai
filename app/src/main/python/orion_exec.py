"""Bounded in-process Python runner with a small safe stdlib and explicit phone capabilities."""
import ast
import contextlib
import io
import json
import asyncio as _asyncio
import datetime as _datetime
import math as _math
import re as _re
import types as _types
import sys as _sys
import time as _time
import urllib.parse as _urllib_parse

MAX_SOURCE = 8000
MAX_NODES = 500
MAX_OUTPUT = 5000
MAX_RANGE = 1000
MAX_SEQ = 10000
MAX_INT_BITS = 4096
MAX_INPUT = 8000
MAX_RUN_SECONDS = 5.0
MAX_EXTENSION_SECONDS = 2.0
MAX_STEPS = 20000

class Rejected(Exception):
    pass

def _range(*args):
    if not 1 <= len(args) <= 3:
        raise Rejected("range accepts 1 to 3 arguments")
    values = [int(x) for x in args]
    r = range(*values)
    if len(r) > MAX_RANGE:
        raise Rejected("range is limited to 1000 iterations")
    return r

_SAFE = {
    "print": print, "range": _range, "len": len, "sum": sum,
    "min": min, "max": max, "sorted": sorted, "abs": abs,
    "round": round, "enumerate": enumerate, "zip": zip,
    "list": list, "dict": dict, "set": set, "tuple": tuple,
    "str": str, "int": int, "float": float, "bool": bool,
    "all": all, "any": any, "reversed": reversed,
    "isinstance": isinstance, "type": type,
    "Exception": Exception, "ValueError": ValueError, "TypeError": TypeError,
    "ZeroDivisionError": ZeroDivisionError, "KeyError": KeyError, "IndexError": IndexError,
}

_ALLOWED_NODES = (
    ast.Module, ast.Expr, ast.Assign, ast.AugAssign, ast.Name, ast.Load, ast.Store,
    ast.Constant, ast.List, ast.Tuple, ast.Dict, ast.Set, ast.Subscript, ast.Slice,
    ast.BinOp, ast.UnaryOp, ast.BoolOp, ast.Compare, ast.If, ast.IfExp,
    ast.For, ast.Break, ast.Continue, ast.Pass, ast.Call, ast.keyword,
    ast.Add, ast.Sub, ast.Mult, ast.Div, ast.FloorDiv, ast.Mod, ast.Pow,
    ast.UAdd, ast.USub, ast.Not, ast.And, ast.Or,
    ast.Eq, ast.NotEq, ast.Lt, ast.LtE, ast.Gt, ast.GtE, ast.In, ast.NotIn,
    ast.ListComp, ast.comprehension, ast.Import, ast.ImportFrom, ast.alias,
    ast.FunctionDef, ast.arguments, ast.arg, ast.Return, ast.While, ast.Try,
    ast.ExceptHandler, ast.Raise, ast.JoinedStr, ast.FormattedValue,
)

_PHONE_METHODS = {
    "battery", "location", "notifications", "contacts", "accessibility_status", "accessibility_open_settings", "accessibility_screen",
    "fs_list", "fs_read", "fs_write", "shell", "http_get", "http_post",
    "ui_tap_text", "ui_tap", "ui_swipe", "ws_connect", "ws_connect_with_headers", "ws_send", "ws_send_binary", "ws_receive", "ws_close",
}


_MODULE_PATHS = {
    "json.loads", "json.dumps",
    "datetime.datetime", "datetime.date", "datetime.time", "datetime.timedelta", "datetime.timezone",
    "datetime.datetime.now", "datetime.datetime.utcnow", "datetime.datetime.fromtimestamp",
    "datetime.now", "datetime.utcnow", "datetime.fromtimestamp", "datetime.strptime", "datetime.isoformat",
    "datetime.datetime.strptime", "datetime.datetime.isoformat",
    "datetime.date.today", "datetime.date.fromisoformat", "datetime.date.isoformat",
    "date.today", "date.fromisoformat", "date.isoformat", "time.isoformat", "timedelta.total_seconds",
    "math.pi", "math.e", "math.tau", "math.inf", "math.nan",
    "math.sqrt", "math.floor", "math.ceil", "math.sin", "math.cos", "math.tan",
    "math.log", "math.log10", "math.exp", "math.pow", "math.fabs", "math.isfinite",
    "math.isclose", "math.gcd", "math.lcm",
    "re.match", "re.search", "re.fullmatch", "re.findall", "re.sub", "re.split", "re.escape",
    "urllib.parse", "urllib.parse.urlparse", "urllib.parse.urlsplit", "urllib.parse.urljoin",
    "urllib.parse.quote", "urllib.parse.unquote", "urllib.parse.urlencode",
    "urllib.parse.parse_qs", "urllib.parse.parse_qsl",
    "asyncio.run", "asyncio.sleep",
}
_ALLOWED_IMPORTS = {"json", "datetime", "math", "re", "asyncio", "urllib.parse"}
_SAFE_OBJECT_METHODS = {"get", "keys", "values", "items", "lower", "upper", "strip", "lstrip", "rstrip", "split", "rsplit", "splitlines", "join", "replace", "startswith", "endswith", "find", "rfind", "count", "capitalize", "title", "swapcase", "casefold", "isalpha", "isdigit", "isalnum", "isspace", "isupper", "islower", "append", "extend", "insert", "pop", "remove", "clear", "sort", "reverse", "copy", "index", "isoformat", "strftime", "timestamp", "weekday", "total_seconds", "group", "groups", "start", "end"}
_SAFE_OBJECT_ATTRS = {"year", "month", "day", "hour", "minute", "second", "microsecond", "tzinfo", "hostname", "port", "scheme", "netloc", "path", "params", "query", "fragment", "group", "groups", "start", "end", "isoformat", "strftime", "timestamp", "weekday", "date", "time", "total_seconds"} | _SAFE_OBJECT_METHODS

def _path(node):
    if isinstance(node, ast.Name):
        return node.id
    if isinstance(node, ast.Attribute):
        parent = _path(node.value)
        return (parent + "." + node.attr) if parent else None
    return None

async def _bounded_async_sleep(seconds=0):
    value = float(seconds)
    if not 0 <= value <= 1:
        raise Rejected("asyncio.sleep is limited to one second")
    await _asyncio.sleep(value)

def _bounded_async_run(coro):
    return _asyncio.run(_asyncio.wait_for(coro, timeout=2.0))


def _bounded_json_loads(value):
    if not isinstance(value, str) or len(value) > 16000:
        raise Rejected("json.loads accepts at most 16000 characters of text")
    return json.loads(value)

def _bounded_json_dumps(value):
    result = json.dumps(value)
    if len(result) > 20000:
        raise Rejected("json.dumps output is limited to 20000 characters")
    return result

def _safe_pattern(pattern, value):
    if not isinstance(pattern, str) or len(pattern) > 160:
        raise Rejected("regular-expression patterns are limited to 160 characters")
    if not isinstance(value, str) or len(value) > 2048:
        raise Rejected("regular-expression input is limited to 2048 characters")
    if _re.search(r"\)[+*{]", pattern):
        raise Rejected("quantified groups are blocked to reduce regular-expression backtracking")
    return pattern

def _bounded_re_match(pattern, value):
    return _re.match(_safe_pattern(pattern, value), value)

def _bounded_re_search(pattern, value):
    return _re.search(_safe_pattern(pattern, value), value)

def _bounded_re_fullmatch(pattern, value):
    return _re.fullmatch(_safe_pattern(pattern, value), value)

def _bounded_re_findall(pattern, value):
    return _re.findall(_safe_pattern(pattern, value), value)

def _bounded_re_sub(pattern, replacement, value):
    return _re.sub(_safe_pattern(pattern, value), str(replacement)[:120], value, count=1000)

def _bounded_re_split(pattern, value):
    return _re.split(_safe_pattern(pattern, value), value, maxsplit=1000)

def _safe_module(name):
    modules = {
        "json": _types.SimpleNamespace(loads=_bounded_json_loads, dumps=_bounded_json_dumps),
        "datetime": _types.SimpleNamespace(
            datetime=_datetime.datetime, date=_datetime.date, time=_datetime.time,
            timedelta=_datetime.timedelta, timezone=_datetime.timezone,
        ),
        "math": _types.SimpleNamespace(**{
            key: getattr(_math, key) for key in (
                "pi", "e", "tau", "inf", "nan", "sqrt", "floor", "ceil", "sin", "cos",
                "tan", "log", "log10", "exp", "pow", "fabs", "isfinite", "isclose",
                "gcd", "lcm"
            )
        }),
        "re": _types.SimpleNamespace(**{
            "match": _bounded_re_match, "search": _bounded_re_search,
            "fullmatch": _bounded_re_fullmatch, "findall": _bounded_re_findall,
            "sub": _bounded_re_sub, "split": _bounded_re_split, "escape": _re.escape,
        }),
        "asyncio": _types.SimpleNamespace(run=_bounded_async_run, sleep=_bounded_async_sleep),
    }
    parse = _types.SimpleNamespace(**{
        key: getattr(_urllib_parse, key) for key in (
            "urlparse", "urlsplit", "urljoin", "quote", "unquote", "urlencode",
            "parse_qs", "parse_qsl"
        )
    })
    modules["urllib"] = _types.SimpleNamespace(parse=parse)
    modules["urllib.parse"] = parse
    return modules.get(name)

def _safe_import(name, globals=None, locals=None, fromlist=(), level=0):
    if level != 0 or name not in _ALLOWED_IMPORTS:
        raise Rejected("import is not on the safe standard-library allowlist")
    module = _safe_module(name)
    if module is None:
        raise Rejected("safe module is unavailable")
    if fromlist:
        for item in fromlist:
            if item == "*":
                raise Rejected("star imports are not supported")
            if not hasattr(module, item):
                raise Rejected("requested module member is not allowlisted: " + str(item))
        return module
    if name == "urllib.parse":
        return _safe_module("urllib")
    return module

def _safe_method(obj, name, *args):
    if name not in _SAFE_OBJECT_METHODS:
        raise Rejected("method is not allowlisted")
    if isinstance(obj, list):
        if name in {"append", "insert"}:
            if len(obj) >= MAX_SEQ: raise Rejected("list size limit exceeded")
            getattr(obj, name)(*args)
            return None
        if name == "extend":
            values = list(args[0])
            _check_seq(len(obj) + len(values))
            obj.extend(values)
            return None
        if name == "sort":
            obj.sort()
            return None
        if name in {"reverse", "clear"}:
            getattr(obj, name)()
            return None
        if name in {"pop", "remove"}:
            return getattr(obj, name)(*args)
        if name in {"copy", "count", "index"}:
            return getattr(obj, name)(*args)
    if isinstance(obj, str):
        if name == "join":
            values = list(args[0])
            if len(values) > MAX_SEQ or sum(len(str(v)) for v in values) + max(0, len(values)-1)*len(obj) > MAX_SEQ:
                raise Rejected("string result exceeds the size limit")
            return obj.join(values)
        if name == "replace":
            old, new = str(args[0]), str(args[1])
            count = args[2] if len(args) > 2 else -1
            if len(new) > 1000: raise Rejected("replacement text is too large")
            count = min(obj.count(old), MAX_SEQ) if count < 0 else min(int(count), MAX_SEQ)
            growth = max(0, len(new) - len(old)) * count
            if len(obj) + growth > MAX_SEQ:
                raise Rejected("string result exceeds the size limit")
            result = obj.replace(old, new, count)
            _check_seq(len(result))
            return result
        result = getattr(obj, name)(*args)
        if isinstance(result, str): _check_seq(len(result))
        return result
    if isinstance(obj, dict) and name in {"get", "keys", "values", "items"}:
        return getattr(obj, name)(*args)
    if name in {"isoformat", "strftime", "timestamp", "weekday", "total_seconds", "group", "groups", "start", "end"}:
        result = getattr(obj, name)(*args)
        if isinstance(result, str): _check_seq(len(result))
        return result
    raise Rejected("method is not available for this value type")


def _join_fstring(parts):
    result = "".join(str(part) for part in parts)
    _check_seq(len(result))
    return result


def _safe_fstring_value(value, conversion=-1, format_spec=""):
    if len(format_spec) > 40 or "{" in format_spec or "}" in format_spec:
        raise Rejected("f-string format specification is too complex")
    import re as _fmt_re
    for number in _fmt_re.findall(r"\d+", format_spec):
        if int(number) > 1000:
            raise Rejected("f-string width or precision exceeds 1000")
    if conversion == 114: value = repr(value)
    elif conversion == 115: value = str(value)
    elif conversion == 97: value = ascii(value)
    if format_spec: value = format(value, format_spec)
    else: value = str(value)
    _check_seq(len(value))
    return value


def _validate(tree, allow_phone=False, allow_general_syntax=True):
    nodes = list(ast.walk(tree))
    function_names = {node.name for node in nodes if isinstance(node, ast.FunctionDef)}
    if len(nodes) > MAX_NODES:
        raise Rejected("script is too complex")
    loops = 0
    allowed_phone_attributes = {
        id(node.func) for node in nodes
        if isinstance(node, ast.Call) and isinstance(node.func, ast.Attribute)
    }
    for node in nodes:
        if not allow_general_syntax and isinstance(node, (ast.FunctionDef, ast.Return, ast.While, ast.Try, ast.ExceptHandler, ast.Raise, ast.JoinedStr, ast.FormattedValue)):
            raise Rejected("generated extensions use the restricted data-transform grammar")
        if isinstance(node, ast.FunctionDef):
            if node.decorator_list or node.returns is not None or node.type_comment is not None:
                raise Rejected("decorated and annotated functions are not supported")
            if node.args.vararg or node.args.kwarg or node.args.kwonlyargs or node.args.kw_defaults:
                raise Rejected("functions support positional parameters only")
        if isinstance(node, ast.ExceptHandler):
            if node.type is not None and not (isinstance(node.type, ast.Name) and node.type.id in {"Exception", "ValueError", "TypeError", "ZeroDivisionError", "KeyError", "IndexError"}):
                raise Rejected("only common safe exception types may be caught")
        if isinstance(node, ast.FormattedValue):
            if node.format_spec is not None and any(isinstance(part, ast.FormattedValue) or not isinstance(part, ast.Constant) or not isinstance(part.value, str) for part in node.format_spec.values):
                raise Rejected("f-string format specification must be literal text")
        if isinstance(node, ast.Attribute):
            phone_valid = (allow_phone and id(node) in allowed_phone_attributes and
                           isinstance(node.value, ast.Name) and node.value.id == "phone" and
                           node.attr in _PHONE_METHODS)
            safe_result_property = node.attr in _SAFE_OBJECT_ATTRS and not (isinstance(node.value, ast.Name) and node.value.id == "phone")
            if not phone_valid and _path(node) not in _MODULE_PATHS and not safe_result_property:
                raise Rejected("attribute access is limited to registered phone capabilities and safe module members")
        elif not isinstance(node, _ALLOWED_NODES):
            raise Rejected("unsupported Python syntax: " + type(node).__name__)
        if isinstance(node, ast.Constant) and isinstance(node.value, int) and not isinstance(node.value, bool):
            if abs(node.value).bit_length() > MAX_INT_BITS:
                raise Rejected("an integer constant exceeds the size limit")
        if isinstance(node, ast.BinOp) and isinstance(node.op, ast.Pow):
            if not isinstance(node.right, ast.Constant) or not isinstance(node.right.value, int) or isinstance(node.right.value, bool):
                raise Rejected("exponents must be small integer constants")
            if node.right.value < 0 or node.right.value > 16:
                raise Rejected("integer exponents must be between 0 and 16")
        if isinstance(node, ast.AugAssign) and isinstance(node.op, ast.Pow):
            if not isinstance(node.value, ast.Constant) or not isinstance(node.value.value, int) or isinstance(node.value.value, bool):
                raise Rejected("augmented exponents must be small integer constants")
            if node.value.value < 0 or node.value.value > 16:
                raise Rejected("integer exponents must be between 0 and 16")
        if isinstance(node, ast.Call):
            safe_builtin = isinstance(node.func, ast.Name) and node.func.id in _SAFE
            safe_phone = (allow_phone and isinstance(node.func, ast.Attribute) and
                          isinstance(node.func.value, ast.Name) and node.func.value.id == "phone" and
                          node.func.attr in _PHONE_METHODS)
            safe_module = isinstance(node.func, ast.Attribute) and _path(node.func) in _MODULE_PATHS
            safe_object_method = (isinstance(node.func, ast.Attribute) and node.func.attr in _SAFE_OBJECT_METHODS and
                                  not (isinstance(node.func.value, ast.Name) and node.func.value.id in {"phone", "json", "datetime", "math", "re", "asyncio", "urllib"}))
            safe_user_function = isinstance(node.func, ast.Name) and node.func.id in function_names
            if not (safe_builtin or safe_phone or safe_module or safe_object_method or safe_user_function):
                raise Rejected("only approved built-ins and registered phone capabilities may be called")
            if node.keywords:
                raise Rejected("keyword arguments are not supported")
        if isinstance(node, ast.Name) and node.id.startswith("_"):
            raise Rejected("private names are not allowed")
        if isinstance(node, (ast.For, ast.While)):
            loops += 1
            if isinstance(node, ast.For) and (not isinstance(node.iter, ast.Call) or not isinstance(node.iter.func, ast.Name) or node.iter.func.id != "range"):
                raise Rejected("for loops must iterate over bounded range()")
        if isinstance(node, ast.ListComp):
            if len(node.generators) > 1:
                raise Rejected("nested comprehensions are not allowed")
            for gen in node.generators:
                if not isinstance(gen.iter, ast.Call) or not isinstance(gen.iter.func, ast.Name) or gen.iter.func.id != "range":
                    raise Rejected("comprehensions must use bounded range()")
    if loops > 2:
        raise Rejected("at most two for loops are allowed")

def _is_seq(x):
    return isinstance(x, (list, tuple, str))

def _check_seq(n):
    if n > MAX_SEQ:
        raise Rejected("a list or text grew beyond " + str(MAX_SEQ) + " items")

def _add(a, b):
    if _is_seq(a) and _is_seq(b):
        _check_seq(len(a) + len(b))
    elif isinstance(a, int) and not isinstance(a, bool) and isinstance(b, int) and not isinstance(b, bool):
        if max(abs(a).bit_length(), abs(b).bit_length()) + 1 > MAX_INT_BITS:
            raise Rejected("a number grew too large")
    return a + b

def _mul(a, b):
    if _is_seq(a) and isinstance(b, int) and not isinstance(b, bool):
        _check_seq(len(a) * max(b, 0))
    elif _is_seq(b) and isinstance(a, int) and not isinstance(a, bool):
        _check_seq(len(b) * max(a, 0))
    elif isinstance(a, int) and isinstance(b, int):
        if abs(a).bit_length() + abs(b).bit_length() > MAX_INT_BITS:
            raise Rejected("a number grew too large")
    return a * b

def _pow(a, b):
    if not isinstance(a, int) or isinstance(a, bool) or not isinstance(b, int) or isinstance(b, bool):
        raise Rejected("exponentiation is limited to bounded integers")
    if b < 0 or b > 16:
        raise Rejected("integer exponents must be between 0 and 16")
    estimated_bits = max(1, abs(a).bit_length()) * b
    if estimated_bits > MAX_INT_BITS:
        raise Rejected("an exponentiation result would exceed the integer size limit")
    return pow(a, b)

class _Guard(ast.NodeTransformer):
    def visit_For(self, node):
        self.generic_visit(node)
        node.body.insert(0, ast.Expr(value=ast.Call(func=ast.Name(id="_tick", ctx=ast.Load()), args=[], keywords=[])))
        return node

    def visit_While(self, node):
        self.generic_visit(node)
        node.body.insert(0, ast.Expr(value=ast.Call(func=ast.Name(id="_tick", ctx=ast.Load()), args=[], keywords=[])))
        return node

    def visit_Call(self, node):
        self.generic_visit(node)
        if isinstance(node.func, ast.Attribute) and node.func.attr in _SAFE_OBJECT_METHODS:
            if isinstance(node.func.value, ast.Name) and node.func.value.id in {"phone", "json", "datetime", "math", "re", "asyncio", "urllib"}:
                return node
            return ast.copy_location(ast.Call(func=ast.Name(id="_safe_method", ctx=ast.Load()),
                args=[node.func.value, ast.Constant(node.func.attr)] + node.args, keywords=[]), node)
        return node

    def visit_JoinedStr(self, node):
        parts = []
        for value in node.values:
            if isinstance(value, ast.Constant) and isinstance(value.value, str):
                parts.append(ast.Constant(value.value))
            elif isinstance(value, ast.FormattedValue):
                self.visit(value.value)
                spec = ""
                if value.format_spec is not None:
                    spec = "".join(part.value for part in value.format_spec.values)
                parts.append(ast.Call(func=ast.Name(id="_safe_fstring_value", ctx=ast.Load()),
                    args=[value.value, ast.Constant(value.conversion), ast.Constant(spec)], keywords=[]))
            else:
                raise Rejected("unsupported f-string component")
        return ast.copy_location(ast.Call(func=ast.Name(id="_join_fstring", ctx=ast.Load()),
            args=[ast.List(elts=parts, ctx=ast.Load())], keywords=[]), node)

    """Routes + and * through size-checked helpers so a short loop cannot grow data without bound."""
    @staticmethod
    def _call(name, left, right):
        return ast.Call(func=ast.Name(id=name, ctx=ast.Load()), args=[left, right], keywords=[])

    def visit_BinOp(self, node):
        self.generic_visit(node)
        if isinstance(node.op, ast.Add):
            return ast.copy_location(self._call("_add", node.left, node.right), node)
        if isinstance(node.op, ast.Mult):
            return ast.copy_location(self._call("_mul", node.left, node.right), node)
        if isinstance(node.op, ast.Pow):
            return ast.copy_location(self._call("_pow", node.left, node.right), node)
        return node

    def visit_AugAssign(self, node):
        self.generic_visit(node)
        if isinstance(node.op, (ast.Add, ast.Mult, ast.Pow)):
            if not isinstance(node.target, ast.Name):
                raise Rejected("bounded augmented operations require a simple variable target")
            name = "_add" if isinstance(node.op, ast.Add) else "_mul" if isinstance(node.op, ast.Mult) else "_pow"
            load = ast.Name(id=node.target.id, ctx=ast.Load())
            return ast.copy_location(ast.Assign(targets=[node.target], value=self._call(name, load, node.value)), node)
        return node

def _prepare(source, allow_phone=False, allow_general_syntax=True):
    tree = ast.parse(source, mode="exec")
    _validate(tree, allow_phone=allow_phone, allow_general_syntax=allow_general_syntax)
    tree = ast.fix_missing_locations(_Guard().visit(tree))
    return compile(tree, "<orion-approved-python>", "exec")

def _scope(extra=None):
    steps = [0]
    def tick():
        steps[0] += 1
        if steps[0] > MAX_STEPS:
            raise Rejected("execution exceeded the 20000-step loop budget")
    scope = {"__builtins__": dict(_SAFE, __import__=_safe_import), "_add": _add, "_mul": _mul, "_pow": _pow, "_tick": tick, "_safe_method": _safe_method, "_safe_fstring_value": _safe_fstring_value, "_join_fstring": _join_fstring}
    if extra:
        scope.update(extra)
    return scope

def _exec_with_deadline(code, scope, timeout_seconds):
    """Cooperative bytecode deadline; C-extension calls retain their own bounds."""
    deadline = _time.monotonic() + max(0.05, min(float(timeout_seconds), 10.0))
    previous_trace = _sys.gettrace()

    def deadline_trace(frame, event, arg):
        if event in ("call", "line", "return", "exception") and _time.monotonic() >= deadline:
            raise Rejected("execution exceeded its time limit")
        return deadline_trace

    try:
        _sys.settrace(deadline_trace)
        exec(code, scope, scope)
    finally:
        _sys.settrace(previous_trace)


def run(source, max_source=MAX_SOURCE, phone_api=None):
    if not isinstance(source, str):
        return "error: Python code must be text"
    if not isinstance(max_source, int) or max_source < MAX_SOURCE or max_source > 16000:
        max_source = MAX_SOURCE
    if len(source) > max_source:
        return "error: Python code exceeds " + str(max_source) + " characters"
    if not source.strip():
        return "error: Python code is required"
    try:
        code = _prepare(source, allow_phone=phone_api is not None)
        output = io.StringIO()
        scope = _scope({"phone": phone_api} if phone_api is not None else None)
        with contextlib.redirect_stdout(output):
            _exec_with_deadline(code, scope, MAX_RUN_SECONDS)
        result = output.getvalue()
        return result[:MAX_OUTPUT] if result else "Python completed (no printed output)."
    except Rejected as exc:
        return "error: Python policy rejected code: " + str(exc)
    except Exception as exc:
        return "error: Python execution failed: " + type(exc).__name__ + ": " + str(exc)[:180]


def run_extension(source, input_json):
    """Runs a generated extension in the same whitelist sandbox. The script reads INPUT, may print,
    and sets RESULT. Returns a JSON string: {"ok":true,"result":...,"printed":"..."} or {"ok":false,"error":"..."}."""
    def fail(message):
        return json.dumps({"ok": False, "error": message[:300]})
    if not isinstance(source, str) or not source.strip():
        return fail("extension source is required")
    if len(source) > MAX_SOURCE:
        return fail("extension source exceeds 8000 characters")
    if not isinstance(input_json, str) or len(input_json) > MAX_INPUT:
        return fail("input must be JSON text of at most 8000 characters")
    try:
        data = json.loads(input_json) if input_json.strip() else {}
        code = _prepare(source, allow_general_syntax=False)
        output = io.StringIO()
        scope = _scope({"INPUT": data})
        with contextlib.redirect_stdout(output):
            _exec_with_deadline(code, scope, MAX_EXTENSION_SECONDS)
        result = scope.get("RESULT")
        printed = output.getvalue()[:MAX_OUTPUT]
        return json.dumps({"ok": True, "result": result, "printed": printed})
    except Rejected as exc:
        return fail("policy rejected the extension: " + str(exc))
    except (TypeError, ValueError) as exc:
        return fail("extension failed: " + type(exc).__name__ + ": " + str(exc))
    except Exception as exc:
        return fail("extension failed: " + type(exc).__name__ + ": " + str(exc))
