plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

android {
    namespace = "com.wxmsglog"
    compileSdk = 35

    // 端侧推理靠 llama.cpp，需要 NDK 现场编译。r27 是支持 16KB page size 的版本，
    // Android 15 之后要求 64 位库按 16KB 对齐，用老 NDK 会被商店退回。
    ndkVersion = "27.0.12077973"

    defaultConfig {
        applicationId = "com.wxmsglog"
        minSdk = 29
        targetSdk = 35
        versionCode = 12
        versionName = "3.7.1"

        // 只出 arm64。32 位机型早就不够跑模型了，多一个 ABI 等于多一倍编译时间。
        ndk {
            abiFilters += "arm64-v8a"
        }

        externalNativeBuild {
            cmake {
                // -O3 是必须的：ggml 的矩阵乘全靠编译器展开，Debug 优化下慢十倍不止
                cppFlags += listOf("-O3", "-fexceptions", "-frtti")
                arguments += listOf("-DCMAKE_BUILD_TYPE=Release")
            }
        }
    }

    externalNativeBuild {
        cmake {
            path = file("src/main/cpp/CMakeLists.txt")
            version = "3.22.1"
        }
    }

    androidResources {
        // 模型文件必须原样打包。gguf 本身已经是压缩过的，再走一遍 deflate
        // 既压不动态，还让构建多花十几分钟；更重要的是不压缩才能拿到
        // assets 里的连续字节偏移，加载时可以直接 mmap，省掉复制 1.3GB 的开销。
        noCompress += "gguf"
    }

    buildTypes {
        release {
            // 不混淆：这个工具只给自己用，出问题时便于按日志定位
            isMinifyEnabled = false
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro",
            )
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    kotlinOptions {
        jvmTarget = "17"
    }

    lint {
        abortOnError = false
    }
}

dependencies {
    implementation("androidx.core:core-ktx:1.13.1")
    implementation("androidx.appcompat:appcompat:1.7.0")
    implementation("androidx.recyclerview:recyclerview:1.3.2")
    // 应用锁：封装了系统指纹/锁屏密码的差异，省得自己按 API 版本分支
    implementation("androidx.biometric:biometric:1.1.0")
}
