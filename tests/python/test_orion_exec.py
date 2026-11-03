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
        for src in ["import os", "def f(): pass", "while True: pass", "RESULT = INPUT.keys()", "x = open('f')", "x = __import__('os')", "RESULT = (lambda: 1)()"]:
            self.assertFalse(ext(src)["ok"], src)

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


if __name__ == "__main__":
    unittest.main()
