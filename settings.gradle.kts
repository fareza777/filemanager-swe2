pluginManagement {
    repositories {
        google()
        // repo.maven.apache.org is unreachable from CI/dev boxes in some networks;
        // Huawei Cloud mirror proxies Maven Central.
        maven("https://repo.huaweicloud.com/repository/maven/")
        gradlePluginPortal()
    }
}
dependencyResolutionManagement {
    repositories {
        google()
        maven("https://repo.huaweicloud.com/repository/maven/")
    }
}
rootProject.name = "FileZen"
include(":app")
