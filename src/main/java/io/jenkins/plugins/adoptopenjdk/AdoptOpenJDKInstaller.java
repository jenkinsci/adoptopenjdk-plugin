package io.jenkins.plugins.adoptopenjdk;

import hudson.Extension;
import hudson.FilePath;
import hudson.Functions;
import hudson.model.DownloadService;
import hudson.model.JDK;
import hudson.model.Node;
import hudson.model.TaskListener;
import hudson.remoting.VirtualChannel;
import hudson.tools.ToolInstallation;
import hudson.tools.ToolInstaller;
import hudson.tools.ToolInstallerDescriptor;
import jenkins.MasterToSlaveFileCallable;
import jenkins.security.MasterToSlaveCallable;
import net.sf.json.JSONObject;
import org.jenkinsci.Symbol;
import org.kohsuke.stapler.DataBoundConstructor;

import javax.annotation.Nonnull;
import java.io.File;
import java.io.IOException;
import java.net.URL;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;

public class AdoptOpenJDKInstaller extends ToolInstaller {

    public final String id;

    @DataBoundConstructor
    public AdoptOpenJDKInstaller(String id) {
        super(null);
        this.id = id;
    }

    private static @Nonnull
    AdoptOpenJDKFamilyList getAdoptOpenJDKFamilyList() throws IOException {
        AdoptOpenJDKList list = AdoptOpenJDKList.all().get(AdoptOpenJDKList.class);
        if (list == null) {
            throw new IOException("AdoptOpenJDKList is not registered as a Downloadable");
        }
        return list.toList();
    }

    @Override
    public FilePath performInstallation(ToolInstallation tool, Node node, TaskListener log) throws IOException, InterruptedException {
        FilePath expected = preferredLocation(tool, node);

        try {
            // already installed?
            FilePath marker = expected.child(".installedByHudson");
            if (marker.exists() && marker.readToString().equals(id)) {
                return expected;
            }
            expected.deleteRecursive();
            expected.mkdirs();

            AdoptOpenJDKFamilyList jdkFamilyList = getAdoptOpenJDKFamilyList();
            AdoptOpenJDKRelease release = jdkFamilyList.getRelease(id);
            if (release == null) {
                throw new IOException("Argh!");
            }

            Platform p = Platform.of(node);
            CPU c = CPU.of(node);

            AdoptOpenJDKFile binary = release.getBinary(p, c);
            if (binary == null) {
                throw new IOException("Argh!");
            }
            String url = binary.binary_link;

            if (expected.installIfNecessaryFrom(new URL(url), log, "Unpacking " + url + " to " + expected + " on " + node.getDisplayName())) {
                expected.child(".timestamp").delete(); // we don't use the timestamp
                FilePath base = findPullUpDirectory(expected);
                if (base != null && base != expected) {
                    base.moveAllChildrenTo(expected);
                }
                expected.act(new ChmodRecAPlusX());
            }
            marker.write(id, null);

        } catch (DetectionFailedException e) {
            log.getLogger().println("JDK installation skipped: " + e.getMessage());
        }

        return expected;
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
     * @param root The directory that contains the extracted archive. This directory contains nothing but the
     *             extracted archive. For example, if the user installed
     *             http://archive.apache.org/dist/ant/binaries/jakarta-ant-1.1.zip , this directory would contain
     *             a single directory "jakarta-ant".
     * @return Return the real top directory inside {@code root} that contains the meat. In the above example,
     * {@code root.child("jakarta-ant")} should be returned. If there's no directory to pull up,
     * return null.
     */
    protected FilePath findPullUpDirectory(FilePath root) throws IOException, InterruptedException {
        // if the directory just contains one directory and that alone, assume that's the pull up subject
        // otherwise leave it as is.
        List<FilePath> children = root.list();
        if (children.size() != 1) return null;
        if (children.get(0).isDirectory())
            return children.get(0);
        return null;
    }

    public enum Platform {
        LINUX("linux"), WINDOWS("windows"), MACOS("mac"), SOLARIS("solaris"), AIX("aix");

        private final String id;

        Platform(String id) {
            this.id = id;
        }

        public static Platform of(Node n) throws IOException, InterruptedException, DetectionFailedException {
            VirtualChannel channel = n.getChannel();
            if (channel == null) {
                throw new IOException("Channel is null, cannot determine Platform of: " + n.getDisplayName());
            }
            return channel.call(new Platform.GetCurrentPlatform());
        }

        public static Platform current() throws DetectionFailedException {
            String arch = System.getProperty("os.name").toLowerCase(Locale.ENGLISH);
            if (arch.contains("linux")) return LINUX;
            if (arch.contains("windows")) return WINDOWS;
            if (arch.contains("sun") || arch.contains("solaris")) return SOLARIS;
            if (arch.contains("mac")) return MACOS;
            if (arch.contains("aix")) return AIX;
            throw new DetectionFailedException("Unknown Platform name: " + arch);
        }

        public String getId() {
            return id;
        }

        static class GetCurrentPlatform extends MasterToSlaveCallable<Platform, DetectionFailedException> {
            private static final long serialVersionUID = 1L;

            public Platform call() throws DetectionFailedException {
                return current();
            }
        }
    }

    public enum CPU {
        i386, amd64, Sparc, s390x, ppc64, arm;

        public static CPU of(Node n) throws IOException, InterruptedException, DetectionFailedException {
            VirtualChannel channel = n.getChannel();
            if (channel == null) {
                throw new IOException("Channel is null, cannot determine CPU of: " + n.getDisplayName());
            }
            return channel.call(new CPU.GetCurrentCPU());
        }

        public static CPU current() throws DetectionFailedException {
            String arch = System.getProperty("os.arch").toLowerCase(Locale.ENGLISH);
            if (arch.contains("sparc")) return Sparc;
            if (arch.contains("amd64") || arch.contains("86_64")) return amd64;
            if (arch.contains("86")) return i386;
            if (arch.contains("s390x")) return s390x;
            if (arch.contains("ppc64")) return ppc64;
            if (arch.contains("arm") || arch.contains("aarch64")) return arm;
            throw new DetectionFailedException("Unknown CPU architecture: " + arch);
        }

        static class GetCurrentCPU extends MasterToSlaveCallable<CPU, DetectionFailedException> {
            private static final long serialVersionUID = 1L;

            public CPU call() throws DetectionFailedException {
                return current();
            }
        }
    }

    /**
     * Sets execute permission on all files, since unzip etc. might not do this.
     * Hackish, is there a better way?
     */
    static class ChmodRecAPlusX extends MasterToSlaveFileCallable<Void> {
        private static final long serialVersionUID = 1L;

        public Void invoke(File d, VirtualChannel channel) throws IOException {
            if (!Functions.isWindows())
                process(d);
            return null;
        }

        private void process(File f) {
            if (f.isFile()) {
                f.setExecutable(true, false);
            } else {
                File[] kids = f.listFiles();
                if (kids != null) {
                    for (File kid : kids) {
                        process(kid);
                    }
                }
            }
        }
    }

    @Extension
    @Symbol("adoptOpenJdkInstaller")
    public static class DescriptorImpl extends ToolInstallerDescriptor<AdoptOpenJDKInstaller> {

        @Nonnull
        @Override
        public String getDisplayName() {
            return "AdoptOpenJDK installer";
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
            return (AdoptOpenJDKFamilyList) JSONObject.toBean(d, AdoptOpenJDKFamilyList.class);
        }
    }

    private static final class DetectionFailedException extends Exception {
        private DetectionFailedException(String message) {
            super(message);
        }
    }

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

    public static final class AdoptOpenJDKFamily {
        public String name;
        public AdoptOpenJDKRelease[] releases;
    }

    public static final class AdoptOpenJDKRelease {
        public AdoptOpenJDKFile[] binaries;
        public String release_name;
        public String openjdk_impl;

        public boolean matchesId(String rhs) {
            return rhs != null && rhs.equals(release_name);
        }

        public AdoptOpenJDKFile getBinary(Platform platform, CPU cpu) throws IOException {
            for (AdoptOpenJDKFile f : binaries) {
                if (!platform.getId().equals(f.os) || !openjdk_impl.equals(f.openjdk_impl)) {
                    continue;
                }
                switch (cpu) {
                    case i386:
                        if (f.architecture.equals("x32")) return f;
                        break;
                    case amd64:
                        if (f.architecture.equals("x64")) return f;
                        break;
                    case Sparc:
                        if (f.architecture.equals("sparcv9")) return f;
                        break;
                    case ppc64:
                        if (f.architecture.equals("ppc64") || f.architecture.equals("ppc64le")) return f;
                        break;
                    case s390x:
                        if (f.architecture.equals("s390x")) return f;
                        break;
                    case arm:
                        if (f.architecture.equals("arm") || f.architecture.equals("aarch64")) return f;
                        break;
                    default:
                        throw new IOException("Argh!");
                }
            }
            return null;
        }
    }

    public static final class AdoptOpenJDKFile {
        public String architecture;
        public String os;
        public String openjdk_impl;
        public String binary_link;
    }
}
