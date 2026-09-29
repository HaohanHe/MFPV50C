plugins {
    id("fabric-loom") version "1.18.2"
    kotlin("jvm") version "2.4.20"
}

version = property("mod_version") as String
group = property("maven_group") as String

base {
    archivesName.set(property("archives_base_name") as String)
}

repositories {
    maven("https://maven.aliyun.com/repository/public")
    maven("https://maven.aliyun.com/repository/google")
    maven("https://maven.fabricmc.net/") { name = "Fabric" }
    maven("https://libraries.minecraft.net/")
    mavenCentral()
}

dependencies {
    minecraft("com.mojang:minecraft:${property("minecraft_version")}")
    mappings(loom.officialMojangMappings())
    modImplementation("net.fabricmc:fabric-loader:${property("loader_version")}")
    modImplementation("net.fabricmc.fabric-api:fabric-api:${property("fabric_version")}")
    modImplementation("net.fabricmc:fabric-language-kotlin:${property("flk_version")}")
}

tasks.processResources {
    val modVersion = project.version.toString()
    inputs.property("version", modVersion)
    filesMatching("fabric.mod.json") {
        expand(mutableMapOf("version" to modVersion))
    }
}

tasks.withType<JavaCompile> {
    options.release.set(21)
}

kotlin {
    jvmToolchain(21)
}

tasks.jar {
    val baseName = project.base.archivesName.get()
    from("LICENSE") {
        rename { "${it}_${baseName}" }
    }
}
