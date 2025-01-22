package io.jenkins.plugins.adoptopenjdk;

/*
 * #%L
 * Eclipse Temurin installer Plugin
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

import static com.github.tomakehurst.wiremock.client.WireMock.*;
import static com.github.tomakehurst.wiremock.core.WireMockConfiguration.wireMockConfig;
import static hudson.Functions.isWindows;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.github.tomakehurst.wiremock.junit5.WireMockExtension;
import hudson.FilePath;
import hudson.model.*;
import hudson.tasks.BatchFile;
import hudson.tasks.Shell;
import hudson.tools.InstallSourceProperty;
import hudson.tools.ToolInstaller;
import java.nio.charset.StandardCharsets;
import java.util.Collections;
import java.util.Objects;
import org.apache.commons.io.IOUtils;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;
import org.jvnet.hudson.test.JenkinsRule;
import org.jvnet.hudson.test.junit.jupiter.WithJenkins;

@WithJenkins
class AdoptOpenJDKInstallerTest {

    private JenkinsRule jenkinsRule;

    @RegisterExtension
    static WireMockExtension wireMockExtension = WireMockExtension.newInstance()
            .options(wireMockConfig().dynamicPort())
            .build();

    private AdoptOpenJDKInstaller installer;
    private JDK testJdk;
    private Slave agent;

    @BeforeEach
    void setUp(JenkinsRule r) throws Exception {
        jenkinsRule = r;

        // setup agent
        agent = jenkinsRule.createOnlineSlave();
        jenkinsRule.jenkins.addNode(agent);

        // configure jdk
        installer = new AdoptOpenJDKInstaller("jdk8u345-b01");
        testJdk = new JDK(
                "jdk8u345",
                null,
                Collections.singletonList(
                        new InstallSourceProperty(Collections.<ToolInstaller>singletonList(installer))));
        jenkinsRule.jenkins.getJDKs().add(testJdk);

        // download releases from mock
        DownloadService.Downloadable jdkDl = DownloadService.Downloadable.get(AdoptOpenJDKInstaller.class.getName());
        String releases = IOUtils.toString(
                getClass().getResourceAsStream("/" + AdoptOpenJDKInstaller.class.getName()), StandardCharsets.UTF_8);
        jdkDl.getDataFile().write(releases.replaceAll("https://github.com", wireMockExtension.baseUrl()));

        setupStub(".*linux.*", "Linux.tar.gz");
        setupStub(".*win.*", "Win.zip");
        setupStub(".*mac.*", "Mac.tar.gz");
    }

    @Test
    void configRoundtrip() throws Exception {
        jenkinsRule.submit(jenkinsRule.createWebClient().goTo("configureTools").getFormByName("config"));

        JDK jdk = jenkinsRule.jenkins.getJDK(testJdk.getName());
        InstallSourceProperty isp = jdk.getProperties().get(InstallSourceProperty.class);
        assertEquals(1, isp.installers.size());
        jenkinsRule.assertEqualBeans(installer, isp.installers.get(AdoptOpenJDKInstaller.class), "id");
    }

    @Test
    void installFromCache() throws Exception {
        FreeStyleProject freeStyleProject = jenkinsRule.createFreeStyleProject();
        freeStyleProject.setAssignedNode(agent);
        freeStyleProject.setJDK(testJdk);
        freeStyleProject
                .getBuildersList()
                .add(isWindows() ? new BatchFile("java -version") : new Shell("java -version"));

        // start initial build to initialize the cache on master
        FilePath cacheDir = jenkinsRule.jenkins.getRootPath().child("caches/adoptopenjdk");
        assertFalse(cacheDir.exists());
        FreeStyleBuild freeStyleBuild1 = scheduleBuild(freeStyleProject);
        jenkinsRule.assertLogContains(wireMockExtension.baseUrl(), freeStyleBuild1);
        jenkinsRule.assertLogNotContains(cacheDir.getRemote(), freeStyleBuild1);
        assertTrue(cacheDir.exists());

        // use installation on agent
        FreeStyleBuild freeStyleBuild2 = scheduleBuild(freeStyleProject);
        jenkinsRule.assertLogNotContains(wireMockExtension.baseUrl(), freeStyleBuild2);
        jenkinsRule.assertLogNotContains(cacheDir.getRemote(), freeStyleBuild2);

        // delete installation on agent
        FilePath jdkInstallation =
                Objects.requireNonNull(agent.getRootPath()).child("tools/hudson.model.JDK/" + testJdk.getName());
        assertTrue(jdkInstallation.exists());
        jdkInstallation.deleteRecursive();
        assertFalse(jdkInstallation.exists());

        // build again to install from cache on master
        FreeStyleBuild freeStyleBuild3 = scheduleBuild(freeStyleProject);
        jenkinsRule.assertLogContains(cacheDir.getRemote(), freeStyleBuild3);
        jenkinsRule.assertLogNotContains(wireMockExtension.baseUrl(), freeStyleBuild3);
    }

    private void setupStub(String urlRegex, String bodyFile) {
        wireMockExtension.stubFor(get(urlMatching(urlRegex))
                .willReturn(aResponse()
                        .withHeader("Content-Type", "application/octet-stream")
                        .withBodyFile(bodyFile)));
    }

    private FreeStyleBuild scheduleBuild(FreeStyleProject freeStyleProject) throws Exception {
        FreeStyleBuild freeStyleBuild = jenkinsRule.assertBuildStatusSuccess(freeStyleProject.scheduleBuild2(0));
        assertEquals(agent, freeStyleBuild.getBuiltOn());
        jenkinsRule.assertLogContains("mock install", freeStyleBuild);
        return freeStyleBuild;
    }
}
