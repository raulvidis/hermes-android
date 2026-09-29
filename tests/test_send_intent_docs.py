"""The android_send_intent examples an agent reads must be calls the bridge accepts.

Review L5: the docstring (and the schema `description`/`action` hint, which is
what actually reaches the agent) advertised
`android_send_intent("android.settings.WIFI_SETTINGS")` — an action
`ActionExecutor.sendIntent` blocks twice over, once by name and once by the
`android.settings.` prefix rule. An agent following its own tool description got
a safety rejection on the first thing it tried, and the docstring also advertised
"setting toggles", which the same rule removed. The `data_uri` hint
(`tel:+1234567890`) had the same defect: `tel` is a denied URI scheme.

The denylists live in Kotlin (`ActionExecutor.kt`), and this repo already pins
docs to code across that boundary (tests/test_android_relay.py pins
docs/architecture.md to `_ROUTES`). Here the prefixes and schemes are restated
rather than parsed out of ActionExecutor.kt, because that file is being
restructured; docs/troubleshooting.md is the prose source they mirror.
"""

import re
import runpy
from pathlib import Path

from tools import android_tool

# Mirrors ActionExecutor.sendIntent: every android.settings.* / android.provider.action.*
# action is refused, and these URI schemes are denied regardless of action.
# `sms` and `mms` joined the denylist with CTRL-2, which also closed the
# personal-data `content://` providers; the schemes are what an advertised
# example can trip, so they are the part this guard has to keep in step.
BLOCKED_ACTION_PREFIXES = ("android.settings.", "android.provider.action.")
BLOCKED_URI_SCHEMES = ("intent", "market", "sms", "smsto", "mms", "mmsto", "tel")

PLUGIN_COPY = Path(__file__).resolve().parents[1] / "hermes-android-plugin" / "android_tool.py"


def _examples(ns) -> dict:
    """Every string the agent can read about android_send_intent, keyed by where it came from.

    `ns` is either the imported tools.android_tool module or the dict returned by
    runpy.run_path for the plugin copy.
    """
    get = (lambda name: ns[name]) if isinstance(ns, dict) else (lambda name: getattr(ns, name))
    schema = get("_SCHEMAS")["android_send_intent"]
    props = schema["parameters"]["properties"]
    return {
        "docstring": get("android_send_intent").__doc__ or "",
        "schema.description": schema["description"],
        "schema.action": props["action"]["description"],
        "schema.data_uri": props["data_uri"]["description"],
    }


def _plugin_module() -> dict:
    return runpy.run_path(str(PLUGIN_COPY))


def _advertised(text: str) -> list:
    """The concrete calls a text tells the agent to make.

    Only `Example:`/`e.g.` snippets count — a docstring has to be able to *name*
    the blocked prefixes to warn about them, and that is not an invitation.
    """
    found = re.findall(r'android_send_intent\(\s*["\']([^"\']+)["\']', text)
    found += re.findall(r'android_send_intent\([^)]*["\']([^"\']+)["\']\s*\)', text)
    found += re.findall(r'e\.g\.\s*([^\s,]+)', text)
    found += re.findall(r'Example:\s*([^\n]+)', text)
    return [f.strip().strip(".,)\"'") for f in found if f.strip()]


def _is_blocked_action(action: str) -> bool:
    return "*" not in action and action.startswith(BLOCKED_ACTION_PREFIXES)


def _is_blocked_uri(uri: str) -> bool:
    if "*" in uri or ":" not in uri:
        return False
    scheme = uri.split("://")[0].split(":")[0].lower()
    return scheme in BLOCKED_URI_SCHEMES


class TestSendIntentExamplesArePermitted:
    def test_no_advertised_example_uses_a_blocked_action(self):
        offenders = []
        for where, text in _examples(android_tool).items():
            for example in _advertised(text):
                for token in re.split(r'[\s,]+', example):
                    if _is_blocked_action(token.strip('"\'()')):
                        offenders.append(f"{where} advertises blocked action {token}")
        assert not offenders, "; ".join(offenders)

    def test_no_advertised_example_uses_a_blocked_uri_scheme(self):
        offenders = []
        for where, text in _examples(android_tool).items():
            for example in _advertised(text):
                for token in re.split(r'[\s,]+', example):
                    if _is_blocked_uri(token.strip('"\'()')):
                        offenders.append(f"{where} advertises denied URI {token}")
        assert not offenders, "; ".join(offenders)

    def test_examples_are_actually_advertised(self):
        """Guard the guard: if the extractor stops matching, the two tests above
        pass vacuously."""
        examples = _advertised(android_tool.android_send_intent.__doc__ or "")
        examples += _advertised(android_tool._SCHEMAS["android_send_intent"]["description"])
        examples += _advertised(
            android_tool._SCHEMAS["android_send_intent"]["parameters"]["properties"]["action"]["description"]
        )
        assert examples, "no advertised example found — the extractor regexes have drifted"

    def test_docstring_no_longer_claims_setting_toggles(self):
        """The blocklist removed settings toggles; the description must not
        still promise them, or the agent retries variants of a refused call."""
        for where, text in _examples(android_tool).items():
            assert "setting toggles" not in text.lower(), where
            assert "deep linking, settings" not in text.lower(), where

    def test_docstring_states_the_blocklist(self):
        """The agent needs to know what is refused before it guesses."""
        doc = android_tool.android_send_intent.__doc__ or ""
        assert "blocked" in doc.lower()
        assert "android.settings" in doc

    def test_plugin_copy_advertises_the_same_examples(self):
        """AGENTS.md parity rule: the plugin registers its own copy, so a fix
        applied to tools/ only would leave the installed plugin lying."""
        plugin = _plugin_module()
        assert _examples(plugin) == _examples(android_tool), (
            "tools/android_tool.py and hermes-android-plugin/android_tool.py "
            "disagree on what android_send_intent advertises"
        )
