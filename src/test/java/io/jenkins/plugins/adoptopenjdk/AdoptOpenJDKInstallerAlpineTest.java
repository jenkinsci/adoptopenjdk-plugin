/*
 * The MIT License
 *
 * Copyright 2025 Mark Waite.
 *
 * Permission is hereby granted, free of charge, to any person obtaining a copy
 * of this software and associated documentation files (the "Software"), to deal
 * in the Software without restriction, including without limitation the rights
 * to use, copy, modify, merge, publish, distribute, sublicense, and/or sell
 * copies of the Software, and to permit persons to whom the Software is
 * furnished to do so, subject to the following conditions:
 *
 * The above copyright notice and this permission notice shall be included in
 * all copies or substantial portions of the Software.
 *
 * THE SOFTWARE IS PROVIDED "AS IS", WITHOUT WARRANTY OF ANY KIND, EXPRESS OR
 * IMPLIED, INCLUDING BUT NOT LIMITED TO THE WARRANTIES OF MERCHANTABILITY,
 * FITNESS FOR A PARTICULAR PURPOSE AND NONINFRINGEMENT. IN NO EVENT SHALL THE
 * AUTHORS OR COPYRIGHT HOLDERS BE LIABLE FOR ANY CLAIM, DAMAGES OR OTHER
 * LIABILITY, WHETHER IN AN ACTION OF CONTRACT, TORT OR OTHERWISE, ARISING FROM,
 * OUT OF OR IN CONNECTION WITH THE SOFTWARE OR THE USE OR OTHER DEALINGS IN
 * THE SOFTWARE.
 */
package io.jenkins.plugins.adoptopenjdk;

import static hudson.Functions.isWindows;
import static io.jenkins.plugins.adoptopenjdk.AdoptOpenJDKInstaller.Platform.AIX;
import static io.jenkins.plugins.adoptopenjdk.AdoptOpenJDKInstaller.Platform.ALPINE_LINUX;
import static io.jenkins.plugins.adoptopenjdk.AdoptOpenJDKInstaller.Platform.LINUX;
import static io.jenkins.plugins.adoptopenjdk.AdoptOpenJDKInstaller.Platform.MACOS;
import static io.jenkins.plugins.adoptopenjdk.AdoptOpenJDKInstaller.Platform.SOLARIS;
import static io.jenkins.plugins.adoptopenjdk.AdoptOpenJDKInstaller.Platform.WINDOWS;
import static org.hamcrest.CoreMatchers.anyOf;
import static org.hamcrest.CoreMatchers.is;
import static org.hamcrest.MatcherAssert.assertThat;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

public class AdoptOpenJDKInstallerAlpineTest {

    private String savedOsReleaseLocation = null;

    @BeforeEach
    void saveOsReleaseLocation() {
        savedOsReleaseLocation = AdoptOpenJDKInstaller.Platform.osReleaseLocation;
    }

    @AfterEach
    void restoreOsReleaseLocation() {
        AdoptOpenJDKInstaller.Platform.osReleaseLocation = savedOsReleaseLocation;
    }

    @Test
    void fileNotFoundDoesNotThrowException() {
        AdoptOpenJDKInstaller.Platform.osReleaseLocation = "non-existent-file";
        assertFalse(AdoptOpenJDKInstaller.Platform.isAlpineLinux());
    }

    @Test
    void alpineLinux() throws Exception {
        AdoptOpenJDKInstaller.Platform.osReleaseLocation =
                getClass().getResource("alpine-os-release").getFile();
        if (isWindows()) {
            assertThat(AdoptOpenJDKInstaller.Platform.current(), is(WINDOWS));
        } else {
            assertTrue(AdoptOpenJDKInstaller.Platform.isAlpineLinux());
            assertThat(AdoptOpenJDKInstaller.Platform.current(), is(ALPINE_LINUX));
        }
    }

    @Test
    void ubuntuLinux() throws Exception {
        AdoptOpenJDKInstaller.Platform.osReleaseLocation =
                getClass().getResource("ubuntu-os-release").getFile();
        if (isWindows()) {
            assertThat(AdoptOpenJDKInstaller.Platform.current(), is(WINDOWS));
        } else {
            assertFalse(AdoptOpenJDKInstaller.Platform.isAlpineLinux());
            assertThat(AdoptOpenJDKInstaller.Platform.current(), is(LINUX));
        }
    }

    @Test
    void currentMatchesExpectation() throws Exception {
        if (isWindows()) {
            assertThat(AdoptOpenJDKInstaller.Platform.current(), is(WINDOWS));
        } else {
            assertThat(
                    AdoptOpenJDKInstaller.Platform.current(),
                    anyOf(is(ALPINE_LINUX), is(LINUX), is(SOLARIS), is(MACOS), is(AIX)));
        }
    }
}
