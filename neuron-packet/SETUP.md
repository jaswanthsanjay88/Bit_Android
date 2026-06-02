# Neuron Packet Module Setup

## OpenSSL Setup (Required)

The neuron-packet module requires OpenSSL prebuilt libraries. Follow these steps:

### Option 1: Download Prebuilt (Recommended)

```bash
# Download OpenSSL 3.x prebuilt for Android
# Place in neuron-packet/src/main/jniLibs/

# For arm64-v8a:
neuron-packet/src/main/jniLibs/arm64-v8a/libcrypto.so
neuron-packet/src/main/jniLibs/arm64-v8a/libssl.so

# For x86_64:
neuron-packet/src/main/jniLibs/x86_64/libcrypto.so
neuron-packet/src/main/jniLibs/x86_64/libssl.so
```

Download from: https://github.com/prabhakarlab/openssl-android/releases

### Option 2: Build from Source

```bash
# Clone OpenSSL
git clone https://github.com/openssl/openssl.git
cd openssl

# For arm64-v8a
export ANDROID_NDK_ROOT=/path/to/ndk
./Configure android-arm64 -D__ANDROID_API__=27
make

# Copy libs
cp libcrypto.so libssl.so /path/to/neuron-packet/src/main/jniLibs/arm64-v8a/
```

### OpenSSL Headers

Copy OpenSSL headers to:
```
neuron-packet/src/main/cpp/third_party/openssl/include/openssl/
```

Required headers:
- evp.h
- sha.h
- rand.h
- des.h
- err.h
- opensslv.h
- (and their dependencies)

## Directory Structure

```
neuron-packet/
├── src/main/
│   ├── cpp/
│   │   ├── third_party/
│   │   │   ├── lz4/          (included)
│   │   │   └── openssl/
│   │   │       └── include/
│   │   │           └── openssl/  (add headers here)
│   │   ├── crypto/
│   │   ├── packet/
│   │   └── utils/
│   ├── jniLibs/
│   │   ├── arm64-v8a/
│   │   │   ├── libcrypto.so  (add this)
│   │   │   └── libssl.so     (add this)
│   │   └── x86_64/
│   │       ├── libcrypto.so  (add this)
│   │       └── libssl.so     (add this)
│   └── java/com/neuronpacket/
```

## Usage

```kotlin
val manager = NeuronPacketManager()

// Export
val result = manager.export(
    outputFile = File("knowledge.neuron"),
    metadata = PacketMetadata(name = "My Data"),
    payload = data,
    config = ExportConfig(adminPassword = "secure123")
)
println("Recovery Key: ${result.getOrNull()?.recoveryKey}")

// Import
manager.open(File("knowledge.neuron"))
val session = manager.authenticate("secure123").getOrThrow()
val payload = manager.decryptPayload(session).getOrThrow()

// Close
manager.close()
```