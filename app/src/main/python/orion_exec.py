"""Constrained in-process Python runner for Orion. No imports, attributes, file, or network APIs."""
import ast
import contextlib
import io

MAX_SOURCE = 8000
MAX_NODES = 500
MAX_OUTPUT = 5000
MAX_RANGE = 1000

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
    ast.Add, ast.Sub, ast.Mult, ast.Div, ast.FloorDiv, ast.Mod,
    ast.UAdd, ast.USub, ast.Not, ast.And, ast.Or,
    ast.Eq, ast.NotEq, ast.Lt, ast.LtE, ast.Gt, ast.GtE, ast.In, ast.NotIn,
    ast.ListComp, ast.comprehension,
)

def _validate(tree):
    nodes = list(ast.walk(tree))
    if len(nodes) > MAX_NODES:
        raise Rejected("script is too complex")
    loops = 0
    for node in nodes:
        if not isinstance(node, _ALLOWED_NODES):
            raise Rejected("unsupported Python syntax: " + type(node).__name__)
        if isinstance(node, ast.Call):
            if not isinstance(node.func, ast.Name) or node.func.id not in _SAFE:
                raise Rejected("only approved built-in functions may be called")
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

def run(source):
    if not isinstance(source, str) or not source.strip():
        return "error: Python code is required"
    if len(source) > MAX_SOURCE:
        return "error: Python code exceeds 8000 characters"
    try:
        tree = ast.parse(source, mode="exec")
        _validate(tree)
        output = io.StringIO()
        scope = {"__builtins__": _SAFE}
        with contextlib.redirect_stdout(output):
            exec(compile(tree, "<orion-approved-python>", "exec"), scope, scope)
        result = output.getvalue()
        return result[:MAX_OUTPUT] if result else "Python completed (no printed output)."
    except Rejected as exc:
        return "error: Python policy rejected code: " + str(exc)
    except Exception as exc:
        return "error: Python execution failed: " + type(exc).__name__ + ": " + str(exc)[:180]
