plugins {
    id("java")
}

group = "CesarCosmico"
version = "1.0"

repositories {
    mavenCentral()
    maven {
        name = "papermc-repo"
        setUrl("https://repo.papermc.io/repository/maven-public/")
    }
    maven {
        name = "sonatype"
        setUrl("https://oss.sonatype.org/content/groups/public/")
    }
    maven {
        name = "CustomFishing"
        setUrl("https://repo.momirealms.net/releases/")
    }
    maven {
        name = "PlaceholderAPI"
        setUrl("https://repo.extendedclip.com/releases/")
    }
}

dependencies {
    compileOnly("io.papermc.paper:paper-api:1.21.11-R0.1-SNAPSHOT")
    compileOnly("net.momirealms:custom-fishing:2.3.16")
    compileOnly("me.clip:placeholderapi:2.11.7")
    implementation("dev.dejvokep:boosted-yaml:1.3.6")
    implementation("net.kyori:adventure-text-minimessage:4.26.1")
}

tasks.test {
    useJUnitPlatform()
}