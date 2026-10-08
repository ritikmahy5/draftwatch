"""Exit codes of MEASUREMENT_CONTRACT.md "Invocation" and the error that carries one."""

EXIT_OK = 0
EXIT_UNEXPECTED = 1
EXIT_BAD_ARGUMENTS = 2
EXIT_MODEL_LOAD = 3
EXIT_OUT_OF_MEMORY = 4
EXIT_BACKEND_COUNTERS = 5


class HarnessError(Exception):
    """A failure with its contract exit code; the message names what failed."""

    def __init__(self, code, message):
        super().__init__(message)
        self.code = code


def is_out_of_memory(error):
    """True for ``torch.cuda.OutOfMemoryError`` (and ``torch.OutOfMemoryError``), without
    importing torch, so the pure modules stay testable without it."""
    for cls in type(error).__mro__:
        if cls.__name__ == "OutOfMemoryError" and cls.__module__.startswith("torch"):
            return True
    return False
