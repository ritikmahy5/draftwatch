import types
import unittest

import _paths  # noqa: F401
from draftwatch_harness.errors import EXIT_BACKEND_COUNTERS, HarnessError
from draftwatch_harness.vllm_backend import check_speculative_config


def config(**changes):
    """vLLM v0.31.0's SpeculativeConfig as the harness asks for it (D80)."""
    fields = dict(method="draft_model", num_speculative_tokens=3,
                  rejection_sample_method="standard", synthetic_acceptance_rates=None,
                  synthetic_acceptance_length=None, enable_adaptive_verification=False,
                  num_speculative_tokens_per_batch_size=None)
    fields.update(changes)
    return types.SimpleNamespace(**fields)


class SpeculativeConfigTest(unittest.TestCase):
    def assertRefused(self, cfg, contains):
        with self.assertRaises(HarnessError) as c:
            check_speculative_config(cfg, 3)
        self.assertEqual(EXIT_BACKEND_COUNTERS, c.exception.code)
        self.assertIn(contains, str(c.exception))

    def test_the_requested_configuration_passes(self):
        check_speculative_config(config(), 3)

    def test_settings_that_change_what_the_counters_count_are_refused(self):
        self.assertRefused(config(method="eagle3"), "method")
        self.assertRefused(config(rejection_sample_method="synthetic"), "rejection_sample_method")
        self.assertRefused(config(synthetic_acceptance_length=2.0), "synthetic_acceptance_length")
        self.assertRefused(config(enable_adaptive_verification=True),
                           "enable_adaptive_verification")
        self.assertRefused(config(num_speculative_tokens_per_batch_size=[(1, 8, 2)]),
                           "num_speculative_tokens_per_batch_size")
        self.assertRefused(config(num_speculative_tokens=2), "num_speculative_tokens")

    def test_an_unknown_vllm_version_without_a_field_is_refused(self):
        cfg = config()
        del cfg.enable_adaptive_verification
        self.assertRefused(cfg, "has no 'enable_adaptive_verification'")


if __name__ == "__main__":
    unittest.main()


class ImportFailureTest(unittest.TestCase):
    def test_a_library_that_fails_to_load_while_importing_vllm_is_exit_3(self):
        import importlib.abc
        import sys

        from draftwatch_harness.errors import EXIT_MODEL_LOAD
        from draftwatch_harness.vllm_backend import VllmBackend

        class Broken(importlib.abc.MetaPathFinder):
            def find_spec(self, name, path=None, target=None):
                if name in ("torch", "vllm"):
                    raise OSError("libnvrtc.so.13: cannot open shared object file")
                return None

        saved = {m: sys.modules.pop(m) for m in list(sys.modules) if m in ("torch", "vllm")}
        sys.meta_path.insert(0, Broken())
        try:
            with self.assertRaises(HarnessError) as c:
                VllmBackend("t", None, "d", {"num_speculative_tokens": 3, "dtype": "bfloat16",
                                             "temperature": 0, "max_new_tokens": 8})
        finally:
            sys.meta_path.pop(0)
            sys.modules.update(saved)
        self.assertEqual(EXIT_MODEL_LOAD, c.exception.code)
        self.assertIn("OSError: libnvrtc.so.13", str(c.exception))
