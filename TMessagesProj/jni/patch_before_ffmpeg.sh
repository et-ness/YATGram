#!/bin/bash

set -e

patch -d ffmpeg -p1 < patches/ffmpeg/0002-configure-dav1d.patch
