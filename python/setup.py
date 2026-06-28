from setuptools import setup, find_packages

setup(
    name="suika-ai-sandbox",
    version="0.1.0",
    description="Gymnasium environment and training toolkit for the Suika AI Sandbox",
    long_description=open("../README.md", encoding="utf-8").read()
        if __import__("pathlib").Path("../README.md").exists() else "",
    long_description_content_type="text/markdown",
    author="suikaAI-plus contributors",
    packages=find_packages(),
    python_requires=">=3.10",
    install_requires=[
        "numpy>=1.24.0",
    ],
    extras_require={
        "gym": [
            "gymnasium>=0.29.0",
        ],
        "training": [
            "gymnasium>=0.29.0",
            "stable-baselines3>=2.3.0",
            "torch>=2.1.0",
        ],
        "dev": [
            "pytest>=7.0.0",
            "ruff>=0.4.0",
        ],
    },
    entry_points={
        "console_scripts": [
            "suika-train-ppo=suika.train_ppo:main",
        ],
    },
    classifiers=[
        "Programming Language :: Python :: 3",
        "License :: OSI Approved :: Apache Software License",
        "Topic :: Scientific/Engineering :: Artificial Intelligence",
    ],
)
