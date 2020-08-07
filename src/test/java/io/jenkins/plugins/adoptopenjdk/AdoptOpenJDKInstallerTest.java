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

import com.github.tomakehurst.wiremock.core.WireMockConfiguration;
import com.github.tomakehurst.wiremock.junit.WireMockRule;
import hudson.FilePath;
import hudson.model.*;
import hudson.tasks.BatchFile;
import hudson.tasks.Shell;
import hudson.tools.InstallSourceProperty;
import hudson.tools.ToolInstaller;
import org.apache.commons.io.IOUtils;
import org.junit.*;
import org.jvnet.hudson.test.BuildWatcher;
import org.jvnet.hudson.test.JenkinsRule;

import java.nio.charset.StandardCharsets;
import java.util.Collections;
import java.util.Objects;

import static com.github.tomakehurst.wiremock.client.WireMock.*;
import static hudson.Functions.isWindows;

public class AdoptOpenJDKInstallerTest {

    @ClassRule
    public static BuildWatcher buildWatcher = new BuildWatcher();

    @Rule
    public JenkinsRule j = new JenkinsRule();

    @Rule
    public WireMockRule wireMockRule = new WireMockRule(WireMockConfiguration.options().dynamicPort());

    private AdoptOpenJDKInstaller installer;
    private JDK testJdk;
    private Slave slave;

    @Before
    public void setUp() throws Exception {
        // setup slave
        slave = j.createOnlineSlave();
        j.jenkins.addNode(slave);

        // configure jdk
        installer = new AdoptOpenJDKInstaller("jdk8u172-b11");
        testJdk = new JDK("jdk8u172", null, Collections.singletonList(
                new InstallSourceProperty(Collections.<ToolInstaller>singletonList(installer)))
        );
        j.jenkins.getJDKs().add(testJdk);

        // download releases from mock
        DownloadService.Downloadable jdkDl = DownloadService.Downloadable.get(AdoptOpenJDKInstaller.class.getName());
        String releases = IOUtils.toString(getClass().getResourceAsStream("/" + AdoptOpenJDKInstaller.class.getName()), StandardCharsets.UTF_8);
        jdkDl.getDataFile().write(releases.replaceAll("https://github.com", wireMockRule.baseUrl()));

        setupStub(".*Linux.*", "Linux.tar.gz");
        setupStub(".*Win.*", "Win.zip");
        setupStub(".*Mac.*", "Mac.tar.gz");
    }

    @Test
    public void configRoundtrip() throws Exception {
        j.submit(j.createWebClient().goTo("configureTools").getFormByName("config"));

        JDK jdk = j.jenkins.getJDK(testJdk.getName());
        InstallSourceProperty isp = jdk.getProperties().get(InstallSourceProperty.class);
        Assert.assertEquals(1, isp.installers.size());
        j.assertEqualBeans(installer, isp.installers.get(AdoptOpenJDKInstaller.class), "id");
    }

    @Test
    public void installFromCache() throws Exception {
        FreeStyleProject freeStyleProject = j.createFreeStyleProject();
        freeStyleProject.setAssignedNode(slave);
        freeStyleProject.setJDK(testJdk);
        freeStyleProject.getBuildersList().add(isWindows() ? new BatchFile("java -version") : new Shell("java -version"));

        // start initial build to initialize the cache on master
        FilePath cacheDir = j.jenkins.getRootPath().child("caches/adoptopenjdk");
        Assert.assertFalse(cacheDir.exists());
        FreeStyleBuild freeStyleBuild1 = scheduleBuild(freeStyleProject);
        j.assertLogContains(wireMockRule.baseUrl(), freeStyleBuild1);
        j.assertLogNotContains(cacheDir.getRemote(), freeStyleBuild1);
        Assert.assertTrue(cacheDir.exists());

        // use installation on slave
        FreeStyleBuild freeStyleBuild2 = scheduleBuild(freeStyleProject);
        j.assertLogNotContains(wireMockRule.baseUrl(), freeStyleBuild2);
        j.assertLogNotContains(cacheDir.getRemote(), freeStyleBuild2);

        // delete installation on slave
        FilePath jdkInstallation = Objects.requireNonNull(slave.getRootPath()).child("tools/hudson.model.JDK/" + testJdk.getName());
        Assert.assertTrue(jdkInstallation.exists());
        jdkInstallation.deleteRecursive();
        Assert.assertFalse(jdkInstallation.exists());

        // build again to install from cache on master
        FreeStyleBuild freeStyleBuild3 = scheduleBuild(freeStyleProject);
        j.assertLogContains(cacheDir.getRemote(), freeStyleBuild3);
        j.assertLogNotContains(wireMockRule.baseUrl(), freeStyleBuild3);
    }

    private void setupStub(String urlRegex, String bodyFile) {
        stubFor(get(urlMatching(urlRegex))
                .willReturn(aResponse()
                        .withHeader("Content-Type", "application/octet-stream")
                        .withBodyFile(bodyFile)
                )
        );
    }

    private FreeStyleBuild scheduleBuild(FreeStyleProject freeStyleProject) throws Exception {
        FreeStyleBuild freeStyleBuild = j.assertBuildStatusSuccess(freeStyleProject.scheduleBuild2(0));
        Assert.assertEquals(slave, freeStyleBuild.getBuiltOn());
        j.assertLogContains("mock install", freeStyleBuild);
        return freeStyleBuild;
    }
}
