package io.github.weg2022.strguard.runtime;

import java.io.InputStream;
import java.io.OutputStream;
import java.net.URL;
import java.nio.file.FileAlreadyExistsException;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.nio.file.attribute.AclEntry;
import java.nio.file.attribute.AclEntryFlag;
import java.nio.file.attribute.AclEntryPermission;
import java.nio.file.attribute.AclEntryType;
import java.nio.file.attribute.AclFileAttributeView;
import java.nio.file.attribute.PosixFilePermission;
import java.nio.file.attribute.PosixFilePermissions;
import java.nio.file.attribute.UserPrincipal;
import java.security.MessageDigest;
import java.util.Collections;
import java.util.EnumSet;
import java.util.Enumeration;
import java.util.HashMap;
import java.util.Map;
import java.util.Properties;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * Extracts, verifies, and loads a bundled Native library for a generated bridge class.
 */
public final class NativeLibraryLoader {
    private static final Pattern GENERATED_LIBRARY_FILE =
            Pattern.compile("(?:lib)?sg_[0-9a-f]{24}[.](?:dll|so|dylib)");
    private static final Pattern GENERATED_RESOURCE_PATH =
            Pattern.compile(
                    "META-INF/strguard/native/[A-Za-z0-9_.-]+/(?:lib)?sg_[0-9a-f]{24}[.](?:dll|so|dylib)"
            );
    private static final Pattern ARTIFACT_MARKER_PATH =
            Pattern.compile("META-INF/strguard/artifacts/[0-9a-f]{32}[.]properties");
    private static final Pattern SHA256 = Pattern.compile("[0-9a-f]{64}");
    private static final Pattern EXTRACTION_DIRECTORY = Pattern.compile("sg-[A-Za-z0-9._-]+");

    private static final String TEMP_ROOT_NAME = "strguard";
    private static final String EXTRACTION_PREFIX = "sg-";
    private static final String OWNER_PROBE_PREFIX = ".sg-owner-";
    private static final String ARTIFACT_MARKER_PREFIX = "META-INF/strguard/artifacts/";
    private static final String ARTIFACT_MARKER_SUFFIX = ".properties";
    private static final Set<PosixFilePermission> PRIVATE_DIRECTORY_PERMISSIONS =
            PosixFilePermissions.fromString("rwx------");
    private static final Set<PosixFilePermission> PRIVATE_FILE_PERMISSIONS =
            PosixFilePermissions.fromString("rw-------");
    private static final int COPY_BUFFER_SIZE = 8 * 1024;

    private NativeLibraryLoader() {
    }

    /**
     * Verifies and loads the Native binary without exposing a hash-to-load gap to generated bridge code.
     *
     * @param anchor generated bridge class used to resolve classpath containers
     * @param resourcePath validated Native binary resource path
     * @param fileName generated Native library file name
     * @param markerResourcePath artifact marker paired with the Native binary
     */
    public static synchronized void load(
            Class<?> anchor,
            String resourcePath,
            String fileName,
            String markerResourcePath
    ) {
        Path library = Path.of(extract(anchor, resourcePath, fileName, markerResourcePath));
        scheduleDeletion(library);
        try {
            System.load(library.toString());
        } finally {
            deleteExtraction(library);
        }
    }

    /**
     * Finds matching binary and marker resources in one classpath container, verifies the binary hash while
     * extracting it, and returns the resulting absolute path.
     *
     * @param anchor generated bridge class used to resolve classpath containers
     * @param resourcePath validated Native binary resource path
     * @param fileName generated Native library file name
     * @param markerResourcePath artifact marker paired with the Native binary
     * @return absolute path to the verified private extraction
     */
    public static String extract(
            Class<?> anchor,
            String resourcePath,
            String fileName,
            String markerResourcePath
    ) {
        requireValidResource(resourcePath, fileName, markerResourcePath);
        ClassLoader classLoader = anchor.getClassLoader();
        if (classLoader == null) {
            classLoader = ClassLoader.getSystemClassLoader();
        }

        Path root = secureTempRoot();
        UserPrincipal owner = owner(root);
        HashMap<String, URL> binaries = resourcesByContainer(classLoader, resourcePath);
        HashMap<String, URL> markers = resourcesByContainer(classLoader, markerResourcePath);
        Path selected = null;
        for (Map.Entry<String, URL> markerEntry : markers.entrySet()) {
            URL binary = binaries.get(markerEntry.getKey());
            if (binary == null) {
                continue;
            }
            String expectedHash = validatedExpectedHash(
                    markerEntry.getValue(),
                    markerResourcePath,
                    anchor,
                    resourcePath
            );
            if (expectedHash == null) {
                continue;
            }
            Path candidate = copyVerified(binary, root, owner, fileName, expectedHash);
            if (candidate == null) {
                continue;
            }
            if (selected != null) {
                deleteExtraction(candidate);
                deleteExtraction(selected);
                throw new IllegalStateException("Multiple valid StrGuard Native runtime resource containers");
            }
            selected = candidate;
        }
        if (selected == null) {
            throw new IllegalStateException("No valid StrGuard Native runtime resource container");
        }
        return selected.toAbsolutePath().toString();
    }

    /**
     * Deletes an extracted library where the host OS permits it.
     *
     * @param path absolute path returned by {@link #extract(Class, String, String, String)}
     */
    public static void loaded(String path) {
        Path library = Path.of(path).toAbsolutePath().normalize();
        Path directory = library.getParent();
        if (directory == null) {
            return;
        }
        if (!secureTempRoot().equals(directory.getParent())) {
            return;
        }
        if (!EXTRACTION_DIRECTORY.matcher(directory.getFileName().toString()).matches()) {
            return;
        }
        if (!isGeneratedLibraryFile(library.getFileName().toString())) {
            return;
        }
        deleteExtraction(library);
    }

    private static void requireValidResource(String resourcePath, String fileName, String markerResourcePath) {
        if (!isGeneratedLibraryFile(fileName)) {
            throw new IllegalArgumentException("Invalid StrGuard Native library file name '" + fileName + "'");
        }
        if (!GENERATED_RESOURCE_PATH.matcher(resourcePath).matches()) {
            throw new IllegalArgumentException("Invalid StrGuard Native runtime resource path '" + resourcePath + "'");
        }
        if (!ARTIFACT_MARKER_PATH.matcher(markerResourcePath).matches()) {
            throw new IllegalArgumentException("Invalid StrGuard artifact marker resource path '" + markerResourcePath + "'");
        }
    }

    private static HashMap<String, URL> resourcesByContainer(ClassLoader classLoader, String resourcePath) {
        HashMap<String, URL> resourcesByContainer = new HashMap<>();
        try {
            Enumeration<URL> resources = classLoader.getResources(resourcePath);
            while (resources.hasMoreElements()) {
                URL resource = resources.nextElement();
                String container = resourceContainer(resource, resourcePath);
                if (resourcesByContainer.put(container, resource) != null) {
                    throw new IllegalStateException("Duplicate StrGuard runtime resource in one container");
                }
            }
        } catch (IllegalStateException failure) {
            throw failure;
        } catch (Exception failure) {
            throw new IllegalStateException("Cannot enumerate StrGuard runtime resources", failure);
        }
        return resourcesByContainer;
    }

    private static String resourceContainer(URL resource, String resourcePath) {
        String external = resource.toExternalForm();
        if (!external.endsWith(resourcePath)) {
            throw new IllegalStateException("Invalid StrGuard runtime resource URL");
        }
        return external.substring(0, external.length() - resourcePath.length());
    }

    private static String validatedExpectedHash(
            URL marker,
            String markerResourcePath,
            Class<?> anchor,
            String resourcePath
    ) {
        Properties properties = new Properties();
        InputStream input = null;
        try {
            input = marker.openStream();
            properties.load(input);
        } catch (Exception ignored) {
            return null;
        } finally {
            closeQuietly(input);
        }
        if (!"1".equals(properties.getProperty("schemaVersion"))) {
            return null;
        }
        String artifactId = markerResourcePath.substring(
                ARTIFACT_MARKER_PREFIX.length(),
                markerResourcePath.length() - ARTIFACT_MARKER_SUFFIX.length()
        );
        if (!artifactId.equals(properties.getProperty("artifactId"))) {
            return null;
        }
        String stage = properties.getProperty("stage");
        if (!"protected".equals(stage) && !"shrunk".equals(stage)) {
            return null;
        }
        if (!"jvm".equals(properties.getProperty("runtimeFamily"))) {
            return null;
        }
        if (!anchor.getName().replace('.', '/').equals(properties.getProperty("bridgeClass"))) {
            return null;
        }
        if (!NativeLibraryLoader.class.getName().replace('.', '/').equals(properties.getProperty("loaderClass"))) {
            return null;
        }

        String nativeResources = properties.getProperty("nativeResources");
        if (nativeResources == null || nativeResources.indexOf(',') >= 0) {
            return null;
        }
        int separator = nativeResources.lastIndexOf(':');
        if (separator <= 0 || separator == nativeResources.length() - 1) {
            return null;
        }
        if (!resourcePath.equals(nativeResources.substring(0, separator))) {
            return null;
        }
        String expectedHash = nativeResources.substring(separator + 1);
        return SHA256.matcher(expectedHash).matches() ? expectedHash : null;
    }

    private static Path copyVerified(
            URL binary,
            Path root,
            UserPrincipal owner,
            String fileName,
            String expectedHash
    ) {
        Path directory = createPrivateDirectory(root, owner);
        Path library = directory.resolve(fileName);
        MessageDigest digest;
        try {
            digest = MessageDigest.getInstance("SHA-256");
        } catch (Exception failure) {
            deleteExtraction(library);
            return null;
        }

        InputStream input = null;
        OutputStream output = null;
        boolean copied = false;
        boolean outputClosed;
        try {
            input = binary.openStream();
            output = Files.newOutputStream(library, StandardOpenOption.CREATE_NEW, StandardOpenOption.WRITE);
            byte[] buffer = new byte[COPY_BUFFER_SIZE];
            int read;
            while ((read = input.read(buffer)) >= 0) {
                output.write(buffer, 0, read);
                digest.update(buffer, 0, read);
            }
            copied = true;
        } catch (Exception ignored) {
            // An unreadable candidate is ignored in favor of a valid matching container.
        } finally {
            outputClosed = closeQuietly(output);
            closeQuietly(input);
        }
        if (!copied || !outputClosed) {
            deleteExtraction(library);
            return null;
        }

        secureOwnedPath(library, owner, false);
        if (!MessageDigest.isEqual(digest.digest(), decodeSha256(expectedHash))) {
            deleteExtraction(library);
            return null;
        }
        return library;
    }

    private static byte[] decodeSha256(String value) {
        byte[] output = new byte[32];
        for (int index = 0; index < output.length; index++) {
            int offset = index * 2;
            output[index] = (byte) ((Character.digit(value.charAt(offset), 16) << 4)
                                    | Character.digit(value.charAt(offset + 1), 16));
        }
        return output;
    }

    private static Path secureTempRoot() {
        Path base = Path.of(System.getProperty("java.io.tmpdir")).toAbsolutePath().normalize();
        if (!Files.isDirectory(base)) {
            throw new IllegalStateException("Invalid Java temporary directory");
        }
        UserPrincipal processOwner = currentOwner(base);
        verifyTemporaryParent(base, processOwner);
        Path root = base.resolve(TEMP_ROOT_NAME);
        try {
            if (supports(root, "posix")) {
                Files.createDirectory(root, PosixFilePermissions.asFileAttribute(PRIVATE_DIRECTORY_PERMISSIONS));
            } else {
                Files.createDirectory(root);
            }
        } catch (FileAlreadyExistsException ignored) {
            // The existing root is accepted only after owner, type, and permissions are verified below.
        } catch (Exception failure) {
            throw new IllegalStateException("Cannot create StrGuard extraction directory", failure);
        }
        secureOwnedPath(root, processOwner, true);
        return root;
    }

    private static UserPrincipal currentOwner(Path base) {
        Path probe = null;
        try {
            if (supports(base, "posix")) {
                probe = Files.createTempDirectory(
                        base,
                        OWNER_PROBE_PREFIX,
                        PosixFilePermissions.asFileAttribute(PRIVATE_DIRECTORY_PERMISSIONS)
                );
            } else {
                probe = Files.createTempDirectory(base, OWNER_PROBE_PREFIX);
            }
            return owner(probe);
        } catch (Exception failure) {
            throw new IllegalStateException("Cannot establish the StrGuard extraction owner", failure);
        } finally {
            if (probe != null) {
                deleteQuietly(probe);
            }
        }
    }

    private static Path createPrivateDirectory(Path root, UserPrincipal expectedOwner) {
        try {
            Path directory;
            if (supports(root, "posix")) {
                directory = Files.createTempDirectory(
                        root,
                        EXTRACTION_PREFIX,
                        PosixFilePermissions.asFileAttribute(PRIVATE_DIRECTORY_PERMISSIONS)
                );
            } else {
                directory = Files.createTempDirectory(root, EXTRACTION_PREFIX);
            }
            secureOwnedPath(directory, expectedOwner, true);
            return directory;
        } catch (Exception failure) {
            throw new IllegalStateException("Cannot create a private StrGuard extraction directory", failure);
        }
    }

    private static void verifyTemporaryParent(Path base, UserPrincipal processOwner) {
        try {
            if (supports(base, "posix")) {
                int mode = ((Number) Files.getAttribute(base, "unix:mode", LinkOption.NOFOLLOW_LINKS)).intValue();
                boolean groupOrWorldWritable = (mode & 0022) != 0;
                boolean sticky = (mode & 01000) != 0;
                if (groupOrWorldWritable && !sticky) {
                    throw new IllegalStateException("Java temporary directory is writable without a sticky bit");
                }
                return;
            }
            if (supports(base, "acl") && !processOwner.equals(owner(base))) {
                throw new IllegalStateException("Java temporary directory is not owned by the current user");
            }
        } catch (IllegalStateException failure) {
            throw failure;
        } catch (Exception failure) {
            throw new IllegalStateException("Cannot verify the Java temporary directory", failure);
        }
    }

    private static void secureOwnedPath(Path path, UserPrincipal expectedOwner, boolean directory) {
        if (Files.isSymbolicLink(path)) {
            throw new IllegalStateException("StrGuard extraction path is a symbolic link");
        }
        boolean correctType = directory
                ? Files.isDirectory(path, LinkOption.NOFOLLOW_LINKS)
                : Files.isRegularFile(path, LinkOption.NOFOLLOW_LINKS);
        if (!correctType || !expectedOwner.equals(owner(path))) {
            throw new IllegalStateException("StrGuard extraction path has an invalid type or owner");
        }
        try {
            if (supports(path, "posix")) {
                Set<PosixFilePermission> expected = directory
                        ? PRIVATE_DIRECTORY_PERMISSIONS
                        : PRIVATE_FILE_PERMISSIONS;
                Files.setPosixFilePermissions(path, expected);
                if (!expected.equals(Files.getPosixFilePermissions(path, LinkOption.NOFOLLOW_LINKS))) {
                    throw new IllegalStateException("StrGuard extraction path is not owner-only");
                }
                return;
            }
            if (supports(path, "acl")) {
                AclFileAttributeView view = Files.getFileAttributeView(
                        path,
                        AclFileAttributeView.class,
                        LinkOption.NOFOLLOW_LINKS
                );
                if (view == null) {
                    throw new IllegalStateException("StrGuard extraction ACL is unavailable");
                }
                AclEntry ownerEntry = AclEntry.newBuilder()
                        .setType(AclEntryType.ALLOW)
                        .setPrincipal(expectedOwner)
                        .setPermissions(EnumSet.allOf(AclEntryPermission.class))
                        .setFlags(
                                directory
                                        ? EnumSet.of(AclEntryFlag.DIRECTORY_INHERIT, AclEntryFlag.FILE_INHERIT)
                                        : Collections.emptySet()
                        )
                        .build();
                view.setAcl(Collections.singletonList(ownerEntry));
                if (!view.getAcl().equals(Collections.singletonList(ownerEntry))) {
                    throw new IllegalStateException("StrGuard extraction ACL is not owner-only");
                }
                return;
            }
        } catch (IllegalStateException failure) {
            throw failure;
        } catch (Exception failure) {
            throw new IllegalStateException("Cannot secure StrGuard extraction permissions", failure);
        }
        throw new IllegalStateException("The temporary filesystem cannot enforce private StrGuard extraction");
    }

    private static UserPrincipal owner(Path path) {
        try {
            return Files.getOwner(path, LinkOption.NOFOLLOW_LINKS);
        } catch (Exception failure) {
            throw new IllegalStateException("Cannot read StrGuard extraction ownership", failure);
        }
    }

    private static boolean supports(Path path, String view) {
        return path.getFileSystem().supportedFileAttributeViews().contains(view);
    }

    private static void scheduleDeletion(Path library) {
        Path directory = library.getParent();
        if (directory != null) {
            directory.toFile().deleteOnExit();
        }
        library.toFile().deleteOnExit();
    }

    private static void deleteExtraction(Path library) {
        try {
            Files.deleteIfExists(library);
        } catch (Exception ignored) {
            return;
        }
        try {
            Files.deleteIfExists(library.getParent());
        } catch (Exception ignored) {
            // The delete-on-exit hook handles files that remain locked by the host OS.
        }
    }

    private static boolean isGeneratedLibraryFile(String fileName) {
        return GENERATED_LIBRARY_FILE.matcher(fileName).matches();
    }

    private static void deleteQuietly(Path path) {
        try {
            Files.deleteIfExists(path);
        } catch (Exception ignored) {
            // Probe cleanup is best effort inside the process-owned temporary directory.
        }
    }

    private static boolean closeQuietly(AutoCloseable closeable) {
        if (closeable == null) {
            return true;
        }
        try {
            closeable.close();
            return true;
        } catch (Exception ignored) {
            return false;
        }
    }
}
