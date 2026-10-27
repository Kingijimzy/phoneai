import importlib.util
import pathlib
import unittest

MODULE = pathlib.Path(__file__).parents[2] / "main" / "python" / "orion_exec.py"
SPEC = importlib.util.spec_from_file_location("orion_exec", MODULE)
orion_exec = importlib.util.module_from_spec(SPEC)
SPEC.loader.exec_module(orion_exec)


class OrionExecTests(unittest.TestCase):
    def test_basic_arithmetic_and_output(self):
        self.assertEqual(orion_exec.run("print(sum(range(5)))"), "10\n")

    def test_rejects_imports_and_attribute_access(self):
        self.assertIn("policy rejected", orion_exec.run("import os"))
        self.assertIn("policy rejected", orion_exec.run("print((1).__class__)"))
        self.assertIn("policy rejected", orion_exec.run("open('secret.txt')"))

    def test_rejects_unbounded_or_unsupported_loops(self):
        self.assertIn("policy rejected", orion_exec.run("while True: pass"))
        self.assertIn("policy rejected", orion_exec.run("for x in [1, 2]: print(x)"))
        self.assertIn("1000 iterations", orion_exec.run("for x in range(1001): print(x)"))

    def test_rejects_oversized_and_empty_code(self):
        self.assertIn("required", orion_exec.run(" "))
        self.assertIn("8000 characters", orion_exec.run(" " * 8001))

    def test_output_is_bounded(self):
        out = orion_exec.run("print('x' * 4000)")
        self.assertLessEqual(len(out), 5000)


if __name__ == "__main__":
    unittest.main()
