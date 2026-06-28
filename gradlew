#!/bin/sh
# Wrapper: delegates to system Gradle if the distributed wrapper cannot download.
# In production environments with network access, replace with the standard Gradle wrapper.
exec gradle "$@"
