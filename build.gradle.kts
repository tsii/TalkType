import java.util.Properties

// Lädt local.properties als Projekt-Properties (Git-ignored): sdk.dir wird von
// AGP selbst gelesen; vk.*-Credentials für die Release-Signatur brauchen hier
// eine explizite Brücke (findProperty greift sonst nicht).
val localProps = File(rootDir, "local.properties")
if (localProps.isFile) {
    val props = Properties().apply { localProps.inputStream().use { load(it) } }
    props.forEach { (k, v) ->
        if (k == "vk.storePassword" || k == "vk.keyPassword" || k == "vk.keyAlias") {
            ext.set(k.toString(), v.toString())
        }
    }
}

plugins {
    id("com.android.application") version "8.8.2" apply false
    id("org.jetbrains.kotlin.android") version "2.0.21" apply false
}
