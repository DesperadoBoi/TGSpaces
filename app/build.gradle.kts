import java.io.File
import java.util.Properties
import org.gradle.api.DefaultTask
import org.gradle.api.GradleException
import org.gradle.api.provider.ListProperty
import org.gradle.api.provider.Property
import org.gradle.api.tasks.Input
import org.gradle.api.tasks.TaskAction

plugins {
    alias(libs.plugins.android.application)
}

abstract class ValidateReleaseSigningTask : DefaultTask() {
    @get:Input
    abstract val signingPropertiesPath: Property<String>

    @get:Input
    abstract val requiredPropertyNames: ListProperty<String>

    @TaskAction
    fun validate() {
        val propertiesFile = File(signingPropertiesPath.get())
        val requiredNames = requiredPropertyNames.get()
        val properties = Properties()

        if (!propertiesFile.isFile) {
            throw GradleException(
                "Release signing requires ${propertiesFile.name} in the project root. " +
                    "Expected properties: ${requiredNames.joinToString()}."
            )
        }

        propertiesFile.inputStream().use { properties.load(it) }

        val missingProperties = requiredNames.filter {
            properties.getProperty(it)?.trim().isNullOrEmpty()
        }
        if (missingProperties.isNotEmpty()) {
            throw GradleException(
                "Release signing file ${propertiesFile.name} is missing required properties: " +
                    missingProperties.joinToString() + "."
            )
        }

        val keyStorePath = properties.getProperty("storeFile").trim()
        val keyStoreFile = File(keyStorePath).let {
            if (it.isAbsolute) it else File(propertiesFile.parentFile, keyStorePath)
        }
        if (!keyStoreFile.isFile) {
            throw GradleException(
                "Release signing keystore was not found at the path configured by storeFile in " +
                    "${propertiesFile.name}."
            )
        }
    }
}

val signingPropertiesFile = rootProject.file("signing.properties")
val signingProperties = Properties()

if (signingPropertiesFile.isFile) {
    signingPropertiesFile.inputStream().use { signingProperties.load(it) }
}

fun signingProperty(name: String): String? =
    signingProperties.getProperty(name)?.trim()?.takeIf { it.isNotEmpty() }

val releaseSigningPropertyNames = listOf(
    "storeFile",
    "storePassword",
    "keyAlias",
    "keyPassword",
)
val signingPropertiesAbsolutePath = signingPropertiesFile.absolutePath
val releaseSigningPropertyNamesForValidation = releaseSigningPropertyNames.toList()

val hasReleaseSigningProperties =
    signingPropertiesFile.isFile && releaseSigningPropertyNames.all { signingProperty(it) != null }

val validateReleaseSigning by tasks.registering(ValidateReleaseSigningTask::class) {
    signingPropertiesPath.set(signingPropertiesAbsolutePath)
    requiredPropertyNames.set(releaseSigningPropertyNamesForValidation)
}

android {
    namespace = "com.example.tgspaces"
    compileSdk {
        version = release(36) {
            minorApiLevel = 1
        }
    }

    defaultConfig {
        applicationId = "com.example.tgspaces"
        minSdk = 24
        targetSdk = 36
        versionCode = 3
        versionName = "0.3.0"

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    }

    signingConfigs {
        if (hasReleaseSigningProperties) {
            create("release") {
                storeFile = rootProject.file(signingProperty("storeFile")!!)
                storePassword = signingProperty("storePassword")
                keyAlias = signingProperty("keyAlias")
                keyPassword = signingProperty("keyPassword")
            }
        }
    }

    buildTypes {
        release {
            if (hasReleaseSigningProperties) {
                signingConfig = signingConfigs.getByName("release")
            }
            optimization {
                enable = false
            }
        }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_11
        targetCompatibility = JavaVersion.VERSION_11
    }
}

afterEvaluate {
    tasks.matching { it.name == "assembleRelease" || it.name == "bundleRelease" || it.name == "preReleaseBuild" }
        .configureEach {
            dependsOn(validateReleaseSigning)
        }
}

dependencies {
    implementation(libs.activity.ktx)
    implementation(libs.appcompat)
    implementation(libs.constraintlayout)
    implementation(libs.core)
    implementation(libs.material)
    testImplementation(libs.junit)
    androidTestImplementation(libs.espresso.core)
    androidTestImplementation(libs.ext.junit)
}
