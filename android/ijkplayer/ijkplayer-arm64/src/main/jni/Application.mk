# Copyright (c) 2013-2014 Bilibili
# copyright (c) 2013-2014 Zhang Rui <bbcallen@gmail.com>
#
# This file is part of ijkPlayer.
#
# ijkPlayer is free software; you can redistribute it and/or
# modify it under the terms of the GNU Lesser General Public
# License as published by the Free Software Foundation; either
# version 2.1 of the License, or (at your option) any later version.
#
# ijkPlayer is distributed in the hope that it will be useful,
# but WITHOUT ANY WARRANTY; without even the implied warranty of
# MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the GNU
# Lesser General Public License for more details.
#
# You should have received a copy of the GNU Lesser General Public
# License along with ijkPlayer; if not, write to the Free Software
# Foundation, Inc., 51 Franklin Street, Fifth Floor, Boston, MA 02110-1301 USA

# APP_OPTIM := release
APP_OPTIM := debug
APP_PLATFORM := android-21
APP_ABI := arm64-v8a
APP_PIE := false

#NDK_TOOLCHAIN_VERSION=4.9
# NKD r23 使用 LOCAL_CLANG 和 LOCAL_TOOLCHAIN_VERSION，不再使用 NDK_TOOLCHAIN_VERSION

APP_STL := c++_static

#APP_CFLAGS:= -O0 -Wall -pipe
APP_CFLAGS := -O0 -w -pipe \
    -Werror=format \
    -ffast-math \
    -fstrict-aliasing -Werror=strict-aliasing \
    -Wno-psabi -Wa,--noexecstack \
    -Wno-deprecated-declarations \
    -DANDROID
#   -DANDROID -DNDEBUG
