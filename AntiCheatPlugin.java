<?xml version="1.0" encoding="UTF-8"?>
<project xmlns="http://maven.apache.org/POM/4.0.0"
         xmlns:xsi="http://www.w3.org/2001/XMLSchema-instance"
         xsi:schemaLocation="http://maven.apache.org/POM/4.0.0 http://maven.apache.org/xsd/maven-4.0.0.xsd">
    <modelVersion>4.0.0</modelVersion>

    <!-- ============================================================
         RENAME ME. com.example.ac is a placeholder. Pick your own
         groupId/package before you ship this anywhere public.
         ============================================================ -->
    <groupId>com.example</groupId>
    <artifactId>SentinelAC</artifactId>
    <version>0.1.0</version>
    <packaging>jar</packaging>
    <name>SentinelAC</name>

    <properties>
        <java.version>21</java.version>
        <maven.compiler.release>21</maven.compiler.release>
        <project.build.sourceEncoding>UTF-8</project.build.sourceEncoding>

        <!-- Bump these to the latest. Verify against your server jar +
             https://github.com/retrooper/packetevents/releases -->
        <paper.version>1.21.11-R0.1-SNAPSHOT</paper.version>
        <packetevents.version>2.12.2</packetevents.version>
    </properties>

    <repositories>
        <repository>
            <id>papermc</id>
            <url>https://repo.papermc.io/repository/maven-public/</url>
        </repository>
        <repository>
            <id>codemc-releases</id>
            <url>https://repo.codemc.io/repository/maven-releases/</url>
        </repository>
    </repositories>

    <dependencies>
        <!-- Paper API (Folia is API-compatible at the Bukkit layer). Provided by the server. -->
        <dependency>
            <groupId>io.papermc.paper</groupId>
            <artifactId>paper-api</artifactId>
            <version>${paper.version}</version>
            <scope>provided</scope>
        </dependency>

        <!-- PacketEvents. We do NOT shade it here; we depend on the installed
             PacketEvents plugin (see plugin.yml `depend`). That is the lowest-friction
             setup. If you prefer a single self-contained jar, switch the scope to
             `compile` and add the shade plugin with relocation (notes in README.md). -->
        <dependency>
            <groupId>com.github.retrooper</groupId>
            <artifactId>packetevents-spigot</artifactId>
            <version>${packetevents.version}</version>
            <scope>provided</scope>
        </dependency>
    </dependencies>

    <build>
        <finalName>${project.name}-${project.version}</finalName>
        <plugins>
            <plugin>
                <groupId>org.apache.maven.plugins</groupId>
                <artifactId>maven-compiler-plugin</artifactId>
                <version>3.13.0</version>
                <configuration>
                    <release>${maven.compiler.release}</release>
                </configuration>
            </plugin>
            <!-- Filter resources so ${project.version} expands inside plugin.yml -->
            <plugin>
                <groupId>org.apache.maven.plugins</groupId>
                <artifactId>maven-resources-plugin</artifactId>
                <version>3.3.1</version>
            </plugin>
        </plugins>
        <resources>
            <resource>
                <directory>src/main/resources</directory>
                <filtering>true</filtering>
            </resource>
        </resources>
    </build>
</project>
