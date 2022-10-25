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
import java.io.IOException;
import java.lang.reflect.Field;
import java.net.HttpURLConnection;
import java.net.URL;
import java.net.URLClassLoader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

@SuppressWarnings("ResultOfMethodCallIgnored") // ignore results of deleting files and stuff
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
        for (JsonElement dependency : dependenciesJson) {
            es.submit(() -> addDependency(dependency.getAsJsonObject()));
        }
        es.shutdown();
        try {
            es.awaitTermination(10, TimeUnit.MINUTES);
        } catch (InterruptedException e) {
            throw new IOException("Timeout while trying to download dependencies", e);
        }
    }

    private void addDependency(JsonObject json) {
        String repository = json.get("repository").getAsString();
        if (repository == null) repository = "https://repo1.maven.org/maven2/";
        if (!repository.endsWith("/")) repository += "/";

        String group = json.get("group").getAsString().replace(".", "/");
        String artifact = json.get("artifact").getAsString();
        String version = json.get("version").getAsString();

        String url = "%s%s/%s/%s/%s-%s.jar".formatted(repository, group, artifact, version, artifact, version);
        try {
            HttpURLConnection connection = (HttpURLConnection) new URL(url).openConnection();
            if (connection.getResponseCode() >= 400) {
                version = getVersionFromMetadata("%s%s/%s/%s".formatted(repository, group, artifact, "maven-metadata.xml"), version);
                url = "%s%s/%s/%s/%s-%s.jar".formatted(repository, group, artifact, version, artifact, version);
            }
            connection = (HttpURLConnection) new URL(url).openConnection();
            if (connection.getResponseCode() >= 400) {
                String subversion = getSubVersionFromMetadata("%s%s/%s/%s/%s".formatted(repository, group, artifact, version, "maven-metadata.xml"));
                url = "%s%s/%s/%s/%s-%s.jar".formatted(repository, group, artifact, version, artifact, subversion);
            }
            addUrl(new URL(url));
        } catch (IOException exception) {
            exception.printStackTrace();
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

    private synchronized void addUrl(URL url) throws IOException {
        File folder = getFolder();
        String fileName = url.getPath();
        fileName = fileName.substring(fileName.lastIndexOf('/') + 1);
        File tempFile = new File(folder, fileName + ".temp");
//        if (!tempFile.exists()) tempFile.createNewFile();
        plugin.getLogger().info("Loading library: " + url);
        Files.copy(url.openStream(), tempFile.toPath(), StandardCopyOption.REPLACE_EXISTING);
        File jarFile = new File(folder, fileName);
//        if (!jarFile.exists()) jarFile.createNewFile();
        JarRelocator relocator = new JarRelocator(tempFile, jarFile, relocations);
        relocator.run();
        tempFile.delete();
        if (!jarFile.exists()) throw new IOException();
        unopenedURLs.add(jarFile.toURI().toURL());
        pathURLs.add(jarFile.toURI().toURL());
    }

    private File getFolder() {
        File file = new File(plugin.getDataFolder(), "lib");
        if (!file.exists()) file.mkdirs();
        return file;
    }
}
