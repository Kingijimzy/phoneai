import json
import os
import sys
import unittest

sys.path.insert(0, os.path.join(os.path.dirname(__file__), "..", "..", "app", "src", "main", "python"))
import orion_exec as o  # noqa: E402


def ext(src, inp="{}"):
    return json.loads(o.run_extension(src, inp))


class ExtensionSandbox(unittest.TestCase):
    def test_reads_input_and_sets_result(self):
        r = ext('total = 0\nfor i in range(len(INPUT["v"])):\n    total += INPUT["v"][i]\nRESULT = total', '{"v":[1,2,3]}')
        self.assertTrue(r["ok"]); self.assertEqual(r["result"], 6)

    def test_printed_output_is_separate_from_result(self):
        r = ext("print('hi')\nRESULT = 1")
        self.assertEqual((r["result"], r["printed"]), (1, "hi\n"))

    def test_policy_rejections(self):
        for src in ["import os", "while True: pass", "RESULT = INPUT.keys()", "x = open('f')", "x = __import__('os')", "RESULT = (lambda: 1)()"]:
            self.assertFalse(ext(src)["ok"], src)

    def test_functions_while_try_fstrings_and_safe_methods_are_supported(self):
        source = """def total(values):
    result = 0
    for index in range(len(values)):
        result += values[index]
    return result
step = 0
while step < 3:
    step += 1
try:
    value = 1 / 0
except ZeroDivisionError:
    value = 42
finally:
    finished = True
items = [3, 1]
items.append(2)
items.sort()
label = f"sum={total(items):02d}"
RESULT = {"sum": total(items), "step": step, "value": value, "finished": finished, "items": items, "label": label, "text": "  ORION  ".strip().lower()}
"""
        result = ext(source)
        self.assertTrue(result["ok"], result)
        self.assertEqual(result["result"], {"sum": 6, "step": 3, "value": 42, "finished": True, "items": [1, 2, 3], "label": "sum=06", "text": "orion"})

    def test_extension_while_loop_is_stopped_by_step_budget(self):
        result = ext("while True: pass")
        self.assertFalse(result["ok"])
        self.assertIn("20000-step loop budget", result["error"])

    def test_growth_is_capped(self):
        self.assertFalse(ext("x=[0]\nfor i in range(1000):\n    x = x + x")["ok"])
        self.assertFalse(ext("x=[0]\nfor i in range(1000):\n    x *= 2")["ok"])
        self.assertFalse(ext("x=3\nfor i in range(100):\n    x = x * x")["ok"])
        self.assertIn("error", o.run("x=[0]*10000000"))

    def test_input_and_source_limits(self):
        self.assertFalse(ext("RESULT=1", "x" * 9000)["ok"])
        self.assertFalse(ext("x=1\n" * 3000)["ok"])
        self.assertFalse(ext("")["ok"])

    def test_existing_python_exec_behaviour_is_unchanged(self):
        self.assertEqual(o.run("print(len([1,2]) + 1)").strip(), "3")
        self.assertEqual(o.run("a=2\na *= 3\nprint(a)").strip(), "6")

    def test_calculator_exponentiation_is_supported(self):
        self.assertEqual(o.run("print(2 ** 5)").strip(), "32")
        self.assertEqual(o.run("print(100 / 4)").strip(), "25.0")


if __name__ == "__main__":
    unittest.main()
