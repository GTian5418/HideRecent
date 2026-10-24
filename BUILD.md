# 编译教程：从零部署环境、本地编译、CI/CD 自动发布「最近任务隐藏 / HideRecent」

按顺序做完 **第 1~3 章** 就能出包，**第 4 章** 是签名（**zipalign + apksigner 一步 v1+v2+v3**），  
**第 5~6 章** 是 GitHub Actions CI/CD，**第 7 章** 是安装，**第 8 章** 是常见报错。

-   项目仓库：[https://github.com/GTian5418/HideRecent](https://github.com/GTian5418/HideRecent)
-   包名：`top.gtian.hiderecent`
-   构建工具：Gradle + AGP 8.7.2 + Kotlin 2.0.21
-   最低/目标 SDK：`minSdk 29` / `targetSdk 34`（`compileSdk 35`）

* * *

## 0\. 一句话总览

需要三样东西：**JDK 17** + **Android SDK** + **Gradle（8.7 以上，推荐 8.9）**。  
三者装好、配好环境变量，再补一个签名配置，执行 `gradle assembleRelease` + zipalign + apksigner 即可出正式包。  
配好 GitHub Actions secrets 后，打 tag 即可自动编译、签名、发布 Release 并同步到 LSPosed 模块仓库。

* * *

## 1\. 安装 JDK 17

AGP 8.7 要求 **JDK 17 或更高**，用 8 / 11 会直接报错。

### 1.1 下载安装

-   推荐 **Microsoft Build of OpenJDK 17** 或 **Temurin (Adoptium) JDK 17**
-   安装到无空格、无中文的路径，例如：

    ```
    D:\DevTools\Java\jdk-17
    ```

### 1.2 配置环境变量（Windows）

`此电脑 → 属性 → 高级系统设置 → 环境变量`，新建：

| 变量名 | 值（示例，改成你自己的路径） |
| --- | --- |
| `JAVA_HOME` | `D:\DevTools\Java\jdk-17` |
| `Path` | 追加 `%JAVA_HOME%\bin` |

### 1.3 验证

打开 **新的** PowerShell / CMD 窗口：

```powershell
java -version
```

应输出 `openjdk version "17.x.x"`。若提示找不到命令，说明 `Path` 没生效，重开终端或重新登录。

* * *

## 2\. 安装 Android SDK

本模块只需要 SDK，**不强制装 Android Studio**（装了更省事）。

### 方案 A：只装命令行工具（体积小）

1.  下载 **Android SDK Command-line Tools**：  
    [https://developer.android.com/studio#command-tools](https://developer.android.com/studio#command-tools)
2.  解压到例如 `D:\DevTools\Android\Sdk\cmdline-tools\latest\`
3.  用 `sdkmanager` 装必需组件：

    ```powershell
    $env:ANDROID_HOME = "D:\DevTools\Android\Sdk"
    & "$env:ANDROID_HOME\cmdline-tools\latest\bin\sdkmanager.bat" `
        "platform-tools" `
        "platforms;android-35" `
        "build-tools;35.0.0"
    ```

4.  接受许可：

    ```powershell
    & "$env:ANDROID_HOME\cmdline-tools\latest\bin\sdkmanager.bat" --licenses
    ```

    全部输入 `y`。

### 方案 B：装 Android Studio

安装后打开一次 `SDK Manager`，勾选：

-   **Android SDK Platform 35**
-   **Android SDK Build-Tools 35.0.0**（34.0.0 亦可）
-   **Android SDK Platform-Tools**

SDK 默认在 `C:\Users\<你>\AppData\Local\Android\Sdk`。

### 2.1 配置环境变量

| 变量名 | 值 |
| --- | --- |
| `ANDROID_HOME` | `D:\DevTools\Android\Sdk` |
| `ANDROID_SDK_ROOT` | 同上（老工具兼容用，可留空） |
| `Path` | 追加 `%ANDROID_HOME%\platform-tools` |

验证：

```powershell
adb version
```

### 2.2 告诉项目 SDK 在哪（关键）

Gradle 不认 `ANDROID_HOME` 就靠 `local.properties`。在**项目根目录**新建  
`local.properties`（该文件已在 `.gitignore` 里，不会入库）：

```properties
sdk.dir=D:\\DevTools\\Android\\Sdk
```

> ⚠️ Windows 路径的反斜杠要写成双写 `\\`。  
> 注意：这是 `local.properties`，**不要**和签名用的 `keystore.properties` 搞混。

* * *

## 3\. 安装 Gradle 并编译

本项目 **没有自带 `gradlew` 包装器**，所以有两种做法。

### 方案 A：用本机已装的 Gradle（推荐，最简单）

1.  下载 Gradle **8.9**（AGP 8.7.2 兼容 8.7~8.9）：  
    [https://gradle.org/releases/](https://gradle.org/releases/)
2.  解压到 `D:\DevTools\Gradle\gradle-8.9`
3.  把 `D:\DevTools\Gradle\gradle-8.9\bin` 加入 `Path`，验证：

    ```powershell
    gradle -v
    ```

### 方案 B：为项目生成包装器（之后别人 clone 直接用 `gradlew`）

在项目根目录执行一次（需已装好任意 Gradle 8.x）：

```powershell
gradle wrapper --gradle-version 8.9
```

会生成 `gradlew`、`gradlew.bat`、`gradle/wrapper/`，之后统一用 `.\gradlew.bat` 即可。

### 3.1 开始编译

先切到项目根目录（含 `settings.gradle.kts` 的那层），然后：

```powershell
# 建议显式指定 JDK，避免机器上多版本 JDK 打架
$env:JAVA_HOME = "D:\DevTools\Java\jdk-17"

# 方案 A（本机 Gradle）
gradle assembleRelease --no-daemon

# 方案 B（有包装器时）
.\gradlew.bat assembleRelease
```

-   首次编译会联网下载 AGP / Kotlin / AndroidX 依赖，**较慢属正常**。
-   项目已配置腾讯云 Maven 镜像，国内网络一般不用挂代理。

### 3.2 产物位置

```
app/build/outputs/apk/release/app-release.apk
```

### 3.3 只想快速验证能否编译

```powershell
gradle assembleDebug      # 调试包，不签名、不混淆，最快
```

### 3.4 清理重建（编译缓存出错时）

```powershell
gradle clean
Remove-Item -Recurse -Force .gradle, app\build -ErrorAction SilentlyContinue
```

* * *

## 4\. 配置签名（zipalign + apksigner 一步 v1+v2+v3 —— 默认签名方式）

`app/build.gradle.kts` 会读取根目录的 `keystore.properties`。  
**没有这个文件时，`assembleRelease` 打出的包是未签名的**，装不上或无法覆盖安装。

> ⚠️ **为什么不用 Gradle 自动签名 / jarsigner 两步法？**
>
> 1.  本项目 `minSdk = 29`，AGP 8.7 在此条件下**强制只输出 v3 签名**，  
>     会忽略 `build.gradle.kts` 里的 `enableV1Signing` / `enableV2Signing`。光靠 Gradle 自动签名只有 v3。
> 2.  旧做法用 `jarsigner` 写 v1 再 `apksigner` 追加 v2/v3。但 **jarsigner 重打包会破坏 ZIP LFH 结构**  
>     （把 `compSize` 设为 0 并启用 data descriptor），导致 LSPosed 索引服务 `tag.yml`  
>     用 Range 请求读 `module.prop` 时解压失败（`Z_BUF_ERROR`），模块无法上架索引。
> 3.  **正确做法**：`zipalign` 对齐后直接用 `apksigner` 一步签名 v1+v2+v3，不经过 jarsigner，ZIP 结构正常。

### 4.1 生成自己的 keystore

```powershell
& "$env:JAVA_HOME\bin\keytool.exe" -genkeypair -v `
  -keystore keystore\hiderecent-release.jks `
  -alias hiderecent `
  -keyalg RSA -keysize 2048 -validity 10000 `
  -storetype PKCS12
```

按提示设置两次密码、填写 CN 等信息。

> 🔑 **务必记住**：keystore 文件、alias、两个密码。  
> 以后升级 App 必须用**同一个** keystore，否则无法覆盖安装，只能卸载重装。

### 4.2 创建 `keystore.properties`

在**项目根目录**新建（已在 `.gitignore` 中，不会入库）：

```properties
storeFile=keystore/hiderecent-release.jks
storePassword=你的store密码
keyAlias=hiderecent
keyPassword=你的key密码
```

-   `storeFile` 用**相对项目根目录**的路径。
-   `keyPassword` 与 `storePassword` 相同即可（PKCS12 通常一致）。

### 4.3 编译出 Gradle 签名的 APK

```powershell
gradle assembleRelease
```

此时产物在 `app\build\outputs\apk\release\app-release.apk`。  
由于 `minSdk 29`，Gradle 自动签名只写了 v3，接下来用 zipalign + apksigner 补全 v1 + v2。

### 4.4 zipalign + apksigner 一步签名 v1 + v2 + v3（默认）

先定义变量（PowerShell）：

```powershell
$bt       = "$env:ANDROID_HOME\build-tools\35.0.0"
$apk      = "app\build\outputs\apk\release\app-release.apk"
$aligned  = "app\build\outputs\apk\release\app-release-aligned.apk"
$ks       = "keystore\hiderecent-release.jks"
$alias    = "hiderecent"
$storePw  = "你的store密码"
$keyPw    = "你的key密码"
```

**第一步：zipalign（4 字节对齐，apksigner 前必须做）**

```powershell
& "$bt\zipalign.exe" -v 4 $apk $aligned
```

输出末尾应看到 `Verification succesful`。

**第二步：apksigner 一步签名 v1 + v2 + v3**

```powershell
& "$bt\apksigner.bat" sign `
  --ks $ks --ks-key-alias $alias `
  --ks-pass "pass:$storePw" --key-pass "pass:$keyPw" `
  --v1-signing-enabled true --v2-signing-enabled true --v3-signing-enabled true `
  --out $apk $aligned
```

-   `--out $apk`：把签名后的 APK 写回原路径（覆盖 Gradle 那个只有 v3 的版本）。
-   `$aligned` 是输入（已对齐），`$apk` 是输出（签名后）。
-   无报错且 `$LASTEXITCODE` 为 `0` 即成功。

等价的 CMD 写法：

```cmd
set BT=%ANDROID_HOME%\build-tools\35.0.0
set APK=app\build\outputs\apk\release\app-release.apk
set ALIGNED=app\build\outputs\apk\release\app-release-aligned.apk
set KS=keystore\hiderecent-release.jks

"%BT%\zipalign.exe" -v 4 "%APK%" "%ALIGNED%"

"%BT%\apksigner.bat" sign ^
  --ks "%KS%" --ks-key-alias hiderecent ^
  --ks-pass "pass:你的store密码" --key-pass "pass:你的key密码" ^
  --v1-signing-enabled true --v2-signing-enabled true --v3-signing-enabled true ^
  --out "%APK%" "%ALIGNED%"
```

### 4.5 验证签名

```powershell
& "$bt\apksigner.bat" verify --verbose --print-certs $apk
```

应能看到：

```
Verified using v1 scheme (JAR signing): true
Verified using v2 scheme (APK Signature Scheme v2): true
Verified using v3 scheme (APK Signature Scheme v3): true
```

查看包信息（可选）：

```powershell
& "$bt\aapt2.exe" dump badging $apk
```

* * *

## 5\. GitHub Actions CI（自动编译验证）

项目根目录 `.github/workflows/android.yml` 是 CI workflow，**手动触发**（`workflow_dispatch`）时编译 debug 包验证不会挂。

### 5.1 完整 workflow 文件

```yaml
# .github/workflows/android.yml
name: Android CI

on:
  workflow_dispatch:

permissions:
  contents: read

concurrency:
  group: android-ci-${{ github.workflow }}-${{ github.ref }}
  cancel-in-progress: true

jobs:
  build:
    runs-on: ubuntu-latest
    steps:
      - name: Checkout
        uses: actions/checkout@v4

      - name: Set up JDK 17
        uses: actions/setup-java@v5
        with:
          distribution: temurin
          java-version: "17"
          cache: gradle

      - name: Set up Android SDK
        uses: android-actions/setup-android@v4

      - name: Install Android SDK packages
        run: |
          yes | sdkmanager --licenses
          sdkmanager \
            "platform-tools" \
            "platforms;android-35" \
            "build-tools;35.0.0"

      - name: Set up Gradle 8.9
        uses: gradle/actions/setup-gradle@v4
        with:
          gradle-version: "8.9"

      - name: Run unit tests
        run: gradle :app:testDebugUnitTest --no-daemon --stacktrace

      - name: Build debug APK
        run: gradle :app:assembleDebug --no-daemon --stacktrace -x testDebugUnitTest

      - name: Upload debug APK artifact
        uses: actions/upload-artifact@v4
        with:
          name: app-debug-apk
          path: app/build/outputs/apk/debug/*.apk
          if-no-files-found: error
```

### 5.2 关键点说明

| 步骤 | 作用 | 注意事项 |
| --- | --- | --- |
| `actions/checkout@v4` | 拉代码 | 默认浅克隆，够用 |
| `actions/setup-java@v5` | 装 JDK 17 | `cache: gradle` 缓存 Gradle 依赖加速 |
| `android-actions/setup-android@v4` | 装 Android SDK | **必须用 v4**，v3 会因 Google 下架 `tools` 包报错 |
| `sdkmanager ...` | 补装 platform-tools / android-35 / build-tools 35.0.0 | `yes | sdkmanager --licenses` 先接受许可 |
| `gradle/actions/setup-gradle@v4` | 装 Gradle 8.9 | 不依赖项目自带 gradlew |
| `assembleDebug` | 编译 debug 包 | CI 只验证能编过，不签名 |
| `upload-artifact@v4` | 上传 APK 为 artifact | 可在 Actions 运行页面下载 |

* * *

## 6\. GitHub Actions Release（自动签名 + 发布 Release + 同步 LSPosed 仓库）

项目根目录 `.github/workflows/release.yml` 是 Release workflow。  
触发方式有两种：

1.  **打 tag 推送**：`git tag v26.10.25 && git push origin v26.10.25` → 自动跑
2.  **手动触发**：GitHub 仓库 → Actions → Release → Run workflow → 填 tag 名

workflow 会自动：编译 release APK → zipalign → apksigner 一步签名 v1+v2+v3 → 上传 GitHub Release → 同步源码到 LSPosed 模块仓库 → 在 LSPosed 仓库创建 Release。

### 6.1 前置：把签名文件存入 GitHub Actions Secrets

keystore 是二进制文件，不能直接放 secret。需要 **base64 编码**后存入。

#### 6.1.1 本地 base64 编码 keystore

```powershell
# 在项目根目录执行
$bytes = [System.IO.File]::ReadAllBytes("keystore\hiderecent-release.jks")
$base64 = [System.Convert]::ToBase64String($bytes)
$base64 | Set-Content -NoNewline keystore_base64.txt
(Get-Content keystore_base64.txt).Length   # 应在几千字符左右
```

> `keystore_base64.txt` 含敏感信息，**不要提交到 git**。用完即删。

#### 6.1.2 在 GitHub 设置 5 个 Secrets

打开 `https://github.com/GTian5418/HideRecent/settings/secrets/actions`，点 **New repository secret**：

| Secret 名称 | 值 | 来源 |
| --- | --- | --- |
| `RELEASE_KEYSTORE_BASE64` | `keystore_base64.txt` 的全部内容（粘贴） | 6.1.1 编码产物 |
| `RELEASE_STORE_PASSWORD` | keystore 的 store 密码 | `keystore.properties` 里的 `storePassword` |
| `RELEASE_KEY_ALIAS` | key alias | `keystore.properties` 里的 `keyAlias`（如 `hiderecent`） |
| `RELEASE_KEY_PASSWORD` | key 密码 | `keystore.properties` 里的 `keyPassword` |
| `XPOSED_REPO_TOKEN` | GitHub PAT，对 `Xposed-Modules-Repo/top.gtian.hiderecent` 有 `repo` 权限 | 用于推送源码 & 创建 Release 到 LSPosed 模块仓库 |

> 🔒 Secrets 加密存储，workflow 里通过 `${{ secrets.XXX }}` 引用，日志中会自动打码。

### 6.2 完整 Release workflow 文件

```yaml
# .github/workflows/release.yml
name: Release

on:
  push:
    tags:
      - "v*"
  workflow_dispatch:
    inputs:
      tag:
        description: "Release tag (e.g. v26.10.25)"
        required: true
        type: string

permissions:
  contents: write

concurrency:
  group: release-${{ github.ref }}
  cancel-in-progress: true

jobs:
  release:
    runs-on: ubuntu-latest
    steps:
      - name: Checkout
        uses: actions/checkout@v4
        with:
          fetch-depth: 0

      - name: Set up JDK 17
        uses: actions/setup-java@v5
        with:
          distribution: temurin
          java-version: "17"
          cache: gradle

      - name: Set up Android SDK
        uses: android-actions/setup-android@v4

      - name: Install Android SDK packages
        run: |
          yes | sdkmanager --licenses
          sdkmanager \
            "platform-tools" \
            "platforms;android-35" \
            "build-tools;35.0.0"

      - name: Set up Gradle 8.9
        uses: gradle/actions/setup-gradle@v4
        with:
          gradle-version: "8.9"

      - name: Restore release keystore
        env:
          RELEASE_KEYSTORE_BASE64: ${{ secrets.RELEASE_KEYSTORE_BASE64 }}
        run: |
          mkdir -p keystore
          echo "$RELEASE_KEYSTORE_BASE64" | base64 -d > keystore/hiderecent-release.jks
          test -s keystore/hiderecent-release.jks || { echo "keystore empty"; exit 1; }
          head -c 4 keystore/hiderecent-release.jks | od -c | head -1

      - name: Write keystore.properties
        env:
          RELEASE_STORE_PASSWORD: ${{ secrets.RELEASE_STORE_PASSWORD }}
          RELEASE_KEY_ALIAS: ${{ secrets.RELEASE_KEY_ALIAS }}
          RELEASE_KEY_PASSWORD: ${{ secrets.RELEASE_KEY_PASSWORD }}
        run: |
          cat > keystore.properties <<EOF
          storeFile=keystore/hiderecent-release.jks
          storePassword=$RELEASE_STORE_PASSWORD
          keyAlias=$RELEASE_KEY_ALIAS
          keyPassword=$RELEASE_KEY_PASSWORD
          EOF
          sed -e 's/\(storePassword=\).*/\1***/' -e 's/\(keyPassword=\).*/\1***/' keystore.properties

      - name: Build release APK
        run: gradle :app:assembleRelease --no-daemon --stacktrace

      - name: Align and Sign APK (v1 + v2 + v3)
        env:
          RELEASE_STORE_PASSWORD: ${{ secrets.RELEASE_STORE_PASSWORD }}
          RELEASE_KEY_ALIAS: ${{ secrets.RELEASE_KEY_ALIAS }}
          RELEASE_KEY_PASSWORD: ${{ secrets.RELEASE_KEY_PASSWORD }}
        run: |
          APK=app/build/outputs/apk/release/app-release.apk
          ALIGNED_APK=app/build/outputs/apk/release/app-release-aligned.apk
          KS=keystore/hiderecent-release.jks
          # Step 1: zipalign (4-byte alignment, required before apksigner)
          $ANDROID_HOME/build-tools/35.0.0/zipalign -v 4 "$APK" "$ALIGNED_APK"
          # Step 2: apksigner signs v1 + v2 + v3 in one pass (no jarsigner)
          $ANDROID_HOME/build-tools/35.0.0/apksigner sign \
            --ks "$KS" --ks-key-alias "$RELEASE_KEY_ALIAS" \
            --ks-pass "pass:$RELEASE_STORE_PASSWORD" \
            --key-pass "pass:$RELEASE_KEY_PASSWORD" \
            --v1-signing-enabled true \
            --v2-signing-enabled true \
            --v3-signing-enabled true \
            --out "$APK" "$ALIGNED_APK"

      - name: Verify signing
        run: |
          APK=app/build/outputs/apk/release/app-release.apk
          ls -l "$APK"
          $ANDROID_HOME/build-tools/35.0.0/apksigner verify --verbose "$APK" | head -8

      - name: Upload release APK
        uses: softprops/action-gh-release@v2
        with:
          tag_name: ${{ inputs.tag || github.ref_name }}
          files: app/build/outputs/apk/release/app-release.apk
          generate_release_notes: true
          fail_on_unmatched_files: true

      - name: Sync source to LSPosed module repo
        env:
          GH_TOKEN: ${{ secrets.XPOSED_REPO_TOKEN }}
        run: |
          REPO=Xposed-Modules-Repo/top.gtian.hiderecent
          git config --unset-all http.https://github.com/.extraheader || true
          git remote add xposed "https://x-access-token:${GH_TOKEN}@github.com/${REPO}.git"
          git push xposed HEAD:main --force

      - name: Publish to LSPosed module repo
        env:
          GH_TOKEN: ${{ secrets.XPOSED_REPO_TOKEN }}
        run: |
          TAG="${{ inputs.tag || github.ref_name }}"
          APK=app/build/outputs/apk/release/app-release.apk
          REPO=Xposed-Modules-Repo/top.gtian.hiderecent
          gh release delete "$TAG" --repo "$REPO" --yes 2>/dev/null || true
          git config --unset-all http.https://github.com/.extraheader || true
          git push --delete xposed "$TAG" 2>/dev/null || true
          gh release create "$TAG" \
            --repo "$REPO" \
            --target main \
            --title "$TAG" \
            --notes "See https://github.com/GTian5418/HideRecent/releases/tag/$TAG" \
            "$APK"
```

### 6.3 Release workflow 关键点说明

| 步骤 | 作用 | 注意事项 |
| --- | --- | --- |
| `on.push.tags: "v*"` | 打 `v` 开头的 tag 自动触发 | 如 `v26.10.25` |
| `fetch-depth: 0` | 完整克隆 | 推送到 LSPosed 仓库需要完整历史 |
| `Align and Sign APK` | zipalign + apksigner 一步签名 | **不用 jarsigner**，避免破坏 ZIP LFH 结构 |
| `Upload release APK` | 上传到源仓库 GitHub Release | `tag_name` 用 `inputs.tag \|\| github.ref_name` |
| `Sync source to LSPosed` | 推送源码到 `Xposed-Modules-Repo/top.gtian.hiderecent` | 用 `XPOSED_REPO_TOKEN`，先 unset extraheader |
| `Publish to LSPosed` | 在 LSPosed 仓库创建 Release 并附 APK | 先删旧 release/tag，再创建新的 |

### 6.4 触发 Release

**方式一：打 tag 推送（推荐）**

```bash
git tag v26.10.25
git push origin v26.10.25
```

push 后 Actions 自动跑，完成后：
1. 源仓库 Release 页面出现 `v26.10.25`，附带签名好的 APK
2. LSPosed 模块仓库 `Xposed-Modules-Repo/top.gtian.hiderecent` 同步源码并创建 Release
3. LSPosed 索引服务扫描到新 Release 后，`modules.lsposed.org` 自动更新（有延迟，通常数小时内）

**方式二：手动触发**

1.  打开 `https://github.com/GTian5418/HideRecent/actions`
2.  左侧选 **Release** workflow → 右上 **Run workflow** → 填版本号 → **Run workflow**

> ⚠️ 手动触发时 `tag_name` 用你填的 `inputs.tag`，**不会**自动创建 git tag。

### 6.5 验证 Release 产物

Release 发布后，在 Release 页面下载 APK，本地验证签名：

```powershell
& "$env:ANDROID_HOME\build-tools\35.0.0\apksigner.bat" verify --verbose 下载的app-release.apk
```

应看到 `v1: true`、`v2: true`、`v3: true`。

* * *

## 7\. 安装到设备

```powershell
adb devices                                   # 确认设备已连接（需开 USB 调试）
adb install -r app\build\outputs\apk\release\app-release.apk
```

`-r` 表示覆盖安装（签名一致时才能成功）。

装完后在 **LSPosed / 现代 Xposed 管理器**里启用模块，并勾选作用域  
（系统框架 + 你的桌面），然后重启设备。详见 `README.md`。

* * *

## 8\. 常见报错速查

| 报错 | 原因 / 解决 |
| --- | --- |
| `'gradlew' 不是内部或外部命令` | 项目没带包装器。用本机 `gradle`，或先跑 `gradle wrapper`（见 3.A/B）。 |
| `SDK location not found` | 缺 `local.properties` 或 `sdk.dir` 路径错。见 2.2。 |
| `Unsupported class file major version` / `requires JDK 17` | `JAVA_HOME` 指向了 JDK 8/11。改成 JDK 17 并重开终端。 |
| `Plugin [id: 'com.android.application', version: '8.7.2'] was not found` | 网络问题或镜像不可达。检查网络，或临时在 `settings.gradle.kts` 里保留 `google()`。 |
| `Found item String/loading more than one time` | `strings.xml` 里有重复的字符串名，删掉重复项。 |
| `Keystore file not found` | `keystore.properties` 里的 `storeFile` 路径不对（应为相对项目根目录）。 |
| 编译卡在下载依赖 | 首次编译正常。可加 `--info` 看进度；确认腾讯云镜像可访问。 |
| 改了隐藏列表不生效 | 部分 ROM 缓存了任务列表，`adb shell am force-stop com.oplus.quickstep` 后重试。 |
| **CI**: `Failed to find package 'tools'` | `setup-android` 用了 v3。改成 `@v4`。见 5.2。 |
| **CI**: `GitHub Releases requires a tag` | 手动触发 release 没填 tag，或 `tag_name` 没配。见 6.2。 |
| **CI**: `keystore empty` / 解码失败 | `RELEASE_KEYSTORE_BASE64` secret 值不对。重新 base64 编码后粘贴，注意不要带换行。 |
| **CI**: 推送 LSPosed 仓库失败 | `XPOSED_REPO_TOKEN` 未设或权限不足。需对 `Xposed-Modules-Repo/top.gtian.hiderecent` 有 `repo` 权限。 |
| **LSPosed 索引不更新** | `tag.yml` 成功但 `modules.lsposed.org` 还显示旧版？索引由 Cloudflare Pages 后端扫描服务定时生成，有数小时延迟，等即可。 |

* * *

## 9\. 环境自检清单

编译前逐条确认：

-   \[ \] `java -version` → 17.x
-   \[ \] `adb version` → 能输出版本号
-   \[ \] `gradle -v` → 8.7 ~ 8.9（或项目里有 `gradlew.bat`）
-   \[ \] 项目根目录存在 `local.properties`，且 `sdk.dir` 指向真实 SDK
-   \[ \] `$env:ANDROID_HOME\platforms\android-35` 目录存在
-   \[ \] `$env:ANDROID_HOME\build-tools\35.0.0` 目录存在
-   \[ \] 要出正式包：根目录存在 `keystore.properties` 且指向有效 keystore
-   \[ \] 要用 CI/CD：GitHub 仓库已设 5 个 Actions secrets（见 6.1.2）

全绿即可执行：

```powershell
gradle assembleRelease
# 然后 zipalign + apksigner 一步签名（见 4.4）
```

或直接打 tag 让 CI 自动出包：

```bash
git tag v26.10.25
git push origin v26.10.25
```
