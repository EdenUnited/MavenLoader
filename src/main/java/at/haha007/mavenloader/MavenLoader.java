package at.haha007.mavenloader;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import me.lucko.jarrelocator.JarRelocator;
import me.lucko.jarrelocator.Relocation;
import org.bukkit.plugin.java.JavaPlugin;
import org.w3c.dom.Document;
import org.w3c.dom.Element;
import org.xml.sax.SAXException;

import javax.xml.parsers.DocumentBuilder;
import javax.xml.parsers.DocumentBuilderFactory;
import javax.xml.parsers.ParserConfigurationException;
import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.lang.reflect.Field;
import java.net.HttpURLConnection;
import java.net.MalformedURLException;
import java.net.URL;
import java.net.URLClassLoader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.StandardCopyOption;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.*;

public class MavenLoader {
    private static final sun.misc.Unsafe UNSAFE;

    static {
        sun.misc.Unsafe unsafe;
        try {
            Field unsafeField = sun.misc.Unsafe.class.getDeclaredField("theUnsafe");
            unsafeField.setAccessible(true);
            unsafe = (sun.misc.Unsafe) unsafeField.get(null);
        } catch (Throwable t) {
            unsafe = null;
        }
        UNSAFE = unsafe;
    }

    private final JavaPlugin plugin;
    private final Collection<URL> unopenedURLs;
    private final Collection<URL> pathURLs;
    private final List<Relocation> relocations;

    public static void loadFromJsonResource(JavaPlugin plugin, String resourceName) throws IOException {
        String jsonString = new String(Objects.requireNonNull(plugin.getResource(resourceName)).readAllBytes(), StandardCharsets.UTF_8);
        new MavenLoader(plugin, jsonString);
    }

    private static Object fetchField(final Class<?> clazz, final Object object, final String name) throws NoSuchFieldException {
        Field field = clazz.getDeclaredField(name);
        long offset = UNSAFE.objectFieldOffset(field);
        return UNSAFE.getObject(object, offset);
    }


    @SuppressWarnings("unchecked") //generics cant be checked at runtime
    private MavenLoader(JavaPlugin plugin, String jsonString) throws IOException {
        this.plugin = plugin;

        //load injection points from classloader
        Collection<URL> unopenedURLs;
        Collection<URL> path;
        try {
            Object ucp = fetchField(URLClassLoader.class, plugin.getClass().getClassLoader(), "ucp");
            unopenedURLs = (Collection<URL>) fetchField(ucp.getClass(), ucp, "unopenedUrls");
            path = (Collection<URL>) fetchField(ucp.getClass(), ucp, "path");
        } catch (Throwable e) {
            unopenedURLs = null;
            path = null;
        }
        this.unopenedURLs = unopenedURLs;
        this.pathURLs = path;

        //load config
        JsonElement json = JsonParser.parseString(jsonString);
        //relocations
        JsonArray relocationsJson = json.getAsJsonObject().getAsJsonArray("relocations");
        relocations = new ArrayList<>();
        if (relocationsJson != null) {
            for (JsonElement relocationJson : relocationsJson) {
                addRelocation(relocationJson.getAsJsonObject());
            }
        }

        //add dependencies
        JsonArray dependenciesJson = json.getAsJsonObject().getAsJsonArray("dependencies");
        ExecutorService es = Executors.newCachedThreadPool();
        List<Future<Exception>> futures = new ArrayList<>();
        for (JsonElement dependency : dependenciesJson) {
            var future = es.submit(() -> addDependency(dependency.getAsJsonObject()));
            futures.add(future);
        }
        es.shutdown();

        for (Future<Exception> future : futures) {
            try {
                if (future.get() != null) {
                    throw new RuntimeException(future.get());
                }
            } catch (InterruptedException | ExecutionException e) {
                throw new RuntimeException(e);
            }
        }
        try {
            if (!es.awaitTermination(10, TimeUnit.MINUTES))
                throw new InterruptedException("Timeout while loading libraries");
            Thread.sleep(10);
        } catch (InterruptedException e) {
            throw new IOException("Timeout while trying to download dependencies", e);
        }
    }

    private Exception addDependency(JsonObject json) {
        try {
            if (!json.has("repository"))
                json.addProperty("repository", "https://repo1.maven.org/maven2/");
            String repository = json.get("repository").getAsString();
            if (!repository.endsWith("/")) repository += "/";

            String group = json.get("group").getAsString().replace(".", "/");
            String artifact = json.get("artifact").getAsString();
            String version = json.get("version").getAsString();

            Dependency dependency = new Dependency(group, artifact, repository, version);
            if (dependency.process()) {
                plugin.getLogger().info("Library loaded: " + group + ":" + artifact);
            } else {
                plugin.getLogger().severe("Error while loading library: " + group + ":" + artifact);
                throw new IllegalStateException();
            }
            return null;
        } catch (Exception e) {
            return e;
        }
    }

    private String getSubVersionFromMetadata(String s) throws IOException {
        URL url = new URL(s);

        DocumentBuilderFactory factory = DocumentBuilderFactory.newInstance();
        DocumentBuilder builder;
        try {
            builder = factory.newDocumentBuilder();
        } catch (ParserConfigurationException e) {
            throw new IOException(e);
        }
        Document doc;
        try {
            doc = builder.parse(url.openStream());
        } catch (SAXException e) {
            throw new IOException(e);
        }
        //metadata.versioning.{$version}
        Element metadata = (Element) doc.getChildNodes().item(0);
        Element versioning = (Element) metadata.getElementsByTagName("versioning").item(0);
        Element snapshotVersions = (Element) versioning.getElementsByTagName("snapshotVersions").item(0);
        return snapshotVersions.getElementsByTagName("value").item(0).getTextContent();
    }

    private String getVersionFromMetadata(String s, String version) throws IOException {
        URL url = new URL(s);

        DocumentBuilderFactory factory = DocumentBuilderFactory.newInstance();
        DocumentBuilder builder;
        try {
            builder = factory.newDocumentBuilder();
        } catch (ParserConfigurationException e) {
            throw new IOException(e);
        }
        Document doc;
        try {
            doc = builder.parse(url.openStream());
        } catch (SAXException e) {
            throw new IOException(e);
        }
        //metadata.versioning.{$version}
        Element metadata = (Element) doc.getChildNodes().item(0);
        Element versioning = (Element) metadata.getElementsByTagName("versioning").item(0);
        return versioning.getElementsByTagName(version).item(0).getTextContent();
    }

    private void addRelocation(JsonObject json) {
        String pattern = json.get("pattern").getAsString();
        String shadedPattern = json.get("shadedPattern").getAsString();
        JsonArray includesJson = json.getAsJsonArray("includes");
        JsonArray excludesJson = json.getAsJsonArray("excludes");
        List<String> includes = new ArrayList<>();
        List<String> excludes = new ArrayList<>();
        if (includesJson != null) {
            for (JsonElement element : includesJson) {
                includes.add(element.getAsString());
            }
        }
        if (excludesJson != null) {
            for (JsonElement element : excludesJson) {
                excludes.add(element.getAsString());
            }
        }
        Relocation relocation = new Relocation(pattern, shadedPattern, includes, excludes);
        relocations.add(relocation);
    }

    private File getLibFolder() {
        File file = new File(plugin.getDataFolder(), "lib");
        if (!file.exists()) {
            if (!file.mkdirs()) {
                plugin.getLogger().warning("Couldn't create lib folder!");
            }
        }
        return file;
    }

    private synchronized boolean addFile(File file) {
        try {
            unopenedURLs.add(file.toURI().toURL());
            pathURLs.add(file.toURI().toURL());
            return true;
        } catch (MalformedURLException e) {
            return false;
        }
    }

    private final class Dependency {
        private final String groupId;
        private final String artifactId;
        private final String repository;
        private final String version;

        private Dependency(String groupId, String artifactId, String repository, String version) {
            this.groupId = groupId;
            this.artifactId = artifactId;
            this.repository = repository;
            this.version = version;
        }

        public boolean process() {
            String localHash = localHash();
            String remoteHash = remoteHash();
            File jarFile = jarFile();

            //library not cached, no way to download. :(
            if (localHash == null && remoteHash == null) {
                plugin.getLogger().severe("Couldn't find library locally or remotely:");
                plugin.getLogger().severe(repository + " -> " + groupId + ":" + artifactId + ":" + version);
                return false;
            }

            //library cached with the same hash as the remote library.
            //no need to download it again
            if (localHash != null && localHash.equalsIgnoreCase(remoteHash)) {
                if (addFile(jarFile)) return true;
                plugin.getLogger().severe("Couldn't load library, Malformed URL! This shouldn't be possible.");
                plugin.getLogger().severe(repository + " -> " + groupId + ":" + artifactId + ":" + version);
                return false;
            }

            //no connection to remote library, use the cached version with a warning
            if (remoteHash == null) {
                plugin.getLogger().warning("Couldn't find remote library, using cached version! This could be an outdated version.");
                if (addFile(jarFile)) return true;
                plugin.getLogger().severe("Couldn't load library, Malformed URL! This shouldn't be possible.");
                plugin.getLogger().severe(repository + " -> " + groupId + ":" + artifactId + ":" + version);
                return false;
            }

            //download
            if (!download()) {
                return false;
            }

            if (addFile(jarFile)) return true;
            plugin.getLogger().severe("Couldn't load library, Malformed URL! This shouldn't be possible.");
            plugin.getLogger().severe(repository + " -> " + groupId + ":" + artifactId + ":" + version);
            return false;
        }

        private boolean download() {
            URL url = jarDownloadUrl();
            if (url == null) return false;
            File jarFile = jarFile();
            if(!jarFile.exists()) {
                try {
                    jarFile.getParentFile().mkdirs();
                    jarFile.createNewFile();
                } catch (IOException e) {
                    throw new RuntimeException(e);
                }
            }
            File tempFile = tempFile();
            if(!tempFile.exists()) {
                try {
                    jarFile.getParentFile().mkdirs();
                    tempFile.createNewFile();
                } catch (IOException e) {
                    throw new RuntimeException(e);
                }
            }
            plugin.getLogger().info("Loading library: " + url);
            try {
                Files.copy(url.openStream(), tempFile.toPath(), StandardCopyOption.REPLACE_EXISTING);
                JarRelocator jarRelocator = new JarRelocator(tempFile, jarFile, relocations);
                jarRelocator.run();
                if (!jarFile.exists()) throw new IOException();
                return true;
            } catch (Exception e) {
                plugin.getLogger().warning("Error while loading library from: " + url.getPath());
                plugin.getLogger().warning("Trying to load library from cache...");
                e.printStackTrace();
                return false;
            }
        }

        private URL jarDownloadUrl() {
            String urlPath = urlPath();
            if (urlPath == null)
                return null;
            try {
                return new URL(urlPath);
            } catch (MalformedURLException e) {
                return null;
            }
        }

        private File jarFile() {
            return new File(getLibFolder(), groupId.replaceAll("[:.]", "_")
                    + "_" + artifactId.replaceAll("[:.]", "_")
                    + "_" + version.replaceAll("[:.]", "_") + ".jar");
        }

        private String localHash() {
            try {
                //use tempFile to get state before relocating
                File file = tempFile();
                FileInputStream fis = new FileInputStream(file);

                byte[] byteArray = new byte[1024];
                int bytesCount = 0;
                MessageDigest digest = MessageDigest.getInstance("SHA-1");

                while ((bytesCount = fis.read(byteArray)) != -1) {
                    digest.update(byteArray, 0, bytesCount);
                }
                fis.close();

                byte[] hashBytes = digest.digest();

                StringBuilder sb = new StringBuilder();
                for (byte hashByte : hashBytes) {
                    sb.append(Integer.toString((hashByte & 0xff) + 0x100, 16).substring(1));
                }

                return sb.toString();
            } catch (NoSuchAlgorithmException | IOException e) {
                return null;
            }
        }

        private String remoteHash() {
            try {
                String url = urlPath();
                HttpURLConnection connection = (HttpURLConnection) new URL(url + ".sha1").openConnection();
                if (connection.getResponseCode() != 200)
                    return null;
                String sha1 = new String(connection.getInputStream().readAllBytes());
                connection.disconnect();
                return sha1;
            } catch (IOException e) {
                return null;
            }
        }

        private String urlPath() {
            try {
                String url = "%s%s/%s/%s/%s-%s.jar".formatted(repository, groupId, artifactId, version, artifactId, version);
                String version = this.version;
                HttpURLConnection connection = (HttpURLConnection) new URL(url).openConnection();
                if (connection.getResponseCode() != 200) {
                    version = getVersionFromMetadata("%s%s/%s/%s".formatted(repository, groupId, artifactId, "maven-metadata.xml"), version);
                    url = "%s%s/%s/%s/%s-%s.jar".formatted(repository, groupId, artifactId, version, artifactId, version);
                }
                connection.disconnect();
                connection = (HttpURLConnection) new URL(url).openConnection();
                if (connection.getResponseCode() != 200) {
                    String subversion = getSubVersionFromMetadata("%s%s/%s/%s/%s".formatted(repository, groupId, artifactId, version, "maven-metadata.xml"));
                    url = "%s%s/%s/%s/%s-%s.jar".formatted(repository, groupId, artifactId, version, artifactId, subversion);
                }
                if (connection.getResponseCode() != 200) {
                    return null;
                }
                connection.disconnect();
                return url;
            } catch (IOException exception) {
                exception.printStackTrace();
                return null;
            }
        }

        private File tempFile() {
            return new File(jarFile().getAbsolutePath() + ".temp");
        }
    }

}
