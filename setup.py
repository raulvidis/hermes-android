"""Packaging hints only — no project metadata.

`name`, `version`, `license`, `dependencies` and `requires-python` are declared
once, in pyproject.toml's `[project]` table, and setuptools (>=68, already
required by `[build-system]`) reads them from there. This file used to repeat
`version`, `install_requires` and `python_requires`, which is how the commit
titled `release: v0.5.0` shipped pyproject.toml at 0.5.0 and setup.py at 0.4.1
(review F8): two declarations, and nothing that forced them to agree.
tests/test_version_consistency.py fails if any of them comes back.

What stays here is what `[project]` does not express: which packages to ship,
and the skill markdown that is not Python.
"""

from setuptools import find_packages, setup

setup(
    packages=find_packages(),
    package_data={
        "": ["skills/android/*.md"],
    },
)
