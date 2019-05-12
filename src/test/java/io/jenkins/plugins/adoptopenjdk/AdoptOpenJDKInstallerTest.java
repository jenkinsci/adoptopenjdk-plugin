package io.jenkins.plugins.adoptopenjdk;

/*
 * #%L
 * AdoptOpenJDK installer Plugin
 * %%
 * Copyright (C) 2016 - 2019 Mads Mohr Christensen
 * %%
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
 * #L%
 */

import hudson.model.JDK;
import hudson.tools.InstallSourceProperty;
import hudson.tools.ToolInstaller;
import org.junit.Assert;
import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;
import org.jvnet.hudson.test.JenkinsRule;

import java.util.Collections;

public class AdoptOpenJDKInstallerTest {

    @Rule
    public JenkinsRule j = new JenkinsRule();

    @Rule
    public TemporaryFolder tmp = new TemporaryFolder();

    private AdoptOpenJDKInstaller installer;
    private JDK testJdk;

    @Before
    public void setUp() throws Exception {
        installer = new AdoptOpenJDKInstaller("jdk8u172-b11");
        testJdk = new JDK("test", tmp.getRoot().getAbsolutePath(), Collections.singletonList(
                new InstallSourceProperty(Collections.<ToolInstaller>singletonList(installer)))
        );

        j.jenkins.getJDKs().add(testJdk);
    }

    @Test
    public void configRoundtrip() throws Exception {
        j.submit(j.createWebClient().goTo("configureTools").getFormByName("config"));

        JDK jdk = j.jenkins.getJDK("test");
        InstallSourceProperty isp = jdk.getProperties().get(InstallSourceProperty.class);
        Assert.assertEquals(1, isp.installers.size());
        j.assertEqualBeans(installer, isp.installers.get(AdoptOpenJDKInstaller.class), "id");
    }
}
