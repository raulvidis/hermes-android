"""
hermes-android plugin — registers 42 android_* tools through the hermes-agent
plugin system.

Drop this folder into ~/.hermes/plugins/hermes-android and restart hermes.
"""

import logging
from pathlib import Path

from .android_tool import _SCHEMAS, _HANDLERS, _check_requirements

logger = logging.getLogger(__name__)

# Resolved once at import and read as a module global by register(), so a test
# can point it at a missing file and prove the failure is reported.
_SKILL_PATH = Path(__file__).resolve().parent / "skill.md"

_SKILL_DESCRIPTION = (
    "Control an Android phone remotely — navigate apps, tap, type, swipe. "
    "Load this before any android_* interaction: it explains how to read "
    "screens via the accessibility tree instead of screenshots."
)


def register(ctx):
    """Called by hermes-agent plugin loader. Registers all android_* tools."""
    for tool_name, schema in _SCHEMAS.items():
        ctx.register_tool(
            name=tool_name,
            toolset="android",
            schema=schema,
            handler=_HANDLERS[tool_name],
            check_fn=(lambda: True) if tool_name == "android_setup" else _check_requirements,
        )

    # Register the usage skill so the agent learns to prefer the accessibility
    # tree (android_read_screen / android_find_nodes / android_tap_text) over
    # screenshot-based visual reading. Without this the agent defaults to
    # android_screenshot + vision for every screen inspection.
    #
    # Each way this can fail is checked and reported separately. It used to be
    # one `except (AttributeError, TypeError): pass`, which also swallowed a
    # missing skill.md and a renamed kwarg — so a hand-copied plugin folder
    # without skill.md showed "42 tools" in /plugins and silently lost the
    # guidance, with nothing in the log to say why.
    if not hasattr(ctx, "register_skill"):
        logger.info(
            "hermes-agent has no register_skill API; the %d android_* tools are "
            "registered but the android usage skill will not be discoverable",
            len(_SCHEMAS),
        )
        return

    if not _SKILL_PATH.is_file():
        logger.warning(
            "android skill.md not found at %s — the plugin folder was probably "
            "copied without it. Tools still work, but the agent will not get the "
            "accessibility-tree-first guidance and will fall back to screenshots.",
            _SKILL_PATH,
        )
        return

    try:
        ctx.register_skill(
            name="android",
            path=_SKILL_PATH,
            description=_SKILL_DESCRIPTION,
        )
    except Exception:
        # Not an "old API" case — hasattr already ruled that out. Losing the
        # skill must not cost the tools, but the reason has to reach the log.
        logger.exception("register_skill failed; android tools are registered without the usage skill")
