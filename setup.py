from setuptools import setup, find_packages

setup(
    name="hermes-android",
    version="0.4.1",
    license="MIT",
    packages=find_packages(),
    install_requires=[
        "requests>=2.28.0",
        "aiohttp>=3.9.0",
    ],
    package_data={
        "": ["skills/android/*.md"],
    },
    entry_points={
        "console_scripts": ["hermes-robot-dialog=tools.robot_dialog:main"],
    },
    python_requires=">=3.11",
)
