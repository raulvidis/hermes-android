"""
hermes-android plugin — registers 42 android_* tools through the hermes-agent
plugin system.

Drop this folder into ~/.hermes/plugins/hermes-android and restart hermes.
"""

from pathlib import Path

from .android_tool import _SCHEMAS, _HANDLERS, _check_requirements


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
    try:
        ctx.register_skill(
            name="android",
            path=Path(__file__).resolve().parent / "skill.md",
            description=(
                "Control an Android phone remotely — navigate apps, tap, type, swipe. "
                "Load this before any android_* interaction: it explains how to read "
                "screens via the accessibility tree instead of screenshots."
            ),
        )
    except (AttributeError, TypeError):
        # hermes-agent older than the register_skill API — tools still work,
        # the skill just won't be discoverable.
        pass
