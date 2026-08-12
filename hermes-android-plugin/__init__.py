"""
hermes-android plugin — registers 42 android_* tools through the hermes-agent
plugin system.

Drop this folder into ~/.hermes/plugins/hermes-android and restart hermes.
"""

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
