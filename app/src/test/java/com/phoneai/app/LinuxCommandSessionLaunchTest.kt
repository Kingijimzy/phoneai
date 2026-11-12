package com.phoneai.app

import org.junit.Assert.assertArrayEquals
import org.junit.Test

class LinuxCommandSessionLaunchTest {
    @Test fun doesNotDuplicateExecutableWhenLaunchConfigAlreadyContainsIt() {
        val args = arrayOf("/data/app/lib/libproot.so", "-0", "-r", "/guest", "/bin/sh", "-i")
        assertArrayEquals(args, LinuxCommandSession.normalizedLaunchArguments(args.first(), args))
    }

    @Test fun prependsExecutableWhenLaunchConfigContainsOnlyArguments() {
        val args = arrayOf("-0", "-r", "/guest", "/bin/sh", "-i")
        assertArrayEquals(
            arrayOf("/data/app/lib/libproot.so", "-0", "-r", "/guest", "/bin/sh", "-i"),
            LinuxCommandSession.normalizedLaunchArguments("/data/app/lib/libproot.so", args)
        )
    }
}
