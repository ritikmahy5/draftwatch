"""The vLLM side of the reference harness (contract "Reference backend"; D80, D82).

This is the only module that imports vLLM, torch, transformers, or PEFT, and it imports them
only when a backend is created, so the rest of the harness is testable without them.
"""

import os
import shutil
import tempfile

from .counters import Snapshot
from .errors import (EXIT_BACKEND_COUNTERS, EXIT_MODEL_LOAD, HarnessError, is_out_of_memory)

# vLLM settings read from the environment that change what is measured, pinned so that a
# measurement never depends on the caller's environment (DECISIONS.md D87). With FlashInfer's
# sampler, temperature > 0 draws a different random stream than vLLM's native sampler, and the
# FlashInfer kernel is compiled at first use, which needs nvcc.
PINNED_ENVIRONMENT = {"VLLM_USE_FLASHINFER_SAMPLER": "0"}

# (attribute of vllm's SpeculativeConfig, the value it must have, why) - D80.
REQUIRED_CONFIG = (
    ("method", "draft_model", "the draft must be the model at --draft-path"),
    ("rejection_sample_method", "standard", "only standard verification is per-token and prefix-based"),
    ("synthetic_acceptance_rates", None, "synthetic sampling replaces verification"),
    ("synthetic_acceptance_length", None, "synthetic sampling replaces verification"),
    ("enable_adaptive_verification", False, "adaptive verification changes the draft budget"),
    ("num_speculative_tokens_per_batch_size", None, "a per-batch-size schedule changes k"),
)


def check_speculative_config(config, k):
    """Exits 5 naming the first setting that would change what the counters count (D80)."""
    for name, want, why in REQUIRED_CONFIG + (("num_speculative_tokens", k, "k must be as asked"),):
        if not hasattr(config, name):
            raise HarnessError(
                EXIT_BACKEND_COUNTERS,
                "vLLM's SpeculativeConfig has no '%s'; this harness was checked against vLLM"
                " v0.31.0 (DECISIONS.md D80)" % name)
        have = getattr(config, name)
        if have != want:
            raise HarnessError(
                EXIT_BACKEND_COUNTERS,
                "vLLM resolved speculative_config.%s to %r, but it must be %r: %s"
                % (name, have, want, why))


class VllmBackend:
    """One vLLM engine with the draft model, used for every call of one run."""

    def __init__(self, target_checkpoint, base_model, draft_path, decoding):
        self.k = decoding["num_speculative_tokens"]
        self.decoding = decoding
        self._merged_dir = None
        os.environ.update(PINNED_ENVIRONMENT)  # before vLLM reads its environment
        try:
            import torch  # noqa: F401  (imported here so OOM errors can be recognized)
            import vllm
            from vllm import LLM, SamplingParams
        except Exception as e:
            # Not only ImportError: a compiled library built for another CUDA version fails with
            # OSError while vLLM imports. Retrying cannot fix the environment (D84).
            raise HarnessError(
                EXIT_MODEL_LOAD, "cannot import vLLM: %s: %s" % (type(e).__name__, e))
        self._sampling_params = SamplingParams
        self.backend = "vllm==" + vllm.__version__
        model = target_checkpoint
        if base_model is not None:
            model = self._merge(base_model, target_checkpoint, decoding["dtype"])
        try:
            self.llm = LLM(
                model=model,
                dtype=decoding["dtype"],
                speculative_config={
                    "method": "draft_model",
                    "model": draft_path,
                    "num_speculative_tokens": self.k,
                },
                disable_log_stats=False,  # LLM defaults to True, and then no counters exist
            )
        except Exception as e:
            if is_out_of_memory(e):
                raise
            raise HarnessError(EXIT_MODEL_LOAD, "vLLM cannot load the models: %s" % e)
        check_speculative_config(self.llm.llm_engine.vllm_config.speculative_config, self.k)

    @property
    def adapter_handling(self):
        return "merged" if self._merged_dir is not None else "none"

    def _merge(self, base_model, adapter, dtype):
        """PEFT merge_and_unload into a temporary directory (DECISIONS.md D8)."""
        try:
            import torch
            from peft import PeftModel
            from transformers import AutoModelForCausalLM, AutoTokenizer
        except ImportError as e:
            raise HarnessError(EXIT_MODEL_LOAD, "cannot import PEFT or transformers: %s" % e)
        self._merged_dir = tempfile.mkdtemp(prefix="draftwatch-merged-")
        try:
            torch_dtype = getattr(torch, dtype) if hasattr(torch, dtype) else "auto"
            base = AutoModelForCausalLM.from_pretrained(base_model, torch_dtype=torch_dtype)
            merged = PeftModel.from_pretrained(base, adapter).merge_and_unload()
            merged.save_pretrained(self._merged_dir)
            AutoTokenizer.from_pretrained(base_model).save_pretrained(self._merged_dir)
        except Exception as e:
            if is_out_of_memory(e):
                raise
            raise HarnessError(
                EXIT_MODEL_LOAD, "cannot merge adapter %s into %s: %s" % (adapter, base_model, e))
        return self._merged_dir

    def snapshot(self):
        return Snapshot.from_metrics(self.llm.get_metrics(), self.k)

    def generate(self, prompt, seed):
        params = self._sampling_params(
            temperature=self.decoding["temperature"],
            max_tokens=self.decoding["max_new_tokens"],
            seed=seed,
        )
        if prompt.text is not None:
            self.llm.generate([prompt.text], params, use_tqdm=False)
        else:
            self.llm.chat(prompt.messages, params, use_tqdm=False)

    def hardware(self):
        import torch

        count = torch.cuda.device_count() if torch.cuda.is_available() else 0
        return {"gpu": torch.cuda.get_device_name(0) if count else "none", "count": count}

    def close(self):
        if self._merged_dir is not None:
            shutil.rmtree(self._merged_dir, ignore_errors=True)
