from setuptools import setup, find_packages

setup(
    name="suika-ai-sandbox",
    version="0.1.0",
    description="Gymnasium environment for Suika AI Sandbox",
    packages=find_packages(),
    python_requires=">=3.10",
    install_requires=[
        "gymnasium>=0.29.0",
        "numpy>=1.24.0",
    ],
    extras_require={
        "training": [
            "stable-baselines3>=2.3.0",
            "torch>=2.1.0",
        ],
        "dev": [
            "pytest>=7.0.0",
        ],
    },
)
