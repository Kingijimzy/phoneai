"""Constrained in-process Python runner for Orion. No imports, attributes, file, or network APIs."""
import ast
import contextlib
import io
import json

MAX_SOURCE = 8000
MAX_NODES = 500
MAX_OUTPUT = 5000
MAX_RANGE = 1000
MAX_SEQ = 10000
MAX_INT_BITS = 4096
MAX_INPUT = 8000

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
}

_ALLOWED_NODES = (
    ast.Module, ast.Expr, ast.Assign, ast.AugAssign, ast.Name, ast.Load, ast.Store,
    ast.Constant, ast.List, ast.Tuple, ast.Dict, ast.Set, ast.Subscript, ast.Slice,
    ast.BinOp, ast.UnaryOp, ast.BoolOp, ast.Compare, ast.If, ast.IfExp,
    ast.For, ast.Break, ast.Continue, ast.Pass, ast.Call, ast.keyword,
    ast.Add, ast.Sub, ast.Mult, ast.Div, ast.FloorDiv, ast.Mod, ast.Pow,
    ast.UAdd, ast.USub, ast.Not, ast.And, ast.Or,
    ast.Eq, ast.NotEq, ast.Lt, ast.LtE, ast.Gt, ast.GtE, ast.In, ast.NotIn,
    ast.ListComp, ast.comprehension,
)

_PHONE_METHODS = {
    "battery", "location", "notifications", "contacts", "accessibility_screen",
    "fs_list", "fs_read", "fs_write", "shell", "http_get",
}

def _validate(tree, allow_phone=False):
    nodes = list(ast.walk(tree))
    if len(nodes) > MAX_NODES:
        raise Rejected("script is too complex")
    loops = 0
    allowed_phone_attributes = {
        id(node.func) for node in nodes
        if isinstance(node, ast.Call) and isinstance(node.func, ast.Attribute)
    }
    for node in nodes:
        if isinstance(node, ast.Attribute):
            valid = (allow_phone and id(node) in allowed_phone_attributes and
                     isinstance(node.value, ast.Name) and node.value.id == "phone" and
                     node.attr in _PHONE_METHODS)
            if not valid:
                raise Rejected("only registered phone.<capability>() calls are allowed")
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
            if not (safe_builtin or safe_phone):
                raise Rejected("only approved built-ins and registered phone capabilities may be called")
            if node.keywords:
                raise Rejected("keyword arguments are not supported")
        if isinstance(node, ast.Name) and node.id.startswith("_"):
            raise Rejected("private names are not allowed")
        if isinstance(node, ast.For):
            loops += 1
            if not isinstance(node.iter, ast.Call) or not isinstance(node.iter.func, ast.Name) or node.iter.func.id != "range":
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

def _prepare(source, allow_phone=False):
    tree = ast.parse(source, mode="exec")
    _validate(tree, allow_phone=allow_phone)
    tree = ast.fix_missing_locations(_Guard().visit(tree))
    return compile(tree, "<orion-approved-python>", "exec")

def _scope(extra=None):
    scope = {"__builtins__": _SAFE, "_add": _add, "_mul": _mul, "_pow": _pow}
    if extra:
        scope.update(extra)
    return scope

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
            exec(code, scope, scope)
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
        code = _prepare(source)
        output = io.StringIO()
        scope = _scope({"INPUT": data})
        with contextlib.redirect_stdout(output):
            exec(code, scope, scope)
        result = scope.get("RESULT")
        printed = output.getvalue()[:MAX_OUTPUT]
        return json.dumps({"ok": True, "result": result, "printed": printed})
    except Rejected as exc:
        return fail("policy rejected the extension: " + str(exc))
    except (TypeError, ValueError) as exc:
        return fail("extension failed: " + type(exc).__name__ + ": " + str(exc))
    except Exception as exc:
        return fail("extension failed: " + type(exc).__name__ + ": " + str(exc))
