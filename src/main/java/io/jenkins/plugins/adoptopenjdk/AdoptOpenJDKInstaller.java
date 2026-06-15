package io.jenkins.plugins.adoptopenjdk;

/*
 * #%L
 * Eclipse Temurin installer Plugin
 * %%
 * Copyright (C) 2016 - 2026 Mads Mohr Christensen
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

import edu.umd.cs.findbugs.annotations.NonNull;
import edu.umd.cs.findbugs.annotations.SuppressFBWarnings;
import hudson.Extension;
import hudson.FilePath;
import hudson.model.DownloadService;
import hudson.model.JDK;
import hudson.model.Node;
import hudson.model.TaskListener;
import hudson.remoting.VirtualChannel;
import hudson.tools.ToolInstallation;
import hudson.tools.ToolInstaller;
import hudson.tools.ToolInstallerDescriptor;
import hudson.tools.ZipExtractionInstaller;
import hudson.util.DirScanner;
import hudson.util.FileVisitor;
import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.io.Serializable;
import java.nio.charset.Charset;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.time.Duration;
import java.time.Instant;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.Map;
import java.util.WeakHashMap;
import java.util.stream.Stream;
import jenkins.model.Jenkins;
import jenkins.security.MasterToSlaveCallable;
import net.sf.json.JSONObject;
import org.apache.commons.io.input.CountingInputStream;
import org.jenkinsci.Symbol;
import org.kohsuke.stapler.DataBoundConstructor;

/**
 * Install OpenJDK from <a href="https://adoptium.net">Adoptium</a>
 * Based on <a href="https://github.com/jenkinsci/jdk-tool-plugin">Oracle Java SE Development Kit Installer</a>
 */
public class AdoptOpenJDKInstaller extends ToolInstaller {

    private static boolean DISABLE_CACHE = Boolean.getBoolean(AdoptOpenJDKInstaller.class.getName() + ".cache.disable");
    private static Long OLD_LATEST_RELEASE_CLEANUP_DAYS =
            Long.getLong(AdoptOpenJDKInstaller.class.getName() + ".cleanup.days", 30);

    /**
     * Eclipse Temurin release id
     */
    public final String id;

    @DataBoundConstructor
    public AdoptOpenJDKInstaller(String id) {
        super(null);
        this.id = id;
    }

    @NonNull
    private static AdoptOpenJDKFamilyList getAdoptOpenJDKFamilyList() throws IOException {
        AdoptOpenJDKList list = AdoptOpenJDKList.all().get(AdoptOpenJDKList.class);
        if (list == null) {
            throw new IllegalStateException(Messages.AdoptOpenJDKInstaller_getAdoptOpenJDKFamilyList_NoDownloadable());
        }
        return list.toList();
    }

    private AdoptOpenJDKFamilyList getJDKFamilyList() throws IOException {
        AdoptOpenJDKFamilyList familyList = getAdoptOpenJDKFamilyList();
        if (familyList.isEmpty()) {
            throw new IOException(Messages.AdoptOpenJDKInstaller_performInstallation_emptyJdkFamilyList());
        }
        return familyList;
    }

    // TODO: Check if invocations can be reduced/cached?! per run execution and node
    @Override
    public FilePath performInstallation(ToolInstallation tool, Node node, TaskListener log)
            throws IOException, InterruptedException {
        FilePath expected = preferredLocation(tool, node);

        try {
            final String id; // the (if necessary resolved) id of a specific release
            AdoptOpenJDKRelease release = null;

            String latestFeature = AdoptOpenJDKFamily.getFeatureVersionIfLatest(this.id);
            if (latestFeature != null) { // Resolve latest (service) release
                AdoptOpenJDKFamily featureFamiliy = Arrays.stream(getJDKFamilyList().data)
                        .filter(f -> latestFeature.equals(f.feature_version))
                        .findFirst()
                        .orElseThrow(() -> new IOException(
                                Messages.AdoptOpenJDKInstaller_performInstallation_releaseNotFound(this.id)));
                // TODO: Or just get the latest release (less convenient in case the support
                // matrix changes or some platforms take longer to build).
                Configuration c = Configuration.of(node);
                release = Arrays.stream(featureFamiliy.releases)
                        .filter(r -> r.getBinary(c.platform(), c.cpu()) != null)
                        .findFirst()
                        .orElseThrow(
                                () -> new IOException(Messages.AdoptOpenJDKInstaller_performInstallation_binaryNotFound(
                                        this.id, c.platform().name(), c.cpu().name())));
                // Don't change existing installations to prevent problems if another running
                // job is currently using the old installation.
                expected = expected.child(release.release_name);
                cleanupOldReleases(expected);
                id = release.release_name;
            } else {
                id = this.id;
            }
            // already installed?
            FilePath marker = expected.child(".installedByJenkins");
            if (marker.exists() && marker.readToString().equals(id)) {
                if (latestFeature != null) {
                    marker.touch(System.currentTimeMillis());
                }
                return expected;
            }
            expected.deleteRecursive();
            expected.mkdirs();

            release = release == null ? getJDKFamilyList().getRelease(id) : release;
            if (release == null) {
                throw new IOException(Messages.AdoptOpenJDKInstaller_performInstallation_releaseNotFound(id));
            }

            Configuration configuration = Configuration.of(node);
            Platform p = configuration.platform();
            CPU c = configuration.cpu();

            AdoptOpenJDKFile binary = release.getBinary(p, c);
            if (binary == null) {
                throw new IOException(
                        Messages.AdoptOpenJDKInstaller_performInstallation_binaryNotFound(id, p.name(), c.name()));
            }
            File cache = getLocalCacheFile(p, c, id);
            if (!DISABLE_CACHE && cache.exists()) {
                try (InputStream in = new FileInputStream(cache)) {
                    CountingInputStream cis = new CountingInputStream(in);
                    try {
                        log.getLogger()
                                .println(Messages.AdoptOpenJDKInstaller_performInstallation_fromCache(
                                        cache, expected, node.getDisplayName()));
                        // the zip contains already the content of the directory
                        expected.unzipFrom(cis);
                        // Still handle old cached archives, which have an extra top-level entry
                        pullUpTopLevelContent(expected, expected, p);
                    } catch (IOException e) {
                        throw new IOException(
                                Messages.AdoptOpenJDKInstaller_performInstallation_failedToUnpack(
                                        cache.toURI().toURL(), cis.getByteCount()),
                                e);
                    }
                }
            } else {
                String url = binary.binary_link;
                ZipExtractionInstaller zipExtractionInstaller = new ZipExtractionInstaller(null, url, null);
                FilePath installation = zipExtractionInstaller.performInstallation(tool, node, log);
                installation.child(".timestamp").delete(); // we don't use the timestamp
                pullUpTopLevelContent(installation, expected, p);
                marker.write(id, null);
                if (!DISABLE_CACHE) {
                    // update the local cache on master
                    // download to a temporary file and rename it in to handle concurrency and failure correctly,
                    Path tmp = new File(cache.getPath() + ".tmp").toPath();
                    try {
                        Path tmpParent = tmp.getParent();
                        if (tmpParent != null) {
                            Files.createDirectories(tmpParent);
                        }
                        try (OutputStream out = Files.newOutputStream(tmp)) {
                            // Add the JDK directory content directly as top-level entries
                            expected.zip(out, new DirectContentScanner());
                        }
                        Files.move(tmp, cache.toPath(), StandardCopyOption.REPLACE_EXISTING);
                    } finally {
                        Files.deleteIfExists(tmp);
                    }
                }
            }
        } catch (DetectionFailedException e) {
            log.getLogger().println(Messages.AdoptOpenJDKInstaller_performInstallation_JdkSkipped(e.getMessage()));
        }

        return expected;
    }

    private void cleanupOldReleases(FilePath expected) throws IOException, InterruptedException {
        if (OLD_LATEST_RELEASE_CLEANUP_DAYS <= 0) {
            return;
        }
        long reference = Instant.now()
                .minus(Duration.ofDays(OLD_LATEST_RELEASE_CLEANUP_DAYS))
                .toEpochMilli();
        for (FilePath directory : expected.getParent().listDirectories()) {
            if (!directory.equals(expected)) {
                FilePath marker = directory.child(".installedByJenkins");
                if (marker.lastModified() < reference) {
                    directory.deleteRecursive();
                }
            }
        }
    }

    private File getLocalCacheFile(Platform platform, CPU cpu, String id) {
        // we force .zip file
        return new File(Jenkins.get().getRootDir(), "caches/adoptopenjdk/" + platform + "/" + cpu + "/" + id + ".zip");
    }

    private void pullUpTopLevelContent(FilePath actual, FilePath expected, Platform p)
            throws IOException, InterruptedException {
        FilePath base = findPullUpDirectory(actual, p);
        if (base != null && !base.equals(expected)) {
            base.moveAllChildrenTo(expected);
        }
    }

    /**
     * Often an archive contains an extra top-level directory that's unnecessary when extracted on the disk
     * into the expected location. If your installation sources provide that kind of archives, override
     * this method to find the real root location.
     *
     * <p>
     * The caller will "pull up" the discovered real root by throw away the intermediate directory,
     * so that the user-configured "tool home" directory contains the right files.
     *
     * <p>
     * The default implementation applies some heuristics to auto-determine if the pull up is necessary.
     * This should work for typical archive files.
     *
     * @param root     The directory that contains the extracted archive. This directory contains nothing but the
     *                 extracted archive. For example, if the user installed
     *                 <a href="https://archive.apache.org/dist/ant/binaries/jakarta-ant-1.1.zip">jakarta-ant-1.1.zip</a>, this directory would contain
     *                 a single directory "jakarta-ant".
     * @param platform The platform for which to find pull up directory for.
     * @return Return the real top directory inside {@code root} that contains the meat. In the above example,
     * {@code root.child("jakarta-ant")} should be returned. If there's no directory to pull up, return null.
     * @throws IOException          Signals that an I/O exception of some sort has occurred.
     * @throws InterruptedException Thrown when a thread is interrupted.
     */
    protected FilePath findPullUpDirectory(FilePath root, Platform platform) throws IOException, InterruptedException {
        // if the directory just contains one directory and that alone, assume that's the pull up subject
        // otherwise leave it as is.
        List<FilePath> children = root.list();
        if (children.size() != 1) return null;

        // Since the MacOS tar.gz file uses a different layout we need to handle this platform differently
        // https://blog.adoptopenjdk.net/2018/10/macos-binary-changes
        if (platform == Platform.MACOS) {
            FilePath contents = children.get(0).child("Contents/Home");
            if (contents.exists() && contents.isDirectory()) return contents;
        }
        return children.get(0);
    }

    /**
     * Scans everything recursively within the root file, skipping the root file
     * itself.
     */
    private static class DirectContentScanner extends DirScanner.Full implements Serializable {
        private static final long serialVersionUID = 6764181784635321066L;

        @Override
        public void scan(File rootDir, FileVisitor visitor) throws IOException {
            File[] content = rootDir.listFiles();
            if (content != null) {
                for (File file : content) {
                    super.scan(file, visitor);
                }
            }
        }
    }

    private record Configuration(Platform platform, CPU cpu) implements Serializable {

        private static final Map<Node, Configuration> CACHE = new WeakHashMap<>();

        static synchronized Configuration of(Node node)
                throws IOException, InterruptedException, DetectionFailedException {
            Configuration configuration = CACHE.get(node);
            if (configuration != null) {
                return configuration;
            }
            VirtualChannel channel = node.getChannel();
            if (channel == null) {
                throw new IOException(Messages.AdoptOpenJDKInstaller_Platform_nullChannel(node.getDisplayName()));
            }
            configuration = channel.call(new Configuration.GetCurrent());
            CACHE.put(node, configuration);
            return configuration;
        }

        static class GetCurrent extends MasterToSlaveCallable<Configuration, DetectionFailedException> {
            private static final long serialVersionUID = 1L;

            @Override
            public Configuration call() throws DetectionFailedException {
                return new Configuration(Platform.current(), CPU.current());
            }
        }
    }

    /**
     * Supported platform
     */
    public enum Platform {
        LINUX("linux"),
        ALPINE_LINUX("alpine-linux"),
        WINDOWS("windows"),
        MACOS("mac"),
        SOLARIS("solaris"),
        AIX("aix");

        private final String id;

        Platform(String id) {
            this.id = id;
        }

        public static Platform current() throws DetectionFailedException {
            String arch = System.getProperty("os.name").toLowerCase(Locale.ENGLISH);
            if (arch.contains("linux")) return isAlpineLinux() ? ALPINE_LINUX : LINUX;
            if (arch.contains("windows")) return WINDOWS;
            if (arch.contains("sun") || arch.contains("solaris")) return SOLARIS;
            if (arch.contains("mac")) return MACOS;
            if (arch.contains("aix")) return AIX;
            throw new DetectionFailedException(Messages.AdoptOpenJDKInstaller_Platform_unknownPlatform(arch));
        }

        // Package protected so that tests can modify it
        static String osReleaseLocation = "/etc/os-release";

        // Package protected so that it can be tested
        static boolean isAlpineLinux() {
            File osRelease = new File(osReleaseLocation);
            try (Stream<String> lines = Files.lines(osRelease.toPath(), Charset.defaultCharset())) {
                return lines.anyMatch("ID=alpine"::equalsIgnoreCase);
            } catch (IOException ioe) {
                return false; // Do not fail OS detection if osReleaseLocation does not exist
            }
        }

        public String getId() {
            return id;
        }
    }

    /**
     * Supported CPU architecture
     */
    public enum CPU {
        // Based on org.apache.commons.lang3.ArchUtils
        I386("x32", Set.of("x86", "i386", "i486", "i586", "i686", "i86pc")), //
        AMD64("x64", Set.of("amd64", "x86_64")), //
        SPARC("sparcv9", Set.of("sparc", "sparcv9")), //
        S390X("s390x", Set.of("s390x")), //
        PPC64("ppc64", Set.of("ppc64")), //
        PPC64LE("ppc64le", Set.of("ppc64le")), //
        RISCV64("riscv64", Set.of("riscv64")), //
        ARM("arm", Set.of("arm")), //
        ARM64("aarch64", Set.of("aarch64", "arm64")), //
        ;

        private final Set<String> systemNames;
        final String adoptiumName;

        private CPU(String adoptiumName, Set<String> systemNames) {
            this.adoptiumName = adoptiumName;
            this.systemNames = systemNames;
        }

        public static CPU current() throws DetectionFailedException {
            String arch = System.getProperty("os.arch").toLowerCase(Locale.ENGLISH);
            for (CPU cpu : CPU.values()) {
                if (cpu.systemNames.contains(arch)) {
                    return cpu;
                }
            }
            throw new DetectionFailedException(Messages.AdoptOpenJDKInstaller_CPU_unknownCpu(arch));
        }
    }

    @Extension
    @Symbol("adoptOpenJdkInstaller")
    public static class DescriptorImpl extends ToolInstallerDescriptor<AdoptOpenJDKInstaller> {

        @NonNull
        @Override
        public String getDisplayName() {
            return Messages.AdoptOpenJDKInstaller_DescriptorImpl_DisplayName();
        }

        @Override
        public boolean isApplicable(Class<? extends ToolInstallation> toolType) {
            return toolType == JDK.class;
        }

        public List<AdoptOpenJDKFamily> getInstallableJDKs() throws IOException {
            return Arrays.asList(getAdoptOpenJDKFamilyList().data);
        }
    }

    @Extension
    @Symbol("adoptOpenJdk")
    public static final class AdoptOpenJDKList extends DownloadService.Downloadable {
        public AdoptOpenJDKList() {
            super(AdoptOpenJDKInstaller.class);
        }

        public AdoptOpenJDKFamilyList toList() throws IOException {
            JSONObject d = getData();
            if (d == null) return new AdoptOpenJDKFamilyList();
            AdoptOpenJDKFamilyList list = (AdoptOpenJDKFamilyList) JSONObject.toBean(d, AdoptOpenJDKFamilyList.class);
            Collections.reverse(Arrays.asList(list.data));
            return list;
        }
    }

    private static final class DetectionFailedException extends Exception {
        private static final long serialVersionUID = -8069815243317818959L;

        private DetectionFailedException(String message) {
            super(message);
        }
    }

    @SuppressFBWarnings(
            value = "NP_UNWRITTEN_PUBLIC_OR_PROTECTED_FIELD",
            justification = "Field initialized during deserialization from JSON object")
    public static final class AdoptOpenJDKFamilyList {
        public AdoptOpenJDKFamily[] data = new AdoptOpenJDKFamily[0];
        public int version;

        public boolean isEmpty() {
            for (AdoptOpenJDKFamily f : data) {
                if (f.releases.length > 0) {
                    return false;
                }
            }
            return true;
        }

        public AdoptOpenJDKRelease getRelease(String productCode) {
            for (AdoptOpenJDKFamily f : data) {
                for (AdoptOpenJDKRelease r : f.releases) {
                    if (r.matchesId(productCode)) {
                        return r;
                    }
                }
            }
            return null;
        }
    }

    /**
     * The group of JDK releases for the same feature (respectively 'major) version.
     */
    @SuppressFBWarnings(
            value = {"UUF_UNUSED_PUBLIC_OR_PROTECTED_FIELD", "UWF_UNWRITTEN_PUBLIC_OR_PROTECTED_FIELD"},
            justification = "Field initialized during deserialization from JSON object")
    public static final class AdoptOpenJDKFamily {
        public String name;
        public String feature_version;
        public AdoptOpenJDKRelease[] releases;

        private static final String LATEST_PREFIX = "jdk-";
        private static final String LATEST_SUFFIX = "-latest";

        public String getLatestRelease() {
            String feature = feature_version;
            return feature != null ? LATEST_PREFIX + feature + LATEST_SUFFIX : null;
        }

        static String getFeatureVersionIfLatest(String id) {
            if (id.startsWith(LATEST_PREFIX) && id.endsWith(LATEST_SUFFIX)) {
                return id.substring(LATEST_PREFIX.length(), id.length() - LATEST_SUFFIX.length());
            }
            return null;
        }
    }

    @SuppressFBWarnings(
            value = {"UWF_UNWRITTEN_PUBLIC_OR_PROTECTED_FIELD", "NP_UNWRITTEN_PUBLIC_OR_PROTECTED_FIELD"},
            justification = "Field initialized during deserialization from JSON object")
    public static final class AdoptOpenJDKRelease {
        public AdoptOpenJDKFile[] binaries;
        public String release_name;
        public String openjdk_impl;

        public boolean matchesId(String rhs) {
            return rhs != null && rhs.equals(release_name);
        }

        public AdoptOpenJDKFile getBinary(Platform platform, CPU cpu) {
            for (AdoptOpenJDKFile f : binaries) {
                if (!platform.getId().equals(f.os) || !openjdk_impl.equals(f.openjdk_impl)) {
                    continue;
                }
                if (cpu.adoptiumName.equals(f.architecture)) {
                    return f;
                }
            }
            return null;
        }
    }

    @SuppressFBWarnings(
            value = "UWF_UNWRITTEN_PUBLIC_OR_PROTECTED_FIELD",
            justification = "Field initialized during deserialization from JSON object")
    public static final class AdoptOpenJDKFile {
        public String architecture;
        public String os;
        public String openjdk_impl;
        public String binary_link;
    }
}
