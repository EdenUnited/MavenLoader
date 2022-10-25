# MavenLoader

A tiny library to load maven dependencies at runtime

## Usage

Load resources:

```java
class MyPlugin extends JavaPlugin {
    public void onEnable() {
        //load dependencies
        MavenLoader.loadFromJsonResource(this, "maven.json");
    }
}
```

<br>The json config may look like this:

```json
{
  "relocations": [
    {
      "pattern": "at.haha007.edencommands",
      "shadedPattern": "my.code.commands",
      "includes": [
      ],
      "excludes": [
      ]
    }
  ],
  "dependencies": [
    {
      "repository": "https://javadoc.jitpack.io/",
      "group": "com.github.EdenUnited",
      "artifact": "EdenCommands",
      "version": "master-SNAPSHOT"
    }
  ]
}
```

includes, excludes and relocations in general are optional.
If you don't need them you can just delete them.
<br>repository is also optional, if you don't specify it, it defaults to maven-central

## Maven
```xml
<repository>
    <id>jitpack</id>
    <url>https://jitpack.io</url>
</repository>
```

```xml
<dependency>
    <groupId>com.github.EdenUnited</groupId>
    <artifactId>MavenLoader</artifactId>
    <version>version</version>
</dependency>
```

Be a good developer and relocate the library, so if someone else uses a different version there are no conflicts.

```xml
<plugin>
    <groupId>org.apache.maven.plugins</groupId>
    <artifactId>maven-shade-plugin</artifactId>
    <version>3.3.0</version>
    <executions>
        <execution>
            <phase>package</phase>
            <goals>
                <goal>shade</goal>
            </goals>
            <configuration>
                <minimizeJar>false</minimizeJar> <!-- Ensure not set to true -->
                <createDependencyReducedPom>false</createDependencyReducedPom>
                <relocations>
                    <relocation>
                        <pattern>at.haha007.mavenloader</pattern>
                        <shadedPattern>{your.package}.mavenloader</shadedPattern>
                    </relocation>
                </relocations>
            </configuration>
        </execution>
    </executions>
</plugin>
```

## Gradle

```
repositories {
    maven { url 'https://jitpack.io' }
}
```

```
dependencies {
    implementation 'com.github.EdenUnited:MavenLoader:version'
}
```

## Version

[![](https://jitpack.io/v/EdenUnited/MavenLoader.svg)](https://jitpack.io/#EdenUnited/MavenLoader)
