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

    def test_agent_calculator_acceptance_script_executes(self):
        code = """first = 25
second = 17
print("Inputs:", first, second)
print("Addition:", first + second)
print("Subtraction:", first - second)
print("Multiplication:", first * second)
if second != 0:
    print("Division:", first / second)
else:
    print("Division: undefined (division by zero)")
"""
        output = orion_exec.run(code)
        self.assertNotIn("error:", output)
        self.assertIn("Addition: 42", output)
        self.assertIn("Subtraction: 8", output)
        self.assertIn("Multiplication: 425", output)
        self.assertIn("Division:", output)

    def test_rejects_imports_and_attribute_access(self):
        self.assertIn("policy rejected", orion_exec.run("import os"))
        self.assertIn("policy rejected", orion_exec.run("print((1).__class__)"))
        self.assertIn("policy rejected", orion_exec.run("open('secret.txt')"))

    def test_rejects_unbounded_or_unsupported_loops(self):
        self.assertIn("policy rejected", orion_exec.run("while True: pass"))
        self.assertIn("policy rejected", orion_exec.run("for x in [1, 2]: print(x)"))
        self.assertIn("1000 iterations", orion_exec.run("for x in range(1001): print(x)"))

    def test_exponentiation_is_bounded(self):
        self.assertEqual(orion_exec.run("print(2 ** 16)"), "65536\n")
        self.assertIn("small integer constants", orion_exec.run("power = 2 ** exponent"))
        self.assertIn("between 0 and 16", orion_exec.run("print(2 ** 17)"))
        self.assertIn("size limit", orion_exec.run("print(((2 ** 16) ** 16) ** 16)"))

    def test_augmented_exponentiation_and_integer_growth_are_bounded(self):
        self.assertIn("between 0 and 16", orion_exec.run("value = 2\nvalue **= 17"))
        self.assertIn("size limit", orion_exec.run("value = 2\nvalue **= 16\nvalue **= 16\nvalue **= 16"))
        huge_literal = "value = " + str(1 << 4096)
        self.assertIn("constant exceeds the size limit", orion_exec.run(huge_literal))

    def test_rejects_oversized_and_empty_code(self):
        self.assertIn("required", orion_exec.run(" "))
        self.assertIn("8000 characters", orion_exec.run(" " * 8001))

    def test_output_is_bounded(self):
        out = orion_exec.run("print('x' * 4000)")
        self.assertLessEqual(len(out), 5000)

    def test_registered_phone_capabilities_are_available_only_to_direct_runner(self):
        class FakePhone:
            def battery(self):
                return '{"level":77}'

        self.assertIn("77", orion_exec.run("print(phone.battery())", phone_api=FakePhone()))
        self.assertIn("policy rejected", orion_exec.run_extension("RESULT = phone.battery()", "{}"))

    def test_phone_attribute_escape_and_unregistered_methods_are_rejected(self):
        class FakePhone:
            def battery(self):
                return "ok"

        self.assertIn("policy rejected", orion_exec.run("print(phone.__class__)", phone_api=FakePhone()))
        self.assertIn("policy rejected", orion_exec.run("print(phone.delete_everything())", phone_api=FakePhone()))

    def test_safe_standard_library_imports(self):
        code = """import json
import math
import re
from datetime import datetime
import urllib.parse
parsed = urllib.parse.urlparse("http://192.168.1.2:8123/api")\nprint(json.dumps({"sqrt": math.sqrt(81), "match": bool(re.search("ha", "homeassistant")), "host": parsed.hostname, "year": datetime.now().year}))
"""
        out = orion_exec.run(code)
        self.assertNotIn("error:", out)
        self.assertIn('"sqrt": 9.0', out)
        self.assertIn('"match": true', out)
        self.assertIn('"host": "192.168.1.2"', out)

    def test_safe_asyncio_subset_is_bounded(self):
        self.assertIn("completed", orion_exec.run("import asyncio\nasyncio.run(asyncio.sleep(0.01))"))
        self.assertIn("limited to one second", orion_exec.run("import asyncio\nasyncio.run(asyncio.sleep(2))"))

    def test_imports_do_not_open_files_or_network(self):
        self.assertIn("policy rejected", orion_exec.run("import urllib.request"))
        self.assertIn("policy rejected", orion_exec.run("import asyncio.subprocess"))
        self.assertIn("policy rejected", orion_exec.run("from json import *"))

    def test_safe_json_dict_helpers_and_regex_limits(self):
        out = orion_exec.run('import json\ndata = json.loads("{\\"count\\": 4}")\nprint(data.get("count"))')
        self.assertEqual(out, "4\n")
        self.assertIn("quantified groups", orion_exec.run('import re\nprint(re.search("(a+)+$", "a" * 100))'))

    def test_accessibility_status_and_websocket_methods_are_registered_phone_calls(self):
        class FakePhone:
            def accessibility_status(self):
                return '{"service_connected": true}'
            def ws_connect(self, url):
                return '{"session_id": "test-session"}'
            def ws_connect_with_headers(self, url, headers):
                return '{"session_id": "auth-session"}'
            def ws_send(self, session_id, text):
                return '{"sent": true}'
            def ws_receive(self, session_id, timeout_ms):
                return '{"type": "text", "data": "ok"}'
            def ws_close(self, session_id):
                return '{"closed": true}'
        code = """print(phone.accessibility_status())
print(phone.ws_connect("wss://example.test/live"))
print(phone.ws_connect_with_headers("wss://example.test/live", "{}"))
print(phone.ws_send("test-session", "hello"))
print(phone.ws_receive("test-session", 100))
print(phone.ws_close("test-session"))
"""
        output = orion_exec.run(code, phone_api=FakePhone())
        self.assertNotIn("error:", output)
        self.assertIn('"service_connected": true', output)
        self.assertIn('"type": "text"', output)


if __name__ == "__main__":
    unittest.main()
